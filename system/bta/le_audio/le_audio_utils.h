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

#include <hardware/audio.h>

#include <bitset>
#include <vector>

#include "le_audio_types.h"

namespace le_audio {
namespace utils {
types::LeAudioContextType AudioContentToLeAudioContext(
    audio_content_type_t content_type, audio_usage_t usage);
types::AudioContexts GetAllowedAudioContextsFromSourceMetadata(
    const std::vector<struct playback_track_metadata>& source_metadata,
    types::AudioContexts allowed_contexts);
std::vector<uint8_t> GetAllCcids(std::bitset<16> context_types);

}  // namespace utils
}  // namespace le_audio
