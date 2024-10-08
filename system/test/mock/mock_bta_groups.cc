/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

#include <vector>

#include "bta/include/bta_groups.h"
#include "test/common/mock_functions.h"

using bluetooth::groups::DeviceGroups;

void DeviceGroups::AddFromStorage(const RawAddress& /* addr */,
                                  const std::vector<uint8_t>& /* in */) {
  inc_func_call_count(__func__);
}

bool DeviceGroups::GetForStorage(const RawAddress& /* addr */, std::vector<uint8_t>& /* out */) {
  inc_func_call_count(__func__);
  return false;
}
