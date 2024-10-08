/*
 * Copyright 2018 The Android Open Source Project
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

#include <vector>

// AVRCP packets pulled from wireshark
namespace {

// AVRCP Get Capabilities Request packet
std::vector<uint8_t> get_capabilities_request = {0x01, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                 0x10, 0x00, 0x00, 0x01, 0x03};

// AVRCP Get Capabilities Request packet with Company ID
std::vector<uint8_t> get_capabilities_request_company_id = {0x01, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                            0x10, 0x00, 0x00, 0x01, 0x02};

// AVRCP Get Capabilities Request packet with Unknown
std::vector<uint8_t> get_capabilities_request_unknown = {0x01, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                         0x10, 0x00, 0x00, 0x01, 0x7f};

// AVRCP Get Capabilities Response to Company ID request
std::vector<uint8_t> get_capabilities_response_company_id = {0x0c, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                             0x10, 0x00, 0x00, 0x08, 0x02, 0x02,
                                                             0x00, 0x19, 0x58, 0x00, 0x23, 0x45};

// AVRCP Get Capabilities Response to Events Supported request
std::vector<uint8_t> get_capabilities_response_events_supported = {
        0x0c, 0x48, 0x00, 0x00, 0x19, 0x58, 0x10, 0x00, 0x00, 0x05, 0x03, 0x03, 0x01, 0x02, 0x05};

// AVRCP Get Element Attributes request for current playing song and attribute
// Title
std::vector<uint8_t> get_element_attributes_request_partial = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x20, 0x00, 0x00, 0x0d, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01};

// AVRCP Get Element Attributes request for current playing song and attributes
// Title, Artist, Album, Media Number, Playing Time, Total Number of Media, and
// Genre
std::vector<uint8_t> get_element_attributes_request_full = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x20, 0x00, 0x00, 0x25, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x01, 0x00,
        0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00,
        0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x06};

// AVRCP Get Element Attributes request for current playing song and attributes
// Title, Artist, Album, Media Number, Playing Time, Total Number of Media,
// Genre, and Cover Art
std::vector<uint8_t> get_element_attributes_request_full_cover_art = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x20, 0x00, 0x00, 0x29, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
        0x02, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x07,
        0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x08};

// AVRCP Get Element Attributes response with attribute values as follows
// Title: "Test Song"
// Artist: "Test Artist"
// Album: "Test Album"
// Track Number: "1"
// Number of Tracks: "2"
// Genre: "Test Genre"
// Duration: "1000"
std::vector<uint8_t> get_elements_attributes_response_full = {
        0x0c, 0x48, 0x00, 0x00, 0x19, 0x58, 0x20, 0x00, 0x00, 0x67, 0x07, 0x00, 0x00, 0x00, 0x01,
        0x00, 0x6a, 0x00, 0x09, 0x54, 0x65, 0x73, 0x74, 0x20, 0x53, 0x6f, 0x6e, 0x67, 0x00, 0x00,
        0x00, 0x02, 0x00, 0x6a, 0x00, 0x0b, 0x54, 0x65, 0x73, 0x74, 0x20, 0x41, 0x72, 0x74, 0x69,
        0x73, 0x74, 0x00, 0x00, 0x00, 0x03, 0x00, 0x6a, 0x00, 0x0a, 0x54, 0x65, 0x73, 0x74, 0x20,
        0x41, 0x6c, 0x62, 0x75, 0x6d, 0x00, 0x00, 0x00, 0x04, 0x00, 0x6a, 0x00, 0x01, 0x31, 0x00,
        0x00, 0x00, 0x05, 0x00, 0x6a, 0x00, 0x01, 0x32, 0x00, 0x00, 0x00, 0x06, 0x00, 0x6a, 0x00,
        0x0a, 0x54, 0x65, 0x73, 0x74, 0x20, 0x47, 0x65, 0x6e, 0x72, 0x65, 0x00, 0x00, 0x00, 0x07,
        0x00, 0x6a, 0x00, 0x04, 0x31, 0x30, 0x30, 0x30};

// AVRCP Get Play Status Request
std::vector<uint8_t> get_play_status_request = {0x01, 0x48, 0x00, 0x00, 0x19,
                                                0x58, 0x30, 0x00, 0x00, 0x00};

// AVRCP Get Play Status Response
std::vector<uint8_t> get_play_status_response = {0x0c, 0x48, 0x00, 0x00, 0x19, 0x58, 0x30,
                                                 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00,
                                                 0xff, 0xff, 0xff, 0xff, 0x00};

// AVRCP List Player Application Setting Attributes Request
std::vector<uint8_t> list_player_application_setting_attributes_request = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x11, 0x00, 0x00, 0x00};

// AVRCP List Player Application Setting Attributes Response
std::vector<uint8_t> list_player_application_setting_attributes_response = {
        0x0c, 0x48, 0x00, 0x00, 0x19, 0x58, 0x11, 0x00, 0x00, 0x03, 0x02, 0x02, 0x03};

// AVRCP List Player Application Setting Attribute Values Request
std::vector<uint8_t> list_player_application_setting_attribute_values_request = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x12, 0x00, 0x00, 0x01, 0x02};

// AVRCP List Player Application Setting Attribute Values - Invalid Setting
// Request
std::vector<uint8_t> invalid_setting_list_player_application_setting_attribute_values_request = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x12, 0x00, 0x00, 0x01, 0xff};

// AVRCP List Player Application Setting Attribute Values - Invalid Length
// Request
std::vector<uint8_t> invalid_length_list_player_application_setting_attribute_values_request = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x12, 0x00, 0x00, 0x00};

// AVRCP List Player Application Setting Attribute Values Response
std::vector<uint8_t> list_player_application_setting_attribute_values_response = {
        0x0c, 0x48, 0x00, 0x00, 0x19, 0x58, 0x12, 0x00, 0x00, 0x05, 0x04, 0x01, 0x02, 0x03, 0x04};

// AVRCP Get Current Player Application Setting Value Request
std::vector<uint8_t> get_current_player_application_setting_value_request = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x13, 0x00, 0x00, 0x03, 0x02, 0x02, 0x03};

// AVRCP Get Current Player Application Setting Value - Invalid Setting
// Request
std::vector<uint8_t> invalid_setting_get_current_player_application_setting_value_request = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x13, 0x00, 0x00, 0x03, 0x02, 0x02, 0x7f};

// AVRCP Get Current Player Application Setting Value - Invalid Length
// Request
std::vector<uint8_t> invalid_length_get_current_player_application_setting_value_request = {
        0x01, 0x48, 0x00, 0x00, 0x19, 0x58, 0x13, 0x00, 0x00, 0x00};

// AVRCP Get Current Player Application Setting Value Response
std::vector<uint8_t> get_current_player_application_setting_value_response = {
        0x0c, 0x48, 0x00, 0x00, 0x19, 0x58, 0x13, 0x00, 0x00, 0x05, 0x02, 0x02, 0x01, 0x03, 0x01};

// AVRCP Set Player Application Setting Value Request
std::vector<uint8_t> set_player_application_setting_value_request = {
        0x00, 0x48, 0x00, 0x00, 0x19, 0x58, 0x14, 0x00, 0x00, 0x05, 0x02, 0x02, 0x01, 0x03, 0x01};

// AVRCP Set Player Application Setting Value Request - Invalid Setting
// Request
std::vector<uint8_t> invalid_setting_set_player_application_setting_value_request = {
        0x00, 0x48, 0x00, 0x00, 0x19, 0x58, 0x14, 0x00, 0x00, 0x05, 0x02, 0x02, 0x01, 0x7f, 0x01};

// AVRCP Set Player Application Setting Value Request - Invalid Value
// Request
std::vector<uint8_t> invalid_value_set_player_application_setting_value_request = {
        0x00, 0x48, 0x00, 0x00, 0x19, 0x58, 0x14, 0x00, 0x00, 0x05, 0x02, 0x02, 0x01, 0x03, 0x7f};

// AVRCP Set Player Application Setting Value Request - Invalid Length
// Request
std::vector<uint8_t> invalid_length_set_player_application_setting_value_request = {
        0x00, 0x48, 0x00, 0x00, 0x19, 0x58, 0x14, 0x00, 0x00, 0x00};

// AVRCP Set Player Application Setting Value Response
std::vector<uint8_t> set_player_application_setting_value_response = {0x09, 0x48, 0x00, 0x00, 0x19,
                                                                      0x58, 0x14, 0x00, 0x00, 0x00};

// AVRCP Pass Through Command Play Pushed Request
std::vector<uint8_t> pass_through_command_play_pushed = {0x00, 0x48, 0x7c, 0x44, 0x00};

// AVRCP Pass Through Command Play Pushed Response
std::vector<uint8_t> pass_through_command_play_released = {0x09, 0x48, 0x7c, 0xc4, 0x00};

// AVRCP Register Playback Status Notification
std::vector<uint8_t> register_play_status_notification = {
        0x03, 0x48, 0x00, 0x00, 0x19, 0x58, 0x31, 0x00, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, 0x05};

// AVRCP Register Volume Changed Notification
std::vector<uint8_t> register_volume_changed_notification = {
        0x03, 0x48, 0x00, 0x00, 0x19, 0x58, 0x31, 0x00, 0x00, 0x05, 0x0d, 0x00, 0x00, 0x00, 0x00};

// AVRCP Register Notification without any parameter
std::vector<uint8_t> register_notification_invalid = {0x03, 0x48, 0x00, 0x00, 0x19, 0x58, 0x31,
                                                      0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00};

// AVRCP Interim Playback Status Notification
std::vector<uint8_t> interim_play_status_notification = {0x0f, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                         0x31, 0x00, 0x00, 0x02, 0x01, 0x00};

// AVRCP Interim Track Changed Notification
std::vector<uint8_t> interim_track_changed_notification = {0x0f, 0x48, 0x00, 0x00, 0x19, 0x58, 0x31,
                                                           0x00, 0x00, 0x09, 0x02, 0x01, 0x02, 0x03,
                                                           0x04, 0x05, 0x06, 0x07, 0x08};

// AVRCP Changed Playback Position Notification
std::vector<uint8_t> changed_play_pos_notification = {
        0x0d, 0x48, 0x00, 0x00, 0x19, 0x58, 0x31, 0x00, 0x00, 0x05, 0x05, 0x00, 0x00, 0x00, 0x00};

// AVRCP Interim Changed Player Setting Notification
std::vector<uint8_t> interim_changed_player_setting_notification = {
        0x0f, 0x48, 0x00, 0x00, 0x19, 0x58, 0x31, 0x00, 0x00, 0x0a,
        0x08, 0x04, 0x01, 0x01, 0x02, 0x01, 0x03, 0x01, 0x04, 0x01};

// AVRCP Changed Player Setting Notification
std::vector<uint8_t> changed_player_setting_notification = {0x0d, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                            0x31, 0x00, 0x00, 0x06, 0x08, 0x02,
                                                            0x02, 0x01, 0x03, 0x02};

// AVRCP Interim Now Playing Changed Notification
std::vector<uint8_t> interim_now_playing_notification = {0x0f, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                         0x31, 0x00, 0x00, 0x01, 0x09};

// AVRCP Interim Available Players Changed Notification
std::vector<uint8_t> interim_available_players_notification = {0x0f, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                               0x31, 0x00, 0x00, 0x01, 0x0a};

// AVRCP Interim Addressed Player Changed Notification with active
// player ID 1
std::vector<uint8_t> interim_addressed_player_notification = {
        0x0f, 0x48, 0x00, 0x00, 0x19, 0x58, 0x31, 0x00, 0x00, 0x05, 0x0b, 0x00, 0x01, 0x00, 0x00};

// AVRCP Interim UIDs Changed Notification
std::vector<uint8_t> interim_uids_notification = {0x0f, 0x48, 0x00, 0x00, 0x19, 0x58, 0x31,
                                                  0x00, 0x00, 0x03, 0x0c, 0x00, 0x00};

// AVRCP Interim Volume Changed Notification with volume at 55% (0x47)
std::vector<uint8_t> interim_volume_changed_notification = {0x0f, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                            0x31, 0x00, 0x00, 0x02, 0x0d, 0x47};

// AVRCP Rejected Volume Changed Notification with volume at 0%
std::vector<uint8_t> rejected_volume_changed_notification = {0x0a, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                             0x31, 0x00, 0x00, 0x02, 0x0d, 0x00};

// AVRCP Changed Volume Changed Notification with volume at 55% (0x47)
std::vector<uint8_t> changed_volume_changed_notification = {0x0d, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                            0x31, 0x00, 0x00, 0x02, 0x0d, 0x47};

// AVRCP Reject List Player Application Settings Response
std::vector<uint8_t> reject_player_app_settings_response = {0x0a, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                            0x11, 0x00, 0x00, 0x01, 0x00};

// AVRCP Browse General Reject packet for invalid PDU ID
std::vector<uint8_t> general_reject_invalid_command_packet = {0xa0, 0x00, 0x01, 0x00};

// AVRCP Browse Get Folder Items Request packet for media players with
// the following data:
//    scope = 0x00 (Media Player List)
//    start_item = 0x00
//    end_item = 0x03
//    attributes_requested: all
std::vector<uint8_t> get_folder_items_request = {0x71, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x00,
                                                 0x00, 0x00, 0x00, 0x00, 0x03, 0x00};

// AVRCP Browse Get Folder Items Request packet for media players with
// the following data:
//    scope = 0x01 (VFS)
//    start_item = 0x00
//    end_item = 0x09
//    attributes_requested: none
std::vector<uint8_t> get_folder_items_request_no_attrs = {0x71, 0x00, 0x0a, 0x01, 0x00, 0x00, 0x00,
                                                          0x00, 0x00, 0x00, 0x00, 0x09, 0xff};

// AVRCP Browse Get Folder Items Request packet for media players with
// the following data:
//    scope = 0x01 (VFS)
//    start_item = 0x00
//    end_item = 0x09
//    attributes_requested: Title
std::vector<uint8_t> get_folder_items_request_title = {0x71, 0x00, 0x0e, 0x01, 0x00, 0x00,
                                                       0x00, 0x00, 0x00, 0x00, 0x00, 0x09,
                                                       0x01, 0x00, 0x00, 0x00, 0x1};

// AVRCP Browse Get Folder Items Request packet for vfs with
// the following data:
//    scope = 0x01 (VFS)
//    start_item = 0x00
//    end_item = 0x05
//    attributes_requested: TITLE
std::vector<uint8_t> get_folder_items_request_vfs = {0x71, 0x00, 0x0e, 0x01, 0x00, 0x00,
                                                     0x00, 0x00, 0x00, 0x00, 0x00, 0x05,
                                                     0x01, 0x00, 0x00, 0x00, 0x01};

// AVRCP Browse Get Folder Items Request packet for now playing with
// the following data:
//    scope = 0x03 (Now Playing)
//    start_item = 0x00
//    end_item = 0x05
//    attributes_requested: All Items
std::vector<uint8_t> get_folder_items_request_now_playing = {
        0x71, 0x00, 0x0a, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00};

// AVRCP Browse Get Folder Items Response packet with range out of bounds error
std::vector<uint8_t> get_folder_items_error_response = {0x71, 0x00, 0x01, 0x0b};

// AVRCP Browse Get Folder Items Response packet for media players
// Contains one media player with the following fields:
//    id = 0x0001
//    name = "com.google.android.music"
//    browsing_supported = true
std::vector<uint8_t> get_folder_items_media_player_response = {
        0x71, 0x00, 0x3c, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x34, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0xb7, 0x01,
        0x0c, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x6a, 0x00, 0x18,
        0x63, 0x6f, 0x6d, 0x2e, 0x67, 0x6f, 0x6f, 0x67, 0x6c, 0x65, 0x2e, 0x61, 0x6e,
        0x64, 0x72, 0x6f, 0x69, 0x64, 0x2e, 0x6d, 0x75, 0x73, 0x69, 0x63};

// AVRCP Browse Get Folder Items Response packet with one folder
// with the following fields:
//    uid = 0x0000000000000001
//    type = 0x00 (Mixed);
//    name = "Test Folder"
//    is_playable = true
std::vector<uint8_t> get_folder_items_folder_response = {
        0x71, 0x00, 0x21, 0x04, 0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x19, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x6a, 0x00,
        0x0b, 0x54, 0x65, 0x73, 0x74, 0x20, 0x46, 0x6f, 0x6c, 0x64, 0x65, 0x72};

// AVRCP Browse Get Folder Items Response packet with one song
// with the following fields:
//    uid = 0x0000000000000002
//    name = "Test Title"
//    attribute[TITLE] = "Test Title"
std::vector<uint8_t> get_folder_items_song_response = {
        0x71, 0x00, 0x32, 0x04, 0x00, 0x00, 0x00, 0x01, 0x03, 0x00, 0x2a, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x6a, 0x00, 0x0a, 0x54, 0x65, 0x73, 0x74,
        0x20, 0x54, 0x69, 0x74, 0x6c, 0x65, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x6a, 0x00,
        0x0a, 0x54, 0x65, 0x73, 0x74, 0x20, 0x54, 0x69, 0x74, 0x6c, 0x65};

// AVRCP Browse Change Path Request down to folder with UID 0x0000000000000002
std::vector<uint8_t> change_path_request = {0x72, 0x00, 0x0b, 0x00, 0x00, 0x01, 0x00,
                                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02};

// AVRCP Browse Change Path Request up
std::vector<uint8_t> change_path_up_request = {0x72, 0x00, 0x0b, 0x00, 0x00, 0x00, 0xFF,
                                               0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// AVRCP Browse Change Path Response with two items in current folder
std::vector<uint8_t> change_path_response = {0x72, 0x00, 0x05, 0x04, 0x00, 0x00, 0x00, 0x02};

// AVRCP Browse Change Path Response with an error of invalid direction
std::vector<uint8_t> change_path_error_response = {0x72, 0x00, 0x01, 0x07};

// AVRCP Get Item Attributes request with all attributes requested
// with the following fields:
//    scope = 0x03 (Now Playing List)
//    uid_counter = 0x0000
//    uid = 0x0000000000000001
std::vector<uint8_t> get_item_attributes_request_all_attributes = {
        0x73, 0x00, 0x28, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x07,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x07};

// AVRCP Get Item Attributes request with all attributes requested
// with the following fields:
//    scope = 0x03 (Now Playing List)
//    uid_counter = 0x0000
//    uid = 0x0000000000000001
//    attributes = Title, Artist, Album, Media Number, Playing Time,
//                 Total Number of Media, Genre, and Cover Art
std::vector<uint8_t> get_item_attributes_request_all_attributes_with_cover_art = {
        0x73, 0x00, 0x2C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x05, 0x00,
        0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x08};

// AVRCP Get Item Attributes request with all attributes requested
// with the following fields:
//    scope = 0x03 (Now Playing List)
//    uid_counter = 0x0001
//    uid = 0x0000000000000001
std::vector<uint8_t> get_item_attributes_request_all_attributes_invalid = {
        0x73, 0x00, 0x28, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x07,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x07};

// AVRCP Get Item Attributes Response with the following attributes:
//    title = "Test Song"
//    artist = "Test Artist"
//    album = "Test Album"
std::vector<uint8_t> get_item_attributes_song_response = {
        0x73, 0x00, 0x38, 0x04, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x6a, 0x00, 0x09, 0x54, 0x65,
        0x73, 0x74, 0x20, 0x53, 0x6f, 0x6e, 0x67, 0x00, 0x00, 0x00, 0x02, 0x00, 0x6a, 0x00, 0x0b,
        0x54, 0x65, 0x73, 0x74, 0x20, 0x41, 0x72, 0x74, 0x69, 0x73, 0x74, 0x00, 0x00, 0x00, 0x03,
        0x00, 0x6a, 0x00, 0x0a, 0x54, 0x65, 0x73, 0x74, 0x20, 0x41, 0x6c, 0x62, 0x75, 0x6d};

// AVRCP Set Addressed Player Request with player_id = 0
std::vector<uint8_t> set_addressed_player_request = {0x00, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                     0x60, 0x00, 0x00, 0x02, 0x00, 0x00};

// AVRCP Set Addressed Player Request with player_id = 1
std::vector<uint8_t> set_addressed_player_id_1_request = {0x00, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                          0x60, 0x00, 0x00, 0x02, 0x00, 0x01};

// AVRCP Set Addressed Player Response with status success
std::vector<uint8_t> set_addressed_player_response = {0x09, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                      0x60, 0x00, 0x00, 0x01, 0x04};

// AVRCP Set Browsed Player Request with player_id = 2
std::vector<uint8_t> set_browsed_player_request = {0x70, 0x00, 0x02, 0x00, 0x02};

// AVRCP Set Browsed Player Request with player_id = 0
std::vector<uint8_t> set_browsed_player_id_0_request = {0x70, 0x00, 0x02, 0x00, 0x00};

// AVRCP Set Browsed Player Response with num items = 4 and depth = 0
std::vector<uint8_t> set_browsed_player_response = {0x70, 0x00, 0x0a, 0x04, 0x00, 0x00, 0x00,
                                                    0x00, 0x00, 0x04, 0x00, 0x6a, 0x00};

// AVRCP Get Total Number of Items Request with Scope = Media Player List
std::vector<uint8_t> get_total_number_of_items_request_media_players = {0x75, 0x00, 0x01, 0x00};

// AVRCP Get Total Number of Items Request with Scope = VFS
std::vector<uint8_t> get_total_number_of_items_request_vfs = {0x75, 0x00, 0x01, 0x01};

// AVRCP Get Total Number of Items Request with Scope = Now Playing List
std::vector<uint8_t> get_total_number_of_items_request_now_playing = {0x75, 0x00, 0x01, 0x03};

// AVRCP Get Total number of Items Response with 5 items in folder
std::vector<uint8_t> get_total_number_of_items_response = {0x75, 0x00, 0x07, 0x04, 0x00,
                                                           0x00, 0x00, 0x00, 0x00, 0x05};

// AVRCP Play Item Request with scope = Now Playing and
// UID = 0x0000000000000003
std::vector<uint8_t> play_item_request = {0x00, 0x48, 0x00, 0x00, 0x19, 0x58, 0x74,
                                          0x00, 0x00, 0x0b, 0x03, 0x00, 0x00, 0x00,
                                          0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00};

// AVRCP Play Item Response
std::vector<uint8_t> play_item_response = {0x09, 0x48, 0x00, 0x00, 0x19, 0x58,
                                           0x74, 0x00, 0x00, 0x01, 0x04};

// AVRCP Set Absolute Volume Request with volume at 56% (0x48)
std::vector<uint8_t> set_absolute_volume_request = {0x00, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                    0x50, 0x00, 0x00, 0x01, 0x48};

// AVRCP Set Absolute Volume Response with voume at 52% (0x43)
std::vector<uint8_t> set_absolute_volume_response = {0x09, 0x48, 0x00, 0x00, 0x19, 0x58,
                                                     0x50, 0x00, 0x00, 0x01, 0x43};

// Invalid Packets
// Short Vendor Packet
std::vector<uint8_t> short_vendor_packet = {0x01, 0x48, 0x00, 0x00, 0x19,
                                            0x58, 0x10, 0x00, 0x00, 0x01};

// Short Get Capabilities Request Packet
std::vector<uint8_t> short_get_capabilities_request = {0x01, 0x48, 0x00, 0x00, 0x19,
                                                       0x58, 0x10, 0x00, 0x00, 0x00};

// Short Get Element Attributes Request Packet
std::vector<uint8_t> short_get_element_attributes_request = {0x01, 0x48, 0x00, 0x00, 0x19,
                                                             0x58, 0x20, 0x00, 0x00, 0x00};

// Short Play Item Request Packet
std::vector<uint8_t> short_play_item_request = {0x00, 0x48, 0x00, 0x00, 0x19,
                                                0x58, 0x74, 0x00, 0x00, 0x00};

// Short Set Addressed Player Request Packet
std::vector<uint8_t> short_set_addressed_player_request = {0x00, 0x48, 0x00, 0x00, 0x19,
                                                           0x58, 0x60, 0x00, 0x00, 0x00};

// Short Browse Packet
std::vector<uint8_t> short_browse_packet = {0x71, 0x00, 0x0a};

// Short Get Folder Items Request Packet
std::vector<uint8_t> short_get_folder_items_request = {0x71, 0x00, 0x00};

// Short Get Total Number of Items Request Packet
std::vector<uint8_t> short_get_total_number_of_items_request = {0x75, 0x00, 0x00};

// Short Change Path Request Packet
std::vector<uint8_t> short_change_path_request = {0x72, 0x00, 0x00};

// Short Get Item Attributes Request Packet
std::vector<uint8_t> short_get_item_attributes_request = {0x73, 0x00, 0x00};

}  // namespace
