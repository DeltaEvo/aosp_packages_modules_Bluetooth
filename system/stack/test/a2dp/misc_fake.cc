/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "misc_fake"

#include <dlfcn.h>

#include "osi/include/log.h"
#include "service/common/bluetooth/a2dp_codec_config.h"
#include "stack/include/a2dp_vendor_ldac.h"

bluetooth::A2dpCodecConfig* bta_av_get_a2dp_current_codec(void) {
  return nullptr;
}

int A2DP_VendorGetTrackSampleRateLdac(const uint8_t* p_codec_info) { return 0; }
int A2DP_VendorGetTrackBitsPerSampleLdac(const uint8_t* p_codec_info) {
  return 0;
}
int A2DP_VendorGetChannelModeCodeLdac(const uint8_t* p_codec_info) { return 0; }

bool A2dpCodecConfig::copyOutOtaCodecConfig(unsigned char*) { return false; }
uint8_t A2dpCodecConfig::getAudioBitsPerSample() { return 0; }
void A2dpCodecConfig::debug_codec_dump(int) {}

int A2DP_VendorGetTrackSampleRateAptx(unsigned char const*) { return 0; }
int A2DP_VendorGetTrackChannelCountAptx(unsigned char const*) { return 0; }

int A2DP_VendorGetTrackSampleRateAptxHd(unsigned char const*) { return 0; }
int A2DP_VendorGetTrackChannelCountAptxHd(unsigned char const*) { return 0; }

void* A2DP_VendorCodecLoadExternalLib(const std::vector<std::string>& lib_paths,
                                      const std::string& friendly_name) {
  std::string lib_path_error_list = "";
  for (auto lib_path : lib_paths) {
    void* lib_handle = dlopen(lib_path.c_str(), RTLD_NOW);
    if (lib_handle != NULL) {
      LOG(INFO) << __func__ << "Library found: " << friendly_name << " with ["
                << lib_path << "]."
                << " (Tested libs: " << lib_path_error_list << ")";
      return lib_handle;
    }
    lib_path_error_list += "[ Err: ";
    lib_path_error_list += dlerror();
    lib_path_error_list += " ], ";
  }
  LOG(ERROR) << __func__ << "Failed to open library: " << friendly_name
             << ". (Tested libs: " << lib_path_error_list << ")";
  return nullptr;
}
