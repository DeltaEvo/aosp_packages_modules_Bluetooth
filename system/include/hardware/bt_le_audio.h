/*
 * Copyright 2019 HIMSA II K/S - www.himsa.com. Represented by EHIMA -
 * www.ehima.com
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

#include <array>
#include <optional>

#include "raw_address.h"

namespace bluetooth {
namespace le_audio {

enum class ConnectionState {
  DISCONNECTED = 0,
  CONNECTING,
  CONNECTED,
  DISCONNECTING
};

enum class GroupStatus {
  INACTIVE = 0,
  ACTIVE,
};

enum class GroupStreamStatus {
  IDLE = 0,
  STREAMING,
  RELEASING,
  SUSPENDING,
  SUSPENDED,
  RECONFIGURED,
  DESTROYED,
};

enum class GroupNodeStatus {
  ADDED = 1,
  REMOVED,
};

typedef enum {
  LE_AUDIO_CODEC_INDEX_SOURCE_LC3 = 0,
  LE_AUDIO_CODEC_INDEX_SOURCE_MAX
} btle_audio_codec_index_t;

typedef struct {
  btle_audio_codec_index_t codec_type;

  std::string ToString() const {
    std::string codec_name_str;

    switch (codec_type) {
      case LE_AUDIO_CODEC_INDEX_SOURCE_LC3:
        codec_name_str = "LC3";
        break;
      default:
        codec_name_str = "Unknown LE codec " + std::to_string(codec_type);
        break;
    }
    return "codec: " + codec_name_str;
  }
} btle_audio_codec_config_t;

class LeAudioClientCallbacks {
 public:
  virtual ~LeAudioClientCallbacks() = default;

  /** Callback for profile connection state change */
  virtual void OnConnectionState(ConnectionState state,
                                 const RawAddress& address) = 0;

  /* Callback with group status update */
  virtual void OnGroupStatus(int group_id, GroupStatus group_status) = 0;

  /* Callback with node status update */
  virtual void OnGroupNodeStatus(const RawAddress& bd_addr, int group_id,
                                 GroupNodeStatus node_status) = 0;
  /* Callback for newly recognized or reconfigured existing le audio group */
  virtual void OnAudioConf(uint8_t direction, int group_id,
                           uint32_t snk_audio_location,
                           uint32_t src_audio_location,
                           uint16_t avail_cont) = 0;
};

class LeAudioClientInterface {
 public:
  virtual ~LeAudioClientInterface() = default;

  /* Register the LeAudio callbacks */
  virtual void Initialize(
      LeAudioClientCallbacks* callbacks,
      const std::vector<btle_audio_codec_config_t>& offloading_preference) = 0;

  /** Connect to LEAudio */
  virtual void Connect(const RawAddress& address) = 0;

  /** Disconnect from LEAudio */
  virtual void Disconnect(const RawAddress& address) = 0;

  /* Cleanup the LeAudio */
  virtual void Cleanup(void) = 0;

  /* Called when LeAudio is unbonded. */
  virtual void RemoveDevice(const RawAddress& address) = 0;

  /* Attach le audio node to group */
  virtual void GroupAddNode(int group_id, const RawAddress& addr) = 0;

  /* Detach le audio node from a group */
  virtual void GroupRemoveNode(int group_id, const RawAddress& addr) = 0;

  /* Set active le audio group */
  virtual void GroupSetActive(int group_id) = 0;
};

static constexpr uint8_t INSTANCE_ID_UNDEFINED = 0xFF;

} /* namespace le_audio */
} /* namespace bluetooth */
