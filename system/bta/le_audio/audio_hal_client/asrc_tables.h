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

#pragma once

#include <cstdint>

namespace le_audio::asrc {

extern const struct ResamplerTables {
  static const int KERNEL_Q = 512;
  static const int KERNEL_A = 16;

  const int32_t h alignas(2 * KERNEL_A *
                          sizeof(int32_t))[KERNEL_Q][2 * KERNEL_A];
  const int16_t d alignas(2 * KERNEL_A *
                          sizeof(int16_t))[KERNEL_Q][2 * KERNEL_A];

} resampler_tables;

}  // namespace le_audio::asrc
