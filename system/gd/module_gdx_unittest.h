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

#include <base/callback.h>
#include <base/functional/bind.h>
#include <base/location.h>

#include <string>

#include "common/contextual_callback.h"
#include "module.h"
#include "module_jniloop.h"
#include "module_mainloop.h"

using namespace bluetooth;

void external_function(int /* a */, double /* b */, char /* c */);

class TestGdxModule : public Module, public ModuleMainloop, public ModuleJniloop {
public:
  void call_on_handler_protected_method(pid_t tid, int a, int b, int c);
  void call_on_main_external_function(pid_t tid, int a, double b, char c);
  void call_on_main(pid_t tid, int a, int b, int c);
  void call_on_main_repost(pid_t tid, int a, int b, int c);
  void call_on_main_recurse(pid_t tid, int a, int b, int c);

  void call_on_jni_external_function(pid_t tid, int a, double b, char c);
  void call_on_jni(pid_t tid, int a, int b, int c);
  void call_on_jni_repost(pid_t tid, int a, int b, int c);
  void call_on_jni_recurse(pid_t tid, int a, int b, int c);

  void set_callback(common::ContextualCallback<void(std::string)> one_message_callback);
  void set_once_callback(common::ContextualOnceCallback<void(std::string)> one_message_callback);

  void call_callback_on_handler(std::string message);
  void call_once_callback_on_handler(std::string message);
  void call_callback_on_jni(std::string message);
  void call_once_callback_on_jni(std::string message);
  void call_callback_on_main(std::string message);
  void call_once_callback_on_main(std::string message);

  static const bluetooth::ModuleFactory Factory;

protected:
  void protected_method(int a, int b, int c);
  void call_on_main_internal(int a, int b, int c);
  void call_on_jni_internal(int a, int b, int c);
  bool IsStarted() const;

  void ListDependencies(bluetooth::ModuleList* /* list */) const override {}
  void Start() override;
  void Stop() override;
  std::string ToString() const override;

private:
  struct PrivateImpl;
  std::shared_ptr<TestGdxModule::PrivateImpl> pimpl_;

  common::ContextualOnceCallback<void(std::string)> call_once_;
  common::ContextualCallback<void(std::string)> call_many_;

  bool started_ = false;

  friend bluetooth::ModuleRegistry;
};
