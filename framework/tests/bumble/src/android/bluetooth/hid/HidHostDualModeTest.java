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
            mFutureHandShakeIntent,
            mFutureReportIntent,
            mFutureProtocolModeIntent,
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
    private byte mReportId;
    private static final int KEYBD_RPT_ID = 1;
    private static final int KEYBD_RPT_SIZE = 9;
    private static final int MOUSE_RPT_ID = 2;
    private static final int MOUSE_RPT_SIZE = 4;

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
                        case BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED:
                            int protocolMode =
                                    intent.getIntExtra(
                                            BluetoothHidHost.EXTRA_PROTOCOL_MODE,
                                            BluetoothHidHost.PROTOCOL_UNSUPPORTED_MODE);
                            Log.i(TAG, "Protocol mode:" + protocolMode);
                            if (mFutureProtocolModeIntent != null) {
                                mFutureProtocolModeIntent.set(protocolMode);
                            }
                            break;
                        case BluetoothHidHost.ACTION_HANDSHAKE:
                            int handShake =
                                    intent.getIntExtra(
                                            BluetoothHidHost.EXTRA_STATUS,
                                            BluetoothHidDevice.ERROR_RSP_UNKNOWN);
                            Log.i(TAG, "Handshake status:" + handShake);
                            if (mFutureHandShakeIntent != null) {
                                mFutureHandShakeIntent.set(handShake);
                            }
                            break;
                        case BluetoothHidHost.ACTION_REPORT:
                            byte[] report = intent.getByteArrayExtra(BluetoothHidHost.EXTRA_REPORT);
                            int reportSize =
                                    intent.getIntExtra(
                                            BluetoothHidHost.EXTRA_REPORT_BUFFER_SIZE, 0);
                            mReportId = report[0];
                            if (mFutureReportIntent != null) {
                                mFutureReportIntent.set((reportSize - 1));
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
        filter.addAction(BluetoothHidHost.ACTION_PROTOCOL_MODE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_HANDSHAKE);
        filter.addAction(BluetoothHidHost.ACTION_REPORT);
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

    /**
     * Test Get Report
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android get report and verifies the report
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hogpGetReportTest() throws Exception {

        // Keyboard report
        byte id = KEYBD_RPT_ID;
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, id, (int) 0);
        mFutureReportIntent = SettableFuture.create();
        assertThat(mFutureReportIntent.get()).isEqualTo(KEYBD_RPT_SIZE);
        assertThat(mReportId).isEqualTo(KEYBD_RPT_ID);

        // Mouse report
        id = MOUSE_RPT_ID;
        mHidService.getReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, id, (int) 0);
        mFutureReportIntent = SettableFuture.create();
        assertThat(mFutureReportIntent.get()).isEqualTo(MOUSE_RPT_SIZE);
        assertThat(mReportId).isEqualTo(MOUSE_RPT_ID);
    }

    /**
     * Test Get Protocol mode
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Gets the Protocol mode and verifies the mode
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hogpGetProtocolModeTest() throws Exception {
        mHidService.getProtocolMode(mDevice);
        mFutureProtocolModeIntent = SettableFuture.create();
        assertThat(mFutureProtocolModeIntent.get())
                .isEqualTo(BluetoothHidHost.PROTOCOL_REPORT_MODE);
    }

    /**
     * Test Set Protocol mode
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Sets the Protocol mode and verifies the mode
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hogpSetProtocolModeTest() throws Exception {
        mHidService.setProtocolMode(mDevice, BluetoothHidHost.PROTOCOL_BOOT_MODE);
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get()).isEqualTo(BluetoothHidDevice.ERROR_RSP_SUCCESS);
    }

    /**
     * Test Set Report
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Set report and verifies the report
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hogpSetReportTest() throws Exception {
        // Keyboard report
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, "010203040506070809");
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get()).isEqualTo(BluetoothHidDevice.ERROR_RSP_SUCCESS);
        // Mouse report
        mHidService.setReport(mDevice, BluetoothHidHost.REPORT_TYPE_INPUT, "02030405");
        mFutureHandShakeIntent = SettableFuture.create();
        assertThat(mFutureHandShakeIntent.get()).isEqualTo(BluetoothHidDevice.ERROR_RSP_SUCCESS);
    }

    /**
     * Test Virtual Unplug from Hid Host
     *
     * <ol>
     *   <li>1. Android creates bonding and connect the HID Device
     *   <li>2. Android Virtual Unplug and verifies Bonding
     * </ol>
     */
    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ALLOW_SWITCHING_HID_AND_HOGP,
        Flags.FLAG_SAVE_INITIAL_HID_CONNECTION_POLICY
    })
    public void hogpVirtualUnplugFromHidHostTest() throws Exception {
        mHidService.virtualUnplug(mDevice);
        mFutureBondIntent = SettableFuture.create();
        assertThat(mFutureBondIntent.get()).isEqualTo(BluetoothDevice.BOND_NONE);
    }
}
