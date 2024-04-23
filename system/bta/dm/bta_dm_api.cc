/******************************************************************************
 *
 *  Copyright 2003-2014 Broadcom Corporation
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
 *  This is the API implementation file for the BTA device manager.
 *
 ******************************************************************************/

#include <android_bluetooth_flags.h>
#include <base/functional/bind.h>
#include <bluetooth/log.h>

#include <vector>

#include "bta/dm/bta_dm_device_search.h"
#include "bta/dm/bta_dm_disc.h"
#include "bta/dm/bta_dm_int.h"
#include "bta/dm/bta_dm_sec_int.h"
#include "hci/le_rand_callback.h"
#include "stack/include/bt_uuid16.h"
#include "stack/include/btm_api.h"
#include "stack/include/btm_client_interface.h"
#include "stack/include/main_thread.h"
#include "stack/include/sdp_api.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

using namespace bluetooth::legacy::stack::sdp;
using namespace bluetooth;

using bluetooth::Uuid;

/*****************************************************************************
 *  Constants
 ****************************************************************************/

void BTA_dm_init() {
  /* if UUID list is not provided as static data */
  bta_sys_eir_register(bta_dm_eir_update_uuid);
  bta_sys_cust_eir_register(bta_dm_eir_update_cust_uuid);
  BTM_SetConsolidationCallback(bta_dm_consolidate);
}

/** Enables bluetooth device under test mode */
void BTA_EnableTestMode(void) {
  do_in_main_thread(FROM_HERE,
                    base::BindOnce(base::IgnoreResult(BTM_EnableTestMode)));
}

/** This function sets the Bluetooth name of local device */
void BTA_DmSetDeviceName(const char* p_name) {
  std::vector<uint8_t> name(BD_NAME_LEN + 1);
  bd_name_from_char_pointer(name.data(), p_name);

  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_set_dev_name, name));
}

/*******************************************************************************
 *
 * Function         BTA_DmSearch
 *
 * Description      This function searches for peer Bluetooth devices. It
 *                  performs an inquiry and gets the remote name for devices.
 *                  Service discovery is done if services is non zero
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmSearch(tBTA_DM_SEARCH_CBACK* p_cback) {
  bta_dm_disc_start_device_discovery(p_cback);
}

/*******************************************************************************
 *
 * Function         BTA_DmSearchCancel
 *
 * Description      This function  cancels a search initiated by BTA_DmSearch
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmSearchCancel(void) { bta_dm_disc_stop_device_discovery(); }

/*******************************************************************************
 *
 * Function         BTA_DmDiscover
 *
 * Description      This function does service discovery for services of a
 *                  peer device
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmDiscover(const RawAddress& bd_addr,
                    service_discovery_callbacks cbacks,
                    tBT_TRANSPORT transport) {
  bta_dm_disc_start_service_discovery(cbacks, bd_addr, transport);
}

/*******************************************************************************
 *
 * Function         BTA_DmGetConnectionState
 *
 * Description      Returns whether the remote device is currently connected.
 *
 * Returns          0 if the device is NOT connected.
 *
 ******************************************************************************/
bool BTA_DmGetConnectionState(const RawAddress& bd_addr) {
  tBTA_DM_PEER_DEVICE* p_dev = bta_dm_find_peer_device(bd_addr);
  return (p_dev && p_dev->conn_state == BTA_DM_CONNECTED);
}

/*******************************************************************************
 *                   Device Identification (DI) Server Functions
 ******************************************************************************/
/*******************************************************************************
 *
 * Function         BTA_DmSetLocalDiRecord
 *
 * Description      This function adds a DI record to the local SDP database.
 *
 * Returns          BTA_SUCCESS if record set sucessfully, otherwise error code.
 *
 ******************************************************************************/
tBTA_STATUS BTA_DmSetLocalDiRecord(tSDP_DI_RECORD* p_device_info,
                                   uint32_t* p_handle) {
  tBTA_STATUS status = BTA_FAILURE;

  if (bta_dm_di_cb.di_num < BTA_DI_NUM_MAX) {
    if (get_legacy_stack_sdp_api()->device_id.SDP_SetLocalDiRecord(
            (tSDP_DI_RECORD*)p_device_info, p_handle) == SDP_SUCCESS) {
      if (!p_device_info->primary_record) {
        bta_dm_di_cb.di_handle[bta_dm_di_cb.di_num] = *p_handle;
        bta_dm_di_cb.di_num++;
      }

      bta_sys_add_uuid(UUID_SERVCLASS_PNP_INFORMATION);
      status = BTA_SUCCESS;
    }
  }

  return status;
}

/*******************************************************************************
 *
 * Function         BTA_DmSetBlePrefConnParams
 *
 * Description      This function is called to set the preferred connection
 *                  parameters when default connection parameter is not desired.
 *
 * Parameters:      bd_addr          - BD address of the peripheral
 *                  scan_interval    - scan interval
 *                  scan_window      - scan window
 *                  min_conn_int     - minimum preferred connection interval
 *                  max_conn_int     - maximum preferred connection interval
 *                  peripheral_latency    - preferred peripheral latency
 *                  supervision_tout - preferred supervision timeout
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmSetBlePrefConnParams(const RawAddress& bd_addr,
                                uint16_t min_conn_int, uint16_t max_conn_int,
                                uint16_t peripheral_latency,
                                uint16_t supervision_tout) {
  do_in_main_thread(
      FROM_HERE,
      base::BindOnce(bta_dm_ble_set_conn_params, bd_addr, min_conn_int,
                     max_conn_int, peripheral_latency, supervision_tout));
}

/*******************************************************************************
 *
 * Function         BTA_DmBleUpdateConnectionParam
 *
 * Description      Update connection parameters, can only be used when
 *                  connection is up.
 *
 * Parameters:      bd_addr          - BD address of the peer
 *                  min_int   -     minimum connection interval,
 *                                  [0x0004 ~ 0x4000]
 *                  max_int   -     maximum connection interval,
 *                                  [0x0004 ~ 0x4000]
 *                  latency   -     peripheral latency [0 ~ 500]
 *                  timeout   -     supervision timeout [0x000a ~ 0xc80]
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmBleUpdateConnectionParams(const RawAddress& bd_addr,
                                     uint16_t min_int, uint16_t max_int,
                                     uint16_t latency, uint16_t timeout,
                                     uint16_t min_ce_len, uint16_t max_ce_len) {
  do_in_main_thread(
      FROM_HERE,
      base::BindOnce(bta_dm_ble_update_conn_params, bd_addr, min_int, max_int,
                     latency, timeout, min_ce_len, max_ce_len));
}

/*******************************************************************************
 *
 * Function         BTA_DmBleConfigLocalPrivacy
 *
 * Description      Enable/disable privacy on the local device
 *
 * Parameters:      privacy_enable   - enable/disabe privacy on remote device.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmBleConfigLocalPrivacy(bool privacy_enable) {
  if (IS_FLAG_ENABLED(synchronous_bta_sec)) {
    bta_dm_ble_config_local_privacy(privacy_enable);
  } else {
    do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_ble_config_local_privacy,
                                                privacy_enable));
  }
}

/*******************************************************************************
 *
 * Function         BTA_DmBleGetEnergyInfo
 *
 * Description      This function is called to obtain the energy info
 *
 * Parameters       p_cmpl_cback - Command complete callback
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmBleGetEnergyInfo(tBTA_BLE_ENERGY_INFO_CBACK* p_cmpl_cback) {
  do_in_main_thread(FROM_HERE,
                    base::BindOnce(bta_dm_ble_get_energy_info, p_cmpl_cback));
}

/** This function is to set maximum LE data packet size */
void BTA_DmBleRequestMaxTxDataLength(const RawAddress& remote_device) {
  do_in_main_thread(FROM_HERE,
                    base::BindOnce(bta_dm_ble_set_data_length, remote_device));
}

/*******************************************************************************
 *
 * Function         BTA_DmBleScan
 *
 * Description      Start or stop the scan procedure.
 *
 * Parameters       start: start or stop the scan procedure,
 *                  duration_sec: Duration of the scan. Continuous scan if 0 is
 *                                passed,
 *                  low_latency_scan: whether this is an low latency scan,
 *                                    default is false.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmBleScan(bool start, uint8_t duration_sec, bool low_latency_scan) {
  log::verbose("start = {}", start);
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_ble_scan, start,
                                              duration_sec, low_latency_scan));
}

/*******************************************************************************
 *
 * Function         BTA_DmBleCsisObserve
 *
 * Description      This procedure keeps the external observer listening for
 *                  advertising events from a CSIS grouped device.
 *
 * Parameters       observe: enable or disable passive observe,
 *                  p_results_cb: Callback to be called with scan results,
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmBleCsisObserve(bool observe, tBTA_DM_SEARCH_CBACK* p_results_cb) {
  log::verbose("enable = {}", observe);
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_ble_csis_observe, observe,
                                              p_results_cb));
}

/*******************************************************************************
 *
 * Function         BTA_DmClearEventFilter
 *
 * Description      This function clears the event filter
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmClearEventFilter(void) {
  log::verbose("BTA_DmClearEventFilter");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_clear_event_filter));
}

/*******************************************************************************
 *
 * Function         BTA_DmClearEventMask
 *
 * Description      This function clears the event mask
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmClearEventMask(void) {
  log::verbose("BTA_DmClearEventMask");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_clear_event_mask));
}

/*******************************************************************************
 *
 * Function         BTA_DmClearEventMask
 *
 * Description      This function clears the filter accept list
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmClearFilterAcceptList(void) {
  log::verbose("BTA_DmClearFilterAcceptList");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_clear_filter_accept_list));
}

/*******************************************************************************
 *
 * Function         BTA_DmLeRand
 *
 * Description      This function clears the event filter
 *
 * Returns          cb: callback to receive the resulting random number
 *
 ******************************************************************************/
void BTA_DmLeRand(bluetooth::hci::LeRandCallback cb) {
  log::verbose("BTA_DmLeRand");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_le_rand, std::move(cb)));
}

/*******************************************************************************
 *
 * Function         BTA_DmDisconnectAllAcls
 *
 * Description      This function will disconnect all LE and Classic ACLs.
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmDisconnectAllAcls() {
  log::verbose("BTA_DmLeRand");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_disconnect_all_acls));
}

void BTA_DmSetEventFilterConnectionSetupAllDevices() {
  log::verbose("BTA_DmSetEventFilterConnectionSetupAllDevices");
  do_in_main_thread(
      FROM_HERE,
      base::BindOnce(bta_dm_set_event_filter_connection_setup_all_devices));
}

void BTA_DmAllowWakeByHid(
    std::vector<RawAddress> classic_hid_devices,
    std::vector<std::pair<RawAddress, uint8_t>> le_hid_devices) {
  log::verbose("BTA_DmAllowWakeByHid");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_allow_wake_by_hid,
                                              std::move(classic_hid_devices),
                                              std::move(le_hid_devices)));
}

void BTA_DmRestoreFilterAcceptList(
    std::vector<std::pair<RawAddress, uint8_t>> le_devices) {
  log::verbose("BTA_DmRestoreFilterAcceptList");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_restore_filter_accept_list,
                                              std::move(le_devices)));
}

void BTA_DmSetDefaultEventMaskExcept(uint64_t mask, uint64_t le_mask) {
  log::verbose("BTA_DmSetDefaultEventMaskExcept");
  do_in_main_thread(
      FROM_HERE,
      base::BindOnce(bta_dm_set_default_event_mask_except, mask, le_mask));
}

void BTA_DmSetEventFilterInquiryResultAllDevices() {
  log::verbose("BTA_DmSetEventFilterInquiryResultAllDevices");
  do_in_main_thread(
      FROM_HERE,
      base::BindOnce(bta_dm_set_event_filter_inquiry_result_all_devices));
}

/*******************************************************************************
 *
 * Function         BTA_DmBleResetId
 *
 * Description      This function resets the ble keys such as IRK
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmBleResetId(void) {
  log::verbose("BTA_DmBleResetId");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_ble_reset_id));
}

/*******************************************************************************
 *
 * Function         BTA_DmBleSubrateRequest
 *
 * Description      subrate request, can only be used when connection is up.
 *
 * Parameters:      bd_addr       - BD address of the peer
 *                  subrate_min   - subrate factor minimum, [0x0001 - 0x01F4]
 *                  subrate_max   - subrate factor maximum, [0x0001 - 0x01F4]
 *                  max_latency   - max peripheral latency [0x0000 - 01F3]
 *                  cont_num      - continuation number [0x0000 - 01F3]
 *                  timeout       - supervision timeout [0x000a - 0xc80]
 *
 * Returns          void
 *
 ******************************************************************************/
void BTA_DmBleSubrateRequest(const RawAddress& bd_addr, uint16_t subrate_min,
                             uint16_t subrate_max, uint16_t max_latency,
                             uint16_t cont_num, uint16_t timeout) {
  log::verbose("");
  do_in_main_thread(FROM_HERE, base::BindOnce(bta_dm_ble_subrate_request,
                                              bd_addr, subrate_min, subrate_max,
                                              max_latency, cont_num, timeout));
}

bool BTA_DmCheckLeAudioCapable(const RawAddress& address) {
  for (tBTM_INQ_INFO* inq_ent = get_btm_client_interface().db.BTM_InqDbFirst();
       inq_ent != nullptr;
       inq_ent = get_btm_client_interface().db.BTM_InqDbNext(inq_ent)) {
    if (inq_ent->results.remote_bd_addr != address) continue;

    if (inq_ent->results.ble_ad_is_le_audio_capable) {
      log::info("Device is LE Audio capable based on AD content");
      return true;
    }

    return false;
  }
  return false;
}
