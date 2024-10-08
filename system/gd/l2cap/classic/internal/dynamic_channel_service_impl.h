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

#include "l2cap/classic/dynamic_channel.h"
#include "l2cap/classic/dynamic_channel_configuration_option.h"
#include "l2cap/classic/dynamic_channel_manager.h"
#include "l2cap/classic/dynamic_channel_service.h"
#include "l2cap/classic/security_policy.h"

namespace bluetooth {
namespace l2cap {
namespace classic {
namespace internal {
class DynamicChannelServiceImpl {
public:
  virtual ~DynamicChannelServiceImpl() = default;

  struct PendingRegistration {
    os::Handler* user_handler_ = nullptr;
    classic::SecurityPolicy security_policy_;
    DynamicChannelManager::OnRegistrationCompleteCallback on_registration_complete_callback_;
    DynamicChannelManager::OnConnectionOpenCallback on_connection_open_callback_;
    DynamicChannelConfigurationOption configuration_;
  };

  virtual void NotifyChannelCreation(std::unique_ptr<DynamicChannel> channel) {
    on_connection_open_callback_(std::move(channel));
  }

  virtual DynamicChannelConfigurationOption GetConfigOption() const { return config_option_; }

  virtual SecurityPolicy GetSecurityPolicy() const { return security_policy_; }

  friend class DynamicChannelServiceManagerImpl;

protected:
  // protected access for mocking
  DynamicChannelServiceImpl(
          classic::SecurityPolicy security_policy,
          DynamicChannelManager::OnConnectionOpenCallback on_connection_open_callback,
          DynamicChannelConfigurationOption config_option)
      : security_policy_(security_policy),
        on_connection_open_callback_(std::move(on_connection_open_callback)),
        config_option_(config_option) {}

private:
  classic::SecurityPolicy security_policy_;
  DynamicChannelManager::OnConnectionOpenCallback on_connection_open_callback_;
  DynamicChannelConfigurationOption config_option_;
};

}  // namespace internal
}  // namespace classic
}  // namespace l2cap
}  // namespace bluetooth
