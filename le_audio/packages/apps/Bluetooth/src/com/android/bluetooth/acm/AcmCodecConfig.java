/******************************************************************************
 *
 *  Copyright 2021 The Android Open Source Project
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


package com.android.bluetooth.acm;

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.SystemProperties;
import android.util.Log;
import com.android.bluetooth.R;
import com.android.bluetooth.btservice.AdapterService;

import java.util.Arrays;
import java.util.Objects;
/*
 * ACM Codec Configuration setup.
 */
class AcmCodecConfig {
    private static final boolean DBG = true;
    private static final String TAG = "AcmCodecConfig";
    static final int CONTEXT_TYPE_UNKNOWN = 0;
    static final int CONTEXT_TYPE_MUSIC = 1;
    static final int CONTEXT_TYPE_VOICE = 2;
    static final int CONTEXT_TYPE_MUSIC_VOICE = 3;

    private Context mContext;
    private AcmNativeInterface mAcmNativeInterface;

    private BluetoothCodecConfig[] mCodecConfigPriorities;
    private int mAcmSourceCodecPriorityLC3 = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;

    private int assigned_codec_length = 0;
    AcmCodecConfig(Context context, AcmNativeInterface acmNativeInterface) {
        mContext = context;
        mAcmNativeInterface = acmNativeInterface;
        mCodecConfigPriorities = assignCodecConfigPriorities();
    }

    BluetoothCodecConfig[] codecConfigPriorities() {
        return mCodecConfigPriorities;
    }

    void setCodecConfigPreference(BluetoothDevice device,
                                  BluetoothCodecConfig newCodecConfig,
                                  int contextType) {
        //Objects.requireNonNull(codecStatus);

        /*// Check whether the codecConfig is selectable for this Bluetooth device.
        BluetoothCodecConfig[] selectableCodecs = codecStatus.getCodecsSelectableCapabilities();
        if (!Arrays.asList(selectableCodecs).stream().anyMatch(codec ->
                codec.isMandatoryCodec())) {
            // Do not set codec preference to native if the selectableCodecs not contain mandatory
            // codec. The reason could be remote codec negotiation is not completed yet.
            Log.w(TAG, "setCodecConfigPreference: must have mandatory codec before changing.");
            return;
        }
        if (!codecStatus.isCodecConfigSelectable(newCodecConfig)) {
            Log.w(TAG, "setCodecConfigPreference: invalid codec "
                    + Objects.toString(newCodecConfig));
            return;
        }

        // Check whether the codecConfig would change current codec config.
        int prioritizedCodecType = getPrioitizedCodecType(newCodecConfig, selectableCodecs);
        BluetoothCodecConfig currentCodecConfig = codecStatus.getCodecConfig();
        if (prioritizedCodecType == currentCodecConfig.getCodecType()
                && (prioritizedCodecType != newCodecConfig.getCodecType()
                || (currentCodecConfig.similarCodecFeedingParameters(newCodecConfig)
                && currentCodecConfig.sameCodecSpecificParameters(newCodecConfig)))) {
            // Same codec with same parameters, no need to send this request to native.
            Log.w(TAG, "setCodecConfigPreference: codec not changed.");
            return;
        }*/

        BluetoothCodecConfig[] codecConfigArray = new BluetoothCodecConfig[1];
        codecConfigArray[0] = newCodecConfig;
        mAcmNativeInterface.setCodecConfigPreference(device, codecConfigArray, contextType, contextType);
    }

    // Get the codec type of the highest priority of selectableCodecs and codecConfig.
    private int getPrioitizedCodecType(BluetoothCodecConfig codecConfig,
            BluetoothCodecConfig[] selectableCodecs) {
        BluetoothCodecConfig prioritizedCodecConfig = codecConfig;
        for (BluetoothCodecConfig config : selectableCodecs) {
            if (prioritizedCodecConfig == null) {
                prioritizedCodecConfig = config;
            }
            if (config.getCodecPriority() > prioritizedCodecConfig.getCodecPriority()) {
                prioritizedCodecConfig = config;
            }
        }
        return prioritizedCodecConfig.getCodecType();
    }

    // Assign the ACM Source codec config priorities
    private BluetoothCodecConfig[] assignCodecConfigPriorities() {
        Resources resources = mContext.getResources();
        if (resources == null) {
            return null;
        }

        int value;
        mAcmSourceCodecPriorityLC3 = BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST;

        BluetoothCodecConfig codecConfig;
        BluetoothCodecConfig[] codecConfigArray;
        int codecCount = 0;
        codecConfigArray =
                new BluetoothCodecConfig[BluetoothCodecConfig.SOURCE_QVA_CODEC_TYPE_MAX];

        codecConfig = new BluetoothCodecConfig.Builder()
                          .setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3)
                          .setCodecPriority(mAcmSourceCodecPriorityLC3)
                          .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_NONE)
                          .setBitsPerSample(luetoothCodecConfig.BITS_PER_SAMPLE_NONE)
                          .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_NONE)
                          .setCodecSpecific1(0)
                          .setCodecSpecific2(0)
                          .setCodecSpecific3(0)
                          .setCodecSpecific4(0)
                          .build();
        codecConfigArray[codecCount++] = codecConfig;
        assigned_codec_length = codecCount;
        return codecConfigArray;
    }
}

