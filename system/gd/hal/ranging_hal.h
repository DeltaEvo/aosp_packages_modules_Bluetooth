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

#pragma once

#include <complex>

#include "module.h"

namespace bluetooth {
namespace hal {

struct VendorSpecificCharacteristic {
  std::array<uint8_t, 16> characteristicUuid_;
  std::vector<uint8_t> value_;
};

struct ChannelSoundingRawData {
  uint8_t num_antenna_paths_;
  std::vector<uint8_t> step_channel_;
  std::vector<std::vector<std::complex<double>>> tone_pct_initiator_;
  std::vector<std::vector<std::complex<double>>> tone_pct_reflector_;
  std::vector<std::vector<uint8_t>> tone_quality_indicator_initiator_;
  std::vector<std::vector<uint8_t>> tone_quality_indicator_reflector_;
};

struct RangingResult {
  double result_meters_;
};

class RangingHalCallback {
 public:
  virtual ~RangingHalCallback() = default;
  virtual void OnOpened(
      uint16_t connection_handle,
      const std::vector<VendorSpecificCharacteristic>& vendor_specific_reply) = 0;
  virtual void OnOpenFailed(uint16_t connection_handle) = 0;
  virtual void OnHandleVendorSpecificReplyComplete(uint16_t connection_handle, bool success) = 0;
  virtual void OnResult(uint16_t connection_handle, const RangingResult& ranging_result) = 0;
};

class RangingHal : public ::bluetooth::Module {
 public:
  static const ModuleFactory Factory;

  virtual ~RangingHal() = default;
  virtual bool IsBound() = 0;
  virtual void RegisterCallback(RangingHalCallback* callback) = 0;
  virtual std::vector<VendorSpecificCharacteristic> GetVendorSpecificCharacteristics() = 0;
  virtual void OpenSession(
      uint16_t connection_handle,
      uint16_t att_handle,
      const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_data) = 0;
  virtual void HandleVendorSpecificReply(
      uint16_t connection_handle,
      const std::vector<hal::VendorSpecificCharacteristic>& vendor_specific_reply) = 0;
  virtual void WriteRawData(uint16_t connection_handle, const ChannelSoundingRawData& raw_data) = 0;
};

}  // namespace hal
}  // namespace bluetooth
