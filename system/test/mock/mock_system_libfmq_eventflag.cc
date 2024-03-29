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
 *   Functions generated:11
 */

#include <fmq/EventFlag.h>

#include <string>

#include "test/common/mock_functions.h"

using namespace android;
using namespace android::hardware;

namespace android {
namespace hardware {
namespace details {

void logError(const std::string& message) {}
void check(bool exp, const char* message) {}

}  // namespace details
}  // namespace hardware
}  // namespace android

EventFlag::EventFlag(std::atomic<uint32_t>* fwAddr, status_t* status) {
  inc_func_call_count(__func__);
}
EventFlag::~EventFlag() { inc_func_call_count(__func__); }
status_t EventFlag::createEventFlag(std::atomic<uint32_t>* fwAddr,
                                    EventFlag** flag) {
  inc_func_call_count(__func__);
  return 0;
}
status_t EventFlag::deleteEventFlag(EventFlag** evFlag) {
  inc_func_call_count(__func__);
  return 0;
}
status_t EventFlag::unmapEventFlagWord(std::atomic<uint32_t>* efWordPtr,
                                       bool* efWordNeedsUnmapping) {
  inc_func_call_count(__func__);
  return 0;
}
status_t EventFlag::wait(uint32_t bitmask, uint32_t* efState,
                         int64_t timeoutNanoSeconds, bool retry) {
  inc_func_call_count(__func__);
  return 0;
}
status_t EventFlag::waitHelper(uint32_t bitmask, uint32_t* efState,
                               int64_t timeoutNanoSeconds) {
  inc_func_call_count(__func__);
  return 0;
}
status_t EventFlag::wake(uint32_t bitmask) {
  inc_func_call_count(__func__);
  return 0;
}
void EventFlag::addNanosecondsToCurrentTime(int64_t nanoSeconds,
                                            struct timespec* waitTime) {
  inc_func_call_count(__func__);
}
