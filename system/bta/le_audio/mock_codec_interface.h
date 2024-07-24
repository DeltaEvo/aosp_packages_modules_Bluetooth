/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
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

#include <gmock/gmock.h>

#include <vector>

#include "codec_interface.h"

class MockCodecInterface {
public:
  static void RegisterMockInstanceHook(std::function<void(MockCodecInterface*, bool)>);
  static void ClearMockInstanceHookList();

  MockCodecInterface() = default;
  MockCodecInterface(const MockCodecInterface&) = delete;
  MockCodecInterface& operator=(const MockCodecInterface&) = delete;

  virtual ~MockCodecInterface() = default;

  MOCK_METHOD((bluetooth::le_audio::CodecInterface::Status), InitEncoder,
              (const bluetooth::le_audio::LeAudioCodecConfiguration& pcm_config,
               const bluetooth::le_audio::LeAudioCodecConfiguration& codec_config));
  MOCK_METHOD(bluetooth::le_audio::CodecInterface::Status, InitDecoder,
              (const bluetooth::le_audio::LeAudioCodecConfiguration& codec_config,
               const bluetooth::le_audio::LeAudioCodecConfiguration& pcm_config));
  MOCK_METHOD(bluetooth::le_audio::CodecInterface::Status, Encode,
              (const uint8_t* data, int stride, uint16_t out_size, std::vector<int16_t>* out_buffer,
               uint16_t out_offset));
  MOCK_METHOD(bluetooth::le_audio::CodecInterface::Status, Decode, (uint8_t* data, uint16_t size));
  MOCK_METHOD((void), Cleanup, ());
  MOCK_METHOD((bool), IsReady, ());
  MOCK_METHOD((uint16_t), GetNumOfSamplesPerChannel, ());
  MOCK_METHOD((uint8_t), GetNumOfBytesPerSample, ());
  MOCK_METHOD((std::vector<int16_t>&), GetDecodedSamples, ());
};
