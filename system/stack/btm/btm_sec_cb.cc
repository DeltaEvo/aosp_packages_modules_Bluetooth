/*
 * Copyright 2023 The Android Open Source Project
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
 */

#include "stack/btm/btm_sec_cb.h"

#include <cstdint>

#include "osi/include/allocator.h"
#include "osi/include/fixed_queue.h"
#include "osi/include/list.h"
#include "stack/btm/security_device_record.h"
#include "stack_config.h"
#include "types/raw_address.h"

void tBTM_SEC_CB::Init(uint8_t initial_security_mode) {
  memset(&cfg, 0, sizeof(cfg));
  memset(&devcb, 0, sizeof(devcb));
  memset(&enc_rand, 0, sizeof(enc_rand));
  memset(&api, 0, sizeof(api));
  memset(&pin_code, 0, sizeof(pin_code));
  memset(sec_serv_rec, 0, sizeof(sec_serv_rec));
  connecting_bda = RawAddress::kEmpty;
  memset(&connecting_dc, 0, sizeof(connecting_dc));

  sec_pending_q = fixed_queue_new(SIZE_MAX);
  sec_collision_timer = alarm_new("btm.sec_collision_timer");
  pairing_timer = alarm_new("btm.pairing_timer");
  execution_wait_timer = alarm_new("btm.execution_wait_timer");

  security_mode = initial_security_mode;
  pairing_bda = RawAddress::kAny;
  sec_dev_rec = list_new([](void* ptr) {
    // Invoke destructor for all record objects and reset to default
    // initialized value so memory may be properly freed
    *((tBTM_SEC_DEV_REC*)ptr) = {};
    osi_free(ptr);
  });
}

void tBTM_SEC_CB::Free() {
  fixed_queue_free(sec_pending_q, nullptr);
  sec_pending_q = nullptr;

  list_free(sec_dev_rec);
  sec_dev_rec = nullptr;

  alarm_free(sec_collision_timer);
  sec_collision_timer = nullptr;

  alarm_free(pairing_timer);
  pairing_timer = nullptr;

  alarm_free(execution_wait_timer);
  execution_wait_timer = nullptr;
}

tBTM_SEC_CB btm_sec_cb;

void BTM_Sec_Init() {
  btm_sec_cb.Init(stack_config_get_interface()->get_pts_secure_only_mode()
                      ? BTM_SEC_MODE_SC
                      : BTM_SEC_MODE_SP);
}

void BTM_Sec_Free() { btm_sec_cb.Free(); }
