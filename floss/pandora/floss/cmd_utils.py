# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import atexit
import itertools
import logging
import os
import pipes
import pwd
import select
import subprocess
import threading

TEE_TO_LOGS = object()
_popen_lock = threading.Lock()
_logging_service = None
_command_serial_number = itertools.count(1)

_LOG_BUFSIZE = 4096
_PIPE_CLOSED = -1


class _LoggerProxy(object):

    def __init__(self, logger):
        self._logger = logger

    def fileno(self):
        """Returns the fileno of the logger pipe."""
        return self._logger._pipe[1]

    def __del__(self):
        self._logger.close()


class _PipeLogger(object):

    def __init__(self, level, prefix):
        self._pipe = list(os.pipe())
        self._level = level
        self._prefix = prefix

    def close(self):
        """Closes the logger."""
        if self._pipe[1] != _PIPE_CLOSED:
            os.close(self._pipe[1])
            self._pipe[1] = _PIPE_CLOSED


class _LoggingService(object):

    def __init__(self):
        # Python's list is thread safe
        self._loggers = []

        # Change tuple to list so that we can change the value when
        # closing the pipe.
        self._pipe = list(os.pipe())
        self._thread = threading.Thread(target=self._service_run)
        self._thread.daemon = True
        self._thread.start()

    def _service_run(self):
        terminate_loop = False
        while not terminate_loop:
            rlist = [l._pipe[0] for l in self._loggers]
            rlist.append(self._pipe[0])
            for r in select.select(rlist, [], [])[0]:
                data = os.read(r, _LOG_BUFSIZE)
                if r != self._pipe[0]:
                    self._output_logger_message(r, data)
                elif len(data) == 0:
                    terminate_loop = True
        # Release resources.
        os.close(self._pipe[0])
        for logger in self._loggers:
            os.close(logger._pipe[0])

    def _output_logger_message(self, r, data):
        logger = next(l for l in self._loggers if l._pipe[0] == r)

        if len(data) == 0:
            os.close(logger._pipe[0])
            self._loggers.remove(logger)
            return

        for line in data.split('\n'):
            logging.log(logger._level, '%s%s', logger._prefix, line)

    def create_logger(self, level=logging.DEBUG, prefix=''):
        """Creates a new logger.

        Args:
            level: The desired logging level.
            prefix: The prefix to add to each log entry.
        """
        logger = _PipeLogger(level=level, prefix=prefix)
        self._loggers.append(logger)
        os.write(self._pipe[1], '\0')
        return _LoggerProxy(logger)

    def shutdown(self):
        """Shuts down the logger."""
        if self._pipe[1] != _PIPE_CLOSED:
            os.close(self._pipe[1])
            self._pipe[1] = _PIPE_CLOSED
            self._thread.join()


def create_logger(level=logging.DEBUG, prefix=''):
    """Creates a new logger.

    Args:
        level: The desired logging level.
        prefix: The prefix to add to each log entry.
    """
    global _logging_service
    if _logging_service is None:
        _logging_service = _LoggingService()
        atexit.register(_logging_service.shutdown)
    return _logging_service.create_logger(level=level, prefix=prefix)


def wait_and_check_returncode(*popens):
    """Waits for all the Popens and check the return code is 0.

    Args:
        popens: The Popens to be checked.

    Raises:
        RuntimeError if the return code is not 0.
    """
    error_message = None
    for p in popens:
        if p.wait() != 0:
            error_message = ('Command failed(%d, %d): %s' % (p.pid, p.returncode, p.command))
            logging.error(error_message)
    if error_message:
        raise RuntimeError(error_message)


def execute(args, stdin=None, stdout=TEE_TO_LOGS, stderr=TEE_TO_LOGS, run_as=None):
    """Executes a child command and wait for it.

    Args:
        args: The command to be executed.
        stdin: The executed program's standard input.
        stdout: The executed program's standard output.
        stderr: The executed program's standard error.
        run_as: If not None, run the command as the given user.

    Returns:
        The output from standard output if 'stdout' is subprocess.PIPE.

    Raises:
        RuntimeException if the return code of the child command is not 0.
    """
    ps = popen(args, stdin=stdin, stdout=stdout, stderr=stderr, run_as=run_as)
    out = ps.communicate()[0] if stdout == subprocess.PIPE else None
    wait_and_check_returncode(ps)
    return out


def _run_as(user):
    """Changes the uid and gid of the running process to be that of the
    given user and configures its supplementary groups.

    Don't call this function directly, instead wrap it in a lambda and
    pass that to the preexec_fn argument of subprocess.Popen.

    Example usage:
    subprocess.Popen(..., preexec_fn=lambda: _run_as('chronos'))

    Args:
        user: The user to run as.
    """
    pw = pwd.getpwnam(user)
    os.setgid(pw.pw_gid)
    os.initgroups(user, pw.pw_gid)
    os.setuid(pw.pw_uid)


def popen(args, stdin=None, stdout=TEE_TO_LOGS, stderr=TEE_TO_LOGS, env=None, run_as=None):
    """Returns a Popen object just as subprocess.Popen does but with the
    executed command stored in Popen.command.

    Args:
        args: The command to be executed.
        stdin: The executed program's standard input.
        stdout: The executed program's standard output.
        stderr: The executed program's standard error.
        env: The executed program's environment.
        run_as: If not None, run the command as the given user.
    """
    command_id = next(_command_serial_number)
    prefix = '[%04d] ' % command_id

    if stdout is TEE_TO_LOGS:
        stdout = create_logger(level=logging.DEBUG, prefix=prefix)
    if stderr is TEE_TO_LOGS:
        stderr = create_logger(level=logging.ERROR, prefix=prefix)

    command = ' '.join(pipes.quote(x) for x in args)
    logging.info('%sRunning: %s', prefix, command)

    preexec_fn = None
    if run_as is not None:
        preexec_fn = lambda: _run_as(run_as)

    # The lock is required for http://crbug.com/323843.
    with _popen_lock:
        ps = subprocess.Popen(args, stdin=stdin, stdout=stdout, stderr=stderr, env=env, preexec_fn=preexec_fn)

    logging.info('%spid is %d', prefix, ps.pid)
    ps.command_id = command_id
    ps.command = command
    return ps
