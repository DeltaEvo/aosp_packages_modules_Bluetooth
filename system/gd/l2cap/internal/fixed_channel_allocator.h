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

#include <type_traits>
#include <unordered_map>

#include "hci/hci_packets.h"
#include "l2cap/cid.h"
#include "os/handler.h"
#include "os/log.h"

namespace bluetooth {
namespace l2cap {

namespace classic {
namespace internal {
class DumpsysHelper;
}  // namespace internal
}  // namespace classic

namespace internal {

// Helper class for keeping channels in a Link. It allocates and frees Channel object, and supports
// querying whether a channel is in use
template <typename FixedChannelImplType, typename LinkType>
class FixedChannelAllocator {
public:
  FixedChannelAllocator(LinkType* link, os::Handler* l2cap_handler)
      : link_(link), l2cap_handler_(l2cap_handler) {
    log::assert_that(link_ != nullptr, "assert failed: link_ != nullptr");
    log::assert_that(l2cap_handler_ != nullptr, "assert failed: l2cap_handler_ != nullptr");
  }

  virtual ~FixedChannelAllocator() = default;

  // Allocates a channel. If cid is used, return nullptr. NOTE: The returned BaseFixedChannelImpl
  // object is still owned by the channel allocator, NOT the client.
  virtual std::shared_ptr<FixedChannelImplType> AllocateChannel(Cid cid) {
    log::assert_that(!IsChannelAllocated(cid), "Cid 0x{:x} for link {} is already in use", cid,
                     link_->ToRedactedStringForLogging());

    log::assert_that(cid >= kFirstFixedChannel && cid <= kLastFixedChannel, "Cid {} out of bound",
                     cid);
    auto elem = channels_.try_emplace(
            cid, std::make_shared<FixedChannelImplType>(cid, link_, l2cap_handler_));
    log::assert_that(elem.second, "Failed to create channel for cid 0x{:x} link {}", cid,
                     link_->ToRedactedStringForLogging());
    log::assert_that(elem.first->second != nullptr, "assert failed: elem.first->second != nullptr");
    return elem.first->second;
  }

  // Frees a channel. If cid doesn't exist, it will crash
  virtual void FreeChannel(Cid cid) {
    log::assert_that(IsChannelAllocated(cid), "Channel is not in use: cid {}, link {}", cid,
                     link_->ToRedactedStringForLogging());

    channels_.erase(cid);
  }

  virtual bool IsChannelAllocated(Cid cid) const { return channels_.find(cid) != channels_.end(); }

  virtual std::shared_ptr<FixedChannelImplType> FindChannel(Cid cid) {
    log::assert_that(IsChannelAllocated(cid), "Channel is not in use: cid {}, link {}", cid,
                     link_->ToRedactedStringForLogging());

    return channels_.find(cid)->second;
  }

  virtual size_t NumberOfChannels() const { return channels_.size(); }

  virtual void OnAclDisconnected(hci::ErrorCode hci_status) {
    for (auto& elem : channels_) {
      elem.second->OnClosed(hci_status);
    }
  }

  virtual int GetRefCount() {
    int ref_count = 0;
    for (auto& elem : channels_) {
      if (elem.second->IsAcquired()) {
        ref_count++;
      }
    }
    return ref_count;
  }

private:
  friend class bluetooth::l2cap::classic::internal::DumpsysHelper;
  LinkType* link_;
  os::Handler* l2cap_handler_;
  std::unordered_map<Cid, std::shared_ptr<FixedChannelImplType>> channels_;
};

}  // namespace internal
}  // namespace l2cap
}  // namespace bluetooth
