/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.os.ParcelUuid;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.flags.Flags;
import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.common.util.concurrent.SettableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import pandora.HIDGrpc;
import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.OwnAddressType;

/** Test cases for {@link Hid Host}. */
@RunWith(AndroidJUnit4.class)
public class HidHostDualModeTest {
    private static final String TAG = "HidHostDualModeTest";
    private SettableFuture<Integer> mFutureConnectionIntent,
            mFutureBondIntent,
            mFutureTransportIntent;
    private SettableFuture<Boolean> mFutureHogpServiceIntent;
    private BluetoothDevice mDevice;
    private BluetoothHidHost mHidService;
    private BluetoothHeadset mHfpService;
    private BluetoothA2dp mA2dpService;
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

    private BroadcastReceiver mHidStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    switch (intent.getAction()) {
                        case BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED:
                            int state =
                                    intent.getIntExtra(
                                            BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR);
                            int transport =
                                    intent.getIntExtra(
                                            BluetoothDevice.EXTRA_TRANSPORT,
                                            BluetoothDevice.TRANSPORT_AUTO);
                            Log.i(
                                    TAG,
                                    "Connection state change: "
                                            + state
                                            + "transport: "
                                            + transport);
                            if (state == BluetoothProfile.STATE_CONNECTED
                                    || state == BluetoothProfile.STATE_DISCONNECTED) {
                                if (mFutureConnectionIntent != null) {
                                    mFutureConnectionIntent.set(state);
                                }
                                if (state == BluetoothProfile.STATE_CONNECTED
                                        && mFutureTransportIntent != null) {
                                    mFutureTransportIntent.set(transport);
                                }
                            }
                            break;
                        case BluetoothDevice.ACTION_PAIRING_REQUEST:
                            mBumble.getRemoteDevice().setPairingConfirmation(true);
                            break;
                        case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                            int bondState =
                                    intent.getIntExtra(
                                            BluetoothDevice.EXTRA_BOND_STATE,
                                            BluetoothDevice.ERROR);
                            Log.i(TAG, "Bond state change:" + bondState);
                            if (bondState == BluetoothDevice.BOND_BONDED
                                    || bondState == BluetoothDevice.BOND_NONE) {
                                if (mFutureBondIntent != null) {
                                    mFutureBondIntent.set(bondState);
                                }
                            }
                            break;
                        case BluetoothDevice.ACTION_UUID:
                            ParcelUuid[] parcelUuids =
                                    intent.getParcelableArrayExtra(
                                            BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
                            for (int i = 0; i < parcelUuids.length; i++) {
                                Log.d(TAG, "UUIDs : index=" + i + " uuid=" + parcelUuids[i]);
                                if (parcelUuids[i].equals(BluetoothUuid.HOGP)) {
                                    if (mFutureHogpServiceIntent != null) {
                                        mFutureHogpServiceIntent.set(true);
                                    }
                                }
                            }
                            break;
                        default:
                            break;
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
        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        mContext.registerReceiver(mHidStateReceiver, filter);
        mAdapter.getProfileProxy(
                mContext, mBluetoothProfileServiceListener, BluetoothProfile.HID_HOST);
        mAdapter.getProfileProxy(mContext, mBluetoothProfileServiceListener, BluetoothProfile.A2DP);
        mAdapter.getProfileProxy(
                mContext, mBluetoothProfileServiceListener, BluetoothProfile.HEADSET);
        mHidBlockingStub = mBumble.hidBlocking();
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(OwnAddressType.RANDOM)
                        .build();
        mBumble.hostBlocking().advertise(request);

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
        mContext.unregisterReceiver(mHidStateReceiver);
    }

    /**
     * Test HID Preferred transport selection Test case
     *
     * <ol>
     *   <li>1. Android to creates bonding and HID connected with default transport.
     *   <li>2. Android switch the transport to LE and Verifies the transport
     *   <li>3. Android switch the transport to BR/EDR and Verifies the transport
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void setPreferredTransportTest() throws Exception {

        mFutureHogpServiceIntent = SettableFuture.create();
        assertThat(mFutureHogpServiceIntent.get()).isTrue();

        assertThat(mHidService.getPreferredTransport(mDevice))
                .isEqualTo(BluetoothDevice.TRANSPORT_BREDR);
        // LE transport
        mFutureTransportIntent = SettableFuture.create();
        mHidService.setPreferredTransport(mDevice, BluetoothDevice.TRANSPORT_LE);
        // Verifies BREDR transport Disconnected
        mFutureConnectionIntent = SettableFuture.create();
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        assertThat(mFutureTransportIntent.get()).isEqualTo(BluetoothDevice.TRANSPORT_LE);
        assertThat(mHidService.getPreferredTransport(mDevice))
                .isEqualTo(BluetoothDevice.TRANSPORT_LE);

        // BREDR transport
        mFutureTransportIntent = SettableFuture.create();
        mHidService.setPreferredTransport(mDevice, BluetoothDevice.TRANSPORT_BREDR);
        // Verifies LE transport Disconnected
        mFutureConnectionIntent = SettableFuture.create();
        assertThat(mFutureConnectionIntent.get()).isEqualTo(BluetoothProfile.STATE_DISCONNECTED);

        assertThat(mFutureTransportIntent.get()).isEqualTo(BluetoothDevice.TRANSPORT_BREDR);
        assertThat(mHidService.getPreferredTransport(mDevice))
                .isEqualTo(BluetoothDevice.TRANSPORT_BREDR);
    }
}
