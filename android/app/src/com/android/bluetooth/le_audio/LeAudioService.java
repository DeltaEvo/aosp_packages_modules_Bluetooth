/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
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

package com.android.bluetooth.le_audio;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;

import static com.android.bluetooth.flags.Flags.leaudioAllowedContextMask;
import static com.android.bluetooth.flags.Flags.leaudioBigDependsOnAudioState;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastAssistantPeripheralEntrustment;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastFeatureSupport;
import static com.android.bluetooth.flags.Flags.leaudioUseAudioModeListener;
import static com.android.modules.utils.build.SdkLevel.isAtLeastU;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.BluetoothLeBroadcastSubgroupSettings;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeAudio;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.IBluetoothLeBroadcastCallback;
import android.bluetooth.IBluetoothVolumeControl;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.Utils;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.AudioRoutingManager;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hap.HapClientService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.mcp.McpService;
import com.android.bluetooth.tbs.TbsGatt;
import com.android.bluetooth.tbs.TbsService;
import com.android.bluetooth.vc.VolumeControlService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/** Provides Bluetooth LeAudio profile, as a service in the Bluetooth application. */
public class LeAudioService extends ProfileService {
    private static final String TAG = "LeAudioService";

    // Timeout for state machine thread join, to prevent potential ANR.
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    private static LeAudioService sLeAudioService;

    /** Indicates group audio support for none direction */
    private static final int AUDIO_DIRECTION_NONE = 0x00;

    /** Indicates group audio support for output direction */
    private static final int AUDIO_DIRECTION_OUTPUT_BIT = 0x01;

    /** Indicates group audio support for input direction */
    private static final int AUDIO_DIRECTION_INPUT_BIT = 0x02;

    /** Indicates group is not active */
    private static final int ACTIVE_STATE_INACTIVE = 0x00;

    /** Indicates group is going to be active */
    private static final int ACTIVE_STATE_GETTING_ACTIVE = 0x01;

    /** Indicates group is active */
    private static final int ACTIVE_STATE_ACTIVE = 0x02;

    /** This is used by application read-only for checking the fallback active group id. */
    public static final String BLUETOOTH_LE_BROADCAST_FALLBACK_ACTIVE_GROUP_ID =
            "bluetooth_le_broadcast_fallback_active_group_id";

    /**
     * Per PBP 1.0 4.3. High Quality Public Broadcast Audio, Broadcast HIGH quality audio configs
     * are with sampling frequency 48khz
     */
    private static final BluetoothLeAudioCodecConfig BROADCAST_HIGH_QUALITY_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000)
                    .build();

    /* 5 seconds timeout for Broadcast streaming state transition */
    private static final int DIALING_OUT_TIMEOUT_MS = 5000;

    private AdapterService mAdapterService;
    private DatabaseManager mDatabaseManager;
    private HandlerThread mStateMachinesThread;
    private volatile BluetoothDevice mActiveAudioOutDevice;
    private volatile BluetoothDevice mActiveAudioInDevice;
    private volatile BluetoothDevice mActiveBroadcastAudioDevice;
    private BluetoothDevice mExposedActiveDevice;
    private LeAudioCodecConfig mLeAudioCodecConfig;
    private final ReentrantReadWriteLock mGroupReadWriteLock = new ReentrantReadWriteLock();
    private final Lock mGroupReadLock = mGroupReadWriteLock.readLock();
    private final Lock mGroupWriteLock = mGroupReadWriteLock.writeLock();
    ServiceFactory mServiceFactory = new ServiceFactory();

    private final LeAudioNativeInterface mNativeInterface;
    boolean mLeAudioNativeIsInitialized = false;
    boolean mLeAudioInbandRingtoneSupportedByPlatform = true;
    boolean mBluetoothEnabled = false;

    /**
     * During a call that has LE Audio -> HFP handover, the HFP device that is going to connect SCO
     * after LE Audio group becomes idle
     */
    BluetoothDevice mHfpHandoverDevice = null;

    /** LE audio active device that was removed from active because of HFP handover */
    BluetoothDevice mLeAudioDeviceInactivatedForHfpHandover = null;

    LeAudioBroadcasterNativeInterface mLeAudioBroadcasterNativeInterface = null;
    private DialingOutTimeoutEvent mDialingOutTimeoutEvent = null;
    @VisibleForTesting AudioManager mAudioManager;
    LeAudioTmapGattServer mTmapGattServer;
    int mTmapRoleMask;
    int mUnicastGroupIdDeactivatedForBroadcastTransition = LE_AUDIO_GROUP_ID_INVALID;
    int mCurrentAudioMode = AudioManager.MODE_NORMAL;
    Optional<Integer> mBroadcastIdDeactivatedForUnicastTransition = Optional.empty();
    Optional<Boolean> mQueuedInCallValue = Optional.empty();
    boolean mTmapStarted = false;
    private boolean mAwaitingBroadcastCreateResponse = false;
    private final LinkedList<BluetoothLeBroadcastSettings> mCreateBroadcastQueue =
            new LinkedList<>();
    boolean mIsSourceStreamMonitorModeEnabled = false;
    boolean mIsBroadcastPausedFromOutside = false;

    @VisibleForTesting TbsService mTbsService;

    @VisibleForTesting McpService mMcpService;

    @VisibleForTesting VolumeControlService mVolumeControlService;

    @VisibleForTesting HapClientService mHapClientService;

    @VisibleForTesting CsipSetCoordinatorService mCsipSetCoordinatorService;

    @VisibleForTesting BassClientService mBassClientService;

    @VisibleForTesting
    @GuardedBy("mBroadcastCallbacks")
    final RemoteCallbackList<IBluetoothLeBroadcastCallback> mBroadcastCallbacks =
            new RemoteCallbackList<>();

    @VisibleForTesting
    @GuardedBy("mLeAudioCallbacks")
    final RemoteCallbackList<IBluetoothLeAudioCallback> mLeAudioCallbacks =
            new RemoteCallbackList<>();

    BluetoothLeScanner mAudioServersScanner;
    /* When mScanCallback is not null, it means scan is started. */
    ScanCallback mScanCallback;

    private final AudioServerScanCallback2 mScanCallback2 = new AudioServerScanCallback2();

    public LeAudioService(Context ctx) {
        this(ctx, LeAudioNativeInterface.getInstance());
    }

    @VisibleForTesting
    LeAudioService(Context ctx, LeAudioNativeInterface nativeInterface) {
        super(ctx);
        mNativeInterface = Objects.requireNonNull(nativeInterface);
    }

    private static class LeAudioGroupDescriptor {
        LeAudioGroupDescriptor(boolean isInbandRingtonEnabled) {
            mIsConnected = false;
            mActiveState = ACTIVE_STATE_INACTIVE;
            mAllowedSinkContexts = BluetoothLeAudio.CONTEXTS_ALL;
            mAllowedSourceContexts = BluetoothLeAudio.CONTEXTS_ALL;
            mHasFallbackDeviceWhenGettingInactive = false;
            mDirection = AUDIO_DIRECTION_NONE;
            mCodecStatus = null;
            mLostLeadDeviceWhileStreaming = null;
            mCurrentLeadDevice = null;
            mInbandRingtoneEnabled = isInbandRingtonEnabled;
            mAvailableContexts = 0;
            mInputSelectableConfig = new ArrayList<>();
            mOutputSelectableConfig = new ArrayList<>();
            mInactivatedDueToContextType = false;
        }

        Boolean mIsConnected;
        Boolean mHasFallbackDeviceWhenGettingInactive;
        Integer mDirection;
        BluetoothLeAudioCodecStatus mCodecStatus;
        /* This can be non empty only for the streaming time */
        BluetoothDevice mLostLeadDeviceWhileStreaming;
        BluetoothDevice mCurrentLeadDevice;
        Boolean mInbandRingtoneEnabled;
        Integer mAvailableContexts;
        List<BluetoothLeAudioCodecConfig> mInputSelectableConfig;
        List<BluetoothLeAudioCodecConfig> mOutputSelectableConfig;
        Boolean mInactivatedDueToContextType;

        private Integer mActiveState;
        private Integer mAllowedSinkContexts;
        private Integer mAllowedSourceContexts;

        boolean isActive() {
            return mActiveState == ACTIVE_STATE_ACTIVE;
        }

        boolean isInactive() {
            return mActiveState == ACTIVE_STATE_INACTIVE;
        }

        boolean isGettingActive() {
            return mActiveState == ACTIVE_STATE_GETTING_ACTIVE;
        }

        void setActiveState(int state) {
            if ((state != ACTIVE_STATE_ACTIVE)
                    && (state != ACTIVE_STATE_INACTIVE)
                    && (state != ACTIVE_STATE_GETTING_ACTIVE)) {
                Log.e(TAG, "LeAudioGroupDescriptor.setActiveState: Invalid state set: " + state);
                return;
            }

            Log.d(TAG, "LeAudioGroupDescriptor.setActiveState: " + mActiveState + " -> " + state);
            mActiveState = state;
        }

        String getActiveStateString() {
            switch (mActiveState) {
                case ACTIVE_STATE_ACTIVE:
                    return "ACTIVE_STATE_ACTIVE";
                case ACTIVE_STATE_INACTIVE:
                    return "ACTIVE_STATE_INACTIVE";
                case ACTIVE_STATE_GETTING_ACTIVE:
                    return "ACTIVE_STATE_GETTING_ACTIVE";
                default:
                    return "INVALID";
            }
        }

        void updateAllowedContexts(Integer allowedSinkContexts, Integer allowedSourceContexts) {
            Log.d(
                    TAG,
                    "LeAudioGroupDescriptor.mAllowedSinkContexts: "
                            + mAllowedSinkContexts
                            + " -> "
                            + allowedSinkContexts
                            + ", LeAudioGroupDescriptor.mAllowedSourceContexts: "
                            + mAllowedSourceContexts
                            + " -> "
                            + allowedSourceContexts);

            mAllowedSinkContexts = allowedSinkContexts;
            mAllowedSourceContexts = allowedSourceContexts;
        }

        Integer getAllowedSinkContexts() {
            return mAllowedSinkContexts;
        }

        Integer getAllowedSourceContexts() {
            return mAllowedSourceContexts;
        }

        boolean areAllowedContextsModified() {
            return (mAllowedSinkContexts != BluetoothLeAudio.CONTEXTS_ALL)
                    || (mAllowedSourceContexts != BluetoothLeAudio.CONTEXTS_ALL);
        }
    }

    private static class LeAudioDeviceDescriptor {
        LeAudioDeviceDescriptor(boolean isInbandRingtonEnabled) {
            mAclConnected = false;
            mStateMachine = null;
            mGroupId = LE_AUDIO_GROUP_ID_INVALID;
            mSinkAudioLocation = BluetoothLeAudio.AUDIO_LOCATION_INVALID;
            mDirection = AUDIO_DIRECTION_NONE;
            mDevInbandRingtoneEnabled = isInbandRingtonEnabled;
        }

        public boolean mAclConnected;
        public LeAudioStateMachine mStateMachine;
        public Integer mGroupId;
        public Integer mSinkAudioLocation;
        public Integer mDirection;
        Boolean mDevInbandRingtoneEnabled;
    }

    private static class LeAudioBroadcastDescriptor {
        LeAudioBroadcastDescriptor() {
            mState = LeAudioStackEvent.BROADCAST_STATE_STOPPED;
            mMetadata = null;
            mRequestedForDetails = false;
        }

        public Integer mState;
        public BluetoothLeBroadcastMetadata mMetadata;
        public Boolean mRequestedForDetails;
    }

    List<BluetoothLeAudioCodecConfig> mInputLocalCodecCapabilities = new ArrayList<>();
    List<BluetoothLeAudioCodecConfig> mOutputLocalCodecCapabilities = new ArrayList<>();

    @GuardedBy("mGroupWriteLock")
    private final Map<Integer, LeAudioGroupDescriptor> mGroupDescriptors = new LinkedHashMap<>();

    @GuardedBy("mGroupReadLock")
    private final Map<Integer, LeAudioGroupDescriptor> mGroupDescriptorsView =
            Collections.unmodifiableMap(mGroupDescriptors);

    private final Map<BluetoothDevice, LeAudioDeviceDescriptor> mDeviceDescriptors =
            new LinkedHashMap<>();
    private final Map<Integer, LeAudioBroadcastDescriptor> mBroadcastDescriptors =
            new LinkedHashMap<>();

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private final AudioManagerAudioDeviceCallback mAudioManagerAudioDeviceCallback =
            new AudioManagerAudioDeviceCallback();
    private final AudioModeChangeListener mAudioModeChangeListener = new AudioModeChangeListener();

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothLeAudioBinder(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileBapUnicastClientEnabled().orElse(false);
    }

    public static boolean isBroadcastEnabled() {
        return leaudioBroadcastFeatureSupport()
                && BluetoothProperties.isProfileBapBroadcastSourceEnabled().orElse(false);
    }

    private boolean registerTmap() {
        if (mTmapGattServer != null) {
            throw new IllegalStateException("TMAP GATT server started before start() is called");
        }
        mTmapGattServer = LeAudioObjectsFactory.getInstance().getTmapGattServer(this);

        try {
            mTmapGattServer.start(mTmapRoleMask);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Fail to start TmapGattServer", e);
            mTmapGattServer = null;
            return false;
        }

        return true;
    }

    @Override
    public void start() {
        Log.i(TAG, "start()");
        if (sLeAudioService != null) {
            throw new IllegalStateException("start() called twice");
        }

        mAdapterService =
                Objects.requireNonNull(
                        AdapterService.getAdapterService(),
                        "AdapterService cannot be null when LeAudioService starts");
        mDatabaseManager =
                Objects.requireNonNull(
                        mAdapterService.getDatabase(),
                        "DatabaseManager cannot be null when LeAudioService starts");

        mAudioManager = getSystemService(AudioManager.class);
        Objects.requireNonNull(
                mAudioManager, "AudioManager cannot be null when LeAudioService starts");

        // Start handler thread for state machines
        mStateMachinesThread = new HandlerThread("LeAudioService.StateMachines");
        mStateMachinesThread.start();

        mBroadcastDescriptors.clear();

        mGroupWriteLock.lock();
        try {
            mDeviceDescriptors.clear();
            mGroupDescriptors.clear();
        } finally {
            mGroupWriteLock.unlock();
        }

        mTmapRoleMask =
                LeAudioTmapGattServer.TMAP_ROLE_FLAG_CG | LeAudioTmapGattServer.TMAP_ROLE_FLAG_UMS;

        // Initialize Broadcast native interface
        if ((mAdapterService.getSupportedProfilesBitMask()
                        & (1 << BluetoothProfile.LE_AUDIO_BROADCAST))
                != 0) {
            Log.i(TAG, "Init Le Audio broadcaster");
            mLeAudioBroadcasterNativeInterface =
                    Objects.requireNonNull(
                            LeAudioBroadcasterNativeInterface.getInstance(),
                            "LeAudioBroadcasterNativeInterface cannot be null when LeAudioService"
                                    + " starts");
            mLeAudioBroadcasterNativeInterface.init();
            mTmapRoleMask |= LeAudioTmapGattServer.TMAP_ROLE_FLAG_BMS;
        } else {
            Log.w(TAG, "Le Audio Broadcasts not supported.");
        }

        mTmapStarted = registerTmap();

        mLeAudioInbandRingtoneSupportedByPlatform =
                BluetoothProperties.isLeAudioInbandRingtoneSupported().orElse(true);

        mAudioManager.registerAudioDeviceCallback(mAudioManagerAudioDeviceCallback, mHandler);

        // Mark service as started
        setLeAudioService(this);

        // Setup codec config
        mLeAudioCodecConfig = new LeAudioCodecConfig(this);
        if (!Flags.leaudioSynchronizeStart()) {
            // Delay the call to init by posting it. This ensures TBS and MCS are fully initialized
            // before we start accepting connections
            mHandler.post(this::init);
            return;
        }
        mNativeInterface.init(mLeAudioCodecConfig.getCodecConfigOffloading());

        if (leaudioUseAudioModeListener()) {
            mAudioManager.addOnModeChangedListener(getMainExecutor(), mAudioModeChangeListener);
        }
    }

    // TODO: b/341385684 -- Delete the init method as it has been inlined in start
    private void init() {
        if (!isAvailable()) {
            Log.e(TAG, " Service disabled before init");
            return;
        }

        if (!mTmapStarted) {
            mTmapStarted = registerTmap();
        }

        mNativeInterface.init(mLeAudioCodecConfig.getCodecConfigOffloading());

        if (leaudioUseAudioModeListener()) {
            mAudioManager.addOnModeChangedListener(getMainExecutor(), mAudioModeChangeListener);
        }
    }

    @Override
    public void stop() {
        Log.i(TAG, "stop()");
        if (sLeAudioService == null) {
            Log.w(TAG, "stop() called before start()");
            return;
        }

        mQueuedInCallValue = Optional.empty();
        if (leaudioUseAudioModeListener()) {
            mAudioManager.removeOnModeChangedListener(mAudioModeChangeListener);
        }

        mCreateBroadcastQueue.clear();
        mAwaitingBroadcastCreateResponse = false;
        mIsSourceStreamMonitorModeEnabled = false;
        mIsBroadcastPausedFromOutside = false;

        clearBroadcastTimeoutCallback();

        if (!Flags.leaudioSynchronizeStart()) {
            mHandler.removeCallbacks(this::init);
        }
        removeActiveDevice(false);

        if (mTmapGattServer == null) {
            Log.w(TAG, "TMAP GATT server should never be null before stop() is called");
        } else {
            mTmapGattServer.stop();
            mTmapGattServer = null;
            mTmapStarted = false;
        }

        stopAudioServersBackgroundScan();
        mAudioServersScanner = null;

        // Don't wait for async call with INACTIVE group status, clean active
        // device for active group.
        mGroupReadLock.lock();
        try {
            try {
                for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                        mGroupDescriptorsView.entrySet()) {
                    LeAudioGroupDescriptor descriptor = entry.getValue();
                    Integer groupId = entry.getKey();
                    if (descriptor.isActive()) {
                        descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                        updateActiveDevices(
                                groupId,
                                descriptor.mDirection,
                                AUDIO_DIRECTION_NONE,
                                false,
                                false,
                                false);
                        break;
                    }
                }

                // Destroy state machines and stop handler thread
                for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                    LeAudioStateMachine sm = descriptor.mStateMachine;
                    if (sm == null) {
                        continue;
                    }
                    sm.quit();
                    sm.cleanup();
                }
            } finally {
                // Upgrade to write lock
                mGroupReadLock.unlock();
                mGroupWriteLock.lock();
            }
            mDeviceDescriptors.clear();
            mGroupDescriptors.clear();
        } finally {
            mGroupWriteLock.unlock();
        }

        // Cleanup native interfaces
        mNativeInterface.cleanup();
        mLeAudioNativeIsInitialized = false;
        mBluetoothEnabled = false;
        mHfpHandoverDevice = null;
        mLeAudioDeviceInactivatedForHfpHandover = null;

        mActiveAudioOutDevice = null;
        mActiveAudioInDevice = null;
        mExposedActiveDevice = null;
        mLeAudioCodecConfig = null;

        // Set the service and BLE devices as inactive
        setLeAudioService(null);

        // Unregister broadcast callbacks
        if (mBroadcastCallbacks != null) {
            mBroadcastCallbacks.kill();
        }

        if (mLeAudioCallbacks != null) {
            mLeAudioCallbacks.kill();
        }

        mBroadcastDescriptors.clear();

        if (mLeAudioBroadcasterNativeInterface != null) {
            mLeAudioBroadcasterNativeInterface.cleanup();
            mLeAudioBroadcasterNativeInterface = null;
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

        mAudioManager.unregisterAudioDeviceCallback(mAudioManagerAudioDeviceCallback);

        mAdapterService = null;
        mAudioManager = null;
        mMcpService = null;
        mTbsService = null;
        mVolumeControlService = null;
        mCsipSetCoordinatorService = null;
        mBassClientService = null;
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");
    }

    public static synchronized LeAudioService getLeAudioService() {
        if (sLeAudioService == null) {
            Log.w(TAG, "getLeAudioService(): service is NULL");
            return null;
        }
        if (!sLeAudioService.isAvailable()) {
            Log.w(TAG, "getLeAudioService(): service is not available");
            return null;
        }
        return sLeAudioService;
    }

    @VisibleForTesting
    static synchronized void setLeAudioService(LeAudioService instance) {
        Log.d(TAG, "setLeAudioService(): set to: " + instance);
        sLeAudioService = instance;
    }

    VolumeControlService getVolumeControlService() {
        if (mVolumeControlService == null) {
            mVolumeControlService = mServiceFactory.getVolumeControlService();
            if (mVolumeControlService == null) {
                Log.e(TAG, "Volume control service is not available");
            }
        }
        return mVolumeControlService;
    }

    BassClientService getBassClientService() {
        if (mBassClientService == null) {
            mBassClientService = mServiceFactory.getBassClientService();
            if (mBassClientService == null) {
                Log.e(TAG, "BASS service is not available");
            }
        }
        return mBassClientService;
    }

    @VisibleForTesting
    int getAudioDeviceGroupVolume(int groupId) {
        VolumeControlService volumeControlService = getVolumeControlService();
        if (volumeControlService == null) {
            return IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;
        }
        return volumeControlService.getAudioDeviceGroupVolume(groupId);
    }

    LeAudioDeviceDescriptor createDeviceDescriptor(
            BluetoothDevice device, boolean isInbandRingtoneEnabled) {
        LeAudioDeviceDescriptor descriptor = mDeviceDescriptors.get(device);
        if (descriptor == null) {
            mDeviceDescriptors.put(device, new LeAudioDeviceDescriptor(isInbandRingtoneEnabled));
            descriptor = mDeviceDescriptors.get(device);
            Log.d(TAG, "Created descriptor for device: " + device);
        } else {
            Log.w(TAG, "Device: " + device + ", already exists");
        }

        return descriptor;
    }

    private void setEnabledState(BluetoothDevice device, boolean enabled) {
        Log.d(TAG, "setEnabledState: address:" + device + " enabled: " + enabled);
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "setEnabledState, mLeAudioNativeIsInitialized is not initialized");
            return;
        }
        mNativeInterface.setEnableState(device, enabled);
    }

    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);

        if (getConnectionPolicy(device) == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.e(TAG, "Cannot connect to " + device + " : CONNECTION_POLICY_FORBIDDEN");
            return false;
        }
        final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
        if (!Utils.arrayContains(featureUuids, BluetoothUuid.LE_AUDIO)) {
            Log.e(TAG, "Cannot connect to " + device + " : Remote does not have LE_AUDIO UUID");
            return false;
        }

        LeAudioStateMachine sm = null;

        mGroupWriteLock.lock();
        try {
            boolean isInbandRingtoneEnabled = false;
            int groupId = getGroupId(device);
            if (groupId != LE_AUDIO_GROUP_ID_INVALID) {
                isInbandRingtoneEnabled = getGroupDescriptor(groupId).mInbandRingtoneEnabled;
            }

            if (createDeviceDescriptor(device, isInbandRingtoneEnabled) == null) {
                return false;
            }

            sm = getOrCreateStateMachine(device);
            if (sm == null) {
                Log.e(TAG, "Ignored connect request for " + device + " : no state machine");
                return false;
            }

        } finally {
            mGroupWriteLock.unlock();
        }

        sm.sendMessage(LeAudioStateMachine.CONNECT);
        return true;
    }

    /**
     * Disconnects LE Audio for the remote bluetooth device
     *
     * @param device is the device with which we would like to disconnect LE Audio
     * @return true if profile disconnected, false if device not connected over LE Audio
     */
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);

        LeAudioStateMachine sm = null;

        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "disconnect: No valid descriptor for device: " + device);
                return false;
            }
            sm = descriptor.mStateMachine;
        } finally {
            mGroupReadLock.unlock();
        }

        if (sm == null) {
            Log.e(TAG, "Ignored disconnect request for " + device + " : no state machine");
            return false;
        }

        sm.sendMessage(LeAudioStateMachine.DISCONNECT);

        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        mGroupReadLock.lock();
        try {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                LeAudioStateMachine sm = descriptor.mStateMachine;
                if (sm != null && sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    BluetoothDevice getConnectedGroupLeadDevice(int groupId) {
        if (getGroupId(mActiveAudioOutDevice) == groupId) {
            return mActiveAudioOutDevice;
        }

        return getLeadDeviceForTheGroup(groupId);
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
        mGroupReadLock.lock();
        try {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.LE_AUDIO)) {
                    continue;
                }
                int connectionState = BluetoothProfile.STATE_DISCONNECTED;
                LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
                if (descriptor == null) {
                    Log.e(
                            TAG,
                            "getDevicesMatchingConnectionStates: No valid descriptor for device: "
                                    + device);
                    continue;
                }

                LeAudioStateMachine sm = descriptor.mStateMachine;
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
        } finally {
            mGroupReadLock.unlock();
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
        mGroupReadLock.lock();
        try {
            for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                if (descriptor.mStateMachine != null) {
                    devices.add(descriptor.mStateMachine.getDevice());
                }
            }
            return devices;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Get the current connection state of the profile
     *
     * @param device is the remote bluetooth device
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected, {@link
     *     BluetoothProfile#STATE_CONNECTING} if this profile is being connected, {@link
     *     BluetoothProfile#STATE_CONNECTED} if this profile is connected, or {@link
     *     BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    public int getConnectionState(BluetoothDevice device) {
        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            LeAudioStateMachine sm = descriptor.mStateMachine;
            if (sm == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Add device to the given group.
     *
     * @param groupId group ID the device is being added to
     * @param device the active device
     * @return true on success, otherwise false
     */
    boolean groupAddNode(int groupId, BluetoothDevice device) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return false;
        }
        return mNativeInterface.groupAddNode(groupId, device);
    }

    /**
     * Remove device from a given group.
     *
     * @param groupId group ID the device is being removed from
     * @param device the active device
     * @return true on success, otherwise false
     */
    boolean groupRemoveNode(int groupId, BluetoothDevice device) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return false;
        }
        return mNativeInterface.groupRemoveNode(groupId, device);
    }

    /**
     * Checks if given group exists.
     *
     * @param groupId group Id to verify
     * @return true given group exists, otherwise false
     */
    public boolean isValidDeviceGroup(int groupId) {
        mGroupReadLock.lock();
        try {
            return groupId != LE_AUDIO_GROUP_ID_INVALID
                    && mGroupDescriptorsView.containsKey(groupId);
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Get all the devices within a given group.
     *
     * @param groupId group id to get devices
     * @return all devices within a given group or empty list
     */
    public List<BluetoothDevice> getGroupDevices(int groupId) {
        List<BluetoothDevice> result = new ArrayList<>();

        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return result;
        }

        mGroupReadLock.lock();
        try {
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                    mDeviceDescriptors.entrySet()) {
                if (entry.getValue().mGroupId == groupId) {
                    result.add(entry.getKey());
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return result;
    }

    /**
     * Get all the devices within a given group.
     *
     * @param device the device for which we want to get all devices in its group
     * @return all devices within a given group or empty list
     */
    public List<BluetoothDevice> getGroupDevices(BluetoothDevice device) {
        List<BluetoothDevice> result = new ArrayList<>();
        int groupId = getGroupId(device);

        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return result;
        }

        mGroupReadLock.lock();
        try {
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                    mDeviceDescriptors.entrySet()) {
                if (entry.getValue().mGroupId == groupId) {
                    result.add(entry.getKey());
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return result;
    }

    /** Get the active device group id */
    public Integer getActiveGroupId() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor descriptor = entry.getValue();
                if (descriptor.isActive()) {
                    return entry.getKey();
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return LE_AUDIO_GROUP_ID_INVALID;
    }

    /**
     * Creates LeAudio Broadcast instance with BluetoothLeBroadcastSettings.
     *
     * @param broadcastSettings broadcast settings for this broadcast source
     */
    public void createBroadcast(BluetoothLeBroadcastSettings broadcastSettings) {
        if (mBroadcastDescriptors.size() >= getMaximumNumberOfBroadcasts()) {
            Log.w(
                    TAG,
                    "createBroadcast reached maximum allowed broadcasts number: "
                            + getMaximumNumberOfBroadcasts());
            mHandler.post(
                    () ->
                            notifyBroadcastStartFailed(
                                    BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
            return;
        }

        if (mLeAudioBroadcasterNativeInterface == null) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        if (mAwaitingBroadcastCreateResponse) {
            mCreateBroadcastQueue.add(broadcastSettings);
            Log.i(TAG, "Broadcast creation queued due to waiting for a previous request response.");
            return;
        }

        if (!areAllGroupsInNotActiveState()) {
            /* Broadcast would be created once unicast group became inactive */
            Log.i(
                    TAG,
                    "Unicast group is active, queueing Broadcast creation, while the Unicast"
                            + " group is deactivated.");
            mCreateBroadcastQueue.add(broadcastSettings);
            if (Flags.leaudioBroadcastAudioHandoverPolicies()) {
                mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SINK, true);
            }
            removeActiveDevice(true);

            return;
        }

        byte[] broadcastCode = broadcastSettings.getBroadcastCode();
        boolean isEncrypted = (broadcastCode != null) && (broadcastCode.length != 0);
        if (isEncrypted) {
            if ((broadcastCode.length > 16) || (broadcastCode.length < 4)) {
                Log.e(TAG, "Invalid broadcast code length. Should be from 4 to 16 octets long.");
                return;
            }
        }

        List<BluetoothLeBroadcastSubgroupSettings> settingsList =
                broadcastSettings.getSubgroupSettings();
        if (settingsList == null || settingsList.size() < 1) {
            Log.d(TAG, "subgroup settings is not valid value");
            return;
        }

        BluetoothLeAudioContentMetadata publicMetadata =
                broadcastSettings.getPublicBroadcastMetadata();

        Log.i(TAG, "createBroadcast: isEncrypted=" + (isEncrypted ? "true" : "false"));

        mAwaitingBroadcastCreateResponse = true;
        mLeAudioBroadcasterNativeInterface.createBroadcast(
                broadcastSettings.isPublicBroadcast(),
                broadcastSettings.getBroadcastName(),
                broadcastCode,
                publicMetadata == null ? null : publicMetadata.getRawMetadata(),
                getBroadcastAudioQualityPerSinkCapabilities(settingsList),
                settingsList.stream()
                        .map(s -> s.getContentMetadata().getRawMetadata())
                        .toArray(byte[][]::new));
    }

    private int[] getBroadcastAudioQualityPerSinkCapabilities(
            List<BluetoothLeBroadcastSubgroupSettings> settingsList) {
        int[] preferredQualityArray =
                settingsList.stream().mapToInt(s -> s.getPreferredQuality()).toArray();

        BassClientService bassClientService = getBassClientService();
        if (bassClientService == null) {
            return preferredQualityArray;
        }

        for (BluetoothDevice sink : bassClientService.getConnectedDevices()) {
            int groupId = getGroupId(sink);
            if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
                continue;
            }

            BluetoothLeAudioCodecStatus codecStatus = getCodecStatus(groupId);
            if (codecStatus != null
                    && !codecStatus.isInputCodecConfigSelectable(BROADCAST_HIGH_QUALITY_CONFIG)) {
                // If any sink device does not support high quality audio config,
                // set all subgroup audio quality to standard quality for now before multi codec
                // config support is ready
                Log.i(
                        TAG,
                        "Sink device doesn't support HIGH broadcast audio quality, use STANDARD"
                                + " quality");
                Arrays.fill(
                        preferredQualityArray,
                        BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD);
                break;
            }
        }
        return preferredQualityArray;
    }

    /**
     * Start LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    public void startBroadcast(int broadcastId) {
        if (mLeAudioBroadcasterNativeInterface == null) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        Log.d(TAG, "startBroadcast");

        /* Start timeout to recover from stucked/error start Broadcast operation */
        mDialingOutTimeoutEvent = new DialingOutTimeoutEvent(broadcastId);
        mHandler.postDelayed(mDialingOutTimeoutEvent, DIALING_OUT_TIMEOUT_MS);

        mLeAudioBroadcasterNativeInterface.startBroadcast(broadcastId);
    }

    /**
     * Updates LeAudio broadcast instance metadata.
     *
     * @param broadcastId broadcast instance identifier
     * @param broadcastSettings broadcast settings for this broadcast source
     */
    public void updateBroadcast(int broadcastId, BluetoothLeBroadcastSettings broadcastSettings) {
        if (mLeAudioBroadcasterNativeInterface == null) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            mHandler.post(
                    () ->
                            notifyBroadcastUpdateFailed(
                                    broadcastId,
                                    BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_BROADCAST_ID));
            Log.e(TAG, "updateBroadcast: No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        List<BluetoothLeBroadcastSubgroupSettings> settingsList =
                broadcastSettings.getSubgroupSettings();
        if (settingsList == null || settingsList.size() < 1) {
            Log.d(TAG, "subgroup settings is not valid value");
            return;
        }

        BluetoothLeAudioContentMetadata publicMetadata =
                broadcastSettings.getPublicBroadcastMetadata();

        Log.d(TAG, "updateBroadcast");
        mLeAudioBroadcasterNativeInterface.updateMetadata(
                broadcastId,
                broadcastSettings.getBroadcastName(),
                publicMetadata == null ? null : publicMetadata.getRawMetadata(),
                settingsList.stream()
                        .map(s -> s.getContentMetadata().getRawMetadata())
                        .toArray(byte[][]::new));
    }

    /**
     * Pause LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    public void pauseBroadcast(Integer broadcastId) {
        if (mLeAudioBroadcasterNativeInterface == null) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            Log.e(TAG, "pauseBroadcast: No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        if (leaudioBigDependsOnAudioState()) {
            if (isPlaying(broadcastId)) {
                Log.d(TAG, "pauseBroadcast");
                mIsBroadcastPausedFromOutside = true;
                mLeAudioBroadcasterNativeInterface.pauseBroadcast(broadcastId);
            } else if (isPaused(broadcastId)) {
                transitionFromBroadcastToUnicast();
            } else {
                Log.d(TAG, "pauseBroadcast: Broadcast is stopped, skip pause request");
            }
        } else {
            if (leaudioBroadcastAssistantPeripheralEntrustment()) {
                if (!isPlaying(broadcastId)) {
                    Log.d(TAG, "pauseBroadcast: Broadcast is not playing, skip pause request");
                    return;
                }

                // Due to broadcast pause sinks may lose synchronization
                BassClientService bassClientService = getBassClientService();
                if (bassClientService != null) {
                    bassClientService.cacheSuspendingSources(broadcastId);
                }
            }

            Log.d(TAG, "pauseBroadcast");
            mLeAudioBroadcasterNativeInterface.pauseBroadcast(broadcastId);
        }
    }

    /**
     * Stop LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    public void stopBroadcast(Integer broadcastId) {
        if (mLeAudioBroadcasterNativeInterface == null) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            mHandler.post(
                    () ->
                            notifyOnBroadcastStopFailed(
                                    BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_BROADCAST_ID));
            Log.e(TAG, "stopBroadcast: No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        Log.d(TAG, "stopBroadcast");
        mLeAudioBroadcasterNativeInterface.stopBroadcast(broadcastId);
    }

    /**
     * Destroy LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    public void destroyBroadcast(int broadcastId) {
        if (mLeAudioBroadcasterNativeInterface == null) {
            Log.w(TAG, "Native interface not available.");
            return;
        }

        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            mHandler.post(
                    () ->
                            notifyOnBroadcastStopFailed(
                                    BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_BROADCAST_ID));
            Log.e(TAG, "destroyBroadcast: No valid descriptor for broadcastId: " + broadcastId);
            return;
        }

        Log.d(TAG, "destroyBroadcast");
        if (Flags.leaudioBroadcastAudioHandoverPolicies()) {
            mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SINK, false);
        }
        mLeAudioBroadcasterNativeInterface.destroyBroadcast(broadcastId);
    }

    /**
     * Checks if Broadcast instance is playing.
     *
     * @param broadcastId broadcast instance identifier
     * @return true if if broadcast is playing, false otherwise
     */
    public boolean isPlaying(int broadcastId) {
        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            Log.e(TAG, "isPlaying: No valid descriptor for broadcastId: " + broadcastId);
            return false;
        }

        return (descriptor.mState.equals(LeAudioStackEvent.BROADCAST_STATE_STREAMING));
    }

    /**
     * Checks if Broadcast instance is paused.
     *
     * @param broadcastId broadcast instance identifier
     * @return true if if broadcast is paused, false otherwise
     */
    public boolean isPaused(int broadcastId) {
        LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
        if (descriptor == null) {
            Log.e(TAG, "isPaused: No valid descriptor for broadcastId: " + broadcastId);
            return false;
        }

        return (descriptor.mState.equals(LeAudioStackEvent.BROADCAST_STATE_PAUSED));
    }

    /**
     * Get all broadcast metadata.
     *
     * @return list of all know Broadcast metadata
     */
    public List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata() {
        return mBroadcastDescriptors.values().stream()
                .map(s -> s.mMetadata)
                .collect(Collectors.toList());
    }

    /**
     * Check if broadcast is active
     *
     * @return true if there is active broadcast, false otherwise
     */
    public boolean isBroadcastActive() {
        return !mBroadcastDescriptors.isEmpty();
    }

    /**
     * Get the maximum number of supported simultaneous broadcasts.
     *
     * @return number of supported simultaneous broadcasts
     */
    public int getMaximumNumberOfBroadcasts() {
        /* TODO: This is currently fixed to 1 */
        return 1;
    }

    /**
     * Get the maximum number of supported streams per broadcast.
     *
     * @return number of supported streams per broadcast
     */
    public int getMaximumStreamsPerBroadcast() {
        /* TODO: This is currently fixed to 1 */
        return 1;
    }

    /**
     * Get the maximum number of supported subgroups per broadcast.
     *
     * @return number of supported subgroups per broadcast
     */
    public int getMaximumSubgroupsPerBroadcast() {
        /* TODO: This is currently fixed to 1 */
        return 1;
    }

    /** Active Broadcast Assistant notification handler */
    public void activeBroadcastAssistantNotification(boolean active) {
        if (getBassClientService() == null) {
            Log.w(TAG, "Ignore active Broadcast Assistant notification");
            return;
        }

        if (active) {
            mIsSourceStreamMonitorModeEnabled = true;
            mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SOURCE, true);
        } else {
            if (mIsSourceStreamMonitorModeEnabled) {
                mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SOURCE, false);
            }

            mIsSourceStreamMonitorModeEnabled = false;
        }
    }

    /** Return true if device is primary - is active or was active before switch to broadcast */
    public boolean isPrimaryDevice(BluetoothDevice device) {
        LeAudioDeviceDescriptor descriptor = mDeviceDescriptors.get(device);
        if (descriptor == null) {
            return false;
        }

        return descriptor.mGroupId == mUnicastGroupIdDeactivatedForBroadcastTransition;
    }

    /** Return true if group is primary - is active or was active before switch to broadcast */
    private boolean isPrimaryGroup(int groupId) {
        return groupId != IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID
                && groupId == mUnicastGroupIdDeactivatedForBroadcastTransition;
    }

    private boolean areBroadcastsAllStopped() {
        if (mBroadcastDescriptors == null) {
            Log.e(TAG, "areBroadcastsAllStopped: Invalid Broadcast Descriptors");
            return false;
        }

        return mBroadcastDescriptors.values().stream()
                .allMatch(d -> d.mState.equals(LeAudioStackEvent.BROADCAST_STATE_STOPPED));
    }

    private Optional<Integer> getFirstNotStoppedBroadcastId() {
        if (mBroadcastDescriptors == null) {
            Log.e(TAG, "getFirstNotStoppedBroadcastId: Invalid Broadcast Descriptors");
            return Optional.empty();
        }

        for (Map.Entry<Integer, LeAudioBroadcastDescriptor> entry :
                mBroadcastDescriptors.entrySet()) {
            if (!entry.getValue().mState.equals(LeAudioStackEvent.BROADCAST_STATE_STOPPED)) {
                return Optional.of(entry.getKey());
            }
        }

        return Optional.empty();
    }

    private boolean areAllGroupsInNotActiveState() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor descriptor = entry.getValue();
                if (!descriptor.isInactive()) {
                    return false;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return true;
    }

    private boolean areAllGroupsInNotGettingActiveState() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor descriptor = entry.getValue();
                if (descriptor.isGettingActive()) {
                    return false;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return true;
    }

    private Integer getFirstGroupIdInGettingActiveState() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> entry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor descriptor = entry.getValue();
                if (descriptor.isGettingActive()) {
                    return entry.getKey();
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return LE_AUDIO_GROUP_ID_INVALID;
    }

    private BluetoothDevice getLeadDeviceForTheGroup(Integer groupId) {
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return null;
        }
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "Group " + groupId + " does not exist");
                return null;
            }

            if (groupDescriptor.mCurrentLeadDevice != null
                    && getConnectionState(groupDescriptor.mCurrentLeadDevice)
                            == BluetoothProfile.STATE_CONNECTED) {
                return groupDescriptor.mCurrentLeadDevice;
            }

            for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                if (!descriptor.mGroupId.equals(groupId)) {
                    continue;
                }

                LeAudioStateMachine sm = descriptor.mStateMachine;
                if (sm == null || sm.getConnectionState() != BluetoothProfile.STATE_CONNECTED) {
                    continue;
                }
                groupDescriptor.mCurrentLeadDevice = sm.getDevice();
                return groupDescriptor.mCurrentLeadDevice;
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return null;
    }

    private boolean updateActiveInDevice(
            BluetoothDevice device,
            Integer groupId,
            Integer oldSupportedAudioDirections,
            Integer newSupportedAudioDirections) {
        boolean oldSupportedByDeviceInput =
                (oldSupportedAudioDirections & AUDIO_DIRECTION_INPUT_BIT) != 0;
        boolean newSupportedByDeviceInput =
                (newSupportedAudioDirections & AUDIO_DIRECTION_INPUT_BIT) != 0;

        /*
         * Do not update input if neither previous nor current device support input
         */
        if (!oldSupportedByDeviceInput && !newSupportedByDeviceInput) {
            Log.d(TAG, "updateActiveInDevice: Device does not support input.");
            return false;
        }

        if (device != null && mActiveAudioInDevice != null) {
            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
            if (deviceDescriptor == null) {
                Log.e(TAG, "updateActiveInDevice: No valid descriptor for device: " + device);
                return false;
            }

            if (deviceDescriptor.mGroupId.equals(groupId)) {
                /* This is the same group as already notified to the system.
                 * Therefore do not change the device we have connected to the group,
                 * unless, previous one is disconnected now
                 */
                if (mAdapterService.isConnected(mActiveAudioInDevice)) {
                    device = mActiveAudioInDevice;
                }
            } else if (deviceDescriptor.mGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                /* Mark old group as no active */
                LeAudioGroupDescriptor descriptor = getGroupDescriptor(deviceDescriptor.mGroupId);
                if (descriptor != null) {
                    descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                }
            }
        }

        BluetoothDevice previousInDevice = mActiveAudioInDevice;

        /*
         * Update input if:
         * - Device changed
         *     OR
         * - Device stops / starts supporting input
         */
        if (!Objects.equals(device, previousInDevice)
                || (oldSupportedByDeviceInput != newSupportedByDeviceInput)) {
            mActiveAudioInDevice = newSupportedByDeviceInput ? device : null;
            Log.d(
                    TAG,
                    " handleBluetoothActiveDeviceChanged previousInDevice: "
                            + previousInDevice
                            + ", mActiveAudioInDevice: "
                            + mActiveAudioInDevice
                            + " isLeOutput: false");

            return true;
        }
        Log.d(TAG, "updateActiveInDevice: Nothing to do.");
        return false;
    }

    private boolean updateActiveOutDevice(
            BluetoothDevice device,
            Integer groupId,
            Integer oldSupportedAudioDirections,
            Integer newSupportedAudioDirections) {
        boolean oldSupportedByDeviceOutput =
                (oldSupportedAudioDirections & AUDIO_DIRECTION_OUTPUT_BIT) != 0;
        boolean newSupportedByDeviceOutput =
                (newSupportedAudioDirections & AUDIO_DIRECTION_OUTPUT_BIT) != 0;

        /*
         * Do not update output if neither previous nor current device support output
         */
        if (!oldSupportedByDeviceOutput && !newSupportedByDeviceOutput) {
            Log.d(TAG, "updateActiveOutDevice: Device does not support output.");
            return false;
        }

        if (device != null && mActiveAudioOutDevice != null) {
            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
            if (deviceDescriptor == null) {
                Log.e(TAG, "updateActiveOutDevice: No valid descriptor for device: " + device);
                return false;
            }

            if (deviceDescriptor.mGroupId.equals(groupId)) {
                /* This is the same group as already notified to the system.
                 * Therefore do not change the device we have connected to the group,
                 * unless, previous one is disconnected now
                 */
                if (mAdapterService.getConnectionState(mActiveAudioOutDevice)
                        != BluetoothDevice.CONNECTION_STATE_DISCONNECTED) {
                    device = mActiveAudioOutDevice;
                }
            } else if (deviceDescriptor.mGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                Log.i(
                        TAG,
                        " Switching active group from "
                                + deviceDescriptor.mGroupId
                                + " to "
                                + groupId);
                /* Mark old group as no active */
                LeAudioGroupDescriptor descriptor = getGroupDescriptor(deviceDescriptor.mGroupId);
                if (descriptor != null) {
                    descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                }
            }
        }

        BluetoothDevice previousOutDevice = mActiveAudioOutDevice;

        /*
         * Update output if:
         * - Device changed
         *     OR
         * - Device stops / starts supporting output
         */
        if (!Objects.equals(device, previousOutDevice)
                || (oldSupportedByDeviceOutput != newSupportedByDeviceOutput)) {
            mActiveAudioOutDevice = newSupportedByDeviceOutput ? device : null;
            Log.d(
                    TAG,
                    " handleBluetoothActiveDeviceChanged previousOutDevice: "
                            + previousOutDevice
                            + ", mActiveAudioOutDevice: "
                            + mActiveAudioOutDevice
                            + " isLeOutput: true");
            return true;
        }
        Log.d(TAG, "updateActiveOutDevice: Nothing to do.");
        return false;
    }

    /**
     * Send broadcast intent about LeAudio connection state changed. This is called by
     * LeAudioStateMachine.
     */
    void notifyConnectionStateChanged(BluetoothDevice device, int newState, int prevState) {
        Log.d(
                TAG,
                "Notify connection state changed."
                        + device
                        + "("
                        + prevState
                        + " -> "
                        + newState
                        + ")");

        mAdapterService.notifyProfileConnectionStateChangeToGatt(
                BluetoothProfile.LE_AUDIO, prevState, newState);
        mAdapterService.handleProfileConnectionStateChange(
                BluetoothProfile.LE_AUDIO, device, prevState, newState);
        mAdapterService
                .getActiveDeviceManager()
                .profileConnectionStateChanged(
                        BluetoothProfile.LE_AUDIO, device, prevState, newState);
        mAdapterService.updateProfileConnectionAdapterProperties(
                device, BluetoothProfile.LE_AUDIO, newState, prevState);

        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
    }

    void sentActiveDeviceChangeIntent(BluetoothDevice device) {
        Intent intent = new Intent(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcastWithMultiplePermissions(
                intent, new String[] {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED});
    }

    void notifyVolumeControlServiceAboutActiveGroup(BluetoothDevice device) {
        VolumeControlService volumeControlService = getVolumeControlService();
        if (volumeControlService == null) {
            return;
        }

        if (mExposedActiveDevice != null) {
            volumeControlService.setGroupActive(getGroupId(mExposedActiveDevice), false);
        }

        if (device != null) {
            volumeControlService.setGroupActive(getGroupId(device), true);
        }
    }

    /**
     * Send broadcast intent about LeAudio active device. This is called when AudioManager confirms,
     * LeAudio device is added or removed.
     */
    @VisibleForTesting
    void notifyActiveDeviceChanged(BluetoothDevice device) {
        Log.d(
                TAG,
                "Notify Active device changed."
                        + device
                        + ". Currently active device is "
                        + mActiveAudioOutDevice);

        mAdapterService.handleActiveDeviceChange(BluetoothProfile.LE_AUDIO, device);
        sentActiveDeviceChangeIntent(device);
        notifyVolumeControlServiceAboutActiveGroup(device);
        mExposedActiveDevice = device;
    }

    boolean isScannerNeeded() {
        if (mDeviceDescriptors.isEmpty() || !mBluetoothEnabled) {
            Log.d(TAG, "isScannerNeeded: false, mBluetoothEnabled: " + mBluetoothEnabled);
            return false;
        }

        if (allLeAudioDevicesConnected()) {
            Log.d(TAG, "isScannerNeeded: all devices connected, scanner not needed");
            return false;
        }

        Log.d(TAG, "isScannerNeeded: true");
        return true;
    }

    boolean allLeAudioDevicesConnected() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> deviceEntry :
                    mDeviceDescriptors.entrySet()) {
                LeAudioDeviceDescriptor deviceDescriptor = deviceEntry.getValue();

                if (deviceDescriptor.mStateMachine == null) {
                    /* Lack of state machine means device is not connected */
                    return false;
                }

                if (!deviceDescriptor.mStateMachine.isConnected()
                        || !deviceDescriptor.mAclConnected) {
                    return false;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
        return true;
    }

    private class AudioServerScanCallback2 extends IScannerCallback.Stub {
        // See BluetoothLeScanner.BleScanCallbackWrapper.mScannerId
        int mScannerId = 0;

        synchronized void startBackgroundScan() {
            if (mScannerId != 0) {
                Log.d(TAG, "Scanner is already registered with id " + mScannerId);
                return;
            }
            if (Flags.scanManagerRefactor()) {
                mAdapterService
                        .getBluetoothScanController()
                        .getTransitionalScanHelper()
                        .registerScannerInternal(this, null);
            } else {
                mAdapterService
                        .getBluetoothGattService()
                        .getTransitionalScanHelper()
                        .registerScannerInternal(this, null);
            }
        }

        synchronized void stopBackgroundScan() {
            if (mScannerId == 0) {
                Log.d(TAG, "Scanner is already unregistered");
                return;
            }
            if (Flags.scanManagerRefactor()) {
                mAdapterService
                        .getBluetoothScanController()
                        .getTransitionalScanHelper()
                        .stopScanInternal(mScannerId);

                mAdapterService
                        .getBluetoothScanController()
                        .getTransitionalScanHelper()
                        .unregisterScannerInternal(mScannerId);
            } else {
                mAdapterService
                        .getBluetoothGattService()
                        .getTransitionalScanHelper()
                        .stopScanInternal(mScannerId);

                mAdapterService
                        .getBluetoothGattService()
                        .getTransitionalScanHelper()
                        .unregisterScannerInternal(mScannerId);
            }
            mScannerId = 0;
        }

        @Override
        public synchronized void onScannerRegistered(int status, int scannerId) {
            mScannerId = scannerId;

            /* Filter we are building here will not match to anything.
             * Eventually we should be able to start scan from native when
             * b/276350722 is done
             */
            ScanFilter filter =
                    new ScanFilter.Builder()
                            .setServiceData(BluetoothUuid.LE_AUDIO, new byte[] {0x11})
                            .build();

            ScanSettings settings =
                    new ScanSettings.Builder()
                            .setLegacy(false)
                            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                            .setPhy(BluetoothDevice.PHY_LE_1M)
                            .build();

            if (Flags.scanManagerRefactor()) {
                mAdapterService
                        .getBluetoothScanController()
                        .getTransitionalScanHelper()
                        .startScanInternal(scannerId, settings, List.of(filter));
            } else {
                mAdapterService
                        .getBluetoothGattService()
                        .getTransitionalScanHelper()
                        .startScanInternal(scannerId, settings, List.of(filter));
            }
        }

        // Eventually we should be able to start scan from native when b/276350722 is done
        // All the result returned here are ignored
        @Override
        public void onScanResult(ScanResult scanResult) {}

        @Override
        public void onBatchScanResults(List<ScanResult> batchResults) {}

        @Override
        public void onFoundOrLost(boolean onFound, ScanResult scanResult) {}

        @Override
        public void onScanManagerErrorCallback(int errorCode) {}
    }

    private class AudioServerScanCallback extends ScanCallback {
        int mMaxScanRetries = 10;
        int mScanRetries = 0;

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            /* Filter is set in the way, that there will be no results found.
             * We just need a scanner to be running for the APCF filtering defined in native
             */
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            /* Filter is set in the way, that there will be no results found.
             * We just need a scanner to be running for the APCF filtering defined in native
             */
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "Scan failed err: " + errorCode + " scan retries: " + mScanRetries);
            switch (errorCode) {
                case SCAN_FAILED_INTERNAL_ERROR:
                case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    if (mScanRetries < mMaxScanRetries) {
                        mScanRetries++;
                        Log.w(TAG, "Failed to start. Let's retry");
                        mHandler.post(() -> startAudioServersBackgroundScan(/* retry= */ true));
                    }
                    break;
                default:
                    /* Indicate scan is no running */
                    mScanCallback = null;
                    break;
            }
        }
    }

    @VisibleForTesting
    boolean handleAudioDeviceAdded(
            BluetoothDevice device, int type, boolean isSink, boolean isSource) {
        Log.d(
                TAG,
                (" handleAudioDeviceAdded: " + device)
                        + (", device type: " + type)
                        + (", isSink: " + isSink)
                        + (" isSource: " + isSource));

        /* Don't expose already exposed active device */
        if (device.equals(mExposedActiveDevice)) {
            Log.d(TAG, " onAudioDevicesAdded: " + device + " is already exposed");
            return true;
        }

        if ((isSink && !device.equals(mActiveAudioOutDevice))
                || (isSource && !device.equals(mActiveAudioInDevice))) {
            Log.e(
                    TAG,
                    "Added device does not match to the one activated here. ("
                            + (device
                                    + " != "
                                    + mActiveAudioOutDevice
                                    + " / "
                                    + mActiveAudioInDevice
                                    + ")"));
            return false;
        }

        notifyActiveDeviceChanged(device);
        return true;
    }

    @VisibleForTesting
    void handleAudioDeviceRemoved(
            BluetoothDevice device, int type, boolean isSink, boolean isSource) {
        Log.d(
                TAG,
                (" handleAudioDeviceRemoved: " + device)
                        + (", device type: " + type)
                        + (", isSink: " + isSink)
                        + (" isSource: " + isSource)
                        + (", mActiveAudioInDevice: " + mActiveAudioInDevice)
                        + (", mActiveAudioOutDevice: " + mActiveAudioOutDevice));

        if (!device.equals(mExposedActiveDevice)) {
            return;
        }

        if ((isSource && mActiveAudioInDevice == null)
                || (isSink && mActiveAudioOutDevice == null)) {
            Log.d(TAG, "Expecting device removal");
            if (mActiveAudioInDevice == null && mActiveAudioOutDevice == null) {
                mExposedActiveDevice = null;
            }
            return;
        }

        Log.i(TAG, "Audio manager disactivate LeAudio device " + mExposedActiveDevice);
        mExposedActiveDevice = null;
        setActiveDevice(null);
    }

    /* Notifications of audio device connection/disconn events. */
    private class AudioManagerAudioDeviceCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (mAudioManager == null || mAdapterService == null) {
                Log.e(TAG, "Callback called when LeAudioService is stopped");
                return;
            }

            for (AudioDeviceInfo deviceInfo : addedDevices) {
                if ((deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_HEADSET)
                        && (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
                    continue;
                }

                String address = deviceInfo.getAddress();
                if (address.equals("00:00:00:00:00:00")) {
                    continue;
                }

                byte[] addressBytes = Utils.getBytesFromAddress(address);
                BluetoothDevice device = mAdapterService.getDeviceFromByte(addressBytes);

                if (handleAudioDeviceAdded(
                        device, deviceInfo.getType(), deviceInfo.isSink(), deviceInfo.isSource())) {
                    return;
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (mAudioManager == null || mAdapterService == null) {
                Log.e(TAG, "Callback called when LeAudioService is stopped");
                return;
            }

            for (AudioDeviceInfo deviceInfo : removedDevices) {
                if ((deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_HEADSET)
                        && (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
                    continue;
                }

                String address = deviceInfo.getAddress();
                if (address.equals("00:00:00:00:00:00")) {
                    continue;
                }

                byte[] addressBytes = Utils.getBytesFromAddress(address);
                BluetoothDevice device = mAdapterService.getDeviceFromByte(addressBytes);

                handleAudioDeviceRemoved(
                        device, deviceInfo.getType(), deviceInfo.isSink(), deviceInfo.isSource());
            }
        }
    }

    /*
     * Report the active broadcast device change to the active device manager and the media
     * framework.
     * @param newDevice new supported broadcast audio device
     * @param previousDevice previous no longer supported broadcast audio device
     */
    private void updateBroadcastActiveDevice(
            BluetoothDevice newDevice,
            BluetoothDevice previousDevice,
            boolean suppressNoisyIntent) {
        mActiveBroadcastAudioDevice = newDevice;
        Log.d(
                TAG,
                "updateBroadcastActiveDevice: newDevice: "
                        + newDevice
                        + ", previousDevice: "
                        + previousDevice);
        mAudioManager.handleBluetoothActiveDeviceChanged(
                newDevice, previousDevice, getBroadcastProfile(suppressNoisyIntent));
    }

    /*
     * Listen mode is set when broadcast is queued, waiting for create response notification or
     * descriptor was created - idicate that create notification was received.
     */
    private boolean wasSetSinkListeningMode() {
        return !mCreateBroadcastQueue.isEmpty()
                || mAwaitingBroadcastCreateResponse
                || !mBroadcastDescriptors.isEmpty();
    }

    /**
     * Report the active devices change to the active device manager and the media framework.
     *
     * @param groupId id of group which devices should be updated
     * @param newSupportedAudioDirections new supported audio directions for group of devices
     * @param oldSupportedAudioDirections old supported audio directions for group of devices
     * @param isActive if there is new active group
     * @param hasFallbackDevice whether any fallback device exists when deactivating the current
     *     active device.
     * @param notifyAndUpdateInactiveOutDeviceOnly if only output device should be updated to
     *     inactive devices (if new out device would be null device).
     * @return true if group is active after change false otherwise.
     */
    private boolean updateActiveDevices(
            Integer groupId,
            Integer oldSupportedAudioDirections,
            Integer newSupportedAudioDirections,
            boolean isActive,
            boolean hasFallbackDevice,
            boolean notifyAndUpdateInactiveOutDeviceOnly) {
        BluetoothDevice newOutDevice = null;
        BluetoothDevice newInDevice = null;
        BluetoothDevice previousActiveOutDevice = mActiveAudioOutDevice;
        BluetoothDevice previousActiveInDevice = mActiveAudioInDevice;

        if (isActive) {
            newOutDevice = getLeadDeviceForTheGroup(groupId);
            newInDevice = newOutDevice;
        } else {
            /* While broadcasting a input device needs to be connected to track Audio Framework
             * streaming requests. This would allow native to make a fallback to Unicast decision.
             */
            if (notifyAndUpdateInactiveOutDeviceOnly
                    && ((newSupportedAudioDirections & AUDIO_DIRECTION_INPUT_BIT) != 0)) {
                newInDevice = getLeadDeviceForTheGroup(groupId);
            } else if (Flags.leaudioBroadcastAudioHandoverPolicies() && wasSetSinkListeningMode()) {
                mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SINK, false);
            }
        }

        boolean isNewActiveOutDevice =
                updateActiveOutDevice(
                        newOutDevice,
                        groupId,
                        oldSupportedAudioDirections,
                        newSupportedAudioDirections);
        boolean isNewActiveInDevice =
                updateActiveInDevice(
                        newInDevice,
                        groupId,
                        oldSupportedAudioDirections,
                        newSupportedAudioDirections);

        Log.d(
                TAG,
                " isNewActiveOutDevice: "
                        + isNewActiveOutDevice
                        + ", "
                        + mActiveAudioOutDevice
                        + ", isNewActiveInDevice: "
                        + isNewActiveInDevice
                        + ", "
                        + mActiveAudioInDevice
                        + ", notifyAndUpdateInactiveOutDeviceOnly: "
                        + notifyAndUpdateInactiveOutDeviceOnly);

        if (isNewActiveOutDevice) {
            int volume = IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;

            if (mActiveAudioOutDevice != null) {
                volume = getAudioDeviceGroupVolume(groupId);
            }

            final boolean suppressNoisyIntent = hasFallbackDevice || mActiveAudioOutDevice != null;

            Log.d(
                    TAG,
                    "suppressNoisyIntent: "
                            + suppressNoisyIntent
                            + ", hasFallbackDevice: "
                            + hasFallbackDevice);
            final BluetoothProfileConnectionInfo connectionInfo;
            if (isAtLeastU()) {
                connectionInfo =
                        BluetoothProfileConnectionInfo.createLeAudioOutputInfo(
                                suppressNoisyIntent, volume);
            } else {
                connectionInfo =
                        BluetoothProfileConnectionInfo.createLeAudioInfo(suppressNoisyIntent, true);
            }
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioOutDevice, previousActiveOutDevice, connectionInfo);
        }

        if (isNewActiveInDevice) {
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioInDevice,
                    previousActiveInDevice,
                    BluetoothProfileConnectionInfo.createLeAudioInfo(false, false));
        }

        if ((mActiveAudioOutDevice == null)
                && (notifyAndUpdateInactiveOutDeviceOnly || (mActiveAudioInDevice == null))) {
            /* Notify about inactive device as soon as possible.
             * When adding new device, wait with notification until AudioManager is ready
             * with adding the device.
             */
            notifyActiveDeviceChanged(null);
        }

        return mActiveAudioOutDevice != null || mActiveAudioInDevice != null;
    }

    private void clearInactiveDueToContextTypeFlags() {
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> groupEntry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor groupDescriptor = groupEntry.getValue();
                if (groupDescriptor.mInactivatedDueToContextType) {
                    Log.d(TAG, "clearInactiveDueToContextTypeFlags " + groupEntry.getKey());
                    groupDescriptor.mInactivatedDueToContextType = false;
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Set the active device group.
     *
     * @param hasFallbackDevice hasFallbackDevice whether any fallback device exists when {@code
     *     device} is null.
     */
    private boolean setActiveGroupWithDevice(BluetoothDevice device, boolean hasFallbackDevice) {
        int groupId = LE_AUDIO_GROUP_ID_INVALID;

        if (device != null) {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "setActiveGroupWithDevice: No valid descriptor for device: " + device);
                return false;
            }

            groupId = descriptor.mGroupId;

            if (!isGroupAvailableForStream(groupId)) {
                Log.e(
                        TAG,
                        "setActiveGroupWithDevice: groupId "
                                + groupId
                                + " is not available for streaming");
                return false;
            }

            clearInactiveDueToContextTypeFlags();
        }

        int currentlyActiveGroupId = getActiveGroupId();
        Log.d(
                TAG,
                "setActiveGroupWithDevice = "
                        + groupId
                        + ", currentlyActiveGroupId = "
                        + currentlyActiveGroupId
                        + ", device: "
                        + device
                        + ", hasFallbackDevice: "
                        + hasFallbackDevice
                        + ", mExposedActiveDevice: "
                        + mExposedActiveDevice);

        LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(currentlyActiveGroupId);
        if (isBroadcastActive()
                && currentlyActiveGroupId == LE_AUDIO_GROUP_ID_INVALID
                && mUnicastGroupIdDeactivatedForBroadcastTransition != LE_AUDIO_GROUP_ID_INVALID) {
            // If broadcast is ongoing and need to update unicast fallback active group
            // we need to update the cached group id and skip changing the active device
            updateFallbackUnicastGroupIdForBroadcast(groupId);

            /* In case of removing fallback unicast group, monitoring input device should be
             * removed from active devices.
             */
            if (groupDescriptor != null && groupId == LE_AUDIO_GROUP_ID_INVALID) {
                updateActiveDevices(
                        groupId,
                        groupDescriptor.mDirection,
                        AUDIO_DIRECTION_NONE,
                        false,
                        groupDescriptor.mHasFallbackDeviceWhenGettingInactive,
                        false);
            }

            return true;
        }

        if (groupDescriptor != null && groupId == currentlyActiveGroupId) {
            /* Make sure active group is already exposed to audio framework.
             * If not, lets wait for it and don't sent additional intent.
             */
            if (groupDescriptor.mCurrentLeadDevice == mExposedActiveDevice) {
                Log.w(
                        TAG,
                        "group is already active: device="
                                + device
                                + ", groupId = "
                                + groupId
                                + ", exposedDevice: "
                                + mExposedActiveDevice);
                sentActiveDeviceChangeIntent(mExposedActiveDevice);
            }
            return true;
        }

        if (currentlyActiveGroupId != LE_AUDIO_GROUP_ID_INVALID
                && (groupId != LE_AUDIO_GROUP_ID_INVALID || hasFallbackDevice)) {
            Log.i(TAG, "Remember that device has FallbackDevice when become inactive active");
            groupDescriptor.mHasFallbackDeviceWhenGettingInactive = true;
        }

        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return false;
        }

        if (Flags.leaudioGettingActiveStateSupport()) {
            mGroupReadLock.lock();
            try {
                LeAudioGroupDescriptor descriptor = mGroupDescriptorsView.get(groupId);
                if (descriptor != null) {
                    descriptor.setActiveState(ACTIVE_STATE_GETTING_ACTIVE);
                }
            } finally {
                mGroupReadLock.unlock();
            }
        }

        mNativeInterface.groupSetActive(groupId);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            /* Native will clear its states and send us group Inactive.
             * However we would like to notify audio framework that LeAudio is not
             * active anymore and does not want to get more audio data.
             */
            handleGroupTransitToInactive(currentlyActiveGroupId);
        }
        return true;
    }

    /**
     * Remove the current active group.
     *
     * @param hasFallbackDevice whether any fallback device exists when deactivating the current
     *     active device.
     * @return true on success, otherwise false
     */
    public boolean removeActiveDevice(boolean hasFallbackDevice) {
        /* Clear active group */
        Log.d(TAG, "removeActiveDevice, hasFallbackDevice " + hasFallbackDevice);
        setActiveGroupWithDevice(null, hasFallbackDevice);
        return true;
    }

    /**
     * Set the active group represented by device.
     *
     * @param device the new active device. Should not be null.
     * @return true on success, otherwise false
     */
    public boolean setActiveDevice(BluetoothDevice device) {
        Log.i(
                TAG,
                "setActiveDevice: device="
                        + device
                        + ", current out="
                        + mActiveAudioOutDevice
                        + ", current in="
                        + mActiveAudioInDevice);
        /* Clear active group */
        if (device == null) {
            Log.e(TAG, "device should not be null!");
            return removeActiveDevice(false);
        }
        if (getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
            Log.e(
                    TAG,
                    "setActiveDevice("
                            + device
                            + "): failed because group device is not "
                            + "connected");
            return false;
        }

        if (!Flags.audioRoutingCentralization()) {
            // If AUDIO_ROUTING_CENTRALIZATION, this will be checked inside AudioRoutingManager.
            if (Utils.isDualModeAudioEnabled()) {
                if (!mAdapterService.isAllSupportedClassicAudioProfilesActive(device)) {
                    Log.e(
                            TAG,
                            "setActiveDevice("
                                    + device
                                    + "): failed because the device is not "
                                    + "active for all supported classic audio profiles");
                    return false;
                }
            }
        }
        return setActiveGroupWithDevice(device, false);
    }

    /**
     * Get the active LE audio devices.
     *
     * <p>Note: When LE audio group is active, one of the Bluetooth device address which belongs to
     * the group, represents the active LE audio group - it is called Lead device. Internally, this
     * address is translated to LE audio group id.
     *
     * @return List of active group members. First element is a Lead device.
     */
    public List<BluetoothDevice> getActiveDevices() {
        Log.d(TAG, "getActiveDevices");
        ArrayList<BluetoothDevice> activeDevices = new ArrayList<>(2);
        activeDevices.add(null);
        activeDevices.add(null);

        int currentlyActiveGroupId = getActiveGroupId();
        if (currentlyActiveGroupId == LE_AUDIO_GROUP_ID_INVALID) {
            return activeDevices;
        }

        BluetoothDevice leadDevice = getConnectedGroupLeadDevice(currentlyActiveGroupId);
        activeDevices.set(0, leadDevice);

        int i = 1;
        for (BluetoothDevice dev : getGroupDevices(currentlyActiveGroupId)) {
            if (Objects.equals(dev, leadDevice)) {
                continue;
            }
            if (i == 1) {
                /* Already has a spot for first member */
                activeDevices.set(i++, dev);
            } else {
                /* Extend list with other members */
                activeDevices.add(dev);
            }
        }
        return activeDevices;
    }

    void connectSet(BluetoothDevice device) {
        LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
        if (descriptor == null) {
            Log.e(TAG, "connectSet: No valid descriptor for device: " + device);
            return;
        }
        if (descriptor.mGroupId == LE_AUDIO_GROUP_ID_INVALID) {
            return;
        }

        Log.d(TAG, "connect() others from group id: " + descriptor.mGroupId);

        Integer setGroupId = descriptor.mGroupId;

        for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                mDeviceDescriptors.entrySet()) {
            BluetoothDevice storedDevice = entry.getKey();
            descriptor = entry.getValue();
            if (device.equals(storedDevice)) {
                continue;
            }

            if (!descriptor.mGroupId.equals(setGroupId)) {
                continue;
            }

            Log.d(TAG, "connect(): " + storedDevice);

            mGroupReadLock.lock();
            try {
                LeAudioStateMachine sm = getOrCreateStateMachine(storedDevice);
                if (sm == null) {
                    Log.e(
                            TAG,
                            "Ignored connect request for " + storedDevice + " : no state machine");
                    continue;
                }
                sm.sendMessage(LeAudioStateMachine.CONNECT);
            } finally {
                mGroupReadLock.unlock();
            }
        }
    }

    BluetoothProfileConnectionInfo getBroadcastProfile(boolean suppressNoisyIntent) {
        Parcel parcel = Parcel.obtain();
        parcel.writeInt(BluetoothProfile.LE_AUDIO_BROADCAST);
        parcel.writeBoolean(suppressNoisyIntent);
        parcel.writeInt(-1 /* mVolume */);
        parcel.writeBoolean(true /* mIsLeOutput */);
        parcel.setDataPosition(0);

        BluetoothProfileConnectionInfo profileInfo =
                BluetoothProfileConnectionInfo.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return profileInfo;
    }

    private void clearLostDevicesWhileStreaming(LeAudioGroupDescriptor descriptor) {
        mGroupReadLock.lock();
        try {
            Log.d(TAG, "Clearing lost dev: " + descriptor.mLostLeadDeviceWhileStreaming);

            LeAudioDeviceDescriptor deviceDescriptor =
                    getDeviceDescriptor(descriptor.mLostLeadDeviceWhileStreaming);
            if (deviceDescriptor == null) {
                Log.e(
                        TAG,
                        "clearLostDevicesWhileStreaming: No valid descriptor for device: "
                                + descriptor.mLostLeadDeviceWhileStreaming);
                return;
            }

            LeAudioStateMachine sm = deviceDescriptor.mStateMachine;
            if (sm != null) {
                LeAudioStackEvent stackEvent =
                        new LeAudioStackEvent(
                                LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
                stackEvent.device = descriptor.mLostLeadDeviceWhileStreaming;
                stackEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
                sm.sendMessage(LeAudioStateMachine.STACK_EVENT, stackEvent);
            }
            descriptor.mLostLeadDeviceWhileStreaming = null;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private void handleDeviceHealthAction(BluetoothDevice device, int action) {
        Log.d(
                TAG,
                "handleDeviceHealthAction: device: "
                        + device
                        + " action: "
                        + action
                        + ", not implemented");
        if (action == LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_DISABLE) {
            MetricsLogger.getInstance()
                    .count(
                            mAdapterService.isLeAudioAllowed(device)
                                    ? BluetoothProtoEnums
                                            .LE_AUDIO_ALLOWLIST_DEVICE_HEALTH_STATUS_BAD
                                    : BluetoothProtoEnums
                                            .LE_AUDIO_NONALLOWLIST_DEVICE_HEALTH_STATUS_BAD,
                            1);
        }
    }

    private void handleGroupHealthAction(int groupId, int action) {
        Log.d(
                TAG,
                "handleGroupHealthAction: groupId: "
                        + groupId
                        + " action: "
                        + action
                        + ", not implemented");
        BluetoothDevice device = getLeadDeviceForTheGroup(groupId);
        switch (action) {
            case LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_DISABLE:
                MetricsLogger.getInstance()
                        .count(
                                mAdapterService.isLeAudioAllowed(device)
                                        ? BluetoothProtoEnums
                                                .LE_AUDIO_ALLOWLIST_GROUP_HEALTH_STATUS_BAD
                                        : BluetoothProtoEnums
                                                .LE_AUDIO_NONALLOWLIST_GROUP_HEALTH_STATUS_BAD,
                                1);
                break;
            case LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_CONSIDER_DISABLING:
                MetricsLogger.getInstance()
                        .count(
                                mAdapterService.isLeAudioAllowed(device)
                                        ? BluetoothProtoEnums
                                                .LE_AUDIO_ALLOWLIST_GROUP_HEALTH_STATUS_TRENDING_BAD
                                        : BluetoothProtoEnums
                                                .LE_AUDIO_NONALLOWLIST_GROUP_HEALTH_STATUS_TRENDING_BAD,
                                1);
                break;
            case LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_INACTIVATE_GROUP:
                LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
                if (groupDescriptor != null
                        && groupDescriptor.isActive()
                        && !isGroupReceivingBroadcast(groupId)) {
                    Log.i(TAG, "Group " + groupId + " is inactivated due to blocked media context");
                    groupDescriptor.mInactivatedDueToContextType = true;
                    setActiveGroupWithDevice(null, false);
                }
            default:
                break;
        }
    }

    private void handleGroupTransitToActive(int groupId) {
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null || (descriptor.isActive())) {
                Log.e(
                        TAG,
                        "handleGroupTransitToActive: no descriptors for group: "
                                + groupId
                                + " or group already active");
                return;
            }

            if (updateActiveDevices(
                    groupId, AUDIO_DIRECTION_NONE, descriptor.mDirection, true, false, false)) {
                descriptor.setActiveState(ACTIVE_STATE_ACTIVE);
            } else {
                descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
            }

            if (descriptor.isActive()) {
                mHandler.post(
                        () ->
                                notifyGroupStatusChanged(
                                        groupId, LeAudioStackEvent.GROUP_STATUS_ACTIVE));
                updateInbandRingtoneForTheGroup(groupId);
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private boolean isBroadcastAllowedToBeActivateInCurrentAudioMode() {
        switch (mCurrentAudioMode) {
            case AudioManager.MODE_NORMAL:
                return true;
            case AudioManager.MODE_RINGTONE:
            case AudioManager.MODE_IN_CALL:
            case AudioManager.MODE_IN_COMMUNICATION:
            default:
                return false;
        }
    }

    private boolean isBroadcastReadyToBeReActivated() {
        return areAllGroupsInNotGettingActiveState()
                && (!mCreateBroadcastQueue.isEmpty()
                        || mBroadcastIdDeactivatedForUnicastTransition.isPresent())
                && isBroadcastAllowedToBeActivateInCurrentAudioMode();
    }

    private void handleGroupTransitToInactive(int groupId) {
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null || descriptor.isInactive()) {
                Log.e(
                        TAG,
                        "handleGroupTransitToInactive: no descriptors for group: "
                                + groupId
                                + " or group already inactive");
                return;
            }

            descriptor.setActiveState(ACTIVE_STATE_INACTIVE);

            /* Group became inactive due to broadcast creation, check if input device should remain
             * connected to track streaming request on Unicast
             */
            boolean leaveConnectedInputDevice = false;
            Integer newDirections = AUDIO_DIRECTION_NONE;
            if (Flags.leaudioBroadcastAudioHandoverPolicies()
                    && isBroadcastReadyToBeReActivated()) {
                leaveConnectedInputDevice = true;
                newDirections |= AUDIO_DIRECTION_INPUT_BIT;

                /* Update Broadcast device before streaming state in handover case to avoid switch
                 * to non LE Audio device in Audio Manager e.g. Phone Speaker.
                 */
                BluetoothDevice device =
                        mAdapterService.getDeviceFromByte(
                                Utils.getBytesFromAddress("FF:FF:FF:FF:FF:FF"));
                if (!device.equals(mActiveBroadcastAudioDevice)) {
                    updateBroadcastActiveDevice(device, mActiveBroadcastAudioDevice, true);
                }
            }

            updateActiveDevices(
                    groupId,
                    descriptor.mDirection,
                    newDirections,
                    false,
                    descriptor.mHasFallbackDeviceWhenGettingInactive,
                    leaveConnectedInputDevice);
            /* Clear lost devices */
            Log.d(TAG, "Clear for group: " + groupId);
            descriptor.mHasFallbackDeviceWhenGettingInactive = false;
            clearLostDevicesWhileStreaming(descriptor);
            mHandler.post(
                    () ->
                            notifyGroupStatusChanged(
                                    groupId, LeAudioStackEvent.GROUP_STATUS_INACTIVE));
            updateInbandRingtoneForTheGroup(groupId);
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private void handleSinkStreamStatusChange(int status) {
        Log.d(TAG, "status: " + status);

        /* Straming request of Unicast Sink stream should result in pausing broadcast and activating
         * Unicast group.
         *
         * When stream is suspended there should be a reverse handover. Active Unicast group should
         * become inactive and broadcast should be resumed grom paused state.
         */
        if (status == LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED) {
            Optional<Integer> broadcastId = getFirstNotStoppedBroadcastId();
            if (broadcastId.isEmpty() || (mBroadcastDescriptors.get(broadcastId.get()) == null)) {
                Log.e(
                        TAG,
                        "handleUnicastStreamStatusChange: Broadcast to Unicast handover not"
                                + " possible");
                return;
            }

            mBroadcastIdDeactivatedForUnicastTransition = Optional.of(broadcastId.get());
            pauseBroadcast(broadcastId.get());
        } else if (status == LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED) {
            /* Deactivate unicast device if there is some and broadcast is ready to be activated */
            if (!areAllGroupsInNotActiveState() && isBroadcastReadyToBeReActivated()) {
                removeActiveDevice(true);
            }
        }
    }

    private void handleSourceStreamStatusChange(int status) {
        BassClientService bassClientService = getBassClientService();
        if (bassClientService == null) {
            Log.e(TAG, "handleSourceStreamStatusChange: BASS Client service is not available");

            mNativeInterface.setUnicastMonitorMode(LeAudioStackEvent.DIRECTION_SOURCE, false);
        }

        bassClientService.handleUnicastSourceStreamStatusChange(status);
    }

    private void handleUnicastStreamStatusChange(int direction, int status) {
        if (direction == LeAudioStackEvent.DIRECTION_SINK) {
            handleSinkStreamStatusChange(status);
        } else if (direction == LeAudioStackEvent.DIRECTION_SOURCE) {
            handleSourceStreamStatusChange(status);
        } else {
            Log.e(TAG, "handleUnicastStreamStatusChange: invalid direction: " + direction);
        }
    }

    private boolean isGroupReceivingBroadcast(int groupId) {
        if (!Flags.leaudioBroadcastAudioHandoverPolicies()) {
            return false;
        }

        BassClientService bassClientService = getBassClientService();
        if (bassClientService == null) {
            return false;
        }

        return bassClientService.isAnyReceiverReceivingBroadcast(getGroupDevices(groupId));
    }

    private void notifyGroupStreamStatusChanged(int groupId, int groupStreamStatus) {
        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks
                            .getBroadcastItem(i)
                            .onGroupStreamStatusChanged(groupId, groupStreamStatus);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void setGroupAllowedContextMask(
            int groupId, int sinkContextTypes, int sourceContextTypes) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }

        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            Log.i(TAG, "setActiveGroupAllowedContextMask: no active group");
            return;
        }

        LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
        if (groupDescriptor == null) {
            Log.e(TAG, "Group " + groupId + " does not exist");
            return;
        }

        groupDescriptor.updateAllowedContexts(sinkContextTypes, sourceContextTypes);

        mNativeInterface.setGroupAllowedContextMask(groupId, sinkContextTypes, sourceContextTypes);
    }

    @VisibleForTesting
    void handleGroupIdleDuringCall() {
        if (mHfpHandoverDevice == null) {
            Log.d(TAG, "There is no HFP handover");
            return;
        }
        HeadsetService headsetService = mServiceFactory.getHeadsetService();
        if (headsetService == null) {
            Log.d(TAG, "There is no HFP service available");
            return;
        }

        BluetoothDevice activeHfpDevice = headsetService.getActiveDevice();
        if (activeHfpDevice == null) {
            Log.d(TAG, "Make " + mHfpHandoverDevice + " active again ");
            headsetService.setActiveDevice(mHfpHandoverDevice);
        } else {
            Log.d(TAG, "Connect audio to " + activeHfpDevice);
            headsetService.connectAudio();
        }
        mHfpHandoverDevice = null;
    }

    void updateInbandRingtoneForTheGroup(int groupId) {
        if (!mLeAudioInbandRingtoneSupportedByPlatform) {
            Log.d(TAG, "Platform does not support inband ringtone");
            return;
        }

        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "group descriptor for " + groupId + " does not exist");
                return;
            }

            boolean ringtoneContextAvailable =
                    ((groupDescriptor.mAvailableContexts & BluetoothLeAudio.CONTEXT_TYPE_RINGTONE)
                            != 0);

            Log.d(
                    TAG,
                    "groupId active state: "
                            + groupDescriptor.mActiveState
                            + " ringtone supported: "
                            + ringtoneContextAvailable);

            boolean isRingtoneEnabled = (groupDescriptor.isActive() && ringtoneContextAvailable);

            Log.d(
                    TAG,
                    "updateInbandRingtoneForTheGroup old: "
                            + groupDescriptor.mInbandRingtoneEnabled
                            + " new: "
                            + isRingtoneEnabled);

            /* If at least one device from the group removes the Ringtone from available
             * context types, the inband ringtone will be removed
             */
            groupDescriptor.mInbandRingtoneEnabled = isRingtoneEnabled;
            TbsService tbsService = getTbsService();
            if (tbsService == null) {
                Log.w(TAG, "updateInbandRingtoneForTheGroup, tbsService not available");
                return;
            }

            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                    mDeviceDescriptors.entrySet()) {
                if (entry.getValue().mGroupId == groupId) {
                    BluetoothDevice device = entry.getKey();
                    LeAudioDeviceDescriptor deviceDescriptor = entry.getValue();
                    Log.i(
                            TAG,
                            "updateInbandRingtoneForTheGroup, setting inband ringtone to: "
                                    + groupDescriptor.mInbandRingtoneEnabled
                                    + " for "
                                    + device
                                    + " "
                                    + deviceDescriptor.mDevInbandRingtoneEnabled);
                    if (Objects.equals(
                            groupDescriptor.mInbandRingtoneEnabled,
                            deviceDescriptor.mDevInbandRingtoneEnabled)) {
                        Log.d(
                                TAG,
                                "Device "
                                        + device
                                        + " has already set inband ringtone to "
                                        + groupDescriptor.mInbandRingtoneEnabled);
                        continue;
                    }

                    deviceDescriptor.mDevInbandRingtoneEnabled =
                            groupDescriptor.mInbandRingtoneEnabled;
                    if (deviceDescriptor.mDevInbandRingtoneEnabled) {
                        tbsService.setInbandRingtoneSupport(device);
                    } else {
                        tbsService.clearInbandRingtoneSupport(device);
                    }
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/348562830 - Remove with flag
    void stopAudioServersBackgroundScan() {
        Log.d(TAG, "stopAudioServersBackgroundScan");

        if (Flags.leaudioCallStartScanDirectly()) {
            mScanCallback2.stopBackgroundScan();
            return;
        }

        if (mAudioServersScanner == null || mScanCallback == null) {
            Log.d(TAG, "stopAudioServersBackgroundScan: already stopped");
            return;
        }

        try {
            mAudioServersScanner.stopScan(mScanCallback);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Fail to stop scanner, consider it stopped", e);
        }

        /* Callback is the indicator for scanning being enabled */
        mScanCallback = null;
    }

    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/348562830 - Remove with flag
    void startAudioServersBackgroundScan(boolean retry) {
        Log.d(TAG, "startAudioServersBackgroundScan, retry: " + retry);

        if (!isScannerNeeded()) {
            return;
        }

        if (Flags.leaudioCallStartScanDirectly()) {
            mScanCallback2.startBackgroundScan();
            return;
        }

        if (mAudioServersScanner == null) {
            mAudioServersScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();
            if (mAudioServersScanner == null) {
                Log.e(TAG, "startAudioServersBackgroundScan: Could not get scanner");
                return;
            }
        }

        if (!retry) {
            if (mScanCallback != null) {
                Log.d(TAG, "startAudioServersBackgroundScan: Scanning already enabled");
                return;
            }
            mScanCallback = new AudioServerScanCallback();
        }

        /* Filter we are building here will not match to anything.
         * Eventually we should be able to start scan from native when
         * b/276350722 is done
         */
        byte[] serviceData = new byte[] {0x11};

        ArrayList filterList = new ArrayList<ScanFilter>();
        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceData(BluetoothUuid.LE_AUDIO, serviceData)
                        .build();
        filterList.add(filter);

        ScanSettings settings =
                new ScanSettings.Builder()
                        .setLegacy(false)
                        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                        .setPhy(BluetoothDevice.PHY_LE_1M)
                        .build();

        try {
            mAudioServersScanner.startScan(filterList, settings, mScanCallback);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Fail to start scanner, consider it stopped", e);
            mScanCallback = null;
        }
    }

    void transitionFromBroadcastToUnicast() {
        if (mUnicastGroupIdDeactivatedForBroadcastTransition == LE_AUDIO_GROUP_ID_INVALID) {
            Log.d(TAG, "No deactivated group due for broadcast transmission");
            // Notify audio manager
            if (mBroadcastDescriptors.values().stream()
                    .noneMatch(d -> d.mState.equals(LeAudioStackEvent.BROADCAST_STATE_STREAMING))) {
                updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, false);
            }
            return;
        }

        if (!leaudioUseAudioModeListener()) {
            if (mQueuedInCallValue.isPresent()) {
                mNativeInterface.setInCall(mQueuedInCallValue.get());
                mQueuedInCallValue = Optional.empty();
            }
        }

        BluetoothDevice unicastDevice =
                getLeadDeviceForTheGroup(mUnicastGroupIdDeactivatedForBroadcastTransition);
        if (unicastDevice == null) {
            /* All devices from group were disconnected in meantime */
            Log.w(
                    TAG,
                    "transitionFromBroadcastToUnicast: No valid unicast device for group ID: "
                            + mUnicastGroupIdDeactivatedForBroadcastTransition);
            updateFallbackUnicastGroupIdForBroadcast(LE_AUDIO_GROUP_ID_INVALID);
            updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, false);
            return;
        }

        Log.d(
                TAG,
                "Transitioning to Unicast stream for group: "
                        + mUnicastGroupIdDeactivatedForBroadcastTransition
                        + ", with device: "
                        + unicastDevice);

        updateFallbackUnicastGroupIdForBroadcast(LE_AUDIO_GROUP_ID_INVALID);
        setActiveDevice(unicastDevice);
    }

    void clearBroadcastTimeoutCallback() {
        if (mHandler == null) {
            Log.e(TAG, "No callback handler");
            return;
        }

        /* Timeout callback already cleared */
        if (mDialingOutTimeoutEvent == null) {
            return;
        }

        mHandler.removeCallbacks(mDialingOutTimeoutEvent);
        mDialingOutTimeoutEvent = null;
    }

    void notifyAudioFrameworkForCodecConfigUpdate(
            int groupId,
            LeAudioGroupDescriptor descriptor,
            boolean outputCodecOrFreqChanged,
            boolean inputCodecOrFreqChanged) {
        Log.i(TAG, "notifyAudioFrameworkForCodecConfigUpdate groupId: " + groupId);

        if (!Flags.leaudioCodecConfigCallbackOrderFix()) {
            Log.d(TAG, "leaudio_codec_config_callback_order_fix is not enabled");
            return;
        }

        if (mActiveAudioOutDevice != null && outputCodecOrFreqChanged) {
            int volume = getAudioDeviceGroupVolume(groupId);

            final BluetoothProfileConnectionInfo connectionInfo;
            if (isAtLeastU()) {
                connectionInfo =
                        BluetoothProfileConnectionInfo.createLeAudioOutputInfo(true, volume);
            } else {
                connectionInfo = BluetoothProfileConnectionInfo.createLeAudioInfo(true, true);
            }

            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioOutDevice, mActiveAudioOutDevice, connectionInfo);
        }

        if (mActiveAudioInDevice != null && inputCodecOrFreqChanged) {
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioInDevice,
                    mActiveAudioInDevice,
                    BluetoothProfileConnectionInfo.createLeAudioInfo(false, false));
        }
    }

    boolean isOutputCodecOfSampleFrequencyChanged(
            BluetoothLeAudioCodecStatus previous, BluetoothLeAudioCodecStatus next) {
        if ((previous == null) && (next == null)) {
            return false;
        }

        if ((previous == null) || (next == null)) {
            Log.d(TAG, previous + " != " + next);
            return true;
        }

        if ((previous.getOutputCodecConfig() == null) && (next.getOutputCodecConfig() == null)) {
            /* Nothing changed here.*/
            return false;
        }

        if ((previous.getOutputCodecConfig() == null || next.getOutputCodecConfig() == null)) {
            Log.d(
                    TAG,
                    "New output codec: "
                            + (previous.getOutputCodecConfig()
                                    + " != "
                                    + next.getOutputCodecConfig()));
            return true;
        }

        if (previous.getOutputCodecConfig().getCodecType()
                != next.getOutputCodecConfig().getCodecType()) {
            Log.d(
                    TAG,
                    "Different output codec type: "
                            + (previous.getOutputCodecConfig().getCodecType()
                                    + " != "
                                    + next.getOutputCodecConfig().getCodecType()));
            return true;
        }
        if (previous.getOutputCodecConfig().getSampleRate()
                != next.getOutputCodecConfig().getSampleRate()) {
            Log.d(
                    TAG,
                    "Different output samplerate: "
                            + (previous.getOutputCodecConfig().getSampleRate()
                                    + " != "
                                    + next.getOutputCodecConfig().getSampleRate()));
            return true;
        }

        return false;
    }

    boolean isInputCodecOfSampleFrequencyChanged(
            BluetoothLeAudioCodecStatus previous, BluetoothLeAudioCodecStatus next) {
        if ((previous == null) && (next == null)) {
            return false;
        }

        if ((previous == null) || (next == null)) {
            Log.d(TAG, previous + " != " + next);
            return true;
        }

        if ((previous.getInputCodecConfig() == null) && (next.getInputCodecConfig() == null)) {
            /* Nothing changed here.*/
            return false;
        }

        if ((previous.getInputCodecConfig() == null) || (next.getInputCodecConfig() == null)) {
            Log.d(
                    TAG,
                    "New input codec: "
                            + (previous.getInputCodecConfig()
                                    + " != "
                                    + next.getInputCodecConfig()));
            return true;
        }

        if (previous.getInputCodecConfig().getCodecType()
                != next.getInputCodecConfig().getCodecType()) {
            Log.d(
                    TAG,
                    "Different input codec type: "
                            + (previous.getInputCodecConfig().getCodecType()
                                    + " != "
                                    + next.getInputCodecConfig().getCodecType()));
            return true;
        }

        if (previous.getInputCodecConfig().getSampleRate()
                != next.getInputCodecConfig().getSampleRate()) {
            Log.d(
                    TAG,
                    "Different input samplerate: "
                            + (previous.getInputCodecConfig().getSampleRate()
                                    + " != "
                                    + next.getInputCodecConfig().getSampleRate()));
            return true;
        }

        return false;
    }

    // Suppressed since this is part of a local process
    @SuppressLint("AndroidFrameworkRequiresPermission")
    void messageFromNative(LeAudioStackEvent stackEvent) {
        Log.d(TAG, "Message from native: " + stackEvent);
        BluetoothDevice device = stackEvent.device;

        if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
            // Some events require device state machine
            mGroupReadLock.lock();
            try {
                LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
                if (deviceDescriptor == null) {
                    Log.e(TAG, "messageFromNative: No valid descriptor for device: " + device);
                    return;
                }

                LeAudioStateMachine sm = deviceDescriptor.mStateMachine;
                if (sm != null) {
                    /*
                     * To improve scenario when lead Le Audio device is disconnected for the
                     * streaming group, while there are still other devices streaming,
                     * LeAudioService will not notify audio framework or other users about
                     * Le Audio lead device disconnection. Instead we try to reconnect under
                     * the hood and keep using lead device as a audio device indetifier in
                     * the audio framework in order to not stop the stream.
                     */
                    int groupId = deviceDescriptor.mGroupId;
                    LeAudioGroupDescriptor descriptor = mGroupDescriptorsView.get(groupId);
                    switch (stackEvent.valueInt1) {
                        case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTING:
                        case LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED:
                            deviceDescriptor.mAclConnected = false;
                            startAudioServersBackgroundScan(/* retry= */ false);

                            boolean disconnectDueToUnbond =
                                    (BluetoothDevice.BOND_NONE
                                            == mAdapterService.getBondState(device));
                            if (descriptor != null
                                    && (Objects.equals(device, mActiveAudioOutDevice)
                                            || Objects.equals(device, mActiveAudioInDevice))
                                    && (getConnectedPeerDevices(groupId).size() > 1)
                                    && !disconnectDueToUnbond) {

                                Log.d(TAG, "Adding to lost devices : " + device);
                                descriptor.mLostLeadDeviceWhileStreaming = device;
                                return;
                            }
                            break;
                        case LeAudioStackEvent.CONNECTION_STATE_CONNECTED:
                        case LeAudioStackEvent.CONNECTION_STATE_CONNECTING:
                            deviceDescriptor.mAclConnected = true;
                            if (descriptor != null
                                    && Objects.equals(
                                            descriptor.mLostLeadDeviceWhileStreaming, device)) {
                                Log.d(TAG, "Removing from lost devices : " + device);
                                descriptor.mLostLeadDeviceWhileStreaming = null;
                                /* Try to connect other devices from the group */
                                connectSet(device);
                            }
                            break;
                    }
                } else {
                    /* state machine does not exist yet */
                    switch (stackEvent.valueInt1) {
                        case LeAudioStackEvent.CONNECTION_STATE_CONNECTED:
                        case LeAudioStackEvent.CONNECTION_STATE_CONNECTING:
                            deviceDescriptor.mAclConnected = true;
                            sm = getOrCreateStateMachine(device);
                            /* Incoming connection try to connect other devices from the group */
                            connectSet(device);
                            break;
                        default:
                            break;
                    }

                    if (sm == null) {
                        Log.e(TAG, "Cannot process stack event: no state machine: " + stackEvent);
                        return;
                    }
                }

                sm.sendMessage(LeAudioStateMachine.STACK_EVENT, stackEvent);
                return;
            } finally {
                mGroupReadLock.unlock();
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED) {
            int groupId = stackEvent.valueInt1;
            int nodeStatus = stackEvent.valueInt2;

            Objects.requireNonNull(stackEvent.device);

            switch (nodeStatus) {
                case LeAudioStackEvent.GROUP_NODE_ADDED:
                    handleGroupNodeAdded(device, groupId);
                    break;
                case LeAudioStackEvent.GROUP_NODE_REMOVED:
                    handleGroupNodeRemoved(device, groupId);
                    break;
                default:
                    break;
            }
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED) {
            mInputLocalCodecCapabilities = stackEvent.valueCodecList1;
            mOutputLocalCodecCapabilities = stackEvent.valueCodecList2;
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED) {
            int groupId = stackEvent.valueInt1;
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.e(TAG, " Group not found " + groupId);
                return;
            }

            descriptor.mInputSelectableConfig = new ArrayList<>(stackEvent.valueCodecList1);
            descriptor.mOutputSelectableConfig = new ArrayList<>(stackEvent.valueCodecList2);

            BluetoothLeAudioCodecConfig emptyConfig =
                    new BluetoothLeAudioCodecConfig.Builder().build();

            descriptor.mInputSelectableConfig.removeIf(n -> n.equals(emptyConfig));
            descriptor.mOutputSelectableConfig.removeIf(n -> n.equals(emptyConfig));

        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED) {
            int groupId = stackEvent.valueInt1;
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.e(TAG, " Group not found " + groupId);
                return;
            }
            BluetoothLeAudioCodecConfig emptyConfig =
                    new BluetoothLeAudioCodecConfig.Builder().build();

            BluetoothLeAudioCodecStatus status =
                    new BluetoothLeAudioCodecStatus(
                            (stackEvent.valueCodec1.equals(emptyConfig)
                                    ? null
                                    : stackEvent.valueCodec1),
                            (stackEvent.valueCodec2.equals(emptyConfig)
                                    ? null
                                    : stackEvent.valueCodec2),
                            mInputLocalCodecCapabilities,
                            mOutputLocalCodecCapabilities,
                            descriptor.mInputSelectableConfig,
                            descriptor.mOutputSelectableConfig);

            boolean outputCodecOrFreqChanged =
                    isOutputCodecOfSampleFrequencyChanged(descriptor.mCodecStatus, status);
            boolean inputCodecOrFreqChanged =
                    isInputCodecOfSampleFrequencyChanged(descriptor.mCodecStatus, status);

            Log.d(
                    TAG,
                    ("Codec update for group:" + groupId)
                            + (", outputCodecOrFreqChanged: " + outputCodecOrFreqChanged)
                            + (", inputCodecOrFreqChanged: " + inputCodecOrFreqChanged));

            descriptor.mCodecStatus = status;
            mHandler.post(() -> notifyUnicastCodecConfigChanged(groupId, status));

            if (descriptor.isActive() && (outputCodecOrFreqChanged || inputCodecOrFreqChanged)) {
                // Audio framework needs to be notified so it get new codec config
                notifyAudioFrameworkForCodecConfigUpdate(
                        groupId, descriptor, outputCodecOrFreqChanged, inputCodecOrFreqChanged);
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED) {
            int direction = stackEvent.valueInt1;
            int groupId = stackEvent.valueInt2;
            int available_contexts = stackEvent.valueInt5;

            mGroupReadLock.lock();
            try {
                LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
                if (descriptor != null) {
                    if (descriptor.isActive()) {
                        if (updateActiveDevices(
                                groupId, descriptor.mDirection, direction, true, false, false)) {
                            descriptor.setActiveState(ACTIVE_STATE_ACTIVE);
                        } else {
                            descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                        }

                        if (descriptor.isInactive()) {
                            mHandler.post(
                                    () ->
                                            notifyGroupStatusChanged(
                                                    groupId,
                                                    BluetoothLeAudio.GROUP_STATUS_INACTIVE));
                        }
                    }
                    boolean availableContextChanged =
                            Integer.bitCount(descriptor.mAvailableContexts)
                                    != Integer.bitCount(available_contexts);

                    descriptor.mDirection = direction;
                    descriptor.mAvailableContexts = available_contexts;
                    updateInbandRingtoneForTheGroup(groupId);

                    if (!availableContextChanged) {
                        Log.d(
                                TAG,
                                " Context did not changed for "
                                        + groupId
                                        + ": "
                                        + descriptor.mAvailableContexts);
                        return;
                    }

                    if (descriptor.mAvailableContexts == 0) {
                        if (descriptor.isActive()) {
                            Log.i(
                                    TAG,
                                    " Inactivating group "
                                            + groupId
                                            + " due to unavailable context types");
                            descriptor.mInactivatedDueToContextType = true;
                            setActiveGroupWithDevice(null, false);
                        }
                        return;
                    }

                    if (descriptor.mInactivatedDueToContextType) {
                        Log.i(
                                TAG,
                                " Some context got available again for "
                                        + groupId
                                        + ", try it out: "
                                        + descriptor.mAvailableContexts);
                        descriptor.mInactivatedDueToContextType = false;
                        setActiveGroupWithDevice(getLeadDeviceForTheGroup(groupId), true);
                    }
                } else {
                    Log.e(TAG, "messageFromNative: no descriptors for group: " + groupId);
                }
            } finally {
                mGroupReadLock.unlock();
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE) {
            Objects.requireNonNull(stackEvent.device);

            int sink_audio_location = stackEvent.valueInt1;

            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "messageFromNative: No valid descriptor for device: " + device);
                return;
            }

            descriptor.mSinkAudioLocation = sink_audio_location;

            Log.i(
                    TAG,
                    "EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE:"
                            + device
                            + " audio location:"
                            + sink_audio_location);
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED) {
            int groupId = stackEvent.valueInt1;
            int groupStatus = stackEvent.valueInt2;

            switch (groupStatus) {
                case LeAudioStackEvent.GROUP_STATUS_ACTIVE:
                    {
                        handleGroupTransitToActive(groupId);

                        /* Clear possible exposed broadcast device after activating unicast */
                        if (mActiveBroadcastAudioDevice != null) {
                            updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, true);
                        }
                        break;
                    }
                case LeAudioStackEvent.GROUP_STATUS_INACTIVE:
                    {
                        if (Flags.leaudioGettingActiveStateSupport()) {
                            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
                            if (descriptor == null) {
                                Log.e(
                                        TAG,
                                        "deviceDisconnected: no descriptors for group: " + groupId);
                                return;
                            }

                            if (descriptor.isActive()) {
                                handleGroupTransitToInactive(groupId);
                            }

                            descriptor.setActiveState(ACTIVE_STATE_INACTIVE);

                            /* In case if group is inactivated due to switch to other */
                            Integer gettingActiveGroupId = getFirstGroupIdInGettingActiveState();
                            if (gettingActiveGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                                if (leaudioAllowedContextMask()) {
                                    /* Context were modified, apply mask to activating group */
                                    if (descriptor.areAllowedContextsModified()) {
                                        setGroupAllowedContextMask(
                                                gettingActiveGroupId,
                                                descriptor.getAllowedSinkContexts(),
                                                descriptor.getAllowedSourceContexts());
                                        setGroupAllowedContextMask(
                                                groupId,
                                                BluetoothLeAudio.CONTEXTS_ALL,
                                                BluetoothLeAudio.CONTEXTS_ALL);
                                    }
                                }
                                break;
                            }

                            if (leaudioAllowedContextMask()) {
                                /* Clear allowed context mask if there is no switch of group */
                                if (descriptor.areAllowedContextsModified()) {
                                    setGroupAllowedContextMask(
                                            groupId,
                                            BluetoothLeAudio.CONTEXTS_ALL,
                                            BluetoothLeAudio.CONTEXTS_ALL);
                                }
                            }
                        } else {
                            handleGroupTransitToInactive(groupId);
                        }

                        if (isBroadcastAllowedToBeActivateInCurrentAudioMode()) {
                            /* Check if broadcast was deactivated due to unicast */
                            if (mBroadcastIdDeactivatedForUnicastTransition.isPresent()) {
                                updateFallbackUnicastGroupIdForBroadcast(groupId);
                                if (!leaudioUseAudioModeListener()) {
                                    mQueuedInCallValue = Optional.empty();
                                }
                                startBroadcast(mBroadcastIdDeactivatedForUnicastTransition.get());
                                mBroadcastIdDeactivatedForUnicastTransition = Optional.empty();
                            }

                            if (!mCreateBroadcastQueue.isEmpty()) {
                                updateFallbackUnicastGroupIdForBroadcast(groupId);
                                BluetoothLeBroadcastSettings settings =
                                        mCreateBroadcastQueue.remove();
                                createBroadcast(settings);
                            }
                        }
                        break;
                    }
                case LeAudioStackEvent.GROUP_STATUS_TURNED_IDLE_DURING_CALL:
                    {
                        handleGroupIdleDuringCall();
                        break;
                    }
                default:
                    break;
            }
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_DEV_RECOMMENDATION) {
            handleDeviceHealthAction(stackEvent.device, stackEvent.valueInt1);
        } else if (stackEvent.type
                == LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION) {
            handleGroupHealthAction(stackEvent.valueInt1, stackEvent.valueInt2);
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED) {
            int broadcastId = stackEvent.valueInt1;
            boolean success = stackEvent.valueBool1;
            if (success) {
                Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " created.");
                mBroadcastDescriptors.put(broadcastId, new LeAudioBroadcastDescriptor());
                mHandler.post(
                        () ->
                                notifyBroadcastStarted(
                                        broadcastId,
                                        BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
                // Start sending the actual stream
                startBroadcast(broadcastId);

            } else {
                // TODO: Improve reason reporting or extend the native stack event with reason code
                Log.e(
                        TAG,
                        "EVENT_TYPE_BROADCAST_CREATED: Failed to create broadcast: " + broadcastId);

                /* Disconnect Broadcast device which was connected to avoid non LE Audio sound
                 * leak in handover scenario.
                 */
                if ((mUnicastGroupIdDeactivatedForBroadcastTransition != LE_AUDIO_GROUP_ID_INVALID)
                        && mCreateBroadcastQueue.isEmpty()
                        && (!Objects.equals(device, mActiveBroadcastAudioDevice))) {
                    clearBroadcastTimeoutCallback();
                    updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, false);
                }

                mHandler.post(() -> notifyBroadcastStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN));
            }

            mAwaitingBroadcastCreateResponse = false;

            // In case if there were additional calls to create broadcast
            if (!mCreateBroadcastQueue.isEmpty()) {
                BluetoothLeBroadcastSettings settings = mCreateBroadcastQueue.remove();
                createBroadcast(settings);
            }

        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED) {
            Integer broadcastId = stackEvent.valueInt1;
            LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
            if (descriptor == null) {
                Log.e(
                        TAG,
                        "EVENT_TYPE_BROADCAST_DESTROYED: No valid descriptor for broadcastId: "
                                + broadcastId);
            } else {
                mBroadcastDescriptors.remove(broadcastId);
            }

            // TODO: Improve reason reporting or extend the native stack event with reason code
            mHandler.post(
                    () ->
                            notifyOnBroadcastStopped(
                                    broadcastId, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
            BassClientService bassClientService = getBassClientService();
            if (bassClientService != null) {
                bassClientService.stopReceiversSourceSynchronization(broadcastId);
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE) {
            int broadcastId = stackEvent.valueInt1;
            int state = stackEvent.valueInt2;
            int previousState;

            LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
            if (descriptor == null) {
                Log.e(
                        TAG,
                        "EVENT_TYPE_BROADCAST_STATE: No valid descriptor for broadcastId: "
                                + broadcastId);
                return;
            }

            /* Request broadcast details if not known yet */
            if (!descriptor.mRequestedForDetails) {
                mLeAudioBroadcasterNativeInterface.getBroadcastMetadata(broadcastId);
                descriptor.mRequestedForDetails = true;
            }
            previousState = descriptor.mState;
            descriptor.mState = state;
            BassClientService bassClientService = getBassClientService();

            switch (descriptor.mState) {
                case LeAudioStackEvent.BROADCAST_STATE_STOPPED:
                    Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " stopped.");

                    // Playback stopped
                    mHandler.post(
                            () ->
                                    notifyPlaybackStopped(
                                            broadcastId,
                                            BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));

                    transitionFromBroadcastToUnicast();
                    destroyBroadcast(broadcastId);
                    break;
                case LeAudioStackEvent.BROADCAST_STATE_CONFIGURING:
                    Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " configuring.");
                    break;
                case LeAudioStackEvent.BROADCAST_STATE_PAUSED:
                    Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " paused.");

                    /* Stop here if Broadcast was not in Streaming state before */
                    if (previousState != LeAudioStackEvent.BROADCAST_STATE_STREAMING) {
                        return;
                    }

                    // Playback paused
                    mHandler.post(
                            () ->
                                    notifyPlaybackStopped(
                                            broadcastId,
                                            BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));

                    if (bassClientService != null) {
                        if (!leaudioBroadcastAssistantPeripheralEntrustment()) {
                            bassClientService.suspendReceiversSourceSynchronization(broadcastId);
                        } else if (leaudioBigDependsOnAudioState()) {
                            bassClientService.cacheSuspendingSources(broadcastId);
                        }
                    }

                    if (!leaudioBigDependsOnAudioState() || mIsBroadcastPausedFromOutside) {
                        mIsBroadcastPausedFromOutside = false;
                        transitionFromBroadcastToUnicast();
                    }
                    break;
                case LeAudioStackEvent.BROADCAST_STATE_STOPPING:
                    Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " stopping.");
                    break;
                case LeAudioStackEvent.BROADCAST_STATE_STREAMING:
                    Log.d(TAG, "Broadcast broadcastId: " + broadcastId + " streaming.");

                    // Stream resumed
                    mHandler.post(
                            () ->
                                    notifyPlaybackStarted(
                                            broadcastId,
                                            BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));

                    clearBroadcastTimeoutCallback();

                    if (previousState == LeAudioStackEvent.BROADCAST_STATE_PAUSED) {
                        if (bassClientService != null) {
                            bassClientService.resumeReceiversSourceSynchronization();
                        }
                    }

                    // Notify audio manager
                    if (mBroadcastDescriptors.values().stream()
                            .anyMatch(
                                    d ->
                                            d.mState.equals(
                                                    LeAudioStackEvent.BROADCAST_STATE_STREAMING))) {
                        if (!Objects.equals(device, mActiveBroadcastAudioDevice)) {
                            updateBroadcastActiveDevice(device, mActiveBroadcastAudioDevice, true);
                        }
                    }
                    break;
                default:
                    Log.e(TAG, "Invalid state of broadcast: " + descriptor.mState);
                    break;
            }

            // Notify broadcast assistant
            if (Flags.leaudioBroadcastAudioHandoverPolicies()) {
                if (bassClientService != null) {
                    bassClientService.notifyBroadcastStateChanged(descriptor.mState, broadcastId);
                }
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED) {
            int broadcastId = stackEvent.valueInt1;
            if (stackEvent.broadcastMetadata == null) {
                Log.e(TAG, "Missing Broadcast metadata for broadcastId: " + broadcastId);
            } else {
                LeAudioBroadcastDescriptor descriptor = mBroadcastDescriptors.get(broadcastId);
                if (descriptor == null) {
                    Log.e(
                            TAG,
                            "EVENT_TYPE_BROADCAST_METADATA_CHANGED: No valid descriptor for "
                                    + "broadcastId: "
                                    + broadcastId);
                    return;
                }
                descriptor.mMetadata = stackEvent.broadcastMetadata;
                mHandler.post(
                        () ->
                                notifyBroadcastMetadataChanged(
                                        broadcastId, stackEvent.broadcastMetadata));
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_NATIVE_INITIALIZED) {
            mLeAudioNativeIsInitialized = true;
            for (Map.Entry<ParcelUuid, Pair<Integer, Integer>> entry :
                    ContentControlIdKeeper.getUuidToCcidContextPairMap().entrySet()) {
                ParcelUuid userUuid = entry.getKey();
                Pair<Integer, Integer> ccidInformation = entry.getValue();
                setCcidInformation(userUuid, ccidInformation.first, ccidInformation.second);
            }
            if (!mTmapStarted) {
                mTmapStarted = registerTmap();
            }
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS) {
            handleUnicastStreamStatusChange(stackEvent.valueInt1, stackEvent.valueInt2);
        } else if (stackEvent.type == LeAudioStackEvent.EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED) {
            mHandler.post(
                    () ->
                            notifyGroupStreamStatusChanged(
                                    stackEvent.valueInt1, stackEvent.valueInt2));
        }
    }

    private LeAudioStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }

        LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
        if (descriptor == null) {
            Log.e(TAG, "getOrCreateStateMachine: No valid descriptor for device: " + device);
            return null;
        }

        LeAudioStateMachine sm = descriptor.mStateMachine;
        if (sm != null) {
            return sm;
        }

        Log.d(TAG, "Creating a new state machine for " + device);

        sm =
                LeAudioStateMachine.make(
                        device, this, mNativeInterface, mStateMachinesThread.getLooper());
        descriptor.mStateMachine = sm;
        return sm;
    }

    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    /**
     * Process a change in the bonding state for a device.
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

        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "bondStateChanged: No valid descriptor for device: " + device);
                return;
            }

            if (descriptor.mGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                /* In case device is still in the group, let's remove it */
                mNativeInterface.groupRemoveNode(descriptor.mGroupId, device);
            }

            descriptor.mGroupId = LE_AUDIO_GROUP_ID_INVALID;
            descriptor.mSinkAudioLocation = BluetoothLeAudio.AUDIO_LOCATION_INVALID;
            descriptor.mDirection = AUDIO_DIRECTION_NONE;

            LeAudioStateMachine sm = descriptor.mStateMachine;
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Device is not disconnected yet.");
                disconnect(device);
                return;
            }
        } finally {
            mGroupReadLock.unlock();
        }
        removeStateMachine(device);
        removeAuthorizationInfoForRelatedProfiles(device);
    }

    private void removeStateMachine(BluetoothDevice device) {
        mGroupReadLock.lock();
        try {
            try {
                LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
                if (descriptor == null) {
                    Log.e(TAG, "removeStateMachine: No valid descriptor for device: " + device);
                    return;
                }

                LeAudioStateMachine sm = descriptor.mStateMachine;
                if (sm == null) {
                    Log.w(
                            TAG,
                            "removeStateMachine: device "
                                    + device
                                    + " does not have a state machine");
                    return;
                }
                Log.i(TAG, "removeStateMachine: removing state machine for device: " + device);
                sm.quit();
                sm.cleanup();
                descriptor.mStateMachine = null;
            } finally {
                // Upgrade to write lock
                mGroupReadLock.unlock();
                mGroupWriteLock.lock();
            }
            mDeviceDescriptors.remove(device);
            if (!isScannerNeeded()) {
                stopAudioServersBackgroundScan();
            }
        } finally {
            mGroupWriteLock.unlock();
        }
    }

    @VisibleForTesting
    List<BluetoothDevice> getConnectedPeerDevices(int groupId) {
        List<BluetoothDevice> result = new ArrayList<>();
        for (BluetoothDevice peerDevice : getConnectedDevices()) {
            if (getGroupId(peerDevice) == groupId) {
                result.add(peerDevice);
            }
        }
        return result;
    }

    /** Process a change for connection of a device. */
    public synchronized void deviceConnected(BluetoothDevice device) {
        LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
        if (deviceDescriptor == null) {
            Log.e(TAG, "deviceConnected: No valid descriptor for device: " + device);
            return;
        }

        if (deviceDescriptor.mGroupId == LE_AUDIO_GROUP_ID_INVALID
                || getConnectedPeerDevices(deviceDescriptor.mGroupId).size() == 1) {
            // Log LE Audio connection event if we are the first device in a set
            // Or when the GroupId has not been found
            // MetricsLogger.logProfileConnectionEvent(
            //         BluetoothMetricsProto.ProfileId.LE_AUDIO);
        }

        LeAudioGroupDescriptor descriptor = getGroupDescriptor(deviceDescriptor.mGroupId);
        if (descriptor != null) {
            descriptor.mIsConnected = true;
        } else {
            Log.e(TAG, "deviceConnected: no descriptors for group: " + deviceDescriptor.mGroupId);
        }

        if (!isScannerNeeded()) {
            stopAudioServersBackgroundScan();
        }
    }

    /** Process a change for disconnection of a device. */
    public synchronized void deviceDisconnected(BluetoothDevice device, boolean hasFallbackDevice) {
        Log.d(TAG, "deviceDisconnected " + device);

        int groupId = LE_AUDIO_GROUP_ID_INVALID;
        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
            if (deviceDescriptor == null) {
                Log.e(TAG, "deviceDisconnected: No valid descriptor for device: " + device);
                return;
            }
            groupId = deviceDescriptor.mGroupId;
        } finally {
            mGroupReadLock.unlock();
        }

        int bondState = mAdapterService.getBondState(device);
        if (bondState == BluetoothDevice.BOND_NONE) {
            Log.d(TAG, device + " is unbond. Remove state machine");

            removeStateMachine(device);
            removeAuthorizationInfoForRelatedProfiles(device);
        }

        if (!isScannerNeeded()) {
            stopAudioServersBackgroundScan();
        }

        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.e(TAG, "deviceDisconnected: no descriptors for group: " + groupId);
                return;
            }

            List<BluetoothDevice> connectedDevices = getConnectedPeerDevices(groupId);
            /* Let's check if the last connected device is really connected */
            if (connectedDevices.size() == 1
                    && Objects.equals(
                            connectedDevices.get(0), descriptor.mLostLeadDeviceWhileStreaming)) {
                clearLostDevicesWhileStreaming(descriptor);
                return;
            }

            if (getConnectedPeerDevices(groupId).isEmpty()) {
                descriptor.mIsConnected = false;
                descriptor.mInactivatedDueToContextType = false;
                if (descriptor.isActive()) {
                    /* Notify Native layer */
                    removeActiveDevice(hasFallbackDevice);
                    descriptor.setActiveState(ACTIVE_STATE_INACTIVE);
                    /* Update audio framework */
                    updateActiveDevices(
                            groupId,
                            descriptor.mDirection,
                            descriptor.mDirection,
                            false,
                            hasFallbackDevice,
                            false);
                    return;
                }
            }

            if (descriptor.isActive()
                    || Objects.equals(mActiveAudioOutDevice, device)
                    || Objects.equals(mActiveAudioInDevice, device)) {
                updateActiveDevices(
                        groupId,
                        descriptor.mDirection,
                        descriptor.mDirection,
                        descriptor.isActive(),
                        hasFallbackDevice,
                        false);
            }
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Check whether can connect to a peer device. The check considers a number of factors during
     * the evaluation.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    public boolean okToConnect(BluetoothDevice device) {
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

    /**
     * Get device audio location.
     *
     * @param device LE Audio capable device
     * @return the sink audioi location that this device currently exposed
     */
    public int getAudioLocation(BluetoothDevice device) {
        if (device == null) {
            return BluetoothLeAudio.AUDIO_LOCATION_INVALID;
        }

        LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
        if (descriptor == null) {
            Log.e(TAG, "getAudioLocation: No valid descriptor for device: " + device);
            return BluetoothLeAudio.AUDIO_LOCATION_INVALID;
        }

        return descriptor.mSinkAudioLocation;
    }

    /**
     * Check if inband ringtone is enabled by the LE Audio group. Group id for the device can be
     * found with {@link BluetoothLeAudio#getGroupId}.
     *
     * @param groupId LE Audio group id
     * @return true if inband ringtone is enabled, false otherwise
     */
    public boolean isInbandRingtoneEnabled(int groupId) {
        if (!mLeAudioInbandRingtoneSupportedByPlatform) {
            return mLeAudioInbandRingtoneSupportedByPlatform;
        }

        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            return false;
        }

        return descriptor.mInbandRingtoneEnabled;
    }

    /**
     * Set In Call state
     *
     * @param inCall True if device in call (any state), false otherwise.
     */
    public void setInCall(boolean inCall) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }

        if (!leaudioUseAudioModeListener()) {
            /* For setting inCall mode */
            if (Flags.leaudioBroadcastAudioHandoverPolicies()
                    && inCall
                    && !areBroadcastsAllStopped()) {
                mQueuedInCallValue = Optional.of(true);

                /* Request activation of unicast group */
                handleUnicastStreamStatusChange(
                        LeAudioStackEvent.DIRECTION_SINK,
                        LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
                return;
            }
        }

        mNativeInterface.setInCall(inCall);

        if (!leaudioUseAudioModeListener()) {
            /* For clearing inCall mode */
            if (Flags.leaudioBroadcastAudioHandoverPolicies()
                    && !inCall
                    && mBroadcastIdDeactivatedForUnicastTransition.isPresent()) {
                handleUnicastStreamStatusChange(
                        LeAudioStackEvent.DIRECTION_SINK,
                        LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
            }
        }
    }

    /**
     * Sends the preferred audio profiles for a dual mode audio device to the native stack.
     *
     * @param groupId is the group id of the device which had a preference change
     * @param isOutputPreferenceLeAudio {@code true} if {@link BluetoothProfile#LE_AUDIO} is
     *     preferred for {@link BluetoothAdapter#AUDIO_MODE_OUTPUT_ONLY}, {@code false} if it is
     *     {@link BluetoothProfile#A2DP}
     * @param isDuplexPreferenceLeAudio {@code true} if {@link BluetoothProfile#LE_AUDIO} is
     *     preferred for {@link BluetoothAdapter#AUDIO_MODE_DUPLEX}, {@code false} if it is {@link
     *     BluetoothProfile#HEADSET}
     */
    public void sendAudioProfilePreferencesToNative(
            int groupId, boolean isOutputPreferenceLeAudio, boolean isDuplexPreferenceLeAudio) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }
        mNativeInterface.sendAudioProfilePreferences(
                groupId, isOutputPreferenceLeAudio, isDuplexPreferenceLeAudio);
    }

    /**
     * Set allowed context which should be considered while Audio Framework would request streaming.
     *
     * @param sinkContextTypes sink context types that would be allowed to stream
     * @param sourceContextTypes source context types that would be allowed to stream
     */
    public void setActiveGroupAllowedContextMask(int sinkContextTypes, int sourceContextTypes) {
        setGroupAllowedContextMask(getActiveGroupId(), sinkContextTypes, sourceContextTypes);
    }

    /**
     * Set Inactive by HFP during handover This is a work around to handle controllers that cannot
     * have SCO and CIS at the same time. So remove active device to tear down CIS, and re-connect
     * the SCO in {@link LeAudioService#handleGroupIdleDuringCall()}
     *
     * @param hfpHandoverDevice is the hfp device that was set to active
     */
    public void setInactiveForHfpHandover(BluetoothDevice hfpHandoverDevice) {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }
        if (getActiveGroupId() != LE_AUDIO_GROUP_ID_INVALID) {
            mHfpHandoverDevice = hfpHandoverDevice;
            if (Flags.leaudioResumeActiveAfterHfpHandover()) {
                // record the lead device
                mLeAudioDeviceInactivatedForHfpHandover = mExposedActiveDevice;
            }
            removeActiveDevice(true);
        }
    }

    /** Resume prior active device after HFP phone call hand over */
    public void setActiveAfterHfpHandover() {
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }
        if (mLeAudioDeviceInactivatedForHfpHandover != null) {
            Log.i(TAG, "handover to LE audio device=" + mLeAudioDeviceInactivatedForHfpHandover);
            setActiveDevice(mLeAudioDeviceInactivatedForHfpHandover);
            mLeAudioDeviceInactivatedForHfpHandover = null;
        } else {
            Log.d(TAG, "nothing to handover back");
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

        if (!mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.LE_AUDIO, connectionPolicy)) {
            return false;
        }

        if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            setEnabledState(device, /* enabled= */ true);
            // Authorizes LEA GATT server services if already assigned to a group
            int groupId = getGroupId(device);
            if (groupId != LE_AUDIO_GROUP_ID_INVALID) {
                setAuthorizationForRelatedProfiles(device, true);
            }
            connect(device);
        } else if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            setEnabledState(device, /* enabled= */ false);
            // Remove authorization for LEA GATT server services
            setAuthorizationForRelatedProfiles(device, false);
            disconnect(device);
        }
        setLeAudioGattClientProfilesPolicy(device, connectionPolicy);
        return true;
    }

    /**
     * Sets the connection policy for LE Audio GATT client profiles
     *
     * @param device is the remote device
     * @param connectionPolicy is the connection policy we wish to set
     */
    private void setLeAudioGattClientProfilesPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(
                TAG,
                "setLeAudioGattClientProfilesPolicy for device "
                        + device
                        + " to policy="
                        + connectionPolicy);
        VolumeControlService volumeControlService = getVolumeControlService();
        if (volumeControlService != null) {
            volumeControlService.setConnectionPolicy(device, connectionPolicy);
        }

        if (mHapClientService == null) {
            mHapClientService = mServiceFactory.getHapClientService();
        }
        if (mHapClientService != null) {
            mHapClientService.setConnectionPolicy(device, connectionPolicy);
        }

        if (mCsipSetCoordinatorService == null) {
            mCsipSetCoordinatorService = mServiceFactory.getCsipSetCoordinatorService();
        }

        // Disallow setting CSIP to forbidden until characteristic reads are complete
        if (mCsipSetCoordinatorService != null) {
            mCsipSetCoordinatorService.setConnectionPolicy(device, connectionPolicy);
        }

        if (mBassClientService == null) {
            mBassClientService = mServiceFactory.getBassClientService();
        }
        if (mBassClientService != null && mBassClientService.isEnabled()) {
            mBassClientService.setConnectionPolicy(device, connectionPolicy);
        }
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p>The connection policy can be any of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        int connection_policy =
                mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO);
        Log.d(TAG, device + " connection policy = " + connection_policy);
        return connection_policy;
    }

    /**
     * Get device group id. Devices with same group id belong to same group (i.e left and right
     * earbud)
     *
     * @param device LE Audio capable device
     * @return group id that this device currently belongs to
     */
    public int getGroupId(BluetoothDevice device) {
        if (device == null) {
            return LE_AUDIO_GROUP_ID_INVALID;
        }

        mGroupReadLock.lock();
        try {
            LeAudioDeviceDescriptor descriptor = getDeviceDescriptor(device);
            if (descriptor == null) {
                Log.e(TAG, "getGroupId: No valid descriptor for device: " + device);
                return LE_AUDIO_GROUP_ID_INVALID;
            }

            return descriptor.mGroupId;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Check if group is available for streaming. If there is no available context types then group
     * is not available for streaming.
     *
     * @param groupId groupid
     * @return true if available, false otherwise
     */
    public boolean isGroupAvailableForStream(int groupId) {
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
            if (descriptor == null) {
                Log.e(TAG, "getGroupId: No valid descriptor for groupId: " + groupId);
                return false;
            }
            return descriptor.mAvailableContexts != 0;
        } finally {
            mGroupReadLock.unlock();
        }
    }

    /**
     * Set the user application ccid along with used context type
     *
     * @param userUuid user uuid
     * @param ccid content control id
     * @param contextType context type
     */
    public void setCcidInformation(ParcelUuid userUuid, int ccid, int contextType) {
        /* for the moment we care only for GMCS and GTBS */
        if (userUuid != BluetoothUuid.GENERIC_MEDIA_CONTROL
                && userUuid.getUuid() != TbsGatt.UUID_GTBS) {
            return;
        }
        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }
        mNativeInterface.setCcidInformation(ccid, contextType);
    }

    /**
     * Set volume for streaming devices
     *
     * @param volume volume to set
     */
    public void setVolume(int volume) {
        Log.d(TAG, "SetVolume " + volume);

        int currentlyActiveGroupId = getActiveGroupId();
        List<BluetoothDevice> activeBroadcastSinks = new ArrayList<>();

        if (currentlyActiveGroupId == LE_AUDIO_GROUP_ID_INVALID) {
            if (!Flags.leaudioBroadcastVolumeControlWithSetVolume()) {
                Log.e(TAG, "There is no active group ");
                return;
            }

            BassClientService bassClientService = getBassClientService();
            if (bassClientService != null) {
                activeBroadcastSinks = bassClientService.getActiveBroadcastSinks();
            }

            if (activeBroadcastSinks.isEmpty()) {
                Log.e(TAG, "There is no active streaming group or broadcast sinks");
                return;
            }
        }

        VolumeControlService volumeControlService = getVolumeControlService();
        if (volumeControlService != null) {
            if (Flags.leaudioBroadcastVolumeControlWithSetVolume()
                    && currentlyActiveGroupId == LE_AUDIO_GROUP_ID_INVALID
                    && !activeBroadcastSinks.isEmpty()) {
                if (Flags.leaudioBroadcastVolumeControlPrimaryGroupOnly()) {
                    if (activeBroadcastSinks.stream()
                            .anyMatch(dev -> isPrimaryGroup(getGroupId(dev)))) {
                        Log.d(
                                TAG,
                                "Setting volume for broadcast sink primary group: "
                                        + mUnicastGroupIdDeactivatedForBroadcastTransition);
                        volumeControlService.setGroupVolume(
                                mUnicastGroupIdDeactivatedForBroadcastTransition, volume);
                    } else {
                        Log.w(TAG, "Setting volume when no active or broadcast primary group");
                    }
                } else {
                    Set<Integer> broadcastGroups =
                            activeBroadcastSinks.stream()
                                    .map(dev -> getGroupId(dev))
                                    .filter(id -> id != IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID)
                                    .collect(Collectors.toSet());

                    Log.d(TAG, "Setting volume for broadcast sink groups: " + broadcastGroups);
                    broadcastGroups.forEach(
                            groupId -> volumeControlService.setGroupVolume(groupId, volume));
                }
            } else {
                volumeControlService.setGroupVolume(currentlyActiveGroupId, volume);
            }
        }
    }

    TbsService getTbsService() {
        if (mTbsService != null) {
            return mTbsService;
        }

        mTbsService = mServiceFactory.getTbsService();
        return mTbsService;
    }

    McpService getMcpService() {
        if (mMcpService != null) {
            return mMcpService;
        }

        mMcpService = mServiceFactory.getMcpService();
        return mMcpService;
    }

    void setAuthorizationForRelatedProfiles(BluetoothDevice device, boolean authorize) {
        McpService mcpService = getMcpService();
        if (mcpService != null) {
            mcpService.setDeviceAuthorized(device, authorize);
        }

        TbsService tbsService = getTbsService();
        if (tbsService != null) {
            tbsService.setDeviceAuthorized(device, authorize);
        }
    }

    void removeAuthorizationInfoForRelatedProfiles(BluetoothDevice device) {
        McpService mcpService = getMcpService();
        if (mcpService != null) {
            mcpService.removeDeviceAuthorizationInfo(device);
        }

        TbsService tbsService = getTbsService();
        if (tbsService != null) {
            tbsService.removeDeviceAuthorizationInfo(device);
        }
    }

    /**
     * This function is called when the framework registers a callback with the service for this
     * first time. This is used as an indication that Bluetooth has been enabled.
     *
     * <p>It is used to authorize all known LeAudio devices in the services which requires that e.g.
     * GMCS
     */
    @VisibleForTesting
    void handleBluetoothEnabled() {
        Log.d(TAG, "handleBluetoothEnabled ");

        mBluetoothEnabled = true;

        mGroupReadLock.lock();
        try {
            if (mDeviceDescriptors.isEmpty()) {
                return;
            }
            for (BluetoothDevice device : mDeviceDescriptors.keySet()) {
                if (getConnectionPolicy(device) != BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
                    setAuthorizationForRelatedProfiles(device, true);
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }

        startAudioServersBackgroundScan(/* retry= */ false);
    }

    @VisibleForTesting
    void handleAudioModeChange(int mode) {
        Log.d(TAG, "Audio mode changed: " + mCurrentAudioMode + " -> " + mode);
        int previousAudioMode = mCurrentAudioMode;

        mCurrentAudioMode = mode;

        switch (mode) {
            case AudioManager.MODE_RINGTONE:
            case AudioManager.MODE_IN_CALL:
            case AudioManager.MODE_IN_COMMUNICATION:
                if (!areBroadcastsAllStopped()) {
                    /* Request activation of unicast group */
                    handleUnicastStreamStatusChange(
                            LeAudioStackEvent.DIRECTION_SINK,
                            LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
                }
                break;
            case AudioManager.MODE_NORMAL:
                /* Remove broadcast if during handover active LE Audio device disappears
                 * (switch to primary device or non LE Audio device)
                 */
                if (isBroadcastReadyToBeReActivated()
                        && isAudioModeChangedFromCommunicationToNormal(
                                previousAudioMode, mCurrentAudioMode)
                        && (getActiveGroupId() == LE_AUDIO_GROUP_ID_INVALID)) {
                    stopBroadcast(mBroadcastIdDeactivatedForUnicastTransition.get());
                    mBroadcastIdDeactivatedForUnicastTransition = Optional.empty();
                    break;
                }

                if (mBroadcastIdDeactivatedForUnicastTransition.isPresent()) {
                    handleUnicastStreamStatusChange(
                            LeAudioStackEvent.DIRECTION_SINK,
                            LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
                }
                break;
            default:
                Log.d(TAG, "Not handled audio mode set: " + mode);
                break;
        }
    }

    private LeAudioGroupDescriptor getGroupDescriptor(int groupId) {
        mGroupReadLock.lock();
        try {
            return mGroupDescriptorsView.get(groupId);
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private LeAudioDeviceDescriptor getDeviceDescriptor(BluetoothDevice device) {
        mGroupReadLock.lock();
        try {
            return mDeviceDescriptors.get(device);
        } finally {
            mGroupReadLock.unlock();
        }
    }

    private void handleGroupNodeAdded(BluetoothDevice device, int groupId) {
        mGroupWriteLock.lock();
        try {
            Log.d(TAG, "Device " + device + " added to group " + groupId);

            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                mGroupDescriptors.put(groupId, new LeAudioGroupDescriptor(false));
            }
            groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "Could not create group description");
                return;
            }
            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
            if (deviceDescriptor == null) {
                deviceDescriptor =
                        createDeviceDescriptor(device, groupDescriptor.mInbandRingtoneEnabled);
                if (deviceDescriptor == null) {
                    Log.e(
                            TAG,
                            "handleGroupNodeAdded: Can't create descriptor for added from"
                                    + " storage device: "
                                    + device);
                    return;
                }

                LeAudioStateMachine unused = getOrCreateStateMachine(device);
                if (getOrCreateStateMachine(device) == null) {
                    Log.e(TAG, "Can't get state machine for device: " + device);
                    return;
                }
            }
            deviceDescriptor.mGroupId = groupId;

            mHandler.post(() -> notifyGroupNodeAdded(device, groupId));
        } finally {
            mGroupWriteLock.unlock();
        }

        if (mBluetoothEnabled) {
            setAuthorizationForRelatedProfiles(device, true);
            startAudioServersBackgroundScan(/* retry= */ false);
        }
    }

    private void notifyGroupNodeAdded(BluetoothDevice device, int groupId) {
        VolumeControlService volumeControlService = getVolumeControlService();
        if (volumeControlService != null) {
            volumeControlService.handleGroupNodeAdded(groupId, device);
        }

        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks.getBroadcastItem(i).onGroupNodeAdded(device, groupId);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void handleGroupNodeRemoved(BluetoothDevice device, int groupId) {
        Log.d(TAG, "Removing device " + device + " grom group " + groupId);

        boolean isGroupEmpty = true;
        mGroupReadLock.lock();
        try {
            LeAudioGroupDescriptor groupDescriptor = getGroupDescriptor(groupId);
            if (groupDescriptor == null) {
                Log.e(TAG, "handleGroupNodeRemoved: No valid descriptor for group: " + groupId);
                return;
            }
            Log.d(TAG, "Lost lead device is " + groupDescriptor.mLostLeadDeviceWhileStreaming);
            if (Objects.equals(device, groupDescriptor.mLostLeadDeviceWhileStreaming)) {
                clearLostDevicesWhileStreaming(groupDescriptor);
            }

            LeAudioDeviceDescriptor deviceDescriptor = getDeviceDescriptor(device);
            if (deviceDescriptor == null) {
                Log.e(TAG, "handleGroupNodeRemoved: No valid descriptor for device: " + device);
                return;
            }
            deviceDescriptor.mGroupId = LE_AUDIO_GROUP_ID_INVALID;

            for (LeAudioDeviceDescriptor descriptor : mDeviceDescriptors.values()) {
                if (descriptor.mGroupId == groupId) {
                    isGroupEmpty = false;
                    break;
                }
            }

            if (isGroupEmpty) {
                /* Device is currently an active device. Group needs to be inactivated before
                 * removing
                 */
                if (Objects.equals(device, mActiveAudioOutDevice)
                        || Objects.equals(device, mActiveAudioInDevice)) {
                    handleGroupTransitToInactive(groupId);
                }

                if (mUnicastGroupIdDeactivatedForBroadcastTransition == groupId) {
                    updateFallbackUnicastGroupIdForBroadcast(LE_AUDIO_GROUP_ID_INVALID);
                }
            }
            mHandler.post(() -> notifyGroupNodeRemoved(device, groupId));
        } finally {
            mGroupReadLock.unlock();
        }

        if (isGroupEmpty) {
            mGroupWriteLock.lock();
            try {
                mGroupDescriptors.remove(groupId);
            } finally {
                mGroupWriteLock.unlock();
            }
        }

        setAuthorizationForRelatedProfiles(device, false);
        removeAuthorizationInfoForRelatedProfiles(device);
    }

    private void notifyGroupNodeRemoved(BluetoothDevice device, int groupId) {
        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks.getBroadcastItem(i).onGroupNodeRemoved(device, groupId);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void notifyGroupStatusChanged(int groupId, int status) {
        synchronized (mLeAudioCallbacks) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks.getBroadcastItem(i).onGroupStatusChanged(groupId, status);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void notifyUnicastCodecConfigChanged(int groupId, BluetoothLeAudioCodecStatus status) {
        if (mLeAudioCallbacks != null) {
            int n = mLeAudioCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mLeAudioCallbacks.getBroadcastItem(i).onCodecConfigChanged(groupId, status);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mLeAudioCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastStarted(Integer broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onBroadcastStarted(reason, broadcastId);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastStartFailed(int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onBroadcastStartFailed(reason);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyOnBroadcastStopped(Integer broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onBroadcastStopped(reason, broadcastId);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyOnBroadcastStopFailed(int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onBroadcastStopFailed(reason);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyPlaybackStarted(Integer broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onPlaybackStarted(reason, broadcastId);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyPlaybackStopped(Integer broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks.getBroadcastItem(i).onPlaybackStopped(reason, broadcastId);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastUpdateFailed(int broadcastId, int reason) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks
                            .getBroadcastItem(i)
                            .onBroadcastUpdateFailed(reason, broadcastId);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    private void notifyBroadcastMetadataChanged(
            int broadcastId, BluetoothLeBroadcastMetadata metadata) {
        synchronized (mBroadcastCallbacks) {
            int n = mBroadcastCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mBroadcastCallbacks
                            .getBroadcastItem(i)
                            .onBroadcastMetadataChanged(broadcastId, metadata);
                } catch (RemoteException e) {
                    continue;
                }
            }
            mBroadcastCallbacks.finishBroadcast();
        }
    }

    /**
     * Update the fallback unicast group id during the handover to broadcast Also store the fallback
     * group id in Settings store.
     *
     * @param groupId group id to update
     */
    private void updateFallbackUnicastGroupIdForBroadcast(int groupId) {
        Log.i(
                TAG,
                "Update unicast fallback active group from: "
                        + mUnicastGroupIdDeactivatedForBroadcastTransition
                        + " to : "
                        + groupId);
        mUnicastGroupIdDeactivatedForBroadcastTransition = groupId;

        // waive WRITE_SECURE_SETTINGS permission check
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            Context userContext =
                    getApplicationContext()
                            .createContextAsUser(
                                    UserHandle.of(ActivityManager.getCurrentUser()), 0);
            Settings.Secure.putInt(
                    userContext.getContentResolver(),
                    BLUETOOTH_LE_BROADCAST_FALLBACK_ACTIVE_GROUP_ID,
                    groupId);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    private boolean isAudioModeChangedFromCommunicationToNormal(int previousMode, int currentMode) {
        switch (previousMode) {
            case AudioManager.MODE_RINGTONE:
            case AudioManager.MODE_IN_CALL:
            case AudioManager.MODE_IN_COMMUNICATION:
                if (currentMode == AudioManager.MODE_NORMAL) {
                    return true;
                }

                return false;
            default:
                return false;
        }
    }

    /**
     * Gets the current codec status (configuration and capability).
     *
     * @param groupId the group id
     * @return the current codec status
     */
    public BluetoothLeAudioCodecStatus getCodecStatus(int groupId) {
        Log.d(TAG, "getCodecStatus(" + groupId + ")");
        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor != null) {
            return descriptor.mCodecStatus;
        }
        return null;
    }

    /**
     * Sets the codec configuration preference.
     *
     * @param groupId the group id
     * @param inputCodecConfig the input codec configuration preference
     * @param outputCodecConfig the output codec configuration preference
     */
    public void setCodecConfigPreference(
            int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig) {
        Log.d(
                TAG,
                "setCodecConfigPreference("
                        + groupId
                        + "): "
                        + Objects.toString(inputCodecConfig)
                        + Objects.toString(outputCodecConfig));
        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            Log.e(TAG, "setCodecConfigPreference: Invalid groupId, " + groupId);
            return;
        }

        if (inputCodecConfig == null || outputCodecConfig == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec config can't be null");
            return;
        }

        /* We support different configuration for input and output but codec type
         * shall be same */
        if (inputCodecConfig.getCodecType() != outputCodecConfig.getCodecType()) {
            Log.e(
                    TAG,
                    "setCodecConfigPreference: Input codec type: "
                            + inputCodecConfig.getCodecType()
                            + "does not match output codec type: "
                            + outputCodecConfig.getCodecType());
            return;
        }

        if (descriptor.mCodecStatus == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec status is null");
            return;
        }

        if (!mLeAudioNativeIsInitialized) {
            Log.e(TAG, "Le Audio not initialized properly.");
            return;
        }

        mNativeInterface.setCodecConfigPreference(groupId, inputCodecConfig, outputCodecConfig);
    }

    /**
     * Checks if the remote device supports LE Audio duplex (output and input).
     *
     * @param device the remote device to check
     * @return {@code true} if LE Audio duplex is supported, {@code false} otherwise
     */
    public boolean isLeAudioDuplexSupported(BluetoothDevice device) {
        int groupId = getGroupId(device);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return false;
        }

        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            return false;
        }
        return (descriptor.mDirection & AUDIO_DIRECTION_OUTPUT_BIT) != 0
                && (descriptor.mDirection & AUDIO_DIRECTION_INPUT_BIT) != 0;
    }

    /**
     * Checks if the remote device supports LE Audio output
     *
     * @param device the remote device to check
     * @return {@code true} if LE Audio output is supported, {@code false} otherwise
     */
    public boolean isLeAudioOutputSupported(BluetoothDevice device) {
        int groupId = getGroupId(device);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return false;
        }

        LeAudioGroupDescriptor descriptor = getGroupDescriptor(groupId);
        if (descriptor == null) {
            return false;
        }
        return (descriptor.mDirection & AUDIO_DIRECTION_OUTPUT_BIT) != 0;
    }

    /**
     * Gets the lead device for the CSIP group containing the provided device
     *
     * @param device the remote device whose CSIP group lead device we want to find
     * @return the lead device of the CSIP group or {@code null} if the group does not exist
     */
    public BluetoothDevice getLeadDevice(BluetoothDevice device) {
        int groupId = getGroupId(device);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return null;
        }
        return getConnectedGroupLeadDevice(groupId);
    }

    /**
     * Sends the preferred audio profile change requested from a call to {@link
     * BluetoothAdapter#setPreferredAudioProfiles(BluetoothDevice, Bundle)} to the audio framework
     * to apply the change. The audio framework will call {@link
     * BluetoothAdapter#notifyActiveDeviceChangeApplied(BluetoothDevice)} once the change is
     * successfully applied.
     *
     * @return the number of requests sent to the audio framework
     */
    public int sendPreferredAudioProfileChangeToAudioFramework() {
        if (mActiveAudioOutDevice == null && mActiveAudioInDevice == null) {
            Log.e(TAG, "sendPreferredAudioProfileChangeToAudioFramework: no active device");
            return 0;
        }

        int audioFrameworkCalls = 0;

        if (mActiveAudioOutDevice != null) {
            int volume = getAudioDeviceGroupVolume(getGroupId(mActiveAudioOutDevice));
            final boolean suppressNoisyIntent = mActiveAudioOutDevice != null;
            Log.i(
                    TAG,
                    "Sending LE Audio Output active device changed for preferred profile "
                            + "change with volume="
                            + volume
                            + " and suppressNoisyIntent="
                            + suppressNoisyIntent);

            final BluetoothProfileConnectionInfo connectionInfo;
            if (isAtLeastU()) {
                connectionInfo =
                        BluetoothProfileConnectionInfo.createLeAudioOutputInfo(
                                suppressNoisyIntent, volume);
            } else {
                connectionInfo =
                        BluetoothProfileConnectionInfo.createLeAudioInfo(suppressNoisyIntent, true);
            }

            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioOutDevice, mActiveAudioOutDevice, connectionInfo);
            audioFrameworkCalls++;
        }

        if (mActiveAudioInDevice != null) {
            Log.i(TAG, "Sending LE Audio Input active device changed for audio profile change");
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveAudioInDevice,
                    mActiveAudioInDevice,
                    BluetoothProfileConnectionInfo.createLeAudioInfo(false, false));
            audioFrameworkCalls++;
        }

        return audioFrameworkCalls;
    }

    class DialingOutTimeoutEvent implements Runnable {
        Integer mBroadcastId;

        DialingOutTimeoutEvent(Integer broadcastId) {
            mBroadcastId = broadcastId;
        }

        @Override
        public void run() {
            Log.w(TAG, "Failed to start Broadcast in time: " + mBroadcastId);

            mDialingOutTimeoutEvent = null;

            if (getLeAudioService() == null) {
                Log.e(TAG, "DialingOutTimeoutEvent: No LE Audio service");
                return;
            }

            if (Flags.leaudioBroadcastDestroyAfterTimeout()) {
                transitionFromBroadcastToUnicast();
                destroyBroadcast(mBroadcastId);
            } else {
                if (mActiveBroadcastAudioDevice != null) {
                    updateBroadcastActiveDevice(null, mActiveBroadcastAudioDevice, false);
                }

                mHandler.post(() -> notifyBroadcastStartFailed(BluetoothStatusCodes.ERROR_TIMEOUT));
            }
        }
    }

    class AudioModeChangeListener implements AudioManager.OnModeChangedListener {
        @Override
        public void onModeChanged(int mode) {
            handleAudioModeChange(mode);
        }
    }

    /** Binder object: must be a static class or memory leak may occur */
    @VisibleForTesting
    static class BluetoothLeAudioBinder extends IBluetoothLeAudio.Stub
            implements IProfileServiceBinder {
        private LeAudioService mService;

        BluetoothLeAudioBinder(LeAudioService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        private LeAudioService getServiceAndEnforceConnect(AttributionSource source) {
            Objects.requireNonNull(source);
            // Cache mService because it can change while getService is called
            LeAudioService service = mService;

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

        private LeAudioService getService() {
            // Cache mService because it can change while getService is called
            LeAudioService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)) {
                return null;
            }
            return service;
        }

        @Override
        public boolean connect(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getConnectedDevices();
        }

        @Override
        public BluetoothDevice getConnectedGroupLeadDevice(int groupId, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return null;
            }

            return service.getConnectedGroupLeadDevice(groupId);
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public boolean setActiveDevice(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            if (Flags.audioRoutingCentralization()) {
                return ((AudioRoutingManager) service.mAdapterService.getActiveDeviceManager())
                        .activateDeviceProfile(device, BluetoothProfile.LE_AUDIO)
                        .join();
            }
            if (device == null) {
                return service.removeActiveDevice(true);
            } else {
                return service.setActiveDevice(device);
            }
        }

        @Override
        public List<BluetoothDevice> getActiveDevices(AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getActiveDevices();
        }

        @Override
        public int getAudioLocation(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return BluetoothLeAudio.AUDIO_LOCATION_INVALID;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getAudioLocation(device);
        }

        @Override
        public boolean isInbandRingtoneEnabled(AttributionSource source, int groupId) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.isInbandRingtoneEnabled(groupId);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
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

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getConnectionPolicy(device);
        }

        @Override
        public void setCcidInformation(
                ParcelUuid userUuid, int ccid, int contextType, AttributionSource source) {
            Objects.requireNonNull(userUuid, "userUuid cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setCcidInformation(userUuid, ccid, contextType);
        }

        @Override
        public int getGroupId(BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return LE_AUDIO_GROUP_ID_INVALID;
            }

            return service.getGroupId(device);
        }

        @Override
        public boolean groupAddNode(int groupId, BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.groupAddNode(groupId, device);
        }

        @Override
        public void setInCall(boolean inCall, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setInCall(inCall);
        }

        @Override
        public void setInactiveForHfpHandover(
                BluetoothDevice hfpHandoverDevice, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setInactiveForHfpHandover(hfpHandoverDevice);
        }

        @Override
        public boolean groupRemoveNode(
                int groupId, BluetoothDevice device, AttributionSource source) {
            Objects.requireNonNull(device, "device cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.groupRemoveNode(groupId, device);
        }

        @Override
        public void setVolume(int volume, AttributionSource source) {
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setVolume(volume);
        }

        @Override
        public void registerCallback(IBluetoothLeAudioCallback callback, AttributionSource source) {
            Objects.requireNonNull(callback, "callback cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            synchronized (service.mLeAudioCallbacks) {
                service.mLeAudioCallbacks.register(callback);
            }
            if (!service.mBluetoothEnabled) {
                service.handleBluetoothEnabled();
            }
        }

        @Override
        public void unregisterCallback(
                IBluetoothLeAudioCallback callback, AttributionSource source) {
            Objects.requireNonNull(callback, "callback cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            synchronized (service.mLeAudioCallbacks) {
                service.mLeAudioCallbacks.unregister(callback);
            }
        }

        @Override
        public void registerLeBroadcastCallback(
                IBluetoothLeBroadcastCallback callback, AttributionSource source) {
            Objects.requireNonNull(callback, "callback cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if ((service == null) || (service.mBroadcastCallbacks == null)) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            synchronized (service.mBroadcastCallbacks) {
                service.mBroadcastCallbacks.register(callback);
            }
        }

        @Override
        public void unregisterLeBroadcastCallback(
                IBluetoothLeBroadcastCallback callback, AttributionSource source) {
            Objects.requireNonNull(callback, "callback cannot be null");
            Objects.requireNonNull(source, "source cannot be null");

            LeAudioService service = getServiceAndEnforceConnect(source);
            if ((service == null) || (service.mBroadcastCallbacks == null)) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            synchronized (service.mBroadcastCallbacks) {
                service.mBroadcastCallbacks.unregister(callback);
            }
        }

        @Override
        public void startBroadcast(
                BluetoothLeBroadcastSettings broadcastSettings, AttributionSource source) {
            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.createBroadcast(broadcastSettings);
        }

        @Override
        public void stopBroadcast(int broadcastId, AttributionSource source) {
            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.stopBroadcast(broadcastId);
        }

        @Override
        public void updateBroadcast(
                int broadcastId,
                BluetoothLeBroadcastSettings broadcastSettings,
                AttributionSource source) {
            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.updateBroadcast(broadcastId, broadcastSettings);
        }

        @Override
        public boolean isPlaying(int broadcastId, AttributionSource source) {
            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.isPlaying(broadcastId);
        }

        @Override
        public List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata(
                AttributionSource source) {
            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getAllBroadcastMetadata();
        }

        @Override
        public int getMaximumNumberOfBroadcasts() {
            LeAudioService service = getService();
            if (service == null) {
                return 0;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getMaximumNumberOfBroadcasts();
        }

        @Override
        public int getMaximumStreamsPerBroadcast() {
            LeAudioService service = getService();
            if (service == null) {
                return 0;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getMaximumStreamsPerBroadcast();
        }

        @Override
        public int getMaximumSubgroupsPerBroadcast() {
            LeAudioService service = getService();
            if (service == null) {
                return 0;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getMaximumSubgroupsPerBroadcast();
        }

        @Override
        public BluetoothLeAudioCodecStatus getCodecStatus(int groupId, AttributionSource source) {
            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return null;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.getCodecStatus(groupId);
        }

        @Override
        public void setCodecConfigPreference(
                int groupId,
                BluetoothLeAudioCodecConfig inputCodecConfig,
                BluetoothLeAudioCodecConfig outputCodecConfig,
                AttributionSource source) {
            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            service.setCodecConfigPreference(groupId, inputCodecConfig, outputCodecConfig);
        }

        @Override
        public boolean isBroadcastActive(AttributionSource source) {
            LeAudioService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            return service.isBroadcastActive();
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        ProfileService.println(sb, "isDualModeAudioEnabled: " + Utils.isDualModeAudioEnabled());
        ProfileService.println(sb, "Active Groups information: ");
        ProfileService.println(sb, "  currentlyActiveGroupId: " + getActiveGroupId());
        ProfileService.println(sb, "  mActiveAudioOutDevice: " + mActiveAudioOutDevice);
        ProfileService.println(sb, "  mActiveAudioInDevice: " + mActiveAudioInDevice);
        ProfileService.println(
                sb,
                "  mUnicastGroupIdDeactivatedForBroadcastTransition: "
                        + mUnicastGroupIdDeactivatedForBroadcastTransition);
        ProfileService.println(
                sb,
                "  mBroadcastIdDeactivatedForUnicastTransition: "
                        + mBroadcastIdDeactivatedForUnicastTransition);
        ProfileService.println(sb, "  mExposedActiveDevice: " + mExposedActiveDevice);
        ProfileService.println(sb, "  mHfpHandoverDevice:" + mHfpHandoverDevice);
        ProfileService.println(
                sb,
                " mLeAudioDeviceInactivatedForHfpHandover:"
                        + mLeAudioDeviceInactivatedForHfpHandover);
        ProfileService.println(
                sb,
                "  mLeAudioIsInbandRingtoneSupported:" + mLeAudioInbandRingtoneSupportedByPlatform);

        int numberOfUngroupedDevs = 0;
        mGroupReadLock.lock();
        try {
            for (Map.Entry<Integer, LeAudioGroupDescriptor> groupEntry :
                    mGroupDescriptorsView.entrySet()) {
                LeAudioGroupDescriptor groupDescriptor = groupEntry.getValue();
                Integer groupId = groupEntry.getKey();
                BluetoothDevice leadDevice = getConnectedGroupLeadDevice(groupId);

                ProfileService.println(sb, "Group: " + groupId);
                ProfileService.println(
                        sb, "  activeState: " + groupDescriptor.getActiveStateString());
                ProfileService.println(sb, "  isConnected: " + groupDescriptor.mIsConnected);
                ProfileService.println(sb, "  mDirection: " + groupDescriptor.mDirection);
                ProfileService.println(sb, "  group lead: " + leadDevice);
                ProfileService.println(
                        sb, "  lost lead device: " + groupDescriptor.mLostLeadDeviceWhileStreaming);
                ProfileService.println(
                        sb, "  mInbandRingtoneEnabled: " + groupDescriptor.mInbandRingtoneEnabled);
                ProfileService.println(
                        sb,
                        "mInactivatedDueToContextType: "
                                + groupDescriptor.mInactivatedDueToContextType);

                for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> deviceEntry :
                        mDeviceDescriptors.entrySet()) {
                    LeAudioDeviceDescriptor deviceDescriptor = deviceEntry.getValue();
                    if (!Objects.equals(deviceDescriptor.mGroupId, groupId)) {
                        if (deviceDescriptor.mGroupId == LE_AUDIO_GROUP_ID_INVALID) {
                            numberOfUngroupedDevs++;
                        }
                        continue;
                    }

                    if (deviceDescriptor.mStateMachine != null) {
                        deviceDescriptor.mStateMachine.dump(sb);
                    } else {
                        ProfileService.println(sb, "state machine is null");
                    }
                    ProfileService.println(
                            sb, "    mAclConnected: " + deviceDescriptor.mAclConnected);
                    ProfileService.println(
                            sb,
                            "    mDevInbandRingtoneEnabled: "
                                    + deviceDescriptor.mDevInbandRingtoneEnabled);
                    ProfileService.println(
                            sb, "    mSinkAudioLocation: " + deviceDescriptor.mSinkAudioLocation);
                    ProfileService.println(sb, "    mDirection: " + deviceDescriptor.mDirection);
                }
            }
        } finally {
            mGroupReadLock.unlock();
        }

        if (numberOfUngroupedDevs > 0) {
            ProfileService.println(sb, "UnGroup devices:");
            for (Map.Entry<BluetoothDevice, LeAudioDeviceDescriptor> entry :
                    mDeviceDescriptors.entrySet()) {
                LeAudioDeviceDescriptor deviceDescriptor = entry.getValue();
                if (deviceDescriptor.mGroupId != LE_AUDIO_GROUP_ID_INVALID) {
                    continue;
                }

                deviceDescriptor.mStateMachine.dump(sb);
                ProfileService.println(sb, "    mAclConnected: " + deviceDescriptor.mAclConnected);
                ProfileService.println(
                        sb,
                        "    mDevInbandRingtoneEnabled: "
                                + deviceDescriptor.mDevInbandRingtoneEnabled);
                ProfileService.println(
                        sb, "    mSinkAudioLocation: " + deviceDescriptor.mSinkAudioLocation);
                ProfileService.println(sb, "    mDirection: " + deviceDescriptor.mDirection);
            }
        }
    }
}
