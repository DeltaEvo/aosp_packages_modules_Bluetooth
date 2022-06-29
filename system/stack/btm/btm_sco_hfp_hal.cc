/*
 * Copyright 2022 The Android Open Source Project
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

#include "btm_sco_hfp_hal.h"

#include <vector>

#include "device/include/esco_parameters.h"

namespace hfp_hal_interface {
namespace {
bool offload_supported = true;
bool offload_enabled = true;
std::vector<bt_codec> cached_codecs;
}  // namespace

// Android implementation only has consts. Initialize CVSD and MSBC to PCM
// offloaded defaults.
void init() {
  bt_codec cvsd = {
      .codec = codec::CVSD,
      .data_path = ESCO_DATA_PATH_PCM,
  };

  bt_codec msbc = {
      .codec = codec::MSBC,
      .data_path = ESCO_DATA_PATH_PCM,
  };

  cached_codecs.clear();
  cached_codecs.emplace_back(cvsd);
  cached_codecs.emplace_back(msbc);
}

// Android statically compiles WBS support.
bool get_wbs_supported() { return !DISABLE_WBS; }

// Checks the supported codecs
bt_codecs get_codec_capabilities(uint64_t codecs) {
  bt_codecs codec_list = {.offload_capable = offload_supported};

  for (auto c : cached_codecs) {
    if (c.codec & codecs) {
      codec_list.codecs.push_back(c);
    }
  }

  return codec_list;
}

// Check if hardware offload is supported
bool get_offload_supported() { return offload_supported; }

// Check if hardware offload is enabled
bool get_offload_enabled() { return offload_supported && offload_enabled; }

// Set offload enable/disable
bool enable_offload(bool enable) {
  if (!offload_supported) {
    return false;
  }
  offload_enabled = enable;
  return true;
}

// Notify the codec datapath to lower layer for offload mode.
bool set_codec_datapath(int codec) { return true; }

// No packet size limits on Android since it will be offloaded.
int get_packet_size(int codec) { return kDefaultPacketSize; }

void notify_sco_connection_change(RawAddress device, bool is_connected,
                                  int codec) {
  // Do nothing since this is handled by Android's audio hidl.
}

}  // namespace hfp_hal_interface
