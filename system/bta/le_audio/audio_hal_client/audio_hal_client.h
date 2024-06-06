/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
 * Copyright (c) 2022 The Android Open Source Project
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

#include <memory>
#include <optional>
#include <vector>

#include "audio_hal_interface/le_audio_software.h"
#include "le_audio/codec_manager.h"
#include "le_audio/le_audio_types.h"

namespace bluetooth::le_audio {
/* Represents configuration used to configure the local audio sessions and
 * the software codecs in case of a software coding sessions.
 */
struct LeAudioCodecConfiguration {
  static constexpr uint8_t kChannelNumberMono =
      bluetooth::audio::le_audio::kChannelNumberMono;
  static constexpr uint8_t kChannelNumberStereo =
      bluetooth::audio::le_audio::kChannelNumberStereo;

  static constexpr uint32_t kSampleRate48000 =
      bluetooth::audio::le_audio::kSampleRate48000;
  static constexpr uint32_t kSampleRate44100 =
      bluetooth::audio::le_audio::kSampleRate44100;
  static constexpr uint32_t kSampleRate32000 =
      bluetooth::audio::le_audio::kSampleRate32000;
  static constexpr uint32_t kSampleRate24000 =
      bluetooth::audio::le_audio::kSampleRate24000;
  static constexpr uint32_t kSampleRate16000 =
      bluetooth::audio::le_audio::kSampleRate16000;
  static constexpr uint32_t kSampleRate8000 =
      bluetooth::audio::le_audio::kSampleRate8000;

  static constexpr uint8_t kBitsPerSample16 =
      bluetooth::audio::le_audio::kBitsPerSample16;
  static constexpr uint8_t kBitsPerSample24 =
      bluetooth::audio::le_audio::kBitsPerSample24;
  static constexpr uint8_t kBitsPerSample32 =
      bluetooth::audio::le_audio::kBitsPerSample32;

  static constexpr uint32_t kInterval7500Us = 7500;
  static constexpr uint32_t kInterval10000Us = 10000;

  /** number of channels */
  uint8_t num_channels = 0;

  /** sampling rate that the codec expects to receive from audio framework */
  uint32_t sample_rate = 0;

  /** bits per sample that codec expects to receive from audio framework */
  uint8_t bits_per_sample = 0;

  /** Data interval determines how often we send samples to the remote. This
   * should match how often we grab data from audio source, optionally we can
   * grab data every 2 or 3 intervals, but this would increase latency.
   *
   * Value is provided in us.
   */
  uint32_t data_interval_us = 0;

  bool operator!=(const LeAudioCodecConfiguration& other) {
    return !((num_channels == other.num_channels) &&
             (sample_rate == other.sample_rate) &&
             (bits_per_sample == other.bits_per_sample) &&
             (data_interval_us == other.data_interval_us));
  }

  bool operator==(const LeAudioCodecConfiguration& other) const {
    return ((num_channels == other.num_channels) &&
            (sample_rate == other.sample_rate) &&
            (bits_per_sample == other.bits_per_sample) &&
            (data_interval_us == other.data_interval_us));
  }

  bool IsInvalid() const {
    return (num_channels == 0) || (sample_rate == 0) ||
           (bits_per_sample == 0) || (data_interval_us == 0);
  }
};

class LeAudioCommonAudioHalClient {
 public:
  virtual ~LeAudioCommonAudioHalClient() = default;
  virtual std::optional<broadcaster::BroadcastConfiguration> GetBroadcastConfig(
      const std::vector<std::pair<types::LeAudioContextType, uint8_t>>&
          subgroup_quality,
      const std::optional<
          std::vector<::bluetooth::le_audio::types::acs_ac_record>>& pacs)
      const = 0;
  virtual std::optional<
      ::bluetooth::le_audio::set_configurations::AudioSetConfiguration>
  GetUnicastConfig(const CodecManager::UnicastConfigurationRequirements&
                       requirements) const = 0;
};

/* Used by the local BLE Audio Sink device to pass the audio data
 * received from a remote BLE Audio Source to the Audio HAL.
 */
class LeAudioSinkAudioHalClient {
 public:
  class Callbacks {
   public:
    Callbacks() = default;
    virtual ~Callbacks() = default;
    virtual void OnAudioSuspend(void) = 0;
    virtual void OnAudioResume(void) = 0;
    virtual void OnAudioMetadataUpdate(sink_metadata_v7 sink_metadata) = 0;

    base::WeakPtrFactory<Callbacks> weak_factory_{this};
  };

  virtual ~LeAudioSinkAudioHalClient() = default;
  virtual bool Start(const LeAudioCodecConfiguration& codecConfiguration,
                     Callbacks* audioReceiver,
                     DsaModes dsa_modes = {DsaMode::DISABLED}) = 0;
  virtual void Stop() = 0;
  virtual size_t SendData(uint8_t* data, uint16_t size) = 0;

  virtual void ConfirmStreamingRequest() = 0;
  virtual void CancelStreamingRequest() = 0;

  virtual void UpdateRemoteDelay(uint16_t remote_delay_ms) = 0;
  virtual void UpdateAudioConfigToHal(
      const ::bluetooth::le_audio::offload_config& config) = 0;
  virtual void SuspendedForReconfiguration() = 0;
  virtual void ReconfigurationComplete() = 0;

  static std::unique_ptr<LeAudioSinkAudioHalClient> AcquireUnicast();
  static void DebugDump(int fd);

 protected:
  LeAudioSinkAudioHalClient() = default;
};

/* Used by the local BLE Audio Source device to get data from the
 * Audio HAL, so we could send it over to a remote BLE Audio Sink device.
 */
class LeAudioSourceAudioHalClient : public LeAudioCommonAudioHalClient {
 public:
  class Callbacks {
   public:
    Callbacks() = default;
    virtual ~Callbacks() = default;
    virtual void OnAudioDataReady(const std::vector<uint8_t>& data) = 0;
    virtual void OnAudioSuspend(void) = 0;
    virtual void OnAudioResume(void) = 0;
    virtual void OnAudioMetadataUpdate(source_metadata_v7 source_metadata,
                                       DsaMode dsa_mode) = 0;

    base::WeakPtrFactory<Callbacks> weak_factory_{this};
  };

  virtual ~LeAudioSourceAudioHalClient() = default;
  virtual bool Start(const LeAudioCodecConfiguration& codecConfiguration,
                     Callbacks* audioReceiver,
                     DsaModes dsa_modes = {DsaMode::DISABLED}) = 0;
  virtual void Stop() = 0;
  virtual size_t SendData(uint8_t* data, uint16_t size) { return 0; }
  virtual void ConfirmStreamingRequest() = 0;
  virtual void CancelStreamingRequest() = 0;
  virtual void UpdateRemoteDelay(uint16_t remote_delay_ms) = 0;
  virtual void UpdateAudioConfigToHal(
      const ::bluetooth::le_audio::offload_config& config) = 0;
  virtual void UpdateBroadcastAudioConfigToHal(
      const ::bluetooth::le_audio::broadcast_offload_config& config) = 0;
  virtual void SuspendedForReconfiguration() = 0;
  virtual void ReconfigurationComplete() = 0;

  static std::unique_ptr<LeAudioSourceAudioHalClient> AcquireUnicast();
  static std::unique_ptr<LeAudioSourceAudioHalClient> AcquireBroadcast();
  static void DebugDump(int fd);

 protected:
  LeAudioSourceAudioHalClient() = default;
};
}  // namespace bluetooth::le_audio
