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

#include "common/bidi_queue.h"
#include "common/interfaces/ILoggable.h"
#include "hci/acl_manager/le_acl_connection.h"
#include "l2cap/cid.h"
#include "l2cap/internal/channel_impl.h"
#include "l2cap/le/fixed_channel.h"
#include "l2cap/le/link_options.h"
#include "os/handler.h"
#include "os/log.h"
namespace bluetooth {
namespace l2cap {
namespace le {
namespace internal {

class Link;

class FixedChannelImpl : public l2cap::internal::ChannelImpl,
                         public bluetooth::common::IRedactableLoggable {
public:
  FixedChannelImpl(Cid cid, Link* link, os::Handler* l2cap_handler);

  FixedChannelImpl(const FixedChannelImpl&) = delete;
  FixedChannelImpl& operator=(const FixedChannelImpl&) = delete;

  virtual ~FixedChannelImpl() = default;

  hci::AddressWithType GetDevice() const { return device_; }

  /* Return the role we have in the associated link */
  virtual hci::Role GetRole() const;

  virtual hci::acl_manager::LeAclConnection* GetAclConnection() const;

  virtual void RegisterOnCloseCallback(os::Handler* user_handler,
                                       FixedChannel::OnCloseCallback on_close_callback);

  virtual void Acquire();

  virtual void Release();

  virtual bool IsAcquired() const { return acquired_; }

  Cid GetCid() const override;
  Cid GetRemoteCid() const override;
  virtual void OnClosed(hci::ErrorCode status);

  virtual std::string ToString() {
    std::ostringstream ss;
    ss << "Device " << device_ << " Cid 0x" << std::hex << cid_;
    return ss.str();
  }

  std::string ToStringForLogging() const override {
    std::ostringstream ss;
    ss << "Device " << device_.ToStringForLogging() << " Cid 0x" << std::hex << cid_;
    return ss.str();
  }

  std::string ToRedactedStringForLogging() const override {
    std::ostringstream ss;
    ss << "Device " << device_.ToRedactedStringForLogging() << " Cid 0x" << std::hex << cid_;
    return ss.str();
  }

  common::BidiQueueEnd<packet::BasePacketBuilder, packet::PacketView<packet::kLittleEndian>>*
  GetQueueUpEnd() {
    return channel_queue_.GetUpEnd();
  }

  common::BidiQueueEnd<packet::PacketView<packet::kLittleEndian>, packet::BasePacketBuilder>*
  GetQueueDownEnd() {
    return channel_queue_.GetDownEnd();
  }

  LinkOptions* GetLinkOptions();

private:
  // Constructor states
  // For logging purpose only
  const Cid cid_;
  // For logging purpose only
  const hci::AddressWithType device_;
  // Needed to handle Acquire() and Release()
  Link* link_;
  os::Handler* l2cap_handler_;

  // User supported states
  os::Handler* user_handler_ = nullptr;
  FixedChannel::OnCloseCallback on_close_callback_{};

  // Internal states
  bool acquired_ = false;
  bool closed_ = false;
  hci::ErrorCode close_reason_ = hci::ErrorCode::SUCCESS;
  static constexpr size_t kChannelQueueSize = 10;
  common::BidiQueue<packet::PacketView<packet::kLittleEndian>, packet::BasePacketBuilder>
          channel_queue_{kChannelQueueSize};
};

}  // namespace internal
}  // namespace le
}  // namespace l2cap
}  // namespace bluetooth
