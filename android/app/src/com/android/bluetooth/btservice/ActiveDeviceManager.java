/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Log;

import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The active device manager is responsible for keeping track of the
 * connected A2DP/HFP/AVRCP/HearingAid/LE audio devices and select which device is
 * active (for each profile).
 * The active device manager selects a fallback device when the currently active device
 * is disconnected, and it selects BT devices that are lastly activated one.
 *
 * Current policy (subject to change):
 * 1) If the maximum number of connected devices is one, the manager doesn't
 *    do anything. Each profile is responsible for automatically selecting
 *    the connected device as active. Only if the maximum number of connected
 *    devices is more than one, the rules below will apply.
 * 2) The selected A2DP active device is the one used for AVRCP as well.
 * 3) The HFP active device might be different from the A2DP active device.
 * 4) The Active Device Manager always listens for ACTION_ACTIVE_DEVICE_CHANGED
 *    broadcasts for each profile:
 *    - BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED for A2DP
 *    - BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED for HFP
 *    - BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED for HearingAid
 *    - BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED for LE audio
 *    If such broadcast is received (e.g., triggered indirectly by user
 *    action on the UI), the device in the received broadcast is marked
 *    as the current active device for that profile.
 * 5) If there is a HearingAid active device, then A2DP, HFP and LE audio active devices
 *    must be set to null (i.e., A2DP, HFP and LE audio cannot have active devices).
 *    The reason is that A2DP, HFP or LE audio cannot be used together with HearingAid.
 * 6) If there are no connected devices (e.g., during startup, or after all
 *    devices have been disconnected, the active device per profile
 *    (A2DP/HFP/HearingAid/LE audio) is selected as follows:
 * 6.1) The last connected HearingAid device is selected as active.
 *      If there is an active A2DP, HFP or LE audio device, those must be set to null.
 * 6.2) The last connected A2DP, HFP or LE audio device is selected as active.
 *      However, if there is an active HearingAid device, then the
 *      A2DP, HFP, or LE audio active device is not set (must remain null).
 * 7) If the currently active device (per profile) is disconnected, the
 *    Active Device Manager just marks that the profile has no active device,
 *    and the lastly activated BT device that is still connected would be selected.
 * 8) If there is already an active device, and the corresponding
 *    ACTION_ACTIVE_DEVICE_CHANGED broadcast is received, the device
 *    contained in the broadcast is marked as active. However, if
 *    the contained device is null, the corresponding profile is marked
 *    as having no active device.
 * 9) If a wired audio device is connected, the audio output is switched
 *    by the Audio Framework itself to that device. We detect this here,
 *    and the active device for each profile (A2DP/HFP/HearingAid/LE audio) is set
 *    to null to reflect the output device state change. However, if the
 *    wired audio device is disconnected, we don't do anything explicit
 *    and apply the default behavior instead:
 * 9.1) If the wired headset is still the selected output device (i.e. the
 *      active device is set to null), the Phone itself will become the output
 *      device (i.e., the active device will remain null). If music was
 *      playing, it will stop.
 * 9.2) If one of the Bluetooth devices is the selected active device
 *      (e.g., by the user in the UI), disconnecting the wired audio device
 *      will have no impact. E.g., music will continue streaming over the
 *      active Bluetooth device.
 */
class ActiveDeviceManager {
    private static final String TAG = "ActiveDeviceManager";
    private static final boolean DBG = true; // Log.isLoggable(TAG, Log.DEBUG);

    private final AdapterService mAdapterService;
    private final ServiceFactory mFactory;
    private HandlerThread mHandlerThread = null;
    private Handler mHandler = null;
    private final AudioManager mAudioManager;
    private final AudioManagerAudioDeviceCallback mAudioManagerAudioDeviceCallback;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final List<BluetoothDevice> mA2dpConnectedDevices = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<BluetoothDevice> mHfpConnectedDevices = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<BluetoothDevice> mHearingAidConnectedDevices = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<BluetoothDevice> mLeAudioConnectedDevices = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<BluetoothDevice> mLeHearingAidConnectedDevices = new ArrayList<>();
    @GuardedBy("mLock")
    private List<BluetoothDevice> mPendingLeHearingAidActiveDevice = new ArrayList<>();
    @GuardedBy("mLock")
    private BluetoothDevice mA2dpActiveDevice = null;
    @GuardedBy("mLock")
    private BluetoothDevice mHfpActiveDevice = null;
    @GuardedBy("mLock")
    private final Set<BluetoothDevice> mHearingAidActiveDevices = new ArraySet<>();
    @GuardedBy("mLock")
    private BluetoothDevice mLeAudioActiveDevice = null;
    @GuardedBy("mLock")
    private BluetoothDevice mLeHearingAidActiveDevice = null;

    // Broadcast receiver for all changes
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                Log.e(TAG, "Received intent with null action");
                return;
            }

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int currentState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                mHandler.post(() -> handleAdapterStateChanged(currentState));
                return;
            }

            final BluetoothDevice device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            final int previousState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
            final int currentState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);

            if (currentState != -1 && previousState == currentState) {
                return;
            }

            switch (action) {
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    if (currentState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleA2dpConnected(device));
                    } else if (previousState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleA2dpDisconnected(device));
                    }
                    break;
                case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                    if (currentState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleHfpConnected(device));
                    } else if (previousState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleHfpDisconnected(device));
                    }
                    break;
                case BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED:
                    if (currentState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleHearingAidConnected(device));
                    } else if (previousState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleHearingAidDisconnected(device));
                    }
                    break;
                case BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED:
                    if (currentState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleLeAudioConnected(device));
                    } else if (previousState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleLeAudioDisconnected(device));
                    }
                    break;
                case BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED:
                    if (currentState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleHapConnected(device));
                    } else if (previousState == BluetoothProfile.STATE_CONNECTED) {
                        mHandler.post(() -> handleHapDisconnected(device));
                    }
                    break;
                case BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED:
                    mHandler.post(() -> handleA2dpActiveDeviceChanged(device));
                    break;
                case BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED:
                    mHandler.post(() -> handleHfpActiveDeviceChanged(device));
                    break;
                case BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED:
                    mHandler.post(() -> handleHearingAidActiveDeviceChanged(device));
                    break;
                case BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED:
                    mHandler.post(() -> handleLeAudioActiveDeviceChanged(device));
                    break;
                case BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE:
                    mHandler.post(() -> handleHapActiveDeviceChanged(device));
                    break;
                default:
                    Log.e(TAG, "Received unexpected intent, action=" + action);
                    break;
            }
        }
    };

    private void handleAdapterStateChanged(int currentState) {
        if (DBG) {
            Log.d(TAG, "handleAdapterStateChanged: currentState=" + currentState);
        }
        if (currentState == BluetoothAdapter.STATE_ON) {
            resetState();
        }
    }

    private void handleA2dpConnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleA2dpConnected: " + device);
            }
            if (mA2dpConnectedDevices.contains(device)) {
                // The device is already connected
                return;
            }
            mA2dpConnectedDevices.add(device);
            if (mHearingAidActiveDevices.isEmpty() && mLeHearingAidActiveDevice == null) {
                // New connected device: select it as active
                // Activate HFP and A2DP at the same time if both profile already connected.
                if (mHfpConnectedDevices.contains(device)) {
                    setA2dpActiveDevice(device);
                    setHfpActiveDevice(device);
                    setLeAudioActiveDevice(null);
                    return;
                }
                DatabaseManager dbManager = mAdapterService.getDatabase();
                // Activate A2DP, if HFP is not supported or enabled.
                if (dbManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET)
                        != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                    setA2dpActiveDevice(device);
                    setLeAudioActiveDevice(null);
                }
            }
        }
    }

    private void handleHfpConnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHfpConnected: " + device);
            }
            if (mHfpConnectedDevices.contains(device)) {
                return;      // The device is already connected
            }
            mHfpConnectedDevices.add(device);
            if (mHearingAidActiveDevices.isEmpty() && mLeHearingAidActiveDevice == null) {
                // New connected device: select it as active
                // Activate HFP and A2DP at the same time once both profile connected.
                if (mA2dpConnectedDevices.contains(device)) {
                    setA2dpActiveDevice(device);
                    setHfpActiveDevice(device);
                    setLeAudioActiveDevice(null);
                    return;
                }
                DatabaseManager dbManager = mAdapterService.getDatabase();
                // Activate HFP, if A2DP is not supported or enabled.
                if (dbManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP)
                        != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                    setHfpActiveDevice(device);
                    setLeAudioActiveDevice(null);
                }
            }
        }
    }

    private void handleHearingAidConnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHearingAidConnected: " + device);
            }
            if (mHearingAidConnectedDevices.contains(device)) {
                return;      // The device is already connected
            }
            mHearingAidConnectedDevices.add(device);
            // New connected device: select it as active
            setHearingAidActiveDevice(device);
            setA2dpActiveDevice(null, true);
            setHfpActiveDevice(null);
            setLeAudioActiveDevice(null);
        }
    }

    private void handleLeAudioConnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleLeAudioConnected: " + device);
            }
            if (mLeAudioConnectedDevices.contains(device)) {
                return;      // The device is already connected
            }
            mLeAudioConnectedDevices.add(device);
            if (mHearingAidActiveDevices.isEmpty()
                    && mLeHearingAidActiveDevice == null
                    && mPendingLeHearingAidActiveDevice.isEmpty()) {
                // New connected device: select it as active
                setLeAudioActiveDevice(device);
                setA2dpActiveDevice(null, true);
                setHfpActiveDevice(null);
            } else if (mPendingLeHearingAidActiveDevice.contains(device)) {
                setLeHearingAidActiveDevice(device);
                setHearingAidActiveDevice(null);
                setA2dpActiveDevice(null, true);
                setHfpActiveDevice(null);
            }
        }
    }

    private void handleHapConnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHapConnected: " + device);
            }
            if (mLeHearingAidConnectedDevices.contains(device)) {
                return;      // The device is already connected
            }
            mLeHearingAidConnectedDevices.add(device);
            if (!mLeAudioConnectedDevices.contains(device)) {
                mPendingLeHearingAidActiveDevice.add(device);
            } else if (Objects.equals(mLeAudioActiveDevice, device)) {
                mLeHearingAidActiveDevice = device;
            } else {
                // New connected device: select it as active
                setLeHearingAidActiveDevice(device);
                setHearingAidActiveDevice(null);
                setA2dpActiveDevice(null, true);
                setHfpActiveDevice(null);
            }
        }
    }

    private void handleA2dpDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleA2dpDisconnected: " + device);
            }
            mA2dpConnectedDevices.remove(device);
            if (Objects.equals(mA2dpActiveDevice, device)) {
                if (!setFallbackDeviceActiveLocked()) {
                    setA2dpActiveDevice(null, false);
                }
            }
        }
    }

    private void handleHfpDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHfpDisconnected: " + device);
            }
            mHfpConnectedDevices.remove(device);
            if (Objects.equals(mHfpActiveDevice, device)) {
                if (mHfpConnectedDevices.isEmpty()) {
                    setHfpActiveDevice(null);
                }
                setFallbackDeviceActiveLocked();
            }
        }
    }

    private void handleHearingAidDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHearingAidDisconnected: " + device);
            }
            mHearingAidConnectedDevices.remove(device);
            if (mHearingAidActiveDevices.remove(device) && mHearingAidActiveDevices.isEmpty()) {
                if (mHearingAidConnectedDevices.isEmpty()) {
                    setHearingAidActiveDevice(null);
                }
                setFallbackDeviceActiveLocked();
            }
        }
    }

    private void handleLeAudioDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleLeAudioDisconnected: " + device);
            }
            mLeAudioConnectedDevices.remove(device);
            mLeHearingAidConnectedDevices.remove(device);
            if (Objects.equals(mLeAudioActiveDevice, device)) {
                if (mLeAudioConnectedDevices.isEmpty()) {
                    setLeAudioActiveDevice(null);
                }
                setFallbackDeviceActiveLocked();
            }
        }
    }

    private void handleHapDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHapDisconnected: " + device);
            }
            mLeHearingAidConnectedDevices.remove(device);
            mPendingLeHearingAidActiveDevice.remove(device);
            if (Objects.equals(mLeHearingAidActiveDevice, device)) {
                mLeHearingAidActiveDevice = null;
            }
        }
    }

    private void handleA2dpActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleA2dpActiveDeviceChanged: " + device);
            }
            if (device != null && !Objects.equals(mA2dpActiveDevice, device)) {
                setHearingAidActiveDevice(null);
                setLeAudioActiveDevice(null);
            }
            if (mHfpConnectedDevices.contains(device)) {
                setHfpActiveDevice(device);
            }
            // Just assign locally the new value
            mA2dpActiveDevice = device;
        }
    }

    private void handleHfpActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHfpActiveDeviceChanged: " + device);
            }
            if (device != null && !Objects.equals(mHfpActiveDevice, device)) {
                setHearingAidActiveDevice(null);
                setLeAudioActiveDevice(null);
            }
            if (mA2dpConnectedDevices.contains(device)) {
                setA2dpActiveDevice(device);
            }
            // Just assign locally the new value
            mHfpActiveDevice = device;
        }
    }

    private void handleHearingAidActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHearingAidActiveDeviceChanged: " + device);
            }
            // Just assign locally the new value
            final HearingAidService hearingAidService = mFactory.getHearingAidService();
            if (hearingAidService != null) {
                long hiSyncId = hearingAidService.getHiSyncId(device);
                if (getHearingAidActiveHiSyncIdLocked() == hiSyncId) {
                    mHearingAidActiveDevices.add(device);
                } else {
                    mHearingAidActiveDevices.clear();
                    mHearingAidActiveDevices.addAll(
                            hearingAidService.getConnectedPeerDevices(hiSyncId));
                }
            }
            if (device != null) {
                setA2dpActiveDevice(null, true);
                setHfpActiveDevice(null);
                setLeAudioActiveDevice(null);
            }
        }
    }

    private void handleLeAudioActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleLeAudioActiveDeviceChanged: " + device);
            }
            if (device != null && !mLeAudioConnectedDevices.contains(device)) {
                mLeAudioConnectedDevices.add(device);
            }
            // Just assign locally the new value
            if (device != null && !Objects.equals(mLeAudioActiveDevice, device)) {
                setA2dpActiveDevice(null, true);
                setHfpActiveDevice(null);
                setHearingAidActiveDevice(null);
            }
            mLeAudioActiveDevice = device;
        }
    }

    private void handleHapActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "handleHapActiveDeviceChanged: " + device);
            }
            if (device != null && !mLeHearingAidConnectedDevices.contains(device)) {
                mLeHearingAidConnectedDevices.add(device);
            }
            // Just assign locally the new value
            if (device != null && !Objects.equals(mLeHearingAidActiveDevice, device)) {
                setA2dpActiveDevice(null, true);
                setHfpActiveDevice(null);
                setHearingAidActiveDevice(null);
            }
            mLeAudioActiveDevice = device;
            mLeHearingAidActiveDevice = device;
        }
    }

    /** Notifications of audio device connection and disconnection events. */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private class AudioManagerAudioDeviceCallback extends AudioDeviceCallback {
        private boolean isWiredAudioHeadset(AudioDeviceInfo deviceInfo) {
            switch (deviceInfo.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    return true;
                default:
                    break;
            }
            return false;
        }

        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (DBG) {
                Log.d(TAG, "onAudioDevicesAdded");
            }
            boolean hasAddedWiredDevice = false;
            for (AudioDeviceInfo deviceInfo : addedDevices) {
                if (DBG) {
                    Log.d(TAG, "Audio device added: " + deviceInfo.getProductName() + " type: "
                            + deviceInfo.getType());
                }
                if (isWiredAudioHeadset(deviceInfo)) {
                    hasAddedWiredDevice = true;
                    break;
                }
            }
            if (hasAddedWiredDevice) {
                wiredAudioDeviceConnected();
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
        }
    }

    ActiveDeviceManager(AdapterService service, ServiceFactory factory) {
        mAdapterService = service;
        mFactory = factory;
        mAudioManager = service.getSystemService(AudioManager.class);
        mAudioManagerAudioDeviceCallback = new AudioManagerAudioDeviceCallback();
    }

    void start() {
        if (DBG) {
            Log.d(TAG, "start()");
        }

        mHandlerThread = new HandlerThread("BluetoothActiveDeviceManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE);
        mAdapterService.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);

        mAudioManager.registerAudioDeviceCallback(mAudioManagerAudioDeviceCallback, mHandler);
    }

    void cleanup() {
        if (DBG) {
            Log.d(TAG, "cleanup()");
        }

        mAudioManager.unregisterAudioDeviceCallback(mAudioManagerAudioDeviceCallback);
        mAdapterService.unregisterReceiver(mReceiver);
        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
        }
        resetState();
    }

    /**
     * Get the {@link Looper} for the handler thread. This is used in testing and helper
     * objects
     *
     * @return {@link Looper} for the handler thread
     */
    @VisibleForTesting
    public Looper getHandlerLooper() {
        if (mHandlerThread == null) {
            return null;
        }
        return mHandlerThread.getLooper();
    }

    private void setA2dpActiveDevice(@NonNull BluetoothDevice device) {
        setA2dpActiveDevice(device, false);
    }

    private void setA2dpActiveDevice(@Nullable BluetoothDevice device, boolean hasFallbackDevice) {
        if (DBG) {
            Log.d(TAG, "setA2dpActiveDevice(" + device + ")"
                    + (device == null ? " hasFallbackDevice=" + hasFallbackDevice : ""));
        }

        final A2dpService a2dpService = mFactory.getA2dpService();
        if (a2dpService == null) {
            return;
        }

        boolean success = false;
        if (device == null) {
            success = a2dpService.removeActiveDevice(!hasFallbackDevice);
        } else {
            success = a2dpService.setActiveDevice(device);
        }

        if (!success) {
            return;
        }

        synchronized (mLock) {
            mA2dpActiveDevice = device;
        }
    }

    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    private void setHfpActiveDevice(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "setHfpActiveDevice(" + device + ")");
            }
            final HeadsetService headsetService = mFactory.getHeadsetService();
            if (headsetService == null) {
                return;
            }
            BluetoothSinkAudioPolicy audioPolicy = headsetService.getHfpCallAudioPolicy(device);
            if (audioPolicy == null || audioPolicy.getActiveDevicePolicyAfterConnection()
                    != BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED) {
                if (!headsetService.setActiveDevice(device)) {
                    return;
                }
                mHfpActiveDevice = device;
            }
        }
    }

    private void setHearingAidActiveDevice(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "setHearingAidActiveDevice(" + device + ")");
            }
            final HearingAidService hearingAidService = mFactory.getHearingAidService();
            if (hearingAidService == null) {
                return;
            }

            if (device == null) {
                hearingAidService.setActiveDevice(null);
                mHearingAidActiveDevices.clear();
                return;
            }

            long hiSyncId = hearingAidService.getHiSyncId(device);
            if (getHearingAidActiveHiSyncIdLocked() == hiSyncId) {
                mHearingAidActiveDevices.add(device);
                return;
            }

            if (!hearingAidService.setActiveDevice(device)) {
                return;
            }
            mHearingAidActiveDevices.clear();
            mHearingAidActiveDevices.addAll(hearingAidService.getConnectedPeerDevices(hiSyncId));
        }
    }

    private void setLeAudioActiveDevice(BluetoothDevice device) {
        synchronized (mLock) {
            if (DBG) {
                Log.d(TAG, "setLeAudioActiveDevice(" + device + ")");
            }
            final LeAudioService leAudioService = mFactory.getLeAudioService();
            if (leAudioService == null) {
                return;
            }
            if (!leAudioService.setActiveDevice(device)) {
                return;
            }
            mLeAudioActiveDevice = device;
            if (device == null) {
                mLeHearingAidActiveDevice = null;
                mPendingLeHearingAidActiveDevice.remove(device);
            }
        }
    }

    private void setLeHearingAidActiveDevice(BluetoothDevice device) {
        synchronized (mLock) {
            if (!Objects.equals(mLeAudioActiveDevice, device)) {
                setLeAudioActiveDevice(device);
            }
            if (Objects.equals(mLeAudioActiveDevice, device)) {
                // setLeAudioActiveDevice succeed
                mLeHearingAidActiveDevice = device;
                mPendingLeHearingAidActiveDevice.remove(device);
            }
        }
    }

    /**
     * TODO: This method can return true when a fallback device for an unrelated profile is found.
     *       Take disconnected profile as an argument, and find the exact fallback device.
     *       Also, split this method to smaller methods for better readability.
     *
     * @return true when the fallback device is activated, false otherwise
     */
    private boolean setFallbackDeviceActiveLocked() {
        if (DBG) {
            Log.d(TAG, "setFallbackDeviceActive");
        }
        DatabaseManager dbManager = mAdapterService.getDatabase();
        if (dbManager == null) {
            return false;
        }
        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        if (!mHearingAidConnectedDevices.isEmpty()) {
            connectedHearingAidDevices.addAll(mHearingAidConnectedDevices);
        }
        if (!mLeHearingAidConnectedDevices.isEmpty()) {
            connectedHearingAidDevices.addAll(mLeHearingAidConnectedDevices);
        }
        if (!connectedHearingAidDevices.isEmpty()) {
            BluetoothDevice device =
                    dbManager.getMostRecentlyConnectedDevicesInList(connectedHearingAidDevices);
            if (device != null) {
                if (mHearingAidConnectedDevices.contains(device)) {
                    if (DBG) {
                        Log.d(TAG, "set hearing aid device active: " + device);
                    }
                    setHearingAidActiveDevice(device);
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                    setLeAudioActiveDevice(null);
                } else {
                    if (DBG) {
                        Log.d(TAG, "set LE hearing aid device active: " + device);
                    }
                    setLeHearingAidActiveDevice(device);
                    setHearingAidActiveDevice(null);
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
                return true;
            }
        }

        A2dpService a2dpService = mFactory.getA2dpService();
        BluetoothDevice a2dpFallbackDevice = null;
        if (a2dpService != null) {
            a2dpFallbackDevice = a2dpService.getFallbackDevice();
        }

        HeadsetService headsetService = mFactory.getHeadsetService();
        BluetoothDevice headsetFallbackDevice = null;
        if (headsetService != null) {
            headsetFallbackDevice = headsetService.getFallbackDevice();
        }

        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        connectedDevices.addAll(mLeAudioConnectedDevices);
        switch (mAudioManager.getMode()) {
            case AudioManager.MODE_NORMAL:
                if (a2dpFallbackDevice != null) {
                    connectedDevices.add(a2dpFallbackDevice);
                }
                break;
            case AudioManager.MODE_RINGTONE:
                if (headsetFallbackDevice != null && headsetService.isInbandRingingEnabled()) {
                    connectedDevices.add(headsetFallbackDevice);
                }
                break;
            default:
                if (headsetFallbackDevice != null) {
                    connectedDevices.add(headsetFallbackDevice);
                }
        }
        BluetoothDevice device = dbManager.getMostRecentlyConnectedDevicesInList(connectedDevices);
        if (device != null) {
            if (mAudioManager.getMode() == AudioManager.MODE_NORMAL) {
                if (Objects.equals(a2dpFallbackDevice, device)) {
                    if (DBG) {
                        Log.d(TAG, "set A2DP device active: " + device);
                    }
                    setA2dpActiveDevice(device);
                    if (headsetFallbackDevice != null) {
                        setHfpActiveDevice(device);
                        setLeAudioActiveDevice(null);
                    }
                } else {
                    if (DBG) {
                        Log.d(TAG, "set LE audio device active: " + device);
                    }
                    setLeAudioActiveDevice(device);
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
            } else {
                if (Objects.equals(headsetFallbackDevice, device)) {
                    if (DBG) {
                        Log.d(TAG, "set HFP device active: " + device);
                    }
                    setHfpActiveDevice(device);
                    if (a2dpFallbackDevice != null) {
                        setA2dpActiveDevice(a2dpFallbackDevice);
                        setLeAudioActiveDevice(null);
                    }
                } else {
                    if (DBG) {
                        Log.d(TAG, "set LE audio device active: " + device);
                    }
                    setLeAudioActiveDevice(device);
                    setA2dpActiveDevice(null, true);
                    setHfpActiveDevice(null);
                }
            }
            return true;
        }

        // No fallback device is found.
        return false;
    }

    private void resetState() {
        synchronized (mLock) {
            mA2dpConnectedDevices.clear();
            mA2dpActiveDevice = null;

            mHfpConnectedDevices.clear();
            mHfpActiveDevice = null;

            mHearingAidConnectedDevices.clear();
            mHearingAidActiveDevices.clear();

            mLeAudioConnectedDevices.clear();
            mLeAudioActiveDevice = null;

            mLeHearingAidConnectedDevices.clear();
            mLeHearingAidActiveDevice = null;
            mPendingLeHearingAidActiveDevice.clear();
        }
    }

    @VisibleForTesting
    BroadcastReceiver getBroadcastReceiver() {
        return mReceiver;
    }

    @VisibleForTesting
    BluetoothDevice getA2dpActiveDevice() {
        return mA2dpActiveDevice;
    }

    @VisibleForTesting
    BluetoothDevice getHfpActiveDevice() {
        return mHfpActiveDevice;
    }

    @VisibleForTesting
    Set<BluetoothDevice> getHearingAidActiveDevices() {
        return mHearingAidActiveDevices;
    }

    @VisibleForTesting
    BluetoothDevice getLeAudioActiveDevice() {
        return mLeAudioActiveDevice;
    }

    long getHearingAidActiveHiSyncIdLocked() {
        final HearingAidService hearingAidService = mFactory.getHearingAidService();
        if (hearingAidService != null && !mHearingAidActiveDevices.isEmpty()) {
            return hearingAidService.getHiSyncId(mHearingAidActiveDevices.iterator().next());
        }
        return BluetoothHearingAid.HI_SYNC_ID_INVALID;
    }

    /**
     * Called when a wired audio device is connected.
     * It might be called multiple times each time a wired audio device is connected.
     */
    @VisibleForTesting
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    void wiredAudioDeviceConnected() {
        if (DBG) {
            Log.d(TAG, "wiredAudioDeviceConnected");
        }
        setA2dpActiveDevice(null, true);
        setHfpActiveDevice(null);
        setHearingAidActiveDevice(null);
        setLeAudioActiveDevice(null);
    }
}
