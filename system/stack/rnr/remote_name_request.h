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
#include "stack/include/security_client_callbacks.h"
#include "types/raw_address.h"

/* Structure returned with remote name  request */
struct tBTM_REMOTE_DEV_NAME {
  tBTM_STATUS status;
  RawAddress bd_addr;
  BD_NAME remote_bd_name;
  tHCI_STATUS hci_status;
};

typedef void(tBTM_NAME_CMPL_CB)(const tBTM_REMOTE_DEV_NAME*);

namespace bluetooth {
namespace rnr {

class RemoteNameRequest {
public:
  tBTM_NAME_CMPL_CB* p_remname_cmpl_cb{nullptr};
  alarm_t* remote_name_timer{nullptr};
  RawAddress remname_bda{};   /* Name of bd addr for active remote name request */
  bool remname_active{false}; /* State of a remote name request by external API */
  tBT_DEVICE_TYPE remname_dev_type{
          BT_DEVICE_TYPE_UNKNOWN}; /* Whether it's LE or BREDR name request */
#define BTM_SEC_MAX_RMT_NAME_CALLBACKS 2
  tBTM_RMT_NAME_CALLBACK* p_rmt_name_callback[BTM_SEC_MAX_RMT_NAME_CALLBACKS]{nullptr, nullptr};
};

}  // namespace rnr
}  // namespace bluetooth

/*******************************************************************************
 *
 * Function         BTM_SecAddRmtNameNotifyCallback
 *
 * Description      Any profile can register to be notified when name of the
 *                  remote device is resolved.
 *
 * Parameters:      p_callback: Callback to add after each remote name
 *                  request has completed or timed out.
 *
 * Returns          true if registered OK, else false
 *
 ******************************************************************************/
bool BTM_SecAddRmtNameNotifyCallback(tBTM_RMT_NAME_CALLBACK* p_callback);

/*******************************************************************************
 *
 * Function         BTM_SecDeleteRmtNameNotifyCallback
 *
 * Description      Any profile can deregister notification when a new Link Key
 *                  is generated per connection.
 *
 * Parameters:      p_callback: Callback to remove after each remote name
 *                  request has completed or timed out.
 *
 * Returns          true if unregistered OK, else false
 *
 ******************************************************************************/
bool BTM_SecDeleteRmtNameNotifyCallback(tBTM_RMT_NAME_CALLBACK* p_callback);

/*******************************************************************************
 *
 * Function         BTM_IsRemoteNameKnown
 *
 * Description      Look up the device record using the bluetooth device
 *                  address and if a record is found check if the name
 *                  has been acquired and cached.
 *
 * Parameters:      bd_addr: Bluetooth device address
 *                  transport: UNUSED
 *
 * Returns          true if name is cached, false otherwise
 *
 ******************************************************************************/
bool BTM_IsRemoteNameKnown(const RawAddress& bd_addr, tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_ReadRemoteDeviceName
 *
 * Description      This function initiates a remote device HCI command to the
 *                  controller and calls the callback when the process has
 *                  completed.
 *
 * Input Params:    remote_bda      - bluetooth device address of name to
 *                                    retrieve
 *                  p_cb            - callback function called when
 *                                    remote name is received or when procedure
 *                                    timed out.
 *                  transport       - transport used to query the remote name
 * Returns
 *                  BTM_CMD_STARTED is returned if the request was successfully
 *                                    sent to HCI.
 *                  BTM_BUSY if already in progress
 *                  BTM_UNKNOWN_ADDR if device address is bad
 *                  BTM_NO_RESOURCES if could not allocate resources to start
 *                                   the command
 *                  BTM_WRONG_MODE if the device is not up.
 *
 ******************************************************************************/
tBTM_STATUS BTM_ReadRemoteDeviceName(const RawAddress& remote_bda, tBTM_NAME_CMPL_CB* p_cb,
                                     tBT_TRANSPORT transport);

/*******************************************************************************
 *
 * Function         BTM_CancelRemoteDeviceName
 *
 * Description      This function initiates the cancel request for the specified
 *                  remote device.
 *
 * Input Params:    None
 *
 * Returns
 *                  BTM_CMD_STARTED is returned if the request was successfully
 *                                  sent to HCI.
 *                  BTM_NO_RESOURCES if could not allocate resources to start
 *                                   the command
 *                  BTM_WRONG_MODE if there is not an active remote name
 *                                 request.
 *
 ******************************************************************************/
tBTM_STATUS BTM_CancelRemoteDeviceName(void);

/*******************************************************************************
 *
 * Function         btm_process_remote_name
 *
 * Description      This function is called when a remote name is received from
 *                  the device. If remote names are cached, it updates the
 *                  inquiry database.
 *
 * Returns          void
 *
 ******************************************************************************/
void btm_process_remote_name(const RawAddress* bda, const BD_NAME bdn, uint16_t /* evt_len */,
                             tHCI_STATUS hci_status);
