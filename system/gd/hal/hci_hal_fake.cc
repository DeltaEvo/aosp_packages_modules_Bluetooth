/*
 * Copyright 2023 The Android Open Source Project
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

#include "hal/hci_hal_fake.h"

namespace bluetooth {
namespace hal {

void TestHciHal::sendHciCommand(hal::HciPacket command) {
  outgoing_commands_.push(std::move(command));
}

void TestHciHal::sendAclData(hal::HciPacket data) { outgoing_acl_.push(std::move(data)); }

void TestHciHal::sendScoData(hal::HciPacket data) { outgoing_sco_.push(std::move(data)); }

void TestHciHal::sendIsoData(hal::HciPacket data) { outgoing_iso_.push(std::move(data)); }

packet::PacketView<packet::kLittleEndian> TestHciHal::GetPacketView(hal::HciPacket data) {
  auto shared = std::make_shared<std::vector<uint8_t>>(data);
  return packet::PacketView<packet::kLittleEndian>(shared);
}

std::optional<hci::CommandView> TestHciHal::GetSentCommand(std::chrono::milliseconds timeout) {
  if (!outgoing_commands_.wait_to_take(timeout)) {
    // Timed out
    return {};
  }
  auto command = hci::CommandView::Create(GetPacketView(outgoing_commands_.take()));
  log::assert_that(command.IsValid(), "assert failed: command.IsValid()");
  return command;
}

std::optional<hci::AclView> TestHciHal::GetSentAcl(std::chrono::milliseconds timeout) {
  if (!outgoing_acl_.wait_to_take(timeout)) {
    // Timed out
    return {};
  }
  auto acl = hci::AclView::Create(GetPacketView(outgoing_acl_.take()));
  log::assert_that(acl.IsValid(), "assert failed: acl.IsValid()");
  return acl;
}

std::optional<hci::ScoView> TestHciHal::GetSentSco(std::chrono::milliseconds timeout) {
  if (!outgoing_commands_.wait_to_take(timeout)) {
    // Timed out
    return {};
  }
  auto sco = hci::ScoView::Create(GetPacketView(outgoing_sco_.take()));
  log::assert_that(sco.IsValid(), "assert failed: sco.IsValid()");
  return sco;
}

std::optional<hci::IsoView> TestHciHal::GetSentIso(std::chrono::milliseconds timeout) {
  if (!outgoing_commands_.wait_to_take(timeout)) {
    // Timed out
    return {};
  }
  log::assert_that(outgoing_iso_.wait_to_take(timeout),
                   "assert failed: outgoing_iso_.wait_to_take(timeout)");
  auto iso = hci::IsoView::Create(GetPacketView(outgoing_iso_.take()));
  log::assert_that(iso.IsValid(), "assert failed: iso.IsValid()");
  return iso;
}

void TestHciHal::InjectEvent(std::unique_ptr<packet::BasePacketBuilder> event) {
  log::assert_that(callbacks != nullptr, "assert failed: callbacks != nullptr");
  auto view = std::vector<uint8_t>();
  packet::BitInserter bi{view};
  event->Serialize(bi);
  callbacks->hciEventReceived(view);
}

const ModuleFactory TestHciHal::Factory = ModuleFactory([]() { return new TestHciHal(); });
}  // namespace hal
}  // namespace bluetooth
