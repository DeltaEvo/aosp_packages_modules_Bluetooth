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

#include "metrics/utils.h"

#include <base/files/file_util.h>
#include <base/strings/string_util.h>

namespace bluetooth {
namespace metrics {

namespace {
// The path to the kernel's boot_id.
const char kBootIdPath[] = "/proc/sys/kernel/random/boot_id";
}  // namespace

bool GetBootId(std::string* boot_id) {
  if (!base::ReadFileToString(base::FilePath(kBootIdPath), boot_id)) {
    return false;
  }
  base::TrimWhitespaceASCII(*boot_id, base::TRIM_TRAILING, boot_id);
  return true;
}

}  // namespace metrics
}  // namespace bluetooth
