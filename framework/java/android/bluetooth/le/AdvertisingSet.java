/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.bluetooth.le;

import static android.bluetooth.le.BluetoothLeUtils.getSyncTimeout;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.annotations.RequiresBluetoothAdvertisePermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothAdminPermission;
import android.content.AttributionSource;
import android.os.RemoteException;
import android.util.Log;

import com.android.modules.utils.SynchronousResultReceiver;

import java.util.concurrent.TimeoutException;

/**
 * This class provides a way to control single Bluetooth LE advertising instance.
 * <p>
 * To get an instance of {@link AdvertisingSet}, call the
 * {@link BluetoothLeAdvertiser#startAdvertisingSet} method.
 *
 * @see AdvertiseData
 */
public final class AdvertisingSet {
    private static final String TAG = "AdvertisingSet";

    private final IBluetoothGatt mGatt;
    private int mAdvertiserId;
    private AttributionSource mAttributionSource;

    /* package */ AdvertisingSet(
            int advertiserId,
            BluetoothAdapter bluetoothAdapter,
            AttributionSource attributionSource) {
        mAdvertiserId = advertiserId;
        mAttributionSource = attributionSource;
        mGatt = requireNonNull(bluetoothAdapter.getBluetoothGatt(), "Failed to get Bluetooth gatt");
    }

    /* package */ void setAdvertiserId(int advertiserId) {
        mAdvertiserId = advertiserId;
    }

    /**
     * Enables Advertising. This method returns immediately, the operation status is
     * delivered through {@code callback.onAdvertisingEnabled()}.
     *
     * @param enable whether the advertising should be enabled (true), or disabled (false)
     * @param duration advertising duration, in 10ms unit. Valid range is from 1 (10ms) to 65535
     * (655,350 ms)
     * @param maxExtendedAdvertisingEvents maximum number of extended advertising events the
     * controller shall attempt to send prior to terminating the extended advertising, even if the
     * duration has not expired. Valid range is from 1 to 255.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothAdvertisePermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    public void enableAdvertising(boolean enable, int duration,
            int maxExtendedAdvertisingEvents) {
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            mGatt.enableAdvertisingSet(mAdvertiserId, enable, duration,
                    maxExtendedAdvertisingEvents, mAttributionSource, recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (TimeoutException | RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Set/update data being Advertised. Make sure that data doesn't exceed the size limit for
     * specified AdvertisingSetParameters. This method returns immediately, the operation status is
     * delivered through {@code callback.onAdvertisingDataSet()}.
     * <p>
     * Advertising data must be empty if non-legacy scannable advertising is used.
     *
     * @param advertiseData Advertisement data to be broadcasted. Size must not exceed {@link
     * BluetoothAdapter#getLeMaximumAdvertisingDataLength}. If the advertisement is connectable,
     * three bytes will be added for flags. If the update takes place when the advertising set is
     * enabled, the data can be maximum 251 bytes long.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothAdvertisePermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    public void setAdvertisingData(AdvertiseData advertiseData) {
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            mGatt.setAdvertisingData(mAdvertiserId, advertiseData, mAttributionSource, recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (TimeoutException | RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Set/update scan response data. Make sure that data doesn't exceed the size limit for
     * specified AdvertisingSetParameters. This method returns immediately, the operation status
     * is delivered through {@code callback.onScanResponseDataSet()}.
     *
     * @param scanResponse Scan response associated with the advertisement data. Size must not
     * exceed {@link BluetoothAdapter#getLeMaximumAdvertisingDataLength}. If the update takes place
     * when the advertising set is enabled, the data can be maximum 251 bytes long.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothAdvertisePermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    public void setScanResponseData(AdvertiseData scanResponse) {
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            mGatt.setScanResponseData(mAdvertiserId, scanResponse, mAttributionSource, recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (TimeoutException | RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Update advertising parameters associated with this AdvertisingSet. Must be called when
     * advertising is not active. This method returns immediately, the operation status is delivered
     * through {@code callback.onAdvertisingParametersUpdated}.
     *
     * @param parameters advertising set parameters.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothAdvertisePermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    public void setAdvertisingParameters(AdvertisingSetParameters parameters) {
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            mGatt.setAdvertisingParameters(mAdvertiserId, parameters, mAttributionSource, recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (TimeoutException | RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Update periodic advertising parameters associated with this set. Must be called when
     * periodic advertising is not enabled. This method returns immediately, the operation
     * status is delivered through {@code callback.onPeriodicAdvertisingParametersUpdated()}.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothAdvertisePermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    public void setPeriodicAdvertisingParameters(PeriodicAdvertisingParameters parameters) {
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            mGatt.setPeriodicAdvertisingParameters(mAdvertiserId, parameters, mAttributionSource,
                    recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (TimeoutException | RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Used to set periodic advertising data, must be called after setPeriodicAdvertisingParameters,
     * or after advertising was started with periodic advertising data set. This method returns
     * immediately, the operation status is delivered through
     * {@code callback.onPeriodicAdvertisingDataSet()}.
     *
     * @param periodicData Periodic advertising data. Size must not exceed {@link
     * BluetoothAdapter#getLeMaximumAdvertisingDataLength}. If the update takes place when the
     * periodic advertising is enabled for this set, the data can be maximum 251 bytes long.
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothAdvertisePermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    public void setPeriodicAdvertisingData(AdvertiseData periodicData) {
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            mGatt.setPeriodicAdvertisingData(mAdvertiserId, periodicData, mAttributionSource, recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (TimeoutException | RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Used to enable/disable periodic advertising. This method returns immediately, the operation
     * status is delivered through {@code callback.onPeriodicAdvertisingEnable()}.
     *
     * @param enable whether the periodic advertising should be enabled (true), or disabled
     * (false).
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothAdvertisePermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    public void setPeriodicAdvertisingEnabled(boolean enable) {
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            mGatt.setPeriodicAdvertisingEnable(mAdvertiserId, enable, mAttributionSource, recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (TimeoutException | RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Returns address associated with this advertising set.
     * This method is exposed only for Bluetooth PTS tests, no app or system service
     * should ever use it.
     *
     * @hide
     */
    @RequiresBluetoothAdvertisePermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public void getOwnAddress() {
        try {
            final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
            mGatt.getOwnAddress(mAdvertiserId, mAttributionSource, recv);
            recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
        } catch (TimeoutException | RemoteException e) {
            Log.e(TAG, "remote exception - ", e);
        }
    }

    /**
     * Returns the advertiser ID associated with this advertising set.
     *
     * <p>This corresponds to the advertising set ID used at the HCI layer, in either LE Extended
     * Advertising or Android-specific Multi-Advertising.
     *
     * @hide
     */
    @RequiresNoPermission
    @SystemApi
    public int getAdvertiserId() {
        return mAdvertiserId;
    }
}
