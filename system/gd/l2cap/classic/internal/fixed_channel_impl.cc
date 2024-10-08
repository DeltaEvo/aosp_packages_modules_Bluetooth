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

#include "l2cap/classic/internal/fixed_channel_impl.h"

#include <bluetooth/log.h>

#include <unordered_map>

#include "l2cap/cid.h"
#include "l2cap/classic/internal/link.h"
#include "os/handler.h"
#include "os/log.h"

namespace bluetooth {
namespace l2cap {
namespace classic {
namespace internal {

FixedChannelImpl::FixedChannelImpl(Cid cid, Link* link, os::Handler* l2cap_handler)
    : cid_(cid), device_(link->GetDevice()), link_(link), l2cap_handler_(l2cap_handler) {
  log::assert_that(cid_ >= kFirstFixedChannel && cid_ <= kLastFixedChannel, "Invalid cid: {}",
                   cid_);
  log::assert_that(link_ != nullptr, "assert failed: link_ != nullptr");
  log::assert_that(l2cap_handler_ != nullptr, "assert failed: l2cap_handler_ != nullptr");
}

void FixedChannelImpl::RegisterOnCloseCallback(os::Handler* user_handler,
                                               FixedChannel::OnCloseCallback on_close_callback) {
  log::assert_that(user_handler_ == nullptr, "OnCloseCallback can only be registered once");
  // If channel is already closed, call the callback immediately without saving it
  if (closed_) {
    user_handler->Post(common::BindOnce(std::move(on_close_callback), close_reason_));
    return;
  }
  user_handler_ = user_handler;
  on_close_callback_ = std::move(on_close_callback);
}

void FixedChannelImpl::OnClosed(hci::ErrorCode status) {
  log::assert_that(!closed_,
                   "Device {} Cid 0x{:x} closed twice, old status 0x{:x}, new status 0x{:x}",
                   ADDRESS_TO_LOGGABLE_CSTR(device_), cid_, static_cast<int>(close_reason_),
                   static_cast<int>(status));
  closed_ = true;
  close_reason_ = status;
  acquired_ = false;
  link_ = nullptr;
  l2cap_handler_ = nullptr;
  if (user_handler_ == nullptr) {
    return;
  }
  // On close callback can only be called once
  user_handler_->Post(common::BindOnce(std::move(on_close_callback_), status));
  user_handler_ = nullptr;
  on_close_callback_.Reset();
}

void FixedChannelImpl::Acquire() {
  log::assert_that(user_handler_ != nullptr,
                   "Must register OnCloseCallback before calling any methods");
  if (closed_) {
    log::warn("{} is already closed", ToRedactedStringForLogging());
    log::assert_that(!acquired_, "assert failed: !acquired_");
    return;
  }
  if (acquired_) {
    log::info("{} was already acquired", ToRedactedStringForLogging());
    return;
  }
  acquired_ = true;
  link_->RefreshRefCount();
}

void FixedChannelImpl::Release() {
  log::assert_that(user_handler_ != nullptr,
                   "Must register OnCloseCallback before calling any methods");
  if (closed_) {
    log::warn("{} is already closed", ToRedactedStringForLogging());
    log::assert_that(!acquired_, "assert failed: !acquired_");
    return;
  }
  if (!acquired_) {
    log::info("{} was already released", ToRedactedStringForLogging());
    return;
  }
  acquired_ = false;
  link_->RefreshRefCount();
}

}  // namespace internal
}  // namespace classic
}  // namespace l2cap
}  // namespace bluetooth
