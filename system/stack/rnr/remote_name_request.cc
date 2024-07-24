/*
 * Copyright 2024 The Android Open Source Project
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

#include "stack/rnr/remote_name_request.h"

#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>

#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/security_device_record.h"

extern tBTM_CB btm_cb;
using namespace bluetooth;

bool BTM_SecAddRmtNameNotifyCallback(tBTM_RMT_NAME_CALLBACK* p_callback) {
  int i;

  for (i = 0; i < BTM_SEC_MAX_RMT_NAME_CALLBACKS; i++) {
    if (btm_cb.rnr.p_rmt_name_callback[i] == NULL) {
      btm_cb.rnr.p_rmt_name_callback[i] = p_callback;
      return true;
    }
  }

  return false;
}

bool BTM_SecDeleteRmtNameNotifyCallback(tBTM_RMT_NAME_CALLBACK* p_callback) {
  int i;

  for (i = 0; i < BTM_SEC_MAX_RMT_NAME_CALLBACKS; i++) {
    if (btm_cb.rnr.p_rmt_name_callback[i] == p_callback) {
      btm_cb.rnr.p_rmt_name_callback[i] = NULL;
      return true;
    }
  }

  return false;
}

bool BTM_IsRemoteNameKnown(const RawAddress& bd_addr, tBT_TRANSPORT /* transport */) {
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev(bd_addr);
  return (p_dev_rec == nullptr) ? false : p_dev_rec->sec_rec.is_name_known();
}
