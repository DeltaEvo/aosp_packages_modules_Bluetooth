/*
 *
 *  Copyright 2019 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
#pragma once

#include <neighbor/name_db.h>

#include <utility>
#include <vector>

#include "hci/address_with_type.h"
#include "hci/hci_packets.h"
#include "neighbor/name_db.h"
#include "security/channel/security_manager_channel.h"
#include "security/pairing/oob_data.h"
#include "security/record/security_record.h"
#include "security/ui.h"

namespace bluetooth {
namespace security {
namespace pairing {

/**
 * Base structure for handling pairing events.
 *
 * <p>Extend this class in order to implement a new style of pairing.
 */
class PairingHandler : public UICallbacks {
public:
  PairingHandler(channel::SecurityManagerChannel* security_manager_channel,
                 std::shared_ptr<record::SecurityRecord> record,
                 neighbor::NameDbModule* name_db_module)
      : security_manager_channel_(security_manager_channel),
        record_(std::move(record)),
        name_db_module_(name_db_module) {}
  ~PairingHandler() = default;

  // Classic
  virtual void Initiate(bool locally_initiated, hci::IoCapability io_capability,
                        hci::AuthenticationRequirements auth_requirements,
                        OobData local_p192_oob_data, OobData local_p256_oob_data) = 0;
  virtual void Cancel() = 0;
  virtual void OnReceive(hci::ChangeConnectionLinkKeyCompleteView packet) = 0;
  virtual void OnReceive(hci::CentralLinkKeyCompleteView packet) = 0;
  virtual void OnReceive(hci::PinCodeRequestView packet) = 0;
  virtual void OnReceive(hci::LinkKeyRequestView packet) = 0;
  virtual void OnReceive(hci::LinkKeyNotificationView packet) = 0;
  virtual void OnReceive(hci::IoCapabilityRequestView packet) = 0;
  virtual void OnReceive(hci::IoCapabilityResponseView packet) = 0;
  virtual void OnReceive(hci::SimplePairingCompleteView packet) = 0;
  virtual void OnReceive(hci::ReturnLinkKeysView packet) = 0;
  virtual void OnReceive(hci::EncryptionChangeView packet) = 0;
  virtual void OnReceive(hci::EncryptionKeyRefreshCompleteView packet) = 0;
  virtual void OnReceive(hci::RemoteOobDataRequestView packet) = 0;
  virtual void OnReceive(hci::UserPasskeyNotificationView packet) = 0;
  virtual void OnReceive(hci::KeypressNotificationView packet) = 0;
  virtual void OnReceive(hci::UserConfirmationRequestView packet) = 0;
  virtual void OnReceive(hci::UserPasskeyRequestView packet) = 0;

  virtual void OnPairingPromptAccepted(const bluetooth::hci::AddressWithType& address,
                                       bool confirmed) = 0;
  virtual void OnConfirmYesNo(const bluetooth::hci::AddressWithType& address, bool confirmed) = 0;
  virtual void OnPasskeyEntry(const bluetooth::hci::AddressWithType& address, uint32_t passkey) = 0;
  virtual void OnPinEntry(const bluetooth::hci::AddressWithType& address,
                          std::vector<uint8_t> pin) override = 0;

protected:
  std::shared_ptr<record::SecurityRecord> GetRecord() { return record_; }
  channel::SecurityManagerChannel* GetChannel() { return security_manager_channel_; }
  neighbor::NameDbModule* GetNameDbModule() { return name_db_module_; }

private:
  channel::SecurityManagerChannel* security_manager_channel_ __attribute__((unused));
  std::shared_ptr<record::SecurityRecord> record_ __attribute__((unused));
  neighbor::NameDbModule* name_db_module_;
};

}  // namespace pairing
}  // namespace security
}  // namespace bluetooth
