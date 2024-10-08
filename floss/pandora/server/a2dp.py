# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""A2DP grpc interface."""

import asyncio
import json
import logging
from typing import AsyncGenerator
from typing import AsyncIterator

from floss.pandora.floss import audio_utils
from floss.pandora.floss import cras_utils
from floss.pandora.floss import media_client
from floss.pandora.floss import utils
from floss.pandora.server import bluetooth as bluetooth_module
from google.protobuf import wrappers_pb2, empty_pb2
import grpc
from pandora import a2dp_grpc_aio
from pandora import a2dp_pb2


class A2DPService(a2dp_grpc_aio.A2DPServicer):
    """Service to trigger Bluetooth A2DP procedures.

    This class implements the Pandora bluetooth test interfaces,
    where the meta class definition is automatically generated by the protobuf.
    The interface definition can be found in:
    https://cs.android.com/android/platform/superproject/+/main:external/pandora/bt-test-interfaces/pandora/a2dp.proto
    """

    def __init__(self, bluetooth: bluetooth_module.Bluetooth):
        self.bluetooth = bluetooth
        self._cras_test_client = cras_utils.CrasTestClient()
        cras_utils.set_floss_enabled(True)

    async def OpenSource(self, request: a2dp_pb2.OpenSourceRequest,
                         context: grpc.ServicerContext) -> a2dp_pb2.OpenSourceResponse:

        class ConnectionObserver(media_client.BluetoothMediaCallbacks):
            """Observer to observe the A2DP profile connection state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_bluetooth_audio_device_added(self, remote_device):
                if remote_device['address'] != self.task['address']:
                    return

                future = self.task['open_source']
                future.get_loop().call_soon_threadsafe(future.set_result, True)

        connection = utils.connection_from(request.connection)
        address = connection.address
        connected_devices = self.bluetooth.get_connected_audio_devices()

        if not self.bluetooth.is_connected(address) or address not in connected_devices:
            try:
                open_source = asyncio.get_running_loop().create_future()
                observer = ConnectionObserver({'open_source': open_source, 'address': address})
                name = utils.create_observer_name(observer)
                self.bluetooth.media_client.register_callback_observer(name, observer)
                self.bluetooth.connect_device(address)
                success = await asyncio.wait_for(open_source, timeout=10)

                if not success:
                    await context.abort(grpc.StatusCode.UNKNOWN, f'Failed to connect to the address {address}.')
            except asyncio.TimeoutError as e:
                logging.error(f'OpenSource: timeout for waiting A2DP connection. {e}')
            finally:
                self.bluetooth.media_client.unregister_callback_observer(name, observer)

        cookie = utils.address_to(address)
        return a2dp_pb2.OpenSourceResponse(source=a2dp_pb2.Source(cookie=cookie))

    async def OpenSink(self, request: a2dp_pb2.OpenSinkRequest,
                       context: grpc.ServicerContext) -> a2dp_pb2.OpenSinkResponse:

        context.set_code(grpc.StatusCode.UNIMPLEMENTED)  # type: ignore
        context.set_details('Method not implemented!')  # type: ignore
        raise NotImplementedError('Method not implemented!')

    async def WaitSource(self, request: a2dp_pb2.WaitSourceRequest,
                         context: grpc.ServicerContext) -> a2dp_pb2.WaitSourceResponse:

        class ConnectionObserver(media_client.BluetoothMediaCallbacks):
            """Observer to observe the A2DP profile connection state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_bluetooth_audio_device_added(self, remote_device):
                if remote_device['address'] != self.task['address']:
                    return

                future = self.task['wait_source']
                future.get_loop().call_soon_threadsafe(future.set_result, address)

        connection = utils.connection_from(request.connection)
        address = connection.address
        if not address:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, 'Request address field must be set.')

        connected_devices = self.bluetooth.get_connected_audio_devices()
        if not self.bluetooth.is_connected(address) or address not in connected_devices:
            try:
                wait_source = asyncio.get_running_loop().create_future()
                observer = ConnectionObserver({'wait_source': wait_source, 'address': address})
                name = utils.create_observer_name(observer)
                self.bluetooth.media_client.register_callback_observer(name, observer)
                await asyncio.wait_for(wait_source, timeout=10)
            except asyncio.TimeoutError as e:
                logging.error(f'WaitSource: timeout for waiting A2DP connection. {e}')
            finally:
                self.bluetooth.media_client.unregister_callback_observer(name, observer)

        cookie = utils.address_to(address)
        return a2dp_pb2.WaitSourceResponse(source=a2dp_pb2.Source(cookie=cookie))

    async def WaitSink(self, request: a2dp_pb2.WaitSinkRequest,
                       context: grpc.ServicerContext) -> a2dp_pb2.WaitSinkResponse:

        context.set_code(grpc.StatusCode.UNIMPLEMENTED)  # type: ignore
        context.set_details('Method not implemented!')  # type: ignore
        raise NotImplementedError('Method not implemented!')

    async def IsSuspended(self, request: a2dp_pb2.IsSuspendedRequest,
                          context: grpc.ServicerContext) -> wrappers_pb2.BoolValue:

        address = utils.address_from(request.target.cookie)
        connected_audio_devices = self.bluetooth.get_connected_audio_devices()
        if address not in connected_audio_devices:
            await context.abort(grpc.StatusCode.FAILED_PRECONDITION,
                                'A2dp device is not connected, cannot get suspend state')

        is_suspended = cras_utils.get_active_stream_count() == 0
        return wrappers_pb2.BoolValue(value=is_suspended)

    async def Start(self, request: a2dp_pb2.StartRequest, context: grpc.ServicerContext) -> a2dp_pb2.StartResponse:

        target = request.WhichOneof('target')
        address = utils.address_from(request.target.cookie)
        connected_audio_devices = self.bluetooth.get_connected_audio_devices()
        if address not in connected_audio_devices:
            await context.abort(grpc.StatusCode.FAILED_PRECONDITION, 'A2dp device is not connected, cannot start')

        audio_data = json.dumps(audio_utils.A2DP_TEST_DATA)
        audio_data = json.loads(audio_data)
        audio_utils.generate_playback_file(audio_data)

        if not audio_utils.select_audio_output_node():
            await context.abort(grpc.StatusCode.UNKNOWN, 'Failed to select audio output node')

        if target == 'source':
            self._cras_test_client.start_playing_subprocess(audio_data['file'],
                                                            channels=audio_data['channels'],
                                                            rate=audio_data['rate'],
                                                            duration=audio_data['duration'])
        else:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, f'Invalid target type: {target}.')

        return a2dp_pb2.StartResponse(started=empty_pb2.Empty())

    async def Suspend(self, request: a2dp_pb2.SuspendRequest,
                      context: grpc.ServicerContext) -> a2dp_pb2.SuspendResponse:

        target = request.WhichOneof('target')
        address = utils.address_from(request.target.cookie)
        connected_audio_devices = self.bluetooth.get_connected_audio_devices()
        if address not in connected_audio_devices:
            await context.abort(grpc.StatusCode.FAILED_PRECONDITION, 'A2dp device is not connected, cannot suspend')

        if cras_utils.get_active_stream_count() == 0:
            await context.abort(grpc.StatusCode.FAILED_PRECONDITION, 'A2dp Device is already suspended, cannot suspend')

        if target == 'source':
            self._cras_test_client.stop_playing_subprocess()
        else:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, f'Invalid target type: {target}.')

        return a2dp_pb2.SuspendResponse(suspended=empty_pb2.Empty())

    async def Close(self, request: a2dp_pb2.CloseRequest, context: grpc.ServicerContext) -> a2dp_pb2.CloseResponse:

        class ConnectionObserver(media_client.BluetoothMediaCallbacks):
            """Observer to observe the A2DP profile connection state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_bluetooth_audio_device_removed(self, address):
                if address != self.task['address']:
                    return

                future = self.task['close_stream']
                future.get_loop().call_soon_threadsafe(future.set_result, address)

        address = utils.address_from(request.target.cookie)
        connected_audio_devices = self.bluetooth.get_connected_audio_devices()
        if address not in connected_audio_devices:
            await context.abort(grpc.StatusCode.FAILED_PRECONDITION, 'A2dp device is not connected, cannot close')

        try:
            close_stream = asyncio.get_running_loop().create_future()
            observer = ConnectionObserver({'close_stream': close_stream, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.media_client.register_callback_observer(name, observer)
            self.bluetooth.disconnect_media(address)
            await close_stream
        finally:
            self.bluetooth.media_client.unregister_callback_observer(name, observer)
        return a2dp_pb2.CloseResponse()

    async def GetAudioEncoding(self, request: a2dp_pb2.GetAudioEncodingRequest,
                               context: grpc.ServicerContext) -> a2dp_pb2.GetAudioEncodingResponse:
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)  # type: ignore
        context.set_details('Method not implemented!')  # type: ignore
        raise NotImplementedError('Method not implemented!')

    async def PlaybackAudio(self, request: AsyncIterator[a2dp_pb2.PlaybackAudioRequest],
                            context: grpc.ServicerContext) -> a2dp_pb2.PlaybackAudioResponse:

        audio_signals = request
        logging.info('PlaybackAudio: Wait for audio signal...')

        audio_signal = await utils.anext(audio_signals)
        audio_data = audio_signal.data

        if cras_utils.get_active_stream_count() == 0:
            await context.abort(grpc.StatusCode.FAILED_PRECONDITION, 'Audio track is not started')

        audio_utils.generate_playback_file_from_binary_data(audio_data)
        audio_file = audio_utils.A2DP_PLAYBACK_DATA['file']
        self._cras_test_client.play(audio_file)
        return a2dp_pb2.PlaybackAudioResponse()

    async def CaptureAudio(self, request: a2dp_pb2.CaptureAudioRequest,
                           context: grpc.ServicerContext) -> AsyncGenerator[a2dp_pb2.CaptureAudioResponse, None]:
        context.set_code(grpc.StatusCode.UNIMPLEMENTED)  # type: ignore
        context.set_details('Method not implemented!')  # type: ignore
        raise NotImplementedError('Method not implemented!')
