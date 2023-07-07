/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import android.os.Handler;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.obex.Authenticator;
import com.android.obex.PasswordAuthentication;

import java.util.Arrays;

/* ObexAuthentication is a required component for PBAP in order to support backwards compatibility
 * with PSE devices prior to PBAP 1.2. With profiles prior to 1.2 the actual initiation of
 * authentication is implementation defined.
 */
class BluetoothPbapObexAuthenticator implements Authenticator {

    private static final String TAG = "PbapClientObexAuth";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);

    //Default session key for legacy devices is 0000
    @VisibleForTesting
    String mSessionKey = "0000";

    private final Handler mCallback;

    BluetoothPbapObexAuthenticator(Handler callback) {
        mCallback = callback;
    }

    @Override
    public PasswordAuthentication onAuthenticationChallenge(String description,
            boolean isUserIdRequired, boolean isFullAccess) {
        PasswordAuthentication pa = null;
        if (DBG) Log.d(TAG, "onAuthenticationChallenge: starting");

        if (mSessionKey != null && mSessionKey.length() != 0) {
            if (DBG) Log.d(TAG, "onAuthenticationChallenge: mSessionKey=" + mSessionKey);
            pa = new PasswordAuthentication(null, mSessionKey.getBytes());
        } else {
            if (DBG) {
                Log.d(TAG,
                        "onAuthenticationChallenge: mSessionKey is empty, timeout/cancel occured");
            }
        }

        return pa;
    }

    @Override
    public byte[] onAuthenticationResponse(byte[] userName) {
        if (VDBG) Log.v(TAG, "onAuthenticationResponse: " + Arrays.toString(userName));
        /* required only in case PCE challenges PSE which we don't do now */
        return null;
    }
}
