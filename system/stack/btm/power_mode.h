/*
 * Copyright 2023 The Android Open Source Project
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

#pragma once

#include <base/strings/stringprintf.h>

#include <cstdint>
#include <string>

#include "stack/include/btm_status.h"
#include "stack/include/hci_error_code.h"
#include "stack/include/hci_mode.h"
#include "types/raw_address.h"

/* BTM Power manager status codes */
enum : uint8_t {
  BTM_PM_STS_ACTIVE = HCI_MODE_ACTIVE,  // 0x00
  BTM_PM_STS_HOLD = HCI_MODE_HOLD,      // 0x01
  BTM_PM_STS_SNIFF = HCI_MODE_SNIFF,    // 0x02
  BTM_PM_STS_PARK = HCI_MODE_PARK,      // 0x03
  BTM_PM_STS_SSR,     /* report the SSR parameters in HCI_SNIFF_SUB_RATE_EVT */
  BTM_PM_STS_PENDING, /* when waiting for status from controller */
  BTM_PM_STS_ERROR    /* when HCI command status returns error */
};
typedef uint8_t tBTM_PM_STATUS;

inline std::string power_mode_status_text(tBTM_PM_STATUS status) {
  switch (status) {
    case BTM_PM_STS_ACTIVE:
      return std::string("active");
    case BTM_PM_STS_HOLD:
      return std::string("hold");
    case BTM_PM_STS_SNIFF:
      return std::string("sniff");
    case BTM_PM_STS_PARK:
      return std::string("park");
    case BTM_PM_STS_SSR:
      return std::string("sniff_subrating");
    case BTM_PM_STS_PENDING:
      return std::string("pending");
    case BTM_PM_STS_ERROR:
      return std::string("error");
    default:
      return std::string("UNKNOWN");
  }
}

/* BTM Power manager modes */
enum : uint8_t {
  BTM_PM_MD_ACTIVE = HCI_MODE_ACTIVE,  // 0x00
  BTM_PM_MD_HOLD = HCI_MODE_HOLD,      // 0x01
  BTM_PM_MD_SNIFF = HCI_MODE_SNIFF,    // 0x02
  BTM_PM_MD_PARK = HCI_MODE_PARK,      // 0x03
  BTM_PM_MD_FORCE = 0x10, /* OR this to force ACL link to a certain mode */
  BTM_PM_MD_UNKNOWN = 0xEF,
};

typedef uint8_t tBTM_PM_MODE;
#define HCI_TO_BTM_POWER_MODE(mode) (static_cast<tBTM_PM_MODE>(mode))

inline bool is_legal_power_mode(tBTM_PM_MODE mode) {
  switch (mode & ~BTM_PM_MD_FORCE) {
    case BTM_PM_MD_ACTIVE:
    case BTM_PM_MD_HOLD:
    case BTM_PM_MD_SNIFF:
    case BTM_PM_MD_PARK:
      return true;
    default:
      return false;
  }
}

inline std::string power_mode_text(tBTM_PM_MODE mode) {
  std::string s = base::StringPrintf((mode & BTM_PM_MD_FORCE) ? "" : "forced:");
  switch (mode & ~BTM_PM_MD_FORCE) {
    case BTM_PM_MD_ACTIVE:
      return s + std::string("active");
    case BTM_PM_MD_HOLD:
      return s + std::string("hold");
    case BTM_PM_MD_SNIFF:
      return s + std::string("sniff");
    case BTM_PM_MD_PARK:
      return s + std::string("park");
    default:
      return s + std::string("UNKNOWN");
  }
}

#define BTM_PM_SET_ONLY_ID 0x80

/* Operation codes */
typedef enum : uint8_t {
  /* The module wants to set the desired power mode */
  BTM_PM_REG_SET = (1u << 0),
  /* The module does not want to involve with PM anymore */
  BTM_PM_DEREG = (1u << 2),
} tBTM_PM_REGISTER;

typedef struct {
  uint16_t max = 0;
  uint16_t min = 0;
  uint16_t attempt = 0;
  uint16_t timeout = 0;
  tBTM_PM_MODE mode = BTM_PM_MD_ACTIVE;  // 0
} tBTM_PM_PWR_MD;

typedef void(tBTM_PM_STATUS_CBACK)(const RawAddress& p_bda,
                                   tBTM_PM_STATUS status, uint16_t value,
                                   tHCI_STATUS hci_status);

#define BTM_CONTRL_UNKNOWN 0
/* ACL link on, SCO link ongoing, sniff mode */
#define BTM_CONTRL_ACTIVE 1
/* Scan state - paging/inquiry/trying to connect*/
#define BTM_CONTRL_SCAN 2
/* Idle state - page scan, LE advt, inquiry scan */
#define BTM_CONTRL_IDLE 3

typedef uint8_t tBTM_CONTRL_STATE;

/*******************************************************************************
 *
 * Function         BTM_PmRegister
 *
 * Description      register or deregister with power manager
 *
 * Returns          BTM_SUCCESS if successful,
 *                  BTM_NO_RESOURCES if no room to hold registration
 *                  BTM_ILLEGAL_VALUE
 *
 ******************************************************************************/
tBTM_STATUS BTM_PmRegister(uint8_t mask, uint8_t* p_pm_id,
                           tBTM_PM_STATUS_CBACK* p_cb);

// Notified by ACL that a new link is connected
void BTM_PM_OnConnected(uint16_t handle, const RawAddress& remote_bda);

// Notified by ACL that a link is disconnected
void BTM_PM_OnDisconnected(uint16_t handle);

/*******************************************************************************
 *
 * Function         BTM_SetPowerMode
 *
 * Description      store the mode in control block or
 *                  alter ACL connection behavior.
 *
 * Returns          BTM_SUCCESS if successful,
 *                  BTM_UNKNOWN_ADDR if bd addr is not active or bad
 *
 ******************************************************************************/
tBTM_STATUS BTM_SetPowerMode(uint8_t pm_id, const RawAddress& remote_bda,
                             const tBTM_PM_PWR_MD* p_mode);
bool BTM_SetLinkPolicyActiveMode(const RawAddress& remote_bda);

/*******************************************************************************
 *
 * Function         BTM_SetSsrParams
 *
 * Description      This sends the given SSR parameters for the given ACL
 *                  connection if it is in ACTIVE mode.
 *
 * Input Param      remote_bda - device address of desired ACL connection
 *                  max_lat    - maximum latency (in 0.625ms)(0-0xFFFE)
 *                  min_rmt_to - minimum remote timeout
 *                  min_loc_to - minimum local timeout
 *
 *
 * Returns          BTM_SUCCESS if the HCI command is issued successful,
 *                  BTM_UNKNOWN_ADDR if bd addr is not active or bad
 *                  BTM_CMD_STORED if the command is stored
 *
 ******************************************************************************/
tBTM_STATUS BTM_SetSsrParams(const RawAddress& remote_bda, uint16_t max_lat,
                             uint16_t min_rmt_to, uint16_t min_loc_to);

/*******************************************************************************
 *
 * Function         BTM_PM_ReadControllerState
 *
 * Description      This function is called to obtain the controller state
 *
 * Returns          Controller state (BTM_CONTRL_ACTIVE, BTM_CONTRL_SCAN, and
 *                                    BTM_CONTRL_IDLE)
 *
 ******************************************************************************/
tBTM_CONTRL_STATE BTM_PM_ReadControllerState(void);
