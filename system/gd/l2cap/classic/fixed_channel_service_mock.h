/*
 * Copyright 2019 The Android Open Source Project
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

#include <gmock/gmock.h>

#include "l2cap/classic/fixed_channel_service.h"

// Unit test interfaces
namespace bluetooth {
namespace l2cap {
namespace classic {
namespace testing {

class MockFixedChannelService : public FixedChannelService {
public:
  MockFixedChannelService() : FixedChannelService() {}
  MOCK_METHOD(void, Unregister,
              (OnUnregisteredCallback on_unregistered, os::Handler* on_unregistered_handler));
};

}  // namespace testing
}  // namespace classic
}  // namespace l2cap
}  // namespace bluetooth
