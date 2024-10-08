/*
 * Copyright 2018 The Android Open Source Project
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

#include <gtest/gtest.h>

#include <future>

#include "common/bind.h"
#include "l2cap/cid.h"
#include "l2cap/le/dynamic_channel_manager.h"
#include "l2cap/le/dynamic_channel_service.h"
#include "l2cap/le/internal/dynamic_channel_service_manager_impl.h"
#include "os/handler.h"
#include "os/thread.h"

namespace bluetooth {
namespace l2cap {
namespace le {
namespace internal {

class L2capLeDynamicServiceManagerTest : public ::testing::Test {
public:
  ~L2capLeDynamicServiceManagerTest() = default;

  void OnServiceRegistered(bool expect_success, DynamicChannelManager::RegistrationResult result,
                           std::unique_ptr<DynamicChannelService> user_service) {
    EXPECT_EQ(result == DynamicChannelManager::RegistrationResult::SUCCESS, expect_success);
    service_registered_ = expect_success;
  }

protected:
  void SetUp() override {
    thread_ = new os::Thread("test_thread", os::Thread::Priority::NORMAL);
    user_handler_ = new os::Handler(thread_);
    l2cap_handler_ = new os::Handler(thread_);
    manager_ = new DynamicChannelServiceManagerImpl{l2cap_handler_};
  }

  void TearDown() override {
    delete manager_;
    l2cap_handler_->Clear();
    delete l2cap_handler_;
    user_handler_->Clear();
    delete user_handler_;
    delete thread_;
  }

  void sync_user_handler() {
    std::promise<void> promise;
    auto future = promise.get_future();
    user_handler_->Post(
            common::BindOnce(&std::promise<void>::set_value, common::Unretained(&promise)));
    future.wait_for(std::chrono::milliseconds(3));
  }

  DynamicChannelServiceManagerImpl* manager_ = nullptr;
  os::Thread* thread_ = nullptr;
  os::Handler* user_handler_ = nullptr;
  os::Handler* l2cap_handler_ = nullptr;

  bool service_registered_ = false;
};

TEST_F(L2capLeDynamicServiceManagerTest, register_and_unregister_le_dynamic_channel) {
  DynamicChannelServiceImpl::PendingRegistration pending_registration{
          .user_handler_ = user_handler_,
          .security_policy_ = SecurityPolicy::NO_SECURITY_WHATSOEVER_PLAINTEXT_TRANSPORT_OK,
          .on_registration_complete_callback_ =
                  common::BindOnce(&L2capLeDynamicServiceManagerTest::OnServiceRegistered,
                                   common::Unretained(this), true)};
  Psm psm = 0x41;
  EXPECT_FALSE(manager_->IsServiceRegistered(psm));
  manager_->Register(psm, std::move(pending_registration));
  EXPECT_TRUE(manager_->IsServiceRegistered(psm));
  sync_user_handler();
  EXPECT_TRUE(service_registered_);
  manager_->Unregister(psm, common::BindOnce([] {}), user_handler_);
  EXPECT_FALSE(manager_->IsServiceRegistered(psm));
}

TEST_F(L2capLeDynamicServiceManagerTest, register_le_dynamic_channel_even_number_psm) {
  DynamicChannelServiceImpl::PendingRegistration pending_registration{
          .user_handler_ = user_handler_,
          .security_policy_ = SecurityPolicy::NO_SECURITY_WHATSOEVER_PLAINTEXT_TRANSPORT_OK,
          .on_registration_complete_callback_ =
                  common::BindOnce(&L2capLeDynamicServiceManagerTest::OnServiceRegistered,
                                   common::Unretained(this), true)};
  Psm psm = 0x0100;
  EXPECT_FALSE(manager_->IsServiceRegistered(psm));
  manager_->Register(psm, std::move(pending_registration));
  EXPECT_TRUE(manager_->IsServiceRegistered(psm));
  sync_user_handler();
  EXPECT_TRUE(service_registered_);
}

}  // namespace internal
}  // namespace le
}  // namespace l2cap
}  // namespace bluetooth
