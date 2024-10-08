/******************************************************************************
 *
 *  Copyright 2015 Google, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#pragma once

#include <string>
#include <unordered_map>

// Creates a hash map based on the |params| string containing key and value
// pairs.  Pairs are expected in the form "key=value" separated by the ';'
// character.  Both ';' and '=' characters are invalid in keys or values.
// |params| cannot be NULL, is not modified and is owned by the caller.
// Examples:
//   "key0=value10;key1=value1;" -> map: [key0]="value0" [key1]="value1"
//   "key0=;key1=value1;"        -> map: [key0]="" [key1]="value1"
//   "=value0;key1=value1;"      -> map: [key1]="value1"
// A new hash map or NULL is returned and is owned by the caller.
std::unordered_map<std::string, std::string> hash_map_utils_new_from_string_params(
        const char* params);
