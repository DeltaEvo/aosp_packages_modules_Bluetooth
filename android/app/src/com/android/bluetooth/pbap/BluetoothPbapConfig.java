/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.bluetooth.pbap;

import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.R;
import com.android.bluetooth.content_profiles.ContentProfileErrorReportUtils;
import com.android.internal.annotations.VisibleForTesting;

// Next tag value for ContentProfileErrorReportUtils.report(): 2
public class BluetoothPbapConfig {
    private static boolean sUseProfileForOwnerVcard = true;
    private static boolean sIncludePhotosInVcard = false;

    public static void init(Context ctx) {
        Resources r = ctx.getResources();
        if (r != null) {
            try {
                sUseProfileForOwnerVcard = r.getBoolean(R.bool.pbap_use_profile_for_owner_vcard);
            } catch (Exception e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.PBAP,
                        BluetoothProtoEnums.BLUETOOTH_PBAP_CONFIG,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        0);

                Log.e("BluetoothPbapConfig", "", e);
            }
            try {
                sIncludePhotosInVcard = r.getBoolean(R.bool.pbap_include_photos_in_vcard);
            } catch (Exception e) {
                ContentProfileErrorReportUtils.report(
                        BluetoothProfile.PBAP,
                        BluetoothProtoEnums.BLUETOOTH_PBAP_CONFIG,
                        BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                        1);
                Log.e("BluetoothPbapConfig", "", e);
            }
        }
    }

    /** If true, owner vcard will be generated from the "Me" profile */
    public static boolean useProfileForOwnerVcard() {
        return sUseProfileForOwnerVcard;
    }

    /** If true, include photos in contact information returned to PCE */
    public static boolean includePhotosInVcard() {
        return sIncludePhotosInVcard;
    }

    @VisibleForTesting
    public static void setIncludePhotosInVcard(boolean includePhotosInVcard) {
        sIncludePhotosInVcard = includePhotosInVcard;
    }
}
