/******************************************************************************
 *
 * Copyright 2019 HIMSA II K/S - www.himsa.com.Represented by EHIMA -
 * www.ehima.com
 * Copyright (c) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

#include <bluetooth/log.h>

#include "audio_hal_client.h"
#include "audio_hal_interface/le_audio_software.h"
#include "bta/le_audio/codec_manager.h"
#include "common/time_util.h"
#include "os/log.h"
#include "osi/include/wakelock.h"
#include "stack/include/main_thread.h"

namespace bluetooth::le_audio {
namespace {
// TODO: HAL state should be in the HAL implementation
enum {
  HAL_UNINITIALIZED,
  HAL_STOPPED,
  HAL_STARTED,
} le_audio_source_hal_state;

class SinkImpl : public LeAudioSinkAudioHalClient {
 public:
  // Interface implementation
  bool Start(const LeAudioCodecConfiguration& codecConfiguration,
             LeAudioSinkAudioHalClient::Callbacks* audioReceiver,
             DsaModes dsa_modes) override;
  void Stop();
  size_t SendData(uint8_t* data, uint16_t size) override;
  void ConfirmStreamingRequest() override;
  void CancelStreamingRequest() override;
  void UpdateRemoteDelay(uint16_t remote_delay_ms) override;
  void UpdateAudioConfigToHal(
      const ::bluetooth::le_audio::offload_config& config) override;
  void SuspendedForReconfiguration() override;
  void ReconfigurationComplete() override;

  // Internal functionality
  SinkImpl() = default;
  ~SinkImpl() override {
    if (le_audio_source_hal_state != HAL_UNINITIALIZED) Release();
  }

  bool OnResumeReq(bool start_media_task);
  bool OnSuspendReq();
  bool OnMetadataUpdateReq(const sink_metadata_v7_t& sink_metadata);
  bool Acquire();
  void Release();

  bluetooth::audio::le_audio::LeAudioClientInterface::Source*
      halSourceInterface_ = nullptr;
  LeAudioSinkAudioHalClient::Callbacks* audioSinkCallbacks_ = nullptr;
};

bool SinkImpl::Acquire() {
  auto source_stream_cb = bluetooth::audio::le_audio::StreamCallbacks{
      .on_resume_ =
          std::bind(&SinkImpl::OnResumeReq, this, std::placeholders::_1),
      .on_suspend_ = std::bind(&SinkImpl::OnSuspendReq, this),
      .on_sink_metadata_update_ = std::bind(&SinkImpl::OnMetadataUpdateReq,
                                            this, std::placeholders::_1),
  };

  auto halInterface = audio::le_audio::LeAudioClientInterface::Get();
  if (halInterface == nullptr) {
    log::error("Can't get LE Audio HAL interface");
    return false;
  }

  halSourceInterface_ =
      halInterface->GetSource(source_stream_cb, get_main_thread());

  if (halSourceInterface_ == nullptr) {
    log::error("Can't get Audio HAL Audio source interface");
    return false;
  }

  log::info("");
  le_audio_source_hal_state = HAL_STOPPED;
  return true;
}

void SinkImpl::Release() {
  if (le_audio_source_hal_state == HAL_UNINITIALIZED) {
    log::warn("Audio HAL Audio source is not running");
    return;
  }

  log::info("");
  if (halSourceInterface_) {
    if (le_audio_source_hal_state == HAL_STARTED) {
      halSourceInterface_->StopSession();
      le_audio_source_hal_state = HAL_STOPPED;
    }

    halSourceInterface_->Cleanup();

    auto halInterface = audio::le_audio::LeAudioClientInterface::Get();
    if (halInterface != nullptr) {
      halInterface->ReleaseSource(halSourceInterface_);
    } else {
      log::error("Can't get LE Audio HAL interface");
    }

    le_audio_source_hal_state = HAL_UNINITIALIZED;
    halSourceInterface_ = nullptr;
  }
}

bool SinkImpl::OnResumeReq(bool start_media_task) {
  if (audioSinkCallbacks_ == nullptr) {
    log::error("audioSinkCallbacks_ not set");
    return false;
  }

  bt_status_t status = do_in_main_thread(
      FROM_HERE,
      base::BindOnce(&LeAudioSinkAudioHalClient::Callbacks::OnAudioResume,
                     audioSinkCallbacks_->weak_factory_.GetWeakPtr()));
  if (status == BT_STATUS_SUCCESS) {
    return true;
  }

  log::error("do_in_main_thread err={}", status);
  return false;
}

bool SinkImpl::OnSuspendReq() {
  if (audioSinkCallbacks_ == nullptr) {
    log::error("audioSinkCallbacks_ not set");
    return false;
  }

  bt_status_t status = do_in_main_thread(
      FROM_HERE,
      base::BindOnce(&LeAudioSinkAudioHalClient::Callbacks::OnAudioSuspend,
                     audioSinkCallbacks_->weak_factory_.GetWeakPtr()));
  if (status == BT_STATUS_SUCCESS) {
    return true;
  }

  log::error("do_in_main_thread err={}", status);
  return false;
}

bool SinkImpl::OnMetadataUpdateReq(const sink_metadata_v7_t& sink_metadata) {
  if (audioSinkCallbacks_ == nullptr) {
    log::error("audioSinkCallbacks_ not set");
    return false;
  }

  bt_status_t status = do_in_main_thread(
      FROM_HERE,
      base::BindOnce(
          &LeAudioSinkAudioHalClient::Callbacks::OnAudioMetadataUpdate,
          audioSinkCallbacks_->weak_factory_.GetWeakPtr(), sink_metadata));
  if (status == BT_STATUS_SUCCESS) {
    return true;
  }

  log::error("do_in_main_thread err={}", status);
  return false;
}

bool SinkImpl::Start(const LeAudioCodecConfiguration& codec_configuration,
                     LeAudioSinkAudioHalClient::Callbacks* audioReceiver,
                     DsaModes dsa_modes) {
  if (!halSourceInterface_) {
    log::error("Audio HAL Audio source interface not acquired");
    return false;
  }

  if (le_audio_source_hal_state == HAL_STARTED) {
    log::error("Audio HAL Audio source is already in use");
    return false;
  }

  log::info(
      "bit rate: {}, num channels: {}, sample rate: {}, data interval: {}",
      codec_configuration.bits_per_sample, codec_configuration.num_channels,
      codec_configuration.sample_rate, codec_configuration.data_interval_us);

  audio::le_audio::LeAudioClientInterface::PcmParameters pcmParameters = {
      .data_interval_us = codec_configuration.data_interval_us,
      .sample_rate = codec_configuration.sample_rate,
      .bits_per_sample = codec_configuration.bits_per_sample,
      .channels_count = codec_configuration.num_channels};

  halSourceInterface_->SetPcmParameters(pcmParameters);
  audio::le_audio::LeAudioClientInterface::Get()->SetAllowedDsaModes(dsa_modes);
  halSourceInterface_->StartSession();

  audioSinkCallbacks_ = audioReceiver;
  le_audio_source_hal_state = HAL_STARTED;
  return true;
}

void SinkImpl::Stop() {
  if (!halSourceInterface_) {
    log::error("Audio HAL Audio source interface already stopped");
    return;
  }

  if (le_audio_source_hal_state != HAL_STARTED) {
    log::error("Audio HAL Audio source was not started!");
    return;
  }

  log::info("");

  halSourceInterface_->StopSession();
  le_audio_source_hal_state = HAL_STOPPED;
  audioSinkCallbacks_ = nullptr;
}

size_t SinkImpl::SendData(uint8_t* data, uint16_t size) {
  size_t bytes_written;
  if (!halSourceInterface_) {
    log::error("Audio HAL Audio source interface not initialized");
    return 0;
  }

  if (le_audio_source_hal_state != HAL_STARTED) {
    log::error("Audio HAL Audio source was not started!");
    return 0;
  }

  /* TODO: What to do if not all data is written ? */
  bytes_written = halSourceInterface_->Write(data, size);
  if (bytes_written != size) {
    log::error(
        "Not all data is written to source HAL. Bytes written: {}, total: {}",
        bytes_written, size);
  }

  return bytes_written;
}

void SinkImpl::ConfirmStreamingRequest() {
  if ((halSourceInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    log::error("Audio HAL Audio source was not started!");
    return;
  }

  log::info("");
  if (com::android::bluetooth::flags::leaudio_start_stream_race_fix()) {
    halSourceInterface_->ConfirmStreamingRequestV2();
  } else {
    halSourceInterface_->ConfirmStreamingRequest();
  }
}

void SinkImpl::SuspendedForReconfiguration() {
  if ((halSourceInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    log::error("Audio HAL Audio source was not started!");
    return;
  }

  log::info("");
  halSourceInterface_->SuspendedForReconfiguration();
}

void SinkImpl::ReconfigurationComplete() {
  if ((halSourceInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    log::error("Audio HAL Audio source was not started!");
    return;
  }

  log::info("");
  halSourceInterface_->ReconfigurationComplete();
}

void SinkImpl::CancelStreamingRequest() {
  if ((halSourceInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    log::error("Audio HAL Audio source was not started!");
    return;
  }

  log::info("");
  if (com::android::bluetooth::flags::leaudio_start_stream_race_fix()) {
    halSourceInterface_->CancelStreamingRequestV2();
  } else {
    halSourceInterface_->CancelStreamingRequest();
  }
}

void SinkImpl::UpdateRemoteDelay(uint16_t remote_delay_ms) {
  if ((halSourceInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    log::error("Audio HAL Audio source was not started!");
    return;
  }

  log::info("");
  halSourceInterface_->SetRemoteDelay(remote_delay_ms);
}

void SinkImpl::UpdateAudioConfigToHal(
    const ::bluetooth::le_audio::offload_config& config) {
  if ((halSourceInterface_ == nullptr) ||
      (le_audio_source_hal_state != HAL_STARTED)) {
    log::error("Audio HAL Audio source was not started!");
    return;
  }

  log::info("");
  halSourceInterface_->UpdateAudioConfigToHal(config);
}
}  // namespace

std::unique_ptr<LeAudioSinkAudioHalClient>
LeAudioSinkAudioHalClient::AcquireUnicast() {
  std::unique_ptr<SinkImpl> impl(new SinkImpl());
  if (!impl->Acquire()) {
    log::error("Could not acquire Unicast Sink on LE Audio HAL enpoint");
    impl.reset();
    return nullptr;
  }

  log::info("");
  return std::move(impl);
}

void LeAudioSinkAudioHalClient::DebugDump(int fd) {
  /* TODO: Add some statistic for LeAudioSink Audio HAL interface */
}
}  // namespace bluetooth::le_audio
