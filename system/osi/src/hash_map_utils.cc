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

#include "osi/include/hash_map_utils.h"

#include <bluetooth/log.h>

#include <cstring>
#include <map>
#include <string>

#include "osi/include/allocator.h"
#include "osi/include/osi.h"

std::unordered_map<std::string, std::string> hash_map_utils_new_from_string_params(
        const char* params) {
  bluetooth::log::assert_that(params != NULL, "assert failed: params != NULL");

  std::unordered_map<std::string, std::string> map;

  char* str = osi_strdup(params);
  if (!str) {
    return map;
  }

  // Parse |str| and add extracted key-and-value pair(s) in |map|.
  char* tmpstr;
  char* kvpair = strtok_r(str, ";", &tmpstr);
  while (kvpair && *kvpair) {
    char* eq = strchr(kvpair, '=');

    if (eq == kvpair) {
      goto next_pair;
    }

    char* key;
    char* value;
    if (eq) {
      key = osi_strndup(kvpair, eq - kvpair);

      // The increment of |eq| moves |eq| to the beginning of the value.
      ++eq;
      value = (*eq != '\0') ? osi_strdup(eq) : osi_strdup("");
    } else {
      key = osi_strdup(kvpair);
      value = osi_strdup("");
    }

    map[key] = value;

    osi_free(key);
    osi_free(value);
  next_pair:
    kvpair = strtok_r(NULL, ";", &tmpstr);
  }

  osi_free(str);
  return map;
}
