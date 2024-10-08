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

#pragma once

#include "osi/include/allocator.h"
#include "stack/include/bt_hdr.h"

typedef void (*packet_reassembled_cb)(BT_HDR* packet);
typedef void (*packet_fragmented_cb)(BT_HDR* packet, bool send_transmit_finished);

typedef struct {
  // Called for every packet fragment.
  packet_fragmented_cb fragmented;

  // Called for every completely reassembled packet.
  packet_reassembled_cb reassembled;
} packet_fragmenter_callbacks_t;

typedef struct packet_fragmenter_t {
  // Initialize the fragmenter, specifying the |result_callbacks|.
  void (*init)(const packet_fragmenter_callbacks_t* result_callbacks);

  // Release all resources associated with the fragmenter.
  void (*cleanup)(void);

  // Fragments |packet| if necessary and hands off everything to the fragmented
  // callback.
  void (*fragment_and_dispatch)(BT_HDR* packet, uint16_t iso_buffer_size);
  // If |packet| is a complete packet, forwards to the reassembled callback.
  // Otherwise holds onto it until all fragments arrive, at which point the
  // reassembled callback is called with the reassembled data.
  void (*reassemble_and_dispatch)(BT_HDR* packet);
} packet_fragmenter_t;

const packet_fragmenter_t* packet_fragmenter_get_interface();
