/*
 * Copyright (C) 2016 The Android Open Source Project
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

//
// Interface to the A2DP SBC Decoder
//

#ifndef A2DP_SBC_DECODER_H
#define A2DP_SBC_DECODER_H

#include "a2dp_codec_api.h"
#include "stack/include/bt_hdr.h"

// Initialize the A2DP SBC decoder.
bool a2dp_sbc_decoder_init(decoded_data_callback_t decode_callback);

// Cleanup the A2DP SBC decoder.
void a2dp_sbc_decoder_cleanup(void);

// Decodes |p_buf|. Calls |decode_callback| passed into |a2dp_sbc_decoder_init|
// if decoded frames are available.
bool a2dp_sbc_decoder_decode_packet(BT_HDR* p_buf);

#endif  // A2DP_SBC_DECODER_H
