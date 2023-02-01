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

#define LOG_TAG "bt_headless_discovery"

#include "test/headless/discovery/discovery.h"

#include <future>

#include "base/logging.h"  // LOG() stdout and android log
#include "btif/include/btif_api.h"
#include "osi/include/log.h"  // android log only
#include "stack/include/sdp_api.h"
#include "test/headless/bt_property.h"
#include "test/headless/get_options.h"
#include "test/headless/headless.h"
#include "test/headless/interface.h"
#include "test/headless/log.h"
#include "test/headless/messenger.h"
#include "test/headless/sdp/sdp.h"
#include "test/headless/stopwatch.h"
#include "test/headless/timeout.h"
#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

using namespace bluetooth::test::headless;
using namespace std::chrono_literals;

namespace {

int start_discovery([[maybe_unused]] unsigned int num_loops,
                    const RawAddress& raw_address) {
  RawAddress bd_addr{raw_address};

  Stopwatch acl_stopwatch("ACL_connection");
  Stopwatch sdp_stopwatch("SDP_discovery");

  LOG_CONSOLE("Started service discovery");
  auto check_point = messenger::sdp::get_check_point();
  ASSERT(bluetoothInterface.get_remote_services(&bd_addr, 0) ==
         BT_STATUS_SUCCESS);

  if (!messenger::acl::await_connected(8s)) {
    LOG_CONSOLE("TIMEOUT waiting for connection to %s",
                raw_address.ToString().c_str());
    return -1;
  }
  LOG_CONSOLE("ACL connected to %s :%sms", STR(raw_address),
              STR(acl_stopwatch));

  if (!messenger::sdp::await_service_discovery(8s, check_point, 1UL)) {
    LOG_CONSOLE("TIMEOUT waiting for service discovery to %s",
                raw_address.ToString().c_str());
    return -1;
  }
  auto callback_queue = messenger::sdp::collect_from(check_point);
  ASSERT_LOG(callback_queue.size() == 1,
             "Received unexpected number of SDP queries");

  auto params = callback_queue.front();
  callback_queue.pop_front();

  LOG_CONSOLE("got remote services :%s", params.ToString().c_str());

  for (int i = 0; i < params.num_properties; i++) {
    process_property(params.bd_addr, params.properties + i);
  }

  // Run a second fetch SDP
  {
    LOG_CONSOLE("Sending second SDP request");
    auto check_point = messenger::sdp::get_check_point();

    ASSERT(bluetoothInterface.get_remote_services(&bd_addr, 0) ==
           BT_STATUS_SUCCESS);

    if (!messenger::acl::await_connected(8s)) {
      LOG_CONSOLE("TIMEOUT waiting for connection to %s",
                  raw_address.ToString().c_str());
      return -1;
    }
    LOG_CONSOLE("ACL connected to %s :%sms", STR(raw_address),
                STR(acl_stopwatch));

    if (!messenger::sdp::await_service_discovery(8s, check_point, 1UL)) {
      LOG_CONSOLE("TIMEOUT waiting for service discovery to %s",
                  raw_address.ToString().c_str());
      return -1;
    }
    auto callback_queue = messenger::sdp::collect_from(check_point);
    ASSERT_LOG(callback_queue.size() == 1,
               "Received unexpected number of SDP queries");

    auto params = callback_queue.front();
    callback_queue.pop_front();

    LOG_CONSOLE("got remote services :%s", params.ToString().c_str());

    for (int i = 0; i < params.num_properties; i++) {
      process_property(params.bd_addr, params.properties);
    }
  }

  // Run a third fetch SDP
  {
    LOG_CONSOLE("Sending third SDP request");
    auto check_point = messenger::sdp::get_check_point();

    ASSERT(bluetoothInterface.get_remote_services(&bd_addr, 0) ==
           BT_STATUS_SUCCESS);

    if (!messenger::acl::await_connected(8s)) {
      LOG_CONSOLE("TIMEOUT waiting for connection to %s",
                  raw_address.ToString().c_str());
      return -1;
    }
    LOG_CONSOLE("ACL connected to %s :%sms", STR(raw_address),
                STR(acl_stopwatch));

    if (!messenger::sdp::await_service_discovery(8s, check_point, 1UL)) {
      LOG_CONSOLE("TIMEOUT waiting for service discovery to %s",
                  raw_address.ToString().c_str());
      return -1;
    }
    auto callback_queue = messenger::sdp::collect_from(check_point);
    ASSERT_LOG(callback_queue.size() == 1,
               "Received unexpected number of SDP queries");

    auto params = callback_queue.front();
    callback_queue.pop_front();

    LOG_CONSOLE("got remote services :%s", params.ToString().c_str());

    for (int i = 0; i < params.num_properties; i++) {
      process_property(params.bd_addr, params.properties);
    }
  }

  LOG_CONSOLE("Awaiting disconnect");
  if (!messenger::acl::await_disconnected(6s)) {
    LOG_CONSOLE("TIMEOUT waiting for disconnection to %s",
                raw_address.ToString().c_str());
    return -1;
  }

  LOG_CONSOLE("Dumpsys system");
  bluetoothInterface.dump(2, nullptr);
  LOG_CONSOLE("Done dumpsys system");

  return 0;
}

}  // namespace

// extern uint8_t btu_trace_level;

int bluetooth::test::headless::Discovery::Run() {
  if (options_.loop_ < 1) {
    LOG_CONSOLE("This test requires at least a single loop");
    options_.Usage();
    return -1;
  }
  if (options_.device_.size() != 1) {
    LOG_CONSOLE("This test requires a single device specified");
    options_.Usage();
    return -1;
  }
  return RunOnHeadlessStack<int>([this]() {
    return start_discovery(options_.loop_, options_.device_.front());
  });
}
