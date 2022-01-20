/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "gd/rust/topshim/hfp/hfp_shim.h"

#include "btif/include/btif_hf.h"
#include "gd/os/log.h"
#include "include/hardware/bt_hf.h"
#include "src/profiles/hfp.rs.h"
#include "types/raw_address.h"

namespace rusty = ::bluetooth::topshim::rust;

namespace bluetooth {
namespace topshim {
namespace rust {
namespace internal {
static HfpIntf* g_hfpif;

// TODO (b/204488136): Refactor to have a2dp, gatt and hfp share these helpers.
static RustRawAddress to_rust_address(const RawAddress& addr) {
  RustRawAddress raddr;
  std::copy(std::begin(addr.address), std::end(addr.address), std::begin(raddr.address));
  return raddr;
}

static RawAddress from_rust_address(const RustRawAddress& raddr) {
  RawAddress addr;
  addr.FromOctets(raddr.address.data());
  return addr;
}

static void connection_state_cb(bluetooth::headset::bthf_connection_state_t state, RawAddress* addr) {
  RustRawAddress raddr = to_rust_address(*addr);
  rusty::hfp_connection_state_callback(state, raddr);
}

}  // namespace internal

class DBusHeadsetCallbacks : public headset::Callbacks {
 public:
  static Callbacks* GetInstance(headset::Interface* headset) {
    static Callbacks* instance = new DBusHeadsetCallbacks(headset);
    return instance;
  }

  DBusHeadsetCallbacks(headset::Interface* headset) : headset_(headset){};

  void ConnectionStateCallback(headset::bthf_connection_state_t state, RawAddress* bd_addr) override {
    LOG_WARN("ConnectionStateCallback from %s", bd_addr->ToString().c_str());
    topshim::rust::internal::connection_state_cb(state, bd_addr);
  }

  void AudioStateCallback(
      [[maybe_unused]] headset::bthf_audio_state_t state, [[maybe_unused]] RawAddress* bd_addr) override {}

  void VoiceRecognitionCallback(
      [[maybe_unused]] headset::bthf_vr_state_t state, [[maybe_unused]] RawAddress* bd_addr) override {}

  void AnswerCallCallback([[maybe_unused]] RawAddress* bd_addr) override {}

  void HangupCallCallback([[maybe_unused]] RawAddress* bd_addr) override {}

  void VolumeControlCallback(
      [[maybe_unused]] headset::bthf_volume_type_t type,
      [[maybe_unused]] int volume,
      [[maybe_unused]] RawAddress* bd_addr) override {}

  void DialCallCallback([[maybe_unused]] char* number, [[maybe_unused]] RawAddress* bd_addr) override {}

  void DtmfCmdCallback([[maybe_unused]] char tone, [[maybe_unused]] RawAddress* bd_addr) override {}

  void NoiseReductionCallback(
      [[maybe_unused]] headset::bthf_nrec_t nrec, [[maybe_unused]] RawAddress* bd_addr) override {}

  void WbsCallback([[maybe_unused]] headset::bthf_wbs_config_t wbs, [[maybe_unused]] RawAddress* bd_addr) override {}

  void AtChldCallback([[maybe_unused]] headset::bthf_chld_type_t chld, [[maybe_unused]] RawAddress* bd_addr) override {}

  void AtCnumCallback([[maybe_unused]] RawAddress* bd_addr) override {}

  void AtCindCallback(RawAddress* bd_addr) override {
    /* This is required to setup the SLC, the format of the response should be
     * +CIND: <call>,<callsetup>,<service>,<signal>,<roam>,<battery>,<callheld>
     */
    LOG_WARN("Respond +CIND: 0,0,0,0,0,0,0 to AT+CIND? from %s", bd_addr->ToString().c_str());

    /* headset::Interface::CindResponse's parameters are similar but different
     * from the actual CIND response. It will construct the final response for
     * you based on the arguments you provide.
     * CindResponse(network_service_availability, active_call_num,
     *              held_call_num, callsetup_state, signal_strength,
     *              roam_state, battery_level, bd_addr);
     */
    headset_->CindResponse(0, 0, 0, headset::BTHF_CALL_STATE_IDLE, 0, 0, 0, bd_addr);
  }

  void AtCopsCallback(RawAddress* bd_addr) override {
    LOG_WARN("Respond +COPS: 0 to AT+COPS? from %s", bd_addr->ToString().c_str());
    headset_->CopsResponse("", bd_addr);
  }

  void AtClccCallback(RawAddress* bd_addr) override {
    LOG_WARN("AT+CLCC from addr %s: Enhanced Call Status is not supported.", bd_addr->ToString().c_str());
    /*
    If we want to support the Enhanced Call Status feature, we need to use this
    callback to send response like "+CLCC: 0,0,0,0,0," with the following codes.
    headset_->ClccResponse(
        0,
        headset::BTHF_CALL_DIRECTION_OUTGOING,
        headset::BTHF_CALL_STATE_ACTIVE,
        headset::BTHF_CALL_TYPE_VOICE,
        headset::BTHF_CALL_MPTY_TYPE_SINGLE,
        NULL,
        headset::BTHF_CALL_ADDRTYPE_UNKNOWN,
        bd_addr);
    */
  }

  void UnknownAtCallback(char* at_string, RawAddress* bd_addr) override {
    LOG_WARN("Reply Error to UnknownAtCallback:%s", at_string);
    headset_->AtResponse(headset::BTHF_AT_RESPONSE_ERROR, 0, bd_addr);
  }

  void KeyPressedCallback([[maybe_unused]] RawAddress* bd_addr) override {}

  void AtBindCallback(char* at_string, RawAddress* bd_addr) override {
    LOG_WARN(
        "AT+BIND %s from addr %s: Bluetooth HF Indicators is not supported.", at_string, bd_addr->ToString().c_str());
  }

  void AtBievCallback(headset::bthf_hf_ind_type_t ind_id, int ind_value, RawAddress* bd_addr) override {
    LOG_WARN(
        "AT+BIEV=%d,%d from addr %s: Bluetooth HF Indicators is not supported.",
        ind_id,
        ind_value,
        bd_addr->ToString().c_str());
  }

  void AtBiaCallback(bool service, bool roam, bool signal, bool battery, RawAddress* bd_addr) override {
    LOG_WARN("AT+BIA=,,%d,%d,%d,%d,from addr %s", service, signal, roam, battery, bd_addr->ToString().c_str());
  }

 private:
  headset::Interface* headset_;
};

int HfpIntf::init() {
  return intf_->Init(DBusHeadsetCallbacks::GetInstance(intf_), 1, false);
}

int HfpIntf::connect(RustRawAddress bt_addr) {
  RawAddress addr = internal::from_rust_address(bt_addr);
  return intf_->Connect(&addr);
}

int HfpIntf::connect_audio(RustRawAddress bt_addr) {
  RawAddress addr = internal::from_rust_address(bt_addr);
  return intf_->ConnectAudio(&addr);
}

int HfpIntf::disconnect(RustRawAddress bt_addr) {
  RawAddress addr = internal::from_rust_address(bt_addr);
  return intf_->Disconnect(&addr);
}

int HfpIntf::disconnect_audio(RustRawAddress bt_addr) {
  RawAddress addr = internal::from_rust_address(bt_addr);
  return intf_->DisconnectAudio(&addr);
}

void HfpIntf::cleanup() {}

std::unique_ptr<HfpIntf> GetHfpProfile(const unsigned char* btif) {
  if (internal::g_hfpif) std::abort();

  const bt_interface_t* btif_ = reinterpret_cast<const bt_interface_t*>(btif);

  auto hfpif = std::make_unique<HfpIntf>(const_cast<headset::Interface*>(
      reinterpret_cast<const headset::Interface*>(btif_->get_profile_interface("handsfree"))));
  internal::g_hfpif = hfpif.get();

  return hfpif;
}

}  // namespace rust
}  // namespace topshim
}  // namespace bluetooth
