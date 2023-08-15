/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com. Represented by EHIMA
 * - www.ehima.com
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

#include <map>
#include <memory>
#include <optional>
#include <tuple>
#include <utility>  // for std::pair
#include <vector>

#ifdef __ANDROID__
#include <android/sysprop/BluetoothProperties.sysprop.h>
#endif

#include "audio_hal_client/audio_hal_client.h"
#include "bt_types.h"
#include "bta_groups.h"
#include "btm_iso_api_types.h"
#include "gatt_api.h"
#include "gd/common/strings.h"
#include "le_audio_log_history.h"
#include "le_audio_types.h"
#include "osi/include/alarm.h"
#include "osi/include/log.h"
#include "osi/include/properties.h"
#include "raw_address.h"

namespace le_audio {

// Maps to BluetoothProfile#LE_AUDIO
#define LE_AUDIO_PROFILE_CONSTANT 22

/* Enums */
enum class DeviceConnectState : uint8_t {
  /* Initial state */
  DISCONNECTED,
  /* When ACL connected, encrypted, CCC registered and initial characteristics
     read is completed */
  CONNECTED,
  /* Used when device is unbonding (RemoveDevice() API is called) */
  REMOVING,
  /* Disconnecting */
  DISCONNECTING,
  /* Disconnecting for recover - after that we want direct connect to be
     initiated */
  DISCONNECTING_AND_RECOVER,
  /* 2 states below are used when user creates connection. Connect API is
     called. */
  CONNECTING_BY_USER,
  /* Always used after CONNECTING_BY_USER */
  CONNECTED_BY_USER_GETTING_READY,
  /* 2 states are used when autoconnect was used for the connection.*/
  CONNECTING_AUTOCONNECT,
  /* Always used after CONNECTING_AUTOCONNECT */
  CONNECTED_AUTOCONNECT_GETTING_READY,
};

std::ostream& operator<<(std::ostream& os, const DeviceConnectState& state);

/* Class definitions */

/* LeAudioDevice class represents GATT server device with ASCS, PAC services as
 * mandatory. Device may contain multiple ASEs, PACs, audio locations. ASEs from
 * multiple devices may be formed in group.
 *
 * Device is created after connection or after storage restoration.
 *
 * Active device means that device has at least one ASE which will participate
 * in any state transition of state machine. ASEs and devices will be activated
 * according to requested by upper context type.
 */
class LeAudioDevice {
 public:
  RawAddress address_;

  DeviceConnectState connection_state_;
  bool known_service_handles_;
  bool notify_connected_after_read_;
  bool closing_stream_for_disconnection_;
  bool autoconnect_flag_;
  uint16_t conn_id_;
  uint16_t mtu_;
  bool encrypted_;
  int group_id_;
  bool csis_member_;
  std::bitset<16> tmap_role_;

  uint8_t audio_directions_;
  types::AudioLocations snk_audio_locations_;
  types::AudioLocations src_audio_locations_;

  types::PublishedAudioCapabilities snk_pacs_;
  types::PublishedAudioCapabilities src_pacs_;

  struct types::hdl_pair snk_audio_locations_hdls_;
  struct types::hdl_pair src_audio_locations_hdls_;
  struct types::hdl_pair audio_avail_hdls_;
  struct types::hdl_pair audio_supp_cont_hdls_;
  std::vector<struct types::ase> ases_;
  struct types::hdl_pair ctp_hdls_;
  uint16_t tmap_role_hdl_;

  alarm_t* link_quality_timer;
  uint16_t link_quality_timer_data;

  LeAudioDevice(const RawAddress& address_, DeviceConnectState state,
                int group_id = bluetooth::groups::kGroupUnknown)
      : address_(address_),
        connection_state_(state),
        known_service_handles_(false),
        notify_connected_after_read_(false),
        closing_stream_for_disconnection_(false),
        autoconnect_flag_(false),
        conn_id_(GATT_INVALID_CONN_ID),
        mtu_(0),
        encrypted_(false),
        group_id_(group_id),
        csis_member_(false),
        audio_directions_(0),
        link_quality_timer(nullptr) {}
  ~LeAudioDevice(void);

  void SetConnectionState(DeviceConnectState state);
  DeviceConnectState GetConnectionState(void);
  void ClearPACs(void);
  void RegisterPACs(std::vector<struct types::acs_ac_record>* apr_db,
                    std::vector<struct types::acs_ac_record>* apr);
  struct types::ase* GetAseByValHandle(uint16_t val_hdl);
  int GetAseCount(uint8_t direction);
  struct types::ase* GetFirstActiveAse(void);
  struct types::ase* GetFirstActiveAseByDirection(uint8_t direction);
  struct types::ase* GetNextActiveAseWithSameDirection(
      struct types::ase* base_ase);
  struct types::ase* GetNextActiveAseWithDifferentDirection(
      struct types::ase* base_ase);
  struct types::ase* GetFirstActiveAseByDataPathState(
      types::AudioStreamDataPathState state);
  struct types::ase* GetFirstInactiveAse(uint8_t direction,
                                         bool reconnect = false);
  struct types::ase* GetFirstAseWithState(uint8_t direction,
                                          types::AseState state);
  struct types::ase* GetNextActiveAse(struct types::ase* ase);
  struct types::ase* GetAseToMatchBidirectionCis(struct types::ase* ase);
  types::BidirectionalPair<struct types::ase*> GetAsesByCisConnHdl(
      uint16_t conn_hdl);
  types::BidirectionalPair<struct types::ase*> GetAsesByCisId(uint8_t cis_id);
  bool HaveActiveAse(void);
  bool HaveAllActiveAsesSameState(types::AseState state);
  bool HaveAnyUnconfiguredAses(void);
  bool IsReadyToCreateStream(void);
  bool IsReadyToSuspendStream(void);
  bool HaveAllActiveAsesCisEst(void);
  bool HaveAnyCisConnected(void);
  bool HasCisId(uint8_t id);
  uint8_t GetMatchingBidirectionCisId(const struct types::ase* base_ase);
  const struct types::acs_ac_record* GetCodecConfigurationSupportedPac(
      uint8_t direction, const set_configurations::CodecCapabilitySetting&
                             codec_capability_setting);
  uint8_t GetLc3SupportedChannelCount(uint8_t direction);
  uint8_t GetPhyBitmask(void);
  bool ConfigureAses(
      const le_audio::set_configurations::SetConfiguration& ent,
      types::LeAudioContextType context_type,
      uint8_t* number_of_already_active_group_ase,
      types::BidirectionalPair<types::AudioLocations>&
          group_audio_locations_out,
      const types::BidirectionalPair<types::AudioContexts>&
          metadata_context_types,
      const types::BidirectionalPair<std::vector<uint8_t>>& ccid_lists,
      bool reuse_cis_id);

  inline types::AudioContexts GetSupportedContexts(
      int direction = (types::kLeAudioDirectionSink |
                       types::kLeAudioDirectionSource)) const {
    return supp_contexts_.get(direction);
  }
  inline void SetSupportedContexts(
      types::BidirectionalPair<types::AudioContexts> contexts) {
    supp_contexts_ = contexts;
  }

  inline types::AudioContexts GetAvailableContexts(
      int direction = (types::kLeAudioDirectionSink |
                       types::kLeAudioDirectionSource)) const {
    return avail_contexts_.get(direction);
  }
  void SetAvailableContexts(
      types::BidirectionalPair<types::AudioContexts> cont_val);

  void DeactivateAllAses(void);
  bool ActivateConfiguredAses(types::LeAudioContextType context_type);

  void PrintDebugState(void);
  void DumpPacsDebugState(std::stringstream& stream);
  void Dump(int fd);

  void DisconnectAcl(void);
  std::vector<uint8_t> GetMetadata(types::AudioContexts context_type,
                                   const std::vector<uint8_t>& ccid_list);
  bool IsMetadataChanged(
      const types::BidirectionalPair<types::AudioContexts>& context_types,
      const types::BidirectionalPair<std::vector<uint8_t>>& ccid_lists);

 private:
  types::BidirectionalPair<types::AudioContexts> avail_contexts_;
  types::BidirectionalPair<types::AudioContexts> supp_contexts_;

  void DumpPacsDebugState(std::stringstream& stream,
                          types::PublishedAudioCapabilities pacs);
};

/* LeAudioDevices class represents a wraper helper over all devices in le audio
 * implementation. It allows to operate on device from a list (vector container)
 * using determinants like address, connection id etc.
 */
class LeAudioDevices {
 public:
  void Add(const RawAddress& address, le_audio::DeviceConnectState state,
           int group_id = bluetooth::groups::kGroupUnknown);
  void Remove(const RawAddress& address);
  LeAudioDevice* FindByAddress(const RawAddress& address) const;
  std::shared_ptr<LeAudioDevice> GetByAddress(const RawAddress& address) const;
  LeAudioDevice* FindByConnId(uint16_t conn_id) const;
  LeAudioDevice* FindByCisConnHdl(uint8_t cig_id, uint16_t conn_hdl) const;
  void SetInitialGroupAutoconnectState(int group_id, int gatt_if,
                                       tBTM_BLE_CONN_TYPE reconnection_mode,
                                       bool current_dev_autoconnect_flag);
  size_t Size(void) const;
  void Dump(int fd, int group_id) const;
  void Cleanup(tGATT_IF client_if);

 private:
  std::vector<std::shared_ptr<LeAudioDevice>> leAudioDevices_;
};

/* LeAudioDeviceGroup class represents group of LeAudioDevices and allows to
 * perform operations on them. Group states are ASE states due to nature of
 * group which operates finally of ASEs.
 *
 * Group is created after adding a node to new group id (which is not on list).
 */

class LeAudioDeviceGroup {
 public:
  const int group_id_;
  bool enabled_;
  types::CigState cig_state_;

  struct stream_configuration stream_conf;

  uint8_t audio_directions_;
  types::AudioLocations snk_audio_locations_;
  types::AudioLocations src_audio_locations_;

  /* Whether LE Audio is preferred for OUTPUT_ONLY and DUPLEX cases */
  bool is_output_preference_le_audio;
  bool is_duplex_preference_le_audio;

  std::vector<struct types::cis> cises_;
  explicit LeAudioDeviceGroup(const int group_id)
      : group_id_(group_id),
        enabled_(true),
        cig_state_(types::CigState::NONE),
        stream_conf({}),
        audio_directions_(0),
        transport_latency_mtos_us_(0),
        transport_latency_stom_us_(0),
        configuration_context_type_(types::LeAudioContextType::UNINITIALIZED),
        metadata_context_type_({.sink = types::AudioContexts(
                                    types::LeAudioContextType::UNINITIALIZED),
                                .source = types::AudioContexts(
                                    types::LeAudioContextType::UNINITIALIZED)}),
        group_available_contexts_(
            {.sink =
                 types::AudioContexts(types::LeAudioContextType::UNINITIALIZED),
             .source = types::AudioContexts(
                 types::LeAudioContextType::UNINITIALIZED)}),
        pending_group_available_contexts_change_(
            types::LeAudioContextType::UNINITIALIZED),
        target_state_(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE),
        current_state_(types::AseState::BTA_LE_AUDIO_ASE_STATE_IDLE) {
#ifdef __ANDROID__
    // 22 maps to BluetoothProfile#LE_AUDIO
    is_output_preference_le_audio = android::sysprop::BluetoothProperties::
                                        getDefaultOutputOnlyAudioProfile() ==
                                    LE_AUDIO_PROFILE_CONSTANT;
    is_duplex_preference_le_audio =
        android::sysprop::BluetoothProperties::getDefaultDuplexAudioProfile() ==
        LE_AUDIO_PROFILE_CONSTANT;
#else
    is_output_preference_le_audio = true;
    is_duplex_preference_le_audio = true;
#endif
  }
  ~LeAudioDeviceGroup(void);

  void AddNode(const std::shared_ptr<LeAudioDevice>& leAudioDevice);
  void RemoveNode(const std::shared_ptr<LeAudioDevice>& leAudioDevice);
  bool IsEmpty(void) const;
  bool IsAnyDeviceConnected(void) const;
  int Size(void) const;
  int NumOfConnected(types::LeAudioContextType context_type =
                         types::LeAudioContextType::RFU) const;
  bool Activate(types::LeAudioContextType context_type);
  void Deactivate(void);
  types::CigState GetCigState(void) const;
  void SetCigState(le_audio::types::CigState state);
  void CigClearCis(void);
  void ClearSinksFromConfiguration(void);
  void ClearSourcesFromConfiguration(void);
  void Cleanup(void);
  LeAudioDevice* GetFirstDevice(void) const;
  LeAudioDevice* GetFirstDeviceWithAvailableContext(
      types::LeAudioContextType context_type) const;
  le_audio::types::LeAudioConfigurationStrategy GetGroupStrategy(
      int expected_group_size) const;
  int GetAseCount(uint8_t direction) const;
  LeAudioDevice* GetNextDevice(LeAudioDevice* leAudioDevice) const;
  LeAudioDevice* GetNextDeviceWithAvailableContext(
      LeAudioDevice* leAudioDevice,
      types::LeAudioContextType context_type) const;
  LeAudioDevice* GetFirstActiveDevice(void) const;
  LeAudioDevice* GetNextActiveDevice(LeAudioDevice* leAudioDevice) const;
  LeAudioDevice* GetFirstActiveDeviceByDataPathState(
      types::AudioStreamDataPathState data_path_state) const;
  LeAudioDevice* GetNextActiveDeviceByDataPathState(
      LeAudioDevice* leAudioDevice,
      types::AudioStreamDataPathState data_path_state) const;
  bool IsDeviceInTheGroup(LeAudioDevice* leAudioDevice) const;
  bool HaveAllActiveDevicesAsesTheSameState(types::AseState state) const;
  bool HaveAnyActiveDeviceInUnconfiguredState() const;
  bool IsGroupStreamReady(void) const;
  bool IsGroupReadyToCreateStream(void) const;
  bool IsGroupReadyToSuspendStream(void) const;
  bool HaveAllCisesDisconnected(void) const;
  uint8_t GetFirstFreeCisId(void) const;
  uint8_t GetFirstFreeCisId(types::CisType cis_type) const;
  void CigGenerateCisIds(types::LeAudioContextType context_type);
  bool CigAssignCisIds(LeAudioDevice* leAudioDevice);
  void CigAssignCisConnHandles(const std::vector<uint16_t>& conn_handles);
  void CigAssignCisConnHandlesToAses(LeAudioDevice* leAudioDevice);
  void CigAssignCisConnHandlesToAses(void);
  void CigUnassignCis(LeAudioDevice* leAudioDevice);
  bool Configure(types::LeAudioContextType context_type,
                 const types::BidirectionalPair<types::AudioContexts>&
                     metadata_context_types,
                 types::BidirectionalPair<std::vector<uint8_t>> ccid_lists = {
                     .sink = {}, .source = {}});
  uint32_t GetSduInterval(uint8_t direction) const;
  uint8_t GetSCA(void) const;
  uint8_t GetPacking(void) const;
  uint8_t GetFraming(void) const;
  uint16_t GetMaxTransportLatencyStom(void) const;
  uint16_t GetMaxTransportLatencyMtos(void) const;
  void SetTransportLatency(uint8_t direction, uint32_t transport_latency_us);
  uint8_t GetRtn(uint8_t direction, uint8_t cis_id) const;
  uint16_t GetMaxSduSize(uint8_t direction, uint8_t cis_id) const;
  uint8_t GetPhyBitmask(uint8_t direction) const;
  uint8_t GetTargetPhy(uint8_t direction) const;
  bool GetPresentationDelay(uint32_t* delay, uint8_t direction) const;
  uint16_t GetRemoteDelay(uint8_t direction) const;
  bool UpdateAudioContextAvailability(void);
  bool UpdateAudioSetConfigurationCache(types::LeAudioContextType ctx_type);
  bool ReloadAudioLocations(void);
  bool ReloadAudioDirections(void);
  const set_configurations::AudioSetConfiguration* GetActiveConfiguration(
      void) const;
  bool IsPendingConfiguration(void) const;
  const set_configurations::AudioSetConfiguration* GetConfiguration(
      types::LeAudioContextType ctx_type);
  const set_configurations::AudioSetConfiguration* GetCachedConfiguration(
      types::LeAudioContextType ctx_type) const;
  void InvalidateCachedConfigurations(void);
  void SetPendingConfiguration(void);
  void ClearPendingConfiguration(void);
  void AddToAllowListNotConnectedGroupMembers(int gatt_if);
  void Disable(int gatt_if);
  void Enable(int gatt_if, tBTM_BLE_CONN_TYPE reconnection_mode);
  bool IsEnabled(void) const;
  bool IsAudioSetConfigurationSupported(
      LeAudioDevice* leAudioDevice,
      const set_configurations::AudioSetConfiguration* audio_set_conf) const;
  std::optional<LeAudioCodecConfiguration> GetCodecConfigurationByDirection(
      types::LeAudioContextType group_context_type, uint8_t direction);
  std::optional<LeAudioCodecConfiguration>
  GetCachedCodecConfigurationByDirection(
      types::LeAudioContextType group_context_type, uint8_t direction) const;
  bool IsAudioSetConfigurationAvailable(
      types::LeAudioContextType group_context_type);
  bool IsMetadataChanged(
      const types::BidirectionalPair<types::AudioContexts>& context_types,
      const types::BidirectionalPair<std::vector<uint8_t>>& ccid_lists) const;
  void CreateStreamVectorForOffloader(uint8_t direction);
  void StreamOffloaderUpdated(uint8_t direction);
  bool IsConfiguredForContext(types::LeAudioContextType context_type) const;
  void RemoveCisFromStreamIfNeeded(LeAudioDevice* leAudioDevice,
                                   uint16_t cis_conn_hdl);

  inline types::AseState GetState(void) const { return current_state_; }
  void SetState(types::AseState state) {
    LOG(INFO) << __func__ << " current state: " << current_state_
              << " new state: " << state;
    LeAudioLogHistory::Get()->AddLogHistory(
        kLogStateMachineTag, group_id_, RawAddress::kEmpty, kLogStateChangedOp,
        bluetooth::common::ToString(current_state_) + "->" +
            bluetooth::common::ToString(state));
    current_state_ = state;
  }

  inline types::AseState GetTargetState(void) const { return target_state_; }
  void SetTargetState(types::AseState state) {
    LOG(INFO) << __func__ << " target state: " << target_state_
              << " new target state: " << state;
    LeAudioLogHistory::Get()->AddLogHistory(
        kLogStateMachineTag, group_id_, RawAddress::kEmpty,
        kLogTargetStateChangedOp,
        bluetooth::common::ToString(target_state_) + "->" +
            bluetooth::common::ToString(state));
    target_state_ = state;
  }

  /* Returns context types for which support was recently added or removed */
  inline types::AudioContexts GetPendingAvailableContextsChange() const {
    return pending_group_available_contexts_change_;
  }

  /* Set which context types were recently added or removed */
  inline void SetPendingAvailableContextsChange(
      types::AudioContexts audio_contexts) {
    pending_group_available_contexts_change_ = audio_contexts;
  }

  inline void ClearPendingAvailableContextsChange() {
    pending_group_available_contexts_change_.clear();
  }

  inline void SetConfigurationContextType(
      types::LeAudioContextType context_type) {
    configuration_context_type_ = context_type;
  }

  inline types::LeAudioContextType GetConfigurationContextType(void) const {
    return configuration_context_type_;
  }

  inline types::BidirectionalPair<types::AudioContexts> GetMetadataContexts()
      const {
    return metadata_context_type_;
  }

  inline void SetAvailableContexts(
      types::BidirectionalPair<types::AudioContexts> new_contexts) {
    group_available_contexts_ = new_contexts;
    LOG_DEBUG(
        " group id: %d, available contexts sink: %s, available contexts "
        "source: "
        "%s",
        group_id_, group_available_contexts_.sink.to_string().c_str(),
        group_available_contexts_.source.to_string().c_str());
  }

  inline types::AudioContexts GetAvailableContexts(
      int direction = (types::kLeAudioDirectionSink |
                       types::kLeAudioDirectionSource)) const {
    LOG_DEBUG(
        " group id: %d, available contexts sink: %s, available contexts "
        "source: "
        "%s",
        group_id_, group_available_contexts_.sink.to_string().c_str(),
        group_available_contexts_.source.to_string().c_str());
    return group_available_contexts_.get(direction);
  }

  types::AudioContexts GetSupportedContexts(
      int direction = (types::kLeAudioDirectionSink |
                       types::kLeAudioDirectionSource)) const;

  types::BidirectionalPair<types::AudioContexts> GetLatestAvailableContexts(
      void) const;

  bool IsInTransition(void) const;
  bool IsStreaming(void) const;
  bool IsReleasingOrIdle(void) const;

  void PrintDebugState(void) const;
  void Dump(int fd, int active_group_id) const;

 private:
  uint32_t transport_latency_mtos_us_;
  uint32_t transport_latency_stom_us_;

  const set_configurations::AudioSetConfiguration*
  FindFirstSupportedConfiguration(types::LeAudioContextType context_type) const;
  bool ConfigureAses(
      const set_configurations::AudioSetConfiguration* audio_set_conf,
      types::LeAudioContextType context_type,
      const types::BidirectionalPair<types::AudioContexts>&
          metadata_context_types,
      const types::BidirectionalPair<std::vector<uint8_t>>& ccid_lists);
  bool IsAudioSetConfigurationSupported(
      const set_configurations::AudioSetConfiguration* audio_set_configuration,
      types::LeAudioContextType context_type,
      types::LeAudioConfigurationStrategy required_snk_strategy) const;
  uint32_t GetTransportLatencyUs(uint8_t direction) const;
  bool IsCisPartOfCurrentStream(uint16_t cis_conn_hdl) const;

  /* Current configuration and metadata context types */
  types::LeAudioContextType configuration_context_type_;
  types::BidirectionalPair<types::AudioContexts> metadata_context_type_;

  /* Mask of contexts that the whole group can handle at it's current state
   * It's being updated each time group members connect, disconnect or their
   * individual available audio contexts are changed.
   */
  types::BidirectionalPair<types::AudioContexts> group_available_contexts_;

  /* A temporary mask for bits which were either added or removed when the
   * group available context type changes. It usually means we should refresh
   * our group configuration capabilities to clear this.
   */
  types::AudioContexts pending_group_available_contexts_change_;

  /* Possible configuration cache - refreshed on each group context availability
   * change. Stored as a pair of (is_valid_cache, configuration*). `pair.first`
   * being `false` means that the cached value should be refreshed.
   */
  std::map<types::LeAudioContextType,
           std::pair<bool, const set_configurations::AudioSetConfiguration*>>
      context_to_configuration_cache_map;

  types::AseState target_state_;
  types::AseState current_state_;
  std::vector<std::weak_ptr<LeAudioDevice>> leAudioDevices_;
};

/* LeAudioDeviceGroup class represents a wraper helper over all device groups in
 * le audio implementation. It allows to operate on device group from a list
 * (vector container) using determinants like id.
 */
class LeAudioDeviceGroups {
 public:
  LeAudioDeviceGroup* Add(int group_id);
  void Remove(const int group_id);
  LeAudioDeviceGroup* FindById(int group_id) const;
  std::vector<int> GetGroupsIds(void) const;
  size_t Size() const;
  bool IsAnyInTransition() const;
  void Cleanup(void);
  void Dump(int fd, int active_group_id) const;

 private:
  std::vector<std::unique_ptr<LeAudioDeviceGroup>> groups_;
};
}  // namespace le_audio
