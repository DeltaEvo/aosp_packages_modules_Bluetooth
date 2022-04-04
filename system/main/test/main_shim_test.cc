/*
 *  Copyright 2021 The Android Open Source Project
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
 */

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <cstddef>
#include <future>
#include <map>

#include "btaa/activity_attribution.h"
#include "btif/include/btif_hh.h"
#include "device/include/controller.h"
#include "hal/hci_hal.h"
#include "hci/acl_manager.h"
#include "hci/acl_manager/classic_acl_connection.h"
#include "hci/acl_manager/connection_callbacks.h"
#include "hci/acl_manager/connection_management_callbacks.h"
#include "hci/acl_manager/le_acl_connection.h"
#include "hci/acl_manager/le_connection_callbacks.h"
#include "hci/acl_manager/le_connection_management_callbacks.h"
#include "hci/acl_manager_mock.h"
#include "hci/controller_mock.h"
#include "hci/le_advertising_manager_mock.h"
#include "hci/le_scanning_manager_mock.h"
#include "include/hardware/ble_scanner.h"
#include "include/hardware/bt_activity_attribution.h"
#include "main/shim/acl.h"
#include "main/shim/acl_legacy_interface.h"
#include "main/shim/ble_scanner_interface_impl.h"
#include "main/shim/helpers.h"
#include "main/shim/le_advertising_manager.h"
#include "main/shim/le_scanning_manager.h"
#include "os/handler.h"
#include "os/mock_queue.h"
#include "os/queue.h"
#include "os/thread.h"
#include "packet/packet_view.h"
#include "stack/btm/btm_int_types.h"
#include "stack/include/bt_hdr.h"
#include "stack/include/hci_error_code.h"
#include "stack/l2cap/l2c_int.h"
#include "test/common/jni_thread.h"
#include "test/common/main_handler.h"
#include "test/common/mock_functions.h"
#include "test/mock/mock_main_shim_entry.h"
#include "types/ble_address_with_type.h"
#include "types/hci_role.h"
#include "types/raw_address.h"

using ::testing::_;

using namespace bluetooth;
using namespace testing;

namespace test = bluetooth::hci::testing;

const uint8_t kMaxLeAcceptlistSize = 16;
const uint8_t kMaxAddressResolutionSize = kMaxLeAcceptlistSize;

tL2C_CB l2cb;
tBTM_CB btm_cb;
btif_hh_cb_t btif_hh_cb;

struct bluetooth::hci::LeScanningManager::impl
    : public bluetooth::hci::LeAddressManagerCallback {};

namespace {
std::map<std::string, std::promise<uint16_t>> mock_function_handle_promise_map;
}

uint8_t mock_get_ble_acceptlist_size() { return 123; }

struct controller_t mock_controller {
  .get_ble_acceptlist_size = mock_get_ble_acceptlist_size,
};

const controller_t* controller_get_interface() { return &mock_controller; }

void mock_on_send_data_upwards(BT_HDR*) { mock_function_count_map[__func__]++; }

void mock_on_packets_completed(uint16_t handle, uint16_t num_packets) {
  mock_function_count_map[__func__]++;
}

void mock_connection_classic_on_connected(const RawAddress& bda,
                                          uint16_t handle, uint8_t enc_mode) {
  mock_function_count_map[__func__]++;
}

void mock_connection_classic_on_failed(const RawAddress& bda,
                                       tHCI_STATUS status) {
  mock_function_count_map[__func__]++;
}

void mock_connection_classic_on_disconnected(tHCI_STATUS status,
                                             uint16_t handle,
                                             tHCI_STATUS reason) {
  mock_function_count_map[__func__]++;
  ASSERT_TRUE(mock_function_handle_promise_map.find(__func__) !=
              mock_function_handle_promise_map.end());
  mock_function_handle_promise_map[__func__].set_value(handle);
}
void mock_connection_le_on_connected(
    const tBLE_BD_ADDR& address_with_type, uint16_t handle, tHCI_ROLE role,
    uint16_t conn_interval, uint16_t conn_latency, uint16_t conn_timeout,
    const RawAddress& local_rpa, const RawAddress& peer_rpa,
    tBLE_ADDR_TYPE peer_addr_type) {
  mock_function_count_map[__func__]++;
}
void mock_connection_le_on_failed(const tBLE_BD_ADDR& address_with_type,
                                  uint16_t handle, bool enhanced,
                                  tHCI_STATUS status) {
  mock_function_count_map[__func__]++;
}
void mock_connection_le_on_disconnected(tHCI_STATUS status, uint16_t handle,
                                        tHCI_STATUS reason) {
  mock_function_count_map[__func__]++;
}

const shim::legacy::acl_interface_t GetMockAclInterface() {
  shim::legacy::acl_interface_t acl_interface{
      .on_send_data_upwards = mock_on_send_data_upwards,
      .on_packets_completed = mock_on_packets_completed,

      .connection.classic.on_connected = mock_connection_classic_on_connected,
      .connection.classic.on_failed = mock_connection_classic_on_failed,
      .connection.classic.on_disconnected =
          mock_connection_classic_on_disconnected,

      .connection.le.on_connected = mock_connection_le_on_connected,
      .connection.le.on_failed = mock_connection_le_on_failed,
      .connection.le.on_disconnected = mock_connection_le_on_disconnected,

      .connection.sco.on_esco_connect_request = nullptr,
      .connection.sco.on_sco_connect_request = nullptr,
      .connection.sco.on_disconnected = nullptr,

      .link.classic.on_authentication_complete = nullptr,
      .link.classic.on_central_link_key_complete = nullptr,
      .link.classic.on_change_connection_link_key_complete = nullptr,
      .link.classic.on_encryption_change = nullptr,
      .link.classic.on_flow_specification_complete = nullptr,
      .link.classic.on_flush_occurred = nullptr,
      .link.classic.on_mode_change = nullptr,
      .link.classic.on_packet_type_changed = nullptr,
      .link.classic.on_qos_setup_complete = nullptr,
      .link.classic.on_read_afh_channel_map_complete = nullptr,
      .link.classic.on_read_automatic_flush_timeout_complete = nullptr,
      .link.classic.on_sniff_subrating = nullptr,
      .link.classic.on_read_clock_complete = nullptr,
      .link.classic.on_read_clock_offset_complete = nullptr,
      .link.classic.on_read_failed_contact_counter_complete = nullptr,
      .link.classic.on_read_link_policy_settings_complete = nullptr,
      .link.classic.on_read_link_quality_complete = nullptr,
      .link.classic.on_read_link_supervision_timeout_complete = nullptr,
      .link.classic.on_read_remote_version_information_complete = nullptr,
      .link.classic.on_read_remote_extended_features_complete = nullptr,
      .link.classic.on_read_rssi_complete = nullptr,
      .link.classic.on_read_transmit_power_level_complete = nullptr,
      .link.classic.on_role_change = nullptr,
      .link.classic.on_role_discovery_complete = nullptr,

      .link.le.on_connection_update = nullptr,
      .link.le.on_data_length_change = nullptr,
      .link.le.on_read_remote_version_information_complete = nullptr,
  };
  return acl_interface;
}

struct hci_packet_parser_t;
const hci_packet_parser_t* hci_packet_parser_get_interface() { return nullptr; }
struct hci_t;
const hci_t* hci_layer_get_interface() { return nullptr; }
struct packet_fragmenter_t;
const packet_fragmenter_t* packet_fragmenter_get_interface() { return nullptr; }
void LogMsg(uint32_t trace_set_mask, const char* fmt_str, ...) {}

template <typename T>
class MockEnQueue : public os::IQueueEnqueue<T> {
  using EnqueueCallback = base::Callback<std::unique_ptr<T>()>;

  void RegisterEnqueue(os::Handler* handler,
                       EnqueueCallback callback) override {}
  void UnregisterEnqueue() override {}
};

template <typename T>
class MockDeQueue : public os::IQueueDequeue<T> {
  using DequeueCallback = base::Callback<void()>;

  void RegisterDequeue(os::Handler* handler,
                       DequeueCallback callback) override {}
  void UnregisterDequeue() override {}
  std::unique_ptr<T> TryDequeue() override { return nullptr; }
};

class MockClassicAclConnection
    : public bluetooth::hci::acl_manager::ClassicAclConnection {
 public:
  MockClassicAclConnection(const hci::Address& address, uint16_t handle) {
    address_ = address;  // ClassicAclConnection
    handle_ = handle;    // AclConnection
  }

  void RegisterCallbacks(
      hci::acl_manager::ConnectionManagementCallbacks* callbacks,
      os::Handler* handler) override {
    callbacks_ = callbacks;
    handler_ = handler;
  }

  // Returns the bidi queue for this mock connection
  AclConnection::QueueUpEnd* GetAclQueueEnd() const override {
    return &mock_acl_queue_;
  }

  mutable common::BidiQueueEnd<hci::BasePacketBuilder,
                               packet::PacketView<hci::kLittleEndian>>
      mock_acl_queue_{&tx_, &rx_};

  MockEnQueue<hci::BasePacketBuilder> tx_;
  MockDeQueue<packet::PacketView<hci::kLittleEndian>> rx_;

  bool ReadRemoteVersionInformation() override { return true; }
  bool ReadRemoteSupportedFeatures() override { return true; }

  bool Disconnect(hci::DisconnectReason reason) override {
    disconnect_cnt_++;
    disconnect_promise_.set_value(handle_);
    return true;
  }

  std::promise<uint16_t> disconnect_promise_;

  hci::acl_manager::ConnectionManagementCallbacks* callbacks_{nullptr};
  os::Handler* handler_{nullptr};

  int disconnect_cnt_{0};
};

namespace bluetooth {
namespace shim {
void init_activity_attribution() {}

namespace testing {
extern os::Handler* mock_handler_;

}  // namespace testing
}  // namespace shim

namespace activity_attribution {
ActivityAttributionInterface* get_activity_attribution_instance() {
  return nullptr;
}

const ModuleFactory ActivityAttribution::Factory =
    ModuleFactory([]() { return nullptr; });
}  // namespace activity_attribution

namespace hal {
const ModuleFactory HciHal::Factory = ModuleFactory([]() { return nullptr; });
}  // namespace hal

}  // namespace bluetooth

class MainShimTest : public testing::Test {
 public:
 protected:
  void SetUp() override {
    reset_mock_function_count_map();

    main_thread_start_up();

    thread_ = new os::Thread("acl_thread", os::Thread::Priority::NORMAL);
    handler_ = new os::Handler(thread_);

    /* extern */ test::mock_controller_ =
        new bluetooth::hci::testing::MockController();
    /* extern */ test::mock_acl_manager_ =
        new bluetooth::hci::testing::MockAclManager();
    /* extern */ test::mock_le_scanning_manager_ =
        new bluetooth::hci::testing::MockLeScanningManager();
    /* extern */ test::mock_le_advertising_manager_ =
        new bluetooth::hci::testing::MockLeAdvertisingManager();
  }
  void TearDown() override {
    delete test::mock_controller_;
    test::mock_controller_ = nullptr;
    delete test::mock_acl_manager_;
    test::mock_acl_manager_ = nullptr;
    delete test::mock_le_advertising_manager_;
    test::mock_le_advertising_manager_ = nullptr;
    delete test::mock_le_scanning_manager_;
    test::mock_le_scanning_manager_ = nullptr;

    handler_->Clear();
    delete handler_;
    delete thread_;

    main_thread_shut_down();
  }
  os::Thread* thread_{nullptr};
  os::Handler* handler_{nullptr};

  // Convenience method to create ACL objects
  std::unique_ptr<shim::legacy::Acl> MakeAcl() {
    EXPECT_CALL(*test::mock_acl_manager_, RegisterCallbacks(_, _)).Times(1);
    EXPECT_CALL(*test::mock_acl_manager_, RegisterLeCallbacks(_, _)).Times(1);
    EXPECT_CALL(*test::mock_controller_,
                RegisterCompletedMonitorAclPacketsCallback(_))
        .Times(1);
    EXPECT_CALL(*test::mock_acl_manager_, HACK_SetNonAclDisconnectCallback(_))
        .Times(1);
    EXPECT_CALL(*test::mock_controller_,
                UnregisterCompletedMonitorAclPacketsCallback)
        .Times(1);
    return std::make_unique<shim::legacy::Acl>(handler_, GetMockAclInterface(),
                                               kMaxLeAcceptlistSize,
                                               kMaxAddressResolutionSize);
  }
};

TEST_F(MainShimTest, Nop) {}

TEST_F(MainShimTest, Acl_Lifecycle) {
  auto acl = MakeAcl();
  acl.reset();
  acl = MakeAcl();
}

TEST_F(MainShimTest, helpers) {
  uint8_t reason = 0;
  do {
    hci::ErrorCode gd_error_code = static_cast<hci::ErrorCode>(reason);
    tHCI_STATUS legacy_code = ToLegacyHciErrorCode(gd_error_code);
    ASSERT_EQ(reason,
              static_cast<uint8_t>(ToLegacyHciErrorCode(gd_error_code)));
    ASSERT_EQ(reason, static_cast<uint8_t>(legacy_code));
  } while (++reason != 0);
}

TEST_F(MainShimTest, connect_and_disconnect) {
  hci::Address address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66});

  auto acl = MakeAcl();

  // Create connection
  EXPECT_CALL(*test::mock_acl_manager_, CreateConnection(_)).Times(1);
  acl->CreateClassicConnection(address);

  // Respond with a mock connection created
  auto connection = std::make_unique<MockClassicAclConnection>(address, 123);
  ASSERT_EQ(123, connection->GetHandle());
  ASSERT_EQ(hci::Address({0x11, 0x22, 0x33, 0x44, 0x55, 0x66}),
            connection->GetAddress());
  MockClassicAclConnection* raw_connection = connection.get();

  acl->OnConnectSuccess(std::move(connection));
  ASSERT_EQ(nullptr, connection);

  // Specify local disconnect request
  auto tx_disconnect_future = raw_connection->disconnect_promise_.get_future();
  acl->DisconnectClassic(123, HCI_SUCCESS, {});

  // Wait for disconnect to be received
  uint16_t result = tx_disconnect_future.get();
  ASSERT_EQ(123, result);

  // Now emulate the remote disconnect response
  auto handle_promise = std::promise<uint16_t>();
  auto rx_disconnect_future = handle_promise.get_future();
  mock_function_handle_promise_map["mock_connection_classic_on_disconnected"] =
      std::move(handle_promise);
  raw_connection->callbacks_->OnDisconnection(hci::ErrorCode::SUCCESS);

  result = rx_disconnect_future.get();
  ASSERT_EQ(123, result);

  // *Our* task completing indicates reactor is done
  std::promise<void> done;
  auto future = done.get_future();
  handler_->Call([](std::promise<void> done) { done.set_value(); },
                 std::move(done));
  future.wait();

  connection.reset();
}

TEST_F(MainShimTest, is_flushable) {
  {
    alignas(BT_HDR)
        std::byte hdr_data[sizeof(BT_HDR) + sizeof(HciDataPreamble)]{};
    BT_HDR* bt_hdr = reinterpret_cast<BT_HDR*>(hdr_data);

    ASSERT_TRUE(!IsPacketFlushable(bt_hdr));
    HciDataPreamble* hci = ToPacketData<HciDataPreamble>(bt_hdr);
    hci->SetFlushable();
    ASSERT_TRUE(IsPacketFlushable(bt_hdr));
  }

  {
    const size_t offset = 1024;
    alignas(BT_HDR)
        std::byte hdr_data[sizeof(BT_HDR) + sizeof(HciDataPreamble) + offset]{};
    BT_HDR* bt_hdr = reinterpret_cast<BT_HDR*>(hdr_data);

    ASSERT_TRUE(!IsPacketFlushable(bt_hdr));
    HciDataPreamble* hci = ToPacketData<HciDataPreamble>(bt_hdr);
    hci->SetFlushable();
    ASSERT_TRUE(IsPacketFlushable(bt_hdr));
  }

  {
    const size_t offset = 1024;
    alignas(BT_HDR)
        std::byte hdr_data[sizeof(BT_HDR) + sizeof(HciDataPreamble) + offset]{};
    BT_HDR* bt_hdr = reinterpret_cast<BT_HDR*>(hdr_data);

    uint8_t* p = ToPacketData<uint8_t>(bt_hdr, L2CAP_SEND_CMD_OFFSET);
    UINT16_TO_STREAM(
        p, 0x123 | (L2CAP_PKT_START_NON_FLUSHABLE << L2CAP_PKT_TYPE_SHIFT));
    ASSERT_TRUE(!IsPacketFlushable(bt_hdr));

    p = ToPacketData<uint8_t>(bt_hdr, L2CAP_SEND_CMD_OFFSET);
    UINT16_TO_STREAM(p, 0x123 | (L2CAP_PKT_START << L2CAP_PKT_TYPE_SHIFT));
    ASSERT_TRUE(IsPacketFlushable(bt_hdr));
  }
}

TEST_F(MainShimTest, BleScannerInterfaceImpl_nop) {
  auto* ble = static_cast<bluetooth::shim::BleScannerInterfaceImpl*>(
      bluetooth::shim::get_ble_scanner_instance());
  ASSERT_NE(nullptr, ble);
}

class TestScanningCallbacks : public ::ScanningCallbacks {
 public:
  ~TestScanningCallbacks() {}
  void OnScannerRegistered(const bluetooth::Uuid app_uuid, uint8_t scannerId,
                           uint8_t status) override {}
  void OnSetScannerParameterComplete(uint8_t scannerId,
                                     uint8_t status) override {}
  void OnScanResult(uint16_t event_type, uint8_t addr_type, RawAddress bda,
                    uint8_t primary_phy, uint8_t secondary_phy,
                    uint8_t advertising_sid, int8_t tx_power, int8_t rssi,
                    uint16_t periodic_adv_int,
                    std::vector<uint8_t> adv_data) override {}
  void OnTrackAdvFoundLost(
      AdvertisingTrackInfo advertising_track_info) override {}
  void OnBatchScanReports(int client_if, int status, int report_format,
                          int num_records, std::vector<uint8_t> data) override {
  }
  void OnBatchScanThresholdCrossed(int client_if) override {}
};

TEST_F(MainShimTest, BleScannerInterfaceImpl_OnScanResult) {
  auto* ble = static_cast<bluetooth::shim::BleScannerInterfaceImpl*>(
      bluetooth::shim::get_ble_scanner_instance());

  EXPECT_CALL(*hci::testing::mock_le_scanning_manager_,
              RegisterScanningCallback(_))
      .Times(1);
  ;
  bluetooth::shim::init_scanning_manager();

  TestScanningCallbacks cb;
  ble->RegisterCallbacks(&cb);

  //  Simulate scan results from the lower layers
  for (int i = 0; i < 2048; i++) {
    uint16_t event_type = 0;
    uint8_t address_type = BLE_ADDR_ANONYMOUS;
    bluetooth::hci::Address address;
    uint8_t primary_phy = 0;
    uint8_t secondary_phy = 0;
    uint8_t advertising_sid = 0;
    int8_t tx_power = 0;
    int8_t rssi = 0;
    uint16_t periodic_advertising_interval = 0;
    std::vector<uint8_t> advertising_data;

    ble->OnScanResult(event_type, address_type, address, primary_phy,
                      secondary_phy, advertising_sid, tx_power, rssi,
                      periodic_advertising_interval, advertising_data);
  }

  ASSERT_EQ(2 * 2048UL, do_in_jni_thread_task_queue.size());
  ASSERT_EQ(0, mock_function_count_map["btm_ble_process_adv_addr"]);

  run_all_jni_thread_task();
}
