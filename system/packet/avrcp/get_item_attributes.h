/*
 * Copyright 2018 The Android Open Source Project
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

#include <set>
#include <vector>

#include "avrcp_browse_packet.h"

namespace bluetooth {
namespace avrcp {

template <class Builder>
class AttributesResponseBuilderTestUser;

class GetItemAttributesResponseBuilder : public BrowsePacketBuilder {
public:
  virtual ~GetItemAttributesResponseBuilder() = default;
  using Builder = std::unique_ptr<GetItemAttributesResponseBuilder>;
  static Builder MakeBuilder(Status status, size_t mtu);

  size_t AddAttributeEntry(AttributeEntry entry);
  size_t AddAttributeEntry(Attribute, const std::string&);

  virtual void clear() { entries_.clear(); }

  static constexpr size_t kHeaderSize() {
    size_t len = BrowsePacket::kMinSize();
    len += 1;  // Status
    len += 1;  // Number of attributes
    return len;
  }

  static constexpr size_t kErrorHeaderSize() {
    size_t len = BrowsePacket::kMinSize();
    len += 1;  // Status
    return len;
  }

  virtual size_t size() const override;
  virtual bool Serialize(const std::shared_ptr<::bluetooth::Packet>& pkt) override;

private:
  Status status_;
  size_t mtu_;
  std::set<AttributeEntry> entries_;
  friend class AttributesResponseBuilderTestUser<GetItemAttributesResponseBuilder>;

  GetItemAttributesResponseBuilder(Status status, size_t mtu)
      : BrowsePacketBuilder(BrowsePdu::GET_ITEM_ATTRIBUTES), status_(status), mtu_(mtu) {}
};

class GetItemAttributesRequest : public BrowsePacket {
public:
  virtual ~GetItemAttributesRequest() = default;

  /**
   * Avrcp Change Path Packet Layout
   *   BrowsePacket:
   *     uint8_t pdu_;
   *     uint16_t length_;
   *   GetItemAttributesRequest:
   *     uint8_t scope_;
   *     uint64_t uid_;
   *     uint16_t uid_counter_;
   *     uint8_t attr_count_;
   *     uint32_t[] attr_requested_;
   */
  static constexpr size_t kMinSize() { return BrowsePacket::kMinSize() + 12; }

  Scope GetScope() const;
  uint64_t GetUid() const;
  uint16_t GetUidCounter() const;
  uint8_t GetNumAttributes() const;  // If this value is zero, then all attributes are requested
  std::vector<Attribute> GetAttributesRequested() const;

  virtual bool IsValid() const override;
  virtual std::string ToString() const override;

protected:
  using BrowsePacket::BrowsePacket;
};

}  // namespace avrcp
}  // namespace bluetooth
