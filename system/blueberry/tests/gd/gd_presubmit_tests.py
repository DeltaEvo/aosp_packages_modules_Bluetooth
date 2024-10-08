#!/usr/bin/env python3
#
#   Copyright 2021 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

from blueberry.tests.gd.gd_all_tests import ALL_TESTS

from mobly import suite_runner

# TODO(b/194723246): Investigate failures to re-activate the test class.
from blueberry.tests.gd.hci.le_scanning_manager_test import LeScanningManagerTest

DISABLED_TESTS = {LeScanningManagerTest}

PRESUBMIT_TESTS = list(ALL_TESTS - DISABLED_TESTS)

if __name__ == '__main__':
    suite_runner.run_suite(PRESUBMIT_TESTS)
