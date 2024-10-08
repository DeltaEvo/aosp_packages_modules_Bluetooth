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

#include "l2cap/le/internal/fixed_channel_service_manager_impl.h"

#include <bluetooth/log.h>

#include "common/bind.h"
#include "l2cap/cid.h"
#include "l2cap/le/internal/fixed_channel_service_impl.h"
#include "os/log.h"

namespace bluetooth {
namespace l2cap {
namespace le {
namespace internal {

void FixedChannelServiceManagerImpl::Register(
        Cid cid, FixedChannelServiceImpl::PendingRegistration pending_registration) {
  if (cid < kFirstFixedChannel || cid > kLastFixedChannel || cid == kLeSignallingCid) {
    std::unique_ptr<FixedChannelService> invalid_service(new FixedChannelService());
    pending_registration.user_handler_->Post(
            common::BindOnce(std::move(pending_registration.on_registration_complete_callback_),
                             FixedChannelManager::RegistrationResult::FAIL_INVALID_SERVICE,
                             std::move(invalid_service)));
  } else if (IsServiceRegistered(cid)) {
    std::unique_ptr<FixedChannelService> invalid_service(new FixedChannelService());
    pending_registration.user_handler_->Post(
            common::BindOnce(std::move(pending_registration.on_registration_complete_callback_),
                             FixedChannelManager::RegistrationResult::FAIL_DUPLICATE_SERVICE,
                             std::move(invalid_service)));
  } else {
    service_map_.try_emplace(
            cid,
            FixedChannelServiceImpl(pending_registration.user_handler_,
                                    std::move(pending_registration.on_connection_open_callback_)));
    std::unique_ptr<FixedChannelService> user_service(
            new FixedChannelService(cid, this, l2cap_layer_handler_));
    pending_registration.user_handler_->Post(common::BindOnce(
            std::move(pending_registration.on_registration_complete_callback_),
            FixedChannelManager::RegistrationResult::SUCCESS, std::move(user_service)));
  }
}

void FixedChannelServiceManagerImpl::Unregister(
        Cid cid, FixedChannelService::OnUnregisteredCallback callback, os::Handler* handler) {
  if (IsServiceRegistered(cid)) {
    service_map_.erase(cid);
    handler->Post(std::move(callback));
  } else {
    log::error("service not registered cid:{}", cid);
  }
}

bool FixedChannelServiceManagerImpl::IsServiceRegistered(Cid cid) const {
  return service_map_.find(cid) != service_map_.end();
}

FixedChannelServiceImpl* FixedChannelServiceManagerImpl::GetService(Cid cid) {
  log::assert_that(IsServiceRegistered(cid), "assert failed: IsServiceRegistered(cid)");
  return &service_map_.find(cid)->second;
}

std::vector<std::pair<Cid, FixedChannelServiceImpl*>>
FixedChannelServiceManagerImpl::GetRegisteredServices() {
  std::vector<std::pair<Cid, FixedChannelServiceImpl*>> results;
  for (auto& elem : service_map_) {
    results.emplace_back(elem.first, &elem.second);
  }
  return results;
}

}  // namespace internal
}  // namespace le
}  // namespace l2cap
}  // namespace bluetooth
