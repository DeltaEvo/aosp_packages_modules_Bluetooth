/******************************************************************************
 *
 *  Copyright 2006-2012 Broadcom Corporation
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
 *  This is the interface file for device mananger call-in functions.
 *
 ******************************************************************************/
#ifndef BTA_DM_CI_H
#define BTA_DM_CI_H

#include <cstdint>

#include "stack/include/bt_octets.h"
#include "types/raw_address.h"

/*****************************************************************************
 *  Function Declarations
 ****************************************************************************/

/*******************************************************************************
 *
 * Function         bta_dm_ci_rmt_oob
 *
 * Description      This function must be called in response to function
 *                  bta_dm_co_rmt_oob() to provide the OOB data associated
 *                  with the remote device.
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_ci_rmt_oob(bool accept, const RawAddress& bd_addr, const Octet16& c, const Octet16& r);
/*******************************************************************************
 *
 * Function         bta_dm_sco_ci_data_ready
 *
 * Description      This function sends an event to indicating that the phone
 *                  has SCO data ready..
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_sco_ci_data_ready(uint16_t event, uint16_t sco_handle);

#endif
