/******************************************************************************
 *
 *  Copyright 1999-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 *
 *  this file contains the main Bluetooth Manager (BTM) internal
 *  definitions.
 *
 ******************************************************************************/

#ifndef BTM_BLE_INT_H
#define BTM_BLE_INT_H

#include "bt_target.h"
#include "btm_ble_api.h"
#include "btm_ble_int_types.h"
#include "btm_int_types.h"
#include "smp_api.h"
#include "stack/include/hci_error_code.h"
#include "types/ble_address_with_type.h"
#include "types/raw_address.h"

void btm_ble_process_periodic_adv_sync_est_evt(uint8_t len, const uint8_t* p);
void btm_ble_process_periodic_adv_pkt(uint8_t len, const uint8_t* p);
void btm_ble_process_periodic_adv_sync_lost_evt(uint8_t len, uint8_t* p);
void btm_send_hci_set_scan_params(uint8_t scan_type, uint16_t scan_int,
                                  uint16_t scan_win,
                                  tBLE_ADDR_TYPE addr_type_own,
                                  uint8_t scan_filter_policy);

void btm_ble_init(void);
void btm_ble_free();
void btm_ble_connected(const RawAddress& bda, uint16_t handle, uint8_t enc_mode,
                       uint8_t role, tBLE_ADDR_TYPE addr_type,
                       bool addr_matched,
                       bool can_read_discoverable_characteristics);

/* LE security function from btm_sec.cc */
void btm_ble_link_sec_check(const RawAddress& bd_addr,
                            tBTM_LE_AUTH_REQ auth_req,
                            tBTM_BLE_SEC_REQ_ACT* p_sec_req_act);
void btm_ble_ltk_request_reply(const RawAddress& bda, bool use_stk,
                               const Octet16& stk);
tBTM_STATUS btm_proc_smp_cback(tSMP_EVT event, const RawAddress& bd_addr,
                               const tSMP_EVT_DATA* p_data);
tBTM_STATUS btm_ble_set_encryption(const RawAddress& bd_addr,
                                   tBTM_BLE_SEC_ACT sec_act, uint8_t link_role);
tBTM_STATUS btm_ble_start_encrypt(const RawAddress& bda, bool use_stk,
                                  Octet16* p_stk);
void btm_ble_link_encrypted(const RawAddress& bd_addr, uint8_t encr_enable);

/* LE device management functions */
void btm_ble_reset_id(void);

bool btm_get_local_div(const RawAddress& bd_addr, uint16_t* p_div);
bool btm_ble_get_enc_key_type(const RawAddress& bd_addr, uint8_t* p_key_types);

void btm_sec_save_le_key(const RawAddress& bd_addr, tBTM_LE_KEY_TYPE key_type,
                         tBTM_LE_KEY_VALUE* p_keys, bool pass_to_application);
void btm_ble_update_sec_key_size(const RawAddress& bd_addr,
                                 uint8_t enc_key_size);
uint8_t btm_ble_read_sec_key_size(const RawAddress& bd_addr);

/* acceptlist function */
void btm_update_scanner_filter_policy(tBTM_BLE_SFP scan_policy);

/* background connection function */
bool btm_ble_suspend_bg_conn(void);
bool btm_ble_resume_bg_conn(void);
void btm_ble_update_mode_operation(uint8_t link_role, const RawAddress* bda,
                                   tHCI_STATUS status);
/* BLE address management */
void btm_gen_resolvable_private_addr(
    base::Callback<void(const RawAddress& rpa)> cb);

tBTM_SEC_DEV_REC* btm_ble_resolve_random_addr(const RawAddress& random_bda);
void btm_gen_resolve_paddr_low(const RawAddress& address);

void btm_ble_batchscan_init(void);
void btm_ble_adv_filter_init(void);
bool btm_ble_topology_check(tBTM_BLE_STATE_MASK request);
bool btm_ble_clear_topology_mask(tBTM_BLE_STATE_MASK request_state);
bool btm_ble_set_topology_mask(tBTM_BLE_STATE_MASK request_state);

void btm_ble_scanner_init(void);
void btm_ble_scanner_cleanup(void);

#endif
