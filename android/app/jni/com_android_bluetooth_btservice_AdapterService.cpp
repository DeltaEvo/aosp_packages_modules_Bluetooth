/*
 * Copyright (C) 2016-2017 The Linux Foundation
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "BluetoothServiceJni"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <hardware/bluetooth.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <pthread.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>

#include <shared_mutex>

#include "com_android_bluetooth.h"
#include "hardware/bt_sock.h"
#include "os/logging/log_redaction.h"
#include "utils/Log.h"
#include "utils/misc.h"

using bluetooth::Uuid;
#ifndef DYNAMIC_LOAD_BLUETOOTH
extern bt_interface_t bluetoothInterface;
#endif

namespace android {
// Both

#define TRANSPORT_AUTO 0
#define TRANSPORT_BREDR 1
#define TRANSPORT_LE 2

#define BLE_ADDR_PUBLIC 0x00
#define BLE_ADDR_RANDOM 0x01

const jint INVALID_FD = -1;

static jmethodID method_oobDataReceivedCallback;
static jmethodID method_stateChangeCallback;
static jmethodID method_adapterPropertyChangedCallback;
static jmethodID method_devicePropertyChangedCallback;
static jmethodID method_deviceFoundCallback;
static jmethodID method_pinRequestCallback;
static jmethodID method_sspRequestCallback;
static jmethodID method_bondStateChangeCallback;
static jmethodID method_addressConsolidateCallback;
static jmethodID method_leAddressAssociateCallback;
static jmethodID method_aclStateChangeCallback;
static jmethodID method_discoveryStateChangeCallback;
static jmethodID method_linkQualityReportCallback;
static jmethodID method_switchBufferSizeCallback;
static jmethodID method_switchCodecCallback;
static jmethodID method_acquireWakeLock;
static jmethodID method_releaseWakeLock;
static jmethodID method_energyInfo;

static struct {
  jclass clazz;
  jmethodID constructor;
} android_bluetooth_UidTraffic;

static const bt_interface_t* sBluetoothInterface = NULL;
static const btsock_interface_t* sBluetoothSocketInterface = NULL;
static JavaVM* vm = NULL;
static JNIEnv* callbackEnv = NULL;
static pthread_t sCallbackThread;
static bool sHaveCallbackThread;

static jobject sJniAdapterServiceObj;
static jobject sJniCallbacksObj;
static std::shared_timed_mutex jniObjMutex;
static jfieldID sJniCallbacksField;

const bt_interface_t* getBluetoothInterface() { return sBluetoothInterface; }

JNIEnv* getCallbackEnv() { return callbackEnv; }

bool isCallbackThread() {
  return sHaveCallbackThread && pthread_equal(sCallbackThread, pthread_self());
}

static void adapter_state_change_callback(bt_state_t status) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;
  ALOGV("%s: Status is: %d", __func__, status);

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj, method_stateChangeCallback,
                               (jint)status);
}

static int get_properties(int num_properties, bt_property_t* properties,
                          jintArray* types, jobjectArray* props) {
  for (int i = 0; i < num_properties; i++) {
    ScopedLocalRef<jbyteArray> propVal(
        callbackEnv, callbackEnv->NewByteArray(properties[i].len));
    if (!propVal.get()) {
      ALOGE("Error while allocation of array in %s", __func__);
      return -1;
    }

    callbackEnv->SetByteArrayRegion(propVal.get(), 0, properties[i].len,
                                    (jbyte*)properties[i].val);
    callbackEnv->SetObjectArrayElement(*props, i, propVal.get());
    callbackEnv->SetIntArrayRegion(*types, i, 1, (jint*)&properties[i].type);
  }
  return 0;
}

static void adapter_properties_callback(bt_status_t status, int num_properties,
                                        bt_property_t* properties) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ALOGV("%s: Status is: %d, Properties: %d", __func__, status, num_properties);

  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Status %d is incorrect", __func__, status);
    return;
  }

  ScopedLocalRef<jbyteArray> val(
      sCallbackEnv.get(),
      (jbyteArray)sCallbackEnv->NewByteArray(num_properties));
  if (!val.get()) {
    ALOGE("%s: Error allocating byteArray", __func__);
    return;
  }

  ScopedLocalRef<jclass> mclass(sCallbackEnv.get(),
                                sCallbackEnv->GetObjectClass(val.get()));

  /* (BT) Initialize the jobjectArray and jintArray here itself and send the
   initialized array pointers alone to get_properties */

  ScopedLocalRef<jobjectArray> props(
      sCallbackEnv.get(),
      sCallbackEnv->NewObjectArray(num_properties, mclass.get(), NULL));
  if (!props.get()) {
    ALOGE("%s: Error allocating object Array for properties", __func__);
    return;
  }

  ScopedLocalRef<jintArray> types(
      sCallbackEnv.get(), (jintArray)sCallbackEnv->NewIntArray(num_properties));
  if (!types.get()) {
    ALOGE("%s: Error allocating int Array for values", __func__);
    return;
  }

  jintArray typesPtr = types.get();
  jobjectArray propsPtr = props.get();
  if (get_properties(num_properties, properties, &typesPtr, &propsPtr) < 0) {
    return;
  }

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj,
                               method_adapterPropertyChangedCallback,
                               types.get(), props.get());
}

static void remote_device_properties_callback(bt_status_t status,
                                              RawAddress* bd_addr,
                                              int num_properties,
                                              bt_property_t* properties) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ALOGV("%s: Status is: %d, Properties: %d", __func__, status, num_properties);

  if (status != BT_STATUS_SUCCESS) {
    ALOGE("%s: Status %d is incorrect", __func__, status);
    return;
  }

  ScopedLocalRef<jbyteArray> val(
      sCallbackEnv.get(),
      (jbyteArray)sCallbackEnv->NewByteArray(num_properties));
  if (!val.get()) {
    ALOGE("%s: Error allocating byteArray", __func__);
    return;
  }

  ScopedLocalRef<jclass> mclass(sCallbackEnv.get(),
                                sCallbackEnv->GetObjectClass(val.get()));

  /* Initialize the jobjectArray and jintArray here itself and send the
   initialized array pointers alone to get_properties */

  ScopedLocalRef<jobjectArray> props(
      sCallbackEnv.get(),
      sCallbackEnv->NewObjectArray(num_properties, mclass.get(), NULL));
  if (!props.get()) {
    ALOGE("%s: Error allocating object Array for properties", __func__);
    return;
  }

  ScopedLocalRef<jintArray> types(
      sCallbackEnv.get(), (jintArray)sCallbackEnv->NewIntArray(num_properties));
  if (!types.get()) {
    ALOGE("%s: Error allocating int Array for values", __func__);
    return;
  }

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Error while allocation byte array in %s", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  jintArray typesPtr = types.get();
  jobjectArray propsPtr = props.get();
  if (get_properties(num_properties, properties, &typesPtr, &propsPtr) < 0) {
    return;
  }

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj,
                               method_devicePropertyChangedCallback, addr.get(),
                               types.get(), props.get());
}

static void device_found_callback(int num_properties,
                                  bt_property_t* properties) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(sCallbackEnv.get(), NULL);
  int addr_index;
  for (int i = 0; i < num_properties; i++) {
    if (properties[i].type == BT_PROPERTY_BDADDR) {
      addr.reset(sCallbackEnv->NewByteArray(properties[i].len));
      if (!addr.get()) {
        ALOGE("Address is NULL (unable to allocate) in %s", __func__);
        return;
      }
      sCallbackEnv->SetByteArrayRegion(addr.get(), 0, properties[i].len,
                                       (jbyte*)properties[i].val);
      addr_index = i;
    }
  }
  if (!addr.get()) {
    ALOGE("Address is NULL in %s", __func__);
    return;
  }

  ALOGV("%s: Properties: %d, Address: %s", __func__, num_properties,
        (const char*)properties[addr_index].val);

  remote_device_properties_callback(BT_STATUS_SUCCESS,
                                    (RawAddress*)properties[addr_index].val,
                                    num_properties, properties);

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj, method_deviceFoundCallback,
                               addr.get());
}

static void bond_state_changed_callback(bt_status_t status, RawAddress* bd_addr,
                                        bt_bond_state_t state,
                                        int fail_reason) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  if (!bd_addr) {
    ALOGE("Address is null in %s", __func__);
    return;
  }

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Address allocation failed in %s", __func__);
    return;
  }
  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj, method_bondStateChangeCallback,
                               (jint)status, addr.get(), (jint)state,
                               (jint)fail_reason);
}

static void address_consolidate_callback(RawAddress* main_bd_addr,
                                         RawAddress* secondary_bd_addr) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);

  ScopedLocalRef<jbyteArray> main_addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!main_addr.get()) {
    ALOGE("Address allocation failed in %s", __func__);
    return;
  }
  sCallbackEnv->SetByteArrayRegion(main_addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)main_bd_addr);

  ScopedLocalRef<jbyteArray> secondary_addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!secondary_addr.get()) {
    ALOGE("Address allocation failed in %s", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(secondary_addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)secondary_bd_addr);

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj,
                               method_addressConsolidateCallback,
                               main_addr.get(), secondary_addr.get());
}

static void le_address_associate_callback(RawAddress* main_bd_addr,
                                          RawAddress* secondary_bd_addr) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);

  ScopedLocalRef<jbyteArray> main_addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!main_addr.get()) {
    ALOGE("Address allocation failed in %s", __func__);
    return;
  }
  sCallbackEnv->SetByteArrayRegion(main_addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)main_bd_addr);

  ScopedLocalRef<jbyteArray> secondary_addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!secondary_addr.get()) {
    ALOGE("Address allocation failed in %s", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(secondary_addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)secondary_bd_addr);

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj,
                               method_leAddressAssociateCallback,
                               main_addr.get(), secondary_addr.get());
}

static void acl_state_changed_callback(bt_status_t status, RawAddress* bd_addr,
                                       bt_acl_state_t state,
                                       int transport_link_type,
                                       bt_hci_error_code_t hci_reason,
                                       bt_conn_direction_t /* direction */,
                                       uint16_t acl_handle) {
  if (!bd_addr) {
    ALOGE("Address is null in %s", __func__);
    return;
  }

  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Address allocation failed in %s", __func__);
    return;
  }
  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj, method_aclStateChangeCallback,
                               (jint)status, addr.get(), (jint)state,
                               (jint)transport_link_type, (jint)hci_reason,
                               (jint)acl_handle);
}

static void discovery_state_changed_callback(bt_discovery_state_t state) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ALOGV("%s: DiscoveryState:%d ", __func__, state);

  sCallbackEnv->CallVoidMethod(
      sJniCallbacksObj, method_discoveryStateChangeCallback, (jint)state);
}

static void pin_request_callback(RawAddress* bd_addr, bt_bdname_t* bdname,
                                 uint32_t cod, bool min_16_digits) {
  if (!bd_addr) {
    ALOGE("Address is null in %s", __func__);
    return;
  }

  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Error while allocating in: %s", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  ScopedLocalRef<jbyteArray> devname(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(bt_bdname_t)));
  if (!devname.get()) {
    ALOGE("Error while allocating in: %s", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(devname.get(), 0, sizeof(bt_bdname_t),
                                   (jbyte*)bdname);

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj, method_pinRequestCallback,
                               addr.get(), devname.get(), cod, min_16_digits);
}

static void ssp_request_callback(RawAddress* bd_addr, bt_bdname_t* bdname,
                                 uint32_t cod, bt_ssp_variant_t pairing_variant,
                                 uint32_t pass_key) {
  if (!bd_addr) {
    ALOGE("Address is null in %s", __func__);
    return;
  }

  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Error while allocating in: %s", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  ScopedLocalRef<jbyteArray> devname(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(bt_bdname_t)));
  if (!devname.get()) {
    ALOGE("Error while allocating in: %s", __func__);
    return;
  }

  sCallbackEnv->SetByteArrayRegion(devname.get(), 0, sizeof(bt_bdname_t),
                                   (jbyte*)bdname);

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj, method_sspRequestCallback,
                               addr.get(), devname.get(), cod,
                               (jint)pairing_variant, pass_key);
}

static jobject createClassicOobDataObject(JNIEnv* env, bt_oob_data_t oob_data) {
  ALOGV("%s", __func__);
  jmethodID classicBuilderConstructor;
  jmethodID setRMethod;
  jmethodID setNameMethod;
  jmethodID buildMethod;

  const JNIJavaMethod javaMethods[] = {
      {"<init>", "([B[B[B)V", &classicBuilderConstructor},
      {"setRandomizerHash", "([B)Landroid/bluetooth/OobData$ClassicBuilder;",
       &setRMethod},
      {"setDeviceName", "([B)Landroid/bluetooth/OobData$ClassicBuilder;",
       &setNameMethod},
      {"build", "()Landroid/bluetooth/OobData;", &buildMethod},
  };
  GET_JAVA_METHODS(env, "android/bluetooth/OobData$ClassicBuilder",
                   javaMethods);

  jbyteArray confirmationHash = env->NewByteArray(OOB_C_SIZE);
  env->SetByteArrayRegion(confirmationHash, 0, OOB_C_SIZE,
                          reinterpret_cast<jbyte*>(oob_data.c));

  jbyteArray oobDataLength = env->NewByteArray(OOB_DATA_LEN_SIZE);
  env->SetByteArrayRegion(oobDataLength, 0, OOB_DATA_LEN_SIZE,
                          reinterpret_cast<jbyte*>(oob_data.oob_data_length));

  jbyteArray address = env->NewByteArray(OOB_ADDRESS_SIZE);
  env->SetByteArrayRegion(address, 0, OOB_ADDRESS_SIZE,
                          reinterpret_cast<jbyte*>(oob_data.address));

  jclass classicBuilderClass =
      env->FindClass("android/bluetooth/OobData$ClassicBuilder");

  jobject oobDataClassicBuilder =
      env->NewObject(classicBuilderClass, classicBuilderConstructor,
                     confirmationHash, oobDataLength, address);

  env->DeleteLocalRef(classicBuilderClass);

  jbyteArray randomizerHash = env->NewByteArray(OOB_R_SIZE);
  env->SetByteArrayRegion(randomizerHash, 0, OOB_R_SIZE,
                          reinterpret_cast<jbyte*>(oob_data.r));

  oobDataClassicBuilder =
      env->CallObjectMethod(oobDataClassicBuilder, setRMethod, randomizerHash);

  int name_char_count = 0;
  for (int i = 0; i < OOB_NAME_MAX_SIZE; i++) {
    if (oob_data.device_name[i] == 0) {
      name_char_count = i;
      break;
    }
  }

  jbyteArray deviceName = env->NewByteArray(name_char_count);
  env->SetByteArrayRegion(deviceName, 0, name_char_count,
                          reinterpret_cast<jbyte*>(oob_data.device_name));

  oobDataClassicBuilder =
      env->CallObjectMethod(oobDataClassicBuilder, setNameMethod, deviceName);

  return env->CallObjectMethod(oobDataClassicBuilder, buildMethod);
}

static jobject createLeOobDataObject(JNIEnv* env, bt_oob_data_t oob_data) {
  ALOGV("%s", __func__);

  jmethodID leBuilderConstructor;
  jmethodID setRMethod;
  jmethodID setNameMethod;
  jmethodID buildMethod;

  const JNIJavaMethod javaMethods[] = {
      {"<init>", "([B[BI)V", &leBuilderConstructor},
      {"setRandomizerHash", "([B)Landroid/bluetooth/OobData$LeBuilder;",
       &setRMethod},
      {"setDeviceName", "([B)Landroid/bluetooth/OobData$LeBuilder;",
       &setNameMethod},
      {"build", "()Landroid/bluetooth/OobData;", &buildMethod},
  };
  GET_JAVA_METHODS(env, "android/bluetooth/OobData$LeBuilder", javaMethods);

  jbyteArray confirmationHash = env->NewByteArray(OOB_C_SIZE);
  env->SetByteArrayRegion(confirmationHash, 0, OOB_C_SIZE,
                          reinterpret_cast<jbyte*>(oob_data.c));

  jbyteArray address = env->NewByteArray(OOB_ADDRESS_SIZE);
  env->SetByteArrayRegion(address, 0, OOB_ADDRESS_SIZE,
                          reinterpret_cast<jbyte*>(oob_data.address));

  jint le_role = (jint)oob_data.le_device_role;

  jclass leBuilderClass = env->FindClass("android/bluetooth/OobData$LeBuilder");

  jobject oobDataLeBuilder = env->NewObject(
      leBuilderClass, leBuilderConstructor, confirmationHash, address, le_role);

  env->DeleteLocalRef(leBuilderClass);

  jbyteArray randomizerHash = env->NewByteArray(OOB_R_SIZE);
  env->SetByteArrayRegion(randomizerHash, 0, OOB_R_SIZE,
                          reinterpret_cast<jbyte*>(oob_data.r));

  oobDataLeBuilder =
      env->CallObjectMethod(oobDataLeBuilder, setRMethod, randomizerHash);

  int name_char_count = 0;
  for (int i = 0; i < OOB_NAME_MAX_SIZE; i++) {
    if (oob_data.device_name[i] == 0) {
      name_char_count = i;
      break;
    }
  }

  jbyteArray deviceName = env->NewByteArray(name_char_count);
  env->SetByteArrayRegion(deviceName, 0, name_char_count,
                          reinterpret_cast<jbyte*>(oob_data.device_name));

  oobDataLeBuilder =
      env->CallObjectMethod(oobDataLeBuilder, setNameMethod, deviceName);

  return env->CallObjectMethod(oobDataLeBuilder, buildMethod);
}

static void generate_local_oob_data_callback(tBT_TRANSPORT transport,
                                             bt_oob_data_t oob_data) {
  ALOGV("%s", __func__);

  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  if (transport == TRANSPORT_BREDR) {
    sCallbackEnv->CallVoidMethod(
        sJniCallbacksObj, method_oobDataReceivedCallback, (jint)transport,
        ((oob_data.is_valid)
             ? createClassicOobDataObject(sCallbackEnv.get(), oob_data)
             : nullptr));
  } else if (transport == TRANSPORT_LE) {
    sCallbackEnv->CallVoidMethod(
        sJniCallbacksObj, method_oobDataReceivedCallback, (jint)transport,
        ((oob_data.is_valid)
             ? createLeOobDataObject(sCallbackEnv.get(), oob_data)
             : nullptr));
  } else {
    // TRANSPORT_AUTO is a concept, however, the host stack doesn't fully
    // implement it So passing it from the java layer is currently useless until
    // the implementation and concept of TRANSPORT_AUTO is fleshed out.
    ALOGE("TRANSPORT: %d not implemented", transport);
    sCallbackEnv->CallVoidMethod(sJniCallbacksObj,
                                 method_oobDataReceivedCallback,
                                 (jint)transport, nullptr);
  }
}

static void link_quality_report_callback(
    uint64_t timestamp, int report_id, int rssi, int snr,
    int retransmission_count, int packets_not_receive_count,
    int negative_acknowledgement_count) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ALOGV("%s: LinkQualityReportCallback: %d %d %d %d %d %d", __func__,
        report_id, rssi, snr, retransmission_count, packets_not_receive_count,
        negative_acknowledgement_count);

  sCallbackEnv->CallVoidMethod(
      sJniCallbacksObj, method_linkQualityReportCallback,
      (jlong)timestamp, (jint)report_id, (jint)rssi, (jint)snr,
      (jint)retransmission_count, (jint)packets_not_receive_count,
      (jint)negative_acknowledgement_count);
}

static void switch_buffer_size_callback(bool is_low_latency_buffer_size) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ALOGV("%s: SwitchBufferSizeCallback: %s", __func__,
        is_low_latency_buffer_size ? "true" : "false");

  sCallbackEnv->CallVoidMethod(
      sJniCallbacksObj, method_switchBufferSizeCallback,
      (jboolean)is_low_latency_buffer_size);
}

static void switch_codec_callback(bool is_low_latency_buffer_size) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniCallbacksObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ALOGV("%s: SwitchCodecCallback: %s", __func__,
        is_low_latency_buffer_size ? "true" : "false");

  sCallbackEnv->CallVoidMethod(sJniCallbacksObj, method_switchCodecCallback,
                               (jboolean)is_low_latency_buffer_size);
}

static void le_rand_callback(uint64_t /* random */) {
  // Android doesn't support the LeRand API.
}

static void callback_thread_event(bt_cb_thread_evt event) {
  if (event == ASSOCIATE_JVM) {
    JavaVMAttachArgs args;
    char name[] = "BT Service Callback Thread";
    args.version = JNI_VERSION_1_6;
    args.name = name;
    args.group = NULL;
    vm->AttachCurrentThread(&callbackEnv, &args);
    sHaveCallbackThread = true;
    sCallbackThread = pthread_self();
    ALOGV("Callback thread attached: %p", callbackEnv);
  } else if (event == DISASSOCIATE_JVM) {
    if (!isCallbackThread()) {
      ALOGE("Callback: '%s' is not called on the correct thread", __func__);
      return;
    }
    vm->DetachCurrentThread();
    sHaveCallbackThread = false;
  }
}

static void dut_mode_recv_callback(uint16_t /* opcode */, uint8_t* /* buf */,
                                   uint8_t /* len */) {}

static void le_test_mode_recv_callback(bt_status_t status,
                                       uint16_t packet_count) {
  ALOGV("%s: status:%d packet_count:%d ", __func__, status, packet_count);
}

static void energy_info_recv_callback(bt_activity_energy_info* p_energy_info,
                                      bt_uid_traffic_t* uid_data) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniAdapterServiceObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return;
  }

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  jsize len = 0;
  for (bt_uid_traffic_t* data = uid_data; data->app_uid != -1; data++) {
    len++;
  }

  ScopedLocalRef<jobjectArray> array(
      sCallbackEnv.get(), sCallbackEnv->NewObjectArray(
                              len, android_bluetooth_UidTraffic.clazz, NULL));
  jsize i = 0;
  for (bt_uid_traffic_t* data = uid_data; data->app_uid != -1; data++) {
    ScopedLocalRef<jobject> uidObj(
        sCallbackEnv.get(),
        sCallbackEnv->NewObject(android_bluetooth_UidTraffic.clazz,
                                android_bluetooth_UidTraffic.constructor,
                                (jint)data->app_uid, (jlong)data->rx_bytes,
                                (jlong)data->tx_bytes));
    sCallbackEnv->SetObjectArrayElement(array.get(), i++, uidObj.get());
  }

  sCallbackEnv->CallVoidMethod(
      sJniCallbacksObj, method_energyInfo, p_energy_info->status,
      p_energy_info->ctrl_state, p_energy_info->tx_time, p_energy_info->rx_time,
      p_energy_info->idle_time, p_energy_info->energy_used, array.get());
}

static bt_callbacks_t sBluetoothCallbacks = {sizeof(sBluetoothCallbacks),
                                             adapter_state_change_callback,
                                             adapter_properties_callback,
                                             remote_device_properties_callback,
                                             device_found_callback,
                                             discovery_state_changed_callback,
                                             pin_request_callback,
                                             ssp_request_callback,
                                             bond_state_changed_callback,
                                             address_consolidate_callback,
                                             le_address_associate_callback,
                                             acl_state_changed_callback,
                                             callback_thread_event,
                                             dut_mode_recv_callback,
                                             le_test_mode_recv_callback,
                                             energy_info_recv_callback,
                                             link_quality_report_callback,
                                             generate_local_oob_data_callback,
                                             switch_buffer_size_callback,
                                             switch_codec_callback,
                                             le_rand_callback};

class JNIThreadAttacher {
 public:
  JNIThreadAttacher(JavaVM* vm) : vm_(vm), env_(nullptr) {
    status_ = vm_->GetEnv((void**)&env_, JNI_VERSION_1_6);

    if (status_ != JNI_OK && status_ != JNI_EDETACHED) {
      ALOGE(
          "JNIThreadAttacher: unable to get environment for JNI CALL, "
          "status: %d",
          status_);
      env_ = nullptr;
      return;
    }

    if (status_ == JNI_EDETACHED) {
      char name[17] = {0};
      if (prctl(PR_GET_NAME, (unsigned long)name) != 0) {
        ALOGE(
            "JNIThreadAttacher: unable to grab previous thread name, error: %s",
            strerror(errno));
        env_ = nullptr;
        return;
      }

      JavaVMAttachArgs args = {
          .version = JNI_VERSION_1_6, .name = name, .group = nullptr};
      if (vm_->AttachCurrentThread(&env_, &args) != 0) {
        ALOGE("JNIThreadAttacher: unable to attach thread to VM");
        env_ = nullptr;
        return;
      }
    }
  }

  ~JNIThreadAttacher() {
    if (status_ == JNI_EDETACHED) vm_->DetachCurrentThread();
  }

  JNIEnv* getEnv() { return env_; }

 private:
  JavaVM* vm_;
  JNIEnv* env_;
  jint status_;
};

static int acquire_wake_lock_callout(const char* lock_name) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniAdapterServiceObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return BT_STATUS_NOT_READY;
  }

  JNIThreadAttacher attacher(vm);
  JNIEnv* env = attacher.getEnv();

  if (env == nullptr) {
    ALOGE("%s: Unable to get JNI Env", __func__);
    return BT_STATUS_JNI_THREAD_ATTACH_ERROR;
  }

  jint ret = BT_STATUS_SUCCESS;
  {
    ScopedLocalRef<jstring> lock_name_jni(env, env->NewStringUTF(lock_name));
    if (lock_name_jni.get()) {
      bool acquired = env->CallBooleanMethod(
          sJniCallbacksObj, method_acquireWakeLock, lock_name_jni.get());
      if (!acquired) ret = BT_STATUS_WAKELOCK_ERROR;
    } else {
      ALOGE("%s unable to allocate string: %s", __func__, lock_name);
      ret = BT_STATUS_NOMEM;
    }
  }

  return ret;
}

static int release_wake_lock_callout(const char* lock_name) {
  std::shared_lock<std::shared_timed_mutex> lock(jniObjMutex);
  if (!sJniAdapterServiceObj) {
    ALOGE("%s, JNI obj is null. Failed to call JNI callback", __func__);
    return BT_STATUS_NOT_READY;
  }

  JNIThreadAttacher attacher(vm);
  JNIEnv* env = attacher.getEnv();

  if (env == nullptr) {
    ALOGE("%s: Unable to get JNI Env", __func__);
    return BT_STATUS_JNI_THREAD_ATTACH_ERROR;
  }

  jint ret = BT_STATUS_SUCCESS;
  {
    ScopedLocalRef<jstring> lock_name_jni(env, env->NewStringUTF(lock_name));
    if (lock_name_jni.get()) {
      bool released = env->CallBooleanMethod(
          sJniCallbacksObj, method_releaseWakeLock, lock_name_jni.get());
      if (!released) ret = BT_STATUS_WAKELOCK_ERROR;
    } else {
      ALOGE("%s unable to allocate string: %s", __func__, lock_name);
      ret = BT_STATUS_NOMEM;
    }
  }

  return ret;
}

static bt_os_callouts_t sBluetoothOsCallouts = {
    sizeof(sBluetoothOsCallouts),
    acquire_wake_lock_callout,
    release_wake_lock_callout,
};

int hal_util_load_bt_library(const bt_interface_t** interface) {
#ifndef DYNAMIC_LOAD_BLUETOOTH
  *interface = &bluetoothInterface;
  return 0;
#else
  const char* sym = BLUETOOTH_INTERFACE_STRING;
  bt_interface_t* itf = nullptr;

  // The library name is not set by default, so the preset library name is used.
  void* handle = dlopen("libbluetooth.so", RTLD_NOW);
  if (!handle) {
    const char* err_str = dlerror();
    ALOGE("%s: failed to load Bluetooth library, error=%s", __func__,
          err_str ? err_str : "error unknown");
    goto error;
  }

  // Get the address of the bt_interface_t.
  itf = (bt_interface_t*)dlsym(handle, sym);
  if (!itf) {
    ALOGE("%s: failed to load symbol from Bluetooth library %s", __func__, sym);
    goto error;
  }

  // Success.
  ALOGI("%s: loaded Bluetooth library successfully", __func__);
  *interface = itf;
  return 0;

error:
  *interface = NULL;
  if (handle) dlclose(handle);

  return -EINVAL;
#endif
}

static bool initNative(JNIEnv* env, jobject obj, jboolean isGuest,
                       jboolean isCommonCriteriaMode, int configCompareResult,
                       jobjectArray initFlags, jboolean isAtvDevice,
                       jstring userDataDirectory) {
  std::unique_lock<std::shared_timed_mutex> lock(jniObjMutex);

  ALOGV("%s", __func__);

  android_bluetooth_UidTraffic.clazz =
      (jclass)env->NewGlobalRef(env->FindClass("android/bluetooth/UidTraffic"));

  sJniAdapterServiceObj = env->NewGlobalRef(obj);
  sJniCallbacksObj =
      env->NewGlobalRef(env->GetObjectField(obj, sJniCallbacksField));

  if (!sBluetoothInterface) {
    return JNI_FALSE;
  }

  int flagCount = env->GetArrayLength(initFlags);
  jstring* flagObjs = new jstring[flagCount];
  const char** flags = nullptr;
  if (flagCount > 0) {
    flags = new const char*[flagCount + 1];
    flags[flagCount] = nullptr;
  }

  for (int i = 0; i < flagCount; i++) {
    flagObjs[i] = (jstring)env->GetObjectArrayElement(initFlags, i);
    flags[i] = env->GetStringUTFChars(flagObjs[i], NULL);
  }

  const char* user_data_directory =
      env->GetStringUTFChars(userDataDirectory, NULL);

  int ret = sBluetoothInterface->init(
      &sBluetoothCallbacks, isGuest == JNI_TRUE ? 1 : 0,
      isCommonCriteriaMode == JNI_TRUE ? 1 : 0, configCompareResult, flags,
      isAtvDevice == JNI_TRUE ? 1 : 0, user_data_directory);

  env->ReleaseStringUTFChars(userDataDirectory, user_data_directory);

  for (int i = 0; i < flagCount; i++) {
    env->ReleaseStringUTFChars(flagObjs[i], flags[i]);
  }

  delete[] flags;
  delete[] flagObjs;

  if (ret != BT_STATUS_SUCCESS) {
    ALOGE("Error while setting the callbacks: %d\n", ret);
    sBluetoothInterface = NULL;
    return JNI_FALSE;
  }
  ret = sBluetoothInterface->set_os_callouts(&sBluetoothOsCallouts);
  if (ret != BT_STATUS_SUCCESS) {
    ALOGE("Error while setting Bluetooth callouts: %d\n", ret);
    sBluetoothInterface->cleanup();
    sBluetoothInterface = NULL;
    return JNI_FALSE;
  }

  sBluetoothSocketInterface =
      (btsock_interface_t*)sBluetoothInterface->get_profile_interface(
          BT_PROFILE_SOCKETS_ID);
  if (sBluetoothSocketInterface == NULL) {
    ALOGE("Error getting socket interface");
  }

  return JNI_TRUE;
}

static bool cleanupNative(JNIEnv* env, jobject /* obj */) {
  std::unique_lock<std::shared_timed_mutex> lock(jniObjMutex);

  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  sBluetoothInterface->cleanup();
  ALOGI("%s: return from cleanup", __func__);

  if (sJniCallbacksObj) {
    env->DeleteGlobalRef(sJniCallbacksObj);
    sJniCallbacksObj = NULL;
  }

  if (sJniAdapterServiceObj) {
    env->DeleteGlobalRef(sJniAdapterServiceObj);
    sJniAdapterServiceObj = NULL;
  }

  if (android_bluetooth_UidTraffic.clazz) {
    env->DeleteGlobalRef(android_bluetooth_UidTraffic.clazz);
    android_bluetooth_UidTraffic.clazz = NULL;
  }
  return JNI_TRUE;
}

static jboolean enableNative(JNIEnv* /* env */, jobject /* obj */) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;
  int ret = sBluetoothInterface->enable();
  return (ret == BT_STATUS_SUCCESS || ret == BT_STATUS_DONE) ? JNI_TRUE
                                                             : JNI_FALSE;
}

static jboolean disableNative(JNIEnv* /* env */, jobject /* obj */) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  int ret = sBluetoothInterface->disable();
  /* Retrun JNI_FALSE only when BTIF explicitly reports
     BT_STATUS_FAIL. It is fine for the BT_STATUS_NOT_READY
     case which indicates that stack had not been enabled.
  */
  return (ret == BT_STATUS_FAIL) ? JNI_FALSE : JNI_TRUE;
}

static jboolean startDiscoveryNative(JNIEnv* /* env */, jobject /* obj */) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  int ret = sBluetoothInterface->start_discovery();
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean cancelDiscoveryNative(JNIEnv* /* env */, jobject /* obj */) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  int ret = sBluetoothInterface->cancel_discovery();
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean createBondNative(JNIEnv* env, jobject /* obj */,
                                 jbyteArray address, jint addrType,
                                 jint transport) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  uint8_t addr_type = (uint8_t)addrType;
  int ret = BT_STATUS_SUCCESS;
  if (addr_type == BLE_ADDR_RANDOM) {
    ret = sBluetoothInterface->create_bond_le((RawAddress*)addr, addr_type);
  } else {
    ret = sBluetoothInterface->create_bond((RawAddress*)addr, transport);
  }

  if (ret != BT_STATUS_SUCCESS) {
    ALOGW("%s: Failed to initiate bonding. Status = %d", __func__, ret);
  }

  env->ReleaseByteArrayElements(address, addr, 0);
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray callByteArrayGetter(JNIEnv* env, jobject object,
                                      const char* className,
                                      const char* methodName) {
  jclass myClass = env->FindClass(className);
  jmethodID myMethod = env->GetMethodID(myClass, methodName, "()[B");
  env->DeleteLocalRef(myClass);
  return (jbyteArray)env->CallObjectMethod(object, myMethod);
}

static jint callIntGetter(JNIEnv* env, jobject object, const char* className,
                          const char* methodName) {
  jclass myClass = env->FindClass(className);
  jmethodID myMethod = env->GetMethodID(myClass, methodName, "()I");
  env->DeleteLocalRef(myClass);
  return env->CallIntMethod(object, myMethod);
}

static jboolean set_data(JNIEnv* env, bt_oob_data_t& oob_data, jobject oobData,
                         jint transport) {
  // Need both arguments to be non NULL
  if (oobData == NULL) {
    ALOGE("%s: oobData is null! Nothing to do.", __func__);
    return JNI_FALSE;
  }

  memset(&oob_data, 0, sizeof(oob_data));

  jbyteArray address = callByteArrayGetter(
      env, oobData, "android/bluetooth/OobData", "getDeviceAddressWithType");

  // Check the data
  int len = env->GetArrayLength(address);
  if (len != OOB_ADDRESS_SIZE) {
    ALOGE("%s: addressBytes must be 7 bytes in length (address plus type) 6+1!",
          __func__);
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  // Convert the address from byte[]
  jbyte* addressBytes = env->GetByteArrayElements(address, NULL);
  if (addressBytes == NULL) {
    ALOGE("%s: addressBytes cannot be null!", __func__);
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }
  memcpy(oob_data.address, addressBytes, len);

  // Get the device name byte[] java object
  jbyteArray deviceName = callByteArrayGetter(
      env, oobData, "android/bluetooth/OobData", "getDeviceName");

  // Optional
  // Convert it to a jbyte* and copy it to the struct
  jbyte* deviceNameBytes = NULL;
  if (deviceName != NULL) {
    deviceNameBytes = env->GetByteArrayElements(deviceName, NULL);
    int len = env->GetArrayLength(deviceName);
    if (len > OOB_NAME_MAX_SIZE) {
      ALOGI(
          "%s: wrong length of deviceName, should be empty or less than or "
          "equal to %d bytes.",
          __func__, OOB_NAME_MAX_SIZE);
      jniThrowIOException(env, EINVAL);
      env->ReleaseByteArrayElements(deviceName, deviceNameBytes, 0);
      return JNI_FALSE;
    }
    memcpy(oob_data.device_name, deviceNameBytes, len);
    env->ReleaseByteArrayElements(deviceName, deviceNameBytes, 0);
  }
  // Used by both classic and LE
  jbyteArray confirmation = callByteArrayGetter(
      env, oobData, "android/bluetooth/OobData", "getConfirmationHash");
  if (confirmation == NULL) {
    ALOGE("%s: confirmation cannot be null!", __func__);
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  // Confirmation is mandatory
  jbyte* confirmationBytes = NULL;
  confirmationBytes = env->GetByteArrayElements(confirmation, NULL);
  len = env->GetArrayLength(confirmation);
  if (confirmationBytes == NULL || len != OOB_C_SIZE) {
    ALOGI(
        "%s: wrong length of Confirmation, should be empty or %d "
        "bytes.",
        __func__, OOB_C_SIZE);
    jniThrowIOException(env, EINVAL);
    env->ReleaseByteArrayElements(confirmation, confirmationBytes, 0);
    return JNI_FALSE;
  }
  memcpy(oob_data.c, confirmationBytes, len);
  env->ReleaseByteArrayElements(confirmation, confirmationBytes, 0);

  // Random is supposedly optional according to the specification
  jbyteArray randomizer = callByteArrayGetter(
      env, oobData, "android/bluetooth/OobData", "getRandomizerHash");
  jbyte* randomizerBytes = NULL;
  if (randomizer != NULL) {
    randomizerBytes = env->GetByteArrayElements(randomizer, NULL);
    int len = env->GetArrayLength(randomizer);
    if (randomizerBytes == NULL || len != OOB_R_SIZE) {
      ALOGI("%s: wrong length of Random, should be empty or %d bytes.",
            __func__, OOB_R_SIZE);
      jniThrowIOException(env, EINVAL);
      env->ReleaseByteArrayElements(randomizer, randomizerBytes, 0);
      return JNI_FALSE;
    }
    memcpy(oob_data.r, randomizerBytes, len);
    env->ReleaseByteArrayElements(randomizer, randomizerBytes, 0);
  }

  // Transport specific data fetching/setting
  if (transport == TRANSPORT_BREDR) {
    // Classic
    // Not optional
    jbyteArray oobDataLength = callByteArrayGetter(
        env, oobData, "android/bluetooth/OobData", "getClassicLength");
    jbyte* oobDataLengthBytes = NULL;
    if (oobDataLength == NULL ||
        env->GetArrayLength(oobDataLength) != OOB_DATA_LEN_SIZE) {
      ALOGI("%s: wrong length of oobDataLength, should be empty or %d bytes.",
            __func__, OOB_DATA_LEN_SIZE);
      jniThrowIOException(env, EINVAL);
      env->ReleaseByteArrayElements(oobDataLength, oobDataLengthBytes, 0);
      return JNI_FALSE;
    }

    oobDataLengthBytes = env->GetByteArrayElements(oobDataLength, NULL);
    memcpy(oob_data.oob_data_length, oobDataLengthBytes, OOB_DATA_LEN_SIZE);
    env->ReleaseByteArrayElements(oobDataLength, oobDataLengthBytes, 0);

    // Optional
    jbyteArray classOfDevice = callByteArrayGetter(
        env, oobData, "android/bluetooth/OobData", "getClassOfDevice");
    jbyte* classOfDeviceBytes = NULL;
    if (classOfDevice != NULL) {
      classOfDeviceBytes = env->GetByteArrayElements(classOfDevice, NULL);
      int len = env->GetArrayLength(classOfDevice);
      if (len != OOB_COD_SIZE) {
        ALOGI("%s: wrong length of classOfDevice, should be empty or %d bytes.",
              __func__, OOB_COD_SIZE);
        jniThrowIOException(env, EINVAL);
        env->ReleaseByteArrayElements(classOfDevice, classOfDeviceBytes, 0);
        return JNI_FALSE;
      }
      memcpy(oob_data.class_of_device, classOfDeviceBytes, len);
      env->ReleaseByteArrayElements(classOfDevice, classOfDeviceBytes, 0);
    }
  } else if (transport == TRANSPORT_LE) {
    // LE
    jbyteArray temporaryKey = callByteArrayGetter(
        env, oobData, "android/bluetooth/OobData", "getLeTemporaryKey");
    jbyte* temporaryKeyBytes = NULL;
    if (temporaryKey != NULL) {
      temporaryKeyBytes = env->GetByteArrayElements(temporaryKey, NULL);
      int len = env->GetArrayLength(temporaryKey);
      if (len != OOB_TK_SIZE) {
        ALOGI("%s: wrong length of temporaryKey, should be empty or %d bytes.",
              __func__, OOB_TK_SIZE);
        jniThrowIOException(env, EINVAL);
        env->ReleaseByteArrayElements(temporaryKey, temporaryKeyBytes, 0);
        return JNI_FALSE;
      }
      memcpy(oob_data.sm_tk, temporaryKeyBytes, len);
      env->ReleaseByteArrayElements(temporaryKey, temporaryKeyBytes, 0);
    }

    jbyteArray leAppearance = callByteArrayGetter(
        env, oobData, "android/bluetooth/OobData", "getLeAppearance");
    jbyte* leAppearanceBytes = NULL;
    if (leAppearance != NULL) {
      leAppearanceBytes = env->GetByteArrayElements(leAppearance, NULL);
      int len = env->GetArrayLength(leAppearance);
      if (len != OOB_LE_APPEARANCE_SIZE) {
        ALOGI("%s: wrong length of leAppearance, should be empty or %d bytes.",
              __func__, OOB_LE_APPEARANCE_SIZE);
        jniThrowIOException(env, EINVAL);
        env->ReleaseByteArrayElements(leAppearance, leAppearanceBytes, 0);
        return JNI_FALSE;
      }
      memcpy(oob_data.le_appearance, leAppearanceBytes, len);
      env->ReleaseByteArrayElements(leAppearance, leAppearanceBytes, 0);
    }

    jint leRole = callIntGetter(env, oobData, "android/bluetooth/OobData",
                                "getLeDeviceRole");
    oob_data.le_device_role = leRole;

    jint leFlag =
        callIntGetter(env, oobData, "android/bluetooth/OobData", "getLeFlags");
    oob_data.le_flags = leFlag;
  }
  return JNI_TRUE;
}

static void generateLocalOobDataNative(JNIEnv* /* env */, jobject /* obj */,
                                       jint transport) {
  // No BT interface? Can't do anything.
  if (!sBluetoothInterface) return;

  if (sBluetoothInterface->generate_local_oob_data(transport) !=
      BT_STATUS_SUCCESS) {
    ALOGE("%s: Call to generate_local_oob_data failed!", __func__);
    bt_oob_data_t oob_data;
    oob_data.is_valid = false;
    generate_local_oob_data_callback(transport, oob_data);
  }
}

static jboolean createBondOutOfBandNative(JNIEnv* env, jobject /* obj */,
                                          jbyteArray address, jint transport,
                                          jobject p192Data, jobject p256Data) {
  // No BT interface? Can't do anything.
  if (!sBluetoothInterface) return JNI_FALSE;

  // No data? Can't do anything
  if (p192Data == NULL && p256Data == NULL) {
    ALOGE("%s: All OOB Data are null! Nothing to do.", __func__);
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  // This address is already reversed which is why its being passed...
  // In the future we want to remove this and just reverse the address
  // for the oobdata in the host stack.
  if (address == NULL) {
    ALOGE("%s: Address cannot be null! Nothing to do.", __func__);
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  // Check the data
  int len = env->GetArrayLength(address);
  if (len != 6) {
    ALOGE("%s: addressBytes must be 6 bytes in length (address plus type) 6+1!",
          __func__);
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  // Convert P192 data from Java POJO to C Struct
  bt_oob_data_t p192_data;
  if (p192Data != NULL) {
    if (set_data(env, p192_data, p192Data, transport) == JNI_FALSE) {
      jniThrowIOException(env, EINVAL);
      return JNI_FALSE;
    }
  }

  // Convert P256 data from Java POJO to C Struct
  bt_oob_data_t p256_data;
  if (p256Data != NULL) {
    if (set_data(env, p256_data, p256Data, transport) == JNI_FALSE) {
      jniThrowIOException(env, EINVAL);
      return JNI_FALSE;
    }
  }

  return ((sBluetoothInterface->create_bond_out_of_band(
              (RawAddress*)addr, transport, &p192_data, &p256_data)) ==
          BT_STATUS_SUCCESS)
             ? JNI_TRUE
             : JNI_FALSE;
}

static jboolean removeBondNative(JNIEnv* env, jobject /* obj */,
                                 jbyteArray address) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  int ret = sBluetoothInterface->remove_bond((RawAddress*)addr);
  env->ReleaseByteArrayElements(address, addr, 0);

  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean cancelBondNative(JNIEnv* env, jobject /* obj */,
                                 jbyteArray address) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  int ret = sBluetoothInterface->cancel_bond((RawAddress*)addr);
  env->ReleaseByteArrayElements(address, addr, 0);
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static int getConnectionStateNative(JNIEnv* env, jobject /* obj */,
                                    jbyteArray address) {
  ALOGV("%s", __func__);
  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  int ret = sBluetoothInterface->get_connection_state((RawAddress*)addr);
  env->ReleaseByteArrayElements(address, addr, 0);

  return ret;
}

static jboolean pinReplyNative(JNIEnv* env, jobject /* obj */,
                               jbyteArray address, jboolean accept, jint len,
                               jbyteArray pinArray) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  jbyte* pinPtr = NULL;
  if (accept) {
    pinPtr = env->GetByteArrayElements(pinArray, NULL);
    if (pinPtr == NULL) {
      jniThrowIOException(env, EINVAL);
      env->ReleaseByteArrayElements(address, addr, 0);
      return JNI_FALSE;
    }
  }

  int ret = sBluetoothInterface->pin_reply((RawAddress*)addr, accept, len,
                                           (bt_pin_code_t*)pinPtr);
  env->ReleaseByteArrayElements(address, addr, 0);
  env->ReleaseByteArrayElements(pinArray, pinPtr, 0);

  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean sspReplyNative(JNIEnv* env, jobject /* obj */,
                               jbyteArray address, jint type, jboolean accept,
                               jint passkey) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  int ret = sBluetoothInterface->ssp_reply(
      (RawAddress*)addr, (bt_ssp_variant_t)type, accept, passkey);
  env->ReleaseByteArrayElements(address, addr, 0);

  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean setAdapterPropertyNative(JNIEnv* env, jobject /* obj */,
                                         jint type, jbyteArray value) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* val = env->GetByteArrayElements(value, NULL);
  bt_property_t prop;
  prop.type = (bt_property_type_t)type;
  prop.len = env->GetArrayLength(value);
  prop.val = val;

  int ret = sBluetoothInterface->set_adapter_property(&prop);
  env->ReleaseByteArrayElements(value, val, 0);

  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean getAdapterPropertiesNative(JNIEnv* /* env */,
                                           jobject /* obj */) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  int ret = sBluetoothInterface->get_adapter_properties();
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean getAdapterPropertyNative(JNIEnv* /* env */, jobject /* obj */,
                                         jint type) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  int ret = sBluetoothInterface->get_adapter_property((bt_property_type_t)type);
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean getDevicePropertyNative(JNIEnv* env, jobject /* obj */,
                                        jbyteArray address, jint type) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  int ret = sBluetoothInterface->get_remote_device_property(
      (RawAddress*)addr, (bt_property_type_t)type);
  env->ReleaseByteArrayElements(address, addr, 0);
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean setDevicePropertyNative(JNIEnv* env, jobject /* obj */,
                                        jbyteArray address, jint type,
                                        jbyteArray value) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* val = env->GetByteArrayElements(value, NULL);
  if (val == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    env->ReleaseByteArrayElements(value, val, 0);
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  bt_property_t prop;
  prop.type = (bt_property_type_t)type;
  prop.len = env->GetArrayLength(value);
  prop.val = val;

  int ret =
      sBluetoothInterface->set_remote_device_property((RawAddress*)addr, &prop);
  env->ReleaseByteArrayElements(value, val, 0);
  env->ReleaseByteArrayElements(address, addr, 0);

  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean getRemoteServicesNative(JNIEnv* env, jobject /* obj */,
                                        jbyteArray address, jint transport) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  jbyte* addr = addr = env->GetByteArrayElements(address, NULL);
  if (addr == NULL) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  int ret =
      sBluetoothInterface->get_remote_services((RawAddress*)addr, transport);
  env->ReleaseByteArrayElements(address, addr, 0);
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static int readEnergyInfoNative() {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;
  int ret = sBluetoothInterface->read_energy_info();
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static void dumpNative(JNIEnv* env, jobject /* obj */, jobject fdObj,
                       jobjectArray argArray) {
  ALOGV("%s", __func__);
  if (!sBluetoothInterface) return;

  int fd = jniGetFDFromFileDescriptor(env, fdObj);
  if (fd < 0) return;

  int numArgs = env->GetArrayLength(argArray);

  jstring* argObjs = new jstring[numArgs];
  const char** args = nullptr;
  if (numArgs > 0) {
    args = new const char*[numArgs + 1];
    args[numArgs] = nullptr;
  }

  for (int i = 0; i < numArgs; i++) {
    argObjs[i] = (jstring)env->GetObjectArrayElement(argArray, i);
    args[i] = env->GetStringUTFChars(argObjs[i], NULL);
  }

  sBluetoothInterface->dump(fd, args);

  for (int i = 0; i < numArgs; i++) {
    env->ReleaseStringUTFChars(argObjs[i], args[i]);
  }

  delete[] args;
  delete[] argObjs;
}

static jbyteArray dumpMetricsNative(JNIEnv* env, jobject /* obj */) {
  ALOGI("%s", __func__);
  if (!sBluetoothInterface) return env->NewByteArray(0);

  std::string output;
  sBluetoothInterface->dumpMetrics(&output);
  jsize output_size = output.size() * sizeof(char);
  jbyteArray output_bytes = env->NewByteArray(output_size);
  env->SetByteArrayRegion(output_bytes, 0, output_size,
                          (const jbyte*)output.data());
  return output_bytes;
}

static jboolean factoryResetNative(JNIEnv* /* env */, jobject /* obj */) {
  ALOGV("%s", __func__);
  if (!sBluetoothInterface) return JNI_FALSE;
  int ret = sBluetoothInterface->config_clear();
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray obfuscateAddressNative(JNIEnv* env, jobject /* obj */,
                                         jbyteArray address) {
  ALOGV("%s", __func__);
  if (!sBluetoothInterface) return env->NewByteArray(0);
  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (addr == nullptr) {
    jniThrowIOException(env, EINVAL);
    return env->NewByteArray(0);
  }
  RawAddress addr_obj = {};
  addr_obj.FromOctets((uint8_t*)addr);
  std::string output = sBluetoothInterface->obfuscate_address(addr_obj);
  jsize output_size = output.size() * sizeof(char);
  jbyteArray output_bytes = env->NewByteArray(output_size);
  env->SetByteArrayRegion(output_bytes, 0, output_size,
                          (const jbyte*)output.data());
  return output_bytes;
}

static jboolean setBufferLengthMillisNative(JNIEnv* /* env */,
                                            jobject /* obj */, jint codec,
                                            jint size) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  int ret = sBluetoothInterface->set_dynamic_audio_buffer_size(codec, size);
  return (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jint connectSocketNative(JNIEnv* env, jobject /* obj */,
                                jbyteArray address, jint type, jbyteArray uuid,
                                jint port, jint flag, jint callingUid) {
  int socket_fd = INVALID_FD;
  jbyte* addr = nullptr;
  jbyte* uuidBytes = nullptr;
  Uuid btUuid;

  if (!sBluetoothSocketInterface) {
    goto done;
  }
  addr = env->GetByteArrayElements(address, nullptr);
  uuidBytes = env->GetByteArrayElements(uuid, nullptr);
  if (addr == nullptr || uuidBytes == nullptr) {
    jniThrowIOException(env, EINVAL);
    goto done;
  }

  btUuid = Uuid::From128BitBE((uint8_t*)uuidBytes);
  if (sBluetoothSocketInterface->connect((RawAddress*)addr, (btsock_type_t)type,
                                         &btUuid, port, &socket_fd, flag,
                                         callingUid) != BT_STATUS_SUCCESS) {
    socket_fd = INVALID_FD;
  }

done:
  if (addr) env->ReleaseByteArrayElements(address, addr, 0);
  if (uuidBytes) env->ReleaseByteArrayElements(uuid, uuidBytes, 0);
  return socket_fd;
}

static jint createSocketChannelNative(JNIEnv* env, jobject /* obj */, jint type,
                                      jstring serviceName, jbyteArray uuid,
                                      jint port, jint flag, jint callingUid) {
  int socket_fd = INVALID_FD;
  jbyte* uuidBytes = nullptr;
  Uuid btUuid;
  const char* nativeServiceName = nullptr;

  if (!sBluetoothSocketInterface) {
    goto done;
  }
  uuidBytes = env->GetByteArrayElements(uuid, nullptr);
  if (serviceName != nullptr) {
    nativeServiceName = env->GetStringUTFChars(serviceName, nullptr);
  }
  if (uuidBytes == nullptr) {
    jniThrowIOException(env, EINVAL);
    goto done;
  }
  btUuid = Uuid::From128BitBE((uint8_t*)uuidBytes);

  if (sBluetoothSocketInterface->listen((btsock_type_t)type, nativeServiceName,
                                        &btUuid, port, &socket_fd, flag,
                                        callingUid) != BT_STATUS_SUCCESS) {
    socket_fd = INVALID_FD;
  }

done:
  if (uuidBytes) env->ReleaseByteArrayElements(uuid, uuidBytes, 0);
  if (nativeServiceName)
    env->ReleaseStringUTFChars(serviceName, nativeServiceName);
  return socket_fd;
}

static void requestMaximumTxDataLengthNative(JNIEnv* env, jobject /* obj */,
                                             jbyteArray address) {
  if (!sBluetoothSocketInterface) {
    return;
  }
  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (addr == nullptr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  RawAddress addressVar = *(RawAddress*)addr;
  sBluetoothSocketInterface->request_max_tx_data_length(addressVar);
  env->ReleaseByteArrayElements(address, addr, 1);
}

static int getMetricIdNative(JNIEnv* env, jobject /* obj */,
                             jbyteArray address) {
  ALOGV("%s", __func__);
  if (!sBluetoothInterface) return 0;  // 0 is invalid id
  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (addr == nullptr) {
    jniThrowIOException(env, EINVAL);
    return 0;
  }
  RawAddress addr_obj = {};
  addr_obj.FromOctets((uint8_t*)addr);
  return sBluetoothInterface->get_metric_id(addr_obj);
}

static jboolean allowLowLatencyAudioNative(JNIEnv* env, jobject /* obj */,
                                           jboolean allowed,
                                           jbyteArray address) {
  ALOGV("%s", __func__);
  if (!sBluetoothInterface) return false;
  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (addr == nullptr) {
    jniThrowIOException(env, EINVAL);
    return false;
  }

  RawAddress addr_obj = {};
  addr_obj.FromOctets((uint8_t*)addr);
  sBluetoothInterface->allow_low_latency_audio(allowed, addr_obj);
  return true;
}

static void metadataChangedNative(JNIEnv* env, jobject /* obj */,
                                  jbyteArray address, jint key,
                                  jbyteArray value) {
  ALOGV("%s", __func__);
  if (!sBluetoothInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (addr == nullptr) {
    jniThrowIOException(env, EINVAL);
    return;
  }
  RawAddress addr_obj = {};
  addr_obj.FromOctets((uint8_t*)addr);

  if (value == NULL) {
    ALOGE("metadataChangedNative() ignoring NULL array");
    return;
  }

  uint16_t len = (uint16_t)env->GetArrayLength(value);
  jbyte* p_value = env->GetByteArrayElements(value, NULL);
  if (p_value == NULL) return;

  std::vector<uint8_t> val_vec(reinterpret_cast<uint8_t*>(p_value),
                               reinterpret_cast<uint8_t*>(p_value + len));
  env->ReleaseByteArrayElements(value, p_value, 0);

  sBluetoothInterface->metadata_changed(addr_obj, key, std::move(val_vec));
  return;
}

static jboolean isLogRedactionEnabledNative(JNIEnv* /* env */,
                                            jobject /* obj */) {
  ALOGV("%s", __func__);
  return bluetooth::os::should_log_be_redacted();
}

static jboolean interopMatchAddrNative(JNIEnv* env, jclass /* clazz */,
                                       jstring feature_name, jstring address) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) {
    ALOGW("%s: sBluetoothInterface is null.", __func__);
    return JNI_FALSE;
  }

  const char* tmp_addr = env->GetStringUTFChars(address, NULL);
  if (!tmp_addr) {
    ALOGW("%s: address is null.", __func__);
    return JNI_FALSE;
  }
  RawAddress bdaddr;
  bool success = RawAddress::FromString(tmp_addr, bdaddr);

  env->ReleaseStringUTFChars(address, tmp_addr);

  if (!success) {
    ALOGW("%s: address is invalid.", __func__);
    return JNI_FALSE;
  }

  const char* feature_name_str = env->GetStringUTFChars(feature_name, NULL);
  if (!feature_name_str) {
    ALOGW("%s: feature name is null.", __func__);
    return JNI_FALSE;
  }

  bool matched =
      sBluetoothInterface->interop_match_addr(feature_name_str, &bdaddr);
  env->ReleaseStringUTFChars(feature_name, feature_name_str);

  return matched ? JNI_TRUE : JNI_FALSE;
}

static jboolean interopMatchNameNative(JNIEnv* env, jclass /* clazz */,
                                       jstring feature_name, jstring name) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) {
    ALOGW("%s: sBluetoothInterface is null.", __func__);
    return JNI_FALSE;
  }

  const char* feature_name_str = env->GetStringUTFChars(feature_name, NULL);
  if (!feature_name_str) {
    ALOGW("%s: feature name is null.", __func__);
    return JNI_FALSE;
  }

  const char* name_str = env->GetStringUTFChars(name, NULL);
  if (!name_str) {
    ALOGW("%s: name is null.", __func__);
    env->ReleaseStringUTFChars(feature_name, feature_name_str);
    return JNI_FALSE;
  }

  bool matched =
      sBluetoothInterface->interop_match_name(feature_name_str, name_str);
  env->ReleaseStringUTFChars(feature_name, feature_name_str);
  env->ReleaseStringUTFChars(name, name_str);

  return matched ? JNI_TRUE : JNI_FALSE;
}

static jboolean interopMatchAddrOrNameNative(JNIEnv* env, jclass /* clazz */,
                                             jstring feature_name,
                                             jstring address) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) {
    ALOGW("%s: sBluetoothInterface is null.", __func__);
    return JNI_FALSE;
  }

  const char* tmp_addr = env->GetStringUTFChars(address, NULL);
  if (!tmp_addr) {
    ALOGW("%s: address is null.", __func__);
    return JNI_FALSE;
  }
  RawAddress bdaddr;
  bool success = RawAddress::FromString(tmp_addr, bdaddr);

  env->ReleaseStringUTFChars(address, tmp_addr);

  if (!success) {
    ALOGW("%s: address is invalid.", __func__);
    return JNI_FALSE;
  }

  const char* feature_name_str = env->GetStringUTFChars(feature_name, NULL);
  if (!feature_name_str) {
    ALOGW("%s: feature name is null.", __func__);
    return JNI_FALSE;
  }

  bool matched = sBluetoothInterface->interop_match_addr_or_name(
      feature_name_str, &bdaddr);
  env->ReleaseStringUTFChars(feature_name, feature_name_str);

  return matched ? JNI_TRUE : JNI_FALSE;
}

static void interopDatabaseAddRemoveAddrNative(JNIEnv* env, jclass /* clazz */,
                                               jboolean do_add,
                                               jstring feature_name,
                                               jstring address, jint length) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) {
    ALOGW("%s: sBluetoothInterface is null.", __func__);
    return;
  }

  if ((do_add == JNI_TRUE) && (length <= 0 || length > 6)) {
    ALOGE("%s: address length %d is invalid, valid length is [1,6]", __func__,
          length);
    return;
  }

  const char* tmp_addr = env->GetStringUTFChars(address, NULL);
  if (!tmp_addr) {
    ALOGW("%s: address is null.", __func__);
    return;
  }
  RawAddress bdaddr;
  bool success = RawAddress::FromString(tmp_addr, bdaddr);

  env->ReleaseStringUTFChars(address, tmp_addr);

  if (!success) {
    ALOGW("%s: address is invalid.", __func__);
    return;
  }

  const char* feature_name_str = env->GetStringUTFChars(feature_name, NULL);
  if (!feature_name_str) {
    ALOGW("%s: feature name is null.", __func__);
    return;
  }

  sBluetoothInterface->interop_database_add_remove_addr(
      (do_add == JNI_TRUE), feature_name_str, &bdaddr, (int)length);

  env->ReleaseStringUTFChars(feature_name, feature_name_str);
}

static void interopDatabaseAddRemoveNameNative(JNIEnv* env, jclass /* clazz */,
                                               jboolean do_add,
                                               jstring feature_name,
                                               jstring name) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) {
    ALOGW("%s: sBluetoothInterface is null.", __func__);
    return;
  }

  const char* feature_name_str = env->GetStringUTFChars(feature_name, NULL);
  if (!feature_name_str) {
    ALOGW("%s: feature name is null.", __func__);
    return;
  }

  const char* name_str = env->GetStringUTFChars(name, NULL);
  if (!name_str) {
    ALOGW("%s: name is null.", __func__);
    env->ReleaseStringUTFChars(feature_name, feature_name_str);
    return;
  }

  sBluetoothInterface->interop_database_add_remove_name(
      (do_add == JNI_TRUE), feature_name_str, name_str);

  env->ReleaseStringUTFChars(feature_name, feature_name_str);
  env->ReleaseStringUTFChars(name, name_str);
}

static int getRemotePbapPceVersionNative(JNIEnv* env, jobject /* obj */,
                                         jstring address) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  const char* tmp_addr = env->GetStringUTFChars(address, NULL);
  if (!tmp_addr) {
    ALOGW("%s: address is null.", __func__);
    return JNI_FALSE;
  }

  RawAddress bdaddr;
  bool success = RawAddress::FromString(tmp_addr, bdaddr);

  env->ReleaseStringUTFChars(address, tmp_addr);

  if (!success) {
    ALOGW("%s: address is invalid.", __func__);
    return JNI_FALSE;
  }

  return sBluetoothInterface->get_remote_pbap_pce_version(&bdaddr);
}

static jboolean pbapPseDynamicVersionUpgradeIsEnabledNative(JNIEnv* /* env */,
                                                            jobject /* obj */) {
  ALOGV("%s", __func__);

  if (!sBluetoothInterface) return JNI_FALSE;

  return sBluetoothInterface->pbap_pse_dynamic_version_upgrade_is_enabled()
             ? JNI_TRUE
             : JNI_FALSE;
}

int register_com_android_bluetooth_btservice_AdapterService(JNIEnv* env) {
  const JNINativeMethod methods[] = {
      {"initNative", "(ZZI[Ljava/lang/String;ZLjava/lang/String;)Z",
       (void*)initNative},
      {"cleanupNative", "()V", (void*)cleanupNative},
      {"enableNative", "()Z", (void*)enableNative},
      {"disableNative", "()Z", (void*)disableNative},
      {"setAdapterPropertyNative", "(I[B)Z", (void*)setAdapterPropertyNative},
      {"getAdapterPropertiesNative", "()Z", (void*)getAdapterPropertiesNative},
      {"getAdapterPropertyNative", "(I)Z", (void*)getAdapterPropertyNative},
      {"getDevicePropertyNative", "([BI)Z", (void*)getDevicePropertyNative},
      {"setDevicePropertyNative", "([BI[B)Z", (void*)setDevicePropertyNative},
      {"startDiscoveryNative", "()Z", (void*)startDiscoveryNative},
      {"cancelDiscoveryNative", "()Z", (void*)cancelDiscoveryNative},
      {"createBondNative", "([BII)Z", (void*)createBondNative},
      {"createBondOutOfBandNative",
       "([BILandroid/bluetooth/OobData;Landroid/bluetooth/OobData;)Z",
       (void*)createBondOutOfBandNative},
      {"removeBondNative", "([B)Z", (void*)removeBondNative},
      {"cancelBondNative", "([B)Z", (void*)cancelBondNative},
      {"generateLocalOobDataNative", "(I)V", (void*)generateLocalOobDataNative},
      {"getConnectionStateNative", "([B)I", (void*)getConnectionStateNative},
      {"pinReplyNative", "([BZI[B)Z", (void*)pinReplyNative},
      {"sspReplyNative", "([BIZI)Z", (void*)sspReplyNative},
      {"getRemoteServicesNative", "([BI)Z", (void*)getRemoteServicesNative},
      {"readEnergyInfoNative", "()I", (void*)readEnergyInfoNative},
      {"dumpNative", "(Ljava/io/FileDescriptor;[Ljava/lang/String;)V",
       (void*)dumpNative},
      {"dumpMetricsNative", "()[B", (void*)dumpMetricsNative},
      {"factoryResetNative", "()Z", (void*)factoryResetNative},
      {"obfuscateAddressNative", "([B)[B", (void*)obfuscateAddressNative},
      {"setBufferLengthMillisNative", "(II)Z",
       (void*)setBufferLengthMillisNative},
      {"getMetricIdNative", "([B)I", (void*)getMetricIdNative},
      {"connectSocketNative", "([BI[BIII)I", (void*)connectSocketNative},
      {"createSocketChannelNative", "(ILjava/lang/String;[BIII)I",
       (void*)createSocketChannelNative},
      {"requestMaximumTxDataLengthNative", "([B)V",
       (void*)requestMaximumTxDataLengthNative},
      {"allowLowLatencyAudioNative", "(Z[B)Z",
       (void*)allowLowLatencyAudioNative},
      {"metadataChangedNative", "([BI[B)V", (void*)metadataChangedNative},
      {"isLogRedactionEnabledNative", "()Z",
       (void*)isLogRedactionEnabledNative},
      {"interopMatchAddrNative", "(Ljava/lang/String;Ljava/lang/String;)Z",
       (void*)interopMatchAddrNative},
      {"interopMatchNameNative", "(Ljava/lang/String;Ljava/lang/String;)Z",
       (void*)interopMatchNameNative},
      {"interopMatchAddrOrNameNative",
       "(Ljava/lang/String;Ljava/lang/String;)Z",
       (void*)interopMatchAddrOrNameNative},
      {"interopDatabaseAddRemoveAddrNative",
       "(ZLjava/lang/String;Ljava/lang/String;I)V",
       (void*)interopDatabaseAddRemoveAddrNative},
      {"interopDatabaseAddRemoveNameNative",
       "(ZLjava/lang/String;Ljava/lang/String;)V",
       (void*)interopDatabaseAddRemoveNameNative},
      {"getRemotePbapPceVersionNative", "(Ljava/lang/String;)I",
       (void*)getRemotePbapPceVersionNative},
      {"pbapPseDynamicVersionUpgradeIsEnabledNative", "()Z",
       (void*)pbapPseDynamicVersionUpgradeIsEnabledNative},
  };
  const int result = REGISTER_NATIVE_METHODS(
      env, "com/android/bluetooth/btservice/AdapterNativeInterface", methods);
  if (result != 0) {
    return result;
  }

  jclass jniAdapterNativeInterfaceClass =
      env->FindClass("com/android/bluetooth/btservice/AdapterNativeInterface");
  sJniCallbacksField =
      env->GetFieldID(jniAdapterNativeInterfaceClass, "mJniCallbacks",
                      "Lcom/android/bluetooth/btservice/JniCallbacks;");
  env->DeleteLocalRef(jniAdapterNativeInterfaceClass);

  const JNIJavaMethod javaMethods[] = {
      {"oobDataReceivedCallback", "(ILandroid/bluetooth/OobData;)V",
       &method_oobDataReceivedCallback},
      {"stateChangeCallback", "(I)V", &method_stateChangeCallback},
      {"adapterPropertyChangedCallback", "([I[[B)V",
       &method_adapterPropertyChangedCallback},
      {"discoveryStateChangeCallback", "(I)V",
       &method_discoveryStateChangeCallback},
      {"devicePropertyChangedCallback", "([B[I[[B)V",
       &method_devicePropertyChangedCallback},
      {"deviceFoundCallback", "([B)V", &method_deviceFoundCallback},
      {"pinRequestCallback", "([B[BIZ)V", &method_pinRequestCallback},
      {"sspRequestCallback", "([B[BIII)V", &method_sspRequestCallback},
      {"bondStateChangeCallback", "(I[BII)V", &method_bondStateChangeCallback},
      {"addressConsolidateCallback", "([B[B)V",
       &method_addressConsolidateCallback},
      {"leAddressAssociateCallback", "([B[B)V",
       &method_leAddressAssociateCallback},
      {"aclStateChangeCallback", "(I[BIIII)V", &method_aclStateChangeCallback},
      {"linkQualityReportCallback", "(JIIIIII)V",
       &method_linkQualityReportCallback},
      {"switchBufferSizeCallback", "(Z)V", &method_switchBufferSizeCallback},
      {"switchCodecCallback", "(Z)V", &method_switchCodecCallback},
      {"acquireWakeLock", "(Ljava/lang/String;)Z", &method_acquireWakeLock},
      {"releaseWakeLock", "(Ljava/lang/String;)Z", &method_releaseWakeLock},
      {"energyInfoCallback", "(IIJJJJ[Landroid/bluetooth/UidTraffic;)V",
       &method_energyInfo},
  };
  GET_JAVA_METHODS(env, "com/android/bluetooth/btservice/JniCallbacks",
                   javaMethods);

  const JNIJavaMethod javaUuidTrafficMethods[] = {
      {"<init>", "(IJJ)V", &android_bluetooth_UidTraffic.constructor},
  };
  GET_JAVA_METHODS(env, "android/bluetooth/UidTraffic", javaUuidTrafficMethods);

  if (env->GetJavaVM(&vm) != JNI_OK) {
    ALOGE("Could not get JavaVM");
  }

  if (hal_util_load_bt_library((bt_interface_t const**)&sBluetoothInterface)) {
    ALOGE("No Bluetooth Library found");
  }

  return 0;
}

} /* namespace android */

/*
 * JNI Initialization
 */
jint JNI_OnLoad(JavaVM* jvm, void* /* reserved */) {
  JNIEnv* e;
  int status;

  ALOGV("Bluetooth Adapter Service : loading JNI\n");

  // Check JNI version
  if (jvm->GetEnv((void**)&e, JNI_VERSION_1_6)) {
    ALOGE("JNI version mismatch error");
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_btservice_AdapterService(e);
  if (status < 0) {
    ALOGE("jni adapter service registration failure, status: %d", status);
    return JNI_ERR;
  }

  status =
      android::register_com_android_bluetooth_btservice_BluetoothKeystore(e);
  if (status < 0) {
    ALOGE("jni BluetoothKeyStore registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_hfp(e);
  if (status < 0) {
    ALOGE("jni hfp registration failure, status: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_hfpclient(e);
  if (status < 0) {
    ALOGE("jni hfp client registration failure, status: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_a2dp(e);
  if (status < 0) {
    ALOGE("jni a2dp source registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_a2dp_sink(e);
  if (status < 0) {
    ALOGE("jni a2dp sink registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_avrcp_target(e);
  if (status < 0) {
    ALOGE("jni new avrcp target registration failure: %d", status);
  }

  status = android::register_com_android_bluetooth_avrcp_controller(e);
  if (status < 0) {
    ALOGE("jni avrcp controller registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_hid_host(e);
  if (status < 0) {
    ALOGE("jni hid registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_hid_device(e);
  if (status < 0) {
    ALOGE("jni hidd registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_pan(e);
  if (status < 0) {
    ALOGE("jni pan registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_gatt(e);
  if (status < 0) {
    ALOGE("jni gatt registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_sdp(e);
  if (status < 0) {
    ALOGE("jni sdp registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_hearing_aid(e);
  if (status < 0) {
    ALOGE("jni hearing aid registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_hap_client(e);
  if (status < 0) {
    ALOGE("jni le audio hearing access client registration failure: %d",
          status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_le_audio(e);
  if (status < 0) {
    ALOGE("jni le_audio registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_vc(e);
  if (status < 0) {
    ALOGE("jni vc registration failure: %d", status);
    return JNI_ERR;
  }

  status = android::register_com_android_bluetooth_csip_set_coordinator(e);
  if (status < 0) {
    ALOGE("jni csis client registration failure: %d", status);
    return JNI_ERR;
  }

  status =
      android::register_com_android_bluetooth_btservice_BluetoothQualityReport(
          e);
  if (status < 0) {
    ALOGE("jni bluetooth quality report registration failure: %d", status);
    return JNI_ERR;
  }

  return JNI_VERSION_1_6;
}

namespace android {

/** Load the java methods or die*/
void jniGetMethodsOrDie(JNIEnv* env, const char* className,
                        const JNIJavaMethod* methods, int nMethods) {
  jclass clazz = env->FindClass(className);
  if (clazz == nullptr) {
    LOG(FATAL) << "Native registration unable to find class '" << className
               << "'; aborting...";
  }

  for (int i = 0; i < nMethods; i++) {
    const JNIJavaMethod& method = methods[i];
    if (method.is_static) {
      *method.id = env->GetStaticMethodID(clazz, method.name, method.signature);
    } else {
      *method.id = env->GetMethodID(clazz, method.name, method.signature);
    }
    if (method.id == nullptr) {
      LOG(FATAL) << "In class " << className << ": Unable to find '"
                 << method.name << "' with signature=" << method.signature
                 << " is_static=" << method.is_static;
    }
  }

  env->DeleteLocalRef(clazz);
}
}  // namespace android
