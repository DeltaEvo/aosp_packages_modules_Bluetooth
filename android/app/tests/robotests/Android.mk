#############################################################
# Bluetooth Robolectric test target.                        #
#############################################################
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := BluetoothRoboTests
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/res

LOCAL_JAVA_RESOURCE_DIRS := config

# Include the testing libraries
LOCAL_JAVA_LIBRARIES := \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth

LOCAL_INSTRUMENTATION_FOR := Bluetooth

LOCAL_MODULE_TAGS := optional

#include $(BUILD_STATIC_JAVA_LIBRARY)

#############################################################
# Bluetooth runner target to run the previous target.       #
#############################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunBluetoothRoboTests
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice

LOCAL_JAVA_LIBRARIES := \
    BluetoothRoboTests \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth

LOCAL_TEST_PACKAGE := Bluetooth

LOCAL_INSTRUMENT_SOURCE_DIRS := $(dir $(LOCAL_PATH))../src

#include external/robolectric-shadows/run_robotests.mk
