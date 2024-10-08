/******************************************************************************
 *
 *  Copyright 2016 Google, Inc.
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

#include <stdbool.h>
#include <stdint.h>

#define UNUSED_ATTR __attribute__((unused))
#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))
#define INVALID_FD (-1)

#define CONCAT(a, b) a##b

// Use during compile time to check conditional values
// NOTE: The the failures will present as a generic error
// "error: initialization makes pointer from integer without a cast"
// but the file and line number will present the condition that
// failed.
#define DUMMY_COUNTER(c) CONCAT(__osi_dummy_, c)
#define DUMMY_PTR DUMMY_COUNTER(__COUNTER__)

// Macros for safe integer to pointer conversion. In the C language, data is
// commonly cast to opaque pointer containers and back for generic parameter
// passing in callbacks. These macros should be used sparingly in new code
// (never in C++ code). Whenever integers need to be passed as a pointer, use
// these macros.
#define PTR_TO_UINT(p) ((unsigned int)((uintptr_t)(p)))
#define UINT_TO_PTR(u) ((void*)((uintptr_t)(u)))

#define PTR_TO_INT(p) ((int)((intptr_t)(p)))
#define INT_TO_PTR(i) ((void*)((intptr_t)(i)))

// Re-run |fn| system call until the system call doesn't cause EINTR.
#define OSI_NO_INTR(fn) \
  do {                  \
  } while ((fn) == -1 && errno == EINTR)

#ifndef FALLTHROUGH_INTENDED
#define FALLTHROUGH_INTENDED [[clang::fallthrough]]  // NOLINT
#endif
