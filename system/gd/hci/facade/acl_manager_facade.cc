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

#include "hci/facade/acl_manager_facade.h"

#include <bluetooth/log.h>

#include <condition_variable>
#include <memory>
#include <mutex>

#include "blueberry/facade/hci/acl_manager_facade.grpc.pb.h"
#include "blueberry/facade/hci/acl_manager_facade.pb.h"
#include "common/bind.h"
#include "grpc/grpc_event_queue.h"
#include "hci/acl_manager.h"
#include "hci/address.h"
#include "hci/class_of_device.h"
#include "hci/hci_packets.h"
#include "packet/raw_builder.h"

using ::grpc::ServerAsyncResponseWriter;
using ::grpc::ServerAsyncWriter;
using ::grpc::ServerContext;

using ::bluetooth::packet::RawBuilder;

namespace bluetooth {
namespace hci {
namespace facade {

using acl_manager::ClassicAclConnection;
using acl_manager::ConnectionCallbacks;
using acl_manager::ConnectionManagementCallbacks;

using namespace blueberry::facade::hci;

class AclManagerFacadeService : public AclManagerFacade::Service, public ConnectionCallbacks {
 public:
  AclManagerFacadeService(AclManager* acl_manager, ::bluetooth::os::Handler* facade_handler)
      : acl_manager_(acl_manager), facade_handler_(facade_handler) {
    acl_manager_->RegisterCallbacks(this, facade_handler_);
  }

  ~AclManagerFacadeService() {
    std::unique_lock<std::mutex> lock(acl_connections_mutex_);
    for (auto& connection : acl_connections_) {
      connection.second.connection_->GetAclQueueEnd()->UnregisterDequeue();
    }
  }

  ::grpc::Status CreateConnection(
      ::grpc::ServerContext* context,
      const ConnectionMsg* request,
      ::grpc::ServerWriter<ConnectionEvent>* writer) override {
    log::info("peer={}", request->address());
    Address peer;
    ASSERT(Address::FromString(request->address(), peer));
    acl_manager_->CreateConnection(peer);
    if (per_connection_events_.size() > current_connection_request_) {
      return ::grpc::Status(::grpc::StatusCode::RESOURCE_EXHAUSTED, "Only one outstanding request is supported");
    }
    per_connection_events_.emplace_back(std::make_unique<::bluetooth::grpc::GrpcEventQueue<ConnectionEvent>>(
        std::string("connection attempt ") + std::to_string(current_connection_request_)));
    return per_connection_events_[current_connection_request_]->RunLoop(context, writer);
  }

  ::grpc::Status Disconnect(
      ::grpc::ServerContext* /* context */,
      const HandleMsg* request,
      ::google::protobuf::Empty* /* response */) override {
    log::info("handle={}", request->handle());
    std::unique_lock<std::mutex> lock(acl_connections_mutex_);
    auto connection = acl_connections_.find(request->handle());
    if (connection == acl_connections_.end()) {
      log::error("Invalid handle");
      return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "Invalid handle");
    } else {
      connection->second.connection_->Disconnect(DisconnectReason::REMOTE_USER_TERMINATED_CONNECTION);
      return ::grpc::Status::OK;
    }
  }

  ::grpc::Status AuthenticationRequested(
      ::grpc::ServerContext* /* context */,
      const HandleMsg* request,
      ::google::protobuf::Empty* /* response */) override {
    log::info("handle={}", request->handle());
    std::unique_lock<std::mutex> lock(acl_connections_mutex_);
    auto connection = acl_connections_.find(request->handle());
    if (connection == acl_connections_.end()) {
      log::error("Invalid handle");
      return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "Invalid handle");
    } else {
      connection->second.connection_->AuthenticationRequested();
      return ::grpc::Status::OK;
    }
  };

#define GET_CONNECTION(view)                                                         \
  std::map<uint16_t, Connection>::iterator connection;                               \
  do {                                                                               \
    if (!view.IsValid()) {                                                           \
      return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "Invalid handle"); \
    }                                                                                \
    std::unique_lock<std::mutex> lock(acl_connections_mutex_);                       \
    connection = acl_connections_.find(view.GetConnectionHandle());                  \
    if (connection == acl_connections_.end()) {                                      \
      return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "Invalid handle"); \
    }                                                                                \
  } while (0)

  ::grpc::Status ConnectionCommand(
      ::grpc::ServerContext* /* context */,
      const ConnectionCommandMsg* request,
      ::google::protobuf::Empty* /* response */) override {
    log::info("size={}", request->packet().size());
    auto command_view =
        ConnectionManagementCommandView::Create(AclCommandView::Create(CommandView::Create(PacketView<kLittleEndian>(
            std::make_shared<std::vector<uint8_t>>(request->packet().begin(), request->packet().end())))));
    if (!command_view.IsValid()) {
      return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "Invalid command packet");
    }
    log::info("opcode={}", OpCodeText(command_view.GetOpCode()));
    switch (command_view.GetOpCode()) {
      case OpCode::AUTHENTICATION_REQUESTED: {
        GET_CONNECTION(AuthenticationRequestedView::Create(command_view));
        connection->second.connection_->AuthenticationRequested();
        return ::grpc::Status::OK;
      }
      case OpCode::DISCONNECT: {
        auto view = DisconnectView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->Disconnect(view.GetReason());
        return ::grpc::Status::OK;
      }
      case OpCode::CHANGE_CONNECTION_PACKET_TYPE: {
        auto view = ChangeConnectionPacketTypeView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->ChangeConnectionPacketType(view.GetPacketType());
        return ::grpc::Status::OK;
      }
      case OpCode::SET_CONNECTION_ENCRYPTION: {
        auto view = SetConnectionEncryptionView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->SetConnectionEncryption(view.GetEncryptionEnable());
        return ::grpc::Status::OK;
      }
      case OpCode::CHANGE_CONNECTION_LINK_KEY: {
        GET_CONNECTION(ChangeConnectionLinkKeyView::Create(command_view));
        connection->second.connection_->ChangeConnectionLinkKey();
        return ::grpc::Status::OK;
      }
      case OpCode::READ_CLOCK_OFFSET: {
        GET_CONNECTION(ReadClockOffsetView::Create(command_view));
        connection->second.connection_->ReadClockOffset();
        return ::grpc::Status::OK;
      }
      case OpCode::HOLD_MODE: {
        auto view = HoldModeView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->HoldMode(view.GetHoldModeMaxInterval(), view.GetHoldModeMinInterval());
        return ::grpc::Status::OK;
      }
      case OpCode::SNIFF_MODE: {
        auto view = SniffModeView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->SniffMode(
            view.GetSniffMaxInterval(), view.GetSniffMinInterval(), view.GetSniffAttempt(), view.GetSniffTimeout());
        return ::grpc::Status::OK;
      }
      case OpCode::EXIT_SNIFF_MODE: {
        GET_CONNECTION(ExitSniffModeView::Create(command_view));
        connection->second.connection_->ExitSniffMode();
        return ::grpc::Status::OK;
      }
      case OpCode::FLUSH: {
        GET_CONNECTION(FlushView::Create(command_view));
        connection->second.connection_->Flush();
        return ::grpc::Status::OK;
      }
      case OpCode::READ_AUTOMATIC_FLUSH_TIMEOUT: {
        GET_CONNECTION(ReadAutomaticFlushTimeoutView::Create(command_view));
        connection->second.connection_->ReadAutomaticFlushTimeout();
        return ::grpc::Status::OK;
      }
      case OpCode::WRITE_AUTOMATIC_FLUSH_TIMEOUT: {
        auto view = WriteAutomaticFlushTimeoutView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->WriteAutomaticFlushTimeout(view.GetFlushTimeout());
        return ::grpc::Status::OK;
      }
      case OpCode::READ_TRANSMIT_POWER_LEVEL: {
        auto view = ReadTransmitPowerLevelView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->ReadTransmitPowerLevel(view.GetTransmitPowerLevelType());
        return ::grpc::Status::OK;
      }
      case OpCode::READ_LINK_SUPERVISION_TIMEOUT: {
        GET_CONNECTION(ReadLinkSupervisionTimeoutView::Create(command_view));
        connection->second.connection_->ReadLinkSupervisionTimeout();
        return ::grpc::Status::OK;
      }
      case OpCode::WRITE_LINK_SUPERVISION_TIMEOUT: {
        auto view = WriteLinkSupervisionTimeoutView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->WriteLinkSupervisionTimeout(view.GetLinkSupervisionTimeout());
        return ::grpc::Status::OK;
      }
      case OpCode::READ_FAILED_CONTACT_COUNTER: {
        GET_CONNECTION(ReadFailedContactCounterView::Create(command_view));
        connection->second.connection_->ReadFailedContactCounter();
        return ::grpc::Status::OK;
      }
      case OpCode::RESET_FAILED_CONTACT_COUNTER: {
        GET_CONNECTION(ResetFailedContactCounterView::Create(command_view));
        connection->second.connection_->ResetFailedContactCounter();
        return ::grpc::Status::OK;
      }
      case OpCode::READ_LINK_QUALITY: {
        GET_CONNECTION(ReadLinkQualityView::Create(command_view));
        connection->second.connection_->ReadLinkQuality();
        return ::grpc::Status::OK;
      }
      case OpCode::READ_AFH_CHANNEL_MAP: {
        GET_CONNECTION(ReadAfhChannelMapView::Create(command_view));
        connection->second.connection_->ReadAfhChannelMap();
        return ::grpc::Status::OK;
      }
      case OpCode::READ_RSSI: {
        GET_CONNECTION(ReadRssiView::Create(command_view));
        connection->second.connection_->ReadRssi();
        return ::grpc::Status::OK;
      }
      case OpCode::READ_CLOCK: {
        auto view = ReadClockView::Create(command_view);
        GET_CONNECTION(view);
        connection->second.connection_->ReadClock(view.GetWhichClock());
        return ::grpc::Status::OK;
      }
      case OpCode::READ_REMOTE_VERSION_INFORMATION: {
        GET_CONNECTION(ReadRemoteVersionInformationView::Create(command_view));
        connection->second.connection_->ReadRemoteVersionInformation();
        return ::grpc::Status::OK;
      }
      case OpCode::READ_REMOTE_SUPPORTED_FEATURES: {
        GET_CONNECTION(ReadRemoteSupportedFeaturesView::Create(command_view));
        connection->second.connection_->ReadRemoteSupportedFeatures();
        return ::grpc::Status::OK;
      }
      case OpCode::READ_REMOTE_EXTENDED_FEATURES: {
        GET_CONNECTION(ReadRemoteExtendedFeaturesView::Create(command_view));
        uint8_t page_number = 0;
        connection->second.connection_->ReadRemoteExtendedFeatures(page_number);
        return ::grpc::Status::OK;
      }
      default:
        return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "Invalid command packet");
    }
  }
#undef GET_CONNECTION

  ::grpc::Status FetchIncomingConnection(
      ::grpc::ServerContext* context,
      const google::protobuf::Empty* /* request */,
      ::grpc::ServerWriter<ConnectionEvent>* writer) override {
    log::info("wait for one incoming connection");
    if (per_connection_events_.size() > current_connection_request_) {
      return ::grpc::Status(::grpc::StatusCode::RESOURCE_EXHAUSTED, "Only one outstanding connection is supported");
    }
    per_connection_events_.emplace_back(std::make_unique<::bluetooth::grpc::GrpcEventQueue<ConnectionEvent>>(
        std::string("incoming connection ") + std::to_string(current_connection_request_)));
    return per_connection_events_[current_connection_request_]->RunLoop(context, writer);
  }

  ::grpc::Status SendAclData(
      ::grpc::ServerContext* /* context */,
      const AclData* request,
      ::google::protobuf::Empty* /* response */) override {
    log::info("handle={}, size={}", request->handle(), request->payload().size());
    std::promise<void> promise;
    auto future = promise.get_future();
    {
      std::unique_lock<std::mutex> lock(acl_connections_mutex_);
      auto connection = acl_connections_.find(request->handle());
      if (connection == acl_connections_.end()) {
        return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "Invalid handle");
      }
      // TODO: This is unsafe because connection may have gone
      connection->second.connection_->GetAclQueueEnd()->RegisterEnqueue(
          facade_handler_,
          common::Bind(
              &AclManagerFacadeService::enqueue_packet,
              common::Unretained(this),
              common::Unretained(request),
              common::Passed(std::move(promise))));
      auto status = future.wait_for(std::chrono::milliseconds(1000));
      if (status != std::future_status::ready) {
        return ::grpc::Status(::grpc::StatusCode::RESOURCE_EXHAUSTED, "Can't send packet");
      }
    }
    return ::grpc::Status::OK;
  }

  std::unique_ptr<BasePacketBuilder> enqueue_packet(const AclData* request, std::promise<void> promise) {
    auto connection = acl_connections_.find(request->handle());
    ASSERT_LOG(connection != acl_connections_.end(), "handle %d", request->handle());
    connection->second.connection_->GetAclQueueEnd()->UnregisterEnqueue();
    std::unique_ptr<RawBuilder> packet =
        std::make_unique<RawBuilder>(std::vector<uint8_t>(request->payload().begin(), request->payload().end()));
    promise.set_value();
    return packet;
  }

  ::grpc::Status FetchAclData(
      ::grpc::ServerContext* context, const HandleMsg* request, ::grpc::ServerWriter<AclData>* writer) override {
    log::info("handle={}", request->handle());
    auto connection = acl_connections_.find(request->handle());
    if (connection == acl_connections_.end()) {
      return ::grpc::Status(::grpc::StatusCode::INVALID_ARGUMENT, "Invalid handle");
    }
    return connection->second.pending_acl_data_.RunLoop(context, writer);
  }

  static inline uint16_t to_handle(uint32_t current_request) {
    return (current_request + 0x10) % 0xe00;
  }

  static inline std::string builder_to_string(std::unique_ptr<BasePacketBuilder> builder) {
    std::vector<uint8_t> bytes;
    BitInserter bit_inserter(bytes);
    builder->Serialize(bit_inserter);
    return std::string(bytes.begin(), bytes.end());
  }

  void on_incoming_acl(std::shared_ptr<ClassicAclConnection> connection, uint16_t handle) {
    log::info(
        "handle={}, addr={}",
        connection->GetHandle(),
        ADDRESS_TO_LOGGABLE_CSTR(connection->GetAddress()));
    auto packet = connection->GetAclQueueEnd()->TryDequeue();
    auto connection_tracker = acl_connections_.find(handle);
    ASSERT_LOG(connection_tracker != acl_connections_.end(), "handle %d", handle);
    AclData acl_data;
    acl_data.set_handle(handle);
    acl_data.set_payload(std::string(packet->begin(), packet->end()));
    log::info("length={}", acl_data.payload().size());
    connection_tracker->second.pending_acl_data_.OnIncomingEvent(acl_data);
  }

  void OnConnectSuccess(std::unique_ptr<ClassicAclConnection> connection) override {
    log::info(
        "handle={}, addr={}",
        connection->GetHandle(),
        ADDRESS_TO_LOGGABLE_CSTR(connection->GetAddress()));
    std::unique_lock<std::mutex> lock(acl_connections_mutex_);
    std::shared_ptr<ClassicAclConnection> shared_connection = std::move(connection);
    uint16_t handle = to_handle(current_connection_request_);
    acl_connections_.erase(handle);
    acl_connections_.emplace(
        std::piecewise_construct,
        std::forward_as_tuple(handle),
        std::forward_as_tuple(handle, shared_connection, per_connection_events_[current_connection_request_]));
    shared_connection->GetAclQueueEnd()->RegisterDequeue(
        facade_handler_,
        common::Bind(&AclManagerFacadeService::on_incoming_acl, common::Unretained(this), shared_connection, handle));
    auto callbacks = acl_connections_.find(handle)->second.GetCallbacks();
    shared_connection->RegisterCallbacks(callbacks, facade_handler_);
    auto addr = shared_connection->GetAddress();
    std::unique_ptr<BasePacketBuilder> builder =
        ConnectionCompleteBuilder::Create(ErrorCode::SUCCESS, handle, addr, LinkType::ACL, Enable::DISABLED);
    ConnectionEvent success;
    success.set_payload(builder_to_string(std::move(builder)));
    per_connection_events_[current_connection_request_]->OnIncomingEvent(success);
    current_connection_request_++;
  }

  void OnConnectRequest(Address /* address */, ClassOfDevice /* cod */) override {
    log::error("Remote connect request unimplemented");
  }

  void OnConnectFail(Address address, ErrorCode reason, bool /* locally_initiated */) override {
    log::info("addr={}, reason={}", ADDRESS_TO_LOGGABLE_CSTR(address), ErrorCodeText(reason));
    std::unique_ptr<BasePacketBuilder> builder =
        ConnectionCompleteBuilder::Create(reason, 0, address, LinkType::ACL, Enable::DISABLED);
    ConnectionEvent fail;
    fail.set_payload(builder_to_string(std::move(builder)));
    per_connection_events_[current_connection_request_]->OnIncomingEvent(fail);
    current_connection_request_++;
  }

  class Connection : public ConnectionManagementCallbacks {
   public:
    Connection(
        uint16_t handle,
        std::shared_ptr<ClassicAclConnection> connection,
        std::shared_ptr<::bluetooth::grpc::GrpcEventQueue<ConnectionEvent>> event_stream)
        : handle_(handle), connection_(std::move(connection)), event_stream_(std::move(event_stream)) {}

    ConnectionManagementCallbacks* GetCallbacks() {
      return this;
    }

    void OnCentralLinkKeyComplete(KeyFlag key_flag) override {
      log::info("key_flag:{}", KeyFlagText(key_flag));
    }

    void OnRoleChange(hci::ErrorCode /* hci_status */, Role new_role) override {
      log::info("new_role:{}", (uint8_t)new_role);
    }

    void OnReadLinkPolicySettingsComplete(uint16_t link_policy_settings) override {
      log::info("link_policy_settings:{}", link_policy_settings);
    }

    void OnConnectionPacketTypeChanged(uint16_t packet_type) override {
      log::info("OnConnectionPacketTypeChanged packet_type:{}", packet_type);
    }

    void OnAuthenticationComplete(hci::ErrorCode /* hci_status */) override {
      log::info("OnAuthenticationComplete");
    }

    void OnEncryptionChange(EncryptionEnabled enabled) override {
      log::info("OnConnectionPacketTypeChanged enabled:{}", (uint8_t)enabled);
    }

    void OnChangeConnectionLinkKeyComplete() override {
      log::info("OnChangeConnectionLinkKeyComplete");
    };

    void OnReadClockOffsetComplete(uint16_t clock_offset) override {
      log::info("OnReadClockOffsetComplete clock_offset:{}", clock_offset);
    };

    void OnModeChange(ErrorCode /* status */, Mode current_mode, uint16_t interval) override {
      log::info("OnModeChange Mode:{}, interval:{}", (uint8_t)current_mode, interval);
    };

    void OnSniffSubrating(
        hci::ErrorCode /* hci_status */,
        uint16_t maximum_transmit_latency,
        uint16_t maximum_receive_latency,
        uint16_t minimum_remote_timeout,
        uint16_t minimum_local_timeout) override {
      log::info(
          "OnSniffSubrating maximum_transmit_latency:{}, maximum_receive_latency:{} "
          "minimum_remote_timeout:{} minimum_local_timeout:{}",
          maximum_transmit_latency,
          maximum_receive_latency,
          minimum_remote_timeout,
          minimum_local_timeout);
    }

    void OnQosSetupComplete(
        ServiceType service_type,
        uint32_t token_rate,
        uint32_t peak_bandwidth,
        uint32_t latency,
        uint32_t delay_variation) override {
      log::info(
          "OnQosSetupComplete service_type:{}, token_rate:{}, peak_bandwidth:{}, latency:{}, "
          "delay_variation:{}",
          (uint8_t)service_type,
          token_rate,
          peak_bandwidth,
          latency,
          delay_variation);
    }

    void OnFlowSpecificationComplete(
        FlowDirection flow_direction,
        ServiceType service_type,
        uint32_t token_rate,
        uint32_t token_bucket_size,
        uint32_t peak_bandwidth,
        uint32_t access_latency) override {
      log::info(
          "OnFlowSpecificationComplete flow_direction:{}. service_type:{}, token_rate:{}, "
          "token_bucket_size:{}, peak_bandwidth:{}, access_latency:{}",
          (uint8_t)flow_direction,
          (uint8_t)service_type,
          token_rate,
          token_bucket_size,
          peak_bandwidth,
          access_latency);
    }

    void OnFlushOccurred() override {
      log::info("OnFlushOccurred");
    }

    void OnRoleDiscoveryComplete(Role current_role) override {
      log::info("OnRoleDiscoveryComplete current_role:{}", (uint8_t)current_role);
    }

    void OnReadAutomaticFlushTimeoutComplete(uint16_t flush_timeout) override {
      log::info("OnReadAutomaticFlushTimeoutComplete flush_timeout:{}", flush_timeout);
    }

    void OnReadTransmitPowerLevelComplete(uint8_t transmit_power_level) override {
      log::info("OnReadTransmitPowerLevelComplete transmit_power_level:{}", transmit_power_level);
    }

    void OnReadLinkSupervisionTimeoutComplete(uint16_t link_supervision_timeout) override {
      log::info(
          "OnReadLinkSupervisionTimeoutComplete link_supervision_timeout:{}",
          link_supervision_timeout);
    }

    void OnReadFailedContactCounterComplete(uint16_t failed_contact_counter) override {
      log::info(
          "OnReadFailedContactCounterComplete failed_contact_counter:{}", failed_contact_counter);
    }

    void OnReadLinkQualityComplete(uint8_t link_quality) override {
      log::info("OnReadLinkQualityComplete link_quality:{}", link_quality);
    }

    void OnReadAfhChannelMapComplete(
        AfhMode afh_mode, std::array<uint8_t, 10> /* afh_channel_map */) override {
      log::info("OnReadAfhChannelMapComplete afh_mode:{}", (uint8_t)afh_mode);
    }

    void OnReadRssiComplete(uint8_t rssi) override {
      log::info("OnReadRssiComplete rssi:{}", rssi);
    }

    void OnReadClockComplete(uint32_t clock, uint16_t accuracy) override {
      log::info("OnReadClockComplete clock:{}, accuracy:{}", clock, accuracy);
    }

    void OnDisconnection(ErrorCode reason) override {
      log::info("reason: {}", ErrorCodeText(reason));
      std::unique_ptr<BasePacketBuilder> builder =
          DisconnectionCompleteBuilder::Create(ErrorCode::SUCCESS, handle_, reason);
      ConnectionEvent disconnection;
      disconnection.set_payload(builder_to_string(std::move(builder)));
      event_stream_->OnIncomingEvent(disconnection);
    }
    void OnReadRemoteVersionInformationComplete(
        hci::ErrorCode /* error_status */,
        uint8_t lmp_version,
        uint16_t manufacturer_name,
        uint16_t sub_version) override {
      log::info(
          "OnReadRemoteVersionInformationComplete lmp_version:{} manufacturer_name:{} "
          "sub_version:{}",
          lmp_version,
          manufacturer_name,
          sub_version);
    }
    void OnReadRemoteSupportedFeaturesComplete(uint64_t features) override {
      log::info(
          "OnReadRemoteSupportedFeaturesComplete features:0x{:x}",
          static_cast<unsigned long>(features));
    }
    void OnReadRemoteExtendedFeaturesComplete(
        uint8_t page_number, uint8_t max_page_number, uint64_t features) override {
      log::info(
          "OnReadRemoteExtendedFeaturesComplete page_number:{} max_page_number:{} features:0x{:x}",
          page_number,
          max_page_number,
          static_cast<unsigned long>(features));
    }

    uint16_t handle_;
    std::shared_ptr<ClassicAclConnection> connection_;
    std::shared_ptr<::bluetooth::grpc::GrpcEventQueue<ConnectionEvent>> event_stream_;
    ::bluetooth::grpc::GrpcEventQueue<AclData> pending_acl_data_{std::string("PendingAclData") +
                                                                 std::to_string(handle_)};
  };

 private:
  AclManager* acl_manager_;
  ::bluetooth::os::Handler* facade_handler_;
  mutable std::mutex acl_connections_mutex_;
  std::map<uint16_t, Connection> acl_connections_;
  std::vector<std::shared_ptr<::bluetooth::grpc::GrpcEventQueue<ConnectionEvent>>> per_connection_events_;
  uint32_t current_connection_request_{0};
};

void AclManagerFacadeModule::ListDependencies(ModuleList* list) const {
  ::bluetooth::grpc::GrpcFacadeModule::ListDependencies(list);
  list->add<AclManager>();
}

void AclManagerFacadeModule::Start() {
  ::bluetooth::grpc::GrpcFacadeModule::Start();
  service_ = new AclManagerFacadeService(GetDependency<AclManager>(), GetHandler());
}

void AclManagerFacadeModule::Stop() {
  delete service_;
  ::bluetooth::grpc::GrpcFacadeModule::Stop();
}

::grpc::Service* AclManagerFacadeModule::GetService() const {
  return service_;
}

const ModuleFactory AclManagerFacadeModule::Factory =
    ::bluetooth::ModuleFactory([]() { return new AclManagerFacadeModule(); });

}  // namespace facade
}  // namespace hci
}  // namespace bluetooth
