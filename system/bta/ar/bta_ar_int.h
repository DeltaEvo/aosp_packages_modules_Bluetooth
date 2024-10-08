/******************************************************************************
 *
 *  Copyright 2008-2012 Broadcom Corporation
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
 *  This is the private interface file for the BTA audio/video registration
 *  module.
 *
 ******************************************************************************/
#ifndef BTA_AR_INT_H
#define BTA_AR_INT_H

#include <cstdint>

#include "bta/include/bta_av_api.h"
#include "profile/avrcp/avrcp_sdp_records.h"
#include "stack/include/avdt_api.h"

#define BTA_AR_AV_MASK 0x01
#define BTA_AR_AVK_MASK 0x02
using namespace bluetooth::avrcp;

/* data associated with BTA_AR */
typedef struct {
  tAVDT_CTRL_CBACK* p_av_conn_cback; /* av connection callback function */
  uint8_t avdt_registered;
  uint8_t avct_registered;
  uint32_t sdp_tg_handle;
  uint32_t sdp_ct_handle;
  uint16_t ct_categories[2];
  uint8_t tg_registered;
  uint16_t sdp_tg_request_id = UNASSIGNED_REQUEST_ID;
  uint16_t sdp_ct_request_id = UNASSIGNED_REQUEST_ID;
  tBTA_AV_HNDL hndl; /* Handle associated with the stream that rejected the
                        connection. */
  uint16_t ct_ver;
  uint16_t tg_categories[2];
} tBTA_AR_CB;

/*****************************************************************************
 *  Global data
 ****************************************************************************/

/* control block declaration */
extern tBTA_AR_CB bta_ar_cb;

#endif /* BTA_AR_INT_H */
