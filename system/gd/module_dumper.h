/*
 * Copyright 2019 The Android Open Source Project
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

#pragma once

#include <string>

namespace bluetooth {

class ModuleRegistry;

class ModuleDumper {
 public:
  ModuleDumper(const ModuleRegistry& module_registry, const char* title)
      : module_registry_(module_registry), title_(title) {}
  void DumpState(std::string* output) const;

 private:
  const ModuleRegistry& module_registry_;
  const std::string title_;
};

}  // namespace bluetooth
