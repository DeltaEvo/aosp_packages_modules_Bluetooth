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

#include "l2cap/classic/internal/dynamic_channel_service_manager_impl.h"
#include "l2cap/classic/security_enforcement_interface.h"
#include "l2cap/internal/dynamic_channel_impl.h"

// Unit test interfaces
namespace bluetooth {
namespace l2cap {
namespace classic {
namespace internal {
namespace testing {

class MockDynamicChannelServiceManagerImpl : public DynamicChannelServiceManagerImpl {
public:
  MockDynamicChannelServiceManagerImpl() : DynamicChannelServiceManagerImpl(nullptr) {}
  MOCK_METHOD(void, Register,
              (Psm psm, DynamicChannelServiceImpl::PendingRegistration pending_registration),
              (override));
  MOCK_METHOD(void, Unregister, (Psm psm, DynamicChannelService::OnUnregisteredCallback callback),
              (override));
  MOCK_METHOD(bool, IsServiceRegistered, (Psm psm), (const, override));
  MOCK_METHOD(DynamicChannelServiceImpl*, GetService, (Psm psm), (override));
  MOCK_METHOD((std::vector<std::pair<Psm, DynamicChannelServiceImpl*>>), GetRegisteredServices, (),
              (override));
  MOCK_METHOD(SecurityEnforcementInterface*, GetSecurityEnforcementInterface, (), (override));
};

}  // namespace testing
}  // namespace internal
}  // namespace classic
}  // namespace l2cap
}  // namespace bluetooth
