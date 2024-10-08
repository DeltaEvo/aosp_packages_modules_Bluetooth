/******************************************************************************
 *
 *  Copyright 2003-2012 Broadcom Corporation
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
 *  This file contains the GATT server main functions and state machine.
 *
 ******************************************************************************/

#include <bluetooth/log.h>

#include "bta/gatt/bta_gatts_int.h"
#include "internal_include/bt_target.h"
#include "stack/include/bt_hdr.h"

using namespace bluetooth;

/* GATTS control block */
tBTA_GATTS_CB bta_gatts_cb;

/*******************************************************************************
 *
 * Function         bta_gatts_hdl_event
 *
 * Description      BTA GATT server main event handling function.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
bool bta_gatts_hdl_event(const BT_HDR_RIGID* p_msg) {
  tBTA_GATTS_CB* p_cb = &bta_gatts_cb;

  switch (p_msg->event) {
    case BTA_GATTS_API_DISABLE_EVT:
      bta_gatts_api_disable(p_cb);
      break;

    case BTA_GATTS_API_REG_EVT:
      bta_gatts_register(p_cb, (tBTA_GATTS_DATA*)p_msg);
      break;

    case BTA_GATTS_INT_START_IF_EVT:
      bta_gatts_start_if(p_cb, (tBTA_GATTS_DATA*)p_msg);
      break;

    case BTA_GATTS_API_DEREG_EVT:
      bta_gatts_deregister(p_cb, (tBTA_GATTS_DATA*)p_msg);
      break;

    case BTA_GATTS_API_INDICATION_EVT:
      bta_gatts_indicate_handle(p_cb, (tBTA_GATTS_DATA*)p_msg);
      break;

    case BTA_GATTS_API_OPEN_EVT:
      bta_gatts_open(p_cb, (tBTA_GATTS_DATA*)p_msg);
      break;

    case BTA_GATTS_API_CANCEL_OPEN_EVT:
      bta_gatts_cancel_open(p_cb, (tBTA_GATTS_DATA*)p_msg);
      break;

    case BTA_GATTS_API_CLOSE_EVT:
      bta_gatts_close(p_cb, (tBTA_GATTS_DATA*)p_msg);
      break;

    case BTA_GATTS_API_RSP_EVT:
      bta_gatts_send_rsp(p_cb, (tBTA_GATTS_DATA*)p_msg);
      break;

    case BTA_GATTS_API_DEL_SRVC_EVT: {
      tBTA_GATTS_SRVC_CB* p_srvc_cb = bta_gatts_find_srvc_cb_by_srvc_id(
              p_cb, ((tBTA_GATTS_DATA*)p_msg)->api_add_service.hdr.layer_specific);

      if (p_srvc_cb != NULL) {
        bta_gatts_delete_service(p_srvc_cb, (tBTA_GATTS_DATA*)p_msg);
      } else {
        log::error("can't delete service - no srvc_cb found");
      }

      break;
    }

    case BTA_GATTS_API_STOP_SRVC_EVT: {
      tBTA_GATTS_SRVC_CB* p_srvc_cb = bta_gatts_find_srvc_cb_by_srvc_id(
              p_cb, ((tBTA_GATTS_DATA*)p_msg)->api_add_service.hdr.layer_specific);

      if (p_srvc_cb != NULL) {
        bta_gatts_stop_service(p_srvc_cb, (tBTA_GATTS_DATA*)p_msg);
      } else {
        log::error("can't stop service - no srvc_cb found");
      }

      break;
    }

    case BTA_GATTS_API_INIT_BONDED_EVT:
      gatt_load_bonded();
      break;

    default:
      break;
  }

  return true;
}
