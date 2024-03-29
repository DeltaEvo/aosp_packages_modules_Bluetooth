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

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.invocation.Invocation;

import java.util.Collection;

import pandora.HostProto.AdvertiseRequest;
import pandora.HostProto.AdvertiseResponse;
import pandora.HostProto.OwnAddressType;

@RunWith(AndroidJUnit4.class)
public class GattClientTest {
    private static final String TAG = "GattClientTest";

    @ClassRule public static final AdoptShellPermissionsRule PERM = new AdoptShellPermissionsRule();

    @Rule public final PandoraDevice mBumble = new PandoraDevice();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final BluetoothManager mManager = mContext.getSystemService(BluetoothManager.class);
    private final BluetoothAdapter mAdapter = mManager.getAdapter();
    private final BluetoothLeScanner mLeScanner = mAdapter.getBluetoothLeScanner();

    @Test
    public void directConnectGattAfterClose() throws Exception {
        advertiseWithBumble();

        BluetoothDevice device =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        for (int i = 0; i < 10; i++) {
            BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
            BluetoothGatt gatt = device.connectGatt(mContext, false, gattCallback);
            gatt.close();

            // Save the number of call in the callback to be checked later
            Collection<Invocation> invocations = mockingDetails(gattCallback).getInvocations();
            int numberOfCalls = invocations.size();

            BluetoothGattCallback gattCallback2 = mock(BluetoothGattCallback.class);
            BluetoothGatt gatt2 = device.connectGatt(mContext, false, gattCallback2);
            verify(gattCallback2, timeout(1000))
                    .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_CONNECTED));
            gatt2.close();

            // After reconnecting with the second set of callback, check that nothing happened on
            // the first set of callback
            Collection<Invocation> invocationsAfterSomeTimes =
                    mockingDetails(gattCallback).getInvocations();
            int numberOfCallsAfterSomeTimes = invocationsAfterSomeTimes.size();
            assertThat(numberOfCallsAfterSomeTimes).isEqualTo(numberOfCalls);
        }
    }

    @Test
    public void fullGattClientLifecycle() throws Exception {
        advertiseWithBumble();

        BluetoothDevice device =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        for (int i = 0; i < 10; i++) {
            BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
            BluetoothGatt gatt = device.connectGatt(mContext, false, gattCallback);
            verify(gattCallback, timeout(1000))
                    .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_CONNECTED));

            gatt.disconnect();
            verify(gattCallback, timeout(1000))
                    .onConnectionStateChange(
                            any(), anyInt(), eq(BluetoothProfile.STATE_DISCONNECTED));

            gatt.close();
        }
    }

    @Test
    public void reconnectExistingClient() throws Exception {
        advertiseWithBumble();

        BluetoothDevice device =
                mAdapter.getRemoteLeDevice(
                        Utils.BUMBLE_RANDOM_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);
        BluetoothGattCallback gattCallback = mock(BluetoothGattCallback.class);
        InOrder inOrder = inOrder(gattCallback);

        BluetoothGatt gatt = device.connectGatt(mContext, false, gattCallback);
        inOrder.verify(gattCallback, timeout(1000))
                .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_CONNECTED));

        gatt.disconnect();
        inOrder.verify(gattCallback, timeout(1000))
                .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_DISCONNECTED));

        gatt.connect();
        inOrder.verify(gattCallback, timeout(1000))
                .onConnectionStateChange(any(), anyInt(), eq(BluetoothProfile.STATE_CONNECTED));

        gatt.close();
        verifyNoMoreInteractions(gattCallback);
    }

    private void advertiseWithBumble() {
        AdvertiseRequest request =
                AdvertiseRequest.newBuilder()
                        .setLegacy(true)
                        .setConnectable(true)
                        .setOwnAddressType(OwnAddressType.RANDOM)
                        .build();

        StreamObserverSpliterator<AdvertiseResponse> responseObserver =
                new StreamObserverSpliterator<>();

        mBumble.host().advertise(request, responseObserver);
    }
}
