/*   Copyright 2019 HIMSA II K/S - www.himsa.com
 * Represented by EHIMA - www.ehima.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "BluetoothLeAudioServiceJni"

#include <hardware/bluetooth.h>

#include <array>
#include <optional>
#include <shared_mutex>

#include "com_android_bluetooth.h"
#include "hardware/bt_le_audio.h"

using bluetooth::le_audio::BroadcastAudioProfile;
using bluetooth::le_audio::BroadcastId;
using bluetooth::le_audio::BroadcastState;
using bluetooth::le_audio::btle_audio_codec_config_t;
using bluetooth::le_audio::btle_audio_codec_index_t;
using bluetooth::le_audio::ConnectionState;
using bluetooth::le_audio::GroupNodeStatus;
using bluetooth::le_audio::GroupStatus;
using bluetooth::le_audio::LeAudioBroadcasterCallbacks;
using bluetooth::le_audio::LeAudioBroadcasterInterface;
using bluetooth::le_audio::LeAudioClientCallbacks;
using bluetooth::le_audio::LeAudioClientInterface;

namespace android {
static jmethodID method_onConnectionStateChanged;
static jmethodID method_onGroupStatus;
static jmethodID method_onGroupNodeStatus;
static jmethodID method_onAudioConf;
static jmethodID method_onSinkAudioLocationAvailable;
static jmethodID method_onAudioLocalCodecCapabilities;
static jmethodID method_onAudioGroupCodecConf;

static struct {
  jclass clazz;
  jmethodID constructor;
  jmethodID getCodecType;
} android_bluetooth_BluetoothLeAudioCodecConfig;

static LeAudioClientInterface* sLeAudioClientInterface = nullptr;
static std::shared_timed_mutex interface_mutex;

static jobject mCallbacksObj = nullptr;
static std::shared_timed_mutex callbacks_mutex;

jobject prepareCodecConfigObj(JNIEnv* env,
                              btle_audio_codec_config_t codecConfig) {
  jobject codecConfigObj =
      env->NewObject(android_bluetooth_BluetoothLeAudioCodecConfig.clazz,
                     android_bluetooth_BluetoothLeAudioCodecConfig.constructor,
                     (jint)codecConfig.codec_type, 0, 0, 0, 0, 0, 0, 0, 0);
  return codecConfigObj;
}

jobjectArray prepareArrayOfCodecConfigs(
    JNIEnv* env, std::vector<btle_audio_codec_config_t> codecConfigs) {
  jsize i = 0;
  jobjectArray CodecConfigArray = env->NewObjectArray(
      (jsize)codecConfigs.size(),
      android_bluetooth_BluetoothLeAudioCodecConfig.clazz, nullptr);

  for (auto const& cap : codecConfigs) {
    jobject Obj = prepareCodecConfigObj(env, cap);

    env->SetObjectArrayElement(CodecConfigArray, i++, Obj);
    env->DeleteLocalRef(Obj);
  }

  return CodecConfigArray;
}

class LeAudioClientCallbacksImpl : public LeAudioClientCallbacks {
 public:
  ~LeAudioClientCallbacksImpl() = default;

  void OnConnectionState(ConnectionState state,
                         const RawAddress& bd_addr) override {
    LOG(INFO) << __func__ << ", state:" << int(state);

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

    ScopedLocalRef<jbyteArray> addr(
        sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
    if (!addr.get()) {
      LOG(ERROR) << "Failed to new jbyteArray bd addr for connection state";
      return;
    }

    sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                     (jbyte*)&bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged,
                                 (jint)state, addr.get());
  }

  void OnGroupStatus(int group_id, GroupStatus group_status) override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGroupStatus,
                                 (jint)group_id, (jint)group_status);
  }

  void OnGroupNodeStatus(const RawAddress& bd_addr, int group_id,
                         GroupNodeStatus node_status) override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

    ScopedLocalRef<jbyteArray> addr(
        sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
    if (!addr.get()) {
      LOG(ERROR) << "Failed to new jbyteArray bd addr for group status";
      return;
    }

    sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                     (jbyte*)&bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGroupNodeStatus,
                                 addr.get(), (jint)group_id, (jint)node_status);
  }

  void OnAudioConf(uint8_t direction, int group_id,
                   uint32_t sink_audio_location, uint32_t source_audio_location,
                   uint16_t avail_cont) override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAudioConf,
                                 (jint)direction, (jint)group_id,
                                 (jint)sink_audio_location,
                                 (jint)source_audio_location, (jint)avail_cont);
  }

  void OnSinkAudioLocationAvailable(const RawAddress& bd_addr,
                                    uint32_t sink_audio_location) override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

    ScopedLocalRef<jbyteArray> addr(
        sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
    if (!addr.get()) {
      LOG(ERROR) << "Failed to new jbyteArray bd addr for group status";
      return;
    }

    sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                     (jbyte*)&bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj,
                                 method_onSinkAudioLocationAvailable,
                                 addr.get(), (jint)sink_audio_location);
  }

  void OnAudioLocalCodecCapabilities(
      std::vector<btle_audio_codec_config_t> local_input_capa_codec_conf,
      std::vector<btle_audio_codec_config_t> local_output_capa_codec_conf)
      override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

    jobject localInputCapCodecConfigArray = prepareArrayOfCodecConfigs(
        sCallbackEnv.get(), local_input_capa_codec_conf);

    jobject localOutputCapCodecConfigArray = prepareArrayOfCodecConfigs(
        sCallbackEnv.get(), local_output_capa_codec_conf);

    sCallbackEnv->CallVoidMethod(
        mCallbacksObj, method_onAudioLocalCodecCapabilities,
        localInputCapCodecConfigArray, localOutputCapCodecConfigArray);
  }

  void OnAudioGroupCodecConf(
      int group_id, btle_audio_codec_config_t input_codec_conf,
      btle_audio_codec_config_t output_codec_conf,
      std::vector<btle_audio_codec_config_t> input_selectable_codec_conf,
      std::vector<btle_audio_codec_config_t> output_selectable_codec_conf)
      override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(callbacks_mutex);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid() || mCallbacksObj == nullptr) return;

    jobject inputCodecConfigObj =
        prepareCodecConfigObj(sCallbackEnv.get(), input_codec_conf);
    jobject outputCodecConfigObj =
        prepareCodecConfigObj(sCallbackEnv.get(), input_codec_conf);
    jobject inputSelectableCodecConfigArray = prepareArrayOfCodecConfigs(
        sCallbackEnv.get(), input_selectable_codec_conf);
    jobject outputSelectableCodecConfigArray = prepareArrayOfCodecConfigs(
        sCallbackEnv.get(), output_selectable_codec_conf);

    sCallbackEnv->CallVoidMethod(
        mCallbacksObj, method_onAudioGroupCodecConf, (jint)group_id,
        inputCodecConfigObj, outputCodecConfigObj,
        inputSelectableCodecConfigArray, outputSelectableCodecConfigArray);
  }
};

static LeAudioClientCallbacksImpl sLeAudioClientCallbacks;

static void classInitNative(JNIEnv* env, jclass clazz) {
  jclass jniBluetoothLeAudioCodecConfigClass =
      env->FindClass("android/bluetooth/BluetoothLeAudioCodecConfig");
  android_bluetooth_BluetoothLeAudioCodecConfig.constructor = env->GetMethodID(
      jniBluetoothLeAudioCodecConfigClass, "<init>", "(IIIIIIIII)V");
  android_bluetooth_BluetoothLeAudioCodecConfig.getCodecType = env->GetMethodID(
      jniBluetoothLeAudioCodecConfigClass, "getCodecType", "()I");

  method_onGroupStatus = env->GetMethodID(clazz, "onGroupStatus", "(II)V");
  method_onGroupNodeStatus =
      env->GetMethodID(clazz, "onGroupNodeStatus", "([BII)V");
  method_onAudioConf = env->GetMethodID(clazz, "onAudioConf", "(IIIII)V");
  method_onSinkAudioLocationAvailable =
      env->GetMethodID(clazz, "onSinkAudioLocationAvailable", "([BI)V");
  method_onConnectionStateChanged =
      env->GetMethodID(clazz, "onConnectionStateChanged", "(I[B)V");
  method_onAudioLocalCodecCapabilities =
      env->GetMethodID(clazz, "onAudioLocalCodecCapabilities",
                       "([Landroid/bluetooth/BluetoothLeAudioCodecConfig;"
                       "[Landroid/bluetooth/BluetoothLeAudioCodecConfig;)V");
  method_onAudioGroupCodecConf =
      env->GetMethodID(clazz, "onAudioGroupCodecConf",
                       "(ILandroid/bluetooth/BluetoothLeAudioCodecConfig;"
                       "Landroid/bluetooth/BluetoothLeAudioCodecConfig;"
                       "[Landroid/bluetooth/BluetoothLeAudioCodecConfig;"
                       "[Landroid/bluetooth/BluetoothLeAudioCodecConfig;)V");
}

std::vector<btle_audio_codec_config_t> prepareCodecPreferences(
    JNIEnv* env, jobject object, jobjectArray codecConfigArray) {
  std::vector<btle_audio_codec_config_t> codec_preferences;

  int numConfigs = env->GetArrayLength(codecConfigArray);
  for (int i = 0; i < numConfigs; i++) {
    jobject jcodecConfig = env->GetObjectArrayElement(codecConfigArray, i);
    if (jcodecConfig == nullptr) continue;
    if (!env->IsInstanceOf(
            jcodecConfig,
            android_bluetooth_BluetoothLeAudioCodecConfig.clazz)) {
      ALOGE("%s: Invalid BluetoothLeAudioCodecConfig instance", __func__);
      continue;
    }
    jint codecType = env->CallIntMethod(
        jcodecConfig,
        android_bluetooth_BluetoothLeAudioCodecConfig.getCodecType);

    btle_audio_codec_config_t codec_config = {
        .codec_type = static_cast<btle_audio_codec_index_t>(codecType)};

    codec_preferences.push_back(codec_config);
  }
  return codec_preferences;
}

static void initNative(JNIEnv* env, jobject object,
                       jobjectArray codecOffloadingArray) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    LOG(ERROR) << "Bluetooth module is not loaded";
    return;
  }

  if (mCallbacksObj != nullptr) {
    LOG(INFO) << "Cleaning up LeAudio callback object";
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }

  if ((mCallbacksObj = env->NewGlobalRef(object)) == nullptr) {
    LOG(ERROR) << "Failed to allocate Global Ref for LeAudio Callbacks";
    return;
  }

  android_bluetooth_BluetoothLeAudioCodecConfig.clazz =
      (jclass)env->NewGlobalRef(
          env->FindClass("android/bluetooth/BluetoothLeAudioCodecConfig"));
  if (android_bluetooth_BluetoothLeAudioCodecConfig.clazz == nullptr) {
    LOG(ERROR) << "Failed to allocate Global Ref for "
                  "BluetoothLeAudioCodecConfig class";
    return;
  }

  sLeAudioClientInterface =
      (LeAudioClientInterface*)btInf->get_profile_interface(
          BT_PROFILE_LE_AUDIO_ID);
  if (sLeAudioClientInterface == nullptr) {
    LOG(ERROR) << "Failed to get Bluetooth LeAudio Interface";
    return;
  }

  std::vector<btle_audio_codec_config_t> codec_offloading =
      prepareCodecPreferences(env, object, codecOffloadingArray);

  sLeAudioClientInterface->Initialize(&sLeAudioClientCallbacks,
                                      codec_offloading);
}

static void cleanupNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(interface_mutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(callbacks_mutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    LOG(ERROR) << "Bluetooth module is not loaded";
    return;
  }

  if (sLeAudioClientInterface != nullptr) {
    sLeAudioClientInterface->Cleanup();
    sLeAudioClientInterface = nullptr;
  }

  env->DeleteGlobalRef(android_bluetooth_BluetoothLeAudioCodecConfig.clazz);
  android_bluetooth_BluetoothLeAudioCodecConfig.clazz = nullptr;

  if (mCallbacksObj != nullptr) {
    env->DeleteGlobalRef(mCallbacksObj);
    mCallbacksObj = nullptr;
  }
}

static jboolean connectLeAudioNative(JNIEnv* env, jobject object,
                                     jbyteArray address) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  RawAddress* tmpraw = (RawAddress*)addr;
  sLeAudioClientInterface->Connect(*tmpraw);
  env->ReleaseByteArrayElements(address, addr, 0);
  return JNI_TRUE;
}

static jboolean disconnectLeAudioNative(JNIEnv* env, jobject object,
                                        jbyteArray address) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  RawAddress* tmpraw = (RawAddress*)addr;
  sLeAudioClientInterface->Disconnect(*tmpraw);
  env->ReleaseByteArrayElements(address, addr, 0);
  return JNI_TRUE;
}

static jboolean groupAddNodeNative(JNIEnv* env, jobject object, jint group_id,
                                   jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  jbyte* addr = env->GetByteArrayElements(address, nullptr);

  if (!sLeAudioClientInterface) {
    LOG(ERROR) << __func__ << ": Failed to get the Bluetooth LeAudio Interface";
    return JNI_FALSE;
  }

  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  RawAddress* tmpraw = (RawAddress*)addr;
  sLeAudioClientInterface->GroupAddNode(group_id, *tmpraw);
  env->ReleaseByteArrayElements(address, addr, 0);

  return JNI_TRUE;
}

static jboolean groupRemoveNodeNative(JNIEnv* env, jobject object,
                                      jint group_id, jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);
  if (!sLeAudioClientInterface) {
    LOG(ERROR) << __func__ << ": Failed to get the Bluetooth LeAudio Interface";
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  RawAddress* tmpraw = (RawAddress*)addr;
  sLeAudioClientInterface->GroupRemoveNode(group_id, *tmpraw);
  env->ReleaseByteArrayElements(address, addr, 0);
  return JNI_TRUE;
}

static void groupSetActiveNative(JNIEnv* env, jobject object, jint group_id) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (!sLeAudioClientInterface) {
    LOG(ERROR) << __func__ << ": Failed to get the Bluetooth LeAudio Interface";
    return;
  }

  sLeAudioClientInterface->GroupSetActive(group_id);
}

static void setCodecConfigPreferenceNative(JNIEnv* env, jobject object,
                                           jint group_id,
                                           jobject inputCodecConfig,
                                           jobject outputCodecConfig) {
  std::shared_lock<std::shared_timed_mutex> lock(interface_mutex);

  if (!env->IsInstanceOf(inputCodecConfig,
                         android_bluetooth_BluetoothLeAudioCodecConfig.clazz) ||
      !env->IsInstanceOf(outputCodecConfig,
                         android_bluetooth_BluetoothLeAudioCodecConfig.clazz)) {
    ALOGE("%s: Invalid BluetoothLeAudioCodecConfig instance", __func__);
    return;
  }

  jint inputCodecType = env->CallIntMethod(
      inputCodecConfig,
      android_bluetooth_BluetoothLeAudioCodecConfig.getCodecType);

  btle_audio_codec_config_t input_codec_config = {
      .codec_type = static_cast<btle_audio_codec_index_t>(inputCodecType)};

  jint outputCodecType = env->CallIntMethod(
      outputCodecConfig,
      android_bluetooth_BluetoothLeAudioCodecConfig.getCodecType);

  btle_audio_codec_config_t output_codec_config = {
      .codec_type = static_cast<btle_audio_codec_index_t>(outputCodecType)};

  sLeAudioClientInterface->SetCodecConfigPreference(
      group_id, input_codec_config, output_codec_config);
}

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void*)classInitNative},
    {"initNative", "([Landroid/bluetooth/BluetoothLeAudioCodecConfig;)V",
     (void*)initNative},
    {"cleanupNative", "()V", (void*)cleanupNative},
    {"connectLeAudioNative", "([B)Z", (void*)connectLeAudioNative},
    {"disconnectLeAudioNative", "([B)Z", (void*)disconnectLeAudioNative},
    {"groupAddNodeNative", "(I[B)Z", (void*)groupAddNodeNative},
    {"groupRemoveNodeNative", "(I[B)Z", (void*)groupRemoveNodeNative},
    {"groupSetActiveNative", "(I)V", (void*)groupSetActiveNative},
    {"setCodecConfigPreferenceNative",
     "(ILandroid/bluetooth/BluetoothLeAudioCodecConfig;Landroid/bluetooth/"
     "BluetoothLeAudioCodecConfig;)V",
     (void*)setCodecConfigPreferenceNative},
};

/* Le Audio Broadcaster */
static jmethodID method_onBroadcastCreated;
static jmethodID method_onBroadcastDestroyed;
static jmethodID method_onBroadcastStateChanged;

static LeAudioBroadcasterInterface* sLeAudioBroadcasterInterface = nullptr;
static std::shared_timed_mutex sBroadcasterInterfaceMutex;

static jobject sBroadcasterCallbacksObj = nullptr;
static std::shared_timed_mutex sBroadcasterCallbacksMutex;

class LeAudioBroadcasterCallbacksImpl : public LeAudioBroadcasterCallbacks {
 public:
  ~LeAudioBroadcasterCallbacksImpl() = default;

  void OnBroadcastCreated(uint32_t broadcast_id, bool success) override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterCallbacksMutex);
    CallbackEnv sCallbackEnv(__func__);

    if (!sCallbackEnv.valid() || sBroadcasterCallbacksObj == nullptr) return;
    sCallbackEnv->CallVoidMethod(sBroadcasterCallbacksObj,
                                 method_onBroadcastCreated, (jint)broadcast_id,
                                 success ? JNI_TRUE : JNI_FALSE);
  }

  void OnBroadcastDestroyed(uint32_t broadcast_id) override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterCallbacksMutex);
    CallbackEnv sCallbackEnv(__func__);

    if (!sCallbackEnv.valid() || sBroadcasterCallbacksObj == nullptr) return;
    sCallbackEnv->CallVoidMethod(sBroadcasterCallbacksObj,
                                 method_onBroadcastDestroyed,
                                 (jint)broadcast_id);
  }

  void OnBroadcastStateChanged(uint32_t broadcast_id,
                               BroadcastState state) override {
    LOG(INFO) << __func__;

    std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterCallbacksMutex);
    CallbackEnv sCallbackEnv(__func__);

    if (!sCallbackEnv.valid() || sBroadcasterCallbacksObj == nullptr) return;
    sCallbackEnv->CallVoidMethod(
        sBroadcasterCallbacksObj, method_onBroadcastStateChanged,
        (jint)broadcast_id,
        (jint) static_cast<std::underlying_type<BroadcastState>::type>(state));
  }
};

static LeAudioBroadcasterCallbacksImpl sLeAudioBroadcasterCallbacks;

static void BroadcasterClassInitNative(JNIEnv* env, jclass clazz) {
  method_onBroadcastCreated =
      env->GetMethodID(clazz, "onBroadcastCreated", "(IZ)V");
  method_onBroadcastDestroyed =
      env->GetMethodID(clazz, "onBroadcastDestroyed", "(I)V");
  method_onBroadcastStateChanged =
      env->GetMethodID(clazz, "onBroadcastStateChanged", "(II)V");
}

static void BroadcasterInitNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(
      sBroadcasterInterfaceMutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(
      sBroadcasterCallbacksMutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    LOG(ERROR) << "Bluetooth module is not loaded";
    return;
  }

  if (sBroadcasterCallbacksObj != nullptr) {
    LOG(INFO) << "Cleaning up LeAudio Broadcaster callback object";
    env->DeleteGlobalRef(sBroadcasterCallbacksObj);
    sBroadcasterCallbacksObj = nullptr;
  }

  if ((sBroadcasterCallbacksObj = env->NewGlobalRef(object)) == nullptr) {
    LOG(ERROR)
        << "Failed to allocate Global Ref for LeAudio Broadcaster Callbacks";
    return;
  }

  sLeAudioBroadcasterInterface =
      (LeAudioBroadcasterInterface*)btInf->get_profile_interface(
          BT_PROFILE_LE_AUDIO_BROADCASTER_ID);
  if (sLeAudioBroadcasterInterface == nullptr) {
    LOG(ERROR) << "Failed to get Bluetooth LeAudio Broadcaster Interface";
    return;
  }

  sLeAudioBroadcasterInterface->Initialize(&sLeAudioBroadcasterCallbacks);
}

static void BroadcasterStopNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(
      sBroadcasterInterfaceMutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    LOG(ERROR) << "Bluetooth module is not loaded";
    return;
  }

  if (sLeAudioBroadcasterInterface != nullptr)
    sLeAudioBroadcasterInterface->Stop();
}

static void BroadcasterCleanupNative(JNIEnv* env, jobject object) {
  std::unique_lock<std::shared_timed_mutex> interface_lock(
      sBroadcasterInterfaceMutex);
  std::unique_lock<std::shared_timed_mutex> callbacks_lock(
      sBroadcasterCallbacksMutex);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == nullptr) {
    LOG(ERROR) << "Bluetooth module is not loaded";
    return;
  }

  if (sLeAudioBroadcasterInterface != nullptr) {
    sLeAudioBroadcasterInterface->Cleanup();
    sLeAudioBroadcasterInterface = nullptr;
  }

  if (sBroadcasterCallbacksObj != nullptr) {
    env->DeleteGlobalRef(sBroadcasterCallbacksObj);
    sBroadcasterCallbacksObj = nullptr;
  }
}

static void CreateBroadcastNative(JNIEnv* env, jobject object,
                                  jbyteArray metadata, jint audio_profile,
                                  jbyteArray broadcast_code) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) return;

  std::array<uint8_t, 16> code_array;
  if (broadcast_code)
    env->GetByteArrayRegion(broadcast_code, 0, 16, (jbyte*)code_array.data());

  jbyte* meta = env->GetByteArrayElements(metadata, nullptr);
  sLeAudioBroadcasterInterface->CreateBroadcast(
      std::vector<uint8_t>(meta, meta + env->GetArrayLength(metadata)),
      static_cast<BroadcastAudioProfile>(audio_profile),
      broadcast_code ? std::optional<std::array<uint8_t, 16>>(code_array)
                     : std::nullopt);
  env->ReleaseByteArrayElements(metadata, meta, 0);
}

static void UpdateMetadataNative(JNIEnv* env, jobject object, jint broadcast_id,
                                 jbyteArray metadata) {
  jbyte* meta = env->GetByteArrayElements(metadata, nullptr);
  sLeAudioBroadcasterInterface->UpdateMetadata(
      broadcast_id,
      std::vector<uint8_t>(meta, meta + env->GetArrayLength(metadata)));
  env->ReleaseByteArrayElements(metadata, meta, 0);
}

static void StartBroadcastNative(JNIEnv* env, jobject object,
                                 jint broadcast_id) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) return;
  sLeAudioBroadcasterInterface->StartBroadcast(broadcast_id);
}

static void StopBroadcastNative(JNIEnv* env, jobject object,
                                jint broadcast_id) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) return;
  sLeAudioBroadcasterInterface->StopBroadcast(broadcast_id);
}

static void PauseBroadcastNative(JNIEnv* env, jobject object,
                                 jint broadcast_id) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) return;
  sLeAudioBroadcasterInterface->PauseBroadcast(broadcast_id);
}

static void DestroyBroadcastNative(JNIEnv* env, jobject object,
                                   jint broadcast_id) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) return;
  sLeAudioBroadcasterInterface->DestroyBroadcast(broadcast_id);
}

static void GetAllBroadcastStatesNative(JNIEnv* env, jobject object) {
  LOG(INFO) << __func__;
  std::shared_lock<std::shared_timed_mutex> lock(sBroadcasterInterfaceMutex);
  if (!sLeAudioBroadcasterInterface) return;
  sLeAudioBroadcasterInterface->GetAllBroadcastStates();
}

static JNINativeMethod sBroadcasterMethods[] = {
    {"classInitNative", "()V", (void*)BroadcasterClassInitNative},
    {"initNative", "()V", (void*)BroadcasterInitNative},
    {"stopNative", "()V", (void*)BroadcasterStopNative},
    {"cleanupNative", "()V", (void*)BroadcasterCleanupNative},
    {"createBroadcastNative", "([BI[B)V", (void*)CreateBroadcastNative},
    {"updateMetadataNative", "(I[B)V", (void*)UpdateMetadataNative},
    {"startBroadcastNative", "(I)V", (void*)StartBroadcastNative},
    {"stopBroadcastNative", "(I)V", (void*)StopBroadcastNative},
    {"pauseBroadcastNative", "(I)V", (void*)PauseBroadcastNative},
    {"destroyBroadcastNative", "(I)V", (void*)DestroyBroadcastNative},
    {"getAllBroadcastStatesNative", "()V", (void*)GetAllBroadcastStatesNative},
};

int register_com_android_bluetooth_le_audio(JNIEnv* env) {
  int register_success = jniRegisterNativeMethods(
      env, "com/android/bluetooth/le_audio/LeAudioNativeInterface", sMethods,
      NELEM(sMethods));
  return register_success &
         jniRegisterNativeMethods(
             env,
             "com/android/bluetooth/le_audio/LeAudioBroadcasterNativeInterface",
             sBroadcasterMethods, NELEM(sBroadcasterMethods));
}
}  // namespace android
