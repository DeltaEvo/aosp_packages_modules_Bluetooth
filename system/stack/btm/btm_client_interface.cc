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

#include "stack/include/btm_client_interface.h"

#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_sec.h"
#include "stack/include/btm_api.h"
#include "stack/include/btm_ble_sec_api.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/btm_sec_api.h"

struct btm_client_interface_t btm_client_interface = {
    .lifecycle =
        {
            .BTM_PmRegister = BTM_PmRegister,
            .BTM_GetHCIConnHandle = BTM_GetHCIConnHandle,
            .BTM_VendorSpecificCommand = BTM_VendorSpecificCommand,
            .ACL_RegisterClient = ACL_RegisterClient,
            .ACL_UnregisterClient = ACL_UnregisterClient,
            .btm_init = btm_init,
            .btm_free = btm_free,
            .btm_ble_init = btm_ble_init,
            .btm_ble_free = btm_ble_free,
            .BTM_reset_complete = BTM_reset_complete,
        },

    // Acl peer and lifecycle
    .peer =
        {
            .features =
                {
                    .SupportTransparentSynchronousData =
                        ACL_SupportTransparentSynchronousData,
                },

            .BTM_IsAclConnectionUp = BTM_IsAclConnectionUp,
            .BTM_ReadConnectedTransportAddress =
                BTM_ReadConnectedTransportAddress,
            .BTM_CancelRemoteDeviceName = BTM_CancelRemoteDeviceName,
            .BTM_ReadRemoteDeviceName = BTM_ReadRemoteDeviceName,
            .BTM_ReadRemoteFeatures = BTM_ReadRemoteFeatures,
            .BTM_ReadDevInfo = BTM_ReadDevInfo,
            .BTM_GetMaxPacketSize = BTM_GetMaxPacketSize,
            .BTM_ReadRemoteVersion = BTM_ReadRemoteVersion,
        },

    .link_policy =
        {
            .BTM_GetRole = BTM_GetRole,
            .BTM_SetPowerMode = BTM_SetPowerMode,
            .BTM_SetSsrParams = BTM_SetSsrParams,
            .BTM_SwitchRoleToCentral = BTM_SwitchRoleToCentral,
            .BTM_block_role_switch_for = BTM_block_role_switch_for,
            .BTM_block_sniff_mode_for = BTM_block_sniff_mode_for,
            .BTM_default_unblock_role_switch = BTM_default_unblock_role_switch,
            .BTM_unblock_role_switch_for = BTM_unblock_role_switch_for,
            .BTM_unblock_sniff_mode_for = BTM_unblock_sniff_mode_for,
            .BTM_WritePageTimeout = BTM_WritePageTimeout,
        },

    .link_controller =
        {
            .BTM_GetLinkSuperTout = BTM_GetLinkSuperTout,
            .BTM_ReadRSSI = BTM_ReadRSSI,
        },

    .security =
        {
            .BTM_SecAddDevice = BTM_SecAddDevice,
            .BTM_SecAddRmtNameNotifyCallback = BTM_SecAddRmtNameNotifyCallback,
            .BTM_SecDeleteDevice = BTM_SecDeleteDevice,
            .BTM_SecRegister = BTM_SecRegister,
            .BTM_SecReadDevName = BTM_SecReadDevName,
            .BTM_SecBond = BTM_SecBond,
            .BTM_SecBondCancel = BTM_SecBondCancel,
            .BTM_SecAddBleKey = BTM_SecAddBleKey,
            .BTM_SecAddBleDevice = BTM_SecAddBleDevice,
            .BTM_SecClearSecurityFlags = BTM_SecClearSecurityFlags,
            .BTM_SecClrService = BTM_SecClrService,
            .BTM_SecClrServiceByPsm = BTM_SecClrServiceByPsm,
            .BTM_RemoteOobDataReply = BTM_RemoteOobDataReply,
            .BTM_PINCodeReply = BTM_PINCodeReply,
            .BTM_ConfirmReqReply = BTM_ConfirmReqReply,
            .BTM_SecDeleteRmtNameNotifyCallback =
                BTM_SecDeleteRmtNameNotifyCallback,
            .BTM_SetEncryption = BTM_SetEncryption,
            .BTM_IsEncrypted = BTM_IsEncrypted,
            .BTM_SecIsSecurityPending = BTM_SecIsSecurityPending,
            .BTM_IsLinkKeyKnown = BTM_IsLinkKeyKnown,
            .BTM_BleSirkConfirmDeviceReply = BTM_BleSirkConfirmDeviceReply,
            .BTM_GetSecurityMode = BTM_GetSecurityMode,
        },

    .ble =
        {
            .BTM_BleGetEnergyInfo = BTM_BleGetEnergyInfo,
            .BTM_BleObserve = BTM_BleObserve,
            .BTM_SetBleDataLength = BTM_SetBleDataLength,
            .BTM_BleConfirmReply = BTM_BleConfirmReply,
            .BTM_BleLoadLocalKeys = BTM_BleLoadLocalKeys,
            .BTM_BlePasskeyReply = BTM_BlePasskeyReply,
            .BTM_BleReadControllerFeatures = BTM_BleReadControllerFeatures,
            .BTM_BleSetPhy = BTM_BleSetPhy,
            .BTM_BleSetPrefConnParams = BTM_BleSetPrefConnParams,
            .BTM_UseLeLink = BTM_UseLeLink,
        },

    .sco =
        {
            .BTM_CreateSco = BTM_CreateSco,
            .BTM_RegForEScoEvts = BTM_RegForEScoEvts,
            .BTM_RemoveSco = BTM_RemoveSco,
            .BTM_WriteVoiceSettings = BTM_WriteVoiceSettings,
            .BTM_EScoConnRsp = BTM_EScoConnRsp,
            .BTM_GetNumScoLinks = BTM_GetNumScoLinks,
            .BTM_SetEScoMode = BTM_SetEScoMode,
        },

    .local =
        {
            .BTM_ReadLocalDeviceNameFromController =
                BTM_ReadLocalDeviceNameFromController,
            .BTM_SetLocalDeviceName = BTM_SetLocalDeviceName,
            .BTM_SetDeviceClass = BTM_SetDeviceClass,
            .BTM_IsDeviceUp = BTM_IsDeviceUp,
            .BTM_ReadDeviceClass = BTM_ReadDeviceClass,
        },

    .eir =
        {
            .BTM_WriteEIR = BTM_WriteEIR,
            .BTM_GetEirSupportedServices = BTM_GetEirSupportedServices,
            .BTM_GetEirUuidList = BTM_GetEirUuidList,
            .BTM_AddEirService = BTM_AddEirService,
            .BTM_RemoveEirService = BTM_RemoveEirService,
        },
    .db =
        {
            .BTM_InqDbRead = BTM_InqDbRead,
            .BTM_InqDbFirst = BTM_InqDbFirst,
            .BTM_InqDbNext = BTM_InqDbNext,
            .BTM_ClearInqDb = BTM_ClearInqDb,
        },
};

struct btm_client_interface_t& get_btm_client_interface() {
  return btm_client_interface;
}
