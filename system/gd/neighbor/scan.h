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

#include <memory>

#include "module.h"

namespace bluetooth {
namespace neighbor {

class ScanModule : public bluetooth::Module {
public:
  ScanModule();
  ScanModule(const ScanModule&) = delete;
  ScanModule& operator=(const ScanModule&) = delete;

  ~ScanModule();

  void SetInquiryScan();
  void ClearInquiryScan();
  bool IsInquiryEnabled() const;

  void SetPageScan();
  void ClearPageScan();
  bool IsPageEnabled() const;

  static const ModuleFactory Factory;

protected:
  void ListDependencies(ModuleList* list) const override;
  void Start() override;
  void Stop() override;
  std::string ToString() const override { return std::string("Scan"); }

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace neighbor
}  // namespace bluetooth
