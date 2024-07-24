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
#define LOG_TAG "BtGdModule"

#include "module.h"

#include <bluetooth/log.h>

using ::bluetooth::os::Handler;
using ::bluetooth::os::Thread;

namespace bluetooth {

constexpr std::chrono::milliseconds kModuleStopTimeout = std::chrono::milliseconds(2000);

ModuleFactory::ModuleFactory(std::function<Module*()> ctor) : ctor_(ctor) {}

Handler* Module::GetHandler() const {
  log::assert_that(handler_ != nullptr, "Can't get handler when it's not started");
  return handler_;
}

const ModuleRegistry* Module::GetModuleRegistry() const { return registry_; }

Module* Module::GetDependency(const ModuleFactory* module) const {
  for (auto& dependency : dependencies_.list_) {
    if (dependency == module) {
      return registry_->Get(module);
    }
  }

  log::fatal("Module was not listed as a dependency in ListDependencies");
}

bluetooth::DumpsysDataFinisher EmptyDumpsysDataFinisher =
        [](bluetooth::DumpsysDataBuilder* /* dumpsys_data_builder */) {};

DumpsysDataFinisher Module::GetDumpsysData(flatbuffers::FlatBufferBuilder* /* builder */) const {
  return EmptyDumpsysDataFinisher;
}

Module* ModuleRegistry::Get(const ModuleFactory* module) const {
  auto instance = started_modules_.find(module);
  log::assert_that(instance != started_modules_.end(),
                   "Request for module not started up, maybe not in Start(ModuleList)?");
  return instance->second;
}

bool ModuleRegistry::IsStarted(const ModuleFactory* module) const {
  return started_modules_.find(module) != started_modules_.end();
}

void ModuleRegistry::Start(ModuleList* modules, Thread* thread) {
  for (auto it = modules->list_.begin(); it != modules->list_.end(); it++) {
    Start(*it, thread);
  }
}

void ModuleRegistry::set_registry_and_handler(Module* instance, Thread* thread) const {
  instance->registry_ = this;
  instance->handler_ = new Handler(thread);
}

Module* ModuleRegistry::Start(const ModuleFactory* module, Thread* thread) {
  auto started_instance = started_modules_.find(module);
  if (started_instance != started_modules_.end()) {
    return started_instance->second;
  }

  log::info("Constructing next module");
  Module* instance = module->ctor_();
  set_registry_and_handler(instance, thread);

  log::info("Starting dependencies of {}", instance->ToString());
  instance->ListDependencies(&instance->dependencies_);
  Start(&instance->dependencies_, thread);

  log::info("Finished starting dependencies and calling Start() of {}", instance->ToString());

  last_instance_ = "starting " + instance->ToString();
  instance->Start();
  start_order_.push_back(module);
  started_modules_[module] = instance;
  log::info("Started {}", instance->ToString());
  return instance;
}

void ModuleRegistry::StopAll() {
  // Since modules were brought up in dependency order, it is safe to tear down by going in reverse
  // order.
  for (auto it = start_order_.rbegin(); it != start_order_.rend(); it++) {
    auto instance = started_modules_.find(*it);
    log::assert_that(instance != started_modules_.end(),
                     "assert failed: instance != started_modules_.end()");
    last_instance_ = "stopping " + instance->second->ToString();

    // Clear the handler before stopping the module to allow it to shut down gracefully.
    log::info("Stopping Handler of Module {}", instance->second->ToString());
    instance->second->handler_->Clear();
    instance->second->handler_->WaitUntilStopped(kModuleStopTimeout);
    log::info("Stopping Module {}", instance->second->ToString());
    instance->second->Stop();
  }
  for (auto it = start_order_.rbegin(); it != start_order_.rend(); it++) {
    auto instance = started_modules_.find(*it);
    log::assert_that(instance != started_modules_.end(),
                     "assert failed: instance != started_modules_.end()");
    delete instance->second->handler_;
    delete instance->second;
    started_modules_.erase(instance);
  }

  log::assert_that(started_modules_.empty(), "assert failed: started_modules_.empty()");
  start_order_.clear();
}

os::Handler* ModuleRegistry::GetModuleHandler(const ModuleFactory* module) const {
  auto started_instance = started_modules_.find(module);
  if (started_instance != started_modules_.end()) {
    return started_instance->second->GetHandler();
  }
  return nullptr;
}

}  // namespace bluetooth
