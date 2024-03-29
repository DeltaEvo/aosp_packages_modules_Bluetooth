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

#include <cstdint>
#include <functional>

// Original included files, if any

// Mocked compile conditionals, if any
namespace test {
namespace mock {
namespace stack_btm_ble_scanner_hci_interface {

// Name: btm_ble_process_periodic_adv_sync_est_evt
// Params: uint8_t data_len, uint8_t* data
// Returns: void
struct btm_ble_process_periodic_adv_sync_est_evt {
  std::function<void(uint8_t data_len, const uint8_t* data)> body{
      [](uint8_t data_len, const uint8_t* data) {}};
  void operator()(uint8_t data_len, const uint8_t* data) {
    body(data_len, data);
  };
};
extern struct btm_ble_process_periodic_adv_sync_est_evt
    btm_ble_process_periodic_adv_sync_est_evt;
// Name: btm_ble_process_periodic_adv_pkt
// Params: uint8_t data_len, uint8_t* data
// Returns: void
struct btm_ble_process_periodic_adv_pkt {
  std::function<void(uint8_t data_len, const uint8_t* data)> body{
      [](uint8_t data_len, const uint8_t* data) {}};
  void operator()(uint8_t data_len, const uint8_t* data) {
    body(data_len, data);
  };
};
extern struct btm_ble_process_periodic_adv_pkt btm_ble_process_periodic_adv_pkt;
// Name: btm_ble_process_periodic_adv_sync_lost_evt
// Params: uint8_t data_len, uint8_t* data
// Returns: void
struct btm_ble_process_periodic_adv_sync_lost_evt {
  std::function<void(uint8_t data_len, uint8_t* data)> body{
      [](uint8_t data_len, uint8_t* data) {}};
  void operator()(uint8_t data_len, uint8_t* data) { body(data_len, data); };
};
extern struct btm_ble_process_periodic_adv_sync_lost_evt
    btm_ble_process_periodic_adv_sync_lost_evt;

}  // namespace stack_btm_ble_scanner_hci_interface
}  // namespace mock
}  // namespace test

// END mockcify generation
