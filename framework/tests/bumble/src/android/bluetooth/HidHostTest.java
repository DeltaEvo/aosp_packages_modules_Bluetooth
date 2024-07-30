/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Empty;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import pandora.HIDGrpc;

/** Test cases for {@link Hid Host}. */
@RunWith(AndroidJUnit4.class)
public class HidHostTest {
    private static final String TAG = "HidHostTest";
    private SettableFuture<Integer> mFutureConnectionIntent, mFutureBondIntent;
    private SettableFuture<Boolean> mAclConnectionIntent;
    private BluetoothDevice mDevice;
    private BluetoothHidHost mService;
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();
    private HIDGrpc.HIDBlockingStub mHidBlockingStub;

    @Rule(order = 0)
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 1)
    public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule(order = 2)
    public final PandoraDevice mBumble = new PandoraDevice();

    private BroadcastReceiver mConnectionStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED.equals(
                            intent.getAction())) {
                        int state =
                                intent.getIntExtra(
                                        BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR);
                        Log.i(TAG, "Connection state change:" + state);
                        if (state == BluetoothProfile.STATE_CONNECTED
                                || state == BluetoothProfile.STATE_DISCONNECTED) {
                            if (mFutureConnectionIntent != null) {
                                mFutureConnectionIntent.set(state);
                            }
                        }
                    } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                        mBumble.getRemoteDevice().setPairingConfirmation(true);
                    } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(
                            intent.getAction())) {
                        int bondState =
                                intent.getIntExtra(
                                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                        Log.i(TAG, "Bond state change:" + bondState);
                        if (bondState == BluetoothDevice.BOND_BONDED
                                || bondState == BluetoothDevice.BOND_NONE) {
                            if (mFutureBondIntent != null) {
                                mFutureBondIntent.set(bondState);
                            }
                        }
                    } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                        if (mAclConnectionIntent != null) {
                            mAclConnectionIntent.set(true);
                        }
                    }
                }
            };

    // These callbacks run on the main thread.
    private final class HidHostServiceListener implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothHidHost) proxy;
        }

        public void onServiceDisconnected(int profile) {}
    }

    @Before
    public void setUp() throws Exception {
        mContext.registerReceiver(
                mConnectionStateReceiver,
                new IntentFilter(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED));
        mContext.registerReceiver(
                mConnectionStateReceiver, new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST));
        mContext.registerReceiver(
                mConnectionStateReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        mContext.registerReceiver(
                mConnectionStateReceiver,
                new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
        mAdapter.getProfileProxy(mContext, new HidHostServiceListener(), BluetoothProfile.HID_HOST);
        mHidBlockingStub = mBumble.hidBlocking();
        mFutureConnectionIntent = SettableFuture.create();

        mDevice = mBumble.getRemoteDevice();
        assertThat(mDevice.createBond()).isTrue();

        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);
    }

    @After
    public void tearDown() throws Exception {
        if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
            mFutureBondIntent = SettableFuture.create();
            mDevice.removeBond();
            assertThat(mFutureBondIntent.get()).isEqualTo(BluetoothDevice.BOND_NONE);
        }

        if (mDevice.isConnected()) {
            mAclConnectionIntent = SettableFuture.create();
            mDevice.disconnect();
            assertThat(mAclConnectionIntent.get()).isTrue();
        }
        mContext.unregisterReceiver(mConnectionStateReceiver);
    }

    /**
     * Test HID Disconnection:
     *
     * <ol>
     *   <li>1. Android tries to create bond, emitting bonding intent 4. Android confirms the
     *       pairing via pairing request intent
     *   <li>2. Bumble confirms the pairing internally
     *   <li>3. Android tries to HID connect and verifies Connection state intent
     *   <li>4. Bumble Disconnect the HID and Android verifies Connection state intent
     * </ol>
     */
    @Test
    public void disconnectHidDeviceTest() throws Exception {

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());

        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    /**
     * Test HID Connection Policy Disable:
     *
     * <ol>
     *   <li>1. Android tries to create bond, emitting bonding intent 4. Android confirms the
     *       pairing via pairing request intent
     *   <li>2. Bumble confirms the pairing internally
     *   <li>3. Android tries to HID connect and verifies Connection state intent
     *   <li>4. Bumble Disconnect the HID and Android verifies Connection state intent
     *   <li>5. Android Disable the HID connection policy
     *   <li>6. Bumble connetct the HID and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void disableHidConnectionPolicyTest() throws Exception {

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());

        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        assertThat(
                        mService.setConnectionPolicy(
                                mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        assertThat(mService.getConnectionState(mDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }
}
