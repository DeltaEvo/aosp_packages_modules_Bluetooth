/******************************************************************************
 *
 *  Copyright 2009-2012 Broadcom Corporation
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
 *  this file contains functions relating to BLE management.
 *
 ******************************************************************************/

#define LOG_TAG "l2c_ble"

#include <base/logging.h>
#include <base/strings/stringprintf.h>
#include <log/log.h>

#ifdef __ANDROID__
#include <android/sysprop/BluetoothProperties.sysprop.h>
#endif

#include "bt_target.h"
#include "btif/include/core_callbacks.h"
#include "btif/include/stack_manager.h"
#include "device/include/controller.h"
#include "main/shim/acl_api.h"
#include "os/log.h"
#include "osi/include/allocator.h"
#include "osi/include/properties.h"
#include "stack/btm/btm_dev.h"
#include "stack/btm/btm_int_types.h"
#include "stack/btm/btm_sec.h"
#include "stack/btm/btm_sec_int_types.h"
#include "stack/include/acl_api.h"
#include "stack/include/bt_psm_types.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/l2c_api.h"
#include "stack/include/l2cdefs.h"
#include "stack/l2cap/l2c_int.h"
#include "stack_config.h"
#include "types/raw_address.h"

namespace {

constexpr char kBtmLogTag[] = "L2CAP";

}

tL2CAP_LE_RESULT_CODE btm_ble_start_sec_check(const RawAddress& bd_addr,
                                              uint16_t psm, bool is_originator,
                                              tBTM_SEC_CALLBACK* p_callback,
                                              void* p_ref_data);

extern tBTM_CB btm_cb;

using base::StringPrintf;

static void l2cble_start_conn_update(tL2C_LCB* p_lcb);
static void l2cble_start_subrate_change(tL2C_LCB* p_lcb);
void gatt_notify_conn_update(const RawAddress& remote, uint16_t interval,
                             uint16_t latency, uint16_t timeout,
                             tHCI_STATUS status);

/*******************************************************************************
 *
 *  Function        L2CA_UpdateBleConnParams
 *
 *  Description     Update BLE connection parameters.
 *
 *  Parameters:     BD Address of remote
 *
 *  Return value:   true if update started
 *
 ******************************************************************************/
bool L2CA_UpdateBleConnParams(const RawAddress& rem_bda, uint16_t min_int,
                              uint16_t max_int, uint16_t latency,
                              uint16_t timeout, uint16_t min_ce_len,
                              uint16_t max_ce_len) {
  tL2C_LCB* p_lcb;

  /* See if we have a link control block for the remote device */
  p_lcb = l2cu_find_lcb_by_bd_addr(rem_bda, BT_TRANSPORT_LE);

  /* If we do not have one, create one and accept the connection. */
  if (!p_lcb || !BTM_IsAclConnectionUp(rem_bda, BT_TRANSPORT_LE)) {
    LOG(WARNING) << __func__ << " - unknown BD_ADDR " << rem_bda;
    return (false);
  }

  if (p_lcb->transport != BT_TRANSPORT_LE) {
    LOG(WARNING) << __func__ << " - BD_ADDR " << rem_bda << " not LE";
    return (false);
  }

  VLOG(2) << __func__ << ": BD_ADDR=" << ADDRESS_TO_LOGGABLE_STR(rem_bda)
          << ", min_int=" << min_int << ", max_int=" << max_int
          << ", min_ce_len=" << min_ce_len << ", max_ce_len=" << max_ce_len;

  p_lcb->min_interval = min_int;
  p_lcb->max_interval = max_int;
  p_lcb->latency = latency;
  p_lcb->timeout = timeout;
  p_lcb->conn_update_mask |= L2C_BLE_NEW_CONN_PARAM;
  p_lcb->min_ce_len = min_ce_len;
  p_lcb->max_ce_len = max_ce_len;

  l2cble_start_conn_update(p_lcb);

  return (true);
}

/*******************************************************************************
 *
 *  Function        L2CA_EnableUpdateBleConnParams
 *
 *  Description     Enable or disable update based on the request from the peer
 *
 *  Parameters:     BD Address of remote
 *
 *  Return value:   true if update started
 *
 ******************************************************************************/
bool L2CA_EnableUpdateBleConnParams(const RawAddress& rem_bda, bool enable) {
  if (stack_config_get_interface()->get_pts_conn_updates_disabled())
    return false;

  tL2C_LCB* p_lcb;

  /* See if we have a link control block for the remote device */
  p_lcb = l2cu_find_lcb_by_bd_addr(rem_bda, BT_TRANSPORT_LE);

  if (!p_lcb) {
    LOG(WARNING) << __func__ << " - unknown BD_ADDR " << rem_bda;
    return false;
  }

  VLOG(2) << __func__ << " - BD_ADDR " << ADDRESS_TO_LOGGABLE_STR(rem_bda)
          << StringPrintf(" enable %d current upd state 0x%02x", enable,
                          p_lcb->conn_update_mask);

  if (p_lcb->transport != BT_TRANSPORT_LE) {
    LOG(WARNING) << __func__ << " - BD_ADDR "
                 << ADDRESS_TO_LOGGABLE_STR(rem_bda) << " not LE, link role "
                 << p_lcb->LinkRole();
    return false;
  }

  if (enable) {
    p_lcb->conn_update_mask &= ~L2C_BLE_CONN_UPDATE_DISABLE;
    p_lcb->subrate_req_mask &= ~L2C_BLE_SUBRATE_REQ_DISABLE;
  } else {
    p_lcb->conn_update_mask |= L2C_BLE_CONN_UPDATE_DISABLE;
    p_lcb->subrate_req_mask |= L2C_BLE_SUBRATE_REQ_DISABLE;
  }

  l2cble_start_conn_update(p_lcb);

  return (true);
}

void L2CA_Consolidate(const RawAddress& identity_addr, const RawAddress& rpa) {
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_bd_addr(rpa, BT_TRANSPORT_LE);
  if (p_lcb == nullptr) {
    return;
  }

  LOG_INFO("consolidating l2c_lcb record %s -> %s",
           ADDRESS_TO_LOGGABLE_CSTR(rpa),
           ADDRESS_TO_LOGGABLE_CSTR(identity_addr));
  p_lcb->remote_bd_addr = identity_addr;
}

hci_role_t L2CA_GetBleConnRole(const RawAddress& bd_addr) {
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_bd_addr(bd_addr, BT_TRANSPORT_LE);
  if (p_lcb == nullptr) {
    return HCI_ROLE_UNKNOWN;
  }
  return p_lcb->LinkRole();
}

/*******************************************************************************
 *
 * Function l2cble_notify_le_connection
 *
 * Description This function notifiy the l2cap connection to the app layer
 *
 * Returns none
 *
 ******************************************************************************/
void l2cble_notify_le_connection(const RawAddress& bda) {
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_bd_addr(bda, BT_TRANSPORT_LE);
  if (p_lcb == nullptr) {
    LOG_WARN("Received notification for le connection but no lcb found");
    return;
  }

  if (BTM_IsAclConnectionUp(bda, BT_TRANSPORT_LE) &&
      p_lcb->link_state != LST_CONNECTED) {
    /* update link status */
    // TODO Move this back into acl layer
    btm_establish_continue_from_address(bda, BT_TRANSPORT_LE);
    /* update l2cap link status and send callback */
    p_lcb->link_state = LST_CONNECTED;
    l2cu_process_fixed_chnl_resp(p_lcb);
  }

  /* For all channels, send the event through their FSMs */
  for (tL2C_CCB* p_ccb = p_lcb->ccb_queue.p_first_ccb; p_ccb;
       p_ccb = p_ccb->p_next_ccb) {
    if (p_ccb->chnl_state == CST_CLOSED)
      l2c_csm_execute(p_ccb, L2CEVT_LP_CONNECT_CFM, NULL);
  }
}

/** This function is called when an HCI Connection Complete event is received.
 */
bool l2cble_conn_comp(uint16_t handle, uint8_t role, const RawAddress& bda,
                      tBLE_ADDR_TYPE type, uint16_t conn_interval,
                      uint16_t conn_latency, uint16_t conn_timeout) {
  // role == HCI_ROLE_CENTRAL => scanner completed connection
  // role == HCI_ROLE_PERIPHERAL => advertiser completed connection

  /* See if we have a link control block for the remote device */
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_bd_addr(bda, BT_TRANSPORT_LE);

  /* If we do not have one, create one. this is auto connection complete. */
  if (!p_lcb) {
    p_lcb = l2cu_allocate_lcb(bda, false, BT_TRANSPORT_LE);
    if (!p_lcb) {
      LOG_ERROR("Unable to allocate link resource for le acl connection");
      return false;
    } else {
      if (!l2cu_initialize_fixed_ccb(p_lcb, L2CAP_ATT_CID)) {
        LOG_ERROR("Unable to allocate channel resource for le acl connection");
        return false;
      }
    }
    p_lcb->link_state = LST_CONNECTING;
  } else if (role == HCI_ROLE_CENTRAL && p_lcb->link_state != LST_CONNECTING) {
    LOG_ERROR(
        "Received le acl connection as role central but not in connecting "
        "state");
    return false;
  }

  if (role == HCI_ROLE_CENTRAL) alarm_cancel(p_lcb->l2c_lcb_timer);

  /* Save the handle */
  l2cu_set_lcb_handle(*p_lcb, handle);

  /* Connected OK. Change state to connected, we were scanning so we are central
   */
  if (role == HCI_ROLE_CENTRAL) {
    p_lcb->SetLinkRoleAsCentral();
  } else {
    p_lcb->SetLinkRoleAsPeripheral();
  }

  p_lcb->transport = BT_TRANSPORT_LE;

  /* update link parameter, set peripheral link as non-spec default upon link up
   */
  p_lcb->min_interval = p_lcb->max_interval = conn_interval;
  p_lcb->timeout = conn_timeout;
  p_lcb->latency = conn_latency;
  p_lcb->conn_update_mask = L2C_BLE_NOT_DEFAULT_PARAM;

  p_lcb->subrate_req_mask = 0;
  p_lcb->subrate_min = 1;
  p_lcb->subrate_max = 1;
  p_lcb->max_latency = 0;
  p_lcb->cont_num = 0;
  p_lcb->supervision_tout = 0;

  p_lcb->peer_chnl_mask[0] = L2CAP_FIXED_CHNL_ATT_BIT |
                             L2CAP_FIXED_CHNL_BLE_SIG_BIT |
                             L2CAP_FIXED_CHNL_SMP_BIT;

  if (role == HCI_ROLE_PERIPHERAL) {
    if (!controller_get_interface()
             ->supports_ble_peripheral_initiated_feature_exchange()) {
      p_lcb->link_state = LST_CONNECTED;
      l2cu_process_fixed_chnl_resp(p_lcb);
    }
  }
  return true;
}

bool l2cble_conn_comp_from_address_with_type(
    uint16_t handle, uint8_t role, const tBLE_BD_ADDR& address_with_type,
    uint16_t conn_interval, uint16_t conn_latency, uint16_t conn_timeout) {
  return l2cble_conn_comp(handle, role, address_with_type.bda,
                          address_with_type.type, conn_interval, conn_latency,
                          conn_timeout);
}

/*******************************************************************************
 *
 *  Function        l2cble_start_conn_update
 *
 *  Description     Start the BLE connection parameter update process based on
 *                  status.
 *
 *  Parameters:     lcb : l2cap link control block
 *
 *  Return value:   none
 *
 ******************************************************************************/
static void l2cble_start_conn_update(tL2C_LCB* p_lcb) {
  uint16_t min_conn_int, max_conn_int, peripheral_latency, supervision_tout;
  if (!BTM_IsAclConnectionUp(p_lcb->remote_bd_addr, BT_TRANSPORT_LE)) {
    LOG(ERROR) << "No known connection ACL for " << p_lcb->remote_bd_addr;
    return;
  }

  // TODO(armansito): The return value of this call wasn't being used but the
  // logic of this function might be depending on its side effects. We should
  // verify if this call is needed at all and remove it otherwise.
  btm_find_or_alloc_dev(p_lcb->remote_bd_addr);

  if ((p_lcb->conn_update_mask & L2C_BLE_UPDATE_PENDING) ||
      (p_lcb->subrate_req_mask & L2C_BLE_SUBRATE_REQ_PENDING)) {
    return;
  }

  if (p_lcb->conn_update_mask & L2C_BLE_CONN_UPDATE_DISABLE) {
    /* application requests to disable parameters update.
       If parameters are already updated, lets set them
       up to what has been requested during connection establishement */
    if (p_lcb->conn_update_mask & L2C_BLE_NOT_DEFAULT_PARAM &&
        /* current connection interval is greater than default min */
        p_lcb->min_interval > BTM_BLE_CONN_INT_MIN) {
      /* use 7.5 ms as fast connection parameter, 0 peripheral latency */
      min_conn_int = max_conn_int = BTM_BLE_CONN_INT_MIN;

      L2CA_AdjustConnectionIntervals(&min_conn_int, &max_conn_int,
                                     BTM_BLE_CONN_INT_MIN);

      peripheral_latency = BTM_BLE_CONN_PERIPHERAL_LATENCY_DEF;
      supervision_tout = BTM_BLE_CONN_TIMEOUT_DEF;

      /* if both side 4.1, or we are central device, send HCI command */
      if (p_lcb->IsLinkRoleCentral()
          || (controller_get_interface()
                  ->supports_ble_connection_parameter_request() &&
              acl_peer_supports_ble_connection_parameters_request(
                  p_lcb->remote_bd_addr))
      ) {
        btsnd_hcic_ble_upd_ll_conn_params(p_lcb->Handle(), min_conn_int,
                                          max_conn_int, peripheral_latency,
                                          supervision_tout, 0, 0);
        p_lcb->conn_update_mask |= L2C_BLE_UPDATE_PENDING;
      } else {
        l2cu_send_peer_ble_par_req(p_lcb, min_conn_int, max_conn_int,
                                   peripheral_latency, supervision_tout);
      }
      p_lcb->conn_update_mask &= ~L2C_BLE_NOT_DEFAULT_PARAM;
      p_lcb->conn_update_mask |= L2C_BLE_NEW_CONN_PARAM;
    }
  } else {
    /* application allows to do update, if we were delaying one do it now */
    if (p_lcb->conn_update_mask & L2C_BLE_NEW_CONN_PARAM) {
      /* if both side 4.1, or we are central device, send HCI command */
      if (p_lcb->IsLinkRoleCentral()
          || (controller_get_interface()
                  ->supports_ble_connection_parameter_request() &&
              acl_peer_supports_ble_connection_parameters_request(
                  p_lcb->remote_bd_addr))
      ) {
        btsnd_hcic_ble_upd_ll_conn_params(p_lcb->Handle(), p_lcb->min_interval,
                                          p_lcb->max_interval, p_lcb->latency,
                                          p_lcb->timeout, p_lcb->min_ce_len,
                                          p_lcb->max_ce_len);
        p_lcb->conn_update_mask |= L2C_BLE_UPDATE_PENDING;
      } else {
        l2cu_send_peer_ble_par_req(p_lcb, p_lcb->min_interval,
                                   p_lcb->max_interval, p_lcb->latency,
                                   p_lcb->timeout);
      }
      p_lcb->conn_update_mask &= ~L2C_BLE_NEW_CONN_PARAM;
      p_lcb->conn_update_mask |= L2C_BLE_NOT_DEFAULT_PARAM;
    }
  }
}

/*******************************************************************************
 *
 * Function         l2cble_process_conn_update_evt
 *
 * Description      This function enables the connection update request from
 *                  remote after a successful connection update response is
 *                  received.
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_process_conn_update_evt(uint16_t handle, uint8_t status,
                                    uint16_t interval, uint16_t latency,
                                    uint16_t timeout) {
  LOG_VERBOSE("%s", __func__);

  /* See if we have a link control block for the remote device */
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_handle(handle);
  if (!p_lcb) {
    LOG_WARN("%s: Invalid handle: %d", __func__, handle);
    return;
  }

  p_lcb->conn_update_mask &= ~L2C_BLE_UPDATE_PENDING;

  if (status != HCI_SUCCESS) {
    LOG_WARN("%s: Error status: %d", __func__, status);
  }

  l2cble_start_conn_update(p_lcb);

  l2cble_start_subrate_change(p_lcb);

  LOG_VERBOSE("%s: conn_update_mask=%d , subrate_req_mask=%d", __func__,
              p_lcb->conn_update_mask, p_lcb->subrate_req_mask);
}

/*******************************************************************************
 *
 * Function         l2cble_handle_connect_rsp_neg
 *
 * Description      This function sends error message to all the
 *                  outstanding channels
 *
 * Returns          void
 *
 ******************************************************************************/
static void l2cble_handle_connect_rsp_neg(tL2C_LCB* p_lcb,
                                          tL2C_CONN_INFO* con_info) {
  tL2C_CCB* temp_p_ccb = NULL;
  for (int i = 0; i < p_lcb->pending_ecoc_conn_cnt; i++) {
    uint16_t cid = p_lcb->pending_ecoc_connection_cids[i];
    temp_p_ccb = l2cu_find_ccb_by_cid(p_lcb, cid);
    l2c_csm_execute(temp_p_ccb, L2CEVT_L2CAP_CREDIT_BASED_CONNECT_RSP_NEG,
                    con_info);
  }

  p_lcb->pending_ecoc_conn_cnt = 0;
  memset(p_lcb->pending_ecoc_connection_cids, 0, L2CAP_CREDIT_BASED_MAX_CIDS);
}

/*******************************************************************************
 *
 * Function         l2cble_process_sig_cmd
 *
 * Description      This function is called when a signalling packet is received
 *                  on the BLE signalling CID
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_process_sig_cmd(tL2C_LCB* p_lcb, uint8_t* p, uint16_t pkt_len) {
  uint8_t* p_pkt_end;
  uint8_t cmd_code, id;
  uint16_t cmd_len;
  uint16_t min_interval, max_interval, latency, timeout;
  tL2C_CONN_INFO con_info;
  uint16_t lcid = 0, rcid = 0, mtu = 0, mps = 0, initial_credit = 0;
  tL2C_CCB *p_ccb = NULL, *temp_p_ccb = NULL;
  tL2C_RCB* p_rcb;
  uint16_t credit;
  uint8_t num_of_channels;

  p_pkt_end = p + pkt_len;

  if (p + 4 > p_pkt_end) {
    LOG(ERROR) << "invalid read";
    return;
  }

  STREAM_TO_UINT8(cmd_code, p);
  STREAM_TO_UINT8(id, p);
  STREAM_TO_UINT16(cmd_len, p);

  /* Check command length does not exceed packet length */
  if ((p + cmd_len) > p_pkt_end) {
    LOG_WARN("L2CAP - LE - format error, pkt_len: %d  cmd_len: %d  code: %d",
             pkt_len, cmd_len, cmd_code);
    return;
  }

  switch (cmd_code) {
    case L2CAP_CMD_REJECT: {
      uint16_t reason;

      if (p + 2 > p_pkt_end) {
        LOG(ERROR) << "invalid L2CAP_CMD_REJECT packet,"
                   << " not containing enough data for `reason` field";
        return;
      }

      STREAM_TO_UINT16(reason, p);

      if (reason == L2CAP_CMD_REJ_NOT_UNDERSTOOD &&
          p_lcb->pending_ecoc_conn_cnt > 0) {
        con_info.l2cap_result = L2CAP_LE_RESULT_NO_PSM;
        l2cble_handle_connect_rsp_neg(p_lcb, &con_info);
      }
    } break;

    case L2CAP_CMD_ECHO_REQ:
    case L2CAP_CMD_ECHO_RSP:
    case L2CAP_CMD_INFO_RSP:
    case L2CAP_CMD_INFO_REQ:
      l2cu_send_peer_cmd_reject(p_lcb, L2CAP_CMD_REJ_NOT_UNDERSTOOD, id, 0, 0);
      break;

    case L2CAP_CMD_BLE_UPDATE_REQ:
      if (p + 8 > p_pkt_end) {
        LOG(ERROR) << "invalid read";
        return;
      }

      STREAM_TO_UINT16(min_interval, p); /* 0x0006 - 0x0C80 */
      STREAM_TO_UINT16(max_interval, p); /* 0x0006 - 0x0C80 */
      STREAM_TO_UINT16(latency, p);      /* 0x0000 - 0x03E8 */
      STREAM_TO_UINT16(timeout, p);      /* 0x000A - 0x0C80 */
      /* If we are a central, the peripheral wants to update the parameters */
      if (p_lcb->IsLinkRoleCentral()) {
        L2CA_AdjustConnectionIntervals(&min_interval, &max_interval,
                                       BTM_BLE_CONN_INT_MIN_LIMIT);

        if (min_interval < BTM_BLE_CONN_INT_MIN ||
            min_interval > BTM_BLE_CONN_INT_MAX ||
            max_interval < BTM_BLE_CONN_INT_MIN ||
            max_interval > BTM_BLE_CONN_INT_MAX ||
            latency > BTM_BLE_CONN_LATENCY_MAX ||
            /*(timeout >= max_interval && latency > (timeout * 10/(max_interval
               * 1.25) - 1)) ||*/
            timeout < BTM_BLE_CONN_SUP_TOUT_MIN ||
            timeout > BTM_BLE_CONN_SUP_TOUT_MAX ||
            max_interval < min_interval) {
          l2cu_send_peer_ble_par_rsp(p_lcb, L2CAP_CFG_UNACCEPTABLE_PARAMS, id);
        } else {
          l2cu_send_peer_ble_par_rsp(p_lcb, L2CAP_CFG_OK, id);

          p_lcb->min_interval = min_interval;
          p_lcb->max_interval = max_interval;
          p_lcb->latency = latency;
          p_lcb->timeout = timeout;
          p_lcb->conn_update_mask |= L2C_BLE_NEW_CONN_PARAM;

          l2cble_start_conn_update(p_lcb);
        }
      } else
        l2cu_send_peer_cmd_reject(p_lcb, L2CAP_CMD_REJ_NOT_UNDERSTOOD, id, 0,
                                  0);
      break;

    case L2CAP_CMD_BLE_UPDATE_RSP:
      p += 2;
      break;

    case L2CAP_CMD_CREDIT_BASED_CONN_REQ: {
      if (p + 10 > p_pkt_end) {
        LOG(ERROR) << "invalid L2CAP_CMD_CREDIT_BASED_CONN_REQ len";
        return;
      }

      STREAM_TO_UINT16(con_info.psm, p);
      STREAM_TO_UINT16(mtu, p);
      STREAM_TO_UINT16(mps, p);
      STREAM_TO_UINT16(initial_credit, p);

      /* Check how many channels remote side wants. */
      num_of_channels = (p_pkt_end - p) / sizeof(uint16_t);
      if (num_of_channels > L2CAP_CREDIT_BASED_MAX_CIDS) {
        LOG_WARN("L2CAP - invalid number of channels requested: %d",
                 num_of_channels);
        l2cu_reject_credit_based_conn_req(p_lcb, id,
                                          L2CAP_CREDIT_BASED_MAX_CIDS,
                                          L2CAP_LE_RESULT_INVALID_PARAMETERS);
        return;
      }

      LOG_DEBUG(
          "Recv L2CAP_CMD_CREDIT_BASED_CONN_REQ with "
          "mtu = %d, "
          "mps = %d, "
          "initial credit = %d"
          "num_of_channels = %d",
          mtu, mps, initial_credit, num_of_channels);

      /* Check PSM Support */
      p_rcb = l2cu_find_ble_rcb_by_psm(con_info.psm);
      if (p_rcb == NULL) {
        LOG_WARN("L2CAP - rcvd conn req for unknown PSM: 0x%04x", con_info.psm);
        l2cu_reject_credit_based_conn_req(p_lcb, id, num_of_channels,
                                          L2CAP_LE_RESULT_NO_PSM);
        return;
      }

      if (p_lcb->pending_ecoc_conn_cnt > 0) {
        LOG_WARN("L2CAP - L2CAP_CMD_CREDIT_BASED_CONN_REQ collision:");
        if (p_rcb->api.pL2CA_CreditBasedCollisionInd_Cb &&
            con_info.psm == BT_PSM_EATT) {
          (*p_rcb->api.pL2CA_CreditBasedCollisionInd_Cb)(p_lcb->remote_bd_addr);
        }
        l2cu_reject_credit_based_conn_req(p_lcb, id, num_of_channels,
                                          L2CAP_LE_RESULT_NO_RESOURCES);
        return;
      }

      p_lcb->pending_ecoc_conn_cnt = num_of_channels;

      if (!p_rcb->api.pL2CA_CreditBasedConnectInd_Cb) {
        LOG_WARN("L2CAP - rcvd conn req for outgoing-only connection PSM: %d",
                 con_info.psm);
        l2cu_reject_credit_based_conn_req(p_lcb, id, num_of_channels,
                                          L2CAP_CONN_NO_PSM);
        return;
      }

      /* validate the parameters */
      if (mtu < L2CAP_CREDIT_BASED_MIN_MTU ||
          mps < L2CAP_CREDIT_BASED_MIN_MPS || mps > L2CAP_LE_MAX_MPS) {
        LOG_ERROR("L2CAP don't like the params");
        l2cu_reject_credit_based_conn_req(p_lcb, id, num_of_channels,
                                          L2CAP_LE_RESULT_INVALID_PARAMETERS);
        return;
      }

      bool lead_cid_set = false;

      for (int i = 0; i < num_of_channels; i++) {
        STREAM_TO_UINT16(rcid, p);
        temp_p_ccb = l2cu_find_ccb_by_remote_cid(p_lcb, rcid);
        if (temp_p_ccb) {
          LOG_WARN("L2CAP - rcvd conn req for duplicated cid: 0x%04x", rcid);
          p_lcb->pending_ecoc_connection_cids[i] = 0;
          p_lcb->pending_l2cap_result =
              L2CAP_LE_RESULT_SOURCE_CID_ALREADY_ALLOCATED;
        } else {
          /* Allocate a ccb for this.*/
          temp_p_ccb = l2cu_allocate_ccb(
              p_lcb, 0, con_info.psm == BT_PSM_EATT /* is_eatt */);
          if (temp_p_ccb == NULL) {
            LOG_ERROR("L2CAP - unable to allocate CCB");
            p_lcb->pending_ecoc_connection_cids[i] = 0;
            p_lcb->pending_l2cap_result = L2CAP_LE_RESULT_NO_RESOURCES;
            continue;
          }

          temp_p_ccb->ecoc = true;
          temp_p_ccb->remote_id = id;
          temp_p_ccb->p_rcb = p_rcb;
          temp_p_ccb->remote_cid = rcid;

          temp_p_ccb->peer_conn_cfg.mtu = mtu;
          temp_p_ccb->peer_conn_cfg.mps = mps;
          temp_p_ccb->peer_conn_cfg.credits = initial_credit;

          temp_p_ccb->tx_mps = mps;
          temp_p_ccb->ble_sdu = NULL;
          temp_p_ccb->ble_sdu_length = 0;
          temp_p_ccb->is_first_seg = true;
          temp_p_ccb->peer_cfg.fcr.mode = L2CAP_FCR_LE_COC_MODE;

          /* This list will be used to prepare response */
          p_lcb->pending_ecoc_connection_cids[i] = temp_p_ccb->local_cid;

          /*This is going to be our lead p_ccb for state machine */
          if (!lead_cid_set) {
            p_ccb = temp_p_ccb;
            p_ccb->local_conn_cfg.mtu = L2CAP_SDU_LENGTH_LE_MAX;
            p_ccb->local_conn_cfg.mps =
                controller_get_interface()->get_acl_data_size_ble();
            p_lcb->pending_lead_cid = p_ccb->local_cid;
            lead_cid_set = true;
          }
        }
      }

      if (!lead_cid_set) {
        LOG_ERROR("L2CAP - unable to allocate CCB");
        l2cu_reject_credit_based_conn_req(p_lcb, id, num_of_channels,
                                          p_lcb->pending_l2cap_result);
        return;
      }

      LOG_DEBUG("L2CAP - processing peer credit based connect request");
      l2c_csm_execute(p_ccb, L2CEVT_L2CAP_CREDIT_BASED_CONNECT_REQ, NULL);
      break;
    }
    case L2CAP_CMD_CREDIT_BASED_CONN_RES:
      if (p + 8 > p_pkt_end) {
        LOG(ERROR) << "invalid L2CAP_CMD_CREDIT_BASED_CONN_RES len";
        return;
      }

      LOG_VERBOSE("Recv L2CAP_CMD_CREDIT_BASED_CONN_RES");
      /* For all channels, see whose identifier matches this id */
      for (temp_p_ccb = p_lcb->ccb_queue.p_first_ccb; temp_p_ccb;
           temp_p_ccb = temp_p_ccb->p_next_ccb) {
        if (temp_p_ccb->local_id == id) {
          p_ccb = temp_p_ccb;
          break;
        }
      }

      if (!p_ccb) {
        LOG_VERBOSE(" Cannot find matching connection req");
        con_info.l2cap_result = L2CAP_LE_RESULT_INVALID_SOURCE_CID;
        l2c_csm_execute(p_ccb, L2CEVT_L2CAP_CONNECT_RSP_NEG, &con_info);
        return;
      }

      STREAM_TO_UINT16(mtu, p);
      STREAM_TO_UINT16(mps, p);
      STREAM_TO_UINT16(initial_credit, p);
      STREAM_TO_UINT16(con_info.l2cap_result, p);

      /* When one of these result is sent back that means,
       * all the channels has been rejected
       */
      if (con_info.l2cap_result == L2CAP_LE_RESULT_NO_PSM ||
          con_info.l2cap_result ==
              L2CAP_LE_RESULT_INSUFFICIENT_AUTHENTICATION ||
          con_info.l2cap_result == L2CAP_LE_RESULT_INSUFFICIENT_ENCRYP ||
          con_info.l2cap_result == L2CAP_LE_RESULT_INSUFFICIENT_AUTHORIZATION ||
          con_info.l2cap_result == L2CAP_LE_RESULT_UNACCEPTABLE_PARAMETERS ||
          con_info.l2cap_result == L2CAP_LE_RESULT_INVALID_PARAMETERS) {
        LOG_ERROR("L2CAP - not accepted. Status %d", con_info.l2cap_result);
        l2cble_handle_connect_rsp_neg(p_lcb, &con_info);
        return;
      }

      /* validate the parameters */
      if (mtu < L2CAP_CREDIT_BASED_MIN_MTU ||
          mps < L2CAP_CREDIT_BASED_MIN_MPS || mps > L2CAP_LE_MAX_MPS) {
        LOG_ERROR("L2CAP - invalid params");
        con_info.l2cap_result = L2CAP_LE_RESULT_INVALID_PARAMETERS;
        l2cble_handle_connect_rsp_neg(p_lcb, &con_info);
        return;
      }

      /* At least some of the channels has been created and parameters are
       * good*/
      num_of_channels = (p_pkt_end - p) / sizeof(uint16_t);
      if (num_of_channels != p_lcb->pending_ecoc_conn_cnt) {
        LOG_ERROR(
            "Incorrect response."
            "expected num of channels = %d"
            "received num of channels = %d",
            num_of_channels, p_lcb->pending_ecoc_conn_cnt);
        return;
      }

      LOG_VERBOSE(
          "mtu = %d, "
          "mps = %d, "
          "initial_credit = %d, "
          "con_info.l2cap_result = %d"
          "num_of_channels = %d",
          mtu, mps, initial_credit, con_info.l2cap_result, num_of_channels);

      con_info.peer_mtu = mtu;

      /* Copy request data and clear it so user can perform another connect if
       * needed in the callback. */
      p_lcb->pending_ecoc_conn_cnt = 0;
      uint16_t cids[L2CAP_CREDIT_BASED_MAX_CIDS];
      std::copy_n(p_lcb->pending_ecoc_connection_cids,
                  L2CAP_CREDIT_BASED_MAX_CIDS, cids);
      std::fill_n(p_lcb->pending_ecoc_connection_cids,
                  L2CAP_CREDIT_BASED_MAX_CIDS, 0);

      for (int i = 0; i < num_of_channels; i++) {
        uint16_t cid = cids[i];
        STREAM_TO_UINT16(rcid, p);

        if (rcid != 0) {
          /* If remote cid is duplicated then disconnect original channel
           * and current channel by sending event to upper layer
           */
          temp_p_ccb = l2cu_find_ccb_by_remote_cid(p_lcb, rcid);
          if (temp_p_ccb != nullptr) {
            LOG_ERROR(
                "Already Allocated Destination cid. "
                "rcid = %d "
                "send peer_disc_req",
                rcid);

            l2cu_send_peer_disc_req(temp_p_ccb);

            temp_p_ccb = l2cu_find_ccb_by_cid(p_lcb, cid);
            con_info.l2cap_result = L2CAP_LE_RESULT_UNACCEPTABLE_PARAMETERS;
            l2c_csm_execute(temp_p_ccb,
                            L2CEVT_L2CAP_CREDIT_BASED_CONNECT_RSP_NEG,
                            &con_info);
            continue;
          }
        }

        temp_p_ccb = l2cu_find_ccb_by_cid(p_lcb, cid);
        temp_p_ccb->remote_cid = rcid;

        LOG_VERBOSE(
            "local cid = %d "
            "remote cid = %d",
            cid, temp_p_ccb->remote_cid);

        /* Check if peer accepted channel, if not release the one not
         * created
         */
        if (temp_p_ccb->remote_cid == 0) {
          l2c_csm_execute(temp_p_ccb, L2CEVT_L2CAP_CREDIT_BASED_CONNECT_RSP_NEG,
                          &con_info);
        } else {
          temp_p_ccb->tx_mps = mps;
          temp_p_ccb->ble_sdu = NULL;
          temp_p_ccb->ble_sdu_length = 0;
          temp_p_ccb->is_first_seg = true;
          temp_p_ccb->peer_cfg.fcr.mode = L2CAP_FCR_LE_COC_MODE;
          temp_p_ccb->peer_conn_cfg.mtu = mtu;
          temp_p_ccb->peer_conn_cfg.mps = mps;
          temp_p_ccb->peer_conn_cfg.credits = initial_credit;

          l2c_csm_execute(temp_p_ccb, L2CEVT_L2CAP_CREDIT_BASED_CONNECT_RSP,
                          &con_info);
        }
      }

      break;
    case L2CAP_CMD_CREDIT_BASED_RECONFIG_REQ: {
      if (p + 6 > p_pkt_end) {
        l2cu_send_ble_reconfig_rsp(p_lcb, id, L2CAP_RECONFIG_UNACCAPTED_PARAM);
        return;
      }

      STREAM_TO_UINT16(mtu, p);
      STREAM_TO_UINT16(mps, p);

      /* validate the parameters */
      if (mtu < L2CAP_CREDIT_BASED_MIN_MTU ||
          mps < L2CAP_CREDIT_BASED_MIN_MPS || mps > L2CAP_LE_MAX_MPS) {
        LOG_ERROR("L2CAP - invalid params");
        l2cu_send_ble_reconfig_rsp(p_lcb, id, L2CAP_RECONFIG_UNACCAPTED_PARAM);
        return;
      }

      /* Check how many channels remote side wants to reconfigure */
      num_of_channels = (p_pkt_end - p) / sizeof(uint16_t);

      LOG_VERBOSE(
          "Recv L2CAP_CMD_CREDIT_BASED_RECONFIG_REQ with "
          "mtu = %d, "
          "mps = %d, "
          "num_of_channels = %d",
          mtu, mps, num_of_channels);

      uint8_t* p_tmp = p;
      for (int i = 0; i < num_of_channels; i++) {
        STREAM_TO_UINT16(rcid, p_tmp);
        p_ccb = l2cu_find_ccb_by_remote_cid(p_lcb, rcid);
        if (!p_ccb) {
          LOG_WARN("L2CAP - rcvd config req for non existing cid: 0x%04x",
                   rcid);
          l2cu_send_ble_reconfig_rsp(p_lcb, id, L2CAP_RECONFIG_INVALID_DCID);
          return;
        }

        if (p_ccb->peer_conn_cfg.mtu > mtu) {
          LOG_WARN(
              "L2CAP - rcvd config req mtu reduction new mtu < mtu (%d < %d)",
              mtu, p_ccb->peer_conn_cfg.mtu);
          l2cu_send_ble_reconfig_rsp(p_lcb, id,
                                     L2CAP_RECONFIG_REDUCTION_MTU_NO_ALLOWED);
          return;
        }

        if (p_ccb->peer_conn_cfg.mps > mps && num_of_channels > 1) {
          LOG_WARN(
              "L2CAP - rcvd config req mps reduction new mps < mps (%d < %d)",
              mtu, p_ccb->peer_conn_cfg.mtu);
          l2cu_send_ble_reconfig_rsp(p_lcb, id,
                                     L2CAP_RECONFIG_REDUCTION_MPS_NO_ALLOWED);
          return;
        }
      }

      for (int i = 0; i < num_of_channels; i++) {
        STREAM_TO_UINT16(rcid, p);

        /* Store new values */
        p_ccb = l2cu_find_ccb_by_remote_cid(p_lcb, rcid);
        p_ccb->peer_conn_cfg.mtu = mtu;
        p_ccb->peer_conn_cfg.mps = mps;
        p_ccb->tx_mps = mps;

        tL2CAP_LE_CFG_INFO le_cfg;
        le_cfg.mps = mps;
        le_cfg.mtu = mtu;

        l2c_csm_execute(p_ccb, L2CEVT_L2CAP_CREDIT_BASED_RECONFIG_REQ, &le_cfg);
      }

      l2cu_send_ble_reconfig_rsp(p_lcb, id, L2CAP_RECONFIG_SUCCEED);

      break;
    }

    case L2CAP_CMD_CREDIT_BASED_RECONFIG_RES: {
      uint16_t result;
      if (p + sizeof(uint16_t) > p_pkt_end) {
        LOG(ERROR) << "invalid read";
        return;
      }
      STREAM_TO_UINT16(result, p);

      LOG_VERBOSE(
          "Recv L2CAP_CMD_CREDIT_BASED_RECONFIG_RES for "
          "result = 0x%04x",
          result);

      p_lcb->pending_ecoc_reconfig_cfg.result = result;

      /* All channels which are in reconfiguration state are marked with
       * reconfig_started flag. Find it and send response
       */
      for (temp_p_ccb = p_lcb->ccb_queue.p_first_ccb; temp_p_ccb;
           temp_p_ccb = temp_p_ccb->p_next_ccb) {
        if ((temp_p_ccb->in_use) && (temp_p_ccb->reconfig_started)) {
          l2c_csm_execute(temp_p_ccb, L2CEVT_L2CAP_CREDIT_BASED_RECONFIG_RSP,
                          &p_lcb->pending_ecoc_reconfig_cfg);

          temp_p_ccb->reconfig_started = false;
          if (result == L2CAP_CFG_OK) {
            temp_p_ccb->local_conn_cfg = p_lcb->pending_ecoc_reconfig_cfg;
          }
        }
      }

      break;
    }

    case L2CAP_CMD_BLE_CREDIT_BASED_CONN_REQ:
      if (p + 10 > p_pkt_end) {
        LOG(ERROR) << "invalid read";
        return;
      }

      STREAM_TO_UINT16(con_info.psm, p);
      STREAM_TO_UINT16(rcid, p);
      STREAM_TO_UINT16(mtu, p);
      STREAM_TO_UINT16(mps, p);
      STREAM_TO_UINT16(initial_credit, p);

      LOG_VERBOSE(
          "Recv L2CAP_CMD_BLE_CREDIT_BASED_CONN_REQ with "
          "mtu = %d, "
          "mps = %d, "
          "initial credit = %d",
          mtu, mps, initial_credit);

      p_ccb = l2cu_find_ccb_by_remote_cid(p_lcb, rcid);
      if (p_ccb) {
        LOG_WARN("L2CAP - rcvd conn req for duplicated cid: 0x%04x", rcid);
        l2cu_reject_ble_coc_connection(
            p_lcb, id, L2CAP_LE_RESULT_SOURCE_CID_ALREADY_ALLOCATED);
        break;
      }

      p_rcb = l2cu_find_ble_rcb_by_psm(con_info.psm);
      if (p_rcb == NULL) {
        LOG_WARN("L2CAP - rcvd conn req for unknown PSM: 0x%04x", con_info.psm);
        l2cu_reject_ble_coc_connection(p_lcb, id, L2CAP_LE_RESULT_NO_PSM);
        break;
      } else {
        if (!p_rcb->api.pL2CA_ConnectInd_Cb) {
          LOG_WARN("L2CAP - rcvd conn req for outgoing-only connection PSM: %d",
                   con_info.psm);
          l2cu_reject_ble_coc_connection(p_lcb, id, L2CAP_CONN_NO_PSM);
          break;
        }
      }

      /* Allocate a ccb for this.*/
      p_ccb = l2cu_allocate_ccb(p_lcb, 0,
                                con_info.psm == BT_PSM_EATT /* is_eatt */);
      if (p_ccb == NULL) {
        LOG_ERROR("L2CAP - unable to allocate CCB");
        l2cu_reject_ble_connection(p_ccb, id, L2CAP_CONN_NO_RESOURCES);
        break;
      }

      /* validate the parameters */
      if (mtu < L2CAP_LE_MIN_MTU || mps < L2CAP_LE_MIN_MPS ||
          mps > L2CAP_LE_MAX_MPS) {
        LOG_ERROR("L2CAP do not like the params");
        l2cu_reject_ble_connection(p_ccb, id, L2CAP_CONN_NO_RESOURCES);
        break;
      }

      p_ccb->remote_id = id;
      p_ccb->p_rcb = p_rcb;
      p_ccb->remote_cid = rcid;

      p_ccb->local_conn_cfg.mtu = L2CAP_SDU_LENGTH_LE_MAX;
      p_ccb->local_conn_cfg.mps =
          controller_get_interface()->get_acl_data_size_ble();
      p_ccb->local_conn_cfg.credits = L2CA_LeCreditDefault();
      p_ccb->remote_credit_count = L2CA_LeCreditDefault();

      p_ccb->peer_conn_cfg.mtu = mtu;
      p_ccb->peer_conn_cfg.mps = mps;
      p_ccb->peer_conn_cfg.credits = initial_credit;

      p_ccb->tx_mps = mps;
      p_ccb->ble_sdu = NULL;
      p_ccb->ble_sdu_length = 0;
      p_ccb->is_first_seg = true;
      p_ccb->peer_cfg.fcr.mode = L2CAP_FCR_LE_COC_MODE;

      p_ccb->connection_initiator = L2CAP_INITIATOR_REMOTE;

      l2c_csm_execute(p_ccb, L2CEVT_L2CAP_CONNECT_REQ, &con_info);
      break;

    case L2CAP_CMD_BLE_CREDIT_BASED_CONN_RES:
      LOG_VERBOSE("Recv L2CAP_CMD_BLE_CREDIT_BASED_CONN_RES");
      /* For all channels, see whose identifier matches this id */
      for (temp_p_ccb = p_lcb->ccb_queue.p_first_ccb; temp_p_ccb;
           temp_p_ccb = temp_p_ccb->p_next_ccb) {
        if (temp_p_ccb->local_id == id) {
          p_ccb = temp_p_ccb;
          break;
        }
      }
      if (p_ccb) {
        LOG_VERBOSE("I remember the connection req");
        if (p + 10 > p_pkt_end) {
          LOG(ERROR) << "invalid read";
          return;
        }

        STREAM_TO_UINT16(p_ccb->remote_cid, p);
        STREAM_TO_UINT16(p_ccb->peer_conn_cfg.mtu, p);
        STREAM_TO_UINT16(p_ccb->peer_conn_cfg.mps, p);
        STREAM_TO_UINT16(p_ccb->peer_conn_cfg.credits, p);
        STREAM_TO_UINT16(con_info.l2cap_result, p);
        con_info.remote_cid = p_ccb->remote_cid;

        LOG_VERBOSE(
            "remote_cid = %d, "
            "mtu = %d, "
            "mps = %d, "
            "initial_credit = %d, "
            "con_info.l2cap_result = %d",
            p_ccb->remote_cid, p_ccb->peer_conn_cfg.mtu,
            p_ccb->peer_conn_cfg.mps, p_ccb->peer_conn_cfg.credits,
            con_info.l2cap_result);

        /* validate the parameters */
        if (p_ccb->peer_conn_cfg.mtu < L2CAP_LE_MIN_MTU ||
            p_ccb->peer_conn_cfg.mps < L2CAP_LE_MIN_MPS ||
            p_ccb->peer_conn_cfg.mps > L2CAP_LE_MAX_MPS) {
          LOG_ERROR("L2CAP do not like the params");
          con_info.l2cap_result = L2CAP_LE_RESULT_NO_RESOURCES;
          l2c_csm_execute(p_ccb, L2CEVT_L2CAP_CONNECT_RSP_NEG, &con_info);
          break;
        }

        p_ccb->tx_mps = p_ccb->peer_conn_cfg.mps;
        p_ccb->ble_sdu = NULL;
        p_ccb->ble_sdu_length = 0;
        p_ccb->is_first_seg = true;
        p_ccb->peer_cfg.fcr.mode = L2CAP_FCR_LE_COC_MODE;

        if (con_info.l2cap_result == L2CAP_LE_RESULT_CONN_OK)
          l2c_csm_execute(p_ccb, L2CEVT_L2CAP_CONNECT_RSP, &con_info);
        else
          l2c_csm_execute(p_ccb, L2CEVT_L2CAP_CONNECT_RSP_NEG, &con_info);
      } else {
        LOG_VERBOSE("I DO NOT remember the connection req");
        con_info.l2cap_result = L2CAP_LE_RESULT_INVALID_SOURCE_CID;
        l2c_csm_execute(p_ccb, L2CEVT_L2CAP_CONNECT_RSP_NEG, &con_info);
      }
      break;

    case L2CAP_CMD_BLE_FLOW_CTRL_CREDIT:
      if (p + 4 > p_pkt_end) {
        LOG(ERROR) << "invalid read";
        return;
      }

      STREAM_TO_UINT16(lcid, p);
      p_ccb = l2cu_find_ccb_by_remote_cid(p_lcb, lcid);
      if (p_ccb == NULL) {
        LOG_VERBOSE("%s Credit received for unknown channel id %d", __func__,
                    lcid);
        break;
      }

      STREAM_TO_UINT16(credit, p);
      l2c_csm_execute(p_ccb, L2CEVT_L2CAP_RECV_FLOW_CONTROL_CREDIT, &credit);
      LOG_VERBOSE("%s Credit received", __func__);
      break;

    case L2CAP_CMD_DISC_REQ:
      if (p + 4 > p_pkt_end) {
        return;
      }
      STREAM_TO_UINT16(lcid, p);
      STREAM_TO_UINT16(rcid, p);

      p_ccb = l2cu_find_ccb_by_cid(p_lcb, lcid);
      if (p_ccb != NULL) {
        if (p_ccb->remote_cid == rcid) {
          p_ccb->remote_id = id;
          l2c_csm_execute(p_ccb, L2CEVT_L2CAP_DISCONNECT_REQ, NULL);
        }
      } else
        l2cu_send_peer_cmd_reject(p_lcb, L2CAP_CMD_REJ_INVALID_CID, id, 0, 0);

      break;

    case L2CAP_CMD_DISC_RSP:
      if (p + 4 > p_pkt_end) {
        LOG(ERROR) << "invalid read";
        return;
      }
      STREAM_TO_UINT16(rcid, p);
      STREAM_TO_UINT16(lcid, p);

      p_ccb = l2cu_find_ccb_by_cid(p_lcb, lcid);
      if (p_ccb != NULL) {
        if ((p_ccb->remote_cid == rcid) && (p_ccb->local_id == id))
          l2c_csm_execute(p_ccb, L2CEVT_L2CAP_DISCONNECT_RSP, NULL);
      }
      break;

    default:
      LOG_WARN("L2CAP - LE - unknown cmd code: %d", cmd_code);
      l2cu_send_peer_cmd_reject(p_lcb, L2CAP_CMD_REJ_NOT_UNDERSTOOD, id, 0, 0);
      break;
  }
}

/** This function is to initate a direct connection. Returns true if connection
 * initiated, false otherwise. */
bool l2cble_create_conn(tL2C_LCB* p_lcb) {
  if (!acl_create_le_connection(p_lcb->remote_bd_addr)) {
    return false;
  }

  p_lcb->link_state = LST_CONNECTING;

  // TODO: we should not need this timer at all, the connection failure should
  // be reported from lower layer
  alarm_set_on_mloop(p_lcb->l2c_lcb_timer, L2CAP_BLE_LINK_CONNECT_TIMEOUT_MS,
                     l2c_lcb_timer_timeout, p_lcb);
  return true;
}

/*******************************************************************************
 *
 * Function         l2c_link_processs_ble_num_bufs
 *
 * Description      This function is called when a "controller buffer size"
 *                  event is first received from the controller. It updates
 *                  the L2CAP values.
 *
 * Returns          void
 *
 ******************************************************************************/
void l2c_link_processs_ble_num_bufs(uint16_t num_lm_ble_bufs) {
  if (num_lm_ble_bufs == 0) {
    num_lm_ble_bufs = L2C_DEF_NUM_BLE_BUF_SHARED;
    l2cb.num_lm_acl_bufs -= L2C_DEF_NUM_BLE_BUF_SHARED;
  }

  l2cb.num_lm_ble_bufs = num_lm_ble_bufs;
  l2cb.controller_le_xmit_window = num_lm_ble_bufs;
}

/*******************************************************************************
 *
 * Function         l2c_ble_link_adjust_allocation
 *
 * Description      This function is called when a link is created or removed
 *                  to calculate the amount of packets each link may send to
 *                  the HCI without an ack coming back.
 *
 *                  Currently, this is a simple allocation, dividing the
 *                  number of Controller Packets by the number of links. In
 *                  the future, QOS configuration should be examined.
 *
 * Returns          void
 *
 ******************************************************************************/
void l2c_ble_link_adjust_allocation(void) {
  uint16_t qq, yy, qq_remainder;
  tL2C_LCB* p_lcb;
  uint16_t hi_quota, low_quota;
  uint16_t num_lowpri_links = 0;
  uint16_t num_hipri_links = 0;
  uint16_t controller_xmit_quota = l2cb.num_lm_ble_bufs;
  uint16_t high_pri_link_quota = L2CAP_HIGH_PRI_MIN_XMIT_QUOTA_A;

  /* If no links active, reset buffer quotas and controller buffers */
  if (l2cb.num_ble_links_active == 0) {
    l2cb.controller_le_xmit_window = l2cb.num_lm_ble_bufs;
    l2cb.ble_round_robin_quota = l2cb.ble_round_robin_unacked = 0;
    return;
  }

  /* First, count the links */
  for (yy = 0, p_lcb = &l2cb.lcb_pool[0]; yy < MAX_L2CAP_LINKS; yy++, p_lcb++) {
    if (p_lcb->in_use && p_lcb->transport == BT_TRANSPORT_LE) {
      if (p_lcb->acl_priority == L2CAP_PRIORITY_HIGH)
        num_hipri_links++;
      else
        num_lowpri_links++;
    }
  }

  /* now adjust high priority link quota */
  low_quota = num_lowpri_links ? 1 : 0;
  while ((num_hipri_links * high_pri_link_quota + low_quota) >
         controller_xmit_quota)
    high_pri_link_quota--;

  /* Work out the xmit quota and buffer quota high and low priorities */
  hi_quota = num_hipri_links * high_pri_link_quota;
  low_quota =
      (hi_quota < controller_xmit_quota) ? controller_xmit_quota - hi_quota : 1;

  /* Work out and save the HCI xmit quota for each low priority link */

  /* If each low priority link cannot have at least one buffer */
  if (num_lowpri_links > low_quota) {
    l2cb.ble_round_robin_quota = low_quota;
    qq = qq_remainder = 0;
  }
  /* If each low priority link can have at least one buffer */
  else if (num_lowpri_links > 0) {
    l2cb.ble_round_robin_quota = 0;
    l2cb.ble_round_robin_unacked = 0;
    qq = low_quota / num_lowpri_links;
    qq_remainder = low_quota % num_lowpri_links;
  }
  /* If no low priority link */
  else {
    l2cb.ble_round_robin_quota = 0;
    l2cb.ble_round_robin_unacked = 0;
    qq = qq_remainder = 0;
  }
  LOG_VERBOSE(
      "l2c_ble_link_adjust_allocation  num_hipri: %u  num_lowpri: %u  "
      "low_quota: %u  round_robin_quota: %u  qq: %u",
      num_hipri_links, num_lowpri_links, low_quota, l2cb.ble_round_robin_quota,
      qq);

  /* Now, assign the quotas to each link */
  for (yy = 0, p_lcb = &l2cb.lcb_pool[0]; yy < MAX_L2CAP_LINKS; yy++, p_lcb++) {
    if (p_lcb->in_use && p_lcb->transport == BT_TRANSPORT_LE) {
      if (p_lcb->acl_priority == L2CAP_PRIORITY_HIGH) {
        p_lcb->link_xmit_quota = high_pri_link_quota;
      } else {
        /* Safety check in case we switched to round-robin with something
         * outstanding */
        /* if sent_not_acked is added into round_robin_unacked then do not add
         * it again */
        /* l2cap keeps updating sent_not_acked for exiting from round robin */
        if ((p_lcb->link_xmit_quota > 0) && (qq == 0))
          l2cb.ble_round_robin_unacked += p_lcb->sent_not_acked;

        p_lcb->link_xmit_quota = qq;
        if (qq_remainder > 0) {
          p_lcb->link_xmit_quota++;
          qq_remainder--;
        }
      }

      LOG_VERBOSE(
          "l2c_ble_link_adjust_allocation LCB %d   Priority: %d  XmitQuota: %d",
          yy, p_lcb->acl_priority, p_lcb->link_xmit_quota);

      LOG_VERBOSE("        SentNotAcked: %d  RRUnacked: %d",
                  p_lcb->sent_not_acked, l2cb.round_robin_unacked);

      /* There is a special case where we have readjusted the link quotas and */
      /* this link may have sent anything but some other link sent packets so */
      /* so we may need a timer to kick off this link's transmissions. */
      if ((p_lcb->link_state == LST_CONNECTED) &&
          (!list_is_empty(p_lcb->link_xmit_data_q)) &&
          (p_lcb->sent_not_acked < p_lcb->link_xmit_quota)) {
        alarm_set_on_mloop(p_lcb->l2c_lcb_timer,
                           L2CAP_LINK_FLOW_CONTROL_TIMEOUT_MS,
                           l2c_lcb_timer_timeout, p_lcb);
      }
    }
  }
}

/*******************************************************************************
 *
 * Function         l2cble_process_rc_param_request_evt
 *
 * Description      process LE Remote Connection Parameter Request Event.
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_process_rc_param_request_evt(uint16_t handle, uint16_t int_min,
                                         uint16_t int_max, uint16_t latency,
                                         uint16_t timeout) {
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_handle(handle);

  if (p_lcb != NULL) {
    p_lcb->min_interval = int_min;
    p_lcb->max_interval = int_max;
    p_lcb->latency = latency;
    p_lcb->timeout = timeout;

    /* if update is enabled, always accept connection parameter update */
    if ((p_lcb->conn_update_mask & L2C_BLE_CONN_UPDATE_DISABLE) == 0) {
      btsnd_hcic_ble_rc_param_req_reply(handle, int_min, int_max, latency,
                                        timeout, 0, 0);
    } else {
      LOG_VERBOSE("L2CAP - LE - update currently disabled");
      p_lcb->conn_update_mask |= L2C_BLE_NEW_CONN_PARAM;
      btsnd_hcic_ble_rc_param_req_neg_reply(handle,
                                            HCI_ERR_UNACCEPT_CONN_INTERVAL);
    }

  } else {
    LOG_WARN("No link to update connection parameter");
  }
}

/*******************************************************************************
 *
 * Function         l2cble_update_data_length
 *
 * Description      This function update link tx data length if applicable
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_update_data_length(tL2C_LCB* p_lcb) {
  uint16_t tx_mtu = 0;
  uint16_t i = 0;

  LOG_VERBOSE("%s", __func__);

  /* See if we have a link control block for the connection */
  if (p_lcb == NULL) return;

  for (i = 0; i < L2CAP_NUM_FIXED_CHNLS; i++) {
    if (i + L2CAP_FIRST_FIXED_CHNL != L2CAP_BLE_SIGNALLING_CID) {
      if ((p_lcb->p_fixed_ccbs[i] != NULL) &&
          (tx_mtu < (p_lcb->p_fixed_ccbs[i]->tx_data_len + L2CAP_PKT_OVERHEAD)))
        tx_mtu = p_lcb->p_fixed_ccbs[i]->tx_data_len + L2CAP_PKT_OVERHEAD;
    }
  }

  if (tx_mtu > BTM_BLE_DATA_SIZE_MAX) tx_mtu = BTM_BLE_DATA_SIZE_MAX;

  /* update TX data length if changed */
  if (p_lcb->tx_data_len != tx_mtu)
    BTM_SetBleDataLength(p_lcb->remote_bd_addr, tx_mtu);
}

/*******************************************************************************
 *
 * Function         l2cble_process_data_length_change_evt
 *
 * Description      This function process the data length change event
 *
 * Returns          void
 *
 ******************************************************************************/
static bool is_legal_tx_data_len(const uint16_t& tx_data_len) {
  return (tx_data_len >= 0x001B && tx_data_len <= 0x00FB);
}

void l2cble_process_data_length_change_event(uint16_t handle,
                                             uint16_t tx_data_len,
                                             uint16_t rx_data_len) {
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_handle(handle);
  if (p_lcb == nullptr) {
    LOG_WARN("Received data length change event for unknown ACL handle:0x%04x",
             handle);
    return;
  }

  if (is_legal_tx_data_len(tx_data_len)) {
    if (p_lcb->tx_data_len != tx_data_len) {
      LOG_DEBUG(
          "Received data length change event for device:%s tx_data_len:%hu => "
          "%hu",
          ADDRESS_TO_LOGGABLE_CSTR(p_lcb->remote_bd_addr), p_lcb->tx_data_len,
          tx_data_len);
      BTM_LogHistory(kBtmLogTag, p_lcb->remote_bd_addr, "LE Data length change",
                     base::StringPrintf("tx_octets:%hu => %hu",
                                        p_lcb->tx_data_len, tx_data_len));
      p_lcb->tx_data_len = tx_data_len;
    } else {
      LOG_DEBUG(
          "Received duplicated data length change event for device:%s "
          "tx_data_len:%hu",
          ADDRESS_TO_LOGGABLE_CSTR(p_lcb->remote_bd_addr), tx_data_len);
    }
  } else {
    LOG_WARN(
        "Received illegal data length change event for device:%s "
        "tx_data_len:%hu",
        ADDRESS_TO_LOGGABLE_CSTR(p_lcb->remote_bd_addr), tx_data_len);
  }
  /* ignore rx_data len for now */
}

/*******************************************************************************
 *
 * Function         l2cble_credit_based_conn_req
 *
 * Description      This function sends LE Credit Based Connection Request for
 *                  LE connection oriented channels.
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_credit_based_conn_req(tL2C_CCB* p_ccb) {
  if (!p_ccb) return;

  if (p_ccb->p_lcb && p_ccb->p_lcb->transport != BT_TRANSPORT_LE) {
    LOG_WARN("LE link doesn't exist");
    return;
  }

  if (p_ccb->ecoc) {
    l2cu_send_peer_credit_based_conn_req(p_ccb);
  } else {
    l2cu_send_peer_ble_credit_based_conn_req(p_ccb);
  }
  return;
}

/*******************************************************************************
 *
 * Function         l2cble_credit_based_conn_res
 *
 * Description      This function sends LE Credit Based Connection Response for
 *                  LE connection oriented channels.
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_credit_based_conn_res(tL2C_CCB* p_ccb, uint16_t result) {
  if (!p_ccb) return;

  if (p_ccb->p_lcb && p_ccb->p_lcb->transport != BT_TRANSPORT_LE) {
    LOG_WARN("LE link doesn't exist");
    return;
  }

  l2cu_send_peer_ble_credit_based_conn_res(p_ccb, result);
  return;
}

/*******************************************************************************
 *
 * Function         l2cble_send_flow_control_credit
 *
 * Description      This function sends flow control credits for
 *                  LE connection oriented channels.
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_send_flow_control_credit(tL2C_CCB* p_ccb, uint16_t credit_value) {
  if (!p_ccb) return;

  if (p_ccb->p_lcb && p_ccb->p_lcb->transport != BT_TRANSPORT_LE) {
    LOG_WARN("LE link doesn't exist");
    return;
  }

  l2cu_send_peer_ble_flow_control_credit(p_ccb, credit_value);
  return;
}

/*******************************************************************************
 *
 * Function         l2cble_send_peer_disc_req
 *
 * Description      This function sends disconnect request
 *                  to the peer LE device
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_send_peer_disc_req(tL2C_CCB* p_ccb) {
  LOG_VERBOSE("%s", __func__);
  if (!p_ccb) return;

  if (p_ccb->p_lcb && p_ccb->p_lcb->transport != BT_TRANSPORT_LE) {
    LOG_WARN("LE link doesn't exist");
    return;
  }

  l2cu_send_peer_ble_credit_based_disconn_req(p_ccb);
  return;
}

/*******************************************************************************
 *
 * Function         l2cble_sec_comp
 *
 * Description      This function is called when security procedure for an LE
 *                  COC link is done
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_sec_comp(const RawAddress* bda, tBT_TRANSPORT transport,
                     void* p_ref_data, tBTM_STATUS status) {
  const RawAddress& p_bda = *bda;
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_bd_addr(p_bda, BT_TRANSPORT_LE);
  tL2CAP_SEC_DATA* p_buf = NULL;
  uint8_t sec_act;

  if (!p_lcb) {
    LOG_WARN("%s: security complete for unknown device. bda=%s", __func__,
             ADDRESS_TO_LOGGABLE_CSTR(*bda));
    return;
  }

  sec_act = p_lcb->sec_act;
  p_lcb->sec_act = 0;

  if (!fixed_queue_is_empty(p_lcb->le_sec_pending_q)) {
    p_buf = (tL2CAP_SEC_DATA*)fixed_queue_dequeue(p_lcb->le_sec_pending_q);
    if (!p_buf) {
      LOG_WARN("%s Security complete for request not initiated from L2CAP",
               __func__);
      return;
    }

    if (status != BTM_SUCCESS) {
      (*(p_buf->p_callback))(p_bda, BT_TRANSPORT_LE, p_buf->p_ref_data, status);
      osi_free(p_buf);
    } else {
      if (sec_act == BTM_SEC_ENCRYPT_MITM) {
        if (BTM_IsLinkKeyAuthed(p_bda, transport))
          (*(p_buf->p_callback))(p_bda, BT_TRANSPORT_LE, p_buf->p_ref_data,
                                 status);
        else {
          LOG_VERBOSE("%s MITM Protection Not present", __func__);
          (*(p_buf->p_callback))(p_bda, BT_TRANSPORT_LE, p_buf->p_ref_data,
                                 BTM_FAILED_ON_SECURITY);
        }
      } else {
        LOG_VERBOSE("%s MITM Protection not required sec_act = %d", __func__,
                    p_lcb->sec_act);

        (*(p_buf->p_callback))(p_bda, BT_TRANSPORT_LE, p_buf->p_ref_data,
                               status);
      }
      osi_free(p_buf);
    }
  } else {
    LOG_WARN("%s Security complete for request not initiated from L2CAP",
             __func__);
    return;
  }

  while (!fixed_queue_is_empty(p_lcb->le_sec_pending_q)) {
    p_buf = (tL2CAP_SEC_DATA*)fixed_queue_dequeue(p_lcb->le_sec_pending_q);

    if (status != BTM_SUCCESS) {
      (*(p_buf->p_callback))(p_bda, BT_TRANSPORT_LE, p_buf->p_ref_data, status);
      osi_free(p_buf);
    }
    else {
      l2ble_sec_access_req(p_bda, p_buf->psm, p_buf->is_originator,
          p_buf->p_callback, p_buf->p_ref_data);

      osi_free(p_buf);
      break;
    }
  }
}

/*******************************************************************************
 *
 * Function         l2ble_sec_access_req
 *
 * Description      This function is called by LE COC link to meet the
 *                  security requirement for the link
 *
 * Returns          Returns  - L2CAP LE Connection Response Result Code.
 *
 ******************************************************************************/
tL2CAP_LE_RESULT_CODE l2ble_sec_access_req(const RawAddress& bd_addr,
                                           uint16_t psm, bool is_originator,
                                           tL2CAP_SEC_CBACK* p_callback,
                                           void* p_ref_data) {
  tL2CAP_LE_RESULT_CODE result;
  tL2C_LCB* p_lcb = NULL;

  if (!p_callback) {
    LOG_ERROR("No callback function");
    return L2CAP_LE_RESULT_NO_RESOURCES;
  }

  p_lcb = l2cu_find_lcb_by_bd_addr(bd_addr, BT_TRANSPORT_LE);

  if (!p_lcb) {
    LOG_ERROR("Security check for unknown device");
    p_callback(bd_addr, BT_TRANSPORT_LE, p_ref_data, BTM_UNKNOWN_ADDR);
    return L2CAP_LE_RESULT_NO_RESOURCES;
  }

  tL2CAP_SEC_DATA* p_buf =
      (tL2CAP_SEC_DATA*)osi_malloc((uint16_t)sizeof(tL2CAP_SEC_DATA));
  if (!p_buf) {
    LOG_ERROR("No resources for connection");
    p_callback(bd_addr, BT_TRANSPORT_LE, p_ref_data, BTM_NO_RESOURCES);
    return L2CAP_LE_RESULT_NO_RESOURCES;
  }

  p_buf->psm = psm;
  p_buf->is_originator = is_originator;
  p_buf->p_callback = p_callback;
  p_buf->p_ref_data = p_ref_data;
  fixed_queue_enqueue(p_lcb->le_sec_pending_q, p_buf);
  result = btm_ble_start_sec_check(bd_addr, psm, is_originator,
                                   &l2cble_sec_comp, p_ref_data);

  return result;
}

/* This function is called to adjust the connection intervals based on various
 * constraints. For example, when there is at least one Hearing Aid device
 * bonded, the minimum interval is raised. On return, min_interval and
 * max_interval are updated. */
void L2CA_AdjustConnectionIntervals(uint16_t* min_interval,
                                    uint16_t* max_interval,
                                    uint16_t floor_interval) {
  // Allow for customization by systemprops for mainline
  uint16_t phone_min_interval = floor_interval;
#ifdef __ANDROID__
  phone_min_interval =
      android::sysprop::BluetoothProperties::getGapLeConnMinLimit().value_or(
          floor_interval);
#else
  phone_min_interval = (uint16_t)osi_property_get_int32(
      "bluetooth.core.gap.le.conn.min.limit", (int32_t)floor_interval);
#endif

  if (GetInterfaceToProfiles()
          ->profileSpecific_HACK->GetHearingAidDeviceCount()) {
    // When there are bonded Hearing Aid devices, we will constrained this
    // minimum interval.
    phone_min_interval = BTM_BLE_CONN_INT_MIN_HEARINGAID;
    LOG_VERBOSE("%s: Have Hearing Aids. Min. interval is set to %d", __func__,
                phone_min_interval);
  }

  if (*min_interval < phone_min_interval) {
    LOG_VERBOSE("%s: requested min_interval=%d too small. Set to %d", __func__,
                *min_interval, phone_min_interval);
    *min_interval = phone_min_interval;
  }

  // While this could result in connection parameters that fall
  // outside fo the range requested, this will allow the connection
  // to remain established.
  // In other words, this is a workaround for certain peripherals.
  if (*max_interval < phone_min_interval) {
    LOG_VERBOSE("%s: requested max_interval=%d too small. Set to %d", __func__,
                *max_interval, phone_min_interval);
    *max_interval = phone_min_interval;
  }
}

void l2cble_use_preferred_conn_params(const RawAddress& bda) {
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_bd_addr(bda, BT_TRANSPORT_LE);
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_or_alloc_dev(bda);

  /* If there are any preferred connection parameters, set them now */
  if ((p_lcb != NULL) && (p_dev_rec != NULL) &&
      (p_dev_rec->conn_params.min_conn_int >= BTM_BLE_CONN_INT_MIN) &&
      (p_dev_rec->conn_params.min_conn_int <= BTM_BLE_CONN_INT_MAX) &&
      (p_dev_rec->conn_params.max_conn_int >= BTM_BLE_CONN_INT_MIN) &&
      (p_dev_rec->conn_params.max_conn_int <= BTM_BLE_CONN_INT_MAX) &&
      (p_dev_rec->conn_params.peripheral_latency <= BTM_BLE_CONN_LATENCY_MAX) &&
      (p_dev_rec->conn_params.supervision_tout >= BTM_BLE_CONN_SUP_TOUT_MIN) &&
      (p_dev_rec->conn_params.supervision_tout <= BTM_BLE_CONN_SUP_TOUT_MAX) &&
      ((p_lcb->min_interval < p_dev_rec->conn_params.min_conn_int &&
        p_dev_rec->conn_params.min_conn_int != BTM_BLE_CONN_PARAM_UNDEF) ||
       (p_lcb->min_interval > p_dev_rec->conn_params.max_conn_int) ||
       (p_lcb->latency > p_dev_rec->conn_params.peripheral_latency) ||
       (p_lcb->timeout > p_dev_rec->conn_params.supervision_tout))) {
    LOG_VERBOSE(
        "%s: HANDLE=%d min_conn_int=%d max_conn_int=%d peripheral_latency=%d "
        "supervision_tout=%d",
        __func__, p_lcb->Handle(), p_dev_rec->conn_params.min_conn_int,
        p_dev_rec->conn_params.max_conn_int,
        p_dev_rec->conn_params.peripheral_latency,
        p_dev_rec->conn_params.supervision_tout);

    p_lcb->min_interval = p_dev_rec->conn_params.min_conn_int;
    p_lcb->max_interval = p_dev_rec->conn_params.max_conn_int;
    p_lcb->timeout = p_dev_rec->conn_params.supervision_tout;
    p_lcb->latency = p_dev_rec->conn_params.peripheral_latency;

    btsnd_hcic_ble_upd_ll_conn_params(
        p_lcb->Handle(), p_dev_rec->conn_params.min_conn_int,
        p_dev_rec->conn_params.max_conn_int,
        p_dev_rec->conn_params.peripheral_latency,
        p_dev_rec->conn_params.supervision_tout, 0, 0);
  }
}

/*******************************************************************************
 *
 *  Function        l2cble_start_subrate_change
 *
 *  Description     Start the BLE subrate change process based on
 *                  status.
 *
 *  Parameters:     lcb : l2cap link control block
 *
 *  Return value:   none
 *
 ******************************************************************************/
static void l2cble_start_subrate_change(tL2C_LCB* p_lcb) {
  if (!BTM_IsAclConnectionUp(p_lcb->remote_bd_addr, BT_TRANSPORT_LE)) {
    LOG(ERROR) << "No known connection ACL for "
               << ADDRESS_TO_LOGGABLE_STR(p_lcb->remote_bd_addr);
    return;
  }

  btm_find_or_alloc_dev(p_lcb->remote_bd_addr);

  LOG_VERBOSE("%s: subrate_req_mask=%d conn_update_mask=%d", __func__,
              p_lcb->subrate_req_mask, p_lcb->conn_update_mask);

  if (p_lcb->subrate_req_mask & L2C_BLE_SUBRATE_REQ_PENDING) {
    LOG_VERBOSE("%s: returning L2C_BLE_SUBRATE_REQ_PENDING ", __func__);
    return;
  }

  if (p_lcb->subrate_req_mask & L2C_BLE_SUBRATE_REQ_DISABLE) {
    LOG_VERBOSE("%s: returning L2C_BLE_SUBRATE_REQ_DISABLE ", __func__);
    return;
  }

  /* application allows to do update, if we were delaying one do it now */
  if (!(p_lcb->subrate_req_mask & L2C_BLE_NEW_SUBRATE_PARAM) ||
      (p_lcb->conn_update_mask & L2C_BLE_UPDATE_PENDING) ||
      (p_lcb->conn_update_mask & L2C_BLE_NEW_CONN_PARAM)) {
    LOG_VERBOSE("%s: returning L2C_BLE_NEW_SUBRATE_PARAM", __func__);
    return;
  }

  if (!controller_get_interface()->supports_ble_connection_subrating() ||
      !acl_peer_supports_ble_connection_subrating(p_lcb->remote_bd_addr) ||
      !acl_peer_supports_ble_connection_subrating_host(p_lcb->remote_bd_addr)) {
    LOG_VERBOSE(
        "%s: returning L2C_BLE_NEW_SUBRATE_PARAM local_host_sup=%d, "
        "local_conn_subrarte_sup=%d, peer_subrate_sup=%d, peer_host_sup=%d",
        __func__,
        controller_get_interface()->supports_ble_connection_subrating_host(),
        controller_get_interface()->supports_ble_connection_subrating(),
        acl_peer_supports_ble_connection_subrating(p_lcb->remote_bd_addr),
        acl_peer_supports_ble_connection_subrating_host(p_lcb->remote_bd_addr));
    return;
  }

  LOG_VERBOSE("%s: Sending HCI cmd for subrate req", __func__);
  bluetooth::shim::ACL_LeSubrateRequest(
      p_lcb->Handle(), p_lcb->subrate_min, p_lcb->subrate_max,
      p_lcb->max_latency, p_lcb->cont_num, p_lcb->supervision_tout);

  p_lcb->subrate_req_mask |= L2C_BLE_SUBRATE_REQ_PENDING;
  p_lcb->subrate_req_mask &= ~L2C_BLE_NEW_SUBRATE_PARAM;
  p_lcb->conn_update_mask |= L2C_BLE_NOT_DEFAULT_PARAM;
}

/*******************************************************************************
 *
 *  Function        L2CA_SetDefaultSubrate
 *
 *  Description     BLE Set Default Subrate
 *
 *  Parameters:     Subrate parameters
 *
 *  Return value:   void
 *
 ******************************************************************************/
void L2CA_SetDefaultSubrate(uint16_t subrate_min, uint16_t subrate_max,
                            uint16_t max_latency, uint16_t cont_num,
                            uint16_t timeout) {
  VLOG(1) << __func__ << " subrate_min=" << subrate_min
          << ", subrate_max=" << subrate_max << ", max_latency=" << max_latency
          << ", cont_num=" << cont_num << ", timeout=" << timeout;

  bluetooth::shim::ACL_LeSetDefaultSubrate(subrate_min, subrate_max,
                                           max_latency, cont_num, timeout);
}

/*******************************************************************************
 *
 *  Function        L2CA_SubrateRequest
 *
 *  Description     BLE Subrate request.
 *
 *  Parameters:     Subrate parameters
 *
 *  Return value:   true if update started
 *
 ******************************************************************************/
bool L2CA_SubrateRequest(const RawAddress& rem_bda, uint16_t subrate_min,
                         uint16_t subrate_max, uint16_t max_latency,
                         uint16_t cont_num, uint16_t timeout) {
  tL2C_LCB* p_lcb;

  /* See if we have a link control block for the remote device */
  p_lcb = l2cu_find_lcb_by_bd_addr(rem_bda, BT_TRANSPORT_LE);

  /* If we don't have one, create one and accept the connection. */
  if (!p_lcb || !BTM_IsAclConnectionUp(rem_bda, BT_TRANSPORT_LE)) {
    LOG(WARNING) << __func__ << " - unknown BD_ADDR "
                 << ADDRESS_TO_LOGGABLE_STR(rem_bda);
    return (false);
  }

  if (p_lcb->transport != BT_TRANSPORT_LE) {
    LOG(WARNING) << __func__ << " - BD_ADDR "
                 << ADDRESS_TO_LOGGABLE_STR(rem_bda) << " not LE";
    return (false);
  }

  VLOG(1) << __func__ << ": BD_ADDR=" << ADDRESS_TO_LOGGABLE_STR(rem_bda)
          << ", subrate_min=" << subrate_min << ", subrate_max=" << subrate_max
          << ", max_latency=" << max_latency << ", cont_num=" << cont_num
          << ", timeout=" << timeout;

  p_lcb->subrate_min = subrate_min;
  p_lcb->subrate_max = subrate_max;
  p_lcb->max_latency = max_latency;
  p_lcb->cont_num = cont_num;
  p_lcb->subrate_req_mask |= L2C_BLE_NEW_SUBRATE_PARAM;
  p_lcb->supervision_tout = timeout;

  l2cble_start_subrate_change(p_lcb);

  return (true);
}

/*******************************************************************************
 *
 * Function         l2cble_process_subrate_change_evt
 *
 * Description      This function enables LE subrating
 *                  after a successful subrate change process is
 *                  done.
 *
 * Parameters:      LE connection handle
 *                  status
 *                  subrate factor
 *                  peripheral latency
 *                  continuation number
 *                  supervision timeout
 *
 * Returns          void
 *
 ******************************************************************************/
void l2cble_process_subrate_change_evt(uint16_t handle, uint8_t status,
                                       uint16_t subrate_factor,
                                       uint16_t peripheral_latency,
                                       uint16_t cont_num, uint16_t timeout) {
  LOG_VERBOSE("%s", __func__);

  /* See if we have a link control block for the remote device */
  tL2C_LCB* p_lcb = l2cu_find_lcb_by_handle(handle);
  if (!p_lcb) {
    LOG_WARN("%s: Invalid handle: %d", __func__, handle);
    return;
  }

  p_lcb->subrate_req_mask &= ~L2C_BLE_SUBRATE_REQ_PENDING;

  if (status != HCI_SUCCESS) {
    LOG_WARN("%s: Error status: %d", __func__, status);
  }

  l2cble_start_conn_update(p_lcb);

  l2cble_start_subrate_change(p_lcb);

  LOG_VERBOSE("%s: conn_update_mask=%d , subrate_req_mask=%d", __func__,
              p_lcb->conn_update_mask, p_lcb->subrate_req_mask);
}
