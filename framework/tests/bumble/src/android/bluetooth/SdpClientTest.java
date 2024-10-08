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
import android.os.ParcelUuid;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import pandora.HostProto.ConnectRequest;

import java.util.Arrays;
import java.util.List;

/** Test cases for {@link ServiceDiscoveryManager}. */
@RunWith(AndroidJUnit4.class)
public class SdpClientTest {
    private static final String TAG = "SdpClientTest";

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();

    private SettableFuture<List<ParcelUuid>> mFutureIntent;

    @Rule public final AdoptShellPermissionsRule mPermissionRule = new AdoptShellPermissionsRule();

    @Rule public final PandoraDevice mBumble = new PandoraDevice();

    private BroadcastReceiver mConnectionStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (BluetoothDevice.ACTION_UUID.equals(intent.getAction())) {
                        ParcelUuid[] parcelUuids =
                                intent.getParcelableArrayExtra(
                                        BluetoothDevice.EXTRA_UUID, ParcelUuid.class);
                        if (parcelUuids != null) {
                            mFutureIntent.set(Arrays.asList(parcelUuids));
                        }
                    }
                }
            };

    @Before
    public void setup() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_UUID);
        mContext.registerReceiver(mConnectionStateReceiver, filter);
    }

    @After
    public void tearDown() {
        mContext.unregisterReceiver(mConnectionStateReceiver);
    }

    @Test
    public void remoteConnectServiceDiscoveryTest() throws Exception {
        mFutureIntent = SettableFuture.create();

        String local_addr = mAdapter.getAddress();
        byte[] local_bytes_addr = Utils.addressBytesFromString(local_addr);

        mBumble.hostBlocking()
                .connect(
                        ConnectRequest.newBuilder()
                                .setAddress(ByteString.copyFrom(local_bytes_addr))
                                .build());

        BluetoothDevice device = mBumble.getRemoteDevice();

        assertThat(device.fetchUuidsWithSdp()).isTrue();

        assertThat(mFutureIntent.get())
                .containsAtLeast(
                        BluetoothUuid.HFP,
                        BluetoothUuid.A2DP_SOURCE,
                        BluetoothUuid.A2DP_SINK,
                        BluetoothUuid.AVRCP);
    }
}
