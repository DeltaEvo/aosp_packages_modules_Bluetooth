/******************************************************************************
 *
 *  Copyright 2014 Google, Inc.
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
#include "osi/include/allocator.h"

#include <bluetooth/log.h>
#include <stdlib.h>
#include <string.h>

using namespace bluetooth;

char* osi_strdup(const char* str) {
  size_t size = strlen(str) + 1;  // + 1 for the null terminator
  char* new_string = (char*)malloc(size);
  log::assert_that(new_string != nullptr, "assert failed: new_string != nullptr");

  if (!new_string) {
    return NULL;
  }

  memcpy(new_string, str, size);
  return new_string;
}

char* osi_strndup(const char* str, size_t len) {
  size_t size = strlen(str);
  if (len < size) {
    size = len;
  }

  char* new_string = (char*)malloc(size + 1);
  log::assert_that(new_string != nullptr, "assert failed: new_string != nullptr");

  if (!new_string) {
    return NULL;
  }

  memcpy(new_string, str, size);
  new_string[size] = '\0';
  return new_string;
}

void* osi_malloc(size_t size) {
  log::assert_that(static_cast<ssize_t>(size) >= 0,
                   "assert failed: static_cast<ssize_t>(size) >= 0");
  void* ptr = malloc(size);
  log::assert_that(ptr != nullptr, "assert failed: ptr != nullptr");
  return ptr;
}

void* osi_calloc(size_t size) {
  log::assert_that(static_cast<ssize_t>(size) >= 0,
                   "assert failed: static_cast<ssize_t>(size) >= 0");
  void* ptr = calloc(1, size);
  log::assert_that(ptr != nullptr, "assert failed: ptr != nullptr");
  return ptr;
}

void osi_free(void* ptr) { free(ptr); }

void osi_free_and_reset(void** p_ptr) {
  log::assert_that(p_ptr != NULL, "assert failed: p_ptr != NULL");
  osi_free(*p_ptr);
  *p_ptr = NULL;
}

const allocator_t allocator_calloc = {osi_calloc, osi_free};

const allocator_t allocator_malloc = {osi_malloc, osi_free};

OsiObject::OsiObject(void* ptr) : ptr_(ptr) {}

OsiObject::OsiObject(const void* ptr) : ptr_(const_cast<void*>(ptr)) {}

OsiObject::~OsiObject() {
  if (ptr_ != nullptr) {
    osi_free(ptr_);
  }
}

void* OsiObject::Release() {
  void* ptr = ptr_;
  ptr_ = nullptr;
  return ptr;
}
