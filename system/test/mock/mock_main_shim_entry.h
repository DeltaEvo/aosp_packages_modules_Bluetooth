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

#include "hci/acl_manager_mock.h"
#include "hci/controller_mock.h"
#include "hci/le_advertising_manager_mock.h"
#include "hci/le_scanning_manager_mock.h"

namespace bluetooth {
namespace hci {
namespace testing {

extern MockAclManager* mock_acl_manager_;
extern MockController* mock_controller_;
extern MockLeAdvertisingManager* mock_le_advertising_manager_;
extern MockLeScanningManager* mock_le_scanning_manager_;
extern MockDistanceMeasurementManager* mock_distance_measurement_manager_;

}  // namespace testing
}  // namespace hci
}  // namespace bluetooth
