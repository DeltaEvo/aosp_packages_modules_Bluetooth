/*
 * Copyright 2020 The Android Open Source Project
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

#include "dumpsys/filter.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <list>
#include <queue>

#include "dumpsys/dumpsys_test_data.h"
#include "test_data/bar.h"
#include "test_data/baz.h"
#include "test_data/foo.h"
#include "test_data/qux.h"
#include "test_data/root.h"

using namespace bluetooth;

namespace testing {

class DumpsysFilterTest : public Test {
protected:
  void SetUp() override {
    test_data_classes_.push_back(std::make_unique<BarTestDataClass>());
    test_data_classes_.push_back(std::make_unique<BazTestDataClass>());
    test_data_classes_.push_back(std::make_unique<FooTestDataClass>());
    test_data_classes_.push_back(std::make_unique<QuxTestDataClass>());
  }

  void TearDown() override {}

  std::list<std::unique_ptr<DumpsysTestDataClass>> test_data_classes_;

  std::string PopulateTestSchema();
};

std::string DumpsysFilterTest::PopulateTestSchema() {
  flatbuffers::FlatBufferBuilder fb_builder(1024);

  auto string_private = fb_builder.CreateString("String private");
  auto string_opaque = fb_builder.CreateString("String opaque");
  auto string_anonymized = fb_builder.CreateString("String anonymized");
  auto string_any = fb_builder.CreateString("String any");

  std::queue<TableAddFunction> queue;
  for (auto& test_data_class : test_data_classes_) {
    queue.push(test_data_class->GetTable(fb_builder));
  }

  testing::DumpsysTestDataRootBuilder builder(fb_builder);

  builder.add_string_private(string_private);
  builder.add_string_opaque(string_opaque);
  builder.add_string_anonymized(string_anonymized);
  builder.add_string_any(string_any);

  builder.add_int_private(123);
  builder.add_int_opaque(456);
  builder.add_int_anonymized(789);
  builder.add_int_any(0xabc);

  while (!queue.empty()) {
    queue.front()(&builder);
    queue.pop();
  }
  fb_builder.Finish(builder.Finish());

  return std::string(fb_builder.GetBufferPointer(),
                     fb_builder.GetBufferPointer() + fb_builder.GetSize());
}

TEST_F(DumpsysFilterTest, filter_as_developer) {
  std::string dumpsys_data = PopulateTestSchema();
  dumpsys::ReflectionSchema reflection_schema(testing::GetBundledSchemaData());

  dumpsys::FilterSchema(reflection_schema, &dumpsys_data);

  const testing::DumpsysTestDataRoot* data_root = GetDumpsysTestDataRoot(dumpsys_data.data());

  ASSERT_TRUE(data_root->string_private()->str() == "String private");
  ASSERT_TRUE(data_root->string_opaque()->str() == "String opaque");
  ASSERT_TRUE(data_root->string_anonymized()->str() == "String anonymized");
  ASSERT_TRUE(data_root->string_any()->str() == "String any");

  ASSERT_TRUE(data_root->int_private() == 123);
  ASSERT_TRUE(data_root->int_opaque() == 456);
  ASSERT_TRUE(data_root->int_anonymized() == 789);
  ASSERT_TRUE(data_root->int_any() == 0xabc);

  ASSERT_EQ(nullptr, data_root->bar_module_data());

  const testing::FooTestSchema* foo = data_root->foo_module_data();

  ASSERT_EQ(123, foo->foo_int_private());
  ASSERT_EQ(123, foo->foo_int_opaque());
  ASSERT_EQ(123, foo->foo_int_anonymized());
  ASSERT_EQ(123, foo->foo_int_any());
  ASSERT_STREQ("123", foo->foo_int_string()->c_str());

  ASSERT_FLOAT_EQ(123.456, foo->foo_float_private());
  ASSERT_FLOAT_EQ(123.456, foo->foo_float_opaque());
  ASSERT_FLOAT_EQ(123.456, foo->foo_float_anonymized());
  ASSERT_FLOAT_EQ(123.456, foo->foo_float_any());
  ASSERT_STREQ("123.456", foo->foo_float_string()->c_str());
}

}  // namespace testing
