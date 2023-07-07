/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.apishim;

import static com.android.modules.utils.build.SdkLevel.isAtLeastU;

import android.os.Build;
import android.os.Bundle;
import android.telecom.Call;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.bluetooth.apishim.common.BluetoothCallShim;

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class BluetoothCallShimImpl extends
        com.android.bluetooth.apishim.api33.BluetoothCallShimImpl {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static BluetoothCallShim newInstance() {
        if (!isAtLeastU()) {
            return com.android.bluetooth.apishim.api33.BluetoothCallShimImpl
                    .newInstance();
        }
        return new BluetoothCallShimImpl();
    }

    @Override
    public boolean isSilentRingingRequested(@Nullable Bundle extras) {
        return extras != null && (extras.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED)
                || extras.getBoolean(Call.EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB));
    }
}