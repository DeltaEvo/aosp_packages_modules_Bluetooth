/*
 * Copyright 2021 The Android Open Source Project
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
 */

#include <cstdint>

#include "bta/include/bta_rfcomm_scn.h"
#include "bta/jv/bta_jv_int.h"
#include "test/common/mock_functions.h"

bool BTA_FreeSCN(uint8_t scn) {
  inc_func_call_count(__func__);
  return false;
}
bool BTA_TryAllocateSCN(uint8_t scn) {
  inc_func_call_count(__func__);
  return false;
}
uint8_t BTA_AllocateSCN(void) {
  inc_func_call_count(__func__);
  return 0;
}
