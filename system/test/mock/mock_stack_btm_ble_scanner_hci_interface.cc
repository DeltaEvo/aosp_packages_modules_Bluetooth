/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Generated mock file from original source file
 *   Functions generated:6
 *
 *  mockcify.pl ver 0.2
 */
// Mock include file to share data between tests and mock
#include "test/mock/mock_stack_btm_ble_scanner_hci_interface.h"

#include <cstdint>

// Original included files, if any

#include "test/common/mock_functions.h"

// Mocked compile conditionals, if any
// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_btm_ble_scanner_hci_interface {

// Function state capture and return values, if needed
struct btm_ble_process_periodic_adv_sync_est_evt
    btm_ble_process_periodic_adv_sync_est_evt;
struct btm_ble_process_periodic_adv_pkt btm_ble_process_periodic_adv_pkt;
struct btm_ble_process_periodic_adv_sync_lost_evt
    btm_ble_process_periodic_adv_sync_lost_evt;

}  // namespace stack_btm_ble_scanner_hci_interface
}  // namespace mock
}  // namespace test

// Mocked functions, if any
void btm_ble_process_periodic_adv_sync_est_evt(uint8_t data_len,
                                               const uint8_t* data) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_ble_scanner_hci_interface::
      btm_ble_process_periodic_adv_sync_est_evt(data_len, data);
}
void btm_ble_process_periodic_adv_pkt(uint8_t data_len, const uint8_t* data) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_ble_scanner_hci_interface::
      btm_ble_process_periodic_adv_pkt(data_len, data);
}
void btm_ble_process_periodic_adv_sync_lost_evt(uint8_t data_len,
                                                uint8_t* data) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_ble_scanner_hci_interface::
      btm_ble_process_periodic_adv_sync_lost_evt(data_len, data);
}

// END mockcify generation
