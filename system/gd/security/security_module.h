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
#include "security/facade_configuration_api.h"
#include "security/security_manager.h"

namespace bluetooth {
namespace security {

class SecurityModule : public bluetooth::Module {
public:
  SecurityModule() = default;
  SecurityModule(const SecurityModule&) = delete;
  SecurityModule& operator=(const SecurityModule&) = delete;

  ~SecurityModule() = default;

  /**
   * Get the api to the SecurityManager
   */
  std::unique_ptr<SecurityManager> GetSecurityManager();

  /**
   * Facade configuration API.
   *
   * <p> This allows you to set thins like IO Capabilities, Authentication Requirements, and OOB
   * Data.
   */
  std::unique_ptr<FacadeConfigurationApi> GetFacadeConfigurationApi();

  static const ModuleFactory Factory;

protected:
  void ListDependencies(ModuleList* list) const override;

  void Start() override;

  void Stop() override;

  std::string ToString() const override;

private:
  struct impl;
  std::unique_ptr<impl> pimpl_;
};

}  // namespace security
}  // namespace bluetooth
