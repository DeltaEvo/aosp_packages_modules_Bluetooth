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

/******************************************************************************
 *
 *  This interface file contains the interface AVDTP conformance API.  These
 *  additional API functions and callback events are provided for
 *  conformance testing purposes only.  They are not intended to be used by
 *  an application.
 *
 ******************************************************************************/
#ifndef AVDT_CAPI_H
#define AVDT_CAPI_H

#include <cstdint>

#include "avdt_api.h"
#include "types/raw_address.h"

/* start AVDTC events here to distinguish from AVDT events */
#define AVDTC_EVT_BEGIN 0x80

/* Discover indication */
#define AVDTC_DISCOVER_IND_EVT (0 + AVDTC_EVT_BEGIN)
/* Get capabilities indication */
#define AVDTC_GETCAP_IND_EVT (1 + AVDTC_EVT_BEGIN)
/* Set configuration confirm */
#define AVDTC_SETCONFIG_CFM_EVT (2 + AVDTC_EVT_BEGIN)
/* Get configuration indication */
#define AVDTC_GETCONFIG_IND_EVT (3 + AVDTC_EVT_BEGIN)
/* Get configuration confirm */
#define AVDTC_GETCONFIG_CFM_EVT (4 + AVDTC_EVT_BEGIN)
/* Open indication */
#define AVDTC_OPEN_IND_EVT (5 + AVDTC_EVT_BEGIN)
/* Start indication */
#define AVDTC_START_IND_EVT (6 + AVDTC_EVT_BEGIN)
/* Close indication */
#define AVDTC_CLOSE_IND_EVT (7 + AVDTC_EVT_BEGIN)
/* Suspend indication */
#define AVDTC_SUSPEND_IND_EVT (8 + AVDTC_EVT_BEGIN)
/* Abort indication */
#define AVDTC_ABORT_IND_EVT (9 + AVDTC_EVT_BEGIN)
/* Abort confirm */
#define AVDTC_ABORT_CFM_EVT (10 + AVDTC_EVT_BEGIN)

typedef struct {
  tAVDT_EVT_HDR hdr;                /* Event header */
  uint8_t seid_list[AVDT_NUM_SEPS]; /* Array of SEID values */
  uint8_t num_seps;                 /* Number of values in array */
} tAVDT_MULTI;

/* Union of all control callback event data structures */
typedef union {
  tAVDT_EVT_HDR hdr;
  tAVDT_CONFIG getconfig_cfm;
  tAVDT_MULTI start_ind;
  tAVDT_MULTI suspend_ind;
} tAVDTC_CTRL;

typedef void tAVDTC_CTRL_CBACK(uint8_t handle, const RawAddress& bd_addr, uint8_t event,
                               tAVDTC_CTRL* p_data);

/*******************************************************************************
 *
 * Function         AVDTC_Init
 *
 * Description      This function is called to begin using the conformance API.
 *                  It must be called after AVDT_Register() and before any
 *                  other API or conformance API functions are called.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_Init(tAVDTC_CTRL_CBACK* p_cback);

/*******************************************************************************
 *
 * Function         AVDTC_DiscoverRsp
 *
 * Description      Send a discover response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_DiscoverRsp(const RawAddress& bd_addr, uint8_t label, tAVDT_SEP_INFO sep_info[],
                       uint8_t num_seps);

/*******************************************************************************
 *
 * Function         AVDTC_GetCapRsp
 *
 * Description     Send a get capabilities response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_GetCapRsp(const RawAddress& bd_addr, uint8_t label, AvdtpSepConfig* p_cap);

/*******************************************************************************
 *
 * Function         AVDTC_GetAllCapRsp
 *
 * Description     Send a get all capabilities response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_GetAllCapRsp(const RawAddress& bd_addr, uint8_t label, AvdtpSepConfig* p_cap);

/*******************************************************************************
 *
 * Function         AVDTC_GetConfigReq
 *
 * Description      Send a get configuration request.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_GetConfigReq(uint8_t handle);

/*******************************************************************************
 *
 * Function         AVDTC_GetConfigRsp
 *
 * Description      Send a get configuration response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_GetConfigRsp(uint8_t handle, uint8_t label, AvdtpSepConfig* p_cfg);

/*******************************************************************************
 *
 * Function         AVDTC_OpenReq
 *
 * Description      Send an open request.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_OpenReq(uint8_t handle);

/*******************************************************************************
 *
 * Function         AVDTC_OpenRsp
 *
 * Description      Send an open response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_OpenRsp(uint8_t handle, uint8_t label);

/*******************************************************************************
 *
 * Function         AVDTC_StartRsp
 *
 * Description      Send a start response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_StartRsp(uint8_t* p_handles, uint8_t num_handles, uint8_t label);

/*******************************************************************************
 *
 * Function         AVDTC_CloseRsp
 *
 * Description      Send a close response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_CloseRsp(uint8_t handle, uint8_t label);

/*******************************************************************************
 *
 * Function         AVDTC_SuspendRsp
 *
 * Description      Send a suspend response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_SuspendRsp(uint8_t* p_handles, uint8_t num_handles, uint8_t label);

/*******************************************************************************
 *
 * Function         AVDTC_AbortReq
 *
 * Description      Send an abort request.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_AbortReq(uint8_t handle);

/*******************************************************************************
 *
 * Function         AVDTC_AbortRsp
 *
 * Description      Send an abort response.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_AbortRsp(uint8_t handle, uint8_t label);

/*******************************************************************************
 *
 * Function         AVDTC_Rej
 *
 * Description      Send a reject message.
 *
 * Returns          void
 *
 ******************************************************************************/
void AVDTC_Rej(uint8_t handle, const RawAddress& bd_addr, uint8_t cmd, uint8_t label,
               uint8_t err_code, uint8_t err_param);

#endif /* AVDT_CAPI_H */
