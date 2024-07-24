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
#ifndef BTM_INT_TYPES_H
#define BTM_INT_TYPES_H

#include <bluetooth/log.h>

#include <cstdint>
#include <memory>
#include <string>

#include "common/circular_buffer.h"
#include "osi/include/fixed_queue.h"
#include "stack/acl/acl.h"
#include "stack/btm/btm_ble_int_types.h"
#include "stack/btm/btm_sco.h"
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/btm_ble_api_types.h"
#include "stack/include/security_client_callbacks.h"
#include "stack/rnr/remote_name_request.h"
#include "types/raw_address.h"

constexpr size_t kMaxLogSize = 255;
constexpr size_t kBtmLogHistoryBufferSize = 200;
constexpr size_t kMaxInquiryScanHistory = 10;

extern bluetooth::common::TimestamperInMilliseconds timestamper_in_milliseconds;

class TimestampedStringCircularBuffer
    : public bluetooth::common::TimestampedCircularBuffer<std::string> {
public:
  explicit TimestampedStringCircularBuffer(size_t size)
      : bluetooth::common::TimestampedCircularBuffer<std::string>(size) {}

  void Push(const std::string& s) {
    bluetooth::common::TimestampedCircularBuffer<std::string>::Push(s.substr(0, kMaxLogSize));
  }

  template <typename... Args>
  void Push(Args... args) {
    char buf[kMaxLogSize];
    std::snprintf(buf, sizeof(buf), args...);
    bluetooth::common::TimestampedCircularBuffer<std::string>::Push(std::string(buf));
  }
};

/* Define a structure to hold all the BTM data
 */

/* Define the Device Management control structure
 */
typedef struct tBTM_DEVCB {
  alarm_t* read_local_name_timer; /* Read local name timer */
  tBTM_CMPL_CB* p_rln_cmpl_cb;    /* Callback function to be called when  */
                                  /* read local name function complete    */

  alarm_t* read_rssi_timer;     /* Read RSSI timer */
  tBTM_CMPL_CB* p_rssi_cmpl_cb; /* Callback function to be called when  */
                                /* read RSSI function completes */

  alarm_t* read_failed_contact_counter_timer;     /* Read Failed Contact Counter */
                                                  /* timer */
  tBTM_CMPL_CB* p_failed_contact_counter_cmpl_cb; /* Callback function to be */
  /* called when read Failed Contact Counter function completes */

  alarm_t* read_automatic_flush_timeout_timer;     /* Read Automatic Flush Timeout */
                                                   /* timer */
  tBTM_CMPL_CB* p_automatic_flush_timeout_cmpl_cb; /* Callback function to be */
  /* called when read Automatic Flush Timeout function completes */

  alarm_t* read_tx_power_timer;     /* Read tx power timer */
  tBTM_CMPL_CB* p_tx_power_cmpl_cb; /* Callback function to be called       */

  DEV_CLASS dev_class; /* Local device class                   */

  tBTM_CMPL_CB* p_le_test_cmd_cmpl_cb; /* Callback function to be called when
                                       LE test mode command has been sent successfully */

  RawAddress read_tx_pwr_addr; /* read TX power target address     */

  void Init() {
    read_local_name_timer = alarm_new("btm.read_local_name_timer");
    read_rssi_timer = alarm_new("btm.read_rssi_timer");
    read_failed_contact_counter_timer = alarm_new("btm.read_failed_contact_counter_timer");
    read_automatic_flush_timeout_timer = alarm_new("btm.read_automatic_flush_timeout_timer");
    read_tx_power_timer = alarm_new("btm.read_tx_power_timer");
  }

  void Free() {
    alarm_free(read_local_name_timer);
    alarm_free(read_rssi_timer);
    alarm_free(read_failed_contact_counter_timer);
    alarm_free(read_automatic_flush_timeout_timer);
    alarm_free(read_tx_power_timer);
  }
} tBTM_DEVCB;

typedef struct tBTM_CB {
  /*****************************************************
  **      Control block for local device
  *****************************************************/
  tBTM_DEVCB devcb;

  /*****************************************************
  **      Control block for local LE device
  *****************************************************/
  tBTM_BLE_CB ble_ctr_cb;

public:
  tBTM_BLE_VSC_CB cmn_ble_vsc_cb;

  /* Packet types supported by the local device */
  uint16_t btm_sco_pkt_types_supported{0};

  /*****************************************************
  **      Inquiry
  *****************************************************/
  tBTM_INQUIRY_VAR_ST btm_inq_vars;

  /*****************************************************
  **      SCO Management
  *****************************************************/
  tSCO_CB sco_cb;

  uint16_t disc_handle{0}; /* for legacy devices */
  uint8_t disc_reason{0};  /* for legacy devices */

  fixed_queue_t* sec_pending_q{nullptr}; /* pending sequrity requests in
                                            tBTM_SEC_QUEUE_ENTRY format */

#define BTM_CODEC_TYPE_MAX_RECORDS 32
  tBTM_BT_DYNAMIC_AUDIO_BUFFER_CB dynamic_audio_buffer_cb[BTM_CODEC_TYPE_MAX_RECORDS];

  tACL_CB acl_cb_;

  std::shared_ptr<TimestampedStringCircularBuffer> history_{nullptr};

  struct {
    struct {
      long long start_time_ms;
      unsigned long results;
    } classic_inquiry, le_scan, le_inquiry, le_observe, le_legacy_scan;
    std::unique_ptr<bluetooth::common::TimestampedCircularBuffer<tBTM_INQUIRY_CMPL>>
            inquiry_history_ = std::make_unique<
                    bluetooth::common::TimestampedCircularBuffer<tBTM_INQUIRY_CMPL>>(
                    kMaxInquiryScanHistory);
  } neighbor;

  bluetooth::rnr::RemoteNameRequest rnr;

  void Init() {
    memset(&devcb, 0, sizeof(devcb));
    memset(&ble_ctr_cb, 0, sizeof(ble_ctr_cb));
    memset(&cmn_ble_vsc_cb, 0, sizeof(cmn_ble_vsc_cb));
    memset(&btm_inq_vars, 0, sizeof(btm_inq_vars));
    memset(&sco_cb, 0, sizeof(sco_cb));

    acl_cb_ = {};
    neighbor = {};
    rnr = {};
    rnr.remote_name_timer = alarm_new("rnr.remote_name_timer");

    /* Initialize BTM component structures */
    btm_inq_vars.Init(); /* Inquiry Database and Structures */
    sco_cb.Init();       /* SCO Database and Structures (If included) */
    devcb.Init();

    history_ = std::make_shared<TimestampedStringCircularBuffer>(kBtmLogHistoryBufferSize);
    bluetooth::log::assert_that(history_ != nullptr, "assert failed: history_ != nullptr");
    history_->Push(std::string("Initialized btm history"));
  }

  void Free() {
    alarm_free(rnr.remote_name_timer);
    history_.reset();

    devcb.Free();
    sco_cb.Free();
    btm_inq_vars.Free();
  }
} tBTM_CB;

#endif  // BTM_INT_TYPES_H
