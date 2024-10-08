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

#include <bluetooth/log.h>

#include "common/contextual_callback.h"
#include "l2cap/psm.h"
#include "os/handler.h"
#include "os/log.h"

namespace bluetooth {
namespace l2cap {
namespace classic {

namespace internal {
class DynamicChannelServiceManagerImpl;
}

class DynamicChannelService {
public:
  DynamicChannelService() = default;
  DynamicChannelService(const DynamicChannelService&) = delete;
  DynamicChannelService& operator=(const DynamicChannelService&) = delete;

  using OnUnregisteredCallback = common::ContextualOnceCallback<void()>;

  /**
   * Unregister a service from L2CAP module. This operation cannot fail.
   * All channels opened for this service will be closed.
   *
   * @param on_unregistered will be triggered when unregistration is complete
   */
  void Unregister(OnUnregisteredCallback on_unregistered);

  friend internal::DynamicChannelServiceManagerImpl;

  Psm GetPsm() const;

protected:
  DynamicChannelService(Psm psm, internal::DynamicChannelServiceManagerImpl* manager,
                        os::Handler* handler)
      : psm_(psm), manager_(manager), l2cap_layer_handler_(handler) {
    log::assert_that(IsPsmValid(psm), "assert failed: IsPsmValid(psm)");
    log::assert_that(manager_ != nullptr, "assert failed: manager_ != nullptr");
    log::assert_that(l2cap_layer_handler_ != nullptr,
                     "assert failed: l2cap_layer_handler_ != nullptr");
  }

private:
  Psm psm_ = kDefaultPsm;
  internal::DynamicChannelServiceManagerImpl* manager_ = nullptr;
  os::Handler* l2cap_layer_handler_;
};

}  // namespace classic
}  // namespace l2cap
}  // namespace bluetooth
