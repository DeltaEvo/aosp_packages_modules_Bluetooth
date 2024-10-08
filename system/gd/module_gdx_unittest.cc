/*
 * Copyright 2023 The Android Open Source Project
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

#include "module_gdx_unittest.h"

#include <base/callback.h>
#include <base/functional/bind.h>
#include <base/location.h>
#include <base/threading/platform_thread.h>
#include <sys/syscall.h>

#include <chrono>
#include <future>
#include <string>

#include "common/bind.h"
#include "common/contextual_callback.h"
#include "gtest/gtest.h"
#include "module.h"
#include "os/handler.h"

using namespace bluetooth;

namespace {
constexpr int sync_timeout_in_ms = 3000;

std::promise<pid_t> gdx_external_function_promise;
std::promise<pid_t> private_impl_promise;
std::promise<pid_t> protected_method_promise;

}  // namespace

// Global function with C linkage
void external_function_gdx(int /* a */, double /* b */, char /* c */) {
  gdx_external_function_promise.set_value(base::PlatformThread::CurrentId());
}

// Module private implementation that is inaccessible externally
struct TestGdxModule::PrivateImpl : public ModuleMainloop, public ModuleJniloop {
  const int kMaxTestGdxModuleRecurseDepth = 10;

  void privateCallableMethod(int /* a */, double /* b */, char /* c */) {
    private_impl_promise.set_value(base::PlatformThread::CurrentId());
  }

  void repostMethodTest(int /* a */, double /* b */, char /* c */) {
    private_impl_promise.set_value(base::PlatformThread::CurrentId());
  }

  void privateCallableRepostOnMainMethod(std::shared_ptr<TestGdxModule::PrivateImpl> ptr, int a,
                                         double b, char c) {
    PostMethodOnMain(ptr, &PrivateImpl::repostMethodTest, a, b, c);
  }

  void privateCallableRepostOnJniMethod(std::shared_ptr<TestGdxModule::PrivateImpl> ptr, int a,
                                        double b, char c) {
    PostMethodOnJni(ptr, &PrivateImpl::repostMethodTest, a, b, c);
  }

  void privateCallableRecursiveOnMainMethod(std::shared_ptr<TestGdxModule::PrivateImpl> ptr,
                                            int depth, double b, char c) {
    if (depth > kMaxTestGdxModuleRecurseDepth) {
      private_impl_promise.set_value(base::PlatformThread::CurrentId());
      return;
    }
    PostMethodOnMain(ptr, &PrivateImpl::privateCallableRecursiveOnMainMethod, ptr, depth + 1, b, c);
  }

  void privateCallableRecursiveOnJniMethod(std::shared_ptr<TestGdxModule::PrivateImpl> ptr,
                                           int depth, double b, char c) {
    if (depth > kMaxTestGdxModuleRecurseDepth) {
      private_impl_promise.set_value(base::PlatformThread::CurrentId());
      return;
    }
    PostMethodOnJni(ptr, &PrivateImpl::privateCallableRecursiveOnJniMethod, ptr, depth + 1, b, c);
  }
};

// Protected module method executed on handler
void TestGdxModule::call_on_handler_protected_method(int loop_tid, int a, int b, int c) {
  protected_method_promise = std::promise<pid_t>();
  auto future = protected_method_promise.get_future();
  CallOn(this, &TestGdxModule::protected_method, a, b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}

// Global external function executed on main loop
void TestGdxModule::call_on_main_external_function(int loop_tid, int a, double b, char c) {
  gdx_external_function_promise = std::promise<pid_t>();
  auto future = gdx_external_function_promise.get_future();
  PostFunctionOnMain(&external_function_gdx, a, b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}

// Private implementation method executed on main loop
void TestGdxModule::call_on_main(int loop_tid, int a, int b, int c) {
  private_impl_promise = std::promise<pid_t>();
  auto future = private_impl_promise.get_future();
  PostMethodOnMain(pimpl_, &TestGdxModule::PrivateImpl::privateCallableMethod, a, b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}

// Private implementation method executed on main loop and reposted
void TestGdxModule::call_on_main_repost(int loop_tid, int a, int b, int c) {
  private_impl_promise = std::promise<pid_t>();
  auto future = private_impl_promise.get_future();
  PostMethodOnMain(pimpl_, &TestGdxModule::PrivateImpl::privateCallableRepostOnMainMethod, pimpl_,
                   a, b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}

// Private implementation method executed on main loop recursively
void TestGdxModule::call_on_main_recurse(int loop_tid, int depth, int b, int c) {
  private_impl_promise = std::promise<pid_t>();
  auto future = private_impl_promise.get_future();
  PostMethodOnMain(pimpl_, &TestGdxModule::PrivateImpl::privateCallableRecursiveOnMainMethod,
                   pimpl_, depth, b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}

// Global external function executed on main loop
void TestGdxModule::call_on_jni_external_function(int loop_tid, int a, double b, char c) {
  gdx_external_function_promise = std::promise<pid_t>();
  auto future = gdx_external_function_promise.get_future();
  PostFunctionOnJni(&external_function_gdx, a, b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}

// Private implementation method executed on main loop
void TestGdxModule::call_on_jni(int loop_tid, int a, int b, int c) {
  private_impl_promise = std::promise<pid_t>();
  auto future = private_impl_promise.get_future();
  PostMethodOnJni(pimpl_, &TestGdxModule::PrivateImpl::privateCallableMethod, a, b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}

// Private implementation method executed on main loop and reposted
void TestGdxModule::call_on_jni_repost(int loop_tid, int a, int b, int c) {
  private_impl_promise = std::promise<pid_t>();
  auto future = private_impl_promise.get_future();
  PostMethodOnJni(pimpl_, &TestGdxModule::PrivateImpl::privateCallableRepostOnJniMethod, pimpl_, a,
                  b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}

// Private implementation method executed on main loop recursively
void TestGdxModule::call_on_jni_recurse(int loop_tid, int depth, int b, int c) {
  private_impl_promise = std::promise<pid_t>();
  auto future = private_impl_promise.get_future();
  PostMethodOnJni(pimpl_, &TestGdxModule::PrivateImpl::privateCallableRecursiveOnJniMethod, pimpl_,
                  depth, b, c);
  ASSERT_EQ(future.wait_for(std::chrono::seconds(3)), std::future_status::ready);
  ASSERT_EQ(future.get(), loop_tid);
}
void TestGdxModule::protected_method(int /* a */, int /* b */, int /* c */) {
  protected_method_promise.set_value(base::PlatformThread::CurrentId());
}

bool TestGdxModule::IsStarted() const { return pimpl_ != nullptr; }

void TestGdxModule::Start() {
  ASSERT_FALSE(IsStarted());
  pimpl_ = std::make_shared<TestGdxModule::PrivateImpl>();
}

void TestGdxModule::Stop() {
  ASSERT_TRUE(IsStarted());
  pimpl_.reset();
}

std::string TestGdxModule::ToString() const { return "TestGdxModule"; }

const bluetooth::ModuleFactory TestGdxModule::Factory =
        bluetooth::ModuleFactory([]() { return new TestGdxModule(); });

void TestGdxModule::set_callback(common::ContextualCallback<void(std::string)> callback) {
  call_many_ = callback;
}

void TestGdxModule::set_once_callback(common::ContextualOnceCallback<void(std::string)> callback) {
  call_once_ = std::move(callback);
}

void TestGdxModule::call_callback_on_handler(std::string message) {
  GetHandler()->Post(common::BindOnce([](common::ContextualCallback<void(std::string)> callback,
                                         std::string message) { callback(message); },
                                      call_many_, message));
}

void TestGdxModule::call_once_callback_on_handler(std::string message) {
  GetHandler()->Post(common::BindOnce([](common::ContextualOnceCallback<void(std::string)> callback,
                                         std::string message) { callback(message); },
                                      std::move(call_once_), message));
}

void TestGdxModule::call_callback_on_main(std::string message) {
  post_on_bt_main([message, this]() { call_many_(message); });
}

void TestGdxModule::call_once_callback_on_main(std::string message) {
  post_on_bt_main([message, this]() { call_once_(message); });
}

void TestGdxModule::call_callback_on_jni(std::string message) {
  post_on_bt_jni([message, this]() { call_many_(message); });
}

void TestGdxModule::call_once_callback_on_jni(std::string message) {
  post_on_bt_jni([message, this]() { call_once_(message); });
}

//
// Module GDx Testing Below
//
class ModuleGdxTest : public ::testing::Test {
protected:
  void SetUp() override {
    test_framework_tid_ = base::PlatformThread::CurrentId();
    module_ = new TestGdxModule();
    main_thread_start_up();
    mainloop_tid_ = get_mainloop_tid();
    jni_thread_startup();
    jniloop_tid_ = get_jniloop_tid();
  }

  void TearDown() override {
    sync_main_handler();
    main_thread_shut_down();
    jni_thread_shutdown();
    delete module_;
  }

  void sync_main_handler() {
    std::promise promise = std::promise<void>();
    std::future future = promise.get_future();
    post_on_bt_main([&promise]() { promise.set_value(); });
    future.wait_for(std::chrono::milliseconds(sync_timeout_in_ms));
  }

  void sync_jni_handler() {
    std::promise promise = std::promise<void>();
    std::future future = promise.get_future();
    post_on_bt_jni([&promise]() { promise.set_value(); });
    future.wait_for(std::chrono::milliseconds(sync_timeout_in_ms));
  }

  static pid_t get_mainloop_tid() {
    std::promise<pid_t> pid_promise = std::promise<pid_t>();
    auto future = pid_promise.get_future();
    post_on_bt_main([&pid_promise]() { pid_promise.set_value(base::PlatformThread::CurrentId()); });
    return future.get();
  }

  static pid_t get_jniloop_tid() {
    std::promise<pid_t> pid_promise = std::promise<pid_t>();
    auto future = pid_promise.get_future();
    post_on_bt_jni([&pid_promise]() { pid_promise.set_value(base::PlatformThread::CurrentId()); });
    return future.get();
  }

  pid_t test_framework_tid_{-1};
  pid_t mainloop_tid_{-1};
  pid_t jniloop_tid_{-1};
  TestModuleRegistry module_registry_;
  TestGdxModule* module_;
};

class ModuleGdxWithStackTest : public ModuleGdxTest {
protected:
  void SetUp() override {
    ModuleGdxTest::SetUp();
    module_registry_.InjectTestModule(&TestGdxModule::Factory, module_ /* pass ownership */);
    module_ = nullptr;  // ownership is passed
    handler_tid_ = get_handler_tid(module_registry_.GetTestModuleHandler(&TestGdxModule::Factory));
  }

  static pid_t get_handler_tid(os::Handler* handler) {
    std::promise<pid_t> handler_tid_promise = std::promise<pid_t>();
    std::future<pid_t> future = handler_tid_promise.get_future();
    handler->Post(common::BindOnce(
            [](std::promise<pid_t> promise) {
              promise.set_value(base::PlatformThread::CurrentId());
            },
            std::move(handler_tid_promise)));
    return future.get();
  }

  void TearDown() override {
    module_registry_.StopAll();
    ModuleGdxTest::TearDown();
  }

  TestGdxModule* Mod() { return module_registry_.GetModuleUnderTest<TestGdxModule>(); }

  pid_t handler_tid_{-1};
};

TEST_F(ModuleGdxTest, nop) {}

TEST_F(ModuleGdxTest, lifecycle) {
  ::bluetooth::os::Thread* thread =
          new bluetooth::os::Thread("Name", bluetooth::os::Thread::Priority::REAL_TIME);
  ASSERT_FALSE(module_registry_.IsStarted<TestGdxModule>());
  module_registry_.Start<TestGdxModule>(thread);
  ASSERT_TRUE(module_registry_.IsStarted<TestGdxModule>());
  module_registry_.StopAll();
  ASSERT_FALSE(module_registry_.IsStarted<TestGdxModule>());
  delete thread;
}

// internal handler
TEST_F(ModuleGdxWithStackTest, call_on_handler_protected_method) {
  Mod()->call_on_handler_protected_method(handler_tid_, 1, 2, 3);
}

TEST_F(ModuleGdxWithStackTest, test_call_on_main) { Mod()->call_on_main(mainloop_tid_, 1, 2, 3); }

TEST_F(ModuleGdxWithStackTest, test_call_gdx_external_function_on_main) {
  Mod()->call_on_main_external_function(mainloop_tid_, 1, 2.3, 'c');
}

TEST_F(ModuleGdxWithStackTest, test_call_on_main_repost) {
  Mod()->call_on_main_repost(mainloop_tid_, 1, 2, 3);
}

TEST_F(ModuleGdxWithStackTest, test_call_on_main_recurse) {
  Mod()->call_on_main_recurse(mainloop_tid_, 1, 2, 3);
}

TEST_F(ModuleGdxWithStackTest, test_call_on_jni) { Mod()->call_on_jni(jniloop_tid_, 1, 2, 3); }

TEST_F(ModuleGdxWithStackTest, test_call_gdx_external_function_on_jni) {
  Mod()->call_on_jni_external_function(jniloop_tid_, 1, 2.3, 'c');
}

TEST_F(ModuleGdxWithStackTest, test_call_on_jni_repost) {
  Mod()->call_on_jni_repost(jniloop_tid_, 1, 2, 3);
}

TEST_F(ModuleGdxWithStackTest, test_call_on_jni_recurse) {
  Mod()->call_on_jni_recurse(jniloop_tid_, 1, 2, 3);
}

class ModuleGdxWithInstrumentedCallback : public ModuleGdxWithStackTest {
protected:
  void SetUp() override { ModuleGdxWithStackTest::SetUp(); }

  void TearDown() override { ModuleGdxWithStackTest::TearDown(); }

  // A helper class to promise/future for synchronization
  class Promises {
  public:
    std::promise<std::string> result;
    std::future<std::string> result_future = result.get_future();
    std::promise<void> blocking;
    std::future<void> blocking_future = blocking.get_future();
    std::promise<void> unblock;
    std::future<void> unblock_future = unblock.get_future();
  };

  class InstrumentedCallback {
  public:
    Promises promises;
    common::ContextualCallback<void(std::string)> callback;
  };

  class InstrumentedOnceCallback {
  public:
    Promises promises;
    common::ContextualOnceCallback<void(std::string)> callback;
  };

  std::unique_ptr<InstrumentedCallback> GetNewCallbackOnMain() {
    auto to_return = std::make_unique<InstrumentedCallback>();
    to_return->callback = get_main()->Bind(
            [](std::promise<std::string>* promise_ptr, std::promise<void>* blocking,
               std::future<void>* unblock, std::string result) {
              // Tell the test that this callback is running (and blocking)
              blocking->set_value();
              // Block until the test is ready to continue
              ASSERT_EQ(std::future_status::ready, unblock->wait_for(std::chrono::seconds(1)));
              // Send the result back to the test
              promise_ptr->set_value(result);
              log::info("set_value {}", result);
            },
            &to_return->promises.result, &to_return->promises.blocking,
            &to_return->promises.unblock_future);

    return to_return;
  }

  std::unique_ptr<InstrumentedOnceCallback> GetNewOnceCallbackOnMain() {
    auto to_return = std::make_unique<InstrumentedOnceCallback>();
    to_return->callback = get_main()->BindOnce(
            [](std::promise<std::string>* promise_ptr, std::promise<void>* blocking,
               std::future<void>* unblock, std::string result) {
              blocking->set_value();
              ASSERT_EQ(std::future_status::ready, unblock->wait_for(std::chrono::seconds(1)));
              promise_ptr->set_value(result);
              log::info("set_value {}", result);
            },
            &to_return->promises.result, &to_return->promises.blocking,
            &to_return->promises.unblock_future);

    return to_return;
  }
};

TEST_F(ModuleGdxWithInstrumentedCallback, test_call_callback_on_handler) {
  auto instrumented = GetNewCallbackOnMain();
  Mod()->set_callback(instrumented->callback);

  // Enqueue the callback and wait for it to block on main thread
  std::string result = "This was called on the handler";
  Mod()->call_callback_on_handler(result);
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.blocking_future.wait_for(std::chrono::seconds(1)));
  log::info("Waiting");

  // Enqueue something else on the main thread and verify that it hasn't run
  static auto second_task_promise = std::promise<void>();
  auto final_future = second_task_promise.get_future();
  do_in_main_thread(common::BindOnce(
          [](std::promise<void> promise) {
            promise.set_value();
            log::info("Finally");
          },
          std::move(second_task_promise)));
  ASSERT_EQ(std::future_status::timeout, final_future.wait_for(std::chrono::milliseconds(1)));

  // Let the callback start
  instrumented->promises.unblock.set_value();
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.result_future.wait_for(std::chrono::seconds(1)));
  ASSERT_EQ(result, instrumented->promises.result_future.get());

  // Let the second task finish
  ASSERT_EQ(std::future_status::ready, final_future.wait_for(std::chrono::seconds(1)));
}

TEST_F(ModuleGdxWithInstrumentedCallback, test_call_once_callback_on_handler) {
  auto instrumented = GetNewOnceCallbackOnMain();
  Mod()->set_once_callback(std::move(instrumented->callback));

  // Enqueue the callback and wait for it to block on main thread
  std::string result = "This was called on the handler";
  Mod()->call_once_callback_on_handler(result);
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.blocking_future.wait_for(std::chrono::seconds(1)));
  log::info("Waiting");

  // Enqueue something else on the main thread and verify that it hasn't run
  static auto second_task_promise = std::promise<void>();
  auto final_future = second_task_promise.get_future();
  do_in_main_thread(common::BindOnce(
          [](std::promise<void> promise) {
            promise.set_value();
            log::info("Finally");
          },
          std::move(second_task_promise)));
  ASSERT_EQ(std::future_status::timeout, final_future.wait_for(std::chrono::milliseconds(1)));

  // Let the callback start
  instrumented->promises.unblock.set_value();
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.result_future.wait_for(std::chrono::seconds(1)));
  ASSERT_EQ(result, instrumented->promises.result_future.get());

  // Let the second task finish
  ASSERT_EQ(std::future_status::ready, final_future.wait_for(std::chrono::seconds(1)));
}

TEST_F(ModuleGdxWithInstrumentedCallback, test_call_callback_on_main) {
  auto instrumented = GetNewCallbackOnMain();
  Mod()->set_callback(instrumented->callback);

  // Enqueue the callback and wait for it to block on main thread
  std::string result = "This was called on the main";
  Mod()->call_callback_on_main(result);
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.blocking_future.wait_for(std::chrono::seconds(1)));
  log::info("Waiting");

  // Enqueue something else on the main thread and verify that it hasn't run
  static auto second_task_promise = std::promise<void>();
  auto final_future = second_task_promise.get_future();
  do_in_main_thread(common::BindOnce(
          [](std::promise<void> promise) {
            promise.set_value();
            log::info("Finally");
          },
          std::move(second_task_promise)));
  ASSERT_EQ(std::future_status::timeout, final_future.wait_for(std::chrono::milliseconds(1)));

  // Let the callback start
  instrumented->promises.unblock.set_value();
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.result_future.wait_for(std::chrono::seconds(1)));
  ASSERT_EQ(result, instrumented->promises.result_future.get());

  // Let the second task finish
  ASSERT_EQ(std::future_status::ready, final_future.wait_for(std::chrono::seconds(1)));
}

TEST_F(ModuleGdxWithInstrumentedCallback, test_call_once_callback_on_main) {
  auto instrumented = GetNewOnceCallbackOnMain();
  Mod()->set_once_callback(std::move(instrumented->callback));

  // Enqueue the callback and wait for it to block on main thread
  std::string result = "This was called on the main";
  Mod()->call_once_callback_on_main(result);
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.blocking_future.wait_for(std::chrono::seconds(1)));
  log::info("Waiting");

  // Enqueue something else on the main thread and verify that it hasn't run
  static auto second_task_promise = std::promise<void>();
  auto final_future = second_task_promise.get_future();
  do_in_main_thread(common::BindOnce(
          [](std::promise<void> promise) {
            promise.set_value();
            log::info("Finally");
          },
          std::move(second_task_promise)));
  ASSERT_EQ(std::future_status::timeout, final_future.wait_for(std::chrono::milliseconds(1)));

  // Let the callback start
  instrumented->promises.unblock.set_value();
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.result_future.wait_for(std::chrono::seconds(1)));
  ASSERT_EQ(result, instrumented->promises.result_future.get());

  // Let the second task finish
  ASSERT_EQ(std::future_status::ready, final_future.wait_for(std::chrono::seconds(1)));
}

TEST_F(ModuleGdxWithInstrumentedCallback, test_call_callback_on_jni) {
  auto instrumented = GetNewCallbackOnMain();
  Mod()->set_callback(instrumented->callback);

  // Enqueue the callback and wait for it to block on main thread
  std::string result = "This was called on the jni";
  Mod()->call_callback_on_jni(result);
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.blocking_future.wait_for(std::chrono::seconds(1)));
  log::info("Waiting");

  // Enqueue something else on the main thread and verify that it hasn't run
  static auto second_task_promise = std::promise<void>();
  auto final_future = second_task_promise.get_future();
  do_in_main_thread(common::BindOnce(
          [](std::promise<void> promise) {
            promise.set_value();
            log::info("Finally");
          },
          std::move(second_task_promise)));
  ASSERT_EQ(std::future_status::timeout, final_future.wait_for(std::chrono::milliseconds(1)));

  // Let the callback start
  instrumented->promises.unblock.set_value();
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.result_future.wait_for(std::chrono::seconds(1)));
  ASSERT_EQ(result, instrumented->promises.result_future.get());

  // Let the second task finish
  ASSERT_EQ(std::future_status::ready, final_future.wait_for(std::chrono::seconds(1)));
}

TEST_F(ModuleGdxWithInstrumentedCallback, test_call_once_callback_on_jni) {
  auto instrumented = GetNewOnceCallbackOnMain();
  Mod()->set_once_callback(std::move(instrumented->callback));

  // Enqueue the callback and wait for it to block on main thread
  std::string result = "This was called on the jni";
  Mod()->call_once_callback_on_jni(result);
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.blocking_future.wait_for(std::chrono::seconds(1)));
  log::info("Waiting");

  // Enqueue something else on the main thread and verify that it hasn't run
  static auto second_task_promise = std::promise<void>();
  auto final_future = second_task_promise.get_future();
  do_in_main_thread(common::BindOnce(
          [](std::promise<void> promise) {
            promise.set_value();
            log::info("Finally");
          },
          std::move(second_task_promise)));
  ASSERT_EQ(std::future_status::timeout, final_future.wait_for(std::chrono::milliseconds(1)));

  // Let the callback start
  instrumented->promises.unblock.set_value();
  ASSERT_EQ(std::future_status::ready,
            instrumented->promises.result_future.wait_for(std::chrono::seconds(1)));
  ASSERT_EQ(result, instrumented->promises.result_future.get());

  // Let the second task finish
  ASSERT_EQ(std::future_status::ready, final_future.wait_for(std::chrono::seconds(1)));
}
