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
 *   Functions generated:10
 *
 *  mockcify.pl ver 0.5.1
 */

#ifndef __clang_analyzer__
#include <cstdint>
#include <functional>
#include <map>
#include <string>

extern std::map<std::string, int> mock_function_count_map;

// Mock include file to share data between tests and mock
#include "test/mock/mock_stack_btm_sco_hfp_hal.h"

// Original usings

// Mocked internal structures, if any

namespace test {
namespace mock {
namespace stack_btm_sco_hfp_hal {

// Function state capture and return values, if needed
struct enable_offload enable_offload;
struct get_codec_capabilities get_codec_capabilities;
struct get_offload_enabled get_offload_enabled;
struct get_offload_supported get_offload_supported;
struct get_packet_size get_packet_size;
struct get_wbs_supported get_wbs_supported;
struct init init;
struct notify_sco_connection_change notify_sco_connection_change;
struct set_codec_datapath set_codec_datapath;
struct update_esco_parameters update_esco_parameters;

}  // namespace stack_btm_sco_hfp_hal
}  // namespace mock
}  // namespace test

// Mocked function return values, if any
namespace test {
namespace mock {
namespace stack_btm_sco_hfp_hal {

bool enable_offload::return_value = false;
hfp_hal_interface::bt_codecs get_codec_capabilities::return_value = {};
bool get_offload_enabled::return_value = false;
bool get_offload_supported::return_value = false;
int get_packet_size::return_value = 0;
bool get_wbs_supported::return_value = false;

}  // namespace stack_btm_sco_hfp_hal
}  // namespace mock
}  // namespace test

namespace hfp_hal_interface {

// Mocked functions, if any
bool enable_offload(bool enable) {
  mock_function_count_map[__func__]++;
  return test::mock::stack_btm_sco_hfp_hal::enable_offload(enable);
}
bt_codecs get_codec_capabilities(uint64_t codecs) {
  mock_function_count_map[__func__]++;
  return test::mock::stack_btm_sco_hfp_hal::get_codec_capabilities(codecs);
}
bool get_offload_enabled() {
  mock_function_count_map[__func__]++;
  return test::mock::stack_btm_sco_hfp_hal::get_offload_enabled();
}
bool get_offload_supported() {
  mock_function_count_map[__func__]++;
  return test::mock::stack_btm_sco_hfp_hal::get_offload_supported();
}
int get_packet_size(int codec) {
  mock_function_count_map[__func__]++;
  return test::mock::stack_btm_sco_hfp_hal::get_packet_size(codec);
}
bool get_wbs_supported() {
  mock_function_count_map[__func__]++;
  return test::mock::stack_btm_sco_hfp_hal::get_wbs_supported();
}
void init() {
  mock_function_count_map[__func__]++;
  test::mock::stack_btm_sco_hfp_hal::init();
}
void notify_sco_connection_change(RawAddress device, bool is_connected,
                                  int codec) {
  mock_function_count_map[__func__]++;
  test::mock::stack_btm_sco_hfp_hal::notify_sco_connection_change(
      device, is_connected, codec);
}
void set_codec_datapath(esco_coding_format_t coding_format) {
  mock_function_count_map[__func__]++;
  test::mock::stack_btm_sco_hfp_hal::set_codec_datapath(coding_format);
}
void update_esco_parameters(enh_esco_params_t* p_parms) {
  mock_function_count_map[__func__]++;
  test::mock::stack_btm_sco_hfp_hal::update_esco_parameters(p_parms);
}

}  // namespace hfp_hal_interface

// Mocked functions complete
#endif  //  __clang_analyzer__
// END mockcify generation
