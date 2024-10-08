/******************************************************************************
 *
 *  Copyright 2002-2012 Broadcom Corporation
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
#ifndef HIDH_API_H
#define HIDH_API_H

#include <cstdint>

#include "stack/include/bt_hdr.h"
#include "stack/include/hiddefs.h"
#include "stack/include/sdp_status.h"
#include "types/raw_address.h"

/*****************************************************************************
 *  Constants
 ****************************************************************************/

/* Attributes mask values to be used in HID_HostAddDev API */
#define HID_VIRTUAL_CABLE 0x0001
#define HID_NORMALLY_CONNECTABLE 0x0002
#define HID_RECONN_INIT 0x0004
#define HID_SDP_DISABLE 0x0008
#define HID_BATTERY_POWER 0x0010
#define HID_REMOTE_WAKE 0x0020
#define HID_SUP_TOUT_AVLBL 0x0040
#define HID_SSR_MAX_LATENCY 0x0080
#define HID_SSR_MIN_TOUT 0x0100

#define HID_SEC_REQUIRED 0x8000
#define HID_ATTR_MASK_IGNORE 0

/*****************************************************************************
 *  Type Definitions
 ****************************************************************************/

typedef void(tHID_HOST_SDP_CALLBACK)(const RawAddress& bd_add, tSDP_STATUS result,
                                     uint16_t attr_mask, tHID_DEV_SDP_INFO* sdp_rec);

/* HID-HOST returns the events in the following table to the application via
 * tHID_HOST_DEV_CALLBACK
 * HID_HDEV_EVT_OPEN  Connected to device with Interrupt and Control Channels
 *                    in OPEN state.
 *                                                      Data = NA
 * HID_HDEV_EVT_CLOSE Connection with device is closed. Data = reason code.
 * HID_HDEV_EVT_RETRYING   Lost connection is being re-connected.
 *                                                      Data = Retrial number
 * HID_HDEV_EVT_IN_REPORT  Device sent an input report  Data = Report Type
 *                                                      pdata = pointer to
 *                                                              BT_HDR
 *                                                      (GKI buffer with report
 *                                                       data.)
 * HID_HDEV_EVT_HANDSHAKE  Device sent SET_REPORT       Data = Result-code
 *                                                      pdata = NA.
 * HID_HDEV_EVT_VC_UNPLUG  Device sent Virtual Unplug   Data = NA. pdata = NA.
 */

enum {
  HID_HDEV_EVT_OPEN,
  HID_HDEV_EVT_CLOSE,
  HID_HDEV_EVT_RETRYING,
  HID_HDEV_EVT_INTR_DATA,
  HID_HDEV_EVT_INTR_DATC,
  HID_HDEV_EVT_CTRL_DATA,
  HID_HDEV_EVT_CTRL_DATC,
  HID_HDEV_EVT_HANDSHAKE,
  HID_HDEV_EVT_VC_UNPLUG
};
typedef void(tHID_HOST_DEV_CALLBACK)(uint8_t dev_handle, const RawAddress& addr,
                                     uint8_t event,  /* Event from HID-DEVICE. */
                                     uint32_t data,  /* Integer data corresponding to the event.*/
                                     BT_HDR* p_buf); /* Pointer data corresponding to the event. */

/*****************************************************************************
 *  External Function Declarations
 ****************************************************************************/

/*******************************************************************************
 *
 * Function         HID_HostGetSDPRecord
 *
 * Description      This function reads the device SDP record.
 *
 * Returns          tHID_STATUS
 *
 ******************************************************************************/
tHID_STATUS HID_HostGetSDPRecord(const RawAddress& addr, tSDP_DISCOVERY_DB* p_db, uint32_t db_len,
                                 tHID_HOST_SDP_CALLBACK* sdp_cback);

/*******************************************************************************
 *
 * Function         HID_HostRegister
 *
 * Description      This function registers HID-Host with lower layers.
 *
 * Returns          tHID_STATUS
 *
 ******************************************************************************/
tHID_STATUS HID_HostRegister(tHID_HOST_DEV_CALLBACK* dev_cback);

/*******************************************************************************
 *
 * Function         HID_HostDeregister
 *
 * Description      This function is called when the host is about power down.
 *
 * Returns          tHID_STATUS
 *
 ******************************************************************************/
tHID_STATUS HID_HostDeregister(void);

/*******************************************************************************
 *
 * Function         HID_HostSDPDisable
 *
 * Description      This is called to check if the device has the HIDSDPDisable
 *                  attribute.
 *
 * Returns          bool
 *
 ******************************************************************************/
bool HID_HostSDPDisable(const RawAddress& addr);

/*******************************************************************************
 *
 * Function         HID_HostAddDev
 *
 * Description      This is called so HID-host may manage this device.
 *
 * Returns          tHID_STATUS
 *
 ******************************************************************************/
tHID_STATUS HID_HostAddDev(const RawAddress& addr, uint16_t attr_mask, uint8_t* handle);

/*******************************************************************************
 *
 * Function         HID_HostRemoveDev
 *
 * Description      Removes the device from the list of devices that the host
 *                  has to manage.
 *
 * Returns          tHID_STATUS
 *
 ******************************************************************************/
tHID_STATUS HID_HostRemoveDev(uint8_t dev_handle);

/*******************************************************************************
 *
 * Function         HID_HostOpenDev
 *
 * Description      This function is called when the user wants to initiate a
 *                  connection attempt to a device.
 *
 * Returns          void
 *
 ******************************************************************************/
tHID_STATUS HID_HostOpenDev(uint8_t dev_handle);

/*******************************************************************************
 *
 * Function         HID_HostWriteDev
 *
 * Description      This function is called when the host has a report to send.
 *
 * Returns          void
 *
 ******************************************************************************/
tHID_STATUS HID_HostWriteDev(uint8_t dev_handle, uint8_t t_type, uint8_t param, uint16_t data,
                             uint8_t report_id, BT_HDR* pbuf);

/*******************************************************************************
 *
 * Function         HID_HostCloseDev
 *
 * Description      This function disconnects the device.
 *
 * Returns          void
 *
 ******************************************************************************/
tHID_STATUS HID_HostCloseDev(uint8_t dev_handle);

/*******************************************************************************
 * Function         HID_HostInit
 *
 * Description      Initialize the control block and trace variable
 *
 * Returns          void
 ******************************************************************************/
void HID_HostInit(void);

#endif /* HIDH_API_H */
