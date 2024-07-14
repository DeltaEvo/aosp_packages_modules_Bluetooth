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

#define LOG_TAG "bt_bta_dm_test"

#include <base/strings/stringprintf.h>
#include <base/test/bind_test_util.h>
#include <bluetooth/log.h>
#include <com_android_bluetooth_flags.h>
#include <flag_macros.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <sys/socket.h>

#include "bta/dm/bta_dm_device_search.h"
#include "bta/dm/bta_dm_device_search_int.h"
#include "bta/dm/bta_dm_disc.h"
#include "bta/dm/bta_dm_disc_int.h"
#include "bta/test/bta_test_fixtures.h"
#include "bta_api_data_types.h"
#include "stack/btm/neighbor_inquiry.h"
#include "stack/include/gatt_api.h"
#include "test/common/main_handler.h"
#include "types/bt_transport.h"

#define TEST_BT com::android::bluetooth::flags

using namespace bluetooth;

namespace {
const RawAddress kRawAddress({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
}

// Test hooks
namespace bluetooth {
namespace legacy {
namespace testing {

void bta_dm_disc_init_search_cb(tBTA_DM_SEARCH_CB& bta_dm_search_cb);
bool bta_dm_read_remote_device_name(const RawAddress& bd_addr, tBT_TRANSPORT transport);
tBTA_DM_SEARCH_CB& bta_dm_disc_search_cb();
void bta_dm_discover_next_device();
void bta_dm_sdp_find_services(tBTA_DM_SDP_STATE* state);
void bta_dm_inq_cmpl();
void bta_dm_inq_cmpl_cb(void* p_result);
void bta_dm_observe_cmpl_cb(void* p_result);
void bta_dm_observe_results_cb(tBTM_INQ_RESULTS* p_inq, const uint8_t* p_eir, uint16_t eir_len);
void bta_dm_opportunistic_observe_results_cb(tBTM_INQ_RESULTS* p_inq, const uint8_t* p_eir,
                                             uint16_t eir_len);
void bta_dm_queue_search(tBTA_DM_API_SEARCH& search);
void bta_dm_start_scan(uint8_t duration_sec, bool low_latency_scan = false);
}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth

class BtaInitializedTest : public BtaWithContextTest {
protected:
  void SetUp() override {
    BtaWithContextTest::SetUp();
    BTA_dm_init();
  }

  void TearDown() override { BtaWithContextTest::TearDown(); }
};

TEST_F(BtaInitializedTest, nop) {}

TEST_F(BtaInitializedTest, DumpsysBtaDmDisc) {
  std::FILE* file = std::tmpfile();
  DumpsysBtaDmDisc(fileno(file));
}

TEST_F(BtaInitializedTest, bta_dm_ble_csis_observe) {
  bta_dm_ble_csis_observe(true, [](tBTA_DM_SEARCH_EVT, tBTA_DM_SEARCH*) {});
}

TEST_F(BtaInitializedTest, bta_dm_ble_csis_observe__false) {
  bta_dm_ble_csis_observe(false, [](tBTA_DM_SEARCH_EVT, tBTA_DM_SEARCH*) {});
}

TEST_F(BtaInitializedTest, bta_dm_ble_scan) {
  // bool start, uint8_t duration_sec, bool low_latency_scan
  constexpr bool kStartLeScan = true;
  constexpr bool kStopLeScan = false;
  const uint8_t duration_in_seconds = 5;
  constexpr bool kLowLatencyScan = true;
  constexpr bool kHighLatencyScan = false;

  bta_dm_ble_scan(kStartLeScan, duration_in_seconds, kLowLatencyScan);
  bta_dm_ble_scan(kStopLeScan, duration_in_seconds, kLowLatencyScan);

  bta_dm_ble_scan(kStartLeScan, duration_in_seconds, kHighLatencyScan);
  bta_dm_ble_scan(kStopLeScan, duration_in_seconds, kHighLatencyScan);
}

TEST_F(BtaInitializedTest, bta_dm_disc_discover_next_device) { bta_dm_disc_discover_next_device(); }

TEST_F(BtaInitializedTest, bta_dm_disc_remove_device) { bta_dm_disc_remove_device(kRawAddress); }

TEST_F(BtaInitializedTest, bta_dm_discover_next_device) {
  bluetooth::legacy::testing::bta_dm_discover_next_device();
}

TEST_F(BtaInitializedTest, bta_dm_sdp_find_services) {
  std::unique_ptr<tBTA_DM_SDP_STATE> state = std::make_unique<tBTA_DM_SDP_STATE>(tBTA_DM_SDP_STATE{
          .bd_addr = kRawAddress,
          .services_to_search = BTA_ALL_SERVICE_MASK,
          .services_found = 0,
          .service_index = 0,
  });
  bluetooth::legacy::testing::bta_dm_sdp_find_services(state.get());
}

TEST_F(BtaInitializedTest, bta_dm_inq_cmpl) { bluetooth::legacy::testing::bta_dm_inq_cmpl(); }

TEST_F(BtaInitializedTest, bta_dm_inq_cmpl_cb) {
  tBTM_INQUIRY_CMPL complete;
  bluetooth::legacy::testing::bta_dm_inq_cmpl_cb(&complete);
}

TEST_F(BtaInitializedTest, bta_dm_observe_cmpl_cb) {
  tBTM_INQUIRY_CMPL complete;
  bluetooth::legacy::testing::bta_dm_observe_cmpl_cb(&complete);
}
TEST_F(BtaInitializedTest, bta_dm_observe_results_cb) {
  tBTM_INQ_RESULTS result;
  const uint8_t p_eir[] = {0x0, 0x1, 0x2, 0x3};
  uint16_t eir_len = sizeof(p_eir);
  bluetooth::legacy::testing::bta_dm_observe_results_cb(&result, p_eir, eir_len);
}

TEST_F(BtaInitializedTest, bta_dm_opportunistic_observe_results_cb) {
  tBTM_INQ_RESULTS result;
  const uint8_t p_eir[] = {0x0, 0x1, 0x2, 0x3};
  uint16_t eir_len = sizeof(p_eir);
  bluetooth::legacy::testing::bta_dm_opportunistic_observe_results_cb(&result, p_eir, eir_len);
}

TEST_F(BtaInitializedTest, bta_dm_queue_search) {
  tBTA_DM_API_SEARCH search{};
  bluetooth::legacy::testing::bta_dm_queue_search(search);

  // Release the queued search
  bta_dm_disc_stop();
}

TEST_F(BtaInitializedTest, bta_dm_read_remote_device_name) {
  bluetooth::legacy::testing::bta_dm_read_remote_device_name(kRawAddress, BT_TRANSPORT_BR_EDR);
}

TEST_F(BtaInitializedTest, bta_dm_start_scan) {
  constexpr bool kLowLatencyScan = true;
  constexpr bool kHighLatencyScan = false;
  const uint8_t duration_sec = 5;
  bluetooth::legacy::testing::bta_dm_start_scan(duration_sec, kLowLatencyScan);
  bluetooth::legacy::testing::bta_dm_start_scan(duration_sec, kHighLatencyScan);
}

TEST_F(BtaInitializedTest, bta_dm_disc_start_device_discovery) {
  bta_dm_disc_start_device_discovery([](tBTA_DM_SEARCH_EVT event, tBTA_DM_SEARCH* p_data) {});
}

TEST_F(BtaInitializedTest, bta_dm_disc_stop_device_discovery) {
  bta_dm_disc_stop_device_discovery();
}

TEST_F(BtaInitializedTest, bta_dm_disc_start_service_discovery__BT_TRANSPORT_AUTO) {
  bta_dm_disc_start_service_discovery(
          {nullptr, nullptr, nullptr,
           [](RawAddress, const std::vector<bluetooth::Uuid>&, tBTA_STATUS) {}},
          kRawAddress, BT_TRANSPORT_AUTO);
}

// must be global, as capturing lambda can't be treated as function
int service_cb_call_cnt = 0;

TEST_F_WITH_FLAGS(BtaInitializedTest, bta_dm_disc_start_service_discovery__BT_TRANSPORT_BR_EDR,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      separate_service_and_device_discovery))) {
  bta_dm_disc_start(true);
  int sdp_call_cnt = 0;
  base::RepeatingCallback<void(tBTA_DM_SDP_STATE*)> sdp_performer =
          base::BindLambdaForTesting([&](tBTA_DM_SDP_STATE* sdp_state) {
            sdp_call_cnt++;
            bta_dm_sdp_finished(sdp_state->bd_addr, BTA_SUCCESS, {}, {});
          });

  bta_dm_disc_override_sdp_performer_for_testing(sdp_performer);
  service_cb_call_cnt = 0;

  bta_dm_disc_start_service_discovery({nullptr, nullptr, nullptr,
                                       [](RawAddress addr, const std::vector<bluetooth::Uuid>&,
                                          tBTA_STATUS) { service_cb_call_cnt++; }},
                                      kRawAddress, BT_TRANSPORT_BR_EDR);

  EXPECT_EQ(sdp_call_cnt, 1);
  EXPECT_EQ(service_cb_call_cnt, 1);

  bta_dm_disc_override_sdp_performer_for_testing({});
}

// must be global, as capturing lambda can't be treated as function
int gatt_service_cb_call_cnt = 0;

TEST_F_WITH_FLAGS(BtaInitializedTest, bta_dm_disc_start_service_discovery__BT_TRANSPORT_LE,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      separate_service_and_device_discovery))) {
  bta_dm_disc_start(true);
  int gatt_call_cnt = 0;
  base::RepeatingCallback<void(const RawAddress&)> gatt_performer =
          base::BindLambdaForTesting([&](const RawAddress& bd_addr) {
            gatt_call_cnt++;
            bta_dm_gatt_finished(bd_addr, BTA_SUCCESS);
          });
  bta_dm_disc_override_gatt_performer_for_testing(gatt_performer);
  gatt_service_cb_call_cnt = 0;

  bta_dm_disc_start_service_discovery({[](RawAddress, BD_NAME, std::vector<bluetooth::Uuid>&,
                                          bool) { gatt_service_cb_call_cnt++; },
                                       nullptr, nullptr, nullptr},
                                      kRawAddress, BT_TRANSPORT_LE);

  EXPECT_EQ(gatt_call_cnt, 1);
  EXPECT_EQ(gatt_service_cb_call_cnt, 1);

  bta_dm_disc_override_gatt_performer_for_testing({});
}

// must be global, as capturing lambda can't be treated as function
int service_cb_both_call_cnt = 0;
int gatt_service_cb_both_call_cnt = 0;

/* This test exercises the usual service discovery flow when bonding to
 * dual-mode, CTKD capable device on LE transport.
 */
TEST_F_WITH_FLAGS(BtaInitializedTest, bta_dm_disc_both_transports_flag_disabled,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT,
                                                      separate_service_and_device_discovery)),
                  REQUIRES_FLAGS_DISABLED(ACONFIG_FLAG(TEST_BT, bta_dm_discover_both))) {
  bta_dm_disc_start(true);

  std::promise<void> gatt_triggered;
  int gatt_call_cnt = 0;
  base::RepeatingCallback<void(const RawAddress&)> gatt_performer =
          base::BindLambdaForTesting([&](const RawAddress& bd_addr) {
            gatt_call_cnt++;
            gatt_triggered.set_value();
          });
  bta_dm_disc_override_gatt_performer_for_testing(gatt_performer);

  int sdp_call_cnt = 0;
  base::RepeatingCallback<void(tBTA_DM_SDP_STATE*)> sdp_performer =
          base::BindLambdaForTesting([&](tBTA_DM_SDP_STATE* sdp_state) { sdp_call_cnt++; });
  bta_dm_disc_override_sdp_performer_for_testing(sdp_performer);

  gatt_service_cb_both_call_cnt = 0;
  service_cb_both_call_cnt = 0;

  bta_dm_disc_start_service_discovery(
          {[](RawAddress, BD_NAME, std::vector<bluetooth::Uuid>&, bool) {}, nullptr, nullptr,
           [](RawAddress addr, const std::vector<bluetooth::Uuid>&, tBTA_STATUS) {
             service_cb_both_call_cnt++;
           }},
          kRawAddress, BT_TRANSPORT_BR_EDR);
  EXPECT_EQ(sdp_call_cnt, 1);

  bta_dm_disc_start_service_discovery(
          {[](RawAddress, BD_NAME, std::vector<bluetooth::Uuid>&, bool) {
             gatt_service_cb_both_call_cnt++;
           },
           nullptr, nullptr,
           [](RawAddress addr, const std::vector<bluetooth::Uuid>&, tBTA_STATUS) {}},
          kRawAddress, BT_TRANSPORT_LE);

  // GATT discovery is queued, until SDP finishes
  EXPECT_EQ(gatt_call_cnt, 0);

  bta_dm_sdp_finished(kRawAddress, BTA_SUCCESS, {}, {});
  EXPECT_EQ(service_cb_both_call_cnt, 1);

  // SDP finished, wait until GATT is triggered.
  EXPECT_EQ(std::future_status::ready,
            gatt_triggered.get_future().wait_for(std::chrono::seconds(1)));
  bta_dm_gatt_finished(kRawAddress, BTA_SUCCESS);
  EXPECT_EQ(gatt_service_cb_both_call_cnt, 1);

  bta_dm_disc_override_sdp_performer_for_testing({});
  bta_dm_disc_override_gatt_performer_for_testing({});
}

/* This test exercises the usual service discovery flow when bonding to
 * dual-mode, CTKD capable device on LE transport.
 */
TEST_F_WITH_FLAGS(BtaInitializedTest, bta_dm_disc_both_transports_flag_enabled,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(TEST_BT, bta_dm_discover_both))) {
  bta_dm_disc_start(true);

  int gatt_call_cnt = 0;
  base::RepeatingCallback<void(const RawAddress&)> gatt_performer =
          base::BindLambdaForTesting([&](const RawAddress& bd_addr) { gatt_call_cnt++; });
  bta_dm_disc_override_gatt_performer_for_testing(gatt_performer);

  int sdp_call_cnt = 0;
  base::RepeatingCallback<void(tBTA_DM_SDP_STATE*)> sdp_performer =
          base::BindLambdaForTesting([&](tBTA_DM_SDP_STATE* sdp_state) { sdp_call_cnt++; });
  bta_dm_disc_override_sdp_performer_for_testing(sdp_performer);

  gatt_service_cb_both_call_cnt = 0;
  service_cb_both_call_cnt = 0;

  bta_dm_disc_start_service_discovery({[](RawAddress, BD_NAME, std::vector<bluetooth::Uuid>&,
                                          bool) { gatt_service_cb_both_call_cnt++; },
                                       nullptr, nullptr,
                                       [](RawAddress addr, const std::vector<bluetooth::Uuid>&,
                                          tBTA_STATUS) { service_cb_both_call_cnt++; }},
                                      kRawAddress, BT_TRANSPORT_BR_EDR);
  EXPECT_EQ(sdp_call_cnt, 1);

  bta_dm_disc_start_service_discovery({[](RawAddress, BD_NAME, std::vector<bluetooth::Uuid>&,
                                          bool) { gatt_service_cb_both_call_cnt++; },
                                       nullptr, nullptr,
                                       [](RawAddress addr, const std::vector<bluetooth::Uuid>&,
                                          tBTA_STATUS) { service_cb_both_call_cnt++; }},
                                      kRawAddress, BT_TRANSPORT_LE);

  // GATT discovery on same device is immediately started
  EXPECT_EQ(gatt_call_cnt, 1);

  // GATT finished first
  bta_dm_gatt_finished(kRawAddress, BTA_SUCCESS);
  EXPECT_EQ(gatt_service_cb_both_call_cnt, 1);

  // SDP finishes too
  bta_dm_sdp_finished(kRawAddress, BTA_SUCCESS, {}, {});
  EXPECT_EQ(service_cb_both_call_cnt, 1);

  bta_dm_disc_override_sdp_performer_for_testing({});
  bta_dm_disc_override_gatt_performer_for_testing({});
}

TEST_F(BtaInitializedTest, init_bta_dm_search_cb__conn_id) {
  // Set the global search block target field to some non-reset value
  tBTA_DM_SEARCH_CB& search_cb = bluetooth::legacy::testing::bta_dm_disc_search_cb();
  search_cb.name_discover_done = true;

  bluetooth::legacy::testing::bta_dm_disc_init_search_cb(search_cb);

  // Verify global search block field reset value is correct
  ASSERT_EQ(search_cb.name_discover_done, false);
}
