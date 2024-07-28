/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.vc;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothCsipSetCoordinator;
import android.bluetooth.IBluetoothLeAudio;
import android.bluetooth.IBluetoothVolumeControl;
import android.bluetooth.IBluetoothVolumeControlCallback;
import android.content.AttributionSource;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.SneakyThrow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class VolumeControlService extends ProfileService {
    private static final String TAG = "VolumeControlService";

    // Timeout for state machine thread join, to prevent potential ANR.
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    private static final int LE_AUDIO_MAX_VOL = 255;

    private static VolumeControlService sVolumeControlService;

    private AdapterService mAdapterService;
    private DatabaseManager mDatabaseManager;
    private HandlerThread mStateMachinesThread;
    private Handler mHandler = null;

    @VisibleForTesting
    @GuardedBy("mCallbacks")
    final RemoteCallbackList<IBluetoothVolumeControlCallback> mCallbacks =
            new RemoteCallbackList<>();

    @VisibleForTesting
    static class VolumeControlOffsetDescriptor {
        Map<Integer, Descriptor> mVolumeOffsets;

        private static class Descriptor {
            Descriptor() {
                mValue = 0;
                mLocation = 0;
                mDescription = null;
            }

            int mValue;
            int mLocation;
            String mDescription;
        }
        ;

        VolumeControlOffsetDescriptor() {
            mVolumeOffsets = new HashMap<>();
        }

        int size() {
            return mVolumeOffsets.size();
        }

        void add(int id) {
            Descriptor d = mVolumeOffsets.get(id);
            if (d == null) {
                mVolumeOffsets.put(id, new Descriptor());
            }
        }

        boolean setValue(int id, int value) {
            Descriptor d = mVolumeOffsets.get(id);
            if (d == null) {
                return false;
            }
            d.mValue = value;
            return true;
        }

        int getValue(int id) {
            Descriptor d = mVolumeOffsets.get(id);
            if (d == null) {
                return 0;
            }
            return d.mValue;
        }

        boolean setDescription(int id, String desc) {
            Descriptor d = mVolumeOffsets.get(id);
            if (d == null) {
                return false;
            }
            d.mDescription = desc;
            return true;
        }

        String getDescription(int id) {
            Descriptor d = mVolumeOffsets.get(id);
            if (d == null) {
                return null;
            }
            return d.mDescription;
        }

        boolean setLocation(int id, int location) {
            Descriptor d = mVolumeOffsets.get(id);
            if (d == null) {
                return false;
            }
            d.mLocation = location;
            return true;
        }

        int getLocation(int id) {
            Descriptor d = mVolumeOffsets.get(id);
            if (d == null) {
                return 0;
            }
            return d.mLocation;
        }

        void remove(int id) {
            mVolumeOffsets.remove(id);
        }

        void clear() {
            mVolumeOffsets.clear();
        }

        void dump(StringBuilder sb) {
            for (Map.Entry<Integer, Descriptor> entry : mVolumeOffsets.entrySet()) {
                Descriptor descriptor = entry.getValue();
                Integer id = entry.getKey();
                ProfileService.println(sb, "        Id: " + id);
                ProfileService.println(sb, "        value: " + descriptor.mValue);
                ProfileService.println(sb, "        location: " + descriptor.mLocation);
                ProfileService.println(sb, "        description: " + descriptor.mDescription);
            }
        }
    }

    VolumeControlNativeInterface mVolumeControlNativeInterface;
    @VisibleForTesting AudioManager mAudioManager;

    private final Map<BluetoothDevice, VolumeControlStateMachine> mStateMachines = new HashMap<>();
    private final Map<BluetoothDevice, VolumeControlOffsetDescriptor> mAudioOffsets =
            new HashMap<>();
    private final Map<Integer, Integer> mGroupVolumeCache = new HashMap<>();
    private final Map<Integer, Boolean> mGroupMuteCache = new HashMap<>();
    private final Map<BluetoothDevice, Integer> mDeviceVolumeCache = new HashMap<>();

    @VisibleForTesting ServiceFactory mFactory = new ServiceFactory();

    public VolumeControlService(Context ctx) {
        super(ctx);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileVcpControllerEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothVolumeControlBinder(this);
    }

    @Override
    public void start() {
        Log.d(TAG, "start()");
        if (sVolumeControlService != null) {
            throw new IllegalStateException("start() called twice");
        }

        // Get AdapterService, VolumeControlNativeInterface, DatabaseManager, AudioManager.
        // None of them can be null.
        mAdapterService =
                Objects.requireNonNull(
                        AdapterService.getAdapterService(),
                        "AdapterService cannot be null when VolumeControlService starts");
        mDatabaseManager =
                Objects.requireNonNull(
                        mAdapterService.getDatabase(),
                        "DatabaseManager cannot be null when VolumeControlService starts");
        mVolumeControlNativeInterface =
                Objects.requireNonNull(
                        VolumeControlNativeInterface.getInstance(),
                        "VolumeControlNativeInterface cannot be null when VolumeControlService"
                                + " starts");
        mAudioManager = getSystemService(AudioManager.class);
        Objects.requireNonNull(
                mAudioManager, "AudioManager cannot be null when VolumeControlService starts");

        // Start handler thread for state machines
        mHandler = new Handler(Looper.getMainLooper());
        mStateMachines.clear();
        mStateMachinesThread = new HandlerThread("VolumeControlService.StateMachines");
        mStateMachinesThread.start();

        mAudioOffsets.clear();
        mGroupVolumeCache.clear();
        mGroupMuteCache.clear();
        mDeviceVolumeCache.clear();

        // Mark service as started
        setVolumeControlService(this);

        // Initialize native interface
        mVolumeControlNativeInterface.init();
    }

    @Override
    public void stop() {
        Log.d(TAG, "stop()");
        if (sVolumeControlService == null) {
            Log.w(TAG, "stop() called before start()");
            return;
        }

        // Mark service as stopped
        setVolumeControlService(null);

        // Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (VolumeControlStateMachine sm : mStateMachines.values()) {
                sm.doQuit();
                sm.cleanup();
            }
            mStateMachines.clear();
        }

        if (mStateMachinesThread != null) {
            try {
                mStateMachinesThread.quitSafely();
                mStateMachinesThread.join(SM_THREAD_JOIN_TIMEOUT_MS);
                mStateMachinesThread = null;
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
        }

        // Unregister handler and remove all queued messages.
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }

        // Cleanup native interface
        mVolumeControlNativeInterface.cleanup();
        mVolumeControlNativeInterface = null;

        mAudioOffsets.clear();
        mGroupVolumeCache.clear();
        mGroupMuteCache.clear();
        mDeviceVolumeCache.clear();

        // Clear AdapterService, VolumeControlNativeInterface
        mAudioManager = null;
        mVolumeControlNativeInterface = null;
        mAdapterService = null;

        if (mCallbacks != null) {
            mCallbacks.kill();
        }
    }

    @Override
    public void cleanup() {
        Log.d(TAG, "cleanup()");
    }

    /**
     * Get the VolumeControlService instance
     *
     * @return VolumeControlService instance
     */
    public static synchronized VolumeControlService getVolumeControlService() {
        if (sVolumeControlService == null) {
            Log.w(TAG, "getVolumeControlService(): service is NULL");
            return null;
        }

        if (!sVolumeControlService.isAvailable()) {
            Log.w(TAG, "getVolumeControlService(): service is not available");
            return null;
        }
        return sVolumeControlService;
    }

    @VisibleForTesting
    static synchronized void setVolumeControlService(VolumeControlService instance) {
        Log.d(TAG, "setVolumeControlService(): set to: " + instance);
        sVolumeControlService = instance;
    }

    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        if (device == null) {
            return false;
        }

        if (getConnectionPolicy(device) == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            return false;
        }
        final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
        if (!Utils.arrayContains(featureUuids, BluetoothUuid.VOLUME_CONTROL)) {
            Log.e(
                    TAG,
                    "Cannot connect to " + device + " : Remote does not have Volume Control UUID");
            return false;
        }

        synchronized (mStateMachines) {
            VolumeControlStateMachine smConnect = getOrCreateStateMachine(device);
            if (smConnect == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
            }
            smConnect.sendMessage(VolumeControlStateMachine.CONNECT);
        }

        return true;
    }

    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            return false;
        }
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = getOrCreateStateMachine(device);
            if (sm != null) {
                sm.sendMessage(VolumeControlStateMachine.DISCONNECT);
            }
        }

        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (VolumeControlStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        }
    }

    /**
     * Check whether can connect to a peer device. The check considers a number of factors during
     * the evaluation.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean okToConnect(BluetoothDevice device) {
        /* Make sure device is valid */
        if (device == null) {
            Log.e(TAG, "okToConnect: Invalid device");
            return false;
        }
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled()) {
            Log.e(TAG, "okToConnect: cannot connect to " + device + " : quiet mode enabled");
            return false;
        }
        // Check connectionPolicy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        int bondState = mAdapterService.getBondState(device);
        // Allow this connection only if the device is bonded. Any attempt to connect while
        // bonding would potentially lead to an unauthorized connection.
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "okToConnect: return false, bondState=" + bondState);
            return false;
        } else if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(TAG, "okToConnect: return false, connectionPolicy=" + connectionPolicy);
            return false;
        }
        return true;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.VOLUME_CONTROL)) {
                    continue;
                }
                int connectionState = BluetoothProfile.STATE_DISCONNECTED;
                VolumeControlStateMachine sm = mStateMachines.get(device);
                if (sm != null) {
                    connectionState = sm.getConnectionState();
                }
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
            return devices;
        }
    }

    /**
     * Get the list of devices that have state machines.
     *
     * @return the list of devices that have state machines
     */
    @VisibleForTesting
    List<BluetoothDevice> getDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (VolumeControlStateMachine sm : mStateMachines.values()) {
                devices.add(sm.getDevice());
            }
            return devices;
        }
    }

    public int getConnectionState(BluetoothDevice device) {
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        }
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p>The device should already be paired. Connection policy can be one of: {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED}, {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device the remote device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true on success, otherwise false
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.VOLUME_CONTROL, connectionPolicy);
        if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.VOLUME_CONTROL);
    }

    boolean isVolumeOffsetAvailable(BluetoothDevice device) {
        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.i(TAG, " There is no offset service for device: " + device);
            return false;
        }
        Log.i(TAG, " Offset service available for device: " + device);
        return true;
    }

    int getNumberOfVolumeOffsetInstances(BluetoothDevice device) {
        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.i(TAG, " There is no offset service for device: " + device);
            return 0;
        }

        int numberOfInstances = offsets.size();

        Log.i(TAG, "Number of VOCS: " + numberOfInstances + ", for device: " + device);
        return numberOfInstances;
    }

    void setVolumeOffset(BluetoothDevice device, int instanceId, int volumeOffset) {
        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.e(TAG, " There is no offset service for device: " + device);
            return;
        }

        int numberOfInstances = offsets.size();
        if (instanceId > numberOfInstances) {
            Log.e(
                    TAG,
                    "Selected VOCS instance ID: "
                            + instanceId
                            + ", exceed available IDs: "
                            + numberOfInstances
                            + ", for device: "
                            + device);
            return;
        }

        int value = offsets.getValue(instanceId);
        if (value == volumeOffset) {
            /* Nothing to do - offset already applied */
            return;
        }

        mVolumeControlNativeInterface.setExtAudioOutVolumeOffset(device, instanceId, volumeOffset);
    }

    void setDeviceVolume(BluetoothDevice device, int volume, boolean isGroupOp) {
        if (!Flags.leaudioBroadcastVolumeControlForConnectedDevices()) {
            return;
        }
        Log.d(
                TAG,
                "setDeviceVolume: " + device + ", volume: " + volume + ", isGroupOp: " + isGroupOp);

        LeAudioService leAudioService = mFactory.getLeAudioService();
        if (leAudioService == null) {
            Log.e(TAG, "leAudioService not available");
            return;
        }
        int groupId = leAudioService.getGroupId(device);
        if (groupId == IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID) {
            Log.e(TAG, "Device not a part of a group");
            return;
        }

        if (isGroupOp) {
            setGroupVolume(groupId, volume);
        } else {
            Log.i(TAG, "Setting individual device volume");
            mDeviceVolumeCache.put(device, volume);
            mVolumeControlNativeInterface.setVolume(device, volume);
        }
    }

    public void setGroupVolume(int groupId, int volume) {
        if (volume < 0) {
            Log.w(TAG, "Tried to set invalid volume " + volume + ". Ignored.");
            return;
        }

        mGroupVolumeCache.put(groupId, volume);
        mVolumeControlNativeInterface.setGroupVolume(groupId, volume);

        // We only receive the volume change and mute state needs to be acquired manually
        Boolean isGroupMute = mGroupMuteCache.getOrDefault(groupId, false);
        Boolean isStreamMute = mAudioManager.isStreamMute(getBluetoothContextualVolumeStream());

        /* Note: AudioService keeps volume levels for each stream and for each device type,
         * however it stores the mute state only for the stream type but not for each individual
         * device type. When active device changes, it's volume level gets aplied, but mute state
         * is not, but can be either derived from the volume level or just unmuted like for A2DP.
         * Also setting volume level > 0 to audio system will implicitly unmute the stream.
         * However LeAudio devices can keep their volume level high, while keeping it mute so we
         * have to explicitly unmute the remote device.
         */
        if (!isGroupMute.equals(isStreamMute)) {
            Log.w(
                    TAG,
                    "Mute state mismatch, stream mute: "
                            + isStreamMute
                            + ", device group mute: "
                            + isGroupMute
                            + ", new volume: "
                            + volume);
            if (isStreamMute) {
                Log.i(TAG, "Mute the group " + groupId);
                muteGroup(groupId);
            }
            if (!isStreamMute && (volume > 0)) {
                Log.i(TAG, "Unmute the group " + groupId);
                unmuteGroup(groupId);
            }
        }
    }

    public int getGroupVolume(int groupId) {
        return mGroupVolumeCache.getOrDefault(
                groupId, IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME);
    }

    /**
     * Get device cached volume.
     *
     * @param device the device
     * @return the cached volume
     */
    public int getDeviceVolume(BluetoothDevice device) {
        return mDeviceVolumeCache.getOrDefault(
                device, IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME);
    }

    /**
     * This should be called by LeAudioService when LE Audio group change it active state.
     *
     * @param groupId the group identifier
     * @param active indicator if group is active or not
     */
    public void setGroupActive(int groupId, boolean active) {
        Log.d(TAG, "setGroupActive: " + groupId + ", active: " + active);
        if (!active) {
            /* For now we don't need to handle group inactivation */
            return;
        }

        int groupVolume = getGroupVolume(groupId);
        Boolean groupMute = getGroupMute(groupId);

        if (groupVolume != IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME) {
            /* Don't need to show volume when activating known device. */
            updateGroupCacheAndAudioSystem(groupId, groupVolume, groupMute, /* showInUI*/ false);
        }
    }

    /**
     * @param groupId the group identifier
     */
    public Boolean getGroupMute(int groupId) {
        return mGroupMuteCache.getOrDefault(groupId, false);
    }

    public void mute(BluetoothDevice device) {
        mVolumeControlNativeInterface.mute(device);
    }

    public void muteGroup(int groupId) {
        mGroupMuteCache.put(groupId, true);
        mVolumeControlNativeInterface.muteGroup(groupId);
    }

    public void unmute(BluetoothDevice device) {
        mVolumeControlNativeInterface.unmute(device);
    }

    public void unmuteGroup(int groupId) {
        mGroupMuteCache.put(groupId, false);
        mVolumeControlNativeInterface.unmuteGroup(groupId);
    }

    void notifyNewCallbackOfKnownVolumeInfo(IBluetoothVolumeControlCallback callback) {
        Log.d(TAG, "notifyNewCallbackOfKnownVolumeInfo");

        // notify volume offset
        for (Map.Entry<BluetoothDevice, VolumeControlOffsetDescriptor> entry :
                mAudioOffsets.entrySet()) {
            VolumeControlOffsetDescriptor descriptor = entry.getValue();

            for (int id = 1; id <= descriptor.size(); id++) {
                BluetoothDevice device = entry.getKey();
                int offset = descriptor.getValue(id);
                int location = descriptor.getLocation(id);
                String description = descriptor.getDescription(id);

                Log.d(
                        TAG,
                        "notifyNewCallbackOfKnownVolumeInfo,"
                                + (" device: " + device)
                                + (", id: " + id)
                                + (", offset: " + offset)
                                + (", location: " + location)
                                + (", description: " + description));
                try {
                    callback.onVolumeOffsetChanged(device, id, offset);
                    if (Flags.leaudioMultipleVocsInstancesApi()) {
                        callback.onVolumeOffsetAudioLocationChanged(device, id, location);
                        callback.onVolumeOffsetAudioDescriptionChanged(device, id, description);
                    }
                } catch (RemoteException e) {
                    // Dead client -- continue
                }
            }
        }

        if (Flags.leaudioBroadcastVolumeControlForConnectedDevices()) {
            // using tempCallbackList is a hack to keep using 'notifyDevicesVolumeChanged'
            // without making any extra modification
            RemoteCallbackList<IBluetoothVolumeControlCallback> tempCallbackList =
                    new RemoteCallbackList<>();

            tempCallbackList.register(callback);
            notifyDevicesVolumeChanged(tempCallbackList, getDevices(), Optional.empty());
            tempCallbackList.unregister(callback);
        }
    }

    void registerCallback(IBluetoothVolumeControlCallback callback) {
        Log.d(TAG, "registerCallback: " + callback);

        synchronized (mCallbacks) {
            /* Here we keep all the user callbacks */
            mCallbacks.register(callback);
        }

        notifyNewCallbackOfKnownVolumeInfo(callback);
    }

    void unregisterCallback(IBluetoothVolumeControlCallback callback) {
        Log.d(TAG, "unregisterCallback: " + callback);

        synchronized (mCallbacks) {
            mCallbacks.unregister(callback);
        }
    }

    void notifyNewRegisteredCallback(IBluetoothVolumeControlCallback callback) {
        Log.d(TAG, "notifyNewRegisteredCallback: " + callback);
        notifyNewCallbackOfKnownVolumeInfo(callback);
    }

    public void handleGroupNodeAdded(int groupId, BluetoothDevice device) {
        // Ignore disconnected device, its volume will be set once it connects
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != BluetoothProfile.STATE_CONNECTED) {
                return;
            }
        }

        // Correct the volume level only if device was already reported as connected.
        boolean can_change_volume = false;
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                can_change_volume = (sm.getConnectionState() == BluetoothProfile.STATE_CONNECTED);
            }
        }

        // If group volume has already changed, the new group member should set it
        if (can_change_volume) {
            Integer groupVolume =
                    mGroupVolumeCache.getOrDefault(
                            groupId, IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME);
            if (groupVolume != IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME) {
                Log.i(TAG, "Setting value:" + groupVolume + " to " + device);
                mVolumeControlNativeInterface.setVolume(device, groupVolume);
            }

            Boolean isGroupMuted = mGroupMuteCache.getOrDefault(groupId, false);
            Log.i(TAG, "Setting mute:" + isGroupMuted + " to " + device);
            if (isGroupMuted) {
                mVolumeControlNativeInterface.mute(device);
            } else {
                mVolumeControlNativeInterface.unmute(device);
            }
        }
    }

    void updateGroupCacheAndAudioSystem(int groupId, int volume, boolean mute, boolean showInUI) {
        Log.d(
                TAG,
                " updateGroupCacheAndAudioSystem: groupId: "
                        + groupId
                        + ", vol: "
                        + volume
                        + ", mute: "
                        + mute
                        + ", showInUI"
                        + showInUI);

        mGroupVolumeCache.put(groupId, volume);
        mGroupMuteCache.put(groupId, mute);

        if (Flags.leaudioBroadcastVolumeControlForConnectedDevices()) {
            LeAudioService leAudioService = mFactory.getLeAudioService();
            if (leAudioService != null) {
                int currentlyActiveGroupId = leAudioService.getActiveGroupId();
                if (currentlyActiveGroupId == IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID
                        || groupId != currentlyActiveGroupId) {
                    Log.i(
                            TAG,
                            "Skip updating to audio system if not updating volume for current"
                                    + " active group");
                    return;
                }
            } else {
                Log.w(TAG, "leAudioService not available");
            }
        }

        int streamType = getBluetoothContextualVolumeStream();
        int flags = AudioManager.FLAG_BLUETOOTH_ABS_VOLUME;
        if (showInUI) {
            flags |= AudioManager.FLAG_SHOW_UI;
        }

        mAudioManager.setStreamVolume(streamType, getAudioDeviceVolume(streamType, volume), flags);

        if (mAudioManager.isStreamMute(streamType) != mute) {
            int adjustment = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            mAudioManager.adjustStreamVolume(streamType, adjustment, flags);
        }
    }

    void handleVolumeControlChanged(
            BluetoothDevice device, int groupId, int volume, boolean mute, boolean isAutonomous) {

        if (isAutonomous && device != null) {
            Log.e(TAG, "We expect only group notification for autonomous updates");
            return;
        }

        if (groupId == IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID) {
            LeAudioService leAudioService = mFactory.getLeAudioService();
            if (leAudioService == null) {
                Log.e(TAG, "leAudioService not available");
                return;
            }
            groupId = leAudioService.getGroupId(device);
        }

        if (groupId == IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID) {
            Log.e(TAG, "Device not a part of the group");
            return;
        }

        int groupVolume = getGroupVolume(groupId);
        Boolean groupMute = getGroupMute(groupId);

        if (Flags.leaudioBroadcastVolumeControlForConnectedDevices()) {
            Log.i(TAG, "handleVolumeControlChanged: " + device + "; volume: " + volume);
            if (device == null) {
                // notify group devices volume changed
                LeAudioService leAudioService = mFactory.getLeAudioService();
                if (leAudioService != null) {
                    notifyDevicesVolumeChanged(
                            mCallbacks,
                            leAudioService.getGroupDevices(groupId),
                            Optional.of(volume));
                } else {
                    Log.w(TAG, "leAudioService not available");
                }
            } else {
                // notify device volume changed
                notifyDevicesVolumeChanged(mCallbacks, Arrays.asList(device), Optional.of(volume));
            }
        }

        if (groupVolume == IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME) {
            /* We are here, because system was just started and LeAudio device just connected.
             * In such case, we take Volume stored on remote device and apply it to our cache and
             * audio system.
             * Note, to match BR/EDR behavior, don't show volume change in UI here
             */
            updateGroupCacheAndAudioSystem(groupId, volume, mute, false);
            return;
        }

        if (!isAutonomous) {
            /* If the change is triggered by Android device, the stream is already changed.
             * However it might be called with isAutonomous, one the first read of after
             * reconnection. Make sure device has group volume. Also it might happen that
             * remote side send us wrong value - lets check it.
             */

            if ((groupVolume == volume) && (groupMute == mute)) {
                Log.i(TAG, " Volume:" + volume + ", mute:" + mute + " confirmed by remote side.");
                return;
            }

            if (device != null) {
                // Correct the volume level only if device was already reported as connected.
                boolean can_change_volume = false;
                synchronized (mStateMachines) {
                    VolumeControlStateMachine sm = mStateMachines.get(device);
                    if (sm != null) {
                        can_change_volume =
                                (sm.getConnectionState() == BluetoothProfile.STATE_CONNECTED);
                    }
                }

                if (can_change_volume && (groupVolume != volume)) {
                    Log.i(TAG, "Setting value:" + groupVolume + " to " + device);
                    mVolumeControlNativeInterface.setVolume(device, groupVolume);
                }
                if (can_change_volume && (groupMute != mute)) {
                    Log.i(TAG, "Setting mute:" + groupMute + " to " + device);
                    if (groupMute) {
                        mVolumeControlNativeInterface.mute(device);
                    } else {
                        mVolumeControlNativeInterface.unmute(device);
                    }
                }
            } else {
                Log.e(
                        TAG,
                        "Volume changed did not succeed. Volume: "
                                + volume
                                + " expected volume: "
                                + groupVolume);
            }
        } else {
            /* Received group notification for autonomous change. Update cache and audio system. */
            updateGroupCacheAndAudioSystem(groupId, volume, mute, true);
        }
    }

    public int getAudioDeviceGroupVolume(int groupId) {
        int volume = getGroupVolume(groupId);
        if (getGroupMute(groupId)) {
            Log.w(
                    TAG,
                    "Volume level is "
                            + volume
                            + ", but muted. Will report 0 for the audio device.");
            volume = 0;
        }

        if (volume == IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME) return -1;
        return getAudioDeviceVolume(getBluetoothContextualVolumeStream(), volume);
    }

    int getAudioDeviceVolume(int streamType, int bleVolume) {
        int deviceMaxVolume = mAudioManager.getStreamMaxVolume(streamType);

        // TODO: Investigate what happens in classic BT when BT volume is changed to zero.
        double deviceVolume = (double) (bleVolume * deviceMaxVolume) / LE_AUDIO_MAX_VOL;
        return (int) Math.round(deviceVolume);
    }

    // Copied from AudioService.getBluetoothContextualVolumeStream() and modified it.
    int getBluetoothContextualVolumeStream() {
        int mode = mAudioManager.getMode();

        Log.d(TAG, "Volume mode: " + mode + "0: normal, 1: ring, 2,3: call");

        switch (mode) {
            case AudioManager.MODE_IN_COMMUNICATION:
            case AudioManager.MODE_IN_CALL:
                return AudioManager.STREAM_VOICE_CALL;
            case AudioManager.MODE_RINGTONE:
                Log.d(TAG, " Update during ringtone applied to voice call");
                return AudioManager.STREAM_VOICE_CALL;
            case AudioManager.MODE_NORMAL:
            default:
                // other conditions will influence the stream type choice, read on...
                break;
        }
        return AudioManager.STREAM_MUSIC;
    }

    void handleDeviceAvailable(BluetoothDevice device, int numberOfExternalOutputs) {
        if (numberOfExternalOutputs == 0) {
            Log.i(TAG, "Volume offset not available");
            return;
        }

        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            offsets = new VolumeControlOffsetDescriptor();
            mAudioOffsets.put(device, offsets);
        } else if (offsets.size() != numberOfExternalOutputs) {
            Log.i(TAG, "Number of offset changed: ");
            offsets.clear();
        }

        /* Stack delivers us number of audio outputs.
         * Offset ids a countinous from 1 to number_of_ext_outputs*/
        for (int i = 1; i <= numberOfExternalOutputs; i++) {
            offsets.add(i);
            mVolumeControlNativeInterface.getExtAudioOutVolumeOffset(device, i);
            mVolumeControlNativeInterface.getExtAudioOutLocation(device, i);
            mVolumeControlNativeInterface.getExtAudioOutDescription(device, i);
        }
    }

    void handleDeviceExtAudioOffsetChanged(BluetoothDevice device, int id, int value) {
        Log.d(TAG, " device: " + device + " offset_id: " + id + " value: " + value);
        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.e(TAG, " Offsets not found for device: " + device);
            return;
        }
        offsets.setValue(id, value);

        synchronized (mCallbacks) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onVolumeOffsetChanged(device, id, value);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    void handleDeviceExtAudioLocationChanged(BluetoothDevice device, int id, int location) {
        Log.d(TAG, " device: " + device + " offset_id: " + id + " location: " + location);

        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.e(TAG, " Offsets not found for device: " + device);
            return;
        }
        offsets.setLocation(id, location);

        if (Flags.leaudioMultipleVocsInstancesApi()) {
            synchronized (mCallbacks) {
                int n = mCallbacks.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    try {
                        mCallbacks
                                .getBroadcastItem(i)
                                .onVolumeOffsetAudioLocationChanged(device, id, location);
                    } catch (RemoteException e) {
                        continue;
                    }
                }
                mCallbacks.finishBroadcast();
            }
        }
    }

    void handleDeviceExtAudioDescriptionChanged(
            BluetoothDevice device, int id, String description) {
        Log.d(TAG, " device: " + device + " offset_id: " + id + " description: " + description);

        VolumeControlOffsetDescriptor offsets = mAudioOffsets.get(device);
        if (offsets == null) {
            Log.e(TAG, " Offsets not found for device: " + device);
            return;
        }
        offsets.setDescription(id, description);

        if (Flags.leaudioMultipleVocsInstancesApi()) {
            synchronized (mCallbacks) {
                int n = mCallbacks.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    try {
                        mCallbacks
                                .getBroadcastItem(i)
                                .onVolumeOffsetAudioDescriptionChanged(device, id, description);
                    } catch (RemoteException e) {
                        continue;
                    }
                }
                mCallbacks.finishBroadcast();
            }
        }
    }

    void messageFromNative(VolumeControlStackEvent stackEvent) {
        Log.d(TAG, "messageFromNative: " + stackEvent);

        if (stackEvent.type == VolumeControlStackEvent.EVENT_TYPE_VOLUME_STATE_CHANGED) {
            handleVolumeControlChanged(
                    stackEvent.device,
                    stackEvent.valueInt1,
                    stackEvent.valueInt2,
                    stackEvent.valueBool1,
                    stackEvent.valueBool2);
            return;
        }

        Objects.requireNonNull(
                stackEvent.device, "Device should never be null, event: " + stackEvent);

        BluetoothDevice device = stackEvent.device;
        if (stackEvent.type == VolumeControlStackEvent.EVENT_TYPE_DEVICE_AVAILABLE) {
            handleDeviceAvailable(device, stackEvent.valueInt1);
            return;
        }

        if (stackEvent.type
                == VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED) {
            handleDeviceExtAudioOffsetChanged(device, stackEvent.valueInt1, stackEvent.valueInt2);
            return;
        }

        if (stackEvent.type == VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED) {
            handleDeviceExtAudioLocationChanged(device, stackEvent.valueInt1, stackEvent.valueInt2);
            return;
        }

        if (stackEvent.type
                == VolumeControlStackEvent.EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED) {
            handleDeviceExtAudioDescriptionChanged(
                    device, stackEvent.valueInt1, stackEvent.valueString1);
            return;
        }

        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                if (stackEvent.type
                        == VolumeControlStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                    switch (stackEvent.valueInt1) {
                        case VolumeControlStackEvent.CONNECTION_STATE_CONNECTED:
                        case VolumeControlStackEvent.CONNECTION_STATE_CONNECTING:
                            sm = getOrCreateStateMachine(device);
                            break;
                        default:
                            break;
                    }
                }
            }
            if (sm == null) {
                Log.e(TAG, "Cannot process stack event: no state machine: " + stackEvent);
                return;
            }
            sm.sendMessage(VolumeControlStateMachine.STACK_EVENT, stackEvent);
        }
    }

    private VolumeControlStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }

            Log.d(TAG, "Creating a new state machine for " + device);
            sm =
                    VolumeControlStateMachine.make(
                            device,
                            this,
                            mVolumeControlNativeInterface,
                            mStateMachinesThread.getLooper());
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    /**
     * Notify devices with volume level
     *
     * <p>In case of handleVolumeControlChanged, volume level is known from native layer caller.
     * Notify the clients with the volume level directly and update the volume cache. In case of
     * newly registered callback, volume level is unknown from caller, notify the clients with
     * cached volume level from either device or group.
     *
     * @param callbacks list of callbacks
     * @param devices list of devices to notify volume changed
     * @param volume volume level
     */
    private void notifyDevicesVolumeChanged(
            RemoteCallbackList<IBluetoothVolumeControlCallback> callbacks,
            List<BluetoothDevice> devices,
            Optional<Integer> volume) {
        if (callbacks == null) {
            Log.e(TAG, "callbacks is null");
            return;
        }

        LeAudioService leAudioService = mFactory.getLeAudioService();
        if (leAudioService == null) {
            Log.e(TAG, "leAudioService not available");
            return;
        }

        for (BluetoothDevice dev : devices) {
            int cachedVolume = IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;
            if (!volume.isPresent()) {
                int groupId = leAudioService.getGroupId(dev);
                if (groupId == IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID) {
                    Log.e(TAG, "Device not a part of a group");
                    continue;
                }
                // if device volume is available, notify with device volume, otherwise group volume
                cachedVolume = getDeviceVolume(dev);
                if (cachedVolume == IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME) {
                    cachedVolume = getGroupVolume(groupId);
                }
            }
            int broadcastVolume = cachedVolume;
            if (volume.isPresent()) {
                broadcastVolume = volume.get();
                mDeviceVolumeCache.put(dev, broadcastVolume);
            }
            int n = callbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    callbacks.getBroadcastItem(i).onDeviceVolumeChanged(dev, broadcastVolume);
                } catch (RemoteException e) {
                    continue;
                }
            }
            callbacks.finishBroadcast();
        }
    }

    /** Process a change in the bonding state for a device */
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    /**
     * Remove state machine if the bonding for a device is removed
     *
     * @param device the device whose bonding state has changed
     * @param bondState the new bond state for the device. Possible values are: {@link
     *     BluetoothDevice#BOND_NONE}, {@link BluetoothDevice#BOND_BONDING}, {@link
     *     BluetoothDevice#BOND_BONDED}.
     */
    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);
        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }

        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnecting device because it was unbonded.");
                disconnect(device);
                return;
            }
            removeStateMachine(device);
        }
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            VolumeControlStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.w(
                        TAG,
                        "removeStateMachine: device " + device + " does not have a state machine");
                return;
            }
            Log.i(TAG, "removeStateMachine: removing state machine for device: " + device);
            sm.doQuit();
            sm.cleanup();
            mStateMachines.remove(device);
        }
    }

    void handleConnectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> connectionStateChanged(device, fromState, toState));
    }

    @VisibleForTesting
    synchronized void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (!isAvailable()) {
            Log.w(TAG, "connectionStateChanged: service is not available");
            return;
        }

        if ((device == null) || (fromState == toState)) {
            Log.e(
                    TAG,
                    "connectionStateChanged: unexpected invocation. device="
                            + device
                            + " fromState="
                            + fromState
                            + " toState="
                            + toState);
            return;
        }

        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == BluetoothProfile.STATE_DISCONNECTED) {
            int bondState = mAdapterService.getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                Log.d(TAG, device + " is unbond. Remove state machine");
                removeStateMachine(device);
            }
        } else if (toState == BluetoothProfile.STATE_CONNECTED) {
            // Restore the group volume if it was changed while the device was not yet connected.
            CsipSetCoordinatorService csipClient = mFactory.getCsipSetCoordinatorService();
            if (csipClient != null) {
                Integer groupId = csipClient.getGroupId(device, BluetoothUuid.CAP);
                if (groupId != IBluetoothCsipSetCoordinator.CSIS_GROUP_ID_INVALID) {
                    Integer groupVolume =
                            mGroupVolumeCache.getOrDefault(
                                    groupId, IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME);
                    if (groupVolume != IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME) {
                        mVolumeControlNativeInterface.setVolume(device, groupVolume);
                    }

                    Boolean groupMute = mGroupMuteCache.getOrDefault(groupId, false);
                    if (groupMute) {
                        mVolumeControlNativeInterface.mute(device);
                    } else {
                        mVolumeControlNativeInterface.unmute(device);
                    }
                }
            } else {
                /* It could happen when Bluetooth is stopping while VC is getting
                 * connection event
                 */
                Log.w(TAG, "CSIP is not available");
            }
        }
        mAdapterService.handleProfileConnectionStateChange(
                BluetoothProfile.VOLUME_CONTROL, device, fromState, toState);
    }

    /** Binder object: must be a static class or memory leak may occur */
    @VisibleForTesting
    static class BluetoothVolumeControlBinder extends IBluetoothVolumeControl.Stub
            implements IProfileServiceBinder {
        @VisibleForTesting boolean mIsTesting = false;
        private VolumeControlService mService;

        BluetoothVolumeControlBinder(VolumeControlService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        private VolumeControlService getService(AttributionSource source) {
            // Cache mService because it can change while getService is called
            VolumeControlService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            return service;
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getConnectionPolicy(device);
        }

        @Override
        public boolean isVolumeOffsetAvailable(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.isVolumeOffsetAvailable(device);
        }

        @Override
        public int getNumberOfVolumeOffsetInstances(
                BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return 0;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getNumberOfVolumeOffsetInstances(device);
        }

        @Override
        public void setVolumeOffset(
                BluetoothDevice device,
                int instanceId,
                int volumeOffset,
                AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setVolumeOffset(device, instanceId, volumeOffset);
        }

        @Override
        public void setDeviceVolume(
                BluetoothDevice device, int volume, boolean isGroupOp, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setDeviceVolume(device, volume, isGroupOp);
        }

        @Override
        public void setGroupVolume(int groupId, int volume, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.setGroupVolume(groupId, volume);
        }

        @Override
        public int getGroupVolume(int groupId, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return 0;
            }

            return service.getGroupVolume(groupId);
        }

        @Override
        public void setGroupActive(int groupId, boolean active, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.setGroupActive(groupId, active);
        }

        @Override
        public void mute(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.mute(device);
        }

        @Override
        public void muteGroup(int groupId, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.muteGroup(groupId);
        }

        @Override
        public void unmute(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.unmute(device);
        }

        @Override
        public void unmuteGroup(int groupId, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.unmuteGroup(groupId);
        }

        private void postAndWait(Handler handler, Runnable runnable) {
            FutureTask<Void> task = new FutureTask(Executors.callable(runnable));

            handler.post(task);
            try {
                task.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException | InterruptedException e) {
                SneakyThrow.sneakyThrow(e);
            } catch (ExecutionException e) {
                SneakyThrow.sneakyThrow(e.getCause());
            }
        }

        @Override
        public void registerCallback(
                IBluetoothVolumeControlCallback callback, AttributionSource source) {
            Objects.requireNonNull(callback, "callback cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            postAndWait(service.mHandler, () -> service.registerCallback(callback));
        }

        @Override
        public void notifyNewRegisteredCallback(
                IBluetoothVolumeControlCallback callback, AttributionSource source) {
            Objects.requireNonNull(callback, "callback cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            postAndWait(service.mHandler, () -> service.notifyNewRegisteredCallback(callback));
        }

        @Override
        public void unregisterCallback(
                IBluetoothVolumeControlCallback callback, AttributionSource source) {
            Objects.requireNonNull(callback, "callback cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            VolumeControlService service = getService(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            postAndWait(service.mHandler, () -> service.unregisterCallback(callback));
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        for (VolumeControlStateMachine sm : mStateMachines.values()) {
            sm.dump(sb);
        }

        for (Map.Entry<BluetoothDevice, VolumeControlOffsetDescriptor> entry :
                mAudioOffsets.entrySet()) {
            VolumeControlOffsetDescriptor descriptor = entry.getValue();
            BluetoothDevice device = entry.getKey();
            ProfileService.println(sb, "    Device: " + device);
            ProfileService.println(sb, "    Volume offset cnt: " + descriptor.size());
            descriptor.dump(sb);
        }
        for (Map.Entry<Integer, Integer> entry : mGroupVolumeCache.entrySet()) {
            Boolean isMute = mGroupMuteCache.getOrDefault(entry.getKey(), false);
            ProfileService.println(
                    sb,
                    "    GroupId: "
                            + entry.getKey()
                            + " volume: "
                            + entry.getValue()
                            + ", mute: "
                            + isMute);
        }
    }
}
