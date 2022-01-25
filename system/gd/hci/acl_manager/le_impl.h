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

#pragma once

#include <atomic>
#include <memory>

#include "common/bind.h"
#include "crypto_toolbox/crypto_toolbox.h"
#include "hci/acl_manager/assembler.h"
#include "hci/acl_manager/round_robin_scheduler.h"
#include "hci/le_address_manager.h"
#include "os/alarm.h"

using bluetooth::crypto_toolbox::Octet16;

namespace bluetooth {
namespace hci {
namespace acl_manager {

using common::BindOnce;

constexpr uint16_t kScanIntervalFast = 0x0060;    /* 30 ~ 60 ms (use 60)  = 96 *0.625 */
constexpr uint16_t kScanWindowFast = 0x0030;      /* 30 ms = 48 *0.625 */
constexpr uint16_t kScanWindow2mFast = 0x0018;    /* 15 ms = 24 *0.625 */
constexpr uint16_t kScanWindowCodedFast = 0x0018; /* 15 ms = 24 *0.625 */
constexpr uint16_t kScanIntervalSlow = 0x0800;    /* 1.28 s = 2048 *0.625 */
constexpr uint16_t kScanWindowSlow = 0x0030;      /* 30 ms = 48 *0.625 */
constexpr std::chrono::milliseconds kCreateConnectionTimeoutMs = std::chrono::milliseconds(30 * 1000);
constexpr uint8_t PHY_LE_NO_PACKET = 0x00;
constexpr uint8_t PHY_LE_1M = 0x01;
constexpr uint8_t PHY_LE_2M = 0x02;
constexpr uint8_t PHY_LE_CODED = 0x04;

struct le_acl_connection {
  le_acl_connection(AddressWithType remote_address, AclConnection::QueueDownEnd* queue_down_end, os::Handler* handler)
      : remote_address_(remote_address),
        assembler_(new acl_manager::assembler(remote_address, queue_down_end, handler)) {}
  ~le_acl_connection() {
    delete assembler_;
  }
  AddressWithType remote_address_;
  acl_manager::assembler* assembler_;
  LeConnectionManagementCallbacks* le_connection_management_callbacks_ = nullptr;
};

struct le_impl : public bluetooth::hci::LeAddressManagerCallback {
  le_impl(
      HciLayer* hci_layer,
      Controller* controller,
      os::Handler* handler,
      RoundRobinScheduler* round_robin_scheduler,
      bool crash_on_unknown_handle)
      : hci_layer_(hci_layer), controller_(controller), round_robin_scheduler_(round_robin_scheduler) {
    hci_layer_ = hci_layer;
    controller_ = controller;
    handler_ = handler;
    connections.crash_on_unknown_handle_ = crash_on_unknown_handle;
    le_acl_connection_interface_ = hci_layer_->GetLeAclConnectionInterface(
        handler_->BindOn(this, &le_impl::on_le_event),
        handler_->BindOn(this, &le_impl::on_le_disconnect),
        handler_->BindOn(this, &le_impl::on_le_read_remote_version_information));
    le_address_manager_ = new LeAddressManager(
        common::Bind(&le_impl::enqueue_command, common::Unretained(this)),
        handler_,
        controller->GetMacAddress(),
        controller->GetLeConnectListSize(),
        controller->GetLeResolvingListSize());
  }

  ~le_impl() {
    if (address_manager_registered) {
      le_address_manager_->Unregister(this);
    }
    delete le_address_manager_;
    hci_layer_->PutLeAclConnectionInterface();
    connections.reset();
  }

  void on_le_event(LeMetaEventView event_packet) {
    SubeventCode code = event_packet.GetSubeventCode();
    switch (code) {
      case SubeventCode::CONNECTION_COMPLETE:
        on_le_connection_complete(event_packet);
        break;
      case SubeventCode::ENHANCED_CONNECTION_COMPLETE:
        on_le_enhanced_connection_complete(event_packet);
        break;
      case SubeventCode::CONNECTION_UPDATE_COMPLETE:
        on_le_connection_update_complete(event_packet);
        break;
      case SubeventCode::PHY_UPDATE_COMPLETE:
        on_le_phy_update_complete(event_packet);
        break;
      case SubeventCode::DATA_LENGTH_CHANGE:
        on_data_length_change(event_packet);
        break;
      case SubeventCode::REMOTE_CONNECTION_PARAMETER_REQUEST:
        on_remote_connection_parameter_request(event_packet);
        break;
      default:
        LOG_ALWAYS_FATAL("Unhandled event code %s", SubeventCodeText(code).c_str());
    }
  }

 private:
  static constexpr uint16_t kIllegalConnectionHandle = 0xffff;
  struct {
   private:
    std::map<uint16_t, le_acl_connection> le_acl_connections_;
    mutable std::mutex le_acl_connections_guard_;
    LeConnectionManagementCallbacks* find_callbacks(uint16_t handle) {
      auto connection = le_acl_connections_.find(handle);
      if (connection == le_acl_connections_.end()) return nullptr;
      return connection->second.le_connection_management_callbacks_;
    }
    void remove(uint16_t handle) {
      auto connection = le_acl_connections_.find(handle);
      if (connection != le_acl_connections_.end()) {
        connection->second.le_connection_management_callbacks_ = nullptr;
        le_acl_connections_.erase(handle);
      }
    }

   public:
    bool crash_on_unknown_handle_ = false;
    bool is_empty() const {
      std::unique_lock<std::mutex> lock(le_acl_connections_guard_);
      return le_acl_connections_.empty();
    }
    void reset() {
      std::unique_lock<std::mutex> lock(le_acl_connections_guard_);
      le_acl_connections_.clear();
    }
    void invalidate(uint16_t handle) {
      std::unique_lock<std::mutex> lock(le_acl_connections_guard_);
      remove(handle);
    }
    void execute(
        uint16_t handle,
        std::function<void(LeConnectionManagementCallbacks* callbacks)> execute,
        bool remove_afterwards = false) {
      std::unique_lock<std::mutex> lock(le_acl_connections_guard_);
      auto callbacks = find_callbacks(handle);
      if (callbacks != nullptr)
        execute(callbacks);
      else
        ASSERT_LOG(!crash_on_unknown_handle_, "Received command for unknown handle:0x%x", handle);
      if (remove_afterwards) remove(handle);
    }
    bool send_packet_upward(uint16_t handle, std::function<void(struct acl_manager::assembler* assembler)> cb) {
      std::unique_lock<std::mutex> lock(le_acl_connections_guard_);
      auto connection = le_acl_connections_.find(handle);
      if (connection != le_acl_connections_.end()) cb(connection->second.assembler_);
      return connection != le_acl_connections_.end();
    }
    void add(
        uint16_t handle,
        const AddressWithType& remote_address,
        AclConnection::QueueDownEnd* queue_end,
        os::Handler* handler,
        LeConnectionManagementCallbacks* le_connection_management_callbacks) {
      std::unique_lock<std::mutex> lock(le_acl_connections_guard_);
      auto emplace_pair = le_acl_connections_.emplace(
          std::piecewise_construct,
          std::forward_as_tuple(handle),
          std::forward_as_tuple(remote_address, queue_end, handler));
      ASSERT(emplace_pair.second);  // Make sure the connection is unique
      emplace_pair.first->second.le_connection_management_callbacks_ = le_connection_management_callbacks;
    }
    uint16_t HACK_get_handle(Address address) const {
      std::unique_lock<std::mutex> lock(le_acl_connections_guard_);
      for (auto it = le_acl_connections_.begin(); it != le_acl_connections_.end(); it++) {
        if (it->second.remote_address_.GetAddress() == address) {
          return it->first;
        }
      }
      return kIllegalConnectionHandle;
    }
  } connections;

 public:
  void enqueue_command(std::unique_ptr<CommandBuilder> command_packet) {
    hci_layer_->EnqueueCommand(
        std::move(command_packet),
        handler_->BindOnce(&LeAddressManager::OnCommandComplete, common::Unretained(le_address_manager_)));
  }

  bool send_packet_upward(uint16_t handle, std::function<void(struct acl_manager::assembler* assembler)> cb) {
    return connections.send_packet_upward(handle, cb);
  }

  void on_common_le_connection_complete(AddressWithType address_with_type) {
    auto connecting_addr_with_type = connecting_le_.find(address_with_type);
    if (connecting_addr_with_type == connecting_le_.end()) {
      LOG_WARN("No prior connection request for %s", address_with_type.ToString().c_str());
    } else {
      connecting_le_.erase(connecting_addr_with_type);
    }
    if (create_connection_timeout_alarms_.find(address_with_type) != create_connection_timeout_alarms_.end()) {
      create_connection_timeout_alarms_.at(address_with_type).Cancel();
      create_connection_timeout_alarms_.erase(address_with_type);
    }
  }

  void on_le_connection_complete(LeMetaEventView packet) {
    LeConnectionCompleteView connection_complete = LeConnectionCompleteView::Create(packet);
    ASSERT(connection_complete.IsValid());
    auto status = connection_complete.GetStatus();
    auto address = connection_complete.GetPeerAddress();
    auto peer_address_type = connection_complete.GetPeerAddressType();
    if (status == ErrorCode::UNKNOWN_CONNECTION && pause_connection) {
      // connection canceled by LeAddressManager.OnPause(), will auto reconnect by LeAddressManager.OnResume()
      return;
    }
    // TODO: find out which address and type was used to initiate the connection
    AddressWithType remote_address(address, peer_address_type);
    AddressWithType local_address = le_address_manager_->GetCurrentAddress();
    on_common_le_connection_complete(remote_address);
    if (status == ErrorCode::UNKNOWN_CONNECTION && remote_address.GetAddress() == Address::kEmpty) {
      // direct connect canceled due to connection timeout, start background connect
      create_le_connection(remote_address, false, false);
      return;
    } else {
      canceled_connections_.erase(remote_address);
      ready_to_unregister = true;
      remove_device_from_connect_list(remote_address);
    }

    if (le_client_handler_ == nullptr) {
      LOG_ERROR("No callbacks to call");
      return;
    }

    if (status != ErrorCode::SUCCESS) {
      le_client_handler_->Post(common::BindOnce(&LeConnectionCallbacks::OnLeConnectFail,
                                                common::Unretained(le_client_callbacks_), remote_address, status));
      return;
    }

    uint16_t conn_interval = connection_complete.GetConnInterval();
    uint16_t conn_latency = connection_complete.GetConnLatency();
    uint16_t supervision_timeout = connection_complete.GetSupervisionTimeout();
    if (!check_connection_parameters(conn_interval, conn_interval, conn_latency, supervision_timeout)) {
      LOG_ERROR("Receive connection complete with invalid connection parameters");
      return;
    }

    auto role = connection_complete.GetRole();
    uint16_t handle = connection_complete.GetConnectionHandle();
    auto queue = std::make_shared<AclConnection::Queue>(10);
    auto queue_down_end = queue->GetDownEnd();
    round_robin_scheduler_->Register(RoundRobinScheduler::ConnectionType::LE, handle, queue);
    std::unique_ptr<LeAclConnection> connection(new LeAclConnection(
        std::move(queue), le_acl_connection_interface_, handle, local_address, remote_address, role));
    connection->peer_address_with_type_ = AddressWithType(address, peer_address_type);
    connections.add(
        handle, remote_address, queue_down_end, handler_, connection->GetEventCallbacks([this](uint16_t handle) {
          this->connections.invalidate(handle);
        }));
    le_client_handler_->Post(common::BindOnce(&LeConnectionCallbacks::OnLeConnectSuccess,
                                              common::Unretained(le_client_callbacks_), remote_address,
                                              std::move(connection)));
  }

  void on_le_enhanced_connection_complete(LeMetaEventView packet) {
    LeEnhancedConnectionCompleteView connection_complete = LeEnhancedConnectionCompleteView::Create(packet);
    ASSERT(connection_complete.IsValid());
    auto status = connection_complete.GetStatus();
    auto address = connection_complete.GetPeerAddress();
    auto peer_address_type = connection_complete.GetPeerAddressType();
    auto peer_resolvable_address = connection_complete.GetPeerResolvablePrivateAddress();
    if (status == ErrorCode::UNKNOWN_CONNECTION && pause_connection) {
      // connection canceled by LeAddressManager.OnPause(), will auto reconnect by LeAddressManager.OnResume()
      return;
    }
    AddressWithType remote_address(address, peer_address_type);
    if (!peer_resolvable_address.IsEmpty()) {
      remote_address = AddressWithType(peer_resolvable_address, AddressType::RANDOM_DEVICE_ADDRESS);
    }
    on_common_le_connection_complete(remote_address);
    if (status == ErrorCode::UNKNOWN_CONNECTION && remote_address.GetAddress() == Address::kEmpty) {
      // direct connect canceled due to connection timeout, start background connect
      create_le_connection(remote_address, false, false);
      return;
    } else {
      canceled_connections_.erase(remote_address);
      ready_to_unregister = true;
      remove_device_from_connect_list(remote_address);
    }

    if (le_client_handler_ == nullptr) {
      LOG_ERROR("No callbacks to call");
      return;
    }

    if (status != ErrorCode::SUCCESS) {
      le_client_handler_->Post(common::BindOnce(&LeConnectionCallbacks::OnLeConnectFail,
                                                common::Unretained(le_client_callbacks_), remote_address, status));
      return;
    }

    auto role = connection_complete.GetRole();
    AddressWithType local_address;
    if (role == hci::Role::CENTRAL) {
      local_address = le_address_manager_->GetCurrentAddress();
    } else {
      // when accepting connection, we must obtain the address from the advertiser.
      // When we receive "set terminated event", we associate connection handle with advertiser address
      local_address = AddressWithType{};
    }

    uint16_t conn_interval = connection_complete.GetConnInterval();
    uint16_t conn_latency = connection_complete.GetConnLatency();
    uint16_t supervision_timeout = connection_complete.GetSupervisionTimeout();
    if (!check_connection_parameters(conn_interval, conn_interval, conn_latency, supervision_timeout)) {
      LOG_ERROR("Receive enhenced connection complete with invalid connection parameters");
      return;
    }
    uint16_t handle = connection_complete.GetConnectionHandle();
    auto queue = std::make_shared<AclConnection::Queue>(10);
    auto queue_down_end = queue->GetDownEnd();
    round_robin_scheduler_->Register(RoundRobinScheduler::ConnectionType::LE, handle, queue);
    std::unique_ptr<LeAclConnection> connection(new LeAclConnection(
        std::move(queue), le_acl_connection_interface_, handle, local_address, remote_address, role));
    connection->peer_address_with_type_ = AddressWithType(address, peer_address_type);
    connections.add(
        handle, remote_address, queue_down_end, handler_, connection->GetEventCallbacks([this](uint16_t handle) {
          this->connections.invalidate(handle);
        }));
    le_client_handler_->Post(common::BindOnce(&LeConnectionCallbacks::OnLeConnectSuccess,
                                              common::Unretained(le_client_callbacks_), remote_address,
                                              std::move(connection)));
  }

  static constexpr bool kRemoveConnectionAfterwards = true;
  void on_le_disconnect(uint16_t handle, ErrorCode reason) {
    bool event_also_routes_to_other_receivers = connections.crash_on_unknown_handle_;
    connections.crash_on_unknown_handle_ = false;
    connections.execute(
        handle,
        [=](LeConnectionManagementCallbacks* callbacks) {
          round_robin_scheduler_->Unregister(handle);
          callbacks->OnDisconnection(reason);
        },
        kRemoveConnectionAfterwards);
    connections.crash_on_unknown_handle_ = event_also_routes_to_other_receivers;
  }

  void on_le_connection_update_complete(LeMetaEventView view) {
    auto complete_view = LeConnectionUpdateCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      LOG_ERROR("Received on_le_connection_update_complete with invalid packet");
      return;
    }
    auto handle = complete_view.GetConnectionHandle();
    connections.execute(handle, [=](LeConnectionManagementCallbacks* callbacks) {
      callbacks->OnConnectionUpdate(
          complete_view.GetStatus(),
          complete_view.GetConnInterval(),
          complete_view.GetConnLatency(),
          complete_view.GetSupervisionTimeout());
    });
  }

  void on_le_phy_update_complete(LeMetaEventView view) {
    auto complete_view = LePhyUpdateCompleteView::Create(view);
    if (!complete_view.IsValid()) {
      LOG_ERROR("Received on_le_phy_update_complete with invalid packet");
      return;
    }
    auto handle = complete_view.GetConnectionHandle();
    connections.execute(handle, [=](LeConnectionManagementCallbacks* callbacks) {
      callbacks->OnPhyUpdate(complete_view.GetStatus(), complete_view.GetTxPhy(), complete_view.GetRxPhy());
    });
  }

  void on_le_read_remote_version_information(
      hci::ErrorCode hci_status, uint16_t handle, uint8_t version, uint16_t manufacturer_name, uint16_t sub_version) {
    connections.execute(handle, [=](LeConnectionManagementCallbacks* callbacks) {
      callbacks->OnReadRemoteVersionInformationComplete(hci_status, version, manufacturer_name, sub_version);
    });
  }

  void on_data_length_change(LeMetaEventView view) {
    auto data_length_view = LeDataLengthChangeView::Create(view);
    if (!data_length_view.IsValid()) {
      LOG_ERROR("Invalid packet");
      return;
    }
    auto handle = data_length_view.GetConnectionHandle();
    connections.execute(handle, [=](LeConnectionManagementCallbacks* callbacks) {
      callbacks->OnDataLengthChange(
          data_length_view.GetMaxTxOctets(),
          data_length_view.GetMaxTxTime(),
          data_length_view.GetMaxRxOctets(),
          data_length_view.GetMaxRxTime());
    });
  }

  void on_remote_connection_parameter_request(LeMetaEventView view) {
    auto request_view = LeRemoteConnectionParameterRequestView::Create(view);
    if (!request_view.IsValid()) {
      LOG_ERROR("Invalid packet");
      return;
    }

    auto handle = request_view.GetConnectionHandle();
    connections.execute(handle, [=](LeConnectionManagementCallbacks* callbacks) {
      // TODO: this is blindly accepting any parameters, just so we don't hang connection
      // have proper parameter negotiation
      le_acl_connection_interface_->EnqueueCommand(
          LeRemoteConnectionParameterRequestReplyBuilder::Create(
              handle,
              request_view.GetIntervalMin(),
              request_view.GetIntervalMax(),
              request_view.GetLatency(),
              request_view.GetTimeout(),
              0,
              0),
          handler_->BindOnce([](CommandCompleteView status) {}));
    });
  }

  uint16_t HACK_get_handle(Address address) {
    return connections.HACK_get_handle(address);
  }

  void UpdateLocalAddress(uint16_t handle, hci::AddressWithType address_with_type) {
    connections.execute(handle, [=](LeConnectionManagementCallbacks* callbacks) {
      callbacks->OnLocalAddressUpdate(address_with_type);
    });
  }

  void add_device_to_connect_list(AddressWithType address_with_type) {
    register_with_address_manager();
    le_address_manager_->AddDeviceToConnectList(
        address_with_type.ToConnectListAddressType(), address_with_type.GetAddress());
  }

  void clear_connect_list() {
    if (!address_manager_registered) {
      le_address_manager_->Register(this);
      address_manager_registered = true;
    }
    pause_connection = true;
    le_address_manager_->ClearConnectList();
  }

  void remove_device_from_connect_list(AddressWithType address_with_type) {
    direct_connections_.erase(address_with_type);
    register_with_address_manager();
    le_address_manager_->RemoveDeviceFromConnectList(
        address_with_type.ToConnectListAddressType(), address_with_type.GetAddress());
  }

  void add_device_to_resolving_list(
      AddressWithType address_with_type,
      const std::array<uint8_t, 16>& peer_irk,
      const std::array<uint8_t, 16>& local_irk) {
    register_with_address_manager();
    le_address_manager_->AddDeviceToResolvingList(
        address_with_type.ToPeerAddressType(), address_with_type.GetAddress(), peer_irk, local_irk);
  }

  void remove_device_from_resolving_list(AddressWithType address_with_type) {
    register_with_address_manager();
    le_address_manager_->RemoveDeviceFromResolvingList(
        address_with_type.ToPeerAddressType(), address_with_type.GetAddress());
  }

  void on_extended_create_connection(CommandStatusView status) {
    ASSERT(status.IsValid());
    ASSERT(status.GetCommandOpCode() == OpCode::LE_EXTENDED_CREATE_CONNECTION);
  }

  void on_create_connection(CommandStatusView status) {
    ASSERT(status.IsValid());
    ASSERT(status.GetCommandOpCode() == OpCode::LE_CREATE_CONNECTION);
  }

  void create_le_connection(AddressWithType address_with_type, bool add_to_connect_list, bool is_direct) {
    if (le_client_callbacks_ == nullptr) {
      LOG_ERROR("No callbacks to call");
      return;
    }

    // TODO: Configure default LE connection parameters?
    if (add_to_connect_list) {
      add_device_to_connect_list(address_with_type);
      if (is_direct) {
        direct_connections_.insert(address_with_type);
        if (create_connection_timeout_alarms_.find(address_with_type) == create_connection_timeout_alarms_.end()) {
          create_connection_timeout_alarms_.emplace(
              std::piecewise_construct,
              std::forward_as_tuple(address_with_type.GetAddress(), address_with_type.GetAddressType()),
              std::forward_as_tuple(handler_));
          create_connection_timeout_alarms_.at(address_with_type)
              .Schedule(
                  common::BindOnce(&le_impl::on_create_connection_timeout, common::Unretained(this), address_with_type),
                  kCreateConnectionTimeoutMs);
        }
      }
    }

    if (!address_manager_registered) {
      auto policy = le_address_manager_->Register(this);
      address_manager_registered = true;

      // Pause connection, wait for set random address complete
      if (policy == LeAddressManager::AddressPolicy::USE_RESOLVABLE_ADDRESS ||
          policy == LeAddressManager::AddressPolicy::USE_NON_RESOLVABLE_ADDRESS) {
        pause_connection = true;
      }
    }

    if (pause_connection) {
      canceled_connections_.insert(address_with_type);
      return;
    }

    uint16_t le_scan_interval = kScanIntervalSlow;
    uint16_t le_scan_window = kScanWindowSlow;
    uint16_t le_scan_window_2m = kScanWindowSlow;
    uint16_t le_scan_window_coded = kScanWindowSlow;
    // If there is any direct connection in the connection list, use the fast parameter
    if (!direct_connections_.empty()) {
      le_scan_interval = kScanIntervalFast;
      le_scan_window = kScanWindowFast;
      le_scan_window_2m = kScanWindow2mFast;
      le_scan_window_coded = kScanWindowCodedFast;
    }
    InitiatorFilterPolicy initiator_filter_policy = InitiatorFilterPolicy::USE_CONNECT_LIST;
    OwnAddressType own_address_type =
        static_cast<OwnAddressType>(le_address_manager_->GetCurrentAddress().GetAddressType());
    uint16_t conn_interval_min = 0x0018;
    uint16_t conn_interval_max = 0x0028;
    uint16_t conn_latency = 0x0000;
    uint16_t supervision_timeout = 0x001f4;
    ASSERT(check_connection_parameters(conn_interval_min, conn_interval_max, conn_latency, supervision_timeout));

    connecting_le_.insert(address_with_type);

    if (initiator_filter_policy == InitiatorFilterPolicy::USE_CONNECT_LIST) {
      address_with_type = AddressWithType();
    }

    if (controller_->IsSupported(OpCode::LE_EXTENDED_CREATE_CONNECTION)) {
      uint8_t initiating_phys = PHY_LE_1M;
      std::vector<LeCreateConnPhyScanParameters> parameters = {};
      LeCreateConnPhyScanParameters scan_parameters;
      scan_parameters.scan_interval_ = le_scan_interval;
      scan_parameters.scan_window_ = le_scan_window;
      scan_parameters.conn_interval_min_ = conn_interval_min;
      scan_parameters.conn_interval_max_ = conn_interval_max;
      scan_parameters.conn_latency_ = conn_latency;
      scan_parameters.supervision_timeout_ = supervision_timeout;
      scan_parameters.min_ce_length_ = 0x00;
      scan_parameters.max_ce_length_ = 0x00;
      parameters.push_back(scan_parameters);

      if (controller_->SupportsBle2mPhy()) {
        LeCreateConnPhyScanParameters scan_parameters_2m;
        scan_parameters_2m.scan_interval_ = le_scan_interval;
        scan_parameters_2m.scan_window_ = le_scan_window_2m;
        scan_parameters_2m.conn_interval_min_ = conn_interval_min;
        scan_parameters_2m.conn_interval_max_ = conn_interval_max;
        scan_parameters_2m.conn_latency_ = conn_latency;
        scan_parameters_2m.supervision_timeout_ = supervision_timeout;
        scan_parameters_2m.min_ce_length_ = 0x00;
        scan_parameters_2m.max_ce_length_ = 0x00;
        parameters.push_back(scan_parameters_2m);
        initiating_phys |= PHY_LE_2M;
      }
      if (controller_->SupportsBleCodedPhy()) {
        LeCreateConnPhyScanParameters scan_parameters_coded;
        scan_parameters_coded.scan_interval_ = le_scan_interval;
        scan_parameters_coded.scan_window_ = le_scan_window_coded;
        scan_parameters_coded.conn_interval_min_ = conn_interval_min;
        scan_parameters_coded.conn_interval_max_ = conn_interval_max;
        scan_parameters_coded.conn_latency_ = conn_latency;
        scan_parameters_coded.supervision_timeout_ = supervision_timeout;
        scan_parameters_coded.min_ce_length_ = 0x00;
        scan_parameters_coded.max_ce_length_ = 0x00;
        parameters.push_back(scan_parameters_coded);
        initiating_phys |= PHY_LE_CODED;
      }

      le_acl_connection_interface_->EnqueueCommand(
          LeExtendedCreateConnectionBuilder::Create(
              initiator_filter_policy,
              own_address_type,
              address_with_type.GetAddressType(),
              address_with_type.GetAddress(),
              initiating_phys,
              parameters),
          handler_->BindOnce(&le_impl::on_extended_create_connection, common::Unretained(this)));
    } else {
      le_acl_connection_interface_->EnqueueCommand(
          LeCreateConnectionBuilder::Create(
              le_scan_interval,
              le_scan_window,
              initiator_filter_policy,
              address_with_type.GetAddressType(),
              address_with_type.GetAddress(),
              own_address_type,
              conn_interval_min,
              conn_interval_max,
              conn_latency,
              supervision_timeout,
              kMinimumCeLength,
              kMaximumCeLength),
          handler_->BindOnce(&le_impl::on_create_connection, common::Unretained(this)));
    }
  }

  void on_create_connection_timeout(AddressWithType address_with_type) {
    LOG_INFO("on_create_connection_timeout, address: %s", address_with_type.ToString().c_str());
    if (create_connection_timeout_alarms_.find(address_with_type) != create_connection_timeout_alarms_.end()) {
      create_connection_timeout_alarms_.at(address_with_type).Cancel();
      create_connection_timeout_alarms_.erase(address_with_type);
      if (background_connections_.find(address_with_type) != background_connections_.end()) {
        direct_connections_.erase(address_with_type);
        le_acl_connection_interface_->EnqueueCommand(
            LeCreateConnectionCancelBuilder::Create(),
            handler_->BindOnce(&le_impl::on_create_connection_cancel_complete, common::Unretained(this)));
      } else {
        cancel_connect(address_with_type);
      }
      le_client_handler_->Post(common::BindOnce(
          &LeConnectionCallbacks::OnLeConnectFail,
          common::Unretained(le_client_callbacks_),
          address_with_type,
          ErrorCode::CONNECTION_ACCEPT_TIMEOUT));
    }
  }

  void cancel_connect(AddressWithType address_with_type) {
    // the connection will be canceled by LeAddressManager.OnPause()
    remove_device_from_connect_list(address_with_type);
  }

  void set_le_suggested_default_data_parameters(uint16_t length, uint16_t time) {
    auto packet = LeWriteSuggestedDefaultDataLengthBuilder::Create(length, time);
    le_acl_connection_interface_->EnqueueCommand(
        std::move(packet), handler_->BindOnce([](CommandCompleteView complete) {}));
  }

  void clear_resolving_list() {
    le_address_manager_->ClearResolvingList();
  }

  void set_privacy_policy_for_initiator_address(
      LeAddressManager::AddressPolicy address_policy,
      AddressWithType fixed_address,
      crypto_toolbox::Octet16 rotation_irk,
      std::chrono::milliseconds minimum_rotation_time,
      std::chrono::milliseconds maximum_rotation_time) {
    le_address_manager_->SetPrivacyPolicyForInitiatorAddress(
        address_policy,
        fixed_address,
        rotation_irk,
        controller_->SupportsBlePrivacy(),
        minimum_rotation_time,
        maximum_rotation_time);
  }

  // TODO(jpawlowski): remove once we have config file abstraction in cert tests
  void set_privacy_policy_for_initiator_address_for_test(
      LeAddressManager::AddressPolicy address_policy,
      AddressWithType fixed_address,
      crypto_toolbox::Octet16 rotation_irk,
      std::chrono::milliseconds minimum_rotation_time,
      std::chrono::milliseconds maximum_rotation_time) {
    le_address_manager_->SetPrivacyPolicyForInitiatorAddressForTest(
        address_policy, fixed_address, rotation_irk, minimum_rotation_time, maximum_rotation_time);
  }

  void handle_register_le_callbacks(LeConnectionCallbacks* callbacks, os::Handler* handler) {
    ASSERT(le_client_callbacks_ == nullptr);
    ASSERT(le_client_handler_ == nullptr);
    le_client_callbacks_ = callbacks;
    le_client_handler_ = handler;
  }

  void handle_unregister_le_callbacks(LeConnectionCallbacks* callbacks, std::promise<void> promise) {
    ASSERT_LOG(le_client_callbacks_ == callbacks, "Registered le callback entity is different then unregister request");
    le_client_callbacks_ = nullptr;
    le_client_handler_ = nullptr;
    promise.set_value();
  }

  bool check_connection_parameters(
      uint16_t conn_interval_min, uint16_t conn_interval_max, uint16_t conn_latency, uint16_t supervision_timeout) {
    if (conn_interval_min < 0x0006 || conn_interval_min > 0x0C80 || conn_interval_max < 0x0006 ||
        conn_interval_max > 0x0C80 || conn_latency > 0x01F3 || supervision_timeout < 0x000A ||
        supervision_timeout > 0x0C80) {
      LOG_ERROR("Invalid parameter");
      return false;
    }

    // The Maximum interval in milliseconds will be conn_interval_max * 1.25 ms
    // The Timeout in milliseconds will be expected_supervision_timeout * 10 ms
    // The Timeout in milliseconds shall be larger than (1 + Latency) * Interval_Max * 2, where Interval_Max is given in
    // milliseconds.
    uint32_t supervision_timeout_min = (uint32_t)(1 + conn_latency) * conn_interval_max * 2 + 1;
    if (supervision_timeout * 8 < supervision_timeout_min || conn_interval_max < conn_interval_min) {
      LOG_ERROR("Invalid parameter");
      return false;
    }

    return true;
  }

  void add_device_to_background_connection_list(AddressWithType address_with_type) {
    background_connections_.insert(address_with_type);
  }

  void remove_device_from_background_connection_list(AddressWithType address_with_type) {
    background_connections_.erase(address_with_type);
  }

  void OnPause() override {
    pause_connection = true;
    if (connecting_le_.empty()) {
      le_address_manager_->AckPause(this);
      return;
    }
    canceled_connections_ = connecting_le_;
    le_acl_connection_interface_->EnqueueCommand(
        LeCreateConnectionCancelBuilder::Create(),
        handler_->BindOnce(&le_impl::on_create_connection_cancel_complete, common::Unretained(this)));
    le_address_manager_->AckPause(this);
  }

  void OnResume() override {
    pause_connection = false;
    if (!canceled_connections_.empty()) {
      create_le_connection(*canceled_connections_.begin(), false, false);
    }
    canceled_connections_.clear();
    le_address_manager_->AckResume(this);
    check_for_unregister();
  }

  void on_create_connection_cancel_complete(CommandCompleteView view) {
    auto complete_view = LeCreateConnectionCancelCompleteView::Create(view);
    ASSERT(complete_view.IsValid());
    if (complete_view.GetStatus() != ErrorCode::SUCCESS) {
      auto status = complete_view.GetStatus();
      std::string error_code = ErrorCodeText(status);
      LOG_WARN("Received on_create_connection_cancel_complete with error code %s", error_code.c_str());
    }
  }

  void register_with_address_manager() {
    if (!address_manager_registered) {
      le_address_manager_->Register(this);
      address_manager_registered = true;
      pause_connection = true;
    }
  }

  void check_for_unregister() {
    if (connections.is_empty() && connecting_le_.empty() && address_manager_registered && ready_to_unregister) {
      le_address_manager_->Unregister(this);
      address_manager_registered = false;
      pause_connection = false;
      ready_to_unregister = false;
    }
  }

  static constexpr uint16_t kMinimumCeLength = 0x0002;
  static constexpr uint16_t kMaximumCeLength = 0x0C00;
  HciLayer* hci_layer_ = nullptr;
  Controller* controller_ = nullptr;
  os::Handler* handler_ = nullptr;
  RoundRobinScheduler* round_robin_scheduler_ = nullptr;
  LeAddressManager* le_address_manager_ = nullptr;
  LeAclConnectionInterface* le_acl_connection_interface_ = nullptr;
  LeConnectionCallbacks* le_client_callbacks_ = nullptr;
  os::Handler* le_client_handler_ = nullptr;
  std::set<AddressWithType> connecting_le_;
  std::set<AddressWithType> canceled_connections_;
  std::set<AddressWithType> direct_connections_;
  // Set of devices that will not be removed from connect list after direct connect timeout
  std::set<AddressWithType> background_connections_;
  bool address_manager_registered = false;
  bool ready_to_unregister = false;
  bool pause_connection = false;
  std::map<AddressWithType, os::Alarm> create_connection_timeout_alarms_;
};

}  // namespace acl_manager
}  // namespace hci
}  // namespace bluetooth
