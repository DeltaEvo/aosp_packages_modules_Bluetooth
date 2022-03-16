/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <gmock/gmock.h>

#include "client_audio.h"

class MockLeAudioClientAudioSource {
 public:
  static void SetMockInstanceForTesting(MockLeAudioClientAudioSource* mock);
  MOCK_METHOD((bool), Start,
              (const LeAudioCodecConfiguration& codecConfiguration,
               LeAudioClientAudioSinkReceiver* audioReceiver));
  MOCK_METHOD((void), Stop, ());
  MOCK_METHOD((const void*), Acquire, ());
  MOCK_METHOD((void), Release, (const void*));
  MOCK_METHOD((void), ConfirmStreamingRequest, ());
  MOCK_METHOD((void), CancelStreamingRequest, ());
  MOCK_METHOD((void), UpdateRemoteDelay, (uint16_t delay));
  MOCK_METHOD((void), DebugDump, (int fd));
  MOCK_METHOD((void), UpdateAudioConfigToHal,
              (const ::le_audio::offload_config&));
  MOCK_METHOD((void), SuspendedForReconfiguration, ());
};

class MockLeAudioClientAudioSink {
 public:
  static void SetMockInstanceForTesting(MockLeAudioClientAudioSink* mock);
  MOCK_METHOD((bool), Start,
              (const LeAudioCodecConfiguration& codecConfiguration,
               LeAudioClientAudioSourceReceiver* audioReceiver));
  MOCK_METHOD((void), Stop, ());
  MOCK_METHOD((const void*), Acquire, ());
  MOCK_METHOD((void), Release, (const void*));
  MOCK_METHOD((size_t), SendData, (uint8_t * data, uint16_t size));
  MOCK_METHOD((void), ConfirmStreamingRequest, ());
  MOCK_METHOD((void), CancelStreamingRequest, ());
  MOCK_METHOD((void), UpdateRemoteDelay, (uint16_t delay));
  MOCK_METHOD((void), DebugDump, (int fd));
  MOCK_METHOD((void), UpdateAudioConfigToHal,
              (const ::le_audio::offload_config&));
  MOCK_METHOD((void), SuspendedForReconfiguration, ());
};
