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

#include "common/time_util.h"

#include <sys/time.h>
#include <time.h>

namespace bluetooth {

namespace common {

uint64_t time_get_audio_server_tick_us() {
#ifndef TARGET_FLOSS
  return time_get_os_boottime_us();
#else
  return time_get_os_monotonic_raw_us();
#endif
}

uint64_t time_get_os_boottime_ms() { return time_get_os_boottime_us() / 1000; }

uint64_t time_get_os_boottime_us() {
  struct timespec ts_now = {};
  clock_gettime(CLOCK_BOOTTIME, &ts_now);

  return ((uint64_t)ts_now.tv_sec * 1000000L) + ((uint64_t)ts_now.tv_nsec / 1000);
}

uint64_t time_gettimeofday_us() {
  struct timeval tv = {};
  gettimeofday(&tv, nullptr);
  return static_cast<uint64_t>(tv.tv_sec) * 1000000ULL + static_cast<uint64_t>(tv.tv_usec);
}

uint64_t time_get_os_monotonic_raw_us() {
  struct timespec ts_now = {};
  clock_gettime(CLOCK_MONOTONIC_RAW, &ts_now);

  return ((uint64_t)ts_now.tv_sec * 1000000L) + ((uint64_t)ts_now.tv_nsec / 1000);
}
}  // namespace common

}  // namespace bluetooth
