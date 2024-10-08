/******************************************************************************
 *
 *  Copyright 2019 The Android Open Source Project
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

#include "hci/address_with_type.h"

#include <gtest/gtest.h>

#include <map>
#include <unordered_map>

#include "hci/address.h"
#include "hci/hci_packets.h"
#include "hci/octets.h"

using namespace bluetooth;

namespace bluetooth {
namespace hci {

TEST(AddressWithTypeTest, AddressWithTypeSameValueSameOrder) {
  Address addr1{{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  AddressType type1 = AddressType::PUBLIC_DEVICE_ADDRESS;
  AddressWithType address_with_type_1(addr1, type1);
  Address addr2{{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  AddressType type2 = AddressType::PUBLIC_DEVICE_ADDRESS;
  AddressWithType address_with_type_2(addr2, type2);
  // Test if two address with type with same byte value have the same hash
  struct std::hash<bluetooth::hci::AddressWithType> hasher;
  EXPECT_EQ(hasher(address_with_type_1), hasher(address_with_type_2));
  // Test if two address with type with the same hash and the same value, they will
  // still map to the same value
  std::unordered_map<AddressWithType, int> data = {};
  data[address_with_type_1] = 5;
  data[address_with_type_2] = 8;
  EXPECT_EQ(data[address_with_type_1], data[address_with_type_2]);
}

TEST(AddressWithTypeTest, HashDifferentDiffAddrSameType) {
  Address addr{{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  AddressType type = AddressType::PUBLIC_IDENTITY_ADDRESS;
  AddressWithType address_with_type(addr, type);
  struct std::hash<AddressWithType> hasher;
  EXPECT_NE(hasher(address_with_type),
            hasher(AddressWithType(Address::kEmpty, AddressType::PUBLIC_IDENTITY_ADDRESS)));
}

TEST(AddressWithTypeTest, HashDifferentSameAddressDiffType) {
  Address addr1{{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  AddressType type1 = AddressType::PUBLIC_DEVICE_ADDRESS;
  AddressWithType address_with_type_1(addr1, type1);
  Address addr2{{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}};
  AddressType type2 = AddressType::PUBLIC_IDENTITY_ADDRESS;
  AddressWithType address_with_type_2(addr2, type2);
  struct std::hash<bluetooth::hci::AddressWithType> hasher;
  EXPECT_NE(hasher(address_with_type_1), hasher(address_with_type_2));
}

TEST(AddressWithTypeTest, IsRpa) {
  // Public address can't be RPA
  EXPECT_FALSE(AddressWithType(Address{{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}},
                               AddressType::PUBLIC_IDENTITY_ADDRESS)
                       .IsRpa());

  // Must have proper Most Significant Bit configuration
  EXPECT_FALSE(AddressWithType(Address{{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}},
                               AddressType::RANDOM_DEVICE_ADDRESS)
                       .IsRpa());
  EXPECT_TRUE(AddressWithType(Address{{0x01, 0x02, 0x03, 0x04, 0x05, 0x40}},
                              AddressType::RANDOM_DEVICE_ADDRESS)
                      .IsRpa());
  EXPECT_TRUE(AddressWithType(Address{{0x01, 0x02, 0x03, 0x04, 0x05, 0x50}},
                              AddressType::RANDOM_DEVICE_ADDRESS)
                      .IsRpa());
  EXPECT_TRUE(AddressWithType(Address{{0x01, 0x02, 0x03, 0x04, 0x05, 0x60}},
                              AddressType::RANDOM_DEVICE_ADDRESS)
                      .IsRpa());
  EXPECT_TRUE(AddressWithType(Address{{0x01, 0x02, 0x03, 0x04, 0x05, 0x70}},
                              AddressType::RANDOM_DEVICE_ADDRESS)
                      .IsRpa());
  EXPECT_FALSE(AddressWithType(Address{{0x01, 0x02, 0x03, 0x04, 0x05, 0x80}},
                               AddressType::RANDOM_DEVICE_ADDRESS)
                       .IsRpa());
}

TEST(AddressWithTypeTest, IsRpaThatMatchesIrk) {
  AddressWithType address_1 = AddressWithType(Address{{0xDE, 0x12, 0xC9, 0x03, 0x02, 0x50}},
                                              AddressType::RANDOM_DEVICE_ADDRESS);
  AddressWithType address_2 = AddressWithType(Address{{0xDD, 0x12, 0xC9, 0x03, 0x02, 0x50}},
                                              AddressType::RANDOM_DEVICE_ADDRESS);
  Octet16 irk_1{0x90, 0x5e, 0x60, 0x59, 0xc9, 0x11, 0x43, 0x7b,
                0x04, 0x09, 0x6a, 0x53, 0x28, 0xe6, 0x59, 0x6d};

  EXPECT_TRUE(address_1.IsRpaThatMatchesIrk(irk_1));
  EXPECT_FALSE(address_2.IsRpaThatMatchesIrk(irk_1));
}

TEST(AddressWithTypeTest, OperatorLessThan) {
  {
    AddressWithType address_1 = AddressWithType(Address{{0x50, 0x02, 0x03, 0xC9, 0x12, 0xDE}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x50, 0x02, 0x03, 0xC9, 0x12, 0xDD}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);

    ASSERT_TRUE(address_2 < address_1);
  }

  {
    AddressWithType address_1 = AddressWithType(Address{{0x50, 0x02, 0x03, 0xC9, 0x12, 0xDE}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x70, 0x02, 0x03, 0xC9, 0x12, 0xDE}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);

    ASSERT_TRUE(address_1 < address_2);
  }

  {
    AddressWithType address_1 = AddressWithType(Address{{0x50, 0x02, 0x03, 0xC9, 0x12, 0xDE}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x70, 0x02, 0x03, 0xC9, 0x12, 0xDD}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);

    ASSERT_TRUE(address_1 < address_2);
  }

  {
    AddressWithType address_1 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);

    ASSERT_TRUE(address_1 < address_2);
  }

  {
    AddressWithType address_1 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);

    ASSERT_FALSE(address_1 < address_2);
  }
}

TEST(AddressWithTypeTest, OrderedMap) {
  std::map<AddressWithType, int> map;

  {
    AddressWithType address_1 = AddressWithType(Address{{0x50, 0x02, 0x03, 0xC9, 0x12, 0xDE}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x70, 0x02, 0x03, 0xC9, 0x12, 0xDD}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);

    map[address_1] = 1;
    map[address_2] = 2;

    ASSERT_EQ(2UL, map.size());
    map.clear();
  }

  {
    AddressWithType address_1 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);

    map[address_1] = 1;
    map[address_2] = 2;

    ASSERT_EQ(2UL, map.size());
    map.clear();
  }

  {
    AddressWithType address_1 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);

    map[address_1] = 1;
    map[address_2] = 2;

    ASSERT_EQ(1UL, map.size());
    map.clear();
  }
}

TEST(AddressWithTypeTest, HashMap) {
  std::unordered_map<AddressWithType, int> map;

  {
    AddressWithType address_1 = AddressWithType(Address{{0x50, 0x02, 0x03, 0xC9, 0x12, 0xDE}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x70, 0x02, 0x03, 0xC9, 0x12, 0xDD}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);

    map[address_1] = 1;
    map[address_2] = 2;

    ASSERT_EQ(2UL, map.size());
    map.clear();
  }

  {
    AddressWithType address_1 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::RANDOM_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);

    map[address_1] = 1;
    map[address_2] = 2;

    ASSERT_EQ(2UL, map.size());
    map.clear();
  }

  {
    AddressWithType address_1 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);
    AddressWithType address_2 = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                AddressType::PUBLIC_DEVICE_ADDRESS);

    map[address_1] = 1;
    map[address_2] = 2;

    ASSERT_EQ(1UL, map.size());
    map.clear();
  }
}

TEST(AddressWithTypeTest, ToFilterAcceptListAddressType) {
  {
    AddressWithType address = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                              AddressType::PUBLIC_DEVICE_ADDRESS);
    ASSERT_EQ(hci::FilterAcceptListAddressType::PUBLIC, address.ToFilterAcceptListAddressType());
  }

  {
    AddressWithType address = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                              AddressType::PUBLIC_IDENTITY_ADDRESS);
    ASSERT_EQ(hci::FilterAcceptListAddressType::PUBLIC, address.ToFilterAcceptListAddressType());
  }

  {
    AddressWithType address = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                              AddressType::RANDOM_DEVICE_ADDRESS);
    ASSERT_EQ(hci::FilterAcceptListAddressType::RANDOM, address.ToFilterAcceptListAddressType());
  }

  {
    AddressWithType address = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                              AddressType::RANDOM_IDENTITY_ADDRESS);
    ASSERT_EQ(hci::FilterAcceptListAddressType::RANDOM, address.ToFilterAcceptListAddressType());
  }
}

TEST(AddressWithTypeTest, ToPeerAddressType) {
  {
    AddressWithType address = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                              AddressType::PUBLIC_DEVICE_ADDRESS);
    ASSERT_EQ(hci::PeerAddressType::PUBLIC_DEVICE_OR_IDENTITY_ADDRESS, address.ToPeerAddressType());
  }

  {
    AddressWithType address = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                              AddressType::PUBLIC_IDENTITY_ADDRESS);
    ASSERT_EQ(hci::PeerAddressType::PUBLIC_DEVICE_OR_IDENTITY_ADDRESS, address.ToPeerAddressType());
  }

  {
    AddressWithType address = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                              AddressType::RANDOM_DEVICE_ADDRESS);
    ASSERT_EQ(hci::PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS, address.ToPeerAddressType());
  }

  {
    AddressWithType address = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                              AddressType::RANDOM_IDENTITY_ADDRESS);
    ASSERT_EQ(hci::PeerAddressType::RANDOM_DEVICE_OR_IDENTITY_ADDRESS, address.ToPeerAddressType());
  }
}

TEST(AddressWithTypeTest, StringStream) {
  AddressWithType address_with_type = AddressWithType(Address{{0x11, 0x22, 0x33, 0x44, 0x55, 0x66}},
                                                      AddressType::PUBLIC_DEVICE_ADDRESS);

  std::stringstream oss;
  oss << address_with_type;
  ASSERT_STREQ("66:55:44:33:22:11[PUBLIC_DEVICE_ADDRESS(0x00)]", oss.str().c_str());
}

}  // namespace hci
}  // namespace bluetooth
