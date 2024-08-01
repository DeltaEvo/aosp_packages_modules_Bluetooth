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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Test cases for {@link Hid Host}. */
@RunWith(AndroidJUnit4.class)
public class HidHostTest {
    private static final String TAG = "HidHostTest";
    private SettableFuture<Integer> mFutureConnectionIntent,
            mFutureAdapterStateIntent,
            mFutureBondIntent;
    private SettableFuture<Boolean> mAclConnectionIntent;
    private BluetoothDevice mDevice;
    private BluetoothHidHost mHidService;
    private BluetoothHeadset mHfpService;
    private BluetoothA2dp mA2dpService;
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();
    private HIDGrpc.HIDBlockingStub mHidBlockingStub;
    private static final int CONNECTION_TIMEOUT_MS = 2_000;

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
                    } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                        int adapterState =
                                intent.getIntExtra(
                                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                        Log.i(TAG, "Adapter state change:" + adapterState);
                        if (adapterState == BluetoothAdapter.STATE_ON
                                || adapterState == BluetoothAdapter.STATE_OFF) {
                            if (mFutureAdapterStateIntent != null) {
                                mFutureAdapterStateIntent.set(adapterState);
                            }
                        }
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
    private final BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
            new BluetoothProfile.ServiceListener() {

                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    switch (profile) {
                        case BluetoothProfile.HEADSET:
                            mHfpService = (BluetoothHeadset) proxy;
                            break;
                        case BluetoothProfile.A2DP:
                            mA2dpService = (BluetoothA2dp) proxy;
                            break;
                        case BluetoothProfile.HID_HOST:
                            mHidService = (BluetoothHidHost) proxy;
                            break;
                        default:
                            break;
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {}
            };

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
        mAdapter.getProfileProxy(
                mContext, mBluetoothProfileServiceListener, BluetoothProfile.HID_HOST);
        mAdapter.getProfileProxy(mContext, mBluetoothProfileServiceListener, BluetoothProfile.A2DP);
        mAdapter.getProfileProxy(
                mContext, mBluetoothProfileServiceListener, BluetoothProfile.HEADSET);
        mHidBlockingStub = mBumble.hidBlocking();
        mFutureConnectionIntent = SettableFuture.create();

        mDevice = mBumble.getRemoteDevice();
        assertThat(mDevice.createBond()).isTrue();

        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);
        if (mA2dpService != null) {
            assertThat(
                            mA2dpService.setConnectionPolicy(
                                    mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
        if (mHfpService != null) {
            assertThat(
                            mHfpService.setConnectionPolicy(
                                    mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                    .isTrue();
        }
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
     * Test HID Device reconnection when connection policy change:
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     *   <li>5. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>6. Android disable connection policy
     *   <li>7. Bumble connect the HID and Android verifies Connection state intent
     *   <li>8. Android enable connection policy
     *   <li>9. Bumble disconnect HID and Android verifies Connection state intent
     *   <li>10. Bumble connect the HID and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hidReconnectionWhenConnectionPolicyChangeTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice))
                .isEqualTo(BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        assertThat(
                        mHidService.setConnectionPolicy(
                                mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        reconnectionFromRemoteAndVerifyDisconnectedState();

        mFutureConnectionIntent = SettableFuture.create();
        assertThat(
                        mHidService.setConnectionPolicy(
                                mDevice, BluetoothProfile.CONNECTION_POLICY_ALLOWED))
                .isTrue();
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);
    }

    /**
     * Test HID Device reconnection after BT restart with connection policy allowed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. BT restart on Android
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hidReconnectionAfterBTrestartWithConnectionPolicyAllowedTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice))
                .isEqualTo(BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        bluetoothRestart();

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_CONNECTED);
    }

    /**
     * Test HID Device reconnection after BT restart with connection policy disallowed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Android disable the connection policy
     *   <li>4. BT restart on Android
     *   <li>5. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hidReconnectionAfterBTrestartWithConnectionPolicyiDisallowedTest()
            throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice))
                .isEqualTo(BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        assertThat(
                        mHidService.setConnectionPolicy(
                                mDevice, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        bluetoothRestart();
        reconnectionFromRemoteAndVerifyDisconnectedState();
    }

    /**
     * Test HID Device reconnection when device is removed
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android verifies the connection policy
     *   <li>3. Android disconnect and remove the bond
     *   <li>4. Bumble reconnects and Android verifies Connection state intent
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hidReconnectionAfterDeviceRemovedTest() throws Exception {

        assertThat(mHidService.getConnectionPolicy(mDevice))
                .isEqualTo(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.disconnectHost(Empty.getDefaultInstance());

        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        mDevice.removeBond();

        mFutureConnectionIntent = SettableFuture.create();
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        assertThat(mHidService.getConnectionState(mDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    private void reconnectionFromRemoteAndVerifyDisconnectedState() throws Exception {
        mHidBlockingStub.connectHost(Empty.getDefaultInstance());
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeOnTimeout(null, CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS).join();
        assertThat(mHidService.getConnectionState(mDevice))
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }

    private void bluetoothRestart() throws Exception {
        mContext.registerReceiver(
                mConnectionStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        mAdapter.disable();
        mFutureAdapterStateIntent = SettableFuture.create();
        assertThat(mFutureAdapterStateIntent.get()).isEqualTo(BluetoothAdapter.STATE_OFF);

        mAdapter.enable();
        mFutureAdapterStateIntent = SettableFuture.create();
        assertThat(mFutureAdapterStateIntent.get()).isEqualTo(BluetoothAdapter.STATE_ON);
    }
}
