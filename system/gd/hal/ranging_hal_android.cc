/*
 * Copyright 2024 The Android Open Source Project
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

#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSounding.h>
#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSoundingSession.h>
#include <aidl/android/hardware/bluetooth/ranging/BnBluetoothChannelSoundingSessionCallback.h>
#include <aidl/android/hardware/bluetooth/ranging/IBluetoothChannelSounding.h>
#include <android/binder_manager.h>
#include <bluetooth/log.h>

#include <unordered_map>

// AIDL uses syslog.h, so these defines conflict with os/log.h
#undef LOG_DEBUG
#undef LOG_INFO
#undef LOG_WARNING

#include "ranging_hal.h"

using aidl::android::hardware::bluetooth::ranging::BluetoothChannelSoundingParameters;
using aidl::android::hardware::bluetooth::ranging::BnBluetoothChannelSoundingSessionCallback;
using aidl::android::hardware::bluetooth::ranging::ChannelSoudingRawData;
using aidl::android::hardware::bluetooth::ranging::ComplexNumber;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSounding;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSoundingSession;
using aidl::android::hardware::bluetooth::ranging::IBluetoothChannelSoundingSessionCallback;
using aidl::android::hardware::bluetooth::ranging::StepTonePct;
using aidl::android::hardware::bluetooth::ranging::VendorSpecificData;

namespace bluetooth {
namespace hal {

class BluetoothChannelSoundingSessionTracker : public BnBluetoothChannelSoundingSessionCallback {
public:
  BluetoothChannelSoundingSessionTracker(uint16_t connection_handle,
                                         RangingHalCallback* ranging_hal_callback,
                                         bool for_vendor_specific_reply)
      : connection_handle_(connection_handle),
        ranging_hal_callback_(ranging_hal_callback),
        for_vendor_specific_reply_(for_vendor_specific_reply) {}

  ::ndk::ScopedAStatus onOpened(::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("connection_handle 0x{:04x}, reason {}", connection_handle_, (uint16_t)in_reason);
    if (for_vendor_specific_reply_) {
      ranging_hal_callback_->OnHandleVendorSpecificReplyComplete(connection_handle_, true);
    }
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus onOpenFailed(
          ::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("connection_handle 0x{:04x}, reason {}", connection_handle_, (uint16_t)in_reason);
    bluetooth_channel_sounding_session_ = nullptr;
    if (for_vendor_specific_reply_) {
      ranging_hal_callback_->OnHandleVendorSpecificReplyComplete(connection_handle_, false);
    } else {
      ranging_hal_callback_->OnOpenFailed(connection_handle_);
    }
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus onResult(
          const ::aidl::android::hardware::bluetooth::ranging::RangingResult& in_result) {
    log::verbose("resultMeters {}", in_result.resultMeters);
    hal::RangingResult ranging_result;
    ranging_result.result_meters_ = in_result.resultMeters;
    ranging_hal_callback_->OnResult(connection_handle_, ranging_result);
    return ::ndk::ScopedAStatus::ok();
  }

  ::ndk::ScopedAStatus onClose(::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("reason {}", (uint16_t)in_reason);
    bluetooth_channel_sounding_session_ = nullptr;
    return ::ndk::ScopedAStatus::ok();
  }
  ::ndk::ScopedAStatus onCloseFailed(
          ::aidl::android::hardware::bluetooth::ranging::Reason in_reason) {
    log::info("reason {}", (uint16_t)in_reason);
    return ::ndk::ScopedAStatus::ok();
  }

  std::shared_ptr<IBluetoothChannelSoundingSession>& GetSession() {
    return bluetooth_channel_sounding_session_;
  }

private:
  std::shared_ptr<IBluetoothChannelSoundingSession> bluetooth_channel_sounding_session_ = nullptr;
  uint16_t connection_handle_;
  RangingHalCallback* ranging_hal_callback_;
  bool for_vendor_specific_reply_;
};

class RangingHalAndroid : public RangingHal {
public:
  bool IsBound() override { return bluetooth_channel_sounding_ != nullptr; }

  void RegisterCallback(RangingHalCallback* callback) { ranging_hal_callback_ = callback; }

  std::vector<VendorSpecificCharacteristic> GetVendorSpecificCharacteristics() override {
    std::vector<VendorSpecificCharacteristic> vendor_specific_characteristics = {};
    if (bluetooth_channel_sounding_ != nullptr) {
      std::optional<std::vector<std::optional<VendorSpecificData>>> vendorSpecificDataOptional;
      bluetooth_channel_sounding_->getVendorSpecificData(&vendorSpecificDataOptional);
      if (vendorSpecificDataOptional.has_value()) {
        for (auto vendor_specific_data : vendorSpecificDataOptional.value()) {
          VendorSpecificCharacteristic vendor_specific_characteristic;
          vendor_specific_characteristic.characteristicUuid_ =
                  vendor_specific_data->characteristicUuid;
          vendor_specific_characteristic.value_ = vendor_specific_data->opaqueValue;
          vendor_specific_characteristics.emplace_back(vendor_specific_characteristic);
        }
      }
      log::info("size {}", vendor_specific_characteristics.size());
    } else {
      log::warn("bluetooth_channel_sounding_ is nullptr");
    }

    return vendor_specific_characteristics;
  }

  void OpenSession(uint16_t connection_handle, uint16_t att_handle,
                   const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data) {
    log::info("connection_handle 0x{:04x}, att_handle 0x{:04x} size of vendor_specific_data {}",
              connection_handle, att_handle, vendor_specific_data.size());
    session_trackers_[connection_handle] =
            ndk::SharedRefBase::make<BluetoothChannelSoundingSessionTracker>(
                    connection_handle, ranging_hal_callback_, false);
    BluetoothChannelSoundingParameters parameters;
    parameters.aclHandle = connection_handle;
    parameters.role = aidl::android::hardware::bluetooth::ranging::Role::INITIATOR;
    parameters.realTimeProcedureDataAttHandle = att_handle;
    CopyVendorSpecificData(vendor_specific_data, parameters.vendorSpecificData);

    auto& tracker = session_trackers_[connection_handle];
    bluetooth_channel_sounding_->openSession(parameters, tracker, &tracker->GetSession());

    if (tracker->GetSession() != nullptr) {
      std::vector<VendorSpecificCharacteristic> vendor_specific_reply = {};
      std::optional<std::vector<std::optional<VendorSpecificData>>> vendorSpecificDataOptional;
      tracker->GetSession()->getVendorSpecificReplies(&vendorSpecificDataOptional);

      if (vendorSpecificDataOptional.has_value()) {
        for (auto& data : vendorSpecificDataOptional.value()) {
          VendorSpecificCharacteristic vendor_specific_characteristic;
          vendor_specific_characteristic.characteristicUuid_ = data->characteristicUuid;
          vendor_specific_characteristic.value_ = data->opaqueValue;
          vendor_specific_reply.emplace_back(vendor_specific_characteristic);
        }
      }
      ranging_hal_callback_->OnOpened(connection_handle, vendor_specific_reply);
    }
  }

  void HandleVendorSpecificReply(
          uint16_t connection_handle,
          const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply) {
    log::info("connection_handle 0x{:04x}", connection_handle);
    session_trackers_[connection_handle] =
            ndk::SharedRefBase::make<BluetoothChannelSoundingSessionTracker>(
                    connection_handle, ranging_hal_callback_, true);
    BluetoothChannelSoundingParameters parameters;
    parameters.aclHandle = connection_handle;
    parameters.role = aidl::android::hardware::bluetooth::ranging::Role::REFLECTOR;
    CopyVendorSpecificData(vendor_specific_reply, parameters.vendorSpecificData);
    auto& tracker = session_trackers_[connection_handle];
    bluetooth_channel_sounding_->openSession(parameters, tracker, &tracker->GetSession());
  }

  void WriteRawData(uint16_t connection_handle, const ChannelSoundingRawData& raw_data) {
    if (session_trackers_.find(connection_handle) == session_trackers_.end()) {
      log::error("Can't find session for connection_handle:0x{:04x}", connection_handle);
      return;
    } else if (session_trackers_[connection_handle]->GetSession() == nullptr) {
      log::error("Session not opened");
      return;
    }

    ChannelSoudingRawData hal_raw_data;
    hal_raw_data.numAntennaPaths = raw_data.num_antenna_paths_;
    hal_raw_data.stepChannels = raw_data.step_channel_;
    hal_raw_data.initiatorData.stepTonePcts.emplace(std::vector<std::optional<StepTonePct>>{});
    hal_raw_data.reflectorData.stepTonePcts.emplace(std::vector<std::optional<StepTonePct>>{});
    for (uint8_t i = 0; i < raw_data.tone_pct_initiator_.size(); i++) {
      StepTonePct step_tone_pct;
      for (uint8_t j = 0; j < raw_data.tone_pct_initiator_[i].size(); j++) {
        ComplexNumber complex_number;
        complex_number.imaginary = raw_data.tone_pct_initiator_[i][j].imag();
        complex_number.real = raw_data.tone_pct_initiator_[i][j].real();
        step_tone_pct.tonePcts.emplace_back(complex_number);
      }
      step_tone_pct.toneQualityIndicator = raw_data.tone_quality_indicator_initiator_[i];
      hal_raw_data.initiatorData.stepTonePcts.value().emplace_back(step_tone_pct);
    }
    for (uint8_t i = 0; i < raw_data.tone_pct_reflector_.size(); i++) {
      StepTonePct step_tone_pct;
      for (uint8_t j = 0; j < raw_data.tone_pct_reflector_[i].size(); j++) {
        ComplexNumber complex_number;
        complex_number.imaginary = raw_data.tone_pct_reflector_[i][j].imag();
        complex_number.real = raw_data.tone_pct_reflector_[i][j].real();
        step_tone_pct.tonePcts.emplace_back(complex_number);
      }
      step_tone_pct.toneQualityIndicator = raw_data.tone_quality_indicator_reflector_[i];
      hal_raw_data.reflectorData.stepTonePcts.value().emplace_back(step_tone_pct);
    }
    session_trackers_[connection_handle]->GetSession()->writeRawData(hal_raw_data);
  }

  void CopyVendorSpecificData(const std::vector<hal::VendorSpecificCharacteristic>& source,
                              std::optional<std::vector<std::optional<VendorSpecificData>>>& dist) {
    dist = std::make_optional<std::vector<std::optional<VendorSpecificData>>>();
    for (auto& data : source) {
      VendorSpecificData vendor_specific_data;
      vendor_specific_data.characteristicUuid = data.characteristicUuid_;
      vendor_specific_data.opaqueValue = data.value_;
      dist->push_back(vendor_specific_data);
    }
  }

protected:
  void ListDependencies(ModuleList* /*list*/) const {}

  void Start() override {
    std::string instance = std::string() + IBluetoothChannelSounding::descriptor + "/default";
    log::info("AServiceManager_isDeclared {}", AServiceManager_isDeclared(instance.c_str()));
    if (AServiceManager_isDeclared(instance.c_str())) {
      ::ndk::SpAIBinder binder(AServiceManager_waitForService(instance.c_str()));
      bluetooth_channel_sounding_ = IBluetoothChannelSounding::fromBinder(binder);
      log::info("Bind IBluetoothChannelSounding {}", IsBound() ? "Success" : "Fail");
    }
  }

  void Stop() override { bluetooth_channel_sounding_ = nullptr; }

  std::string ToString() const override { return std::string("RangingHalAndroid"); }

private:
  std::shared_ptr<IBluetoothChannelSounding> bluetooth_channel_sounding_;
  RangingHalCallback* ranging_hal_callback_;
  std::unordered_map<uint16_t, std::shared_ptr<BluetoothChannelSoundingSessionTracker>>
          session_trackers_;
};

const ModuleFactory RangingHal::Factory = ModuleFactory([]() { return new RangingHalAndroid(); });

}  // namespace hal
}  // namespace bluetooth
