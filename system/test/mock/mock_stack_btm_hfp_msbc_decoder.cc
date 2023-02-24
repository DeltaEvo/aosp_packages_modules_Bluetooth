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
/*
 * Generated mock file from original source file
 *   Functions generated:3
 *
 *  mockcify.pl ver 0.5.0
 */

#include <cstdint>
#include <functional>
#include <map>
#include <string>

// Mock include file to share data between tests and mock
#include "test/mock/mock_stack_btm_hfp_msbc_decoder.h"

// Original usings

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_btm_hfp_msbc_decoder {

// Function state capture and return values, if needed
struct hfp_msbc_decoder_cleanup hfp_msbc_decoder_cleanup;
struct hfp_msbc_decoder_decode_packet hfp_msbc_decoder_decode_packet;
struct hfp_msbc_decoder_init hfp_msbc_decoder_init;

}  // namespace stack_btm_hfp_msbc_decoder
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_btm_hfp_msbc_decoder {

bool hfp_msbc_decoder_decode_packet::return_value = false;
bool hfp_msbc_decoder_init::return_value = false;

}  // namespace stack_btm_hfp_msbc_decoder
}  // namespace mock
}  // namespace test

// Mocked functions, if any
void hfp_msbc_decoder_cleanup(void) {
  inc_func_call_count(__func__);
  test::mock::stack_btm_hfp_msbc_decoder::hfp_msbc_decoder_cleanup();
}
bool hfp_msbc_decoder_decode_packet(const uint8_t* i_buf, int16_t* o_buf,
                                    size_t out_len) {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_hfp_msbc_decoder::hfp_msbc_decoder_decode_packet(
      i_buf, o_buf, out_len);
}
bool hfp_msbc_decoder_init() {
  inc_func_call_count(__func__);
  return test::mock::stack_btm_hfp_msbc_decoder::hfp_msbc_decoder_init();
}
// Mocked functions complete
// END mockcify generation
