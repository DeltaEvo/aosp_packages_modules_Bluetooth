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
#pragma once

#include "osi/include/alarm.h"
#include "stack/include/bt_device_type.h"
#include "stack/include/bt_name.h"
#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "types/raw_address.h"

/* Structure returned with remote name  request */
typedef struct {
  tBTM_STATUS status;
  RawAddress bd_addr;
  BD_NAME remote_bd_name;
  tHCI_STATUS hci_status;
} tBTM_REMOTE_DEV_NAME;

typedef void(tBTM_NAME_CMPL_CB)(const tBTM_REMOTE_DEV_NAME*);

namespace bluetooth {
namespace rnr {

class RemoteNameRequest {
 public:
  tBTM_NAME_CMPL_CB* p_remname_cmpl_cb{nullptr};
  alarm_t* remote_name_timer{nullptr};
  RawAddress remname_bda{}; /* Name of bd addr for active remote name request */
  bool remname_active{
      false}; /* State of a remote name request by external API */
  tBT_DEVICE_TYPE remname_dev_type{
      BT_DEVICE_TYPE_UNKNOWN}; /* Whether it's LE or BREDR name request */
};

}  // namespace rnr
}  // namespace bluetooth
