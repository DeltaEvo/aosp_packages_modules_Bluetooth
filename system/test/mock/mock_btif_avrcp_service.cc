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
 *   Functions generated:1
 *
 *  mockcify.pl ver 0.5.0
 */

// Mock include file to share data between tests and mock
#include "test/mock/mock_btif_avrcp_service.h"

#include "btif/avrcp/avrcp_service.h"
#include "test/common/mock_functions.h"
#include "types/raw_address.h"

// Original usings

// Mocked internal structures, if any

bluetooth::avrcp::AvrcpService* bluetooth::avrcp::AvrcpService::Get() {
  inc_func_call_count(__func__);
  return nullptr;
}

void bluetooth::avrcp::AvrcpService::ConnectDevice(RawAddress const&) {
  inc_func_call_count(__func__);
}

// Mocked functions complete
// END mockcify generation
