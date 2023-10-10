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

/*
 * Generated mock file from original source file
 *   Functions generated:14
 */

#include <map>
#include <string>

#define LOG_TAG "bt_shim"
#include "gd/common/init_flags.h"
#include "main/shim/entry.h"
#include "main/shim/shim.h"
#include "test/common/mock_functions.h"

#ifndef UNUSED_ATTR
#define UNUSED_ATTR
#endif

bool bluetooth::shim::is_gd_stack_started_up() {
  inc_func_call_count(__func__);
  return false;
}
future_t* GeneralShutDown() {
  inc_func_call_count(__func__);
  return nullptr;
}
future_t* IdleModuleStartUp() {
  inc_func_call_count(__func__);
  return nullptr;
}
future_t* ShimModuleStartUp() {
  inc_func_call_count(__func__);
  return nullptr;
}
