/*
 * Copyright 2018 The Android Open Source Project
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

import static android.bluetooth.BluetoothUtils.getSyncTimeout;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothAdminPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import com.android.modules.utils.SynchronousResultReceiver;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * This class provides the public APIs to control the Hearing Aid profile.
 *
 * <p>BluetoothHearingAid is a proxy object for controlling the Bluetooth Hearing Aid
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothHearingAid proxy object.
 *
 * <p> Android only supports one set of connected Bluetooth Hearing Aid device at a time. Each
 * method is protected with its appropriate permission.
 */
public final class BluetoothHearingAid implements BluetoothProfile {
    private static final String TAG = "BluetoothHearingAid";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /**
     * This class provides the APIs to get device's advertisement data. The advertisement data might
     * be incomplete or not available.
     *
     * <p><a
     * href=https://source.android.com/docs/core/connect/bluetooth/asha#advertisements-for-asha-gatt-service>
     * documentation can be found here</a>
     *
     * @hide
     */
    @SystemApi
    public static final class AdvertisementServiceData implements Parcelable {
        private static final String TAG = "AdvertisementData";

        private final int mCapability;
        private final int mTruncatedHiSyncId;

        /**
         * Construct AdvertisementServiceData.
         *
         * @param capability hearing aid's capability
         * @param truncatedHiSyncId truncated HiSyncId
         * @hide
         */
        public AdvertisementServiceData(int capability, int truncatedHiSyncId) {
            if (DBG) {
                Log.d(TAG, "capability:" + capability + " truncatedHiSyncId:" + truncatedHiSyncId);
            }
            mCapability = capability;
            mTruncatedHiSyncId = truncatedHiSyncId;
        }

        /**
         * Get the mode of the device based on its advertisement data.
         *
         * @hide
         */
        @RequiresPermission(
                allOf = {
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                })
        @SystemApi
        @DeviceMode
        public int getDeviceMode() {
            if (VDBG) log("getDeviceMode()");
            return (mCapability >> 1) & 1;
        }

        private AdvertisementServiceData(@NonNull Parcel in) {
            mCapability = in.readInt();
            mTruncatedHiSyncId = in.readInt();
        }

        /**
         * Get the side of the device based on its advertisement data.
         *
         * @hide
         */
        @RequiresPermission(
                allOf = {
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                })
        @SystemApi
        @DeviceSide
        public int getDeviceSide() {
            if (VDBG) log("getDeviceSide()");
            return mCapability & 1;
        }

        /**
         * Check if {@link BluetoothHearingAid} marks itself as CSIP supported based on its
         * advertisement data.
         *
         * @return {@code true} when CSIP is supported, {@code false} otherwise
         * @hide
         */
        @RequiresPermission(
                allOf = {
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                })
        @SystemApi
        public boolean isCsipSupported() {
            if (VDBG) log("isCsipSupported()");
            return ((mCapability >> 2) & 1) != 0;
        }

        /**
         * Get the truncated HiSyncId of the device based on its advertisement data.
         *
         * @hide
         */
        @RequiresPermission(
                allOf = {
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                })
        @SystemApi
        public int getTruncatedHiSyncId() {
            if (VDBG) log("getTruncatedHiSyncId: " + mTruncatedHiSyncId);
            return mTruncatedHiSyncId;
        }

        /**
         * Check if another {@link AdvertisementServiceData} is likely a pair with current one.
         * There is a possibility of a collision on truncated HiSyncId which leads to falsely
         * identified as a pair.
         *
         * @param data another device's {@link AdvertisementServiceData}
         * @return {@code true} if the devices are a likely pair, {@code false} otherwise
         * @hide
         */
        @RequiresPermission(
                allOf = {
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                })
        @SystemApi
        public boolean isInPairWith(@Nullable AdvertisementServiceData data) {
            if (VDBG) log("isInPairWith()");
            if (data == null) {
                return false;
            }

            boolean bothSupportCsip = isCsipSupported() && data.isCsipSupported();
            boolean isDifferentSide =
                    (getDeviceSide() != SIDE_UNKNOWN && data.getDeviceSide() != SIDE_UNKNOWN)
                            && (getDeviceSide() != data.getDeviceSide());
            boolean isSameTruncatedHiSyncId = mTruncatedHiSyncId == data.mTruncatedHiSyncId;
            return bothSupportCsip && isDifferentSide && isSameTruncatedHiSyncId;
        }

        /**
         * @hide
         */
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mCapability);
            dest.writeInt(mTruncatedHiSyncId);
        }

        public static final @NonNull Parcelable.Creator<AdvertisementServiceData> CREATOR =
                new Parcelable.Creator<AdvertisementServiceData>() {
                    public AdvertisementServiceData createFromParcel(Parcel in) {
                        return new AdvertisementServiceData(in);
                    }

                    public AdvertisementServiceData[] newArray(int size) {
                        return new AdvertisementServiceData[size];
                    }
                };

    }

    /**
     * Intent used to broadcast the change in connection state of the Hearing Aid profile. Please
     * note that in the binaural case, there will be two different LE devices for the left and right
     * side and each device will have their own connection state changes.S
     *
     * <p>This intent will have 3 extras:
     *
     * <ul>
     *   <li>{@link #EXTRA_STATE} - The current state of the profile.
     *   <li>{@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.
     *   <li>{@link BluetoothDevice#EXTRA_DEVICE} - The remote device.
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of {@link
     * #STATE_DISCONNECTED}, {@link #STATE_CONNECTING}, {@link #STATE_CONNECTED}, {@link
     * #STATE_DISCONNECTING}.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Intent used to broadcast the selection of a connected device as active.
     *
     * <p>This intent will have one extra:
     * <ul>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. It can
     * be null if no device is active. </li>
     * </ul>
     *
     * @hide
     */
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.hearingaid.profile.action.ACTIVE_DEVICE_CHANGED";

    /** @hide */
    @IntDef(prefix = "SIDE_", value = {
            SIDE_UNKNOWN,
            SIDE_LEFT,
            SIDE_RIGHT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceSide {}

    /**
     * Indicates the device side could not be read.
     *
     * @hide
     */
    @SystemApi
    public static final int SIDE_UNKNOWN = -1;

    /**
     * This device represents Left Hearing Aid.
     *
     * @hide
     */
    @SystemApi
    public static final int SIDE_LEFT = IBluetoothHearingAid.SIDE_LEFT;

    /**
     * This device represents Right Hearing Aid.
     *
     * @hide
     */
    @SystemApi
    public static final int SIDE_RIGHT = IBluetoothHearingAid.SIDE_RIGHT;

    /** @hide */
    @IntDef(prefix = "MODE_", value = {
            MODE_UNKNOWN,
            MODE_MONAURAL,
            MODE_BINAURAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceMode {}

    /**
     * Indicates the device mode could not be read.
     *
     * @hide
     */
    @SystemApi
    public static final int MODE_UNKNOWN = -1;

    /**
     * This device is Monaural.
     *
     * @hide
     */
    @SystemApi
    public static final int MODE_MONAURAL = IBluetoothHearingAid.MODE_MONAURAL;

    /**
     * This device is Binaural (should receive only left or right audio).
     *
     * @hide
     */
    @SystemApi
    public static final int MODE_BINAURAL = IBluetoothHearingAid.MODE_BINAURAL;

    /**
     * Indicates the HiSyncID could not be read and is unavailable.
     *
     * @hide
     */
    @SystemApi
    public static final long HI_SYNC_ID_INVALID = 0;

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;

    private IBluetoothHearingAid mService;

    /**
     * Create a BluetoothHearingAid proxy object for interacting with the local
     * Bluetooth Hearing Aid service.
     */
    /* package */ BluetoothHearingAid(Context context, BluetoothAdapter adapter) {
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mService = null;
    }

    /** @hide */
    @Override
    public void onServiceConnected(IBinder service) {
        mService = IBluetoothHearingAid.Stub.asInterface(service);
    }

    /** @hide */
    @Override
    public void onServiceDisconnected() {
        mService = null;
    }

    private IBluetoothHearingAid getService() {
        return mService;
    }

    /** @hide */
    @Override
    public BluetoothAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is already connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that
     * connection state intent for the profile will be broadcasted with
     * the state. Users can get the connection state of the profile
     * from this intent.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        final IBluetoothHearingAid service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
                service.connect(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Initiate disconnection from a profile
     *
     * <p> This API will return false in scenarios like the profile on the
     * Bluetooth device is not in connected state etc. When this API returns,
     * true, it is guaranteed that the connection state change
     * intent will be broadcasted with the state. Users can get the
     * disconnection state of the profile from this intent.
     *
     * <p> If the disconnection is initiated by a remote device, the state
     * will transition from {@link #STATE_CONNECTED} to
     * {@link #STATE_DISCONNECTED}. If the disconnect is initiated by the
     * host (local) device the state will transition from
     * {@link #STATE_CONNECTED} to state {@link #STATE_DISCONNECTING} to
     * state {@link #STATE_DISCONNECTED}. The transition to
     * {@link #STATE_DISCONNECTING} can be used to distinguish between the
     * two scenarios.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        final IBluetoothHearingAid service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
                service.disconnect(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothHearingAid service = getService();
        final List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                        SynchronousResultReceiver.get();
                service.getConnectedDevices(mAttributionSource, recv);
                return Attributable.setAttributionSource(
                        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue),
                        mAttributionSource);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @NonNull
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(@NonNull int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothHearingAid service = getService();
        final List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                        SynchronousResultReceiver.get();
                service.getDevicesMatchingConnectionStates(states, mAttributionSource, recv);
                return Attributable.setAttributionSource(
                        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue),
                        mAttributionSource);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @BluetoothProfile.BtProfileState
    public int getConnectionState(@NonNull BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        final IBluetoothHearingAid service = getService();
        final int defaultValue = BluetoothProfile.STATE_DISCONNECTED;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();
                service.getConnectionState(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Select a connected device as active.
     *
     * The active device selection is per profile. An active device's
     * purpose is profile-specific. For example, Hearing Aid audio
     * streaming is to the active Hearing Aid device. If a remote device
     * is not connected, it cannot be selected as active.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is not connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that the
     * {@link #ACTION_ACTIVE_DEVICE_CHANGED} intent will be broadcasted
     * with the active device.
     *
     * @param device the remote Bluetooth device. Could be null to clear
     * the active device and stop streaming audio to a Bluetooth device.
     * @return false on immediate error, true otherwise
     * @hide
     */
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean setActiveDevice(@Nullable BluetoothDevice device) {
        if (DBG) log("setActiveDevice(" + device + ")");
        final IBluetoothHearingAid service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && ((device == null) || isValidDevice(device))) {
            try {
                final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
                service.setActiveDevice(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Get the connected physical Hearing Aid devices that are active
     *
     * @return the list of active devices. The first element is the left active
     * device; the second element is the right active device. If either or both side
     * is not active, it will be null on that position. Returns empty list on error.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public @NonNull List<BluetoothDevice> getActiveDevices() {
        if (VDBG) log("getActiveDevices()");
        final IBluetoothHearingAid service = getService();
        final List<BluetoothDevice> defaultValue = new ArrayList<>();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                final SynchronousResultReceiver<List<BluetoothDevice>> recv =
                        SynchronousResultReceiver.get();
                service.getActiveDevices(mAttributionSource, recv);
                return Attributable.setAttributionSource(
                        recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue),
                        mAttributionSource);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Set priority of the profile
     *
     * <p> The device should already be paired.
     * Priority can be one of {@link #PRIORITY_ON} or {@link #PRIORITY_OFF},
     *
     * @param device Paired bluetooth device
     * @param priority
     * @return true if priority is set, false on error
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean setPriority(BluetoothDevice device, int priority) {
        if (DBG) log("setPriority(" + device + ", " + priority + ")");
        return setConnectionPolicy(device, BluetoothAdapter.priorityToConnectionPolicy(priority));
    }

    /**
     * Set connection policy of the profile
     *
     * <p> The device should already be paired.
     * Connection policy can be one of {@link #CONNECTION_POLICY_ALLOWED},
     * {@link #CONNECTION_POLICY_FORBIDDEN}, {@link #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public boolean setConnectionPolicy(@NonNull BluetoothDevice device,
            @ConnectionPolicy int connectionPolicy) {
        if (DBG) log("setConnectionPolicy(" + device + ", " + connectionPolicy + ")");
        verifyDeviceNotNull(device, "setConnectionPolicy");
        final IBluetoothHearingAid service = getService();
        final boolean defaultValue = false;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)
                    && (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
                        || connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED)) {
            try {
                final SynchronousResultReceiver<Boolean> recv = SynchronousResultReceiver.get();
                service.setConnectionPolicy(device, connectionPolicy, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Get the priority of the profile.
     *
     * <p> The priority can be any of:
     * {@link #PRIORITY_OFF}, {@link #PRIORITY_ON}, {@link #PRIORITY_UNDEFINED}
     *
     * @param device Bluetooth device
     * @return priority of the device
     * @hide
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public int getPriority(BluetoothDevice device) {
        if (VDBG) log("getPriority(" + device + ")");
        return BluetoothAdapter.connectionPolicyToPriority(getConnectionPolicy(device));
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p> The connection policy can be any of:
     * {@link #CONNECTION_POLICY_ALLOWED}, {@link #CONNECTION_POLICY_FORBIDDEN},
     * {@link #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public @ConnectionPolicy int getConnectionPolicy(@NonNull BluetoothDevice device) {
        if (VDBG) log("getConnectionPolicy(" + device + ")");
        verifyDeviceNotNull(device, "getConnectionPolicy");
        final IBluetoothHearingAid service = getService();
        final int defaultValue = BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();
                service.getConnectionPolicy(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Helper for converting a state to a string.
     *
     * For debug use only - strings are not internationalized.
     *
     * @hide
     */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_DISCONNECTED:
                return "disconnected";
            case STATE_CONNECTING:
                return "connecting";
            case STATE_CONNECTED:
                return "connected";
            case STATE_DISCONNECTING:
                return "disconnecting";
            default:
                return "<unknown state " + state + ">";
        }
    }

    /**
     * Tells remote device to set an absolute volume.
     *
     * @param volume Absolute volume to be set on remote
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public void setVolume(int volume) {
        if (DBG) Log.d(TAG, "setVolume(" + volume + ")");
        final IBluetoothHearingAid service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                final SynchronousResultReceiver recv = SynchronousResultReceiver.get();
                service.setVolume(volume, mAttributionSource, recv);
                recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    /**
     * Get the HiSyncId (unique hearing aid device identifier) of the device.
     *
     * <a href=https://source.android.com/devices/bluetooth/asha#hisyncid>HiSyncId documentation
     * can be found here</a>
     *
     * @param device Bluetooth device
     * @return the HiSyncId of the device
     * @hide
     */
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
    })
    public long getHiSyncId(@NonNull BluetoothDevice device) {
        if (VDBG) log("getHiSyncId(" + device + ")");
        verifyDeviceNotNull(device, "getHiSyncId");
        final IBluetoothHearingAid service = getService();
        final long defaultValue = HI_SYNC_ID_INVALID;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Long> recv = SynchronousResultReceiver.get();
                service.getHiSyncId(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Get the side of the device.
     *
     * @param device Bluetooth device.
     * @return the {@code SIDE_LEFT}, {@code SIDE_RIGHT} of the device, or {@code SIDE_UNKNOWN} if
     *         one is not available.
     * @hide
     */
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @DeviceSide
    public int getDeviceSide(@NonNull BluetoothDevice device) {
        if (VDBG) log("getDeviceSide(" + device + ")");
        verifyDeviceNotNull(device, "getDeviceSide");
        final IBluetoothHearingAid service = getService();
        final int defaultValue = SIDE_UNKNOWN;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();
                service.getDeviceSide(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Get the mode of the device.
     *
     * @param device Bluetooth device
     * @return the {@code MODE_MONAURAL}, {@code MODE_BINAURAL} of the device, or
     *         {@code MODE_UNKNOWN} if one is not available.
     * @hide
     */
    @SystemApi
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @DeviceMode
    public int getDeviceMode(@NonNull  BluetoothDevice device) {
        if (VDBG) log("getDeviceMode(" + device + ")");
        verifyDeviceNotNull(device, "getDeviceMode");
        final IBluetoothHearingAid service = getService();
        final int defaultValue = MODE_UNKNOWN;
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                final SynchronousResultReceiver<Integer> recv = SynchronousResultReceiver.get();
                service.getDeviceMode(device, mAttributionSource, recv);
                return recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(defaultValue);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return defaultValue;
    }

    /**
     * Get ASHA device's advertisement service data.
     *
     * @param device discovered Bluetooth device
     * @return {@link AdvertisementServiceData}
     * @hide
     */
    @SystemApi
    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            })
    public @Nullable AdvertisementServiceData getAdvertisementServiceData(
            @NonNull BluetoothDevice device) {
        if (DBG) {
            log("getAdvertisementServiceData()");
        }
        final IBluetoothHearingAid service = getService();
        AdvertisementServiceData result = null;
        if (service == null || !isEnabled() || !isValidDevice(device)) {
            Log.w(TAG, "Proxy not attached to service");
            if (DBG) {
                log(Log.getStackTraceString(new Throwable()));
            }
        } else {
            try {
                final SynchronousResultReceiver<AdvertisementServiceData> recv =
                        SynchronousResultReceiver.get();
                service.getAdvertisementServiceData(device, mAttributionSource, recv);
                result = recv.awaitResultNoInterrupt(getSyncTimeout()).getValue(null);
            } catch (RemoteException | TimeoutException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return result;
    }

    /**
     * Get the side of the device.
     *
     * <p>TODO(b/231901542): Used by internal only to improve hearing aids experience in short-term.
     * Need to change to formal call in next bluetooth release.
     *
     * @param device Bluetooth device.
     * @return SIDE_LEFT or SIDE_RIGHT
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private int getDeviceSideInternal(BluetoothDevice device) {
        return getDeviceSide(device);
    }

    /**
     * Get the mode of the device.
     *
     * <p>TODO(b/231901542): Used by internal only to improve hearing aids experience in short-term.
     * Need to change to formal call in next bluetooth release.
     *
     * @param device Bluetooth device
     * @return MODE_MONAURAL or MODE_BINAURAL
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private int getDeviceModeInternal(BluetoothDevice device) {
        return getDeviceMode(device);
    }

    private boolean isEnabled() {
        if (mAdapter.getState() == BluetoothAdapter.STATE_ON) return true;
        return false;
    }

    private void verifyDeviceNotNull(BluetoothDevice device, String methodName) {
        if (device == null) {
            Log.e(TAG, methodName + ": device param is null");
            throw new IllegalArgumentException("Device cannot be null");
        }
    }

    private boolean isValidDevice(BluetoothDevice device) {
        if (device == null) return false;

        if (BluetoothAdapter.checkBluetoothAddress(device.getAddress())) return true;
        return false;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
