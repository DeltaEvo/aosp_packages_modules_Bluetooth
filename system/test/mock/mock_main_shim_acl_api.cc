/*
 * Copyright 2020 The Android Open Source Project
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
 *   Functions generated:5
 */

#include <map>
#include <string>

extern std::map<std::string, int> mock_function_count_map;

#include <cstddef>
#include <cstdint>

#include "main/shim/acl_api.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/bt_octets.h"
#include "types/ble_address_with_type.h"
#include "types/raw_address.h"

#ifndef UNUSED_ATTR
#define UNUSED_ATTR
#endif

void bluetooth::shim::ACL_CreateClassicConnection(
    const RawAddress& raw_address) {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_CancelClassicConnection(
    const RawAddress& raw_address) {
  mock_function_count_map[__func__]++;
}
bool bluetooth::shim::ACL_AcceptLeConnectionFrom(
    const tBLE_BD_ADDR& legacy_address_with_type, bool is_direct) {
  mock_function_count_map[__func__]++;
  return true;
}
void bluetooth::shim::ACL_IgnoreLeConnectionFrom(
    const tBLE_BD_ADDR& legacy_address_with_type) {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_ConfigureLePrivacy(bool is_le_privacy_enabled) {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_WriteData(uint16_t handle, BT_HDR* p_buf) {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_Disconnect(uint16_t handle, bool is_classic,
                                     tHCI_STATUS reason, std::string comment) {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_IgnoreAllLeConnections() {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_ReadConnectionAddress(const RawAddress& pseudo_addr,
                                                RawAddress& conn_addr,
                                                tBLE_ADDR_TYPE* p_addr_type) {
  mock_function_count_map[__func__]++;
}

void bluetooth::shim::ACL_AddToAddressResolution(
    const tBLE_BD_ADDR& legacy_address_with_type, const Octet16& peer_irk,
    const Octet16& local_irk) {
  mock_function_count_map[__func__]++;
}

void bluetooth::shim::ACL_RemoveFromAddressResolution(
    const tBLE_BD_ADDR& legacy_address_with_type) {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_ClearAddressResolution() {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_LeSetDefaultSubrate(uint16_t subrate_min,
                                              uint16_t subrate_max,
                                              uint16_t max_latency,
                                              uint16_t cont_num,
                                              uint16_t sup_tout) {
  mock_function_count_map[__func__]++;
}
void bluetooth::shim::ACL_LeSubrateRequest(
    uint16_t hci_handle, uint16_t subrate_min, uint16_t subrate_max,
    uint16_t max_latency, uint16_t cont_num, uint16_t sup_tout) {
  mock_function_count_map[__func__]++;
}
