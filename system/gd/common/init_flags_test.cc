/******************************************************************************
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
 ******************************************************************************/

#include "common/init_flags.h"

#include <gtest/gtest.h>

using bluetooth::common::InitFlags;

TEST(InitFlagsTest, test_leaudio_targeted_announcement_reconnection_mode) {
  const char* input[] = {"INIT_leaudio_targeted_announcement_reconnection_mode=true", nullptr};
  InitFlags::Load(input);
  ASSERT_TRUE(InitFlags::IsTargetedAnnouncementReconnectionMode());
}

TEST(InitFlagsTest, test_enable_use_rsi_from_cached_inqiry_results) {
  const char* input[] = {"INIT_use_rsi_from_cached_inqiry_results=true", nullptr};
  InitFlags::Load(input);
  ASSERT_TRUE(InitFlags::UseRsiFromCachedInquiryResults());
}
