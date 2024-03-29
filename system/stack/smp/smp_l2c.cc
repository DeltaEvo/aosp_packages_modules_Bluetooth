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
 *  This file contains functions for the SMP L2Cap interface
 *
 ******************************************************************************/

#define LOG_TAG "bluetooth"

#include <base/logging.h>
#include <string.h>

#include "bt_target.h"
#include "btm_ble_api.h"
#include "common/metrics.h"
#include "l2c_api.h"
#include "main/shim/dumpsys.h"
#include "osi/include/allocator.h"
#include "osi/include/log.h"
#include "osi/include/osi.h"  // UNUSED_ATTR
#include "smp_int.h"
#include "stack/btm/btm_dev.h"
#include "stack/include/bt_hdr.h"
#include "types/raw_address.h"

static void smp_connect_callback(uint16_t channel, const RawAddress& bd_addr,
                                 bool connected, uint16_t reason,
                                 tBT_TRANSPORT transport);
static void smp_data_received(uint16_t channel, const RawAddress& bd_addr,
                              BT_HDR* p_buf);

static void smp_br_connect_callback(uint16_t channel, const RawAddress& bd_addr,
                                    bool connected, uint16_t reason,
                                    tBT_TRANSPORT transport);
static void smp_br_data_received(uint16_t channel, const RawAddress& bd_addr,
                                 BT_HDR* p_buf);

/*******************************************************************************
 *
 * Function         smp_l2cap_if_init
 *
 * Description      This function is called during the SMP task startup
 *                  to register interface functions with L2CAP.
 *
 ******************************************************************************/
void smp_l2cap_if_init(void) {
  tL2CAP_FIXED_CHNL_REG fixed_reg;
  LOG_VERBOSE("SMDBG l2c %s", __func__);

  fixed_reg.pL2CA_FixedConn_Cb = smp_connect_callback;
  fixed_reg.pL2CA_FixedData_Cb = smp_data_received;

  fixed_reg.pL2CA_FixedCong_Cb =
      NULL; /* do not handle congestion on this channel */
  fixed_reg.default_idle_tout =
      60; /* set 60 seconds timeout, 0xffff default idle timeout */

  L2CA_RegisterFixedChannel(L2CAP_SMP_CID, &fixed_reg);

  fixed_reg.pL2CA_FixedConn_Cb = smp_br_connect_callback;
  fixed_reg.pL2CA_FixedData_Cb = smp_br_data_received;

  L2CA_RegisterFixedChannel(L2CAP_SMP_BR_CID, &fixed_reg);
}

/*******************************************************************************
 *
 * Function         smp_connect_callback
 *
 * Description      This callback function is called by L2CAP to indicate that
 *                  SMP channel is
 *                      connected (conn = true)/disconnected (conn = false).
 *
 ******************************************************************************/
static void smp_connect_callback(UNUSED_ATTR uint16_t channel,
                                 const RawAddress& bd_addr, bool connected,
                                 UNUSED_ATTR uint16_t reason,
                                 tBT_TRANSPORT transport) {
  tSMP_CB* p_cb = &smp_cb;
  tSMP_INT_DATA int_data;

  if (bd_addr.IsEmpty()) {
    LOG_WARN("Received unexpected callback for empty address");
    return;
  }

  if (transport == BT_TRANSPORT_BR_EDR) {
    LOG_WARN("Received unexpected callback on classic channel peer:%s",
             ADDRESS_TO_LOGGABLE_CSTR(bd_addr));
    return;
  }

  if (connected) {
    LOG_DEBUG("SMP Received connect callback bd_addr:%s transport:%s",
              ADDRESS_TO_LOGGABLE_CSTR(bd_addr), bt_transport_text(transport).c_str());
  } else {
    LOG_DEBUG("SMP Received disconnect callback bd_addr:%s transport:%s",
              ADDRESS_TO_LOGGABLE_CSTR(bd_addr), bt_transport_text(transport).c_str());
  }

  if (bd_addr == p_cb->pairing_bda) {
    LOG_DEBUG("Received callback for device in pairing process:%s state:%s",
              ADDRESS_TO_LOGGABLE_CSTR(bd_addr),
              (connected) ? "connected" : "disconnected");

    if (connected) {
      if (!p_cb->connect_initialized) {
        p_cb->connect_initialized = true;
        /* initiating connection established */
        p_cb->role = L2CA_GetBleConnRole(bd_addr);

        /* initialize local i/r key to be default keys */
        p_cb->local_r_key = p_cb->local_i_key = SMP_SEC_DEFAULT_KEY;
        p_cb->loc_auth_req = p_cb->peer_auth_req = SMP_DEFAULT_AUTH_REQ;
        p_cb->cb_evt = SMP_IO_CAP_REQ_EVT;
        smp_sm_event(p_cb, SMP_L2CAP_CONN_EVT, NULL);
      }
    } else {
      /* Disconnected while doing security */
      smp_sm_event(p_cb, SMP_L2CAP_DISCONN_EVT, &int_data);
    }
  }
}

/*******************************************************************************
 *
 * Function         smp_data_received
 *
 * Description      This function is called when data is received from L2CAP on
 *                  SMP channel.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void smp_data_received(uint16_t channel, const RawAddress& bd_addr,
                              BT_HDR* p_buf) {
  tSMP_CB* p_cb = &smp_cb;
  uint8_t* p = (uint8_t*)(p_buf + 1) + p_buf->offset;
  uint8_t cmd;

  if (p_buf->len < 1) {
    LOG_WARN("%s: smp packet length %d too short: must be at least 1", __func__,
             p_buf->len);
    osi_free(p_buf);
    return;
  }

  STREAM_TO_UINT8(cmd, p);

  LOG_VERBOSE("%s: SMDBG l2c, cmd=0x%x", __func__, cmd);

  /* sanity check */
  if ((SMP_OPCODE_MAX < cmd) || (SMP_OPCODE_MIN > cmd)) {
    LOG_WARN("Ignore received command with RESERVED code 0x%02x", cmd);
    osi_free(p_buf);
    return;
  }

  /* reject the pairing request if there is an on-going SMP pairing */
  if (SMP_OPCODE_PAIRING_REQ == cmd || SMP_OPCODE_SEC_REQ == cmd) {
    if ((p_cb->state == SMP_STATE_IDLE) &&
        (p_cb->br_state == SMP_BR_STATE_IDLE) &&
        !(p_cb->flags & SMP_PAIR_FLAGS_WE_STARTED_DD)) {
      p_cb->role = L2CA_GetBleConnRole(bd_addr);
      p_cb->pairing_bda = bd_addr;
    } else if (bd_addr != p_cb->pairing_bda) {
      osi_free(p_buf);
      smp_reject_unexpected_pairing_command(bd_addr);
      return;
    }
    /* else, out of state pairing request/security request received, passed into
     * SM */
  }

  if (bd_addr == p_cb->pairing_bda) {
    alarm_set_on_mloop(p_cb->smp_rsp_timer_ent, SMP_WAIT_FOR_RSP_TIMEOUT_MS,
                       smp_rsp_timeout, NULL);

    smp_log_metrics(p_cb->pairing_bda, false /* incoming */,
                    p_buf->data + p_buf->offset, p_buf->len,
                    false /* is_over_br */);

    if (cmd == SMP_OPCODE_CONFIRM) {
      LOG_VERBOSE(
          "in %s cmd = 0x%02x, peer_auth_req = 0x%02x,"
          "loc_auth_req = 0x%02x",
          __func__, cmd, p_cb->peer_auth_req, p_cb->loc_auth_req);

      if ((p_cb->peer_auth_req & SMP_SC_SUPPORT_BIT) &&
          (p_cb->loc_auth_req & SMP_SC_SUPPORT_BIT)) {
        cmd = SMP_OPCODE_PAIR_COMMITM;
      }
    }

    p_cb->rcvd_cmd_code = cmd;
    p_cb->rcvd_cmd_len = (uint8_t)p_buf->len;
    tSMP_INT_DATA smp_int_data;
    smp_int_data.p_data = p;
    smp_sm_event(p_cb, static_cast<tSMP_EVENT>(cmd), &smp_int_data);
  } else {
    L2CA_RemoveFixedChnl(channel, bd_addr);
  }

  osi_free(p_buf);
}

/*******************************************************************************
 *
 * Function         smp_br_connect_callback
 *
 * Description      This callback function is called by L2CAP to indicate that
 *                  SMP BR channel is
 *                      connected (conn = true)/disconnected (conn = false).
 *
 ******************************************************************************/
static void smp_br_connect_callback(uint16_t channel, const RawAddress& bd_addr,
                                    bool connected, uint16_t reason,
                                    tBT_TRANSPORT transport) {
  tSMP_CB* p_cb = &smp_cb;
  tSMP_INT_DATA int_data;

  LOG_VERBOSE("%s", __func__);

  if (transport != BT_TRANSPORT_BR_EDR) {
    LOG_WARN("%s is called on unexpected transport %d", __func__, transport);
    return;
  }

  VLOG(1) << __func__ << " for pairing BDA: "
          << ADDRESS_TO_LOGGABLE_STR(bd_addr)
          << ", pairing_bda:" << ADDRESS_TO_LOGGABLE_STR(p_cb->pairing_bda)
          << " Event: " << ((connected) ? "connected" : "disconnected");

  if (bd_addr != p_cb->pairing_bda) return;

  /* Check if we already finished SMP pairing over LE, and are waiting to
   * check if other side returns some errors. Connection/disconnection on
   * Classic transport shouldn't impact that.
   */
  tBTM_SEC_DEV_REC* p_dev_rec = btm_find_dev(p_cb->pairing_bda);
  if ((smp_get_state() == SMP_STATE_BOND_PENDING ||
       smp_get_state() == SMP_STATE_IDLE) &&
      (p_dev_rec && p_dev_rec->is_link_key_known()) &&
      alarm_is_scheduled(p_cb->delayed_auth_timer_ent)) {
    /* If we were to not return here, we would reset SMP control block, and
     * delayed_auth_timer_ent would never be executed. Even though we stored all
     * keys, stack would consider device as not bonded. It would reappear after
     * stack restart, when we re-read record from storage. Service discovery
     * would stay broken.
     */
    LOG_INFO("Classic event after CTKD on LE transport");
    return;
  }

  if (connected) {
    if (!p_cb->connect_initialized) {
      p_cb->connect_initialized = true;
      /* initialize local i/r key to be default keys */
      p_cb->local_r_key = p_cb->local_i_key = SMP_BR_SEC_DEFAULT_KEY;
      p_cb->loc_auth_req = p_cb->peer_auth_req = 0;
      p_cb->cb_evt = SMP_BR_KEYS_REQ_EVT;
      smp_br_state_machine_event(p_cb, SMP_BR_L2CAP_CONN_EVT, NULL);
    }
  } else {
    /* Disconnected while doing security */
    if (p_cb->smp_over_br) {
      LOG_DEBUG("SMP over BR/EDR not supported, terminate the ongoing pairing");
      smp_br_state_machine_event(p_cb, SMP_BR_L2CAP_DISCONN_EVT, &int_data);
    } else {
      LOG_DEBUG("SMP over BR/EDR not supported, continue the LE pairing");
    }
  }
}

/*******************************************************************************
 *
 * Function         smp_br_data_received
 *
 * Description      This function is called when data is received from L2CAP on
 *                  SMP BR channel.
 *
 * Returns          void
 *
 ******************************************************************************/
static void smp_br_data_received(uint16_t channel, const RawAddress& bd_addr,
                                 BT_HDR* p_buf) {
  tSMP_CB* p_cb = &smp_cb;
  uint8_t* p = (uint8_t*)(p_buf + 1) + p_buf->offset;
  uint8_t cmd;
  LOG_VERBOSE("SMDBG l2c %s", __func__);

  if (p_buf->len < 1) {
    LOG_WARN("%s: smp packet length %d too short: must be at least 1", __func__,
             p_buf->len);
    osi_free(p_buf);
    return;
  }

  STREAM_TO_UINT8(cmd, p);

  /* sanity check */
  if ((SMP_OPCODE_MAX < cmd) || (SMP_OPCODE_MIN > cmd)) {
    LOG_WARN("Ignore received command with RESERVED code 0x%02x", cmd);
    osi_free(p_buf);
    return;
  }

  /* reject the pairing request if there is an on-going SMP pairing */
  if (SMP_OPCODE_PAIRING_REQ == cmd) {
    if ((p_cb->state == SMP_STATE_IDLE) &&
        (p_cb->br_state == SMP_BR_STATE_IDLE)) {
      p_cb->role = HCI_ROLE_PERIPHERAL;
      p_cb->smp_over_br = true;
      p_cb->pairing_bda = bd_addr;
    } else if (bd_addr != p_cb->pairing_bda) {
      osi_free(p_buf);
      smp_reject_unexpected_pairing_command(bd_addr);
      return;
    }
    /* else, out of state pairing request received, passed into State Machine */
  }

  if (bd_addr == p_cb->pairing_bda) {
    alarm_set_on_mloop(p_cb->smp_rsp_timer_ent, SMP_WAIT_FOR_RSP_TIMEOUT_MS,
                       smp_rsp_timeout, NULL);

    smp_log_metrics(p_cb->pairing_bda, false /* incoming */,
                    p_buf->data + p_buf->offset, p_buf->len,
                    true /* is_over_br */);

    p_cb->rcvd_cmd_code = cmd;
    p_cb->rcvd_cmd_len = (uint8_t)p_buf->len;
    tSMP_INT_DATA smp_int_data;
    smp_int_data.p_data = p;
    smp_br_state_machine_event(p_cb, static_cast<tSMP_EVENT>(cmd),
                               &smp_int_data);
  }

  osi_free(p_buf);
}
