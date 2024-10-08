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

#include "btcore/include/property.h"

#include <arpa/inet.h>
#include <gtest/gtest.h>

#include "types/bluetooth/uuid.h"
#include "types/raw_address.h"

using bluetooth::Uuid;

class PropertyTest : public ::testing::Test {};

TEST_F(PropertyTest, addr) {
  RawAddress addr0 = {{0x1, 0x2, 0x3, 0x4, 0x5, 0x6}};
  bt_property_t* property = property_new_addr(&addr0);

  EXPECT_EQ(addr0.address[0], ((uint8_t*)property->val)[0]);
  EXPECT_EQ(addr0.address[1], ((uint8_t*)property->val)[1]);
  EXPECT_EQ(addr0.address[2], ((uint8_t*)property->val)[2]);
  EXPECT_EQ(addr0.address[3], ((uint8_t*)property->val)[3]);
  EXPECT_EQ(addr0.address[4], ((uint8_t*)property->val)[4]);
  EXPECT_EQ(addr0.address[5], ((uint8_t*)property->val)[5]);
  EXPECT_EQ(BT_PROPERTY_BDADDR, property->type);
  EXPECT_EQ((int)sizeof(RawAddress), property->len);

  const RawAddress* addr1 = property_as_addr(property);
  EXPECT_EQ(addr0.address[0], addr1->address[0]);

  property_free(property);
}

TEST_F(PropertyTest, device_class) {
  bt_device_class_t dc0 = {{0x01, 0x23, 0x45}};
  bt_property_t* property = property_new_device_class(&dc0);

  EXPECT_EQ(dc0._[0], ((uint8_t*)property->val)[0]);
  EXPECT_EQ(dc0._[1], ((uint8_t*)property->val)[1]);
  EXPECT_EQ(dc0._[2], ((uint8_t*)property->val)[2]);
  EXPECT_EQ(BT_PROPERTY_CLASS_OF_DEVICE, property->type);
  EXPECT_EQ((int)sizeof(bt_device_class_t), property->len);

  const bt_device_class_t* dc1 = property_as_device_class(property);
  int dc_int = device_class_to_int(dc1);
  EXPECT_EQ(0x452301, dc_int);

  property_free(property);
}

TEST_F(PropertyTest, device_type) {
  bt_device_type_t dt0 = (bt_device_type_t)1;
  bt_property_t* property = property_new_device_type(dt0);

  EXPECT_EQ((int)dt0, *(int*)property->val);
  EXPECT_EQ(BT_PROPERTY_TYPE_OF_DEVICE, property->type);
  EXPECT_EQ((int)sizeof(bt_device_type_t), property->len);

  bt_device_type_t dt1 = property_as_device_type(property);
  EXPECT_EQ(1, (int)dt1);

  property_free(property);
}

TEST_F(PropertyTest, discovery_timeout) {
  uint32_t timeout0 = 12345;
  bt_property_t* property = property_new_discoverable_timeout(timeout0);

  EXPECT_EQ(timeout0, *(uint32_t*)property->val);
  EXPECT_EQ(BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT, property->type);
  EXPECT_EQ((int)sizeof(uint32_t), property->len);

  uint32_t timeout1 = property_as_discoverable_timeout(property);
  EXPECT_EQ(timeout0, timeout1);

  property_free(property);
}

TEST_F(PropertyTest, name) {
  const char* name0 = "My btcore name";
  bt_property_t* property = property_new_name(name0);

  EXPECT_EQ(0, strcmp((char*)name0, (char*)property->val));
  EXPECT_EQ(BT_PROPERTY_BDNAME, property->type);
  EXPECT_EQ((int)sizeof(bt_bdname_t), property->len);

  const bt_bdname_t* name1 = property_as_name(property);
  EXPECT_EQ(0, strcmp((char*)name0, (char*)name1->name));

  property_free(property);
}

TEST_F(PropertyTest, rssi) {
  int8_t rssi0 = -56;
  bt_property_t* property = property_new_rssi(rssi0);

  EXPECT_EQ(*(int8_t*)property->val, rssi0);
  EXPECT_EQ(BT_PROPERTY_REMOTE_RSSI, property->type);
  EXPECT_EQ((int)sizeof(int8_t), property->len);

  int8_t rss1 = property_as_rssi(property);
  EXPECT_EQ(rssi0, rss1);

  property_free(property);
}

TEST_F(PropertyTest, uuids) {
  Uuid uuid0 = Uuid::From128BitBE({{
          0x00,
          0x11,
          0x22,
          0x33,
          0x44,
          0x55,
          0x66,
          0x77,
          0x88,
          0x99,
          0xaa,
          0xbb,
          0xcc,
          0xdd,
          0xee,
          0xff,
  }});
  bt_property_t* property = property_new_uuids(&uuid0, 1);

  EXPECT_EQ(0, memcmp(uuid0.To128BitBE().data(), property->val, sizeof(Uuid)));
  EXPECT_EQ(BT_PROPERTY_UUIDS, property->type);
  EXPECT_EQ((int)sizeof(Uuid), property->len);

  size_t uuid_cnt1;
  const Uuid* uuid1 = property_as_uuids(property, &uuid_cnt1);
  EXPECT_EQ(uuid0, *uuid1);

  property_free(property);
}

TEST_F(PropertyTest, copy) {
  {
    Uuid uuids[] = {
            Uuid::From128BitBE({{
                    0x00,
                    0x11,
                    0x22,
                    0x33,
                    0x44,
                    0x55,
                    0x66,
                    0x77,
                    0x88,
                    0x99,
                    0xaa,
                    0xbb,
                    0xcc,
                    0xdd,
                    0xee,
                    0xff,
            }}),
            Uuid::From128BitBE({{
                    0xf0,
                    0xe1,
                    0xd2,
                    0xc3,
                    0xf4,
                    0xe5,
                    0xd6,
                    0xc7,
                    0xf8,
                    0xe9,
                    0xda,
                    0xcb,
                    0xfc,
                    0xed,
                    0xde,
                    0xcf,
            }}),
    };

    bt_property_t* property0 = property_new_uuids(uuids, sizeof(uuids) / sizeof(Uuid));

    bt_property_t property1;
    property_copy(&property1, property0);
    EXPECT_TRUE(property_equals(property0, &property1));

    property_free(property0);
  }
}

TEST_F(PropertyTest, equals) {
  {
    RawAddress addr0 = {{0x1, 0x2, 0x3, 0x4, 0x5, 0x6}};
    bt_property_t* property0 = property_new_addr(&addr0);

    bt_device_class_t dc0 = {{0x01, 0x23, 0x45}};
    bt_property_t* property1 = property_new_device_class(&dc0);

    EXPECT_FALSE(property_equals(property0, property1));

    property_free(property0);
    property_free(property1);
  }

  {
    RawAddress addr = {{0x1, 0x2, 0x3, 0x4, 0x5, 0x6}};
    bt_property_t* property0 = property_new_addr(&addr);
    bt_property_t* property1 = property_new_addr(&addr);

    EXPECT_TRUE(property_equals(property0, property1));

    property_free(property0);
    property_free(property1);
  }

  {
    RawAddress addr0 = {{0x1, 0x2, 0x3, 0x4, 0x5, 0x6}};
    bt_property_t* property0 = property_new_addr(&addr0);

    RawAddress addr1 = {{0x1, 0x2, 0x3, 0x4, 0x5, 0xff}};
    bt_property_t* property1 = property_new_addr(&addr1);

    EXPECT_FALSE(property_equals(property0, property1));

    property_free(property0);
    property_free(property1);
  }

  {
    const char* name0 = "My btcore name";
    bt_property_t* property0 = property_new_name(name0);

    const char* name1 = "My btcore name";
    bt_property_t* property1 = property_new_name(name1);

    EXPECT_TRUE(property_equals(property0, property1));

    property_free(property0);
    property_free(property1);
  }

  {
    const char* name0 = "My btcore name";
    bt_property_t* property0 = property_new_name(name0);

    const char* name1 = "My btcore name     ";
    bt_property_t* property1 = property_new_name(name1);

    EXPECT_FALSE(property_equals(property0, property1));

    property_free(property0);
    property_free(property1);
  }
}
