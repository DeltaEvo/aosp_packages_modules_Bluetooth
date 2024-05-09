/*
 * Copyright 2022 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "bta/dm/bta_dm_disc_int.h"
#include "bta/test/bta_test_fixtures.h"
#include "osi/include/allocator.h"

void BTA_dm_on_hw_on();
void BTA_dm_on_hw_off();

namespace {
const char kName[] = "Hello";
}

namespace bluetooth {
namespace legacy {
namespace testing {

tBTA_DM_SERVICE_DISCOVERY_CB& bta_dm_discovery_cb();
void bta_dm_sdp_result(tSDP_STATUS sdp_status);

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth

class BtaSdpTest : public BtaWithHwOnTest {
 protected:
  void SetUp() override { BtaWithHwOnTest::SetUp(); }

  void TearDown() override { BtaWithHwOnTest::TearDown(); }
};

class BtaSdpRegisteredTest : public BtaSdpTest {
 protected:
  void SetUp() override { BtaSdpTest::SetUp(); }

  void TearDown() override { BtaSdpTest::TearDown(); }

  tBTA_SYS_REG bta_sys_reg = {
      .evt_hdlr = [](const BT_HDR_RIGID* p_msg) -> bool {
        osi_free((void*)p_msg);
        return false;
      },
      .disable = []() {},
  };
};

TEST_F(BtaSdpTest, nop) {}

TEST_F(BtaSdpRegisteredTest, bta_dm_sdp_result_SDP_SUCCESS) {
  tBTA_DM_SERVICE_DISCOVERY_CB& discovery_cb =
      bluetooth::legacy::testing::bta_dm_discovery_cb();
  discovery_cb.service_index = BTA_MAX_SERVICE_ID;

  bluetooth::legacy::testing::bta_dm_sdp_result(SDP_SUCCESS);
}
