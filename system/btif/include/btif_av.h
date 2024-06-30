/******************************************************************************
 *
 *  Copyright 2009-2012 Broadcom Corporation
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

/**
 * BTIF AV API functions accessed internally.
 */

#ifndef BTIF_AV_H
#define BTIF_AV_H

#include <cstdint>
#include <vector>

#include "include/hardware/bt_av.h"
#include "types/raw_address.h"

/* Interface methods for the A2DP source stack. */

bt_status_t btif_av_source_init(
    btav_source_callbacks_t* callbacks, int max_connected_audio_devices,
    const std::vector<btav_a2dp_codec_config_t>& codec_priorities,
    const std::vector<btav_a2dp_codec_config_t>& offloading_preference,
    std::vector<btav_a2dp_codec_info_t>* supported_codecs);
bt_status_t btif_av_source_connect(const RawAddress& peer_address);
bt_status_t btif_av_source_disconnect(const RawAddress& peer_address);
bt_status_t btif_av_source_set_silence_device(const RawAddress& peer_address,
                                              bool silence);
bt_status_t btif_av_source_set_active_device(const RawAddress& peer_address);
bt_status_t btif_av_source_set_codec_config_preference(
    const RawAddress& peer_address,
    std::vector<btav_a2dp_codec_config_t> codec_preferences);
void btif_av_source_cleanup();

/* Interface methods for the A2DP sink stack. */

bt_status_t btif_av_sink_init(btav_sink_callbacks_t* callbacks,
                              int max_connected_audio_devices);
bt_status_t btif_av_sink_connect(const RawAddress& peer_address);
bt_status_t btif_av_sink_disconnect(const RawAddress& peer_address);
void btif_av_sink_cleanup();
void btif_av_sink_set_audio_focus_state(int focus_state);
void btif_av_sink_set_audio_track_gain(float gain);
bt_status_t btif_av_sink_set_active_device(const RawAddress& peer_address);

/**
 * Enum to represent the type of local a2dp profile.
 */
enum class A2dpType { kSource, kSink, kUnknown };

/**
 * When the local device is A2DP source, get the address of the active peer.
 */
RawAddress btif_av_source_active_peer(void);

/**
 * When the local device is A2DP sink, get the address of the active peer.
 */
RawAddress btif_av_sink_active_peer(void);

/**
 * Check whether A2DP Sink is enabled.
 */
bool btif_av_is_sink_enabled(void);

/**
 * Check whether A2DP Source is enabled.
 */
bool btif_av_is_source_enabled(void);

/**
 * Start streaming.
 * @param local_a2dp_type type of local a2dp profile.
 */
void btif_av_stream_start(const A2dpType local_a2dp_type);

/**
 * Start streaming with latency setting.
 */
void btif_av_stream_start_with_latency(bool use_latency_mode);

/**
 * Stop streaming.
 *
 * @param peer_address the peer address or RawAddress::kEmpty to stop all peers
 */
void btif_av_stream_stop(const RawAddress& peer_address);

/**
 * Suspend streaming.
 */
void btif_av_stream_suspend(void);

/**
 * Start offload streaming.
 */
void btif_av_stream_start_offload(void);

/**
 * Check whether ready to start the A2DP stream.
 * @param local_a2dp_type type of local a2dp profile.
 */
bool btif_av_stream_ready(const A2dpType local_a2dp_type);

/**
 * Check whether the A2DP stream is in started state and ready
 * for media start.
 * @param local_a2dp_type type of local a2dp profile.
 */
bool btif_av_stream_started_ready(const A2dpType local_a2dp_type);

/**
 * Check whether there is a connected peer (either Source or Sink)
 * @param local_a2dp_type type of local a2dp profile.
 */
bool btif_av_is_connected(const A2dpType local_a2dp_type);

/**
 * Get the Stream Endpoint Type of the Active peer.
 * @param local_a2dp_type type of local a2dp profile.
 *
 * @return the stream endpoint type: either AVDT_TSEP_SRC or AVDT_TSEP_SNK
 */
uint8_t btif_av_get_peer_sep(const A2dpType local_a2dp_type);

/**
 * Clear the remote suspended flag for the active peer.
 * @param local_a2dp_type type of local a2dp profile.
 */
void btif_av_clear_remote_suspend_flag(const A2dpType local_a2dp_type);

/**
 * Check whether the connected A2DP peer supports EDR.
 *
 * The value can be provided only if the remote peer is connected.
 * Otherwise, the answer will be always false.
 *
 * @param peer_address the peer address
 * @param local_a2dp_type type of local a2dp profile.
 * @return true if the remote peer is capable of EDR
 */
bool btif_av_is_peer_edr(const RawAddress& peer_address,
                         const A2dpType local_a2dp_type);

/**
 * Check whether the connected A2DP peer supports 3 Mbps EDR.
 *
 * The value can be provided only if the remote peer is connected.
 * Otherwise, the answer will be always false.
 *
 * @param peer_address the peer address
 * @param local_a2dp_type type of local a2dp profile.
 * @return true if the remote peer is capable of EDR and supports 3 Mbps
 */
bool btif_av_peer_supports_3mbps(const RawAddress& peer_address,
                                 const A2dpType local_a2dp_type);

/**
 * Check whether the mandatory codec is more preferred for this peer.
 *
 * @param peer_address the target peer address
 * @param local_a2dp_type type of local a2dp profile.
 * @return true if optional codecs are not preferred to be used
 */
bool btif_av_peer_prefers_mandatory_codec(const RawAddress& peer_address,
                                          const A2dpType local_a2dp_type);

/**
 * Report A2DP Source Codec State for a peer.
 *
 * @param peer_address the address of the peer to report
 * @param codec_config the codec config to report
 * @param codecs_local_capabilities the codecs local capabilities to report
 * @param codecs_selectable_capabilities the codecs selectable capabilities
 * to report
 */
void btif_av_report_source_codec_state(
    const RawAddress& peer_address,
    const btav_a2dp_codec_config_t& codec_config,
    const std::vector<btav_a2dp_codec_config_t>& codecs_local_capabilities,
    const std::vector<btav_a2dp_codec_config_t>&
        codecs_selectable_capabilities);

/**
 * Initialize / shut down the A2DP Source service.
 *
 * @param enable true to enable the A2DP Source service, false to disable it
 * @return BT_STATUS_SUCCESS on success, BT_STATUS_FAIL otherwise
 */
bt_status_t btif_av_source_execute_service(bool enable);

/**
 * Initialize / shut down the A2DP Sink service.
 *
 * @param enable true to enable the A2DP Sink service, false to disable it
 * @return BT_STATUS_SUCCESS on success, BT_STATUS_FAIL otherwise
 */
bt_status_t btif_av_sink_execute_service(bool enable);

/**
 * Peer ACL disconnected.
 *
 * @param peer_address the disconnected peer address
 * @param local_a2dp_type type of local a2dp profile.
 */
void btif_av_acl_disconnected(const RawAddress& peer_address,
                              const A2dpType local_a2dp_type);

/**
 * Dump debug-related information for the BTIF AV module.
 *
 * @param fd the file descriptor to use for writing the ASCII formatted
 * information
 */
void btif_debug_av_dump(int fd);

/**
 * Set the audio delay for the stream.
 *
 * @param peer_address the address of the peer to report
 * @param delay the delay to set in units of 1/10ms
 * @param local_a2dp_type type of local a2dp profile.
 */
void btif_av_set_audio_delay(const RawAddress& peer_address, uint16_t delay,
                             const A2dpType local_a2dp_type);

/**
 * Get the audio delay for the stream.
 * @param local_a2dp_type type of local a2dp profile.
 */
uint16_t btif_av_get_audio_delay(const A2dpType local_a2dp_type);

/**
 * Reset the audio delay and count of audio bytes sent to zero.
 */
void btif_av_reset_audio_delay(void);

/**
 *  check A2DP offload support enabled
 *  @param  none
 */
bool btif_av_is_a2dp_offload_enabled(void);

/**
 *  check A2DP offload enabled and running
 *  @param  none
 */
bool btif_av_is_a2dp_offload_running(void);

/**
 * Check whether peer device is silenced
 *
 * @param peer_address to check
 *
 */
bool btif_av_is_peer_silenced(const RawAddress& peer_address);

/**
 * check the a2dp connect status
 *
 * @param peer_address : checked device address
 * @param local_a2dp_type type of local a2dp profile.
 *
 */
bool btif_av_is_connected_addr(const RawAddress& peer_address,
                               const A2dpType local_a2dp_type);

/**
 * Set the dynamic audio buffer size
 *
 * @param dynamic_audio_buffer_size to set
 */
void btif_av_set_dynamic_audio_buffer_size(uint8_t dynamic_audio_buffer_size);

/**
 * Enable/disable the low latency
 *
 * @param is_low_latency to set
 */
void btif_av_set_low_latency(bool is_low_latency);

/**
 * Initiate an AV connection after 3s timeout to peer audio sink
 * @param handle bta handle
 * @param peer_addr peer address
 */
void btif_av_connect_sink_delayed(uint8_t handle,
                                  const RawAddress& peer_address);

/**
 * Check whether A2DP Source is enabled.
 */
bool btif_av_is_source_enabled(void);
bool btif_av_both_enable(void);
bool btif_av_src_sink_coexist_enabled(void);
bool btif_av_is_sink_enabled(void);
bool btif_av_peer_is_connected_sink(const RawAddress& peer_address);
bool btif_av_peer_is_connected_source(const RawAddress& peer_address);
bool btif_av_peer_is_sink(const RawAddress& peer_address);
bool btif_av_peer_is_source(const RawAddress& peer_address);

#endif /* BTIF_AV_H */
