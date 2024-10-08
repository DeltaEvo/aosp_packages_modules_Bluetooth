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
package com.android.bluetooth.hfpclient;

import android.bluetooth.BluetoothDevice;
import android.telecom.Conference;
import android.telecom.Connection;
import android.telecom.PhoneAccountHandle;
import android.util.Log;

public class HfpClientConference extends Conference {
    private static final String TAG = "HfpClientConference";

    private final BluetoothDevice mDevice;
    private final HeadsetClientServiceInterface mServiceInterface;

    public HfpClientConference(
            BluetoothDevice device,
            PhoneAccountHandle handle,
            HeadsetClientServiceInterface serviceInterface) {
        super(handle);
        mDevice = device;
        mServiceInterface = serviceInterface;
        boolean manage = mServiceInterface.hasHfpClientEcc(device);
        setConnectionCapabilities(
                Connection.CAPABILITY_SUPPORT_HOLD
                        | Connection.CAPABILITY_HOLD
                        | (manage ? Connection.CAPABILITY_MANAGE_CONFERENCE : 0));
        setActive();
    }

    @Override
    public void onDisconnect() {
        Log.d(TAG, "onDisconnect");
        mServiceInterface.terminateCall(mDevice, null);
    }

    @Override
    public void onMerge(Connection connection) {
        Log.d(TAG, "onMerge " + connection);
        addConnection(connection);
    }

    @Override
    public void onSeparate(Connection connection) {
        Log.d(TAG, "onSeparate " + connection);
        ((HfpClientConnection) connection).enterPrivateMode();
        removeConnection(connection);
    }

    @Override
    public void onHold() {
        Log.d(TAG, "onHold");
        mServiceInterface.holdCall(mDevice);
    }

    @Override
    public void onUnhold() {
        Log.d(TAG, "onUnhold");
        mServiceInterface.acceptCall(mDevice, HeadsetClientServiceInterface.CALL_ACCEPT_HOLD);
    }

    @Override
    public void onPlayDtmfTone(char c) {
        Log.d(TAG, "onPlayDtmfTone " + c);
        mServiceInterface.sendDTMF(mDevice, (byte) c);
    }

    @Override
    public void onConnectionAdded(Connection connection) {
        Log.d(TAG, "onConnectionAdded " + connection);
        if (connection.getState() == Connection.STATE_HOLDING
                && getState() == Connection.STATE_ACTIVE) {
            connection.onAnswer();
        } else if (connection.getState() == Connection.STATE_ACTIVE
                && getState() == Connection.STATE_HOLDING) {
            mServiceInterface.acceptCall(mDevice, HeadsetClientServiceInterface.CALL_ACCEPT_NONE);
        }
    }
}
