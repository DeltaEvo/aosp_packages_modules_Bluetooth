/*
 * Copyright 2020 The Android Open Source Project
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

// Adapted from get_item_attributes_packet_test

#include <gtest/gtest.h>

#include "avrcp_test_packets.h"
#include "get_item_attributes.h"
#include "packet_test_helper.h"

namespace bluetooth {
namespace avrcp {

using TestGetItemAttrsReqPacket = TestPacketType<GetItemAttributesRequest>;

// Builder packet.

extern "C" int LLVMFuzzerTestOneInput(const char* data, size_t size) {
  std::vector<uint8_t> get_item_attributes_request;

  if (size > 44) {
    for (size_t x = 0; x < size; x++) {
      get_item_attributes_request.push_back(data[x]);
    }
    auto test_packet = TestGetItemAttrsReqPacket::Make(get_item_attributes_request);
  }

  return 0;
}

}  // namespace avrcp
}  // namespace bluetooth
