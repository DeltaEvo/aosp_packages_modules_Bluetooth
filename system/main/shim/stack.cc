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

#define LOG_TAG "bt_gd_shim"

#include "main/shim/stack.h"

#include <bluetooth/log.h>
#include <fcntl.h>
#include <unistd.h>

#include <string>

#include "common/strings.h"
#include "hal/hci_hal.h"
#include "hci/acl_manager.h"
#include "hci/acl_manager/acl_scheduler.h"
#include "hci/controller.h"
#include "hci/controller_interface.h"
#include "hci/distance_measurement_manager.h"
#include "hci/hci_layer.h"
#include "hci/le_advertising_manager.h"
#include "hci/le_scanning_manager.h"
#if TARGET_FLOSS
#include "hci/msft.h"
#endif
#include "hci/remote_name_request.h"
#include "main/shim/acl.h"
#include "main/shim/acl_legacy_interface.h"
#include "main/shim/distance_measurement_manager.h"
#include "main/shim/entry.h"
#include "main/shim/hci_layer.h"
#include "main/shim/le_advertising_manager.h"
#include "main/shim/le_scanning_manager.h"
#include "metrics/counter_metrics.h"
#include "shim/dumpsys.h"
#include "storage/storage_module.h"
#if TARGET_FLOSS
#include "sysprops/sysprops_module.h"
#endif

namespace bluetooth {
namespace shim {

using ::bluetooth::common::StringFormat;

struct Stack::impl {
  legacy::Acl* acl_ = nullptr;
};

Stack::Stack() { pimpl_ = std::make_shared<Stack::impl>(); }

Stack* Stack::GetInstance() {
  static Stack instance;
  return &instance;
}

void Stack::StartEverything() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(!is_running_, "Gd stack already running");
  log::info("Starting Gd stack");
  ModuleList modules;

  modules.add<metrics::CounterMetrics>();
  modules.add<hal::HciHal>();
  modules.add<hci::HciLayer>();
  modules.add<storage::StorageModule>();
  modules.add<shim::Dumpsys>();
#if TARGET_FLOSS
  modules.add<sysprops::SyspropsModule>();
#endif

  modules.add<hci::Controller>();
  modules.add<hci::acl_manager::AclScheduler>();
  modules.add<hci::AclManager>();
  modules.add<hci::RemoteNameRequestModule>();
  modules.add<hci::LeAdvertisingManager>();
#if TARGET_FLOSS
  modules.add<hci::MsftExtensionManager>();
#endif
  modules.add<hci::LeScanningManager>();
  modules.add<hci::DistanceMeasurementManager>();
  Start(&modules);
  is_running_ = true;
  // Make sure the leaf modules are started
  log::assert_that(stack_manager_.GetInstance<storage::StorageModule>() != nullptr,
                   "assert failed: stack_manager_.GetInstance<storage::StorageModule>() != "
                   "nullptr");
  log::assert_that(stack_manager_.GetInstance<shim::Dumpsys>() != nullptr,
                   "assert failed: stack_manager_.GetInstance<shim::Dumpsys>() != nullptr");
  if (stack_manager_.IsStarted<hci::Controller>()) {
    pimpl_->acl_ = new legacy::Acl(stack_handler_, legacy::GetAclInterface(),
                                   GetController()->GetLeFilterAcceptListSize(),
                                   GetController()->GetLeResolvingListSize());
  } else {
    log::error("Unable to create shim ACL layer as Controller has not started");
  }

  bluetooth::shim::hci_on_reset_complete();
  bluetooth::shim::init_advertising_manager();
  bluetooth::shim::init_scanning_manager();
  bluetooth::shim::init_distance_measurement_manager();
}

void Stack::StartModuleStack(const ModuleList* modules, const os::Thread* thread) {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(!is_running_, "Gd stack already running");
  stack_thread_ = const_cast<os::Thread*>(thread);
  log::info("Starting Gd stack");

  stack_manager_.StartUp(const_cast<ModuleList*>(modules), stack_thread_);
  stack_handler_ = new os::Handler(stack_thread_);

  num_modules_ = modules->NumModules();
  is_running_ = true;
}

void Stack::Start(ModuleList* modules) {
  log::assert_that(!is_running_, "Gd stack already running");
  log::info("Starting Gd stack");

  stack_thread_ = new os::Thread("gd_stack_thread", os::Thread::Priority::REAL_TIME);
  stack_manager_.StartUp(modules, stack_thread_);

  stack_handler_ = new os::Handler(stack_thread_);

  log::info("Successfully toggled Gd stack");
}

void Stack::Stop() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  bluetooth::shim::hci_on_shutting_down();

  // Make sure gd acl flag is enabled and we started it up
  if (pimpl_->acl_ != nullptr) {
    pimpl_->acl_->FinalShutdown();
    delete pimpl_->acl_;
    pimpl_->acl_ = nullptr;
  }

  log::assert_that(is_running_, "Gd stack not running");
  is_running_ = false;

  stack_handler_->Clear();

  stack_manager_.ShutDown();

  delete stack_handler_;
  stack_handler_ = nullptr;

  stack_thread_->Stop();
  delete stack_thread_;
  stack_thread_ = nullptr;

  log::info("Successfully shut down Gd stack");
}

bool Stack::IsRunning() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  return is_running_;
}

StackManager* Stack::GetStackManager() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &stack_manager_;
}

const StackManager* Stack::GetStackManager() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return &stack_manager_;
}

legacy::Acl* Stack::GetAcl() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  log::assert_that(pimpl_->acl_ != nullptr, "Acl shim layer has not been created");
  return pimpl_->acl_;
}

os::Handler* Stack::GetHandler() {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  log::assert_that(is_running_, "assert failed: is_running_");
  return stack_handler_;
}

bool Stack::IsDumpsysModuleStarted() const {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  return GetStackManager()->IsStarted<Dumpsys>();
}

bool Stack::LockForDumpsys(std::function<void()> dumpsys_callback) {
  std::lock_guard<std::recursive_mutex> lock(mutex_);
  if (is_running_) {
    dumpsys_callback();
  }
  return is_running_;
}

}  // namespace shim
}  // namespace bluetooth
