/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2016-2017 The Linux Foundation
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

import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
import static android.bluetooth.BluetoothAdapter.SCAN_MODE_NONE;
import static android.bluetooth.BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
import static android.bluetooth.BluetoothDevice.TRANSPORT_AUTO;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import static com.android.bluetooth.ChangeIds.ENFORCE_CONNECT;
import static com.android.bluetooth.Utils.callerIsSystem;
import static com.android.bluetooth.Utils.callerIsSystemOrActiveOrManagedUser;
import static com.android.bluetooth.Utils.enforceBluetoothPrivilegedPermission;
import static com.android.bluetooth.Utils.enforceCdmAssociation;
import static com.android.bluetooth.Utils.enforceDumpPermission;
import static com.android.bluetooth.Utils.enforceLocalMacAddressPermission;
import static com.android.bluetooth.Utils.getBytesFromAddress;
import static com.android.bluetooth.Utils.hasBluetoothPrivilegedPermission;
import static com.android.bluetooth.Utils.isDualModeAudioEnabled;
import static com.android.bluetooth.Utils.isPackageNameAccurate;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.app.compat.CompatChanges;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.ActiveDeviceProfile;
import android.bluetooth.BluetoothAdapter.ActiveDeviceUse;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothFrameworkInitializer;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.BluetoothQualityReport;
import android.bluetooth.BluetoothSap;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BufferConstraints;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothActivityEnergyInfoListener;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothConnectionCallback;
import android.bluetooth.IBluetoothMetadataListener;
import android.bluetooth.IBluetoothOobDataCallback;
import android.bluetooth.IBluetoothPreferredAudioProfilesCallback;
import android.bluetooth.IBluetoothQualityReportReadyCallback;
import android.bluetooth.IBluetoothSocketManager;
import android.bluetooth.IncomingRfcommSocketInfo;
import android.bluetooth.OobData;
import android.bluetooth.UidTraffic;
import android.bluetooth.rfcomm.BluetoothRfcommProtoEnums;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.BatteryStatsManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.avrcp.AvrcpTargetService;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerService;
import com.android.bluetooth.bas.BatteryService;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.InteropUtil.InteropFeature;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import com.android.bluetooth.btservice.bluetoothkeystore.BluetoothKeystoreNativeInterface;
import com.android.bluetooth.btservice.bluetoothkeystore.BluetoothKeystoreService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.btservice.storage.MetadataDatabase;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.gatt.GattService;
import com.android.bluetooth.hap.HapClientService;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hfpclient.HeadsetClientService;
import com.android.bluetooth.hid.HidDeviceService;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.le_scan.ScanManager;
import com.android.bluetooth.map.BluetoothMapService;
import com.android.bluetooth.mapclient.MapClientService;
import com.android.bluetooth.mcp.McpService;
import com.android.bluetooth.opp.BluetoothOppService;
import com.android.bluetooth.pan.PanService;
import com.android.bluetooth.pbap.BluetoothPbapService;
import com.android.bluetooth.pbapclient.PbapClientService;
import com.android.bluetooth.sap.SapService;
import com.android.bluetooth.sdp.SdpManager;
import com.android.bluetooth.tbs.TbsService;
import com.android.bluetooth.telephony.BluetoothInCallService;
import com.android.bluetooth.vc.VolumeControlService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.expresslog.Counter;
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.BytesMatcher;

import libcore.util.SneakyThrow;

import com.google.common.base.Ascii;
import com.google.common.collect.EvictingQueue;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AdapterService extends Service {
    private static final String TAG = "BluetoothAdapterService";

    private static final int MESSAGE_PROFILE_SERVICE_STATE_CHANGED = 1;
    private static final int MESSAGE_PROFILE_SERVICE_REGISTERED = 2;
    private static final int MESSAGE_PROFILE_SERVICE_UNREGISTERED = 3;
    private static final int MESSAGE_PREFERRED_AUDIO_PROFILES_AUDIO_FRAMEWORK_TIMEOUT = 4;

    private static final int CONTROLLER_ENERGY_UPDATE_TIMEOUT_MILLIS = 100;
    private static final int MIN_ADVT_INSTANCES_FOR_MA = 5;
    private static final int MIN_OFFLOADED_FILTERS = 10;
    private static final int MIN_OFFLOADED_SCAN_STORAGE_BYTES = 1024;

    private static final Duration PENDING_SOCKET_HANDOFF_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration GENERATE_LOCAL_OOB_DATA_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration PREFERRED_AUDIO_PROFILE_CHANGE_TIMEOUT = Duration.ofSeconds(10);

    static final String PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE = "phonebook_access_permission";
    static final String MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE = "message_access_permission";
    static final String SIM_ACCESS_PERMISSION_PREFERENCE_FILE = "sim_access_permission";

    private static BluetoothProperties.snoop_log_mode_values sSnoopLogSettingAtEnable =
            BluetoothProperties.snoop_log_mode_values.EMPTY;
    private static String sDefaultSnoopLogSettingAtEnable = "empty";
    private static boolean sSnoopLogFilterHeadersSettingAtEnable = false;
    private static boolean sSnoopLogFilterProfileA2dpSettingAtEnable = false;
    private static boolean sSnoopLogFilterProfileRfcommSettingAtEnable = false;

    private static BluetoothProperties.snoop_log_filter_profile_pbap_values
            sSnoopLogFilterProfilePbapModeSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_profile_pbap_values.EMPTY;
    private static BluetoothProperties.snoop_log_filter_profile_map_values
            sSnoopLogFilterProfileMapModeSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_profile_map_values.EMPTY;

    private static AdapterService sAdapterService;

    private final Object mEnergyInfoLock = new Object();
    private final SparseArray<UidTraffic> mUidTraffic = new SparseArray<>();

    private final Map<Integer, ProfileService> mStartedProfiles = new HashMap<>();
    private final List<ProfileService> mRegisteredProfiles = new ArrayList<>();
    private final List<ProfileService> mRunningProfiles = new ArrayList<>();

    private final List<DiscoveringPackage> mDiscoveringPackages = new ArrayList<>();

    private final AdapterNativeInterface mNativeInterface = AdapterNativeInterface.getInstance();

    private final Map<BluetoothDevice, List<IBluetoothMetadataListener>> mMetadataListeners =
            new HashMap<>();

    // Map<groupId, PendingAudioProfilePreferenceRequest>
    @GuardedBy("mCsipGroupsPendingAudioProfileChanges")
    private final Map<Integer, PendingAudioProfilePreferenceRequest>
            mCsipGroupsPendingAudioProfileChanges = new HashMap<>();

    private final Map<BluetoothStateCallback, Executor> mLocalCallbacks = new ConcurrentHashMap<>();
    private final Map<UUID, RfcommListenerData> mBluetoothServerSockets = new ConcurrentHashMap<>();
    private final ArrayDeque<IBluetoothOobDataCallback> mOobDataCallbackQueue = new ArrayDeque<>();

    private final RemoteCallbackList<IBluetoothPreferredAudioProfilesCallback>
            mPreferredAudioProfilesCallbacks = new RemoteCallbackList<>();
    private final RemoteCallbackList<IBluetoothQualityReportReadyCallback>
            mBluetoothQualityReportReadyCallbacks = new RemoteCallbackList<>();
    private final RemoteCallbackList<IBluetoothCallback> mRemoteCallbacks =
            new RemoteCallbackList<>();

    private final EvictingQueue<String> mScanModeChanges = EvictingQueue.create(10);

    private final DeviceConfigListener mDeviceConfigListener = new DeviceConfigListener();

    private final Looper mLooper;
    private final AdapterServiceHandler mHandler;

    private int mStackReportedState;
    private long mTxTimeTotalMs;
    private long mRxTimeTotalMs;
    private long mIdleTimeTotalMs;
    private long mEnergyUsedTotalVoltAmpSecMicro;
    private HashSet<String> mLeAudioAllowDevices = new HashSet<>();

    private BluetoothAdapter mAdapter;
    @VisibleForTesting AdapterProperties mAdapterProperties;
    private AdapterState mAdapterStateMachine;
    private BondStateMachine mBondStateMachine;
    private RemoteDevices mRemoteDevices;

    /* TODO: Consider to remove the search API from this class, if changed to use call-back */
    private SdpManager mSdpManager = null;

    private boolean mNativeAvailable;
    private boolean mCleaningUp;
    private Set<IBluetoothConnectionCallback> mBluetoothConnectionCallbacks = new HashSet<>();
    private boolean mQuietmode = false;
    private Map<String, CallerInfo> mBondAttemptCallerInfo = new HashMap<>();

    private BatteryStatsManager mBatteryStatsManager;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private UserManager mUserManager;
    private CompanionDeviceManager mCompanionDeviceManager;

    // Phone Policy is not used on all devices. Ensure you null check before using it
    @Nullable private PhonePolicy mPhonePolicy;

    private ActiveDeviceManager mActiveDeviceManager;
    private final DatabaseManager mDatabaseManager;
    private final SilenceDeviceManager mSilenceDeviceManager;
    private CompanionManager mBtCompanionManager;
    private AppOpsManager mAppOps;

    private BluetoothSocketManagerBinder mBluetoothSocketManagerBinder;

    private BluetoothKeystoreService mBluetoothKeystoreService;
    private A2dpService mA2dpService;
    private A2dpSinkService mA2dpSinkService;
    private HeadsetService mHeadsetService;
    private HeadsetClientService mHeadsetClientService;
    private BluetoothMapService mMapService;
    private MapClientService mMapClientService;
    private HidDeviceService mHidDeviceService;
    private HidHostService mHidHostService;
    private PanService mPanService;
    private BluetoothPbapService mPbapService;
    private PbapClientService mPbapClientService;
    private HearingAidService mHearingAidService;
    private HapClientService mHapClientService;
    private SapService mSapService;
    private VolumeControlService mVolumeControlService;
    private CsipSetCoordinatorService mCsipSetCoordinatorService;
    private LeAudioService mLeAudioService;
    private BassClientService mBassClientService;
    private BatteryService mBatteryService;
    private BluetoothQualityReportNativeInterface mBluetoothQualityReportNativeInterface;
    private GattService mGattService;
    private ScanController mScanController;

    private volatile boolean mTestModeEnabled = false;

    private MetricsLogger mMetricsLogger;

    /** Handlers for incoming service calls */
    private AdapterServiceBinder mBinder;

    private volatile int mScanMode;

    // Report ID definition
    public enum BqrQualityReportId {
        QUALITY_REPORT_ID_MONITOR_MODE(0x01),
        QUALITY_REPORT_ID_APPROACH_LSTO(0x02),
        QUALITY_REPORT_ID_A2DP_AUDIO_CHOPPY(0x03),
        QUALITY_REPORT_ID_SCO_VOICE_CHOPPY(0x04),
        QUALITY_REPORT_ID_ROOT_INFLAMMATION(0x05),
        QUALITY_REPORT_ID_CONNECT_FAIL(0x08),
        QUALITY_REPORT_ID_LMP_LL_MESSAGE_TRACE(0x11),
        QUALITY_REPORT_ID_BT_SCHEDULING_TRACE(0x12),
        QUALITY_REPORT_ID_CONTROLLER_DBG_INFO(0x13);

        private final int mValue;

        BqrQualityReportId(int value) {
            mValue = value;
        }

        public int getValue() {
            return mValue;
        }
    };

    static {
        if (!Flags.avoidStaticLoadingOfNative()) {
            Log.d(TAG, "Loading JNI Library");
            if (Utils.isInstrumentationTestMode()) {
                Log.w(TAG, "App is instrumented. Skip loading the native");
            } else {
                System.loadLibrary("bluetooth_jni");
            }
        }
    }

    // Keep a constructor for ActivityThread.handleCreateService
    AdapterService() {
        this(Looper.getMainLooper());
    }

    @VisibleForTesting
    AdapterService(Looper looper) {
        mLooper = requireNonNull(looper);
        mHandler = new AdapterServiceHandler(mLooper);
        mSilenceDeviceManager = new SilenceDeviceManager(this, new ServiceFactory(), mLooper);
        mDatabaseManager = new DatabaseManager(this);
    }

    @VisibleForTesting
    public AdapterService(Context ctx) {
        this(Looper.getMainLooper());
        attachBaseContext(ctx);
    }

    public static synchronized AdapterService getAdapterService() {
        return sAdapterService;
    }

    AdapterNativeInterface getNative() {
        return mNativeInterface;
    }

    /** Allow test to set an AdapterService to be return by AdapterService.getAdapterService() */
    @VisibleForTesting
    public static synchronized void setAdapterService(AdapterService instance) {
        if (instance == null) {
            Log.e(TAG, "setAdapterService() - instance is null");
            return;
        }
        Log.d(TAG, "setAdapterService() - set service to " + instance);
        sAdapterService = instance;
    }

    /** Clear test Adapter service. See {@code setAdapterService} */
    @VisibleForTesting
    public static synchronized void clearAdapterService(AdapterService instance) {
        if (sAdapterService == instance) {
            Log.d(TAG, "clearAdapterService() - This adapter was cleared " + instance);
            sAdapterService = null;
        } else {
            Log.d(
                    TAG,
                    "clearAdapterService() - incorrect cleared adapter."
                            + (" Instance=" + instance)
                            + (" vs sAdapterService=" + sAdapterService));
        }
    }

    /**
     * Register a {@link ProfileService} with AdapterService.
     *
     * @param profile the service being added.
     */
    public void addProfile(ProfileService profile) {
        mHandler.obtainMessage(MESSAGE_PROFILE_SERVICE_REGISTERED, profile).sendToTarget();
    }

    /**
     * Unregister a ProfileService with AdapterService.
     *
     * @param profile the service being removed.
     */
    public void removeProfile(ProfileService profile) {
        mHandler.obtainMessage(MESSAGE_PROFILE_SERVICE_UNREGISTERED, profile).sendToTarget();
    }

    /**
     * Notify AdapterService that a ProfileService has started or stopped.
     *
     * @param profile the service being removed.
     * @param state {@link BluetoothAdapter#STATE_ON} or {@link BluetoothAdapter#STATE_OFF}
     */
    public void onProfileServiceStateChanged(ProfileService profile, int state) {
        if (state != BluetoothAdapter.STATE_ON && state != BluetoothAdapter.STATE_OFF) {
            throw new IllegalArgumentException(BluetoothAdapter.nameForState(state));
        }
        Message m = mHandler.obtainMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        m.obj = profile;
        m.arg1 = state;
        mHandler.sendMessage(m);
    }

    class AdapterServiceHandler extends Handler {
        AdapterServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.v(TAG, "handleMessage() - Message: " + msg.what);

            switch (msg.what) {
                case MESSAGE_PROFILE_SERVICE_STATE_CHANGED:
                    Log.v(TAG, "handleMessage() - MESSAGE_PROFILE_SERVICE_STATE_CHANGED");
                    processProfileServiceStateChanged((ProfileService) msg.obj, msg.arg1);
                    break;
                case MESSAGE_PROFILE_SERVICE_REGISTERED:
                    Log.v(TAG, "handleMessage() - MESSAGE_PROFILE_SERVICE_REGISTERED");
                    registerProfileService((ProfileService) msg.obj);
                    break;
                case MESSAGE_PROFILE_SERVICE_UNREGISTERED:
                    Log.v(TAG, "handleMessage() - MESSAGE_PROFILE_SERVICE_UNREGISTERED");
                    unregisterProfileService((ProfileService) msg.obj);
                    break;
                case MESSAGE_PREFERRED_AUDIO_PROFILES_AUDIO_FRAMEWORK_TIMEOUT:
                    Log.e(
                            TAG,
                            "handleMessage() - "
                                    + "MESSAGE_PREFERRED_PROFILE_CHANGE_AUDIO_FRAMEWORK_TIMEOUT");
                    int groupId = (int) msg.obj;

                    synchronized (mCsipGroupsPendingAudioProfileChanges) {
                        removeFromPendingAudioProfileChanges(groupId);
                        PendingAudioProfilePreferenceRequest request =
                                mCsipGroupsPendingAudioProfileChanges.remove(groupId);
                        Log.e(
                                TAG,
                                "Preferred audio profiles change audio framework timeout for "
                                        + ("device " + request.mDeviceRequested));
                        sendPreferredAudioProfilesCallbackToApps(
                                request.mDeviceRequested,
                                request.mRequestedPreferences,
                                BluetoothStatusCodes.ERROR_TIMEOUT);
                    }
                    break;
            }
        }

        private void registerProfileService(ProfileService profile) {
            if (mRegisteredProfiles.contains(profile)) {
                Log.e(TAG, profile.getName() + " already registered.");
                return;
            }
            mRegisteredProfiles.add(profile);
        }

        private void unregisterProfileService(ProfileService profile) {
            if (!mRegisteredProfiles.contains(profile)) {
                Log.e(TAG, profile.getName() + " not registered (UNREGISTER).");
                return;
            }
            mRegisteredProfiles.remove(profile);
        }

        private void processProfileServiceStateChanged(ProfileService profile, int state) {
            switch (state) {
                case BluetoothAdapter.STATE_ON:
                    if (!mRegisteredProfiles.contains(profile)) {
                        Log.e(TAG, profile.getName() + " not registered (STATE_ON).");
                        return;
                    }
                    if (mRunningProfiles.contains(profile)) {
                        Log.e(TAG, profile.getName() + " already running.");
                        return;
                    }
                    mRunningProfiles.add(profile);
                    // TODO(b/228875190): GATT is assumed supported. GATT starting triggers hardware
                    // initialization. Configuring a device without GATT causes start up failures.
                    if (GattService.class.getSimpleName().equals(profile.getName())
                            && !Flags.scanManagerRefactor()) {
                        mNativeInterface.enable();
                    } else if (mRegisteredProfiles.size() == Config.getSupportedProfiles().length
                            && mRegisteredProfiles.size() == mRunningProfiles.size()) {
                        mAdapterProperties.onBluetoothReady();
                        setScanMode(SCAN_MODE_CONNECTABLE, "processProfileServiceStateChanged");
                        updateUuids();
                        initProfileServices();
                        mNativeInterface.getAdapterProperty(
                                AbstractionLayer.BT_PROPERTY_DYNAMIC_AUDIO_BUFFER);
                        mAdapterStateMachine.sendMessage(AdapterState.BREDR_STARTED);
                        mBtCompanionManager.loadCompanionInfo();
                    }
                    break;
                case BluetoothAdapter.STATE_OFF:
                    if (!mRegisteredProfiles.contains(profile)) {
                        Log.e(TAG, profile.getName() + " not registered (STATE_OFF).");
                        return;
                    }
                    if (!mRunningProfiles.contains(profile)) {
                        Log.e(TAG, profile.getName() + " not running.");
                        return;
                    }
                    mRunningProfiles.remove(profile);

                    if (Flags.scanManagerRefactor()) {
                        if (mRunningProfiles.size() == 0) {
                            mAdapterStateMachine.sendMessage(AdapterState.BREDR_STOPPED);
                        }
                    } else {
                        // TODO(b/228875190): GATT is assumed supported. GATT is expected to be the
                        // only profile available in the "BLE ON" state. If only GATT is left, send
                        // BREDR_STOPPED. If GATT is stopped, deinitialize the hardware.
                        if ((mRunningProfiles.size() == 1
                                && (GattService.class
                                        .getSimpleName()
                                        .equals(mRunningProfiles.get(0).getName())))) {
                            mAdapterStateMachine.sendMessage(AdapterState.BREDR_STOPPED);
                        } else if (mRunningProfiles.size() == 0) {
                            mNativeInterface.disable();
                        }
                    }
                    break;
                default:
                    Log.e(TAG, "Unhandled profile state: " + state);
            }
        }
    }

    /**
     * Stores information about requests made to the audio framework arising from calls to {@link
     * BluetoothAdapter#setPreferredAudioProfiles(BluetoothDevice, Bundle)}.
     */
    private static class PendingAudioProfilePreferenceRequest {
        // The newly requested preferences
        final Bundle mRequestedPreferences;
        // Reference counter for how many calls are pending completion in the audio framework
        int mRemainingRequestsToAudioFramework;
        // The device with which the request was made. Used for sending the callback.
        final BluetoothDevice mDeviceRequested;

        /**
         * Constructs an entity to store information about pending preferred audio profile changes.
         *
         * @param preferences newly requested preferences
         * @param numRequestsToAudioFramework how many active device changed requests are sent to
         *     the audio framework
         * @param device the device with which the request was made
         */
        PendingAudioProfilePreferenceRequest(
                Bundle preferences, int numRequestsToAudioFramework, BluetoothDevice device) {
            mRequestedPreferences = preferences;
            mRemainingRequestsToAudioFramework = numRequestsToAudioFramework;
            mDeviceRequested = device;
        }
    }

    final @NonNull <T> T getNonNullSystemService(@NonNull Class<T> clazz) {
        return requireNonNull(getSystemService(clazz), clazz.getSimpleName() + " cannot be null");
    }

    @Override
    @RequiresPermission(
            allOf = {
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                android.Manifest.permission.READ_DEVICE_CONFIG,
            },
            anyOf = {
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                android.Manifest.permission.INTERACT_ACROSS_USERS,
                android.Manifest.permission.CREATE_USERS,
                android.Manifest.permission.MANAGE_USERS,
            })
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate()");
        if (!Flags.fastBindToApp()) {
            init();
            return;
        }
        // OnCreate must perform the minimum of infaillible and mandatory initialization
        mRemoteDevices = new RemoteDevices(this, mLooper);
        mAdapterProperties = new AdapterProperties(this);
        mAdapterStateMachine = new AdapterState(this, mLooper);
        mBinder = new AdapterServiceBinder(this);
        mUserManager = getNonNullSystemService(UserManager.class);
        mAppOps = getNonNullSystemService(AppOpsManager.class);
        mPowerManager = getNonNullSystemService(PowerManager.class);
        mBatteryStatsManager = getNonNullSystemService(BatteryStatsManager.class);
        mCompanionDeviceManager = getNonNullSystemService(CompanionDeviceManager.class);
        setAdapterService(this);
    }

    private void init() {
        Log.d(TAG, "init()");
        Config.init(this);
        initMetricsLogger();
        mDeviceConfigListener.start();

        if (!Flags.fastBindToApp()) {
            // Moved to OnCreate
            mUserManager = getNonNullSystemService(UserManager.class);
            mAppOps = getNonNullSystemService(AppOpsManager.class);
            mPowerManager = getNonNullSystemService(PowerManager.class);
            mBatteryStatsManager = getNonNullSystemService(BatteryStatsManager.class);
            mCompanionDeviceManager = getNonNullSystemService(CompanionDeviceManager.class);
            mRemoteDevices = new RemoteDevices(this, mLooper);
        }

        clearDiscoveringPackages();
        if (!Flags.fastBindToApp()) {
            mBinder = new AdapterServiceBinder(this);
        }
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!Flags.fastBindToApp()) {
            // Moved to OnCreate
            mAdapterProperties = new AdapterProperties(this);
            mAdapterStateMachine = new AdapterState(this, mLooper);
        }
        boolean isCommonCriteriaMode =
                getNonNullSystemService(DevicePolicyManager.class)
                        .isCommonCriteriaModeEnabled(null);
        mBluetoothKeystoreService =
                new BluetoothKeystoreService(
                        BluetoothKeystoreNativeInterface.getInstance(), isCommonCriteriaMode);
        mBluetoothKeystoreService.start();
        int configCompareResult = mBluetoothKeystoreService.getCompareResult();

        // Start tracking Binder latency for the bluetooth process.
        BluetoothFrameworkInitializer.initializeBinderCallsStats(getApplicationContext());

        // Android TV doesn't show consent dialogs for just works and encryption only le pairing
        boolean isAtvDevice =
                getApplicationContext()
                        .getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY);
        if (Flags.avoidStaticLoadingOfNative()) {
            if (Utils.isInstrumentationTestMode()) {
                Log.w(TAG, "This Bluetooth App is instrumented. ** Skip loading the native **");
            } else {
                Log.d(TAG, "Loading JNI Library");
                System.loadLibrary("bluetooth_jni");
            }
        }
        mNativeInterface.init(
                this,
                mAdapterProperties,
                mUserManager.isGuestUser(),
                isCommonCriteriaMode,
                configCompareResult,
                getInitFlags(),
                isAtvDevice,
                getApplicationInfo().dataDir);
        mNativeAvailable = true;
        // Load the name and address
        mNativeInterface.getAdapterProperty(AbstractionLayer.BT_PROPERTY_BDADDR);
        mNativeInterface.getAdapterProperty(AbstractionLayer.BT_PROPERTY_BDNAME);
        mNativeInterface.getAdapterProperty(AbstractionLayer.BT_PROPERTY_CLASS_OF_DEVICE);

        mBluetoothKeystoreService.initJni();

        mBluetoothQualityReportNativeInterface =
                requireNonNull(
                        BluetoothQualityReportNativeInterface.getInstance(),
                        "BluetoothQualityReportNativeInterface cannot be null when BQR starts");
        mBluetoothQualityReportNativeInterface.init();

        if (Flags.fastBindToApp()) {
            mSdpManager = new SdpManager(this, mLooper);
        } else {
            mSdpManager = new SdpManager(this);
        }

        mDatabaseManager.start(MetadataDatabase.createDatabase(this));

        boolean isAutomotiveDevice =
                getApplicationContext()
                        .getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);

        /*
         * Phone policy is specific to phone implementations and hence if a device wants to exclude
         * it out then it can be disabled by using the flag below. Phone policy is never used on
         * Android Automotive OS builds, in favor of a policy currently located in
         * CarBluetoothService.
         */
        if (!isAutomotiveDevice && getResources().getBoolean(R.bool.enable_phone_policy)) {
            Log.i(TAG, "Phone policy enabled");
            mPhonePolicy = new PhonePolicy(this, new ServiceFactory());
            mPhonePolicy.start();
        } else {
            Log.i(TAG, "Phone policy disabled");
        }

        if (Flags.audioRoutingCentralization()) {
            mActiveDeviceManager = new AudioRoutingManager(this, new ServiceFactory());
        } else {
            mActiveDeviceManager = new ActiveDeviceManager(this, new ServiceFactory());
        }
        mActiveDeviceManager.start();

        mSilenceDeviceManager.start();

        mBtCompanionManager = new CompanionManager(this, new ServiceFactory());

        mBluetoothSocketManagerBinder = new BluetoothSocketManagerBinder(this);

        if (!Flags.fastBindToApp()) {
            setAdapterService(this);
        }

        invalidateBluetoothCaches();

        // First call to getSharedPreferences will result in a file read into
        // memory cache. Call it here asynchronously to avoid potential ANR
        // in the future
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                getSharedPreferences(
                        PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE, Context.MODE_PRIVATE);
                getSharedPreferences(
                        MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE, Context.MODE_PRIVATE);
                getSharedPreferences(SIM_ACCESS_PERMISSION_PREFERENCE_FILE, Context.MODE_PRIVATE);
                return null;
            }
        }.execute();

        try {
            int systemUiUid =
                    getApplicationContext()
                            .createContextAsUser(UserHandle.SYSTEM, /* flags= */ 0)
                            .getPackageManager()
                            .getPackageUid(
                                    "com.android.systemui", PackageManager.MATCH_SYSTEM_ONLY);

            Utils.setSystemUiUid(systemUiUid);
        } catch (PackageManager.NameNotFoundException e) {
            // Some platforms, such as wearables do not have a system ui.
            Log.w(TAG, "Unable to resolve SystemUI's UID.", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind()");
        return mBinder;
    }

    @Override
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public boolean onUnbind(Intent intent) {
        if (Flags.explicitKillFromSystemServer()) {
            Log.d(TAG, "onUnbind()");
            return super.onUnbind(intent);
        }
        Log.d(TAG, "onUnbind() - calling cleanup");
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        if (Flags.explicitKillFromSystemServer()) {
            return;
        }
        if (!isMock()) {
            // TODO(b/27859763)
            Log.i(TAG, "Force exit to cleanup internal state in Bluetooth stack");
            System.exit(0);
        }
    }

    public ActiveDeviceManager getActiveDeviceManager() {
        return mActiveDeviceManager;
    }

    public SilenceDeviceManager getSilenceDeviceManager() {
        return mSilenceDeviceManager;
    }

    private boolean initMetricsLogger() {
        if (mMetricsLogger != null) {
            return false;
        }
        mMetricsLogger = MetricsLogger.getInstance();
        return mMetricsLogger.init(this);
    }

    private boolean closeMetricsLogger() {
        if (mMetricsLogger == null) {
            return false;
        }
        boolean result = mMetricsLogger.close();
        mMetricsLogger = null;
        return result;
    }

    /**
     * Log L2CAP CoC Server Connection Metrics
     *
     * @param port port of socket
     * @param isSecured if secured API is called
     * @param result transaction result of the connection
     * @param socketCreationLatencyMillis latency of the connection
     * @param timeoutMillis timeout set by the app
     */
    public void logL2capcocServerConnection(
            BluetoothDevice device,
            int port,
            boolean isSecured,
            int result,
            long socketCreationTimeMillis,
            long socketCreationLatencyMillis,
            long socketConnectionTimeMillis,
            long timeoutMillis,
            int appUid) {

        int metricId = 0;
        if (device != null) {
            metricId = getMetricId(device);
        }
        long currentTime = System.currentTimeMillis();
        long endToEndLatencyMillis = currentTime - socketCreationTimeMillis;
        long socketAcceptanceLatencyMillis = currentTime - socketConnectionTimeMillis;
        Log.i(
                TAG,
                "Statslog L2capcoc server connection."
                        + (" metricId " + metricId)
                        + (" port " + port)
                        + (" isSecured " + isSecured)
                        + (" result " + result)
                        + (" endToEndLatencyMillis " + endToEndLatencyMillis)
                        + (" socketCreationLatencyMillis " + socketCreationLatencyMillis)
                        + (" socketAcceptanceLatencyMillis " + socketAcceptanceLatencyMillis)
                        + (" timeout set by app " + timeoutMillis)
                        + (" appUid " + appUid));
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_L2CAP_COC_SERVER_CONNECTION,
                metricId,
                port,
                isSecured,
                result,
                endToEndLatencyMillis,
                timeoutMillis,
                appUid,
                socketCreationLatencyMillis,
                socketAcceptanceLatencyMillis);
    }

    public void setMetricsLogger(MetricsLogger metricsLogger) {
        mMetricsLogger = metricsLogger;
    }

    /**
     * Log L2CAP CoC Client Connection Metrics
     *
     * @param device Bluetooth device
     * @param port port of socket
     * @param isSecured if secured API is called
     * @param result transaction result of the connection
     * @param socketCreationLatencyNanos latency of the connection
     */
    public void logL2capcocClientConnection(
            BluetoothDevice device,
            int port,
            boolean isSecured,
            int result,
            long socketCreationTimeNanos,
            long socketCreationLatencyNanos,
            long socketConnectionTimeNanos,
            int appUid) {

        int metricId = getMetricId(device);
        long currentTime = System.nanoTime();
        long endToEndLatencyMillis = (currentTime - socketCreationTimeNanos) / 1000000;
        long socketCreationLatencyMillis = socketCreationLatencyNanos / 1000000;
        long socketConnectionLatencyMillis = (currentTime - socketConnectionTimeNanos) / 1000000;
        Log.i(
                TAG,
                "Statslog L2capcoc client connection."
                        + (" metricId " + metricId)
                        + (" port " + port)
                        + (" isSecured " + isSecured)
                        + (" result " + result)
                        + (" endToEndLatencyMillis " + endToEndLatencyMillis)
                        + (" socketCreationLatencyMillis " + socketCreationLatencyMillis)
                        + (" socketConnectionLatencyMillis " + socketConnectionLatencyMillis)
                        + (" appUid " + appUid));
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_L2CAP_COC_CLIENT_CONNECTION,
                metricId,
                port,
                isSecured,
                result,
                endToEndLatencyMillis,
                appUid,
                socketCreationLatencyMillis,
                socketConnectionLatencyMillis);
    }

    /**
     * Log RFCOMM Connection Metrics
     *
     * @param device Bluetooth device
     * @param isSecured if secured API is called
     * @param resultCode transaction result of the connection
     * @param isSerialPort true if service class UUID is 0x1101
     */
    public void logRfcommConnectionAttempt(
            BluetoothDevice device,
            boolean isSecured,
            int resultCode,
            long socketCreationTimeNanos,
            boolean isSerialPort,
            int appUid) {
        int metricId = getMetricId(device);
        long currentTime = System.nanoTime();
        long endToEndLatencyNanos = currentTime - socketCreationTimeNanos;
        byte[] remoteDeviceInfoBytes = MetricsLogger.getInstance().getRemoteDeviceInfoProto(device);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_RFCOMM_CONNECTION_ATTEMPTED,
                metricId,
                endToEndLatencyNanos,
                isSecured
                        ? BluetoothRfcommProtoEnums.SOCKET_SECURITY_SECURE
                        : BluetoothRfcommProtoEnums.SOCKET_SECURITY_INSECURE,
                resultCode,
                isSerialPort,
                appUid,
                remoteDeviceInfoBytes);
    }

    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.UPDATE_DEVICE_STATS,
            })
    void bringUpBle() {
        Log.d(TAG, "bleOnProcessStart()");

        if (getResources()
                .getBoolean(R.bool.config_bluetooth_reload_supported_profiles_when_enabled)) {
            Config.init(getApplicationContext());
        }

        // Reset |mRemoteDevices| whenever BLE is turned off then on
        // This is to replace the fact that |mRemoteDevices| was
        // reinitialized in previous code.
        //
        // TODO(apanicke): The reason is unclear but
        // I believe it is to clear the variable every time BLE was
        // turned off then on. The same effect can be achieved by
        // calling cleanup but this may not be necessary at all
        // We should figure out why this is needed later
        mRemoteDevices.reset();
        mAdapterProperties.init(mRemoteDevices);

        Log.d(TAG, "bleOnProcessStart() - Make Bond State Machine");
        mBondStateMachine = BondStateMachine.make(this, mAdapterProperties, mRemoteDevices);

        mNativeInterface.getCallbacks().init(mBondStateMachine, mRemoteDevices);

        mBatteryStatsManager.reportBleScanReset();
        BluetoothStatsLog.write_non_chained(
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED,
                -1,
                null,
                BluetoothStatsLog.BLE_SCAN_STATE_CHANGED__STATE__RESET,
                false,
                false,
                false);

        // TODO(b/228875190): GATT is assumed supported. As a result, we don't respect the
        // configuration sysprop. Configuring a device without GATT, although rare, will cause stack
        // start up errors yielding init loops.
        if (!GattService.isEnabled()) {
            Log.w(
                    TAG,
                    "GATT is configured off but the stack assumes it to be enabled. Start anyway.");
        }
        if (Flags.scanManagerRefactor()) {
            startScanController();
        } else {
            startGattProfileService();
        }
    }

    void bringDownBle() {
        if (Flags.scanManagerRefactor()) {
            stopScanController();
        } else {
            stopGattProfileService();
        }
    }

    void stateChangeCallback(int status) {
        if (status == AbstractionLayer.BT_STATE_OFF) {
            Log.d(TAG, "stateChangeCallback: disableNative() completed");
            mAdapterStateMachine.sendMessage(AdapterState.BLE_STOPPED);
        } else if (status == AbstractionLayer.BT_STATE_ON) {
            mAdapterStateMachine.sendMessage(AdapterState.BLE_STARTED);
        } else {
            Log.e(TAG, "Incorrect status " + status + " in stateChangeCallback");
        }
    }

    void startProfileServices() {
        Log.d(TAG, "startCoreServices()");
        int[] supportedProfileServices = Config.getSupportedProfiles();
        if (Flags.scanManagerRefactor()) {
            // Scanning is always supported, started separately, and is not a profile service.
            // This will check other profile services.
            if (supportedProfileServices.length == 0) {
                mAdapterProperties.onBluetoothReady();
                setScanMode(SCAN_MODE_CONNECTABLE, "startProfileServices");
                updateUuids();
                mAdapterStateMachine.sendMessage(AdapterState.BREDR_STARTED);
            } else {
                setAllProfileServiceStates(supportedProfileServices, BluetoothAdapter.STATE_ON);
            }
        } else {
            // TODO(b/228875190): GATT is assumed supported. If we support no other profiles then
            // just move on to BREDR_STARTED. Note that configuring GATT to NOT supported will cause
            // adapter initialization failures
            if (supportedProfileServices.length == 1
                    && supportedProfileServices[0] == BluetoothProfile.GATT) {
                mAdapterProperties.onBluetoothReady();
                setScanMode(SCAN_MODE_CONNECTABLE, "startProfileServices");
                updateUuids();
                mAdapterStateMachine.sendMessage(AdapterState.BREDR_STARTED);
            } else {
                setAllProfileServiceStates(supportedProfileServices, BluetoothAdapter.STATE_ON);
            }
        }
    }

    void stopProfileServices() {
        // Make sure to stop classic background tasks now
        mNativeInterface.cancelDiscovery();
        setScanMode(SCAN_MODE_NONE, "StopProfileServices");

        int[] supportedProfileServices = Config.getSupportedProfiles();
        if (Flags.scanManagerRefactor()) {
            // Scanning is always supported, started separately, and is not a profile service.
            // This will check other profile services.
            if (supportedProfileServices.length == 0) {
                mAdapterStateMachine.sendMessage(AdapterState.BREDR_STOPPED);
            } else {
                setAllProfileServiceStates(supportedProfileServices, BluetoothAdapter.STATE_OFF);
            }
        } else {
            // TODO(b/228875190): GATT is assumed supported. If we support no profiles then just
            // move on to BREDR_STOPPED
            if (supportedProfileServices.length == 1
                    && (mRunningProfiles.size() == 1
                            && GattService.class
                                    .getSimpleName()
                                    .equals(mRunningProfiles.get(0).getName()))) {
                Log.d(
                        TAG,
                        "stopProfileServices() - No profiles services to stop or already stopped.");
                mAdapterStateMachine.sendMessage(AdapterState.BREDR_STOPPED);
            } else {
                setAllProfileServiceStates(supportedProfileServices, BluetoothAdapter.STATE_OFF);
            }
        }
    }

    private void startGattProfileService() {
        mGattService = new GattService(this);

        mStartedProfiles.put(BluetoothProfile.GATT, mGattService);
        addProfile(mGattService);
        mGattService.start();
        mGattService.setAvailable(true);
        onProfileServiceStateChanged(mGattService, BluetoothAdapter.STATE_ON);
    }

    private void startScanController() {
        mScanController = new ScanController(this);
        mNativeInterface.enable();
    }

    private void stopGattProfileService() {
        setScanMode(SCAN_MODE_NONE, "stopGattProfileService");

        if (mRunningProfiles.size() == 0) {
            Log.d(TAG, "stopGattProfileService() - No profiles services to stop.");
            mAdapterStateMachine.sendMessage(AdapterState.BLE_STOPPED);
        }

        mStartedProfiles.remove(BluetoothProfile.GATT);
        if (mGattService != null) {
            mGattService.setAvailable(false);
            onProfileServiceStateChanged(mGattService, BluetoothAdapter.STATE_OFF);
            mGattService.stop();
            removeProfile(mGattService);
            mGattService.cleanup();
            mGattService.getBinder().cleanup();
            mGattService = null;
        }
    }

    private void stopScanController() {
        setScanMode(SCAN_MODE_NONE, "stopScanController");

        if (mScanController == null) {
            mAdapterStateMachine.sendMessage(AdapterState.BLE_STOPPED);
        } else {
            mScanController.stop();
            mScanController = null;
            mNativeInterface.disable();
        }
    }

    private void invalidateBluetoothGetStateCache() {
        BluetoothAdapter.invalidateBluetoothGetStateCache();
    }

    void updateLeAudioProfileServiceState() {
        Set<Integer> nonSupportedProfiles = new HashSet<>();

        if (!isLeConnectedIsochronousStreamCentralSupported()) {
            for (int profileId : Config.getLeAudioUnicastProfiles()) {
                nonSupportedProfiles.add(profileId);
            }
        }

        if (!isLeAudioBroadcastAssistantSupported()) {
            nonSupportedProfiles.add(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);
        }

        if (!isLeAudioBroadcastSourceSupported()) {
            Config.setProfileEnabled(BluetoothProfile.LE_AUDIO_BROADCAST, false);
        }

        // Disable the non-supported profiles service
        for (int profileId : nonSupportedProfiles) {
            Config.setProfileEnabled(profileId, false);
            if (mStartedProfiles.containsKey(profileId)) {
                setProfileServiceState(profileId, BluetoothAdapter.STATE_OFF);
            }
        }
    }

    void updateAdapterState(int prevState, int newState) {
        mAdapterProperties.setState(newState);
        invalidateBluetoothGetStateCache();

        // Only BluetoothManagerService should be registered
        int n = mRemoteCallbacks.beginBroadcast();
        Log.d(
                TAG,
                "updateAdapterState() - Broadcasting state "
                        + BluetoothAdapter.nameForState(newState)
                        + " to "
                        + n
                        + " receivers.");
        for (int i = 0; i < n; i++) {
            try {
                mRemoteCallbacks.getBroadcastItem(i).onBluetoothStateChange(prevState, newState);
            } catch (RemoteException e) {
                Log.d(TAG, "updateAdapterState() - Callback #" + i + " failed (" + e + ")");
            }
        }
        mRemoteCallbacks.finishBroadcast();

        for (Map.Entry<BluetoothStateCallback, Executor> e : mLocalCallbacks.entrySet()) {
            e.getValue().execute(() -> e.getKey().onBluetoothStateChange(prevState, newState));
        }

        // Turn the Adapter all the way off if we are disabling and the snoop log setting changed.
        if (newState == BluetoothAdapter.STATE_BLE_TURNING_ON) {
            sSnoopLogSettingAtEnable =
                    BluetoothProperties.snoop_log_mode()
                            .orElse(BluetoothProperties.snoop_log_mode_values.EMPTY);
            sDefaultSnoopLogSettingAtEnable =
                    Settings.Global.getString(
                            getContentResolver(), Settings.Global.BLUETOOTH_BTSNOOP_DEFAULT_MODE);

            sSnoopLogFilterHeadersSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_snoop_headers_enabled().orElse(false);
            sSnoopLogFilterProfileA2dpSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_profile_a2dp_enabled().orElse(false);
            sSnoopLogFilterProfileRfcommSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_profile_rfcomm_enabled().orElse(false);
            sSnoopLogFilterProfilePbapModeSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_profile_pbap()
                            .orElse(BluetoothProperties.snoop_log_filter_profile_pbap_values.EMPTY);
            sSnoopLogFilterProfileMapModeSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_profile_map()
                            .orElse(BluetoothProperties.snoop_log_filter_profile_map_values.EMPTY);

            BluetoothProperties.snoop_default_mode(
                    BluetoothProperties.snoop_default_mode_values.DISABLED);
            for (BluetoothProperties.snoop_default_mode_values value :
                    BluetoothProperties.snoop_default_mode_values.values()) {
                if (value.getPropValue().equals(sDefaultSnoopLogSettingAtEnable)) {
                    BluetoothProperties.snoop_default_mode(value);
                }
            }
        } else if (newState == BluetoothAdapter.STATE_BLE_ON
                && prevState != BluetoothAdapter.STATE_OFF) {
            var snoopLogSetting =
                    BluetoothProperties.snoop_log_mode()
                            .orElse(BluetoothProperties.snoop_log_mode_values.EMPTY);
            var snoopDefaultModeSetting =
                    Settings.Global.getString(
                            getContentResolver(), Settings.Global.BLUETOOTH_BTSNOOP_DEFAULT_MODE);

            var snoopLogFilterHeadersSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_snoop_headers_enabled().orElse(false);
            var snoopLogFilterProfileA2dpSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_profile_a2dp_enabled().orElse(false);
            var snoopLogFilterProfileRfcommSettingAtEnable =
                    BluetoothProperties.snoop_log_filter_profile_rfcomm_enabled().orElse(false);

            var snoopLogFilterProfilePbapModeSetting =
                    BluetoothProperties.snoop_log_filter_profile_pbap()
                            .orElse(BluetoothProperties.snoop_log_filter_profile_pbap_values.EMPTY);
            var snoopLogFilterProfileMapModeSetting =
                    BluetoothProperties.snoop_log_filter_profile_map()
                            .orElse(BluetoothProperties.snoop_log_filter_profile_map_values.EMPTY);

            if (!(sSnoopLogSettingAtEnable == snoopLogSetting)
                    || !(sDefaultSnoopLogSettingAtEnable == snoopDefaultModeSetting)
                    || !(sSnoopLogFilterHeadersSettingAtEnable
                            == snoopLogFilterHeadersSettingAtEnable)
                    || !(sSnoopLogFilterProfileA2dpSettingAtEnable
                            == snoopLogFilterProfileA2dpSettingAtEnable)
                    || !(sSnoopLogFilterProfileRfcommSettingAtEnable
                            == snoopLogFilterProfileRfcommSettingAtEnable)
                    || !(sSnoopLogFilterProfilePbapModeSettingAtEnable
                            == snoopLogFilterProfilePbapModeSetting)
                    || !(sSnoopLogFilterProfileMapModeSettingAtEnable
                            == snoopLogFilterProfileMapModeSetting)) {
                mAdapterStateMachine.sendMessage(AdapterState.BLE_TURN_OFF);
            }
        }
    }

    void linkQualityReportCallback(
            long timestamp,
            int reportId,
            int rssi,
            int snr,
            int retransmissionCount,
            int packetsNotReceiveCount,
            int negativeAcknowledgementCount) {
        BluetoothInCallService bluetoothInCallService = BluetoothInCallService.getInstance();

        if (reportId == BqrQualityReportId.QUALITY_REPORT_ID_SCO_VOICE_CHOPPY.getValue()) {
            if (bluetoothInCallService == null) {
                Log.w(
                        TAG,
                        "No BluetoothInCallService while trying to send BQR."
                                + (" timestamp: " + timestamp)
                                + (" reportId: " + reportId)
                                + (" rssi: " + rssi)
                                + (" snr: " + snr)
                                + (" retransmissionCount: " + retransmissionCount)
                                + (" packetsNotReceiveCount: " + packetsNotReceiveCount)
                                + (" negativeAcknowledgementCount: "
                                        + negativeAcknowledgementCount));
                return;
            }
            bluetoothInCallService.sendBluetoothCallQualityReport(
                    timestamp,
                    rssi,
                    snr,
                    retransmissionCount,
                    packetsNotReceiveCount,
                    negativeAcknowledgementCount);
        }
    }

    /**
     * Callback from Bluetooth Quality Report Native Interface to inform the listeners about
     * Bluetooth Quality.
     *
     * @param device is the BluetoothDevice which connection quality is being reported
     * @param bluetoothQualityReport a Parcel that contains information about Bluetooth Quality
     * @return whether the Bluetooth stack acknowledged the change successfully
     */
    public int bluetoothQualityReportReadyCallback(
            BluetoothDevice device, BluetoothQualityReport bluetoothQualityReport) {
        synchronized (mBluetoothQualityReportReadyCallbacks) {
            int n = mBluetoothQualityReportReadyCallbacks.beginBroadcast();
            Log.d(
                    TAG,
                    "bluetoothQualityReportReadyCallback() - "
                            + "Broadcasting Bluetooth Quality Report to "
                            + n
                            + " receivers.");
            for (int i = 0; i < n; i++) {
                try {
                    mBluetoothQualityReportReadyCallbacks
                            .getBroadcastItem(i)
                            .onBluetoothQualityReportReady(
                                    device, bluetoothQualityReport, BluetoothStatusCodes.SUCCESS);
                } catch (RemoteException e) {
                    Log.d(
                            TAG,
                            "bluetoothQualityReportReadyCallback() - Callback #"
                                    + i
                                    + " failed ("
                                    + e
                                    + ")");
                }
            }
            mBluetoothQualityReportReadyCallbacks.finishBroadcast();
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    void switchBufferSizeCallback(boolean isLowLatencyBufferSize) {
        List<BluetoothDevice> activeDevices = getActiveDevices(BluetoothProfile.A2DP);
        if (activeDevices.size() != 1) {
            Log.e(
                    TAG,
                    "Cannot switch buffer size. The number of A2DP active devices is "
                            + activeDevices.size());
            return;
        }

        // Send intent to fastpair
        Intent switchBufferSizeIntent = new Intent(BluetoothDevice.ACTION_SWITCH_BUFFER_SIZE);
        switchBufferSizeIntent.setClassName(
                getString(com.android.bluetooth.R.string.peripheral_link_package),
                getString(com.android.bluetooth.R.string.peripheral_link_package)
                        + getString(com.android.bluetooth.R.string.peripheral_link_service));
        switchBufferSizeIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, activeDevices.get(0));
        switchBufferSizeIntent.putExtra(
                BluetoothDevice.EXTRA_LOW_LATENCY_BUFFER_SIZE, isLowLatencyBufferSize);
        sendBroadcastMultiplePermissions(
                switchBufferSizeIntent,
                new String[] {
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_PRIVILEGED
                },
                null);
    }

    void switchCodecCallback(boolean isLowLatencyBufferSize) {
        List<BluetoothDevice> activeDevices = getActiveDevices(BluetoothProfile.A2DP);
        if (activeDevices.size() != 1) {
            Log.e(
                    TAG,
                    "Cannot switch buffer size. The number of A2DP active devices is "
                            + activeDevices.size());
            return;
        }
        mA2dpService.switchCodecByBufferSize(activeDevices.get(0), isLowLatencyBufferSize);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void cleanup() {
        Log.d(TAG, "cleanup()");
        if (mCleaningUp) {
            Log.e(TAG, "cleanup() - Service already starting to cleanup, ignoring request...");
            return;
        }

        closeMetricsLogger();

        clearAdapterService(this);

        mCleaningUp = true;
        invalidateBluetoothCaches();

        stopRfcommServerSockets();

        // This wake lock release may also be called concurrently by
        // {@link #releaseWakeLock(String lockName)}, so a synchronization is needed here.
        synchronized (this) {
            if (mWakeLock != null) {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
                mWakeLock = null;
            }
        }

        mDatabaseManager.cleanup();

        if (mAdapterStateMachine != null) {
            mAdapterStateMachine.doQuit();
        }

        if (mBondStateMachine != null) {
            mBondStateMachine.doQuit();
        }

        if (mRemoteDevices != null) {
            mRemoteDevices.reset();
        }

        if (mSdpManager != null) {
            mSdpManager.cleanup();
            mSdpManager = null;
        }

        if (mNativeAvailable) {
            Log.d(TAG, "cleanup() - Cleaning up adapter native");
            mNativeInterface.cleanup();
            mNativeAvailable = false;
        }

        if (mAdapterProperties != null) {
            mAdapterProperties.cleanup();
        }

        if (mNativeInterface.getCallbacks() != null) {
            mNativeInterface.getCallbacks().cleanup();
        }

        if (mBluetoothKeystoreService != null) {
            Log.d(TAG, "cleanup(): mBluetoothKeystoreService.cleanup()");
            mBluetoothKeystoreService.cleanup();
        }

        if (mPhonePolicy != null) {
            mPhonePolicy.cleanup();
        }

        mSilenceDeviceManager.cleanup();

        if (mActiveDeviceManager != null) {
            mActiveDeviceManager.cleanup();
        }

        if (mBluetoothSocketManagerBinder != null) {
            mBluetoothSocketManagerBinder.cleanUp();
            mBluetoothSocketManagerBinder = null;
        }

        if (!Flags.explicitKillFromSystemServer()) {
            // Bluetooth will be killed, no need to cleanup binder
            if (mBinder != null) {
                mBinder.cleanup();
                mBinder = null; // Do not remove. Otherwise Binder leak!
            }
        }

        mPreferredAudioProfilesCallbacks.kill();

        mBluetoothQualityReportReadyCallbacks.kill();

        mRemoteCallbacks.kill();
    }

    private void invalidateBluetoothCaches() {
        BluetoothAdapter.invalidateGetProfileConnectionStateCache();
        BluetoothAdapter.invalidateIsOffloadedFilteringSupportedCache();
        BluetoothDevice.invalidateBluetoothGetBondStateCache();
        BluetoothAdapter.invalidateBluetoothGetStateCache();
        BluetoothAdapter.invalidateGetAdapterConnectionStateCache();
        BluetoothMap.invalidateBluetoothGetConnectionStateCache();
        BluetoothSap.invalidateBluetoothGetConnectionStateCache();
    }

    private static final Map<Integer, Function<AdapterService, ProfileService>>
            PROFILE_CONSTRUCTORS =
                    Map.ofEntries(
                            Map.entry(BluetoothProfile.A2DP, A2dpService::new),
                            Map.entry(BluetoothProfile.A2DP_SINK, A2dpSinkService::new),
                            Map.entry(BluetoothProfile.AVRCP, AvrcpTargetService::new),
                            Map.entry(
                                    BluetoothProfile.AVRCP_CONTROLLER, AvrcpControllerService::new),
                            Map.entry(
                                    BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT,
                                    BassClientService::new),
                            Map.entry(BluetoothProfile.BATTERY, BatteryService::new),
                            Map.entry(
                                    BluetoothProfile.CSIP_SET_COORDINATOR,
                                    CsipSetCoordinatorService::new),
                            Map.entry(BluetoothProfile.HAP_CLIENT, HapClientService::new),
                            Map.entry(BluetoothProfile.HEADSET, HeadsetService::new),
                            Map.entry(BluetoothProfile.HEADSET_CLIENT, HeadsetClientService::new),
                            Map.entry(BluetoothProfile.HEARING_AID, HearingAidService::new),
                            Map.entry(BluetoothProfile.HID_DEVICE, HidDeviceService::new),
                            Map.entry(BluetoothProfile.HID_HOST, HidHostService::new),
                            Map.entry(BluetoothProfile.GATT, GattService::new),
                            Map.entry(BluetoothProfile.LE_AUDIO, LeAudioService::new),
                            Map.entry(BluetoothProfile.LE_CALL_CONTROL, TbsService::new),
                            Map.entry(BluetoothProfile.MAP, BluetoothMapService::new),
                            Map.entry(BluetoothProfile.MAP_CLIENT, MapClientService::new),
                            Map.entry(BluetoothProfile.MCP_SERVER, McpService::new),
                            Map.entry(BluetoothProfile.OPP, BluetoothOppService::new),
                            Map.entry(BluetoothProfile.PAN, PanService::new),
                            Map.entry(BluetoothProfile.PBAP, BluetoothPbapService::new),
                            Map.entry(BluetoothProfile.PBAP_CLIENT, PbapClientService::new),
                            Map.entry(BluetoothProfile.SAP, SapService::new),
                            Map.entry(BluetoothProfile.VOLUME_CONTROL, VolumeControlService::new));

    @VisibleForTesting
    void setProfileServiceState(int profileId, int state) {
        if (state == BluetoothAdapter.STATE_ON) {
            if (!mStartedProfiles.containsKey(profileId)) {
                ProfileService profileService = PROFILE_CONSTRUCTORS.get(profileId).apply(this);
                mStartedProfiles.put(profileId, profileService);
                addProfile(profileService);
                profileService.start();
                profileService.setAvailable(true);
                onProfileServiceStateChanged(profileService, BluetoothAdapter.STATE_ON);
            } else {
                Log.e(
                        TAG,
                        "setProfileServiceState("
                                + BluetoothProfile.getProfileName(profileId)
                                + ", STATE_ON): profile is already started");
            }
        } else if (state == BluetoothAdapter.STATE_OFF) {
            ProfileService profileService = mStartedProfiles.remove(profileId);
            if (profileService != null) {
                profileService.setAvailable(false);
                onProfileServiceStateChanged(profileService, BluetoothAdapter.STATE_OFF);
                profileService.stop();
                removeProfile(profileService);
                profileService.cleanup();
                profileService.getBinder().cleanup();
            } else {
                Log.e(
                        TAG,
                        "setProfileServiceState("
                                + BluetoothProfile.getProfileName(profileId)
                                + ", STATE_OFF): profile is already stopped");
            }
        }
    }

    private void setAllProfileServiceStates(int[] profileIds, int state) {
        for (int profileId : profileIds) {
            if (!Flags.scanManagerRefactor()) {
                // TODO(b/228875190): GATT is assumed supported and treated differently as part of
                //  the "BLE ON" state, despite GATT not being BLE specific.
                if (profileId == BluetoothProfile.GATT) {
                    continue;
                }
            }
            setProfileServiceState(profileId, state);
        }
    }

    /**
     * Checks whether the remote device is a dual mode audio sink device (supports both classic and
     * LE Audio sink roles.
     *
     * @param device the remote device
     * @return {@code true} if it's a dual mode audio device, {@code false} otherwise
     */
    public boolean isDualModeAudioSinkDevice(BluetoothDevice device) {
        if (mLeAudioService == null
                || mLeAudioService.getGroupId(device) == LE_AUDIO_GROUP_ID_INVALID) {
            return false;
        }

        // Check if any device in the CSIP group is a dual mode audio sink device
        for (BluetoothDevice groupDevice :
                mLeAudioService.getGroupDevices(mLeAudioService.getGroupId(device))) {
            if (isProfileSupported(groupDevice, BluetoothProfile.LE_AUDIO)
                    && (isProfileSupported(groupDevice, BluetoothProfile.HEADSET)
                            || isProfileSupported(groupDevice, BluetoothProfile.A2DP))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the local and remote device support a connection for duplex audio (input and
     * output) over HFP or LE Audio.
     *
     * @param groupDevices the devices in the CSIP group
     * @return {@code true} if duplex is supported on the remote device, {@code false} otherwise
     */
    private boolean isDuplexAudioSupported(List<BluetoothDevice> groupDevices) {
        for (BluetoothDevice device : groupDevices) {
            if (isProfileSupported(device, BluetoothProfile.HEADSET)
                    || (isProfileSupported(device, BluetoothProfile.LE_AUDIO)
                            && mLeAudioService != null
                            && mLeAudioService.isLeAudioDuplexSupported(device))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the local and remote device support a connection for output only audio over
     * A2DP or LE Audio.
     *
     * @param groupDevices the devices in the CSIP group
     * @return {@code true} if output only is supported, {@code false} otherwise
     */
    private boolean isOutputOnlyAudioSupported(List<BluetoothDevice> groupDevices) {
        for (BluetoothDevice device : groupDevices) {
            if (isProfileSupported(device, BluetoothProfile.A2DP)
                    || (isProfileSupported(device, BluetoothProfile.LE_AUDIO)
                            && mLeAudioService != null
                            && mLeAudioService.isLeAudioOutputSupported(device))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verifies whether the profile is supported by the local bluetooth adapter by checking a
     * bitmask of its supported profiles
     *
     * @param device is the remote device we wish to connect to
     * @param profile is the profile we are checking for support
     * @return true if the profile is supported by both the local and remote device, false otherwise
     */
    @VisibleForTesting
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    boolean isProfileSupported(BluetoothDevice device, int profile) {
        ParcelUuid[] remoteDeviceUuids = getRemoteUuids(device);
        ParcelUuid[] localDeviceUuids = mAdapterProperties.getUuids();
        if (remoteDeviceUuids == null || remoteDeviceUuids.length == 0) {
            Log.e(
                    TAG,
                    "isProfileSupported("
                            + ("device=" + device)
                            + (", profile=" + BluetoothProfile.getProfileName(profile) + "):")
                            + " remote device Uuids Empty");
        }

        Log.v(
                TAG,
                "isProfileSupported("
                        + ("device=" + device)
                        + (", profile=" + BluetoothProfile.getProfileName(profile) + "):")
                        + (" local_uuids=" + Arrays.toString(localDeviceUuids))
                        + (", remote_uuids=" + Arrays.toString(remoteDeviceUuids)));

        if (profile == BluetoothProfile.HEADSET) {
            return (Utils.arrayContains(localDeviceUuids, BluetoothUuid.HSP_AG)
                            && Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.HSP))
                    || (Utils.arrayContains(localDeviceUuids, BluetoothUuid.HFP_AG)
                            && Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.HFP));
        }
        if (profile == BluetoothProfile.HEADSET_CLIENT) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.HFP_AG)
                    && Utils.arrayContains(localDeviceUuids, BluetoothUuid.HFP);
        }
        if (profile == BluetoothProfile.A2DP) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.ADV_AUDIO_DIST)
                    || Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.A2DP_SINK);
        }
        if (profile == BluetoothProfile.A2DP_SINK) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.ADV_AUDIO_DIST)
                    || Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.A2DP_SOURCE);
        }
        if (profile == BluetoothProfile.OPP) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.OBEX_OBJECT_PUSH);
        }
        if (profile == BluetoothProfile.HID_HOST) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.HID)
                    || Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.HOGP)
                    || (Flags.androidHeadtrackerService()
                            && Utils.arrayContains(
                                    remoteDeviceUuids, HidHostService.ANDROID_HEADTRACKER_UUID));
        }
        if (profile == BluetoothProfile.HID_DEVICE) {
            return mHidDeviceService.getConnectionState(device)
                    == BluetoothProfile.STATE_DISCONNECTED;
        }
        if (profile == BluetoothProfile.PAN) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.NAP);
        }
        if (profile == BluetoothProfile.MAP) {
            return mMapService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
        }
        if (profile == BluetoothProfile.PBAP) {
            return mPbapService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
        }
        if (profile == BluetoothProfile.MAP_CLIENT) {
            return Utils.arrayContains(localDeviceUuids, BluetoothUuid.MNS)
                    && Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.MAS);
        }
        if (profile == BluetoothProfile.PBAP_CLIENT) {
            return Utils.arrayContains(localDeviceUuids, BluetoothUuid.PBAP_PCE)
                    && Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.PBAP_PSE);
        }
        if (profile == BluetoothProfile.HEARING_AID) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.HEARING_AID);
        }
        if (profile == BluetoothProfile.SAP) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.SAP);
        }
        if (profile == BluetoothProfile.VOLUME_CONTROL) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.VOLUME_CONTROL);
        }
        if (profile == BluetoothProfile.CSIP_SET_COORDINATOR) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.COORDINATED_SET);
        }
        if (profile == BluetoothProfile.LE_AUDIO) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.LE_AUDIO);
        }
        if (profile == BluetoothProfile.HAP_CLIENT) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.HAS);
        }
        if (profile == BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.BASS);
        }
        if (profile == BluetoothProfile.BATTERY) {
            return Utils.arrayContains(remoteDeviceUuids, BluetoothUuid.BATTERY);
        }

        Log.e(TAG, "isSupported: Unexpected profile passed in to function: " + profile);
        return false;
    }

    /**
     * Checks if the connection policy of all profiles are unknown for the given device
     *
     * @param device is the device for which we are checking if the connection policy of all
     *     profiles are unknown
     * @return false if one of profile is enabled or disabled, true otherwise
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    boolean isAllProfilesUnknown(BluetoothDevice device) {
        if (mA2dpService != null
                && mA2dpService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mA2dpSinkService != null
                && mA2dpSinkService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mHeadsetService != null
                && mHeadsetService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mHeadsetClientService != null
                && mHeadsetClientService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mMapClientService != null
                && mMapClientService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mHidHostService != null
                && mHidHostService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mPanService != null
                && mPanService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mPbapClientService != null
                && mPbapClientService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mHearingAidService != null
                && mHearingAidService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mHapClientService != null
                && mHapClientService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mVolumeControlService != null
                && mVolumeControlService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mCsipSetCoordinatorService != null
                && mCsipSetCoordinatorService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mLeAudioService != null
                && mLeAudioService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        if (mBassClientService != null
                && mBassClientService.getConnectionPolicy(device)
                        != BluetoothProfile.CONNECTION_POLICY_UNKNOWN) {
            return false;
        }
        return true;
    }

    /**
     * Connects only available profiles (those with {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED})
     *
     * @param device is the device with which we are connecting the profiles
     * @return {@link BluetoothStatusCodes#SUCCESS}
     */
    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                android.Manifest.permission.MODIFY_PHONE_STATE,
            })
    private int connectEnabledProfiles(BluetoothDevice device) {
        if (mCsipSetCoordinatorService != null
                && isProfileSupported(device, BluetoothProfile.CSIP_SET_COORDINATOR)
                && mCsipSetCoordinatorService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting Coordinated Set Profile");
            mCsipSetCoordinatorService.connect(device);
        }
        if (mA2dpService != null
                && isProfileSupported(device, BluetoothProfile.A2DP)
                && mA2dpService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting A2dp");
            mA2dpService.connect(device);
        }
        if (mA2dpSinkService != null
                && isProfileSupported(device, BluetoothProfile.A2DP_SINK)
                && mA2dpSinkService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting A2dp Sink");
            mA2dpSinkService.connect(device);
        }
        if (mHeadsetService != null
                && isProfileSupported(device, BluetoothProfile.HEADSET)
                && mHeadsetService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting Headset Profile");
            mHeadsetService.connect(device);
        }
        if (mHeadsetClientService != null
                && isProfileSupported(device, BluetoothProfile.HEADSET_CLIENT)
                && mHeadsetClientService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting HFP");
            mHeadsetClientService.connect(device);
        }
        if (mMapClientService != null
                && isProfileSupported(device, BluetoothProfile.MAP_CLIENT)
                && mMapClientService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting MAP");
            mMapClientService.connect(device);
        }
        if (mHidHostService != null
                && isProfileSupported(device, BluetoothProfile.HID_HOST)
                && mHidHostService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting Hid Host Profile");
            mHidHostService.connect(device);
        }
        if (mPanService != null
                && isProfileSupported(device, BluetoothProfile.PAN)
                && mPanService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting Pan Profile");
            mPanService.connect(device);
        }
        if (mPbapClientService != null
                && isProfileSupported(device, BluetoothProfile.PBAP_CLIENT)
                && mPbapClientService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting Pbap");
            mPbapClientService.connect(device);
        }
        if (mHearingAidService != null
                && isProfileSupported(device, BluetoothProfile.HEARING_AID)
                && mHearingAidService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting Hearing Aid Profile");
            mHearingAidService.connect(device);
        }
        if (mHapClientService != null
                && isProfileSupported(device, BluetoothProfile.HAP_CLIENT)
                && mHapClientService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting HAS Profile");
            mHapClientService.connect(device);
        }
        if (mVolumeControlService != null
                && isProfileSupported(device, BluetoothProfile.VOLUME_CONTROL)
                && mVolumeControlService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting Volume Control Profile");
            mVolumeControlService.connect(device);
        }
        if (mLeAudioService != null
                && isProfileSupported(device, BluetoothProfile.LE_AUDIO)
                && mLeAudioService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting LeAudio profile (BAP)");
            mLeAudioService.connect(device);
        }
        if (mBassClientService != null
                && isProfileSupported(device, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)
                && mBassClientService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting LE Broadcast Assistant Profile");
            mBassClientService.connect(device);
        }
        if (mBatteryService != null
                && isProfileSupported(device, BluetoothProfile.BATTERY)
                && mBatteryService.getConnectionPolicy(device)
                        > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.i(TAG, "connectEnabledProfiles: Connecting Battery Service");
            mBatteryService.connect(device);
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    /**
     * Verifies that all bluetooth profile services are running
     *
     * @return true if all bluetooth profile services running, false otherwise
     */
    private boolean profileServicesRunning() {
        if (mRegisteredProfiles.size() == Config.getSupportedProfiles().length
                && mRegisteredProfiles.size() == mRunningProfiles.size()) {
            return true;
        }

        Log.e(TAG, "profileServicesRunning: One or more supported services not running");
        return false;
    }

    /** Initializes all the profile services fields */
    private void initProfileServices() {
        Log.i(TAG, "initProfileServices: Initializing all bluetooth profile services");
        mA2dpService = A2dpService.getA2dpService();
        mA2dpSinkService = A2dpSinkService.getA2dpSinkService();
        mHeadsetService = HeadsetService.getHeadsetService();
        mHeadsetClientService = HeadsetClientService.getHeadsetClientService();
        mMapService = BluetoothMapService.getBluetoothMapService();
        mMapClientService = MapClientService.getMapClientService();
        mHidDeviceService = HidDeviceService.getHidDeviceService();
        mHidHostService = HidHostService.getHidHostService();
        mPanService = PanService.getPanService();
        mPbapService = BluetoothPbapService.getBluetoothPbapService();
        mPbapClientService = PbapClientService.getPbapClientService();
        mHearingAidService = HearingAidService.getHearingAidService();
        mHapClientService = HapClientService.getHapClientService();
        mSapService = SapService.getSapService();
        mVolumeControlService = VolumeControlService.getVolumeControlService();
        mCsipSetCoordinatorService = CsipSetCoordinatorService.getCsipSetCoordinatorService();
        mLeAudioService = LeAudioService.getLeAudioService();
        mBassClientService = BassClientService.getBassClientService();
        mBatteryService = BatteryService.getBatteryService();
        if (Flags.scanManagerRefactor()) {
            mGattService = GattService.getGattService();
        }
    }

    @BluetoothAdapter.RfcommListenerResult
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private int startRfcommListener(
            String name,
            ParcelUuid uuid,
            PendingIntent pendingIntent,
            AttributionSource attributionSource) {
        if (mBluetoothServerSockets.containsKey(uuid.getUuid())) {
            Log.d(
                    TAG,
                    String.format(
                            "Cannot start RFCOMM listener: UUID %s already in use.",
                            uuid.getUuid()));
            return BluetoothStatusCodes.RFCOMM_LISTENER_START_FAILED_UUID_IN_USE;
        }

        try {
            startRfcommListenerInternal(name, uuid.getUuid(), pendingIntent, attributionSource);
        } catch (IOException e) {
            return BluetoothStatusCodes.RFCOMM_LISTENER_FAILED_TO_CREATE_SERVER_SOCKET;
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    @BluetoothAdapter.RfcommListenerResult
    @VisibleForTesting
    int stopRfcommListener(ParcelUuid uuid, AttributionSource attributionSource) {
        RfcommListenerData listenerData = mBluetoothServerSockets.get(uuid.getUuid());

        if (listenerData == null) {
            Log.d(
                    TAG,
                    String.format(
                            "Cannot stop RFCOMM listener: UUID %s is not registered.",
                            uuid.getUuid()));
            return BluetoothStatusCodes.RFCOMM_LISTENER_OPERATION_FAILED_NO_MATCHING_SERVICE_RECORD;
        }

        if (attributionSource.getUid() != listenerData.mAttributionSource.getUid()) {
            return BluetoothStatusCodes.RFCOMM_LISTENER_OPERATION_FAILED_DIFFERENT_APP;
        }

        // Remove the entry so that it does not try and restart the server socket.
        mBluetoothServerSockets.remove(uuid.getUuid());

        return listenerData.closeServerAndPendingSockets(mHandler);
    }

    @VisibleForTesting
    IncomingRfcommSocketInfo retrievePendingSocketForServiceRecord(
            ParcelUuid uuid, AttributionSource attributionSource) {
        IncomingRfcommSocketInfo socketInfo = new IncomingRfcommSocketInfo();

        RfcommListenerData listenerData = mBluetoothServerSockets.get(uuid.getUuid());

        if (listenerData == null) {
            socketInfo.status =
                    BluetoothStatusCodes
                            .RFCOMM_LISTENER_OPERATION_FAILED_NO_MATCHING_SERVICE_RECORD;
            return socketInfo;
        }

        if (attributionSource.getUid() != listenerData.mAttributionSource.getUid()) {
            socketInfo.status = BluetoothStatusCodes.RFCOMM_LISTENER_OPERATION_FAILED_DIFFERENT_APP;
            return socketInfo;
        }

        BluetoothSocket socket = listenerData.mPendingSockets.poll();

        if (socket == null) {
            socketInfo.status = BluetoothStatusCodes.RFCOMM_LISTENER_NO_SOCKET_AVAILABLE;
            return socketInfo;
        }

        mHandler.removeCallbacksAndMessages(socket);

        socketInfo.bluetoothDevice = socket.getRemoteDevice();
        socketInfo.pfd = socket.getParcelFileDescriptor();
        socketInfo.status = BluetoothStatusCodes.SUCCESS;

        return socketInfo;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void handleIncomingRfcommConnections(UUID uuid) {
        RfcommListenerData listenerData = mBluetoothServerSockets.get(uuid);
        while (true) {
            BluetoothSocket socket;
            try {
                socket = listenerData.mServerSocket.accept();
            } catch (IOException e) {
                if (mBluetoothServerSockets.containsKey(uuid)) {
                    // The uuid still being in the map indicates that the accept failure is
                    // unexpected. Try and restart the listener.
                    Log.e(TAG, "Failed to accept socket on " + listenerData.mServerSocket, e);
                    restartRfcommListener(listenerData, uuid);
                }
                return;
            }

            listenerData.mPendingSockets.add(socket);
            try {
                listenerData.mPendingIntent.send();
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "PendingIntent for RFCOMM socket notifications cancelled.", e);
                // The pending intent was cancelled, close the server as there is no longer any way
                // to notify the app that registered the listener.
                listenerData.closeServerAndPendingSockets(mHandler);
                mBluetoothServerSockets.remove(uuid);
                return;
            }
            mHandler.postDelayed(
                    () -> pendingSocketTimeoutRunnable(listenerData, socket),
                    socket,
                    PENDING_SOCKET_HANDOFF_TIMEOUT.toMillis());
        }
    }

    // Tries to restart the rfcomm listener for the given UUID
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void restartRfcommListener(RfcommListenerData listenerData, UUID uuid) {
        listenerData.closeServerAndPendingSockets(mHandler);
        try {
            startRfcommListenerInternal(
                    listenerData.mName,
                    uuid,
                    listenerData.mPendingIntent,
                    listenerData.mAttributionSource);
        } catch (IOException e) {
            Log.e(TAG, "Failed to recreate rfcomm server socket", e);

            mBluetoothServerSockets.remove(uuid);
        }
    }

    private void pendingSocketTimeoutRunnable(
            RfcommListenerData listenerData, BluetoothSocket socket) {
        boolean socketFound = listenerData.mPendingSockets.remove(socket);
        if (socketFound) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close bt socket", e);
                // We don't care if closing the socket failed, just continue on.
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void startRfcommListenerInternal(
            String name, UUID uuid, PendingIntent intent, AttributionSource attributionSource)
            throws IOException {
        BluetoothServerSocket bluetoothServerSocket =
                mAdapter.listenUsingRfcommWithServiceRecord(name, uuid);

        RfcommListenerData listenerData =
                new RfcommListenerData(bluetoothServerSocket, name, intent, attributionSource);

        mBluetoothServerSockets.put(uuid, listenerData);

        new Thread(() -> handleIncomingRfcommConnections(uuid)).start();
    }

    private void stopRfcommServerSockets() {
        Iterator<Map.Entry<UUID, RfcommListenerData>> socketsIterator =
                mBluetoothServerSockets.entrySet().iterator();
        while (socketsIterator.hasNext()) {
            socketsIterator.next().getValue().closeServerAndPendingSockets(mHandler);
            socketsIterator.remove();
        }
    }

    private static class RfcommListenerData {
        final BluetoothServerSocket mServerSocket;
        // Service record name
        final String mName;
        // The Intent which contains the Service info to which the incoming socket connections are
        // handed off to.
        final PendingIntent mPendingIntent;
        // AttributionSource for the requester of the RFCOMM listener
        final AttributionSource mAttributionSource;
        // Contains the connected sockets which are pending transfer to the app which requested the
        // listener.
        final ConcurrentLinkedQueue<BluetoothSocket> mPendingSockets =
                new ConcurrentLinkedQueue<>();

        RfcommListenerData(
                BluetoothServerSocket serverSocket,
                String name,
                PendingIntent pendingIntent,
                AttributionSource attributionSource) {
            mServerSocket = serverSocket;
            mName = name;
            mPendingIntent = pendingIntent;
            mAttributionSource = attributionSource;
        }

        int closeServerAndPendingSockets(Handler handler) {
            int result = BluetoothStatusCodes.SUCCESS;
            try {
                mServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to call close on rfcomm server socket", e);
                result = BluetoothStatusCodes.RFCOMM_LISTENER_FAILED_TO_CLOSE_SERVER_SOCKET;
            }
            mPendingSockets.forEach(
                    pendingSocket -> {
                        handler.removeCallbacksAndMessages(pendingSocket);
                        try {
                            pendingSocket.close();
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to close socket", e);
                        }
                    });
            mPendingSockets.clear();

            return result;
        }
    }

    @VisibleForTesting
    boolean isAvailable() {
        return !mCleaningUp;
    }

    /**
     * Get an metadata of given device and key
     *
     * @param device Bluetooth device
     * @param key Metadata key
     * @param value Metadata value
     * @return if metadata is set successfully
     */
    public boolean setMetadata(BluetoothDevice device, int key, byte[] value) {
        if (value == null || value.length > BluetoothDevice.METADATA_MAX_LENGTH) {
            return false;
        }
        return mDatabaseManager.setCustomMeta(device, key, value);
    }

    /**
     * Get an metadata of given device and key
     *
     * @param device Bluetooth device
     * @param key Metadata key
     * @return value of given device and key combination
     */
    public byte[] getMetadata(BluetoothDevice device, int key) {
        return mDatabaseManager.getCustomMeta(device, key);
    }

    /** Update Adapter Properties when BT profiles connection state changes. */
    public void updateProfileConnectionAdapterProperties(
            BluetoothDevice device, int profile, int state, int prevState) {
        mHandler.post(
                () ->
                        mAdapterProperties.updateOnProfileConnectionChanged(
                                device, profile, state, prevState));
    }

    /**
     * The Binder implementation must be declared to be a static class, with the AdapterService
     * instance passed in the constructor. Furthermore, when the AdapterService shuts down, the
     * reference to the AdapterService must be explicitly removed.
     *
     * <p>Otherwise, a memory leak can occur from repeated starting/stopping the service...Please
     * refer to android.os.Binder for further details on why an inner instance class should be
     * avoided.
     *
     * <p>TODO: b/339548431 -- Delete this comment as it does not apply when we get killed
     */
    @VisibleForTesting
    public static class AdapterServiceBinder extends IBluetooth.Stub {
        // TODO: b/339548431 move variable to final
        private AdapterService mService;

        AdapterServiceBinder(AdapterService svc) {
            mService = svc;
            mService.invalidateBluetoothGetStateCache();
            BluetoothAdapter.getDefaultAdapter().disableBluetoothGetStateCache();
        }

        public void cleanup() {
            mService = null;
        }

        public AdapterService getService() {
            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        @Override
        public int getState() {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothAdapter.STATE_OFF;
            }

            return service.getState();
        }

        @Override
        public void killBluetoothProcess() {
            mService.enforceCallingPermission(
                    android.Manifest.permission.BLUETOOTH_PRIVILEGED, null);

            Runnable killAction =
                    () -> {
                        if (Flags.killInsteadOfExit()) {
                            Log.i(TAG, "killBluetoothProcess: Calling killProcess(myPid())");
                            Process.killProcess(Process.myPid());
                        } else {
                            Log.i(TAG, "killBluetoothProcess: Calling System.exit");
                            System.exit(0);
                        }
                    };

            // Post on the main handler to let the cleanup complete before calling exit
            mService.mHandler.post(killAction);

            try {
                // Wait for Bluetooth to be killed from its main thread
                Thread.sleep(950); // SystemServer is waiting 1000 ms, we need to wait less here
            } catch (InterruptedException e) {
                Log.e(TAG, "killBluetoothProcess: Interrupted while waiting for kill");
            }

            // Bluetooth cannot be killed on the main thread; it is in a deadLock.
            // Trying to recover by killing the Bluetooth from the binder thread.
            // This is bad :(
            Counter.logIncrement("bluetooth.value_kill_from_binder_thread");
            Log.wtf(TAG, "Failed to kill Bluetooth using its main thread. Trying from binder");
            killAction.run();
        }

        @Override
        public void enable(boolean quietMode, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "enable")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService enable")) {
                return;
            }

            service.enable(quietMode);
        }

        @Override
        public void disable(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "disable")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService disable")) {
                return;
            }

            service.disable();
        }

        @Override
        public String getAddress(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getAddress")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getAddress")) {
                return null;
            }

            enforceLocalMacAddressPermission(service);

            return Utils.getAddressStringFromByte(service.mAdapterProperties.getAddress());
        }

        @Override
        public boolean isLogRedactionEnabled() {
            AdapterService service = getService();
            if (service == null) {
                return true;
            }
            return service.mNativeInterface.isLogRedactionEnabled();
        }

        @Override
        public List<ParcelUuid> getUuids(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getUuids")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getUuids")) {
                return Collections.emptyList();
            }

            ParcelUuid[] parcels = service.mAdapterProperties.getUuids();
            if (parcels == null) {
                parcels = new ParcelUuid[0];
            }
            return Arrays.asList(parcels);
        }

        @Override
        public String getIdentityAddress(String address) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getIdentityAddress")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            Utils.getCallingAttributionSource(mService),
                            "AdapterService getIdentityAddress")) {
                return null;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.getIdentityAddress(address);
        }

        @Override
        public String getName(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getName")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getName")) {
                return null;
            }

            return service.getName();
        }

        @Override
        public int getNameLengthForAdvertise(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "getNameLengthForAdvertise")
                    || !Utils.checkAdvertisePermissionForDataDelivery(
                            service, attributionSource, TAG)) {
                return -1;
            }

            return service.getNameLengthForAdvertise();
        }

        @Override
        public boolean setName(String name, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setName")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService setName")) {
                return false;
            }

            Log.d(TAG, "AdapterServiceBinder.setName(" + name + ")");
            return service.mAdapterProperties.setName(name);
        }

        @Override
        public int getScanMode(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getScanMode")
                    || !Utils.checkScanPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getScanMode")) {
                return SCAN_MODE_NONE;
            }

            return service.getScanMode();
        }

        @Override
        public int setScanMode(int mode, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setScanMode")
                    || !Utils.checkScanPermissionForDataDelivery(
                            service, attributionSource, "AdapterService setScanMode")) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            String logCaller =
                    Utils.getUidPidString() + " packageName=" + attributionSource.getPackageName();
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            mService.mHandler.post(
                    () ->
                            future.complete(
                                    service.getState() == BluetoothAdapter.STATE_ON
                                            && service.setScanMode(mode, logCaller)));
            return future.join()
                    ? BluetoothStatusCodes.SUCCESS
                    : BluetoothStatusCodes.ERROR_UNKNOWN;
        }

        @Override
        public long getDiscoverableTimeout(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getDiscoverableTimeout")
                    || !Utils.checkScanPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getDiscoverableTimeout")) {
                return -1;
            }

            return service.mAdapterProperties.getDiscoverableTimeout();
        }

        @Override
        public int setDiscoverableTimeout(long timeout, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setDiscoverableTimeout")
                    || !Utils.checkScanPermissionForDataDelivery(
                            service, attributionSource, "AdapterService setDiscoverableTimeout")) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            return service.mAdapterProperties.setDiscoverableTimeout((int) timeout)
                    ? BluetoothStatusCodes.SUCCESS
                    : BluetoothStatusCodes.ERROR_UNKNOWN;
        }

        @Override
        public boolean startDiscovery(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "startDiscovery")) {
                return false;
            }

            if (!Utils.checkScanPermissionForDataDelivery(
                    service, attributionSource, "Starting discovery.")) {
                return false;
            }

            Log.i(TAG, "startDiscovery: from " + Utils.getUidPidString());
            return service.startDiscovery(attributionSource);
        }

        @Override
        public boolean cancelDiscovery(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "cancelDiscovery")
                    || !Utils.checkScanPermissionForDataDelivery(
                            service, attributionSource, "AdapterService cancelDiscovery")) {
                return false;
            }

            Log.i(TAG, "cancelDiscovery: from " + Utils.getUidPidString());
            return service.mNativeInterface.cancelDiscovery();
        }

        @Override
        public boolean isDiscovering(AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "isDiscovering")
                    || !Utils.checkScanPermissionForDataDelivery(
                            service, attributionSource, "AdapterService isDiscovering")) {
                return false;
            }

            return service.mAdapterProperties.isDiscovering();
        }

        @Override
        public long getDiscoveryEndMillis(AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getDiscoveryEndMillis")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return -1;
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.mAdapterProperties.discoveryEndMillis();
        }

        @Override
        public List<BluetoothDevice> getMostRecentlyConnectedDevices(
                AttributionSource attributionSource) {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "AdapterService getMostRecentlyConnectedDevices")) {
                return Collections.emptyList();
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.mDatabaseManager.getMostRecentlyConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getBondedDevices(AttributionSource attributionSource) {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getBondedDevices")) {
                return Collections.emptyList();
            }

            return Arrays.asList(service.getBondedDevices());
        }

        @Override
        public int getAdapterConnectionState() {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null) {
                return BluetoothAdapter.STATE_DISCONNECTED;
            }

            return service.mAdapterProperties.getConnectionState();
        }

        /**
         * This method has an associated binder cache. The invalidation methods must be changed if
         * the logic behind this method changes.
         */
        @Override
        public int getProfileConnectionState(int profile, AttributionSource source) {
            AdapterService service = getService();
            boolean checkConnect = false;
            final int callingUid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                checkConnect = CompatChanges.isChangeEnabled(ENFORCE_CONNECT, callingUid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "getProfileConnectionState")
                    || (checkConnect
                            && !Utils.checkConnectPermissionForDataDelivery(
                                    service, source, "AdapterService getProfileConnectionState"))) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            return service.mAdapterProperties.getProfileConnectionState(profile);
        }

        @Override
        public boolean createBond(
                BluetoothDevice device,
                int transport,
                OobData remoteP192Data,
                OobData remoteP256Data,
                AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "createBond")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService createBond")) {
                return false;
            }

            // This conditional is required to satisfy permission dependencies
            // since createBond calls createBondOutOfBand with null value passed as data.
            // BluetoothDevice#createBond requires BLUETOOTH_ADMIN only.
            service.enforceBluetoothPrivilegedPermissionIfNeeded(remoteP192Data, remoteP256Data);

            Log.i(
                    TAG,
                    "createBond: device="
                            + device
                            + ", transport="
                            + transport
                            + ", from "
                            + Utils.getUidPidString());
            return service.createBond(
                    device,
                    transport,
                    remoteP192Data,
                    remoteP256Data,
                    attributionSource.getPackageName());
        }

        @Override
        public boolean cancelBondProcess(
                BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "cancelBondProcess")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService cancelBondProcess")) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            Log.i(TAG, "cancelBondProcess: device=" + device + ", from " + Utils.getUidPidString());

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            if (deviceProp != null) {
                deviceProp.setBondingInitiatedLocally(false);
            }

            return service.mNativeInterface.cancelBond(getBytesFromAddress(device.getAddress()));
        }

        @Override
        public boolean removeBond(BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "removeBond")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService removeBond")) {
                return false;
            }

            Log.i(TAG, "removeBond: device=" + device + ", from " + Utils.getUidPidString());

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDED) {
                Log.w(
                        TAG,
                        device.getAddressForLogging()
                                + " cannot be removed since "
                                + ((deviceProp == null)
                                        ? "properties are empty"
                                        : "bond state is " + deviceProp.getBondState()));
                return false;
            }
            service.mBondAttemptCallerInfo.remove(device.getAddress());
            deviceProp.setBondingInitiatedLocally(false);

            Message msg = service.mBondStateMachine.obtainMessage(BondStateMachine.REMOVE_BOND);
            msg.obj = device;
            service.mBondStateMachine.sendMessage(msg);
            return true;
        }

        @Override
        public int getBondState(BluetoothDevice device, AttributionSource attributionSource) {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getBondState")) {
                return BluetoothDevice.BOND_NONE;
            }

            return service.getBondState(device);
        }

        @Override
        public boolean isBondingInitiatedLocally(
                BluetoothDevice device, AttributionSource attributionSource) {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "AdapterService isBondingInitiatedLocally")) {
                return false;
            }

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            return deviceProp != null && deviceProp.isBondingInitiatedLocally();
        }

        @Override
        public void generateLocalOobData(
                int transport, IBluetoothOobDataCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "generateLocalOobData")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return;
            }
            enforceBluetoothPrivilegedPermission(service);
            service.generateLocalOobData(transport, callback);
        }

        @Override
        public long getSupportedProfiles(AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return 0;
            }
            enforceBluetoothPrivilegedPermission(service);

            return Config.getSupportedProfilesBitMask();
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getConnectionState")) {
                return BluetoothDevice.CONNECTION_STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public int getConnectionHandle(
                BluetoothDevice device, int transport, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getConnectionHandle")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, TAG)) {
                return BluetoothDevice.ERROR;
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.getConnectionHandle(device, transport);
        }

        @Override
        public boolean canBondWithoutDialog(BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.canBondWithoutDialog(device);
        }

        @Override
        public String getPackageNameOfBondingApplication(BluetoothDevice device) {
            AdapterService service = getService();

            if (service == null) {
                return null;
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.getPackageNameOfBondingApplication(device);
        }

        @Override
        public boolean removeActiveDevice(@ActiveDeviceUse int profiles, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "removeActiveDevice")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }
            Log.i(
                    TAG,
                    "removeActiveDevice: profiles="
                            + profiles
                            + ", from "
                            + Utils.getUidPidString());
            return service.setActiveDevice(null, profiles);
        }

        @Override
        public boolean setActiveDevice(
                BluetoothDevice device, @ActiveDeviceUse int profiles, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setActiveDevice")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            Log.i(
                    TAG,
                    "setActiveDevice: device="
                            + device
                            + ", profiles="
                            + profiles
                            + ", from "
                            + Utils.getUidPidString());

            return service.setActiveDevice(device, profiles);
        }

        @Override
        public List<BluetoothDevice> getActiveDevices(
                @ActiveDeviceProfile int profile, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getActiveDevices")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return Collections.emptyList();
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.getActiveDevices(profile);
        }

        @Override
        public int connectAllEnabledProfiles(BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "connectAllEnabledProfiles")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            if (device == null) {
                throw new IllegalArgumentException("device cannot be null");
            }
            if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
                throw new IllegalArgumentException("device cannot have an invalid address");
            }
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }

            enforceBluetoothPrivilegedPermission(service);

            Log.i(
                    TAG,
                    "connectAllEnabledProfiles: device="
                            + device
                            + ", from "
                            + Utils.getUidPidString());

            try {
                return service.connectAllEnabledProfiles(device);
            } catch (Exception e) {
                Log.v(TAG, "connectAllEnabledProfiles() failed", e);
                SneakyThrow.sneakyThrow(e);
                throw new RuntimeException(e);
            }
        }

        @Override
        public int disconnectAllEnabledProfiles(BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(
                    service, TAG, "disconnectAllEnabledProfiles")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            if (device == null) {
                throw new IllegalArgumentException("device cannot be null");
            }
            if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
                throw new IllegalArgumentException("device cannot have an invalid address");
            }
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }

            enforceBluetoothPrivilegedPermission(service);

            Log.i(
                    TAG,
                    "disconnectAllEnabledProfiles: device="
                            + device
                            + ", from "
                            + Utils.getUidPidString());

            try {
                return service.disconnectAllEnabledProfiles(device);
            } catch (Exception e) {
                Log.v(TAG, "disconnectAllEnabledProfiles() failed", e);
                SneakyThrow.sneakyThrow(e);
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getRemoteName(BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteName")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getRemoteName")) {
                return null;
            }

            return service.getRemoteName(device);
        }

        @Override
        public int getRemoteType(BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteType")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getRemoteType")) {
                return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
            }

            return service.getRemoteType(device);
        }

        @Override
        public String getRemoteAlias(BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteAlias")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getRemoteAlias")) {
                return null;
            }

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            return deviceProp != null ? deviceProp.getAlias() : null;
        }

        @Override
        public int setRemoteAlias(
                BluetoothDevice device, String name, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "setRemoteAlias")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            if (name != null && name.isEmpty()) {
                throw new IllegalArgumentException("alias cannot be the empty string");
            }

            if (!hasBluetoothPrivilegedPermission(service)) {
                if (!Utils.checkConnectPermissionForDataDelivery(
                        service, attributionSource, "AdapterService setRemoteAlias")) {
                    return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
                }
                enforceCdmAssociation(
                        service.mCompanionDeviceManager,
                        service,
                        attributionSource.getPackageName(),
                        Binder.getCallingUid(),
                        device);
            }

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null) {
                return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
            }
            deviceProp.setAlias(device, name);
            return BluetoothStatusCodes.SUCCESS;
        }

        @Override
        public int getRemoteClass(BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteClass")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getRemoteClass")) {
                return 0;
            }

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            return deviceProp != null ? deviceProp.getBluetoothClass() : 0;
        }

        @Override
        public List<ParcelUuid> getRemoteUuids(
                BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getRemoteUuids")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getRemoteUuids")) {
                return Collections.emptyList();
            }

            ParcelUuid[] parcels = service.getRemoteUuids(device);
            if (parcels == null) {
                return null;
            }
            return Arrays.asList(parcels);
        }

        @Override
        public boolean fetchRemoteUuids(
                BluetoothDevice device, int transport, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "fetchRemoteUuids")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService fetchRemoteUuids")) {
                return false;
            }
            if (transport != TRANSPORT_AUTO) {
                enforceBluetoothPrivilegedPermission(service);
            }

            Log.i(
                    TAG,
                    "fetchRemoteUuids: device="
                            + device
                            + ", transport="
                            + transport
                            + ", from "
                            + Utils.getUidPidString());

            service.mRemoteDevices.fetchUuids(device, transport);
            MetricsLogger.getInstance().cacheCount(BluetoothProtoEnums.SDP_FETCH_UUID_REQUEST, 1);
            return true;
        }

        @Override
        public boolean setPin(
                BluetoothDevice device,
                boolean accept,
                int len,
                byte[] pinCode,
                AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setPin")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService setPin")) {
                return false;
            }

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            // Only allow setting a pin in bonding state, or bonded state in case of security
            // upgrade.
            if (deviceProp == null || !deviceProp.isBondingOrBonded()) {
                Log.e(TAG, "setPin: device=" + device + ", not bonding");
                return false;
            }
            if (pinCode.length != len) {
                android.util.EventLog.writeEvent(
                        0x534e4554, "139287605", -1, "PIN code length mismatch");
                return false;
            }
            service.logUserBondResponse(
                    device, accept, BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_PIN_REPLIED);
            Log.i(
                    TAG,
                    "setPin: device="
                            + device
                            + ", accept="
                            + accept
                            + ", from "
                            + Utils.getUidPidString());
            return service.mNativeInterface.pinReply(
                    getBytesFromAddress(device.getAddress()), accept, len, pinCode);
        }

        @Override
        public boolean setPasskey(
                BluetoothDevice device,
                boolean accept,
                int len,
                byte[] passkey,
                AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setPasskey")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService setPasskey")) {
                return false;
            }

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null || !deviceProp.isBonding()) {
                Log.e(TAG, "setPasskey: device=" + device + ", not bonding");
                return false;
            }
            if (passkey.length != len) {
                android.util.EventLog.writeEvent(
                        0x534e4554, "139287605", -1, "Passkey length mismatch");
                return false;
            }
            service.logUserBondResponse(
                    device, accept, BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_SSP_REPLIED);
            Log.i(
                    TAG,
                    "setPasskey: device="
                            + device
                            + ", accept="
                            + accept
                            + ", from "
                            + Utils.getUidPidString());

            return service.mNativeInterface.sspReply(
                    getBytesFromAddress(device.getAddress()),
                    AbstractionLayer.BT_SSP_VARIANT_PASSKEY_ENTRY,
                    accept,
                    Utils.byteArrayToInt(passkey));
        }

        @Override
        public boolean setPairingConfirmation(
                BluetoothDevice device, boolean accept, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setPairingConfirmation")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null || !deviceProp.isBonding()) {
                Log.e(TAG, "setPairingConfirmation: device=" + device + ", not bonding");
                return false;
            }
            service.logUserBondResponse(
                    device, accept, BluetoothProtoEnums.BOND_SUB_STATE_LOCAL_SSP_REPLIED);
            Log.i(
                    TAG,
                    "setPairingConfirmation: device="
                            + device
                            + ", accept="
                            + accept
                            + ", from "
                            + Utils.getUidPidString());

            return service.mNativeInterface.sspReply(
                    getBytesFromAddress(device.getAddress()),
                    AbstractionLayer.BT_SSP_VARIANT_PASSKEY_CONFIRMATION,
                    accept,
                    0);
        }

        @Override
        public boolean getSilenceMode(BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getSilenceMode")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.mSilenceDeviceManager.getSilenceMode(device);
        }

        @Override
        public boolean setSilenceMode(
                BluetoothDevice device, boolean silence, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setSilenceMode")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.mSilenceDeviceManager.setSilenceMode(device, silence);
            return true;
        }

        @Override
        public int getPhonebookAccessPermission(
                BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "getPhonebookAccessPermission")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "AdapterService getPhonebookAccessPermission")) {
                return BluetoothDevice.ACCESS_UNKNOWN;
            }

            return service.getDeviceAccessFromPrefs(
                    device, PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE);
        }

        @Override
        public boolean setPhonebookAccessPermission(
                BluetoothDevice device, int value, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "setPhonebookAccessPermission")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.setPhonebookAccessPermission(device, value);
            return true;
        }

        @Override
        public int getMessageAccessPermission(
                BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "getMessageAccessPermission")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "AdapterService getMessageAccessPermission")) {
                return BluetoothDevice.ACCESS_UNKNOWN;
            }

            return service.getDeviceAccessFromPrefs(
                    device, MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE);
        }

        @Override
        public boolean setMessageAccessPermission(
                BluetoothDevice device, int value, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "setMessageAccessPermission")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.setMessageAccessPermission(device, value);
            return true;
        }

        @Override
        public int getSimAccessPermission(
                BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getSimAccessPermission")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getSimAccessPermission")) {
                return BluetoothDevice.ACCESS_UNKNOWN;
            }

            return service.getDeviceAccessFromPrefs(device, SIM_ACCESS_PERMISSION_PREFERENCE_FILE);
        }

        @Override
        public boolean setSimAccessPermission(
                BluetoothDevice device, int value, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setSimAccessPermission")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.setSimAccessPermission(device, value);
            return true;
        }

        @Override
        public void logL2capcocServerConnection(
                BluetoothDevice device,
                int port,
                boolean isSecured,
                int result,
                long socketCreationTimeMillis,
                long socketCreationLatencyMillis,
                long socketConnectionTimeMillis,
                long timeoutMillis) {
            AdapterService service = getService();
            if (service == null) {
                return;
            }
            service.logL2capcocServerConnection(
                    device,
                    port,
                    isSecured,
                    result,
                    socketCreationTimeMillis,
                    socketCreationLatencyMillis,
                    socketConnectionTimeMillis,
                    timeoutMillis,
                    Binder.getCallingUid());
        }

        @Override
        public IBluetoothSocketManager getSocketManager() {
            AdapterService service = getService();
            if (service == null) {
                return null;
            }

            return IBluetoothSocketManager.Stub.asInterface(service.mBluetoothSocketManagerBinder);
        }

        @Override
        public void logL2capcocClientConnection(
                BluetoothDevice device,
                int port,
                boolean isSecured,
                int result,
                long socketCreationTimeNanos,
                long socketCreationLatencyNanos,
                long socketConnectionTimeNanos) {
            AdapterService service = getService();
            if (service == null) {
                return;
            }
            service.logL2capcocClientConnection(
                    device,
                    port,
                    isSecured,
                    result,
                    socketCreationTimeNanos,
                    socketCreationLatencyNanos,
                    socketConnectionTimeNanos,
                    Binder.getCallingUid());
        }

        @Override
        public void logRfcommConnectionAttempt(
                BluetoothDevice device,
                boolean isSecured,
                int resultCode,
                long socketCreationTimeNanos,
                boolean isSerialPort) {
            AdapterService service = getService();
            if (service == null) {
                return;
            }
            service.logRfcommConnectionAttempt(
                    device,
                    isSecured,
                    resultCode,
                    socketCreationTimeNanos,
                    isSerialPort,
                    Binder.getCallingUid());
        }

        @Override
        public boolean sdpSearch(
                BluetoothDevice device, ParcelUuid uuid, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "sdpSearch")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService sdpSearch")) {
                return false;
            }

            if (service.mSdpManager == null) {
                return false;
            }
            service.mSdpManager.sdpSearch(device, uuid);
            return true;
        }

        @Override
        public int getBatteryLevel(BluetoothDevice device, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getBatteryLevel")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService getBatteryLevel")) {
                return BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
            }

            DeviceProperties deviceProp = service.mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null) {
                return BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
            }
            return deviceProp.getBatteryLevel();
        }

        @Override
        public int getMaxConnectedAudioDevices(AttributionSource attributionSource) {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "AdapterService getMaxConnectedAudioDevices")) {
                return -1;
            }

            return service.getMaxConnectedAudioDevices();
        }

        @Override
        public boolean factoryReset(AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);
            return service.factoryReset();
        }

        @Override
        public void registerBluetoothConnectionCallback(
                IBluetoothConnectionCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "registerBluetoothConnectionCallback")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return;
            }
            enforceBluetoothPrivilegedPermission(service);
            service.mBluetoothConnectionCallbacks.add(callback);
        }

        @Override
        public void unregisterBluetoothConnectionCallback(
                IBluetoothConnectionCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "unregisterBluetoothConnectionCallback")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return;
            }
            enforceBluetoothPrivilegedPermission(service);
            service.mBluetoothConnectionCallbacks.remove(callback);
        }

        @Override
        public void registerCallback(IBluetoothCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "registerCallback")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.registerRemoteCallback(callback);
        }

        @Override
        public void unregisterCallback(IBluetoothCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "unregisterCallback")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.unregisterRemoteCallback(callback);
        }

        @Override
        public boolean isMultiAdvertisementSupported() {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }

            int val = service.mAdapterProperties.getNumOfAdvertisementInstancesSupported();
            return val >= MIN_ADVT_INSTANCES_FOR_MA;
        }

        /**
         * This method has an associated binder cache. The invalidation methods must be changed if
         * the logic behind this method changes.
         */
        @Override
        public boolean isOffloadedFilteringSupported() {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }

            int val = service.getNumOfOffloadedScanFilterSupported();
            return val >= MIN_OFFLOADED_FILTERS;
        }

        @Override
        public boolean isOffloadedScanBatchingSupported() {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }

            int val = service.getOffloadedScanResultStorage();
            return val >= MIN_OFFLOADED_SCAN_STORAGE_BYTES;
        }

        @Override
        public boolean isLe2MPhySupported() {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }

            return service.isLe2MPhySupported();
        }

        @Override
        public boolean isLeCodedPhySupported() {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }

            return service.isLeCodedPhySupported();
        }

        @Override
        public boolean isLeExtendedAdvertisingSupported() {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }

            return service.isLeExtendedAdvertisingSupported();
        }

        @Override
        public boolean isLePeriodicAdvertisingSupported() {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }

            return service.isLePeriodicAdvertisingSupported();
        }

        @Override
        public int isLeAudioSupported() {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }

            Set<Integer> supportedProfileServices =
                    Arrays.stream(Config.getSupportedProfiles())
                            .boxed()
                            .collect(Collectors.toSet());
            int[] leAudioUnicastProfiles = Config.getLeAudioUnicastProfiles();

            if (Arrays.stream(leAudioUnicastProfiles)
                    .allMatch(supportedProfileServices::contains)) {
                return BluetoothStatusCodes.FEATURE_SUPPORTED;
            }

            return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
        }

        @Override
        public int isLeAudioBroadcastSourceSupported() {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }

            long supportBitMask = Config.getSupportedProfilesBitMask();
            if ((supportBitMask & (1 << BluetoothProfile.LE_AUDIO_BROADCAST)) != 0) {
                return BluetoothStatusCodes.FEATURE_SUPPORTED;
            }

            return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
        }

        @Override
        public int isLeAudioBroadcastAssistantSupported() {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }

            int[] supportedProfileServices = Config.getSupportedProfiles();

            if (Arrays.stream(supportedProfileServices)
                    .anyMatch(
                            profileId ->
                                    profileId == BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)) {
                return BluetoothStatusCodes.FEATURE_SUPPORTED;
            }

            return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
        }

        @Override
        public int isDistanceMeasurementSupported(AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            } else if (!callerIsSystemOrActiveOrManagedUser(
                    service, TAG, "isDistanceMeasurementSupported")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            } else if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);
            return BluetoothStatusCodes.FEATURE_SUPPORTED;
        }

        @Override
        public int getLeMaximumAdvertisingDataLength() {
            AdapterService service = getService();
            if (service == null) {
                return 0;
            }

            return service.getLeMaximumAdvertisingDataLength();
        }

        @Override
        public boolean isActivityAndEnergyReportingSupported() {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }

            return service.mAdapterProperties.isActivityAndEnergyReportingSupported();
        }

        @Override
        public BluetoothActivityEnergyInfo reportActivityInfo(AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.reportActivityInfo();
        }

        @Override
        public boolean registerMetadataListener(
                IBluetoothMetadataListener listener,
                BluetoothDevice device,
                AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "registerMetadataListener")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            List<IBluetoothMetadataListener> list = service.mMetadataListeners.get(device);
            if (list == null) {
                list = new ArrayList<>();
            } else if (list.contains(listener)) {
                // The device is already registered with this listener
                return true;
            }
            list.add(listener);
            service.mMetadataListeners.put(device, list);
            return true;
        }

        @Override
        public boolean unregisterMetadataListener(
                BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "unregisterMetadataListener")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.mMetadataListeners.remove(device);
            return true;
        }

        @Override
        public boolean setMetadata(
                BluetoothDevice device, int key, byte[] value, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "setMetadata")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return false;
            }

            enforceBluetoothPrivilegedPermission(service);

            if (value.length > BluetoothDevice.METADATA_MAX_LENGTH) {
                return false;
            }
            return service.mDatabaseManager.setCustomMeta(device, key, value);
        }

        @Override
        public byte[] getMetadata(BluetoothDevice device, int key, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "getMetadata")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            enforceBluetoothPrivilegedPermission(service);

            return service.mDatabaseManager.getCustomMeta(device, key);
        }

        @Override
        public int isRequestAudioPolicyAsSinkSupported(
                BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "isRequestAudioPolicyAsSinkSupported")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.FEATURE_NOT_CONFIGURED;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.isRequestAudioPolicyAsSinkSupported(device);
        }

        @Override
        public int requestAudioPolicyAsSink(
                BluetoothDevice device,
                BluetoothSinkAudioPolicy policies,
                AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            } else if (!callerIsSystemOrActiveOrManagedUser(
                    service, TAG, "requestAudioPolicyAsSink")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            } else if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.requestAudioPolicyAsSink(device, policies);
        }

        @Override
        public BluetoothSinkAudioPolicy getRequestedAudioPolicyAsSink(
                BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "getRequestedAudioPolicyAsSink")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.getRequestedAudioPolicyAsSink(device);
        }

        @Override
        public void requestActivityInfo(
                IBluetoothActivityEnergyInfoListener listener, AttributionSource source) {
            BluetoothActivityEnergyInfo info = reportActivityInfo(source);
            try {
                listener.onBluetoothActivityEnergyInfoAvailable(info);
            } catch (RemoteException e) {
                Log.e(TAG, "onBluetoothActivityEnergyInfo: RemoteException", e);
            }
        }

        @Override
        public void startBrEdr(AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "startBrEdr")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.startBrEdr();
        }

        @Override
        public void stopBle(AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "stopBle")
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return;
            }

            enforceBluetoothPrivilegedPermission(service);

            service.stopBle();
        }

        @Override
        public void dump(FileDescriptor fd, String[] args) {
            PrintWriter writer = new PrintWriter(new FileOutputStream(fd));
            AdapterService service = getService();
            if (service == null) {
                return;
            }

            enforceDumpPermission(service);

            service.dump(fd, writer, args);
            writer.close();
        }

        @Override
        public boolean allowLowLatencyAudio(boolean allowed, BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "allowLowLatencyAudio")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            Utils.getCallingAttributionSource(service),
                            "AdapterService allowLowLatencyAudio")) {
                return false;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.allowLowLatencyAudio(allowed, device);
        }

        @Override
        public int startRfcommListener(
                String name,
                ParcelUuid uuid,
                PendingIntent pendingIntent,
                AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "startRfcommListener")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService startRfcommListener")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.startRfcommListener(name, uuid, pendingIntent, attributionSource);
        }

        @Override
        public int stopRfcommListener(ParcelUuid uuid, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(service, TAG, "stopRfcommListener")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, attributionSource, "AdapterService stopRfcommListener")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.stopRfcommListener(uuid, attributionSource);
        }

        @Override
        public IncomingRfcommSocketInfo retrievePendingSocketForServiceRecord(
                ParcelUuid uuid, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "retrievePendingSocketForServiceRecord")
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "AdapterService retrievePendingSocketForServiceRecord")) {
                return null;
            }
            enforceBluetoothPrivilegedPermission(service);
            return service.retrievePendingSocketForServiceRecord(uuid, attributionSource);
        }

        @Override
        public void setForegroundUserId(int userId, AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service,
                            Utils.getCallingAttributionSource(mService),
                            "AdapterService setForegroundUserId")) {
                return;
            }
            enforceBluetoothPrivilegedPermission(service);
            Utils.setForegroundUserId(userId);
        }

        @Override
        public int setPreferredAudioProfiles(
                BluetoothDevice device, Bundle modeToProfileBundle, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "setPreferredAudioProfiles")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            requireNonNull(device);
            requireNonNull(modeToProfileBundle);
            if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
                throw new IllegalArgumentException("device cannot have an invalid address");
            }
            if (service.getBondState(device) != BluetoothDevice.BOND_BONDED) {
                return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
            }
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            return service.setPreferredAudioProfiles(device, modeToProfileBundle);
        }

        @Override
        public Bundle getPreferredAudioProfiles(BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return Bundle.EMPTY;
            }
            if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "getPreferredAudioProfiles")) {
                return Bundle.EMPTY;
            }
            requireNonNull(device);
            if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
                throw new IllegalArgumentException("device cannot have an invalid address");
            }
            if (service.getBondState(device) != BluetoothDevice.BOND_BONDED) {
                return Bundle.EMPTY;
            }
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return Bundle.EMPTY;
            }
            enforceBluetoothPrivilegedPermission(service);

            return service.getPreferredAudioProfiles(device);
        }

        @Override
        public int notifyActiveDeviceChangeApplied(
                BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystem(TAG, "notifyActiveDeviceChangeApplied")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            requireNonNull(device);
            if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
                throw new IllegalArgumentException("device cannot have an invalid address");
            }
            if (service.getBondState(device) != BluetoothDevice.BOND_BONDED) {
                return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
            }
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            return service.notifyActiveDeviceChangeApplied(device);
        }

        @Override
        public int registerPreferredAudioProfilesChangedCallback(
                IBluetoothPreferredAudioProfilesCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(
                    service, TAG, "registerPreferredAudioProfilesChangedCallback")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            requireNonNull(callback);
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            // If LE only mode is enabled, the dual mode audio feature is disabled
            if (!isDualModeAudioEnabled()) {
                return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
            }

            service.mPreferredAudioProfilesCallbacks.register(callback);
            return BluetoothStatusCodes.SUCCESS;
        }

        @Override
        public int unregisterPreferredAudioProfilesChangedCallback(
                IBluetoothPreferredAudioProfilesCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(
                    service, TAG, "unregisterPreferredAudioProfilesChangedCallback")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            requireNonNull(callback);
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            if (!service.mPreferredAudioProfilesCallbacks.unregister(callback)) {
                Log.e(
                        TAG,
                        "unregisterPreferredAudioProfilesChangedCallback: callback was never "
                                + "registered");
                return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
            }
            return BluetoothStatusCodes.SUCCESS;
        }

        @Override
        public int registerBluetoothQualityReportReadyCallback(
                IBluetoothQualityReportReadyCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(
                    service, TAG, "registerBluetoothQualityReportReadyCallback")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            requireNonNull(callback);
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            service.mBluetoothQualityReportReadyCallbacks.register(callback);
            return BluetoothStatusCodes.SUCCESS;
        }

        @Override
        public int unregisterBluetoothQualityReportReadyCallback(
                IBluetoothQualityReportReadyCallback callback, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(
                    service, TAG, "unregisterBluetoothQualityReportReadyCallback")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            requireNonNull(callback);
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            if (!service.mBluetoothQualityReportReadyCallbacks.unregister(callback)) {
                Log.e(
                        TAG,
                        "unregisterBluetoothQualityReportReadyCallback: callback was never "
                                + "registered");
                return BluetoothStatusCodes.ERROR_CALLBACK_NOT_REGISTERED;
            }
            return BluetoothStatusCodes.SUCCESS;
        }

        @Override
        public int getOffloadedTransportDiscoveryDataScanSupported(
                AttributionSource attributionSource) {
            AdapterService service = getService();
            if (service == null
                    || !callerIsSystemOrActiveOrManagedUser(
                            service, TAG, "getOffloadedTransportDiscoveryDataScanSupported")
                    || !Utils.checkScanPermissionForDataDelivery(
                            service,
                            attributionSource,
                            "getOffloadedTransportDiscoveryDataScanSupported")) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION;
            }
            enforceBluetoothPrivilegedPermission(service);

            return service.getOffloadedTransportDiscoveryDataScanSupported();
        }

        @Override
        public boolean isMediaProfileConnected(AttributionSource source) {
            AdapterService service = getService();
            if (service == null
                    || !Utils.checkConnectPermissionForDataDelivery(
                            service, source, "AdapterService.isMediaProfileConnected")) {
                return false;
            }
            enforceBluetoothPrivilegedPermission(service);

            return service.isMediaProfileConnected();
        }

        @Override
        public IBinder getBluetoothGatt() {
            AdapterService service = getService();
            return service == null ? null : service.getBluetoothGatt();
        }

        @Override
        public IBinder getBluetoothScan() {
            AdapterService service = getService();
            return service == null ? null : service.getBluetoothScan();
        }

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void unregAllGattClient(AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return;
            }
            enforceBluetoothPrivilegedPermission(service);
            service.unregAllGattClient(source);
        }

        @Override
        public IBinder getProfile(int profileId) {
            AdapterService service = getService();
            if (service == null) {
                return null;
            }

            return service.getProfile(profileId);
        }

        @Override
        public int setActiveAudioDevicePolicy(
                BluetoothDevice device, int activeAudioDevicePolicy, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
            }
            if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "setActiveAudioDevicePolicy")) {
                return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED;
            }
            if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
                throw new IllegalArgumentException("device cannot have an invalid address");
            }
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION;
            }

            enforceBluetoothPrivilegedPermission(service);
            return service.mDatabaseManager.setActiveAudioDevicePolicy(
                    device, activeAudioDevicePolicy);
        }

        @Override
        public int getActiveAudioDevicePolicy(BluetoothDevice device, AttributionSource source) {
            AdapterService service = getService();
            if (service == null) {
                return BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT;
            }
            if (!callerIsSystemOrActiveOrManagedUser(service, TAG, "getActiveAudioDevicePolicy")) {
                throw new IllegalStateException(
                        "Caller is not the system or part of the active/managed user");
            }
            if (!BluetoothAdapter.checkBluetoothAddress(device.getAddress())) {
                throw new IllegalArgumentException("device cannot have an invalid address");
            }
            if (!Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT;
            }

            enforceBluetoothPrivilegedPermission(service);
            return service.mDatabaseManager.getActiveAudioDevicePolicy(device);
        }
    }

    /**
     * Gets the preferred audio profiles for the device. See {@link
     * BluetoothAdapter#getPreferredAudioProfiles(BluetoothDevice)} for more details.
     *
     * @param device is the remote device whose preferences we want to fetch
     * @return a Bundle containing the preferred audio profiles for the device
     */
    public Bundle getPreferredAudioProfiles(BluetoothDevice device) {
        if (!isDualModeAudioEnabled()
                || mLeAudioService == null
                || !isDualModeAudioSinkDevice(device)) {
            return Bundle.EMPTY;
        }
        // Checks if the device is part of an LE Audio group
        List<BluetoothDevice> groupDevices = mLeAudioService.getGroupDevices(device);
        if (groupDevices.isEmpty()) {
            return Bundle.EMPTY;
        }

        // If there are no preferences stored, return the defaults
        Bundle storedBundle = Bundle.EMPTY;
        for (BluetoothDevice groupDevice : groupDevices) {
            Bundle groupDevicePreferences = mDatabaseManager.getPreferredAudioProfiles(groupDevice);
            if (!groupDevicePreferences.isEmpty()) {
                storedBundle = groupDevicePreferences;
                break;
            }
        }

        if (storedBundle.isEmpty()) {
            Bundle defaultPreferencesBundle = new Bundle();
            boolean useDefaultPreferences = false;
            if (isOutputOnlyAudioSupported(groupDevices)) {
                // Gets the default output only audio profile or defaults to LE_AUDIO if not present
                int outputOnlyDefault =
                        BluetoothProperties.getDefaultOutputOnlyAudioProfile()
                                .orElse(BluetoothProfile.LE_AUDIO);
                if (outputOnlyDefault != BluetoothProfile.A2DP
                        && outputOnlyDefault != BluetoothProfile.LE_AUDIO) {
                    outputOnlyDefault = BluetoothProfile.LE_AUDIO;
                }
                defaultPreferencesBundle.putInt(
                        BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY, outputOnlyDefault);
                useDefaultPreferences = true;
            }
            if (isDuplexAudioSupported(groupDevices)) {
                // Gets the default duplex audio profile or defaults to LE_AUDIO if not present
                int duplexDefault =
                        BluetoothProperties.getDefaultDuplexAudioProfile()
                                .orElse(BluetoothProfile.LE_AUDIO);
                if (duplexDefault != BluetoothProfile.HEADSET
                        && duplexDefault != BluetoothProfile.LE_AUDIO) {
                    duplexDefault = BluetoothProfile.LE_AUDIO;
                }
                defaultPreferencesBundle.putInt(BluetoothAdapter.AUDIO_MODE_DUPLEX, duplexDefault);
                useDefaultPreferences = true;
            }

            if (useDefaultPreferences) {
                return defaultPreferencesBundle;
            }
        }
        return storedBundle;
    }

    /**
     * Sets the preferred audio profiles for the device. See {@link
     * BluetoothAdapter#setPreferredAudioProfiles(BluetoothDevice, Bundle)} for more details.
     *
     * @param device is the remote device whose preferences we want to fetch
     * @param modeToProfileBundle is the preferences we want to set for the device
     * @return whether the preferences were successfully requested
     */
    private int setPreferredAudioProfiles(BluetoothDevice device, Bundle modeToProfileBundle) {
        Log.i(TAG, "setPreferredAudioProfiles for device=" + device.getAddressForLogging());
        if (!isDualModeAudioEnabled()) {
            Log.e(TAG, "setPreferredAudioProfiles called while sysprop is disabled");
            return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
        }
        if (mLeAudioService == null) {
            Log.e(TAG, "setPreferredAudioProfiles: LEA service is not up");
            return BluetoothStatusCodes.ERROR_PROFILE_NOT_CONNECTED;
        }
        if (!isDualModeAudioSinkDevice(device)) {
            Log.e(TAG, "setPreferredAudioProfiles: Not a dual mode audio device");
            return BluetoothStatusCodes.ERROR_NOT_DUAL_MODE_AUDIO_DEVICE;
        }
        // Checks if the device is part of an LE Audio group
        int groupId = mLeAudioService.getGroupId(device);
        List<BluetoothDevice> groupDevices = mLeAudioService.getGroupDevices(groupId);
        if (groupDevices.isEmpty()) {
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }

        // Copies relevant keys & values from modeToProfile bundle
        Bundle strippedPreferences = new Bundle();
        if (modeToProfileBundle.containsKey(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY)
                && isOutputOnlyAudioSupported(groupDevices)) {
            int outputOnlyProfile =
                    modeToProfileBundle.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY);
            if (outputOnlyProfile != BluetoothProfile.A2DP
                    && outputOnlyProfile != BluetoothProfile.LE_AUDIO) {
                throw new IllegalArgumentException(
                        "AUDIO_MODE_OUTPUT_ONLY has invalid value: " + outputOnlyProfile);
            }
            strippedPreferences.putInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY, outputOnlyProfile);
        }
        if (modeToProfileBundle.containsKey(BluetoothAdapter.AUDIO_MODE_DUPLEX)
                && isDuplexAudioSupported(groupDevices)) {
            int duplexProfile = modeToProfileBundle.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX);
            if (duplexProfile != BluetoothProfile.HEADSET
                    && duplexProfile != BluetoothProfile.LE_AUDIO) {
                throw new IllegalArgumentException(
                        "AUDIO_MODE_DUPLEX has invalid value: " + duplexProfile);
            }
            strippedPreferences.putInt(BluetoothAdapter.AUDIO_MODE_DUPLEX, duplexProfile);
        }

        synchronized (mCsipGroupsPendingAudioProfileChanges) {
            if (mCsipGroupsPendingAudioProfileChanges.containsKey(groupId)) {
                return BluetoothStatusCodes.ERROR_ANOTHER_ACTIVE_REQUEST;
            }

            Bundle previousPreferences = getPreferredAudioProfiles(device);

            int dbResult =
                    mDatabaseManager.setPreferredAudioProfiles(groupDevices, strippedPreferences);
            if (dbResult != BluetoothStatusCodes.SUCCESS) {
                return dbResult;
            }

            int outputOnlyPreference =
                    strippedPreferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY);
            if (outputOnlyPreference == 0) {
                outputOnlyPreference =
                        previousPreferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY);
            }
            int duplexPreference = strippedPreferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX);
            if (duplexPreference == 0) {
                duplexPreference = previousPreferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX);
            }

            mLeAudioService.sendAudioProfilePreferencesToNative(
                    groupId,
                    outputOnlyPreference == BluetoothProfile.LE_AUDIO,
                    duplexPreference == BluetoothProfile.LE_AUDIO);

            /* Populates the HashMap to hold requests on the groupId. We will update
            numRequestsToAudioFramework after we make requests to the audio framework */
            PendingAudioProfilePreferenceRequest holdRequest =
                    new PendingAudioProfilePreferenceRequest(strippedPreferences, 0, device);
            mCsipGroupsPendingAudioProfileChanges.put(groupId, holdRequest);

            // Notifies audio framework via the handler thread to avoid this blocking calls
            mHandler.post(
                    () ->
                            sendPreferredAudioProfileChangeToAudioFramework(
                                    device, strippedPreferences, previousPreferences));
            return BluetoothStatusCodes.SUCCESS;
        }
    }

    /**
     * Sends the updated preferred audio profiles to the audio framework.
     *
     * @param device is the device with updated audio preferences
     * @param strippedPreferences is a {@link Bundle} containing the preferences
     */
    private void sendPreferredAudioProfileChangeToAudioFramework(
            BluetoothDevice device, Bundle strippedPreferences, Bundle previousPreferences) {
        int newOutput = strippedPreferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY);
        int newDuplex = strippedPreferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX);
        int previousOutput = previousPreferences.getInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY);
        int previousDuplex = previousPreferences.getInt(BluetoothAdapter.AUDIO_MODE_DUPLEX);

        Log.i(
                TAG,
                "sendPreferredAudioProfileChangeToAudioFramework: changing output from "
                        + BluetoothProfile.getProfileName(previousOutput)
                        + " to "
                        + BluetoothProfile.getProfileName(newOutput)
                        + " and duplex from "
                        + BluetoothProfile.getProfileName(previousDuplex)
                        + " to "
                        + BluetoothProfile.getProfileName(newDuplex));

        // If no change from existing preferences, do not inform audio framework
        if (previousOutput == newOutput && previousDuplex == newDuplex) {
            Log.i(TAG, "No change to preferred audio profiles, no requests to Audio FW");
            sendPreferredAudioProfilesCallbackToApps(
                    device, strippedPreferences, BluetoothStatusCodes.SUCCESS);
            return;
        }

        int numRequestsToAudioFw = 0;

        // Checks if the device is part of an LE Audio group
        int groupId = mLeAudioService.getGroupId(device);
        List<BluetoothDevice> groupDevices = mLeAudioService.getGroupDevices(groupId);
        if (groupDevices.isEmpty()) {
            Log.i(
                    TAG,
                    "sendPreferredAudioProfileChangeToAudioFramework: Empty LEA group for "
                            + "device - "
                            + device);
            sendPreferredAudioProfilesCallbackToApps(
                    device, strippedPreferences, BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED);
            return;
        }

        synchronized (mCsipGroupsPendingAudioProfileChanges) {
            if (previousOutput != newOutput) {
                if (newOutput == BluetoothProfile.A2DP
                        && mA2dpService.getActiveDevice() != null
                        && groupDevices.contains(mA2dpService.getActiveDevice())) {
                    Log.i(TAG, "Sent change for AUDIO_MODE_OUTPUT_ONLY to A2DP to Audio FW");
                    numRequestsToAudioFw +=
                            mA2dpService.sendPreferredAudioProfileChangeToAudioFramework();
                } else if (newOutput == BluetoothProfile.LE_AUDIO
                        && mLeAudioService.getActiveGroupId() == groupId) {
                    Log.i(TAG, "Sent change for AUDIO_MODE_OUTPUT_ONLY to LE_AUDIO to Audio FW");
                    numRequestsToAudioFw +=
                            mLeAudioService.sendPreferredAudioProfileChangeToAudioFramework();
                }
            }

            if (previousDuplex != newDuplex) {
                if (newDuplex == BluetoothProfile.HEADSET
                        && mHeadsetService.getActiveDevice() != null
                        && groupDevices.contains(mHeadsetService.getActiveDevice())) {
                    Log.i(TAG, "Sent change for AUDIO_MODE_DUPLEX to HFP to Audio FW");
                    // TODO(b/275426145): Add similar HFP method in BluetoothProfileConnectionInfo
                    numRequestsToAudioFw +=
                            mA2dpService.sendPreferredAudioProfileChangeToAudioFramework();
                } else if (newDuplex == BluetoothProfile.LE_AUDIO
                        && mLeAudioService.getActiveGroupId() == groupId) {
                    Log.i(TAG, "Sent change for AUDIO_MODE_DUPLEX to LE_AUDIO to Audio FW");
                    numRequestsToAudioFw +=
                            mLeAudioService.sendPreferredAudioProfileChangeToAudioFramework();
                }
            }

            Log.i(
                    TAG,
                    "sendPreferredAudioProfileChangeToAudioFramework: sent "
                            + numRequestsToAudioFw
                            + " request(s) to the Audio Framework for device: "
                            + device);

            if (numRequestsToAudioFw > 0) {
                mCsipGroupsPendingAudioProfileChanges.put(
                        groupId,
                        new PendingAudioProfilePreferenceRequest(
                                strippedPreferences, numRequestsToAudioFw, device));

                Message m =
                        mHandler.obtainMessage(
                                MESSAGE_PREFERRED_AUDIO_PROFILES_AUDIO_FRAMEWORK_TIMEOUT);
                m.obj = groupId;
                mHandler.sendMessageDelayed(m, PREFERRED_AUDIO_PROFILE_CHANGE_TIMEOUT.toMillis());
                return;
            }
        }
        sendPreferredAudioProfilesCallbackToApps(
                device, strippedPreferences, BluetoothStatusCodes.SUCCESS);
    }

    private void removeFromPendingAudioProfileChanges(int groupId) {
        synchronized (mCsipGroupsPendingAudioProfileChanges) {
            Log.i(
                    TAG,
                    "removeFromPendingAudioProfileChanges: Timeout on change for groupId="
                            + groupId);
            if (!mCsipGroupsPendingAudioProfileChanges.containsKey(groupId)) {
                Log.e(
                        TAG,
                        "removeFromPendingAudioProfileChanges( "
                                + groupId
                                + ", "
                                + groupId
                                + ") is not pending");
                return;
            }
        }
    }

    /**
     * Notification from the audio framework that an active device change has taken effect. See
     * {@link BluetoothAdapter#notifyActiveDeviceChangeApplied(BluetoothDevice)} for more details.
     *
     * @param device the remote device whose preferred audio profiles have been changed
     * @return whether the Bluetooth stack acknowledged the change successfully
     */
    private int notifyActiveDeviceChangeApplied(BluetoothDevice device) {
        if (mLeAudioService == null) {
            Log.e(TAG, "LE Audio profile not enabled");
            return BluetoothStatusCodes.ERROR_PROFILE_NOT_CONNECTED;
        }

        int groupId = mLeAudioService.getGroupId(device);
        if (groupId == LE_AUDIO_GROUP_ID_INVALID) {
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }

        synchronized (mCsipGroupsPendingAudioProfileChanges) {
            if (!mCsipGroupsPendingAudioProfileChanges.containsKey(groupId)) {
                Log.e(
                        TAG,
                        "notifyActiveDeviceChangeApplied, but no pending request for "
                                + "groupId: "
                                + groupId);
                return BluetoothStatusCodes.ERROR_UNKNOWN;
            }

            PendingAudioProfilePreferenceRequest pendingRequest =
                    mCsipGroupsPendingAudioProfileChanges.get(groupId);

            // If this is the final audio framework request, send callback to apps
            if (pendingRequest.mRemainingRequestsToAudioFramework == 1) {
                Log.i(
                        TAG,
                        "notifyActiveDeviceChangeApplied: Complete for device "
                                + pendingRequest.mDeviceRequested);
                sendPreferredAudioProfilesCallbackToApps(
                        pendingRequest.mDeviceRequested,
                        pendingRequest.mRequestedPreferences,
                        BluetoothStatusCodes.SUCCESS);
                // Removes the timeout from the handler
                mHandler.removeMessages(
                        MESSAGE_PREFERRED_AUDIO_PROFILES_AUDIO_FRAMEWORK_TIMEOUT, groupId);
            } else if (pendingRequest.mRemainingRequestsToAudioFramework > 1) {
                PendingAudioProfilePreferenceRequest updatedPendingRequest =
                        new PendingAudioProfilePreferenceRequest(
                                pendingRequest.mRequestedPreferences,
                                pendingRequest.mRemainingRequestsToAudioFramework - 1,
                                pendingRequest.mDeviceRequested);
                Log.i(
                        TAG,
                        "notifyActiveDeviceChangeApplied: Updating device "
                                + updatedPendingRequest.mDeviceRequested
                                + " with new remaining requests count="
                                + updatedPendingRequest.mRemainingRequestsToAudioFramework);
                mCsipGroupsPendingAudioProfileChanges.put(groupId, updatedPendingRequest);
            } else {
                Log.i(
                        TAG,
                        "notifyActiveDeviceChangeApplied: "
                                + pendingRequest.mDeviceRequested
                                + " has no remaining requests to audio framework, but is still"
                                + " present in mCsipGroupsPendingAudioProfileChanges");
            }
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    private void sendPreferredAudioProfilesCallbackToApps(
            BluetoothDevice device, Bundle preferredAudioProfiles, int status) {
        int n = mPreferredAudioProfilesCallbacks.beginBroadcast();
        Log.d(
                TAG,
                "sendPreferredAudioProfilesCallbackToApps() - Broadcasting audio profile "
                        + ("change callback to device: " + device)
                        + (" and status=" + status)
                        + (" to " + n + " receivers."));
        for (int i = 0; i < n; i++) {
            try {
                mPreferredAudioProfilesCallbacks
                        .getBroadcastItem(i)
                        .onPreferredAudioProfilesChanged(device, preferredAudioProfiles, status);
            } catch (RemoteException e) {
                Log.d(
                        TAG,
                        "sendPreferredAudioProfilesCallbackToApps() - Callback #"
                                + i
                                + " failed ("
                                + e
                                + ")");
            }
        }
        mPreferredAudioProfilesCallbacks.finishBroadcast();
    }

    // ----API Methods--------

    public boolean isEnabled() {
        return getState() == BluetoothAdapter.STATE_ON;
    }

    public int getState() {
        if (mAdapterProperties != null) {
            return mAdapterProperties.getState();
        }
        return BluetoothAdapter.STATE_OFF;
    }

    @RequiresPermission(
            anyOf = {
                android.Manifest.permission.INTERACT_ACROSS_USERS,
                android.Manifest.permission.MANAGE_USERS,
            })
    public synchronized void enable(boolean quietMode) {
        // Enforce the user restriction for disallowing Bluetooth if it was set.
        if (mUserManager.hasUserRestrictionForUser(
                UserManager.DISALLOW_BLUETOOTH, UserHandle.SYSTEM)) {
            Log.d(TAG, "enable() called when Bluetooth was disallowed");
            return;
        }
        if (Flags.fastBindToApp()) {
            // The call to init must be done on the main thread
            mHandler.post(() -> init());
        }

        Log.i(TAG, "enable() - Enable called with quiet mode status =  " + quietMode);
        mQuietmode = quietMode;
        mAdapterStateMachine.sendMessage(AdapterState.BLE_TURN_ON);
    }

    void disable() {
        Log.d(TAG, "disable() called with mRunningProfiles.size() = " + mRunningProfiles.size());
        mAdapterStateMachine.sendMessage(AdapterState.USER_TURN_OFF);
    }

    public String getName() {
        return mAdapterProperties.getName();
    }

    public int getNameLengthForAdvertise() {
        return mAdapterProperties.getName().length();
    }

    List<DiscoveringPackage> getDiscoveringPackages() {
        return mDiscoveringPackages;
    }

    void clearDiscoveringPackages() {
        synchronized (mDiscoveringPackages) {
            mDiscoveringPackages.clear();
        }
    }

    boolean startDiscovery(AttributionSource attributionSource) {
        UserHandle callingUser = Binder.getCallingUserHandle();
        Log.d(TAG, "startDiscovery");
        String callingPackage = attributionSource.getPackageName();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        boolean isQApp = Utils.checkCallerTargetSdk(this, callingPackage, Build.VERSION_CODES.Q);
        boolean hasDisavowedLocation =
                Utils.hasDisavowedLocationForScan(this, attributionSource, mTestModeEnabled);
        String permission = null;
        if (Utils.checkCallerHasNetworkSettingsPermission(this)) {
            permission = android.Manifest.permission.NETWORK_SETTINGS;
        } else if (Utils.checkCallerHasNetworkSetupWizardPermission(this)) {
            permission = android.Manifest.permission.NETWORK_SETUP_WIZARD;
        } else if (!hasDisavowedLocation) {
            if (isQApp) {
                if (!Utils.checkCallerHasFineLocation(this, attributionSource, callingUser)) {
                    return false;
                }
                permission = android.Manifest.permission.ACCESS_FINE_LOCATION;
            } else {
                if (!Utils.checkCallerHasCoarseLocation(this, attributionSource, callingUser)) {
                    return false;
                }
                permission = android.Manifest.permission.ACCESS_COARSE_LOCATION;
            }
        }

        synchronized (mDiscoveringPackages) {
            mDiscoveringPackages.add(
                    new DiscoveringPackage(callingPackage, permission, hasDisavowedLocation));
        }
        return mNativeInterface.startDiscovery();
    }

    /**
     * Same as API method {@link BluetoothAdapter#getBondedDevices()}
     *
     * @return array of bonded {@link BluetoothDevice} or null on error
     */
    public BluetoothDevice[] getBondedDevices() {
        return mAdapterProperties.getBondedDevices();
    }

    /**
     * Get the database manager to access Bluetooth storage
     *
     * @return {@link DatabaseManager} or null on error
     */
    public DatabaseManager getDatabase() {
        return mDatabaseManager;
    }

    public byte[] getByteIdentityAddress(BluetoothDevice device) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp != null && deviceProp.getIdentityAddress() != null) {
            return Utils.getBytesFromAddress(deviceProp.getIdentityAddress());
        }

        if (Flags.identityAddressNullIfUnknown()) {
            // Return null if identity address unknown
            return null;
        } else {
            return Utils.getByteAddress(device);
        }
    }

    public BluetoothDevice getDeviceFromByte(byte[] address) {
        BluetoothDevice device = mRemoteDevices.getDevice(address);
        if (device == null) {
            device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
        }
        return device;
    }

    public String getIdentityAddress(String address) {
        BluetoothDevice device =
                BluetoothAdapter.getDefaultAdapter().getRemoteDevice(Ascii.toUpperCase(address));
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp != null && deviceProp.getIdentityAddress() != null) {
            return deviceProp.getIdentityAddress();
        } else {
            if (Flags.identityAddressNullIfUnknown()) {
                // Return null if identity address unknown
                return null;
            } else {
                return address;
            }
        }
    }

    private static class CallerInfo {
        public String callerPackageName;
        public UserHandle user;
    }

    boolean createBond(
            BluetoothDevice device,
            int transport,
            OobData remoteP192Data,
            OobData remoteP256Data,
            String callingPackage) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp != null && deviceProp.getBondState() != BluetoothDevice.BOND_NONE) {
            // true for BONDING, false for BONDED
            return deviceProp.getBondState() == BluetoothDevice.BOND_BONDING;
        }

        if (!isPackageNameAccurate(this, callingPackage, Binder.getCallingUid())) {
            return false;
        }

        CallerInfo createBondCaller = new CallerInfo();
        createBondCaller.callerPackageName = callingPackage;
        createBondCaller.user = Binder.getCallingUserHandle();
        mBondAttemptCallerInfo.put(device.getAddress(), createBondCaller);

        mRemoteDevices.setBondingInitiatedLocally(Utils.getByteAddress(device));

        // Pairing is unreliable while scanning, so cancel discovery
        // Note, remove this when native stack improves
        mNativeInterface.cancelDiscovery();

        Message msg = mBondStateMachine.obtainMessage(BondStateMachine.CREATE_BOND);
        msg.obj = device;
        msg.arg1 = transport;

        Bundle remoteOobDatasBundle = new Bundle();
        boolean setData = false;
        if (remoteP192Data != null) {
            remoteOobDatasBundle.putParcelable(BondStateMachine.OOBDATAP192, remoteP192Data);
            setData = true;
        }
        if (remoteP256Data != null) {
            remoteOobDatasBundle.putParcelable(BondStateMachine.OOBDATAP256, remoteP256Data);
            setData = true;
        }
        if (setData) {
            msg.setData(remoteOobDatasBundle);
        }
        mBondStateMachine.sendMessage(msg);
        return true;
    }

    /**
     * Fetches the local OOB data to give out to remote.
     *
     * @param transport - specify data transport.
     * @param callback - callback used to receive the requested {@link OobData}; null will be
     *     ignored silently.
     */
    public synchronized void generateLocalOobData(
            int transport, IBluetoothOobDataCallback callback) {
        if (callback == null) {
            Log.e(TAG, "'callback' argument must not be null!");
            return;
        }
        if (mOobDataCallbackQueue.peek() != null) {
            try {
                callback.onError(BluetoothStatusCodes.ERROR_ANOTHER_ACTIVE_OOB_REQUEST);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to make callback", e);
            }
            return;
        }
        mOobDataCallbackQueue.offer(callback);
        mHandler.postDelayed(
                () -> removeFromOobDataCallbackQueue(callback),
                GENERATE_LOCAL_OOB_DATA_TIMEOUT.toMillis());
        mNativeInterface.generateLocalOobData(transport);
    }

    private synchronized void removeFromOobDataCallbackQueue(IBluetoothOobDataCallback callback) {
        if (callback == null) {
            return;
        }

        if (mOobDataCallbackQueue.peek() == callback) {
            try {
                mOobDataCallbackQueue.poll().onError(BluetoothStatusCodes.ERROR_UNKNOWN);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to make OobDataCallback to remove callback from queue", e);
            }
        }
    }

    /* package */ synchronized void notifyOobDataCallback(int transport, OobData oobData) {
        if (mOobDataCallbackQueue.peek() == null) {
            Log.e(TAG, "Failed to make callback, no callback exists");
            return;
        }
        if (oobData == null) {
            try {
                mOobDataCallbackQueue.poll().onError(BluetoothStatusCodes.ERROR_UNKNOWN);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to make callback", e);
            }
        } else {
            try {
                mOobDataCallbackQueue.poll().onOobData(transport, oobData);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to make callback", e);
            }
        }
    }

    public boolean isQuietModeEnabled() {
        Log.d(TAG, "isQuietModeEnabled() - Enabled = " + mQuietmode);
        return mQuietmode;
    }

    public void updateUuids() {
        Log.d(TAG, "updateUuids() - Updating UUIDs for bonded devices");
        BluetoothDevice[] bondedDevices = getBondedDevices();
        if (bondedDevices == null) {
            return;
        }

        for (BluetoothDevice device : bondedDevices) {
            mRemoteDevices.updateUuids(device);
        }
    }

    /**
     * Update device UUID changed to {@link BondStateMachine}
     *
     * @param device remote device of interest
     */
    public void deviceUuidUpdated(BluetoothDevice device) {
        // Notify BondStateMachine for SDP complete / UUID changed.
        Message msg = mBondStateMachine.obtainMessage(BondStateMachine.UUID_UPDATE);
        msg.obj = device;
        mBondStateMachine.sendMessage(msg);
    }

    /**
     * Get the bond state of a particular {@link BluetoothDevice}
     *
     * @param device remote device of interest
     * @return bond state
     *     <p>Possible values are {@link BluetoothDevice#BOND_NONE}, {@link
     *     BluetoothDevice#BOND_BONDING}, {@link BluetoothDevice#BOND_BONDED}.
     */
    @VisibleForTesting
    public int getBondState(BluetoothDevice device) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return BluetoothDevice.BOND_NONE;
        }
        return deviceProp.getBondState();
    }

    int getConnectionState(BluetoothDevice device) {
        final String address = device.getAddress();
        if (Flags.apiGetConnectionStateUsingIdentityAddress()) {
            int connectionState = mNativeInterface.getConnectionState(getBytesFromAddress(address));
            final String identityAddress = getIdentityAddress(address);
            if (identityAddress != null) {
                connectionState |=
                        mNativeInterface.getConnectionState(getBytesFromAddress(identityAddress));
            }
            return connectionState;
        }
        return mNativeInterface.getConnectionState(getBytesFromAddress(address));
    }

    int getConnectionHandle(BluetoothDevice device, int transport) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return BluetoothDevice.ERROR;
        }
        return deviceProp.getConnectionHandle(transport);
    }

    /**
     * Get ASHA Capability
     *
     * @param device discovered bluetooth device
     * @return ASHA capability
     */
    public int getAshaCapability(BluetoothDevice device) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return BluetoothDevice.ERROR;
        }
        return deviceProp.getAshaCapability();
    }

    /**
     * Get ASHA truncated HiSyncId
     *
     * @param device discovered bluetooth device
     * @return ASHA truncated HiSyncId
     */
    public int getAshaTruncatedHiSyncId(BluetoothDevice device) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return BluetoothDevice.ERROR;
        }
        return deviceProp.getAshaTruncatedHiSyncId();
    }

    /**
     * Checks whether the device was recently associated with the comapnion app that called {@link
     * BluetoothDevice#createBond}. This allows these devices to skip the pairing dialog if their
     * pairing variant is {@link BluetoothDevice#PAIRING_VARIANT_CONSENT}.
     *
     * @param device the bluetooth device that is being bonded
     * @return true if it was recently associated and we can bypass the dialog, false otherwise
     */
    public boolean canBondWithoutDialog(BluetoothDevice device) {
        if (mBondAttemptCallerInfo.containsKey(device.getAddress())) {
            CallerInfo bondCallerInfo = mBondAttemptCallerInfo.get(device.getAddress());

            return mCompanionDeviceManager.canPairWithoutPrompt(
                    bondCallerInfo.callerPackageName, device.getAddress(), bondCallerInfo.user);
        }
        return false;
    }

    /**
     * Returns the package name of the most recent caller that called {@link
     * BluetoothDevice#createBond} on the given device.
     */
    @Nullable
    public String getPackageNameOfBondingApplication(BluetoothDevice device) {
        CallerInfo info = mBondAttemptCallerInfo.get(device.getAddress());
        if (info == null) {
            return null;
        }
        return info.callerPackageName;
    }

    /**
     * Sets device as the active devices for the profiles passed into the function.
     *
     * @param device is the remote bluetooth device
     * @param profiles is a constant that references for which profiles we'll be setting the remote
     *     device as our active device. One of the following: {@link
     *     BluetoothAdapter#ACTIVE_DEVICE_AUDIO}, {@link BluetoothAdapter#ACTIVE_DEVICE_PHONE_CALL}
     *     {@link BluetoothAdapter#ACTIVE_DEVICE_ALL}
     * @return false if profiles value is not one of the constants we accept, true otherwise
     */
    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                android.Manifest.permission.MODIFY_PHONE_STATE,
            })
    public boolean setActiveDevice(BluetoothDevice device, @ActiveDeviceUse int profiles) {
        boolean setA2dp = false;
        boolean setHeadset = false;

        // Determine for which profiles we want to set device as our active device
        switch (profiles) {
            case BluetoothAdapter.ACTIVE_DEVICE_AUDIO:
                setA2dp = true;
                break;
            case BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL:
                setHeadset = true;
                break;
            case BluetoothAdapter.ACTIVE_DEVICE_ALL:
                setA2dp = true;
                setHeadset = true;
                break;
            default:
                return false;
        }

        boolean a2dpSupported =
                mA2dpService != null
                        && (device == null
                                || mA2dpService.getConnectionPolicy(device)
                                        == BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        boolean hfpSupported =
                mHeadsetService != null
                        && (device == null
                                || mHeadsetService.getConnectionPolicy(device)
                                        == BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        boolean leAudioSupported =
                mLeAudioService != null
                        && (device == null
                                || mLeAudioService.getConnectionPolicy(device)
                                        == BluetoothProfile.CONNECTION_POLICY_ALLOWED);

        if (leAudioSupported) {
            Log.i(TAG, "setActiveDevice: Setting active Le Audio device " + device);
            if (device == null) {
                /* If called by BluetoothAdapter it means Audio should not be stopped.
                 * For this reason let's say that fallback device exists
                 */
                mLeAudioService.removeActiveDevice(true /* hasFallbackDevice */);
            } else {
                mLeAudioService.setActiveDevice(device);
            }
        }

        if (setA2dp && a2dpSupported) {
            Log.i(TAG, "setActiveDevice: Setting active A2dp device " + device);
            if (device == null) {
                mA2dpService.removeActiveDevice(false);
            } else {
                /* Workaround for the controller issue which is not able to handle correctly
                 * A2DP offloader vendor specific command while ISO Data path is set.
                 * Proper solutions should be delivered in b/312396770
                 */
                if (mLeAudioService != null) {
                    List<BluetoothDevice> activeLeAudioDevices = mLeAudioService.getActiveDevices();
                    if (activeLeAudioDevices.get(0) != null) {
                        mLeAudioService.removeActiveDevice(true);
                    }
                }
                mA2dpService.setActiveDevice(device);
            }
        }

        if (mHearingAidService != null
                && (device == null
                        || mHearingAidService.getConnectionPolicy(device)
                                == BluetoothProfile.CONNECTION_POLICY_ALLOWED)) {
            Log.i(TAG, "setActiveDevice: Setting active Hearing Aid " + device);
            if (device == null) {
                mHearingAidService.removeActiveDevice(false);
            } else {
                mHearingAidService.setActiveDevice(device);
            }
        }

        if (setHeadset && hfpSupported) {
            Log.i(TAG, "setActiveDevice: Setting active Headset " + device);
            mHeadsetService.setActiveDevice(device);
        }

        return true;
    }

    /**
     * Checks if all supported classic audio profiles are active on this LE Audio device.
     *
     * @param leAudioDevice the remote device
     * @return {@code true} if all supported classic audio profiles are active on this device,
     *     {@code false} otherwise
     */
    public boolean isAllSupportedClassicAudioProfilesActive(BluetoothDevice leAudioDevice) {
        if (mLeAudioService == null) {
            return false;
        }
        boolean a2dpSupported = isProfileSupported(leAudioDevice, BluetoothProfile.A2DP);
        boolean hfpSupported = isProfileSupported(leAudioDevice, BluetoothProfile.HEADSET);

        List<BluetoothDevice> groupDevices = mLeAudioService.getGroupDevices(leAudioDevice);
        if (hfpSupported && mHeadsetService != null) {
            BluetoothDevice activeHfpDevice = mHeadsetService.getActiveDevice();
            if (activeHfpDevice == null || !groupDevices.contains(activeHfpDevice)) {
                return false;
            }
        }
        if (a2dpSupported && mA2dpService != null) {
            BluetoothDevice activeA2dpDevice = mA2dpService.getActiveDevice();
            if (activeA2dpDevice == null || !groupDevices.contains(activeA2dpDevice)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the active devices for the BluetoothProfile specified
     *
     * @param profile is the profile from which we want the active devices. Possible values are:
     *     {@link BluetoothProfile#HEADSET}, {@link BluetoothProfile#A2DP}, {@link
     *     BluetoothProfile#HEARING_AID} {@link BluetoothProfile#LE_AUDIO}
     * @return A list of active bluetooth devices
     */
    public List<BluetoothDevice> getActiveDevices(@ActiveDeviceProfile int profile) {
        List<BluetoothDevice> activeDevices = new ArrayList<>();

        switch (profile) {
            case BluetoothProfile.HEADSET:
                if (mHeadsetService == null) {
                    Log.e(TAG, "getActiveDevices: HeadsetService is null");
                } else {
                    BluetoothDevice device = mHeadsetService.getActiveDevice();
                    if (device != null) {
                        activeDevices.add(device);
                    }
                    Log.i(TAG, "getActiveDevices: Headset device: " + device);
                }
                break;
            case BluetoothProfile.A2DP:
                if (mA2dpService == null) {
                    Log.e(TAG, "getActiveDevices: A2dpService is null");
                } else {
                    BluetoothDevice device = mA2dpService.getActiveDevice();
                    if (device != null) {
                        activeDevices.add(device);
                    }
                    Log.i(TAG, "getActiveDevices: A2dp device: " + device);
                }
                break;
            case BluetoothProfile.HEARING_AID:
                if (mHearingAidService == null) {
                    Log.e(TAG, "getActiveDevices: HearingAidService is null");
                } else {
                    activeDevices = mHearingAidService.getActiveDevices();
                    Log.i(
                            TAG,
                            "getActiveDevices: Hearing Aid devices:"
                                    + (" Left[" + activeDevices.get(0) + "] -")
                                    + (" Right[" + activeDevices.get(1) + "]"));
                }
                break;
            case BluetoothProfile.LE_AUDIO:
                if (mLeAudioService == null) {
                    Log.e(TAG, "getActiveDevices: LeAudioService is null");
                } else {
                    activeDevices = mLeAudioService.getActiveDevices();
                    Log.i(
                            TAG,
                            "getActiveDevices: LeAudio devices:"
                                    + (" Lead[" + activeDevices.get(0) + "] -")
                                    + (" member_1[" + activeDevices.get(1) + "]"));
                }
                break;
            default:
                Log.e(TAG, "getActiveDevices: profile value is not valid");
        }
        return activeDevices;
    }

    /**
     * Attempts connection to all enabled and supported bluetooth profiles between the local and
     * remote device
     *
     * @param device is the remote device with which to connect these profiles
     * @return {@link BluetoothStatusCodes#SUCCESS} if all profiles connections are attempted, false
     *     if an error occurred
     */
    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                android.Manifest.permission.MODIFY_PHONE_STATE,
            })
    public int connectAllEnabledProfiles(BluetoothDevice device) {
        if (!profileServicesRunning()) {
            Log.e(TAG, "connectAllEnabledProfiles: Not all profile services running");
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }

        // Checks if any profiles are enablde or disabled and if so, only connect enabled profiles
        if (!isAllProfilesUnknown(device)) {
            return connectEnabledProfiles(device);
        }

        connectAllSupportedProfiles(device);

        return BluetoothStatusCodes.SUCCESS;
    }

    /**
     * Connect all supported bluetooth profiles between the local and remote device
     *
     * @param device is the remote device with which to connect all supported profiles
     */
    @RequiresPermission(
            allOf = {
                android.Manifest.permission.BLUETOOTH_PRIVILEGED,
                android.Manifest.permission.MODIFY_PHONE_STATE,
            })
    void connectAllSupportedProfiles(BluetoothDevice device) {
        int numProfilesConnected = 0;

        // All profile toggles disabled, so connects all supported profiles
        if (mA2dpService != null && isProfileSupported(device, BluetoothProfile.A2DP)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting A2dp");
            // Set connection policy also connects the profile with CONNECTION_POLICY_ALLOWED
            mA2dpService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mA2dpSinkService != null && isProfileSupported(device, BluetoothProfile.A2DP_SINK)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting A2dp Sink");
            mA2dpSinkService.setConnectionPolicy(
                    device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mHeadsetService != null && isProfileSupported(device, BluetoothProfile.HEADSET)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting Headset Profile");
            mHeadsetService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mHeadsetClientService != null
                && isProfileSupported(device, BluetoothProfile.HEADSET_CLIENT)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting HFP");
            mHeadsetClientService.setConnectionPolicy(
                    device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mMapClientService != null && isProfileSupported(device, BluetoothProfile.MAP_CLIENT)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting MAP");
            mMapClientService.setConnectionPolicy(
                    device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mHidHostService != null && isProfileSupported(device, BluetoothProfile.HID_HOST)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting Hid Host Profile");
            mHidHostService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mPanService != null && isProfileSupported(device, BluetoothProfile.PAN)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting Pan Profile");
            mPanService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mPbapClientService != null
                && isProfileSupported(device, BluetoothProfile.PBAP_CLIENT)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting Pbap");
            mPbapClientService.setConnectionPolicy(
                    device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mHearingAidService != null
                && isProfileSupported(device, BluetoothProfile.HEARING_AID)) {
            if (mHapClientService != null
                    && isProfileSupported(device, BluetoothProfile.HAP_CLIENT)) {
                Log.i(
                        TAG,
                        "connectAllSupportedProfiles: Hearing Access Client Profile is enabled at"
                                + " the same time with Hearing Aid Profile, ignore Hearing Aid"
                                + " Profile");
            } else {
                Log.i(TAG, "connectAllSupportedProfiles: Connecting Hearing Aid Profile");
                mHearingAidService.setConnectionPolicy(
                        device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
                numProfilesConnected++;
            }
        }
        if (mHapClientService != null && isProfileSupported(device, BluetoothProfile.HAP_CLIENT)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting Hearing Access Client Profile");
            mHapClientService.setConnectionPolicy(
                    device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mVolumeControlService != null
                && isProfileSupported(device, BluetoothProfile.VOLUME_CONTROL)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting Volume Control Profile");
            mVolumeControlService.setConnectionPolicy(
                    device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mCsipSetCoordinatorService != null
                && isProfileSupported(device, BluetoothProfile.CSIP_SET_COORDINATOR)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting Coordinated Set Profile");
            mCsipSetCoordinatorService.setConnectionPolicy(
                    device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mLeAudioService != null && isProfileSupported(device, BluetoothProfile.LE_AUDIO)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting LeAudio profile (BAP)");
            mLeAudioService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mBassClientService != null
                && isProfileSupported(device, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting LE Broadcast Assistant Profile");
            mBassClientService.setConnectionPolicy(
                    device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }
        if (mBatteryService != null && isProfileSupported(device, BluetoothProfile.BATTERY)) {
            Log.i(TAG, "connectAllSupportedProfiles: Connecting Battery Service");
            mBatteryService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            numProfilesConnected++;
        }

        Log.i(
                TAG,
                "connectAllSupportedProfiles: Number of Profiles Connected: "
                        + numProfilesConnected);
    }

    /**
     * Disconnects all enabled and supported bluetooth profiles between the local and remote device
     *
     * @param device is the remote device with which to disconnect these profiles
     * @return true if all profiles successfully disconnected, false if an error occurred
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public int disconnectAllEnabledProfiles(BluetoothDevice device) {
        if (!profileServicesRunning()) {
            Log.e(TAG, "disconnectAllEnabledProfiles: Not all profile services bound");
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }

        if (mA2dpService != null
                && (mA2dpService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mA2dpService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting A2dp");
            mA2dpService.disconnect(device);
        }
        if (mA2dpSinkService != null
                && (mA2dpSinkService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mA2dpSinkService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting A2dp Sink");
            mA2dpSinkService.disconnect(device);
        }
        if (mHeadsetService != null
                && (mHeadsetService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mHeadsetService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Headset Profile");
            mHeadsetService.disconnect(device);
        }
        if (mHeadsetClientService != null
                && (mHeadsetClientService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTED
                        || mHeadsetClientService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting HFP");
            mHeadsetClientService.disconnect(device);
        }
        if (mMapClientService != null
                && (mMapClientService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mMapClientService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting MAP Client");
            mMapClientService.disconnect(device);
        }
        if (mMapService != null
                && (mMapService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mMapService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting MAP");
            mMapService.disconnect(device);
        }
        if (mHidDeviceService != null
                && (mHidDeviceService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mHidDeviceService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Hid Device Profile");
            mHidDeviceService.disconnect(device);
        }
        if (mHidHostService != null
                && (mHidHostService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mHidHostService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Hid Host Profile");
            mHidHostService.disconnect(device);
        }
        if (mPanService != null
                && (mPanService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mPanService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Pan Profile");
            mPanService.disconnect(device);
        }
        if (mPbapClientService != null
                && (mPbapClientService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTED
                        || mPbapClientService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Pbap Client");
            mPbapClientService.disconnect(device);
        }
        if (mPbapService != null
                && (mPbapService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mPbapService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Pbap Server");
            mPbapService.disconnect(device);
        }
        if (mHearingAidService != null
                && (mHearingAidService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTED
                        || mHearingAidService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Hearing Aid Profile");
            mHearingAidService.disconnect(device);
        }
        if (mHapClientService != null
                && (mHapClientService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mHapClientService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Hearing Access Profile Client");
            mHapClientService.disconnect(device);
        }
        if (mVolumeControlService != null
                && (mVolumeControlService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTED
                        || mVolumeControlService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Volume Control Profile");
            mVolumeControlService.disconnect(device);
        }
        if (mSapService != null
                && (mSapService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mSapService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Sap Profile");
            mSapService.disconnect(device);
        }
        if (mCsipSetCoordinatorService != null
                && (mCsipSetCoordinatorService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTED
                        || mCsipSetCoordinatorService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting Coordinater Set Profile");
            mCsipSetCoordinatorService.disconnect(device);
        }
        if (mLeAudioService != null
                && (mLeAudioService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mLeAudioService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting LeAudio profile (BAP)");
            mLeAudioService.disconnect(device);
        }
        if (mBassClientService != null
                && (mBassClientService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTED
                        || mBassClientService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(
                    TAG,
                    "disconnectAllEnabledProfiles: Disconnecting "
                            + "LE Broadcast Assistant Profile");
            mBassClientService.disconnect(device);
        }
        if (mBatteryService != null
                && (mBatteryService.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        || mBatteryService.getConnectionState(device)
                                == BluetoothProfile.STATE_CONNECTING)) {
            Log.i(TAG, "disconnectAllEnabledProfiles: Disconnecting " + "Battery Service");
            mBatteryService.disconnect(device);
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    /**
     * Same as API method {@link BluetoothDevice#getName()}
     *
     * @param device remote device of interest
     * @return remote device name
     */
    public String getRemoteName(BluetoothDevice device) {
        if (mRemoteDevices == null) {
            return null;
        }
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return null;
        }
        return deviceProp.getName();
    }

    /**
     * Get UUIDs for service supported by a remote device
     *
     * @param device the remote device that we want to get UUIDs from
     * @return the uuids of the remote device
     */
    public ParcelUuid[] getRemoteUuids(BluetoothDevice device) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return null;
        }
        return deviceProp.getUuids();
    }

    public Set<IBluetoothConnectionCallback> getBluetoothConnectionCallbacks() {
        return mBluetoothConnectionCallbacks;
    }

    /**
     * Converts HCI disconnect reasons to Android disconnect reasons.
     *
     * <p>The HCI Error Codes used for ACL disconnect reasons propagated up from native code were
     * copied from: packages/modules/Bluetooth/system/stack/include/hci_error_code.h
     *
     * <p>These error codes are specified and described in Bluetooth Core Spec v5.1, Vol 2, Part D.
     *
     * @param hciReason is the raw HCI disconnect reason from native.
     * @return the Android disconnect reason for apps.
     */
    static @BluetoothAdapter.BluetoothConnectionCallback.DisconnectReason int
            hciToAndroidDisconnectReason(int hciReason) {
        switch (hciReason) {
            case /*HCI_SUCCESS*/ 0x00:
            case /*HCI_ERR_UNSPECIFIED*/ 0x1F:
            case /*HCI_ERR_UNDEFINED*/ 0xff:
                return BluetoothStatusCodes.ERROR_UNKNOWN;
            case /*HCI_ERR_ILLEGAL_COMMAND*/ 0x01:
            case /*HCI_ERR_NO_CONNECTION*/ 0x02:
            case /*HCI_ERR_HW_FAILURE*/ 0x03:
            case /*HCI_ERR_DIFF_TRANSACTION_COLLISION*/ 0x2A:
            case /*HCI_ERR_ROLE_SWITCH_PENDING*/ 0x32:
            case /*HCI_ERR_ROLE_SWITCH_FAILED*/ 0x35:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL;
            case /*HCI_ERR_PAGE_TIMEOUT*/ 0x04:
            case /*HCI_ERR_CONNECTION_TOUT*/ 0x08:
            case /*HCI_ERR_HOST_TIMEOUT*/ 0x10:
            case /*HCI_ERR_LMP_RESPONSE_TIMEOUT*/ 0x22:
            case /*HCI_ERR_ADVERTISING_TIMEOUT*/ 0x3C:
            case /*HCI_ERR_CONN_FAILED_ESTABLISHMENT*/ 0x3E:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_TIMEOUT;
            case /*HCI_ERR_AUTH_FAILURE*/ 0x05:
            case /*HCI_ERR_KEY_MISSING*/ 0x06:
            case /*HCI_ERR_HOST_REJECT_SECURITY*/ 0x0E:
            case /*HCI_ERR_REPEATED_ATTEMPTS*/ 0x17:
            case /*HCI_ERR_PAIRING_NOT_ALLOWED*/ 0x18:
            case /*HCI_ERR_ENCRY_MODE_NOT_ACCEPTABLE*/ 0x25:
            case /*HCI_ERR_UNIT_KEY_USED*/ 0x26:
            case /*HCI_ERR_PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED*/ 0x29:
            case /*HCI_ERR_INSUFFCIENT_SECURITY*/ 0x2F:
            case /*HCI_ERR_HOST_BUSY_PAIRING*/ 0x38:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SECURITY;
            case /*HCI_ERR_MEMORY_FULL*/ 0x07:
            case /*HCI_ERR_MAX_NUM_OF_CONNECTIONS*/ 0x09:
            case /*HCI_ERR_MAX_NUM_OF_SCOS*/ 0x0A:
            case /*HCI_ERR_COMMAND_DISALLOWED*/ 0x0C:
            case /*HCI_ERR_HOST_REJECT_RESOURCES*/ 0x0D:
            case /*HCI_ERR_LIMIT_REACHED*/ 0x43:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_RESOURCE_LIMIT_REACHED;
            case /*HCI_ERR_CONNECTION_EXISTS*/ 0x0B:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_CONNECTION_ALREADY_EXISTS;
            case /*HCI_ERR_HOST_REJECT_DEVICE*/ 0x0F:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_SYSTEM_POLICY;
            case /*HCI_ERR_ILLEGAL_PARAMETER_FMT*/ 0x12:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_BAD_PARAMETERS;
            case /*HCI_ERR_PEER_USER*/ 0x13:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE_REQUEST;
            case /*HCI_ERR_REMOTE_POWER_OFF*/ 0x15:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE_REQUEST;
            case /*HCI_ERR_CONN_CAUSE_LOCAL_HOST*/ 0x16:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_LOCAL_REQUEST;
            case /*HCI_ERR_UNSUPPORTED_REM_FEATURE*/ 0x1A:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_REMOTE;
            case /*HCI_ERR_UNACCEPT_CONN_INTERVAL*/ 0x3B:
                return BluetoothStatusCodes.ERROR_DISCONNECT_REASON_BAD_PARAMETERS;
            default:
                Log.e(TAG, "Invalid HCI disconnect reason: " + hciReason);
                return BluetoothStatusCodes.ERROR_UNKNOWN;
        }
    }

    void logUserBondResponse(BluetoothDevice device, boolean accepted, int event) {
        final long token = Binder.clearCallingIdentity();
        try {
            BluetoothStatsLog.write(
                    BluetoothStatsLog.BLUETOOTH_BOND_STATE_CHANGED,
                    obfuscateAddress(device),
                    0,
                    device.getType(),
                    BluetoothDevice.BOND_BONDING,
                    event,
                    accepted ? 0 : BluetoothDevice.UNBOND_REASON_AUTH_REJECTED);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    int getDeviceAccessFromPrefs(BluetoothDevice device, String prefFile) {
        SharedPreferences prefs = getSharedPreferences(prefFile, Context.MODE_PRIVATE);
        if (!prefs.contains(device.getAddress())) {
            return BluetoothDevice.ACCESS_UNKNOWN;
        }
        return prefs.getBoolean(device.getAddress(), false)
                ? BluetoothDevice.ACCESS_ALLOWED
                : BluetoothDevice.ACCESS_REJECTED;
    }

    void setDeviceAccessFromPrefs(BluetoothDevice device, int value, String prefFile) {
        SharedPreferences pref = getSharedPreferences(prefFile, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        if (value == BluetoothDevice.ACCESS_UNKNOWN) {
            editor.remove(device.getAddress());
        } else {
            editor.putBoolean(device.getAddress(), value == BluetoothDevice.ACCESS_ALLOWED);
        }
        editor.apply();
    }

    public void setPhonebookAccessPermission(BluetoothDevice device, int value) {
        setDeviceAccessFromPrefs(device, value, PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE);
    }

    public void setMessageAccessPermission(BluetoothDevice device, int value) {
        setDeviceAccessFromPrefs(device, value, MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE);
    }

    public void setSimAccessPermission(BluetoothDevice device, int value) {
        setDeviceAccessFromPrefs(device, value, SIM_ACCESS_PERMISSION_PREFERENCE_FILE);
    }

    public boolean isRpaOffloadSupported() {
        return mAdapterProperties.isRpaOffloadSupported();
    }

    public int getNumOfOffloadedIrkSupported() {
        return mAdapterProperties.getNumOfOffloadedIrkSupported();
    }

    public int getNumOfOffloadedScanFilterSupported() {
        return mAdapterProperties.getNumOfOffloadedScanFilterSupported();
    }

    public int getOffloadedScanResultStorage() {
        return mAdapterProperties.getOffloadedScanResultStorage();
    }

    public boolean isLe2MPhySupported() {
        return mAdapterProperties.isLe2MPhySupported();
    }

    public boolean isLeCodedPhySupported() {
        return mAdapterProperties.isLeCodedPhySupported();
    }

    public boolean isLeExtendedAdvertisingSupported() {
        return mAdapterProperties.isLeExtendedAdvertisingSupported();
    }

    public boolean isLePeriodicAdvertisingSupported() {
        return mAdapterProperties.isLePeriodicAdvertisingSupported();
    }

    /**
     * Check if the LE audio broadcast source feature is supported.
     *
     * @return true, if the LE audio broadcast source is supported
     */
    public boolean isLeAudioBroadcastSourceSupported() {
        return mAdapterProperties.isLePeriodicAdvertisingSupported()
                && mAdapterProperties.isLeExtendedAdvertisingSupported()
                && mAdapterProperties.isLeIsochronousBroadcasterSupported();
    }

    /**
     * Check if the LE audio broadcast assistant feature is supported.
     *
     * @return true, if the LE audio broadcast assistant is supported
     */
    public boolean isLeAudioBroadcastAssistantSupported() {
        return mAdapterProperties.isLePeriodicAdvertisingSupported()
                && mAdapterProperties.isLeExtendedAdvertisingSupported()
                && (mAdapterProperties.isLePeriodicAdvertisingSyncTransferSenderSupported()
                        || mAdapterProperties
                                .isLePeriodicAdvertisingSyncTransferRecipientSupported());
    }

    /**
     * Check if the LE channel sounding feature is supported.
     *
     * @return true, if the LE channel sounding is supported
     */
    public boolean isLeChannelSoundingSupported() {
        return mAdapterProperties.isLeChannelSoundingSupported();
    }

    public long getSupportedProfilesBitMask() {
        return Config.getSupportedProfilesBitMask();
    }

    /**
     * Check if the LE audio CIS central feature is supported.
     *
     * @return true, if the LE audio CIS central is supported
     */
    public boolean isLeConnectedIsochronousStreamCentralSupported() {
        return mAdapterProperties.isLeConnectedIsochronousStreamCentralSupported();
    }

    public int getLeMaximumAdvertisingDataLength() {
        return mAdapterProperties.getLeMaximumAdvertisingDataLength();
    }

    /**
     * Get the maximum number of connected audio devices.
     *
     * @return the maximum number of connected audio devices
     */
    public int getMaxConnectedAudioDevices() {
        return mAdapterProperties.getMaxConnectedAudioDevices();
    }

    /**
     * Check whether A2DP offload is enabled.
     *
     * @return true if A2DP offload is enabled
     */
    public boolean isA2dpOffloadEnabled() {
        return mAdapterProperties.isA2dpOffloadEnabled();
    }

    /** Register a bluetooth state callback */
    public void registerBluetoothStateCallback(Executor executor, BluetoothStateCallback callback) {
        mLocalCallbacks.put(callback, executor);
    }

    /** Unregister a bluetooth state callback */
    public void unregisterBluetoothStateCallback(BluetoothStateCallback callback) {
        mLocalCallbacks.remove(callback);
    }

    @VisibleForTesting
    void registerRemoteCallback(IBluetoothCallback callback) {
        mRemoteCallbacks.register(callback);
    }

    @VisibleForTesting
    void unregisterRemoteCallback(IBluetoothCallback callback) {
        mRemoteCallbacks.unregister(callback);
    }

    @VisibleForTesting
    void startBrEdr() {
        mAdapterStateMachine.sendMessage(AdapterState.USER_TURN_ON);
    }

    @VisibleForTesting
    void stopBle() {
        mAdapterStateMachine.sendMessage(AdapterState.BLE_TURN_OFF);
    }

    @VisibleForTesting
    boolean factoryReset() {
        mDatabaseManager.factoryReset();

        if (mBluetoothKeystoreService != null) {
            mBluetoothKeystoreService.factoryReset();
        }

        if (mBtCompanionManager != null) {
            mBtCompanionManager.factoryReset();
        }

        return mNativeInterface.factoryReset();
    }

    @VisibleForTesting
    int getScanMode() {
        return mScanMode;
    }

    private boolean setScanMode(int mode, String from) {
        mScanModeChanges.add(Utils.getLocalTimeString() + " (" + from + ") " + dumpScanMode(mode));
        if (!mNativeInterface.setScanMode(convertScanModeToHal(mode))) {
            return false;
        }
        mScanMode = mode;
        Intent intent =
                new Intent(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
                        .putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, mScanMode)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_SCAN, Utils.getTempBroadcastOptions().toBundle());
        return true;
    }

    @VisibleForTesting
    BluetoothActivityEnergyInfo reportActivityInfo() {
        if (mAdapterProperties.getState() != BluetoothAdapter.STATE_ON
                || !mAdapterProperties.isActivityAndEnergyReportingSupported()) {
            return null;
        }

        // Pull the data. The callback will notify mEnergyInfoLock.
        mNativeInterface.readEnergyInfo();

        synchronized (mEnergyInfoLock) {
            long now = System.currentTimeMillis();
            final long deadline = now + CONTROLLER_ENERGY_UPDATE_TIMEOUT_MILLIS;
            while (now < deadline) {
                try {
                    mEnergyInfoLock.wait(deadline - now);
                    break;
                } catch (InterruptedException e) {
                    now = System.currentTimeMillis();
                }
            }

            final BluetoothActivityEnergyInfo info =
                    new BluetoothActivityEnergyInfo(
                            SystemClock.elapsedRealtime(),
                            mStackReportedState,
                            mTxTimeTotalMs,
                            mRxTimeTotalMs,
                            mIdleTimeTotalMs,
                            mEnergyUsedTotalVoltAmpSecMicro);

            // Copy the traffic objects whose byte counts are > 0
            final List<UidTraffic> result = new ArrayList<>();
            for (int i = 0; i < mUidTraffic.size(); i++) {
                final UidTraffic traffic = mUidTraffic.valueAt(i);
                if (traffic.getTxBytes() != 0 || traffic.getRxBytes() != 0) {
                    result.add(traffic.clone());
                }
            }

            info.setUidTraffic(result);

            return info;
        }
    }

    public int getTotalNumOfTrackableAdvertisements() {
        return mAdapterProperties.getTotalNumOfTrackableAdvertisements();
    }

    /**
     * Return if offloaded TDS filter is supported.
     *
     * @return {@code BluetoothStatusCodes.FEATURE_SUPPORTED} if supported
     */
    public int getOffloadedTransportDiscoveryDataScanSupported() {
        if (mAdapterProperties.isOffloadedTransportDiscoveryDataScanSupported()) {
            return BluetoothStatusCodes.FEATURE_SUPPORTED;
        }
        return BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
    }

    IBinder getBluetoothGatt() {
        return mGattService == null ? null : mGattService.getBinder();
    }

    public GattService getBluetoothGattService() {
        return mGattService;
    }

    IBinder getBluetoothScan() {
        return mScanController == null ? null : mScanController.getBinder();
    }

    public ScanController getBluetoothScanController() {
        return mScanController;
    }

    void unregAllGattClient(AttributionSource source) {
        if (mGattService != null) {
            mGattService.unregAll(source);
        }
    }

    IBinder getProfile(int profileId) {
        if (getState() == BluetoothAdapter.STATE_TURNING_ON) {
            return null;
        }

        // LE_AUDIO_BROADCAST is not associated with a service and use LE_AUDIO's Binder
        if (profileId == BluetoothProfile.LE_AUDIO_BROADCAST) {
            profileId = BluetoothProfile.LE_AUDIO;
        }

        ProfileService profile = mStartedProfiles.get(profileId);
        if (profile != null) {
            return profile.getBinder();
        } else {
            return null;
        }
    }

    boolean isMediaProfileConnected() {
        if (mA2dpService != null && mA2dpService.getConnectedDevices().size() > 0) {
            Log.d(TAG, "isMediaProfileConnected. A2dp is connected");
            return true;
        } else if (mHearingAidService != null
                && mHearingAidService.getConnectedDevices().size() > 0) {
            Log.d(TAG, "isMediaProfileConnected. HearingAid is connected");
            return true;
        } else if (mLeAudioService != null && mLeAudioService.getConnectedDevices().size() > 0) {
            Log.d(TAG, "isMediaProfileConnected. LeAudio is connected");
            return true;
        } else {
            Log.d(
                    TAG,
                    "isMediaProfileConnected: no Media connected."
                            + (" A2dp=" + mA2dpService)
                            + (" HearingAid=" + mHearingAidService)
                            + (" LeAudio=" + mLeAudioService));
            return false;
        }
    }

    /** Update PhonePolicy when new {@link BluetoothDevice} creates an ACL connection. */
    public void updatePhonePolicyOnAclConnect(BluetoothDevice device) {
        if (mPhonePolicy != null) {
            mPhonePolicy.handleAclConnected(device);
        }
    }

    /**
     * Notify {@link BluetoothProfile} when ACL connection disconnects from {@link BluetoothDevice}
     * for a given {@code transport}.
     */
    public void notifyAclDisconnected(BluetoothDevice device, int transport) {
        if (mMapService != null && mMapService.isAvailable()) {
            mMapService.aclDisconnected(device);
        }
        if (mMapClientService != null && mMapClientService.isAvailable()) {
            mMapClientService.aclDisconnected(device, transport);
        }
        if (mSapService != null && mSapService.isAvailable()) {
            mSapService.aclDisconnected(device);
        }
        if (mPbapClientService != null && mPbapClientService.isAvailable()) {
            mPbapClientService.aclDisconnected(device, transport);
        }
    }

    /**
     * Notify GATT of a Bluetooth profile's connection state change for a given {@link
     * BluetoothProfile}.
     */
    public void notifyProfileConnectionStateChangeToGatt(int profile, int fromState, int toState) {
        if (mGattService == null) {
            Log.w(TAG, "GATT Service is not running!");
            return;
        }
        if (Flags.scanManagerRefactor()) {
            mScanController.notifyProfileConnectionStateChange(profile, fromState, toState);
        } else {
            mGattService.notifyProfileConnectionStateChange(profile, fromState, toState);
        }
    }

    /**
     * Handle Bluetooth app state when connection state changes for a given {@code profile}.
     *
     * <p>Currently this function is limited to handling Phone policy but the eventual goal is to
     * move all connection logic here.
     */
    public void handleProfileConnectionStateChange(
            int profile, BluetoothDevice device, int fromState, int toState) {
        if (mPhonePolicy != null) {
            mPhonePolicy.profileConnectionStateChanged(profile, device, fromState, toState);
        }
    }

    /** Handle Bluetooth app state when active device changes for a given {@code profile}. */
    public void handleActiveDeviceChange(int profile, BluetoothDevice device) {
        mActiveDeviceManager.profileActiveDeviceChanged(profile, device);
        mSilenceDeviceManager.profileActiveDeviceChanged(profile, device);
        if (mPhonePolicy != null) {
            mPhonePolicy.profileActiveDeviceChanged(profile, device);
        }
    }

    /** Notify MAP and Pbap when a new sdp search record is found. */
    public void sendSdpSearchRecord(
            BluetoothDevice device, int status, Parcelable record, ParcelUuid uuid) {
        if (mMapService != null && mMapService.isAvailable()) {
            mMapService.receiveSdpSearchRecord(status, record, uuid);
        }
        if (mMapClientService != null && mMapClientService.isAvailable()) {
            mMapClientService.receiveSdpSearchRecord(device, status, record, uuid);
        }
        if (mPbapClientService != null && mPbapClientService.isAvailable()) {
            mPbapClientService.receiveSdpSearchRecord(device, status, record, uuid);
        }
    }

    /** Handle Bluetooth profiles when bond state changes with a {@link BluetoothDevice} */
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (mA2dpService != null && mA2dpService.isAvailable()) {
            mA2dpService.handleBondStateChanged(device, fromState, toState);
        }
        if (mHeadsetService != null && mHeadsetService.isAvailable()) {
            mHeadsetService.handleBondStateChanged(device, fromState, toState);
        }
        if (mLeAudioService != null && mLeAudioService.isAvailable()) {
            mLeAudioService.handleBondStateChanged(device, fromState, toState);
        }
        if (mHearingAidService != null && mHearingAidService.isAvailable()) {
            mHearingAidService.handleBondStateChanged(device, fromState, toState);
        }
        if (mHapClientService != null && mHapClientService.isAvailable()) {
            mHapClientService.handleBondStateChanged(device, fromState, toState);
        }
        if (mBassClientService != null && mBassClientService.isAvailable()) {
            mBassClientService.handleBondStateChanged(device, fromState, toState);
        }
        if (mBatteryService != null && mBatteryService.isAvailable()) {
            mBatteryService.handleBondStateChanged(device, fromState, toState);
        }
        if (mVolumeControlService != null && mVolumeControlService.isAvailable()) {
            mVolumeControlService.handleBondStateChanged(device, fromState, toState);
        }
        if (mPbapService != null && mPbapService.isAvailable()) {
            mPbapService.handleBondStateChanged(device, fromState, toState);
        }
        if (mCsipSetCoordinatorService != null && mCsipSetCoordinatorService.isAvailable()) {
            mCsipSetCoordinatorService.handleBondStateChanged(device, fromState, toState);
        }
        mDatabaseManager.handleBondStateChanged(device, fromState, toState);
    }

    static int convertScanModeToHal(int mode) {
        switch (mode) {
            case SCAN_MODE_NONE:
                return AbstractionLayer.BT_SCAN_MODE_NONE;
            case SCAN_MODE_CONNECTABLE:
                return AbstractionLayer.BT_SCAN_MODE_CONNECTABLE;
            case SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        }
        return -1;
    }

    static int convertScanModeFromHal(int mode) {
        switch (mode) {
            case AbstractionLayer.BT_SCAN_MODE_NONE:
                return SCAN_MODE_NONE;
            case AbstractionLayer.BT_SCAN_MODE_CONNECTABLE:
                return SCAN_MODE_CONNECTABLE;
            case AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        }
        return -1;
    }

    // This function is called from JNI. It allows native code to acquire a single wake lock.
    // If the wake lock is already held, this function returns success. Although this function
    // only supports acquiring a single wake lock at a time right now, it will eventually be
    // extended to allow acquiring an arbitrary number of wake locks. The current interface
    // takes |lockName| as a parameter in anticipation of that implementation.
    boolean acquireWakeLock(String lockName) {
        synchronized (this) {
            if (mWakeLock == null) {
                mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);
            }

            if (!mWakeLock.isHeld()) {
                mWakeLock.acquire();
            }
        }
        return true;
    }

    // This function is called from JNI. It allows native code to release a wake lock acquired
    // by |acquireWakeLock|. If the wake lock is not held, this function returns failure.
    // Note that the release() call is also invoked by {@link #cleanup()} so a synchronization is
    // needed here. See the comment for |acquireWakeLock| for an explanation of the interface.
    boolean releaseWakeLock(String lockName) {
        synchronized (this) {
            if (mWakeLock == null) {
                Log.e(TAG, "Repeated wake lock release; aborting release: " + lockName);
                return false;
            }

            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
        return true;
    }

    void energyInfoCallbackInternal(
            int status,
            int ctrlState,
            long txTime,
            long rxTime,
            long idleTime,
            long energyUsed,
            UidTraffic[] data) {
        // Energy is product of mA, V and ms. If the chipset doesn't
        // report it, we have to compute it from time
        if (energyUsed == 0) {
            try {
                final long txMah = Math.multiplyExact(txTime, getTxCurrentMa());
                final long rxMah = Math.multiplyExact(rxTime, getRxCurrentMa());
                final long idleMah = Math.multiplyExact(idleTime, getIdleCurrentMa());
                energyUsed =
                        (long)
                                (Math.addExact(Math.addExact(txMah, rxMah), idleMah)
                                        * getOperatingVolt());
            } catch (ArithmeticException e) {
                Log.wtf(TAG, "overflow in bluetooth energy callback", e);
                // Energy is already 0 if the exception was thrown.
            }
        }

        synchronized (mEnergyInfoLock) {
            mStackReportedState = ctrlState;
            long totalTxTimeMs;
            long totalRxTimeMs;
            long totalIdleTimeMs;
            long totalEnergy;
            try {
                totalTxTimeMs = Math.addExact(mTxTimeTotalMs, txTime);
                totalRxTimeMs = Math.addExact(mRxTimeTotalMs, rxTime);
                totalIdleTimeMs = Math.addExact(mIdleTimeTotalMs, idleTime);
                totalEnergy = Math.addExact(mEnergyUsedTotalVoltAmpSecMicro, energyUsed);
            } catch (ArithmeticException e) {
                // This could be because we accumulated a lot of time, or we got a very strange
                // value from the controller (more likely). Discard this data.
                Log.wtf(TAG, "overflow in bluetooth energy callback", e);
                totalTxTimeMs = mTxTimeTotalMs;
                totalRxTimeMs = mRxTimeTotalMs;
                totalIdleTimeMs = mIdleTimeTotalMs;
                totalEnergy = mEnergyUsedTotalVoltAmpSecMicro;
            }

            mTxTimeTotalMs = totalTxTimeMs;
            mRxTimeTotalMs = totalRxTimeMs;
            mIdleTimeTotalMs = totalIdleTimeMs;
            mEnergyUsedTotalVoltAmpSecMicro = totalEnergy;

            for (UidTraffic traffic : data) {
                UidTraffic existingTraffic = mUidTraffic.get(traffic.getUid());
                if (existingTraffic == null) {
                    mUidTraffic.put(traffic.getUid(), traffic);
                } else {
                    existingTraffic.addRxBytes(traffic.getRxBytes());
                    existingTraffic.addTxBytes(traffic.getTxBytes());
                }
            }
            mEnergyInfoLock.notifyAll();
        }
    }

    void energyInfoCallback(
            int status,
            int ctrlState,
            long txTime,
            long rxTime,
            long idleTime,
            long energyUsed,
            UidTraffic[] data) {
        if (Flags.btSystemContextReport()) {
            energyInfoCallbackInternal(
                    status, ctrlState, txTime, rxTime, idleTime, energyUsed, data);
        } else if (ctrlState >= BluetoothActivityEnergyInfo.BT_STACK_STATE_INVALID
                && ctrlState <= BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_IDLE) {
            energyInfoCallbackInternal(
                    status, ctrlState, txTime, rxTime, idleTime, energyUsed, data);
        }
        Log.v(
                TAG,
                "energyInfoCallback()"
                        + (" status = " + status)
                        + (" txTime = " + txTime)
                        + (" rxTime = " + rxTime)
                        + (" idleTime = " + idleTime)
                        + (" energyUsed = " + energyUsed)
                        + (" ctrlState = " + String.format("0x%08x", ctrlState))
                        + (" traffic = " + Arrays.toString(data)));
    }

    /** Update metadata change to registered listeners */
    @VisibleForTesting
    public void metadataChanged(String address, int key, byte[] value) {
        BluetoothDevice device = mRemoteDevices.getDevice(Utils.getBytesFromAddress(address));

        // pass just interesting metadata to native, to reduce spam
        if (key == BluetoothDevice.METADATA_LE_AUDIO) {
            mNativeInterface.metadataChanged(Utils.getBytesFromAddress(address), key, value);
        }

        if (mMetadataListeners.containsKey(device)) {
            List<IBluetoothMetadataListener> list = mMetadataListeners.get(device);
            for (IBluetoothMetadataListener listener : list) {
                try {
                    listener.onMetadataChanged(device, key, value);
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException when onMetadataChanged");
                }
            }
        }
    }

    private int getIdleCurrentMa() {
        return BluetoothProperties.getHardwareIdleCurrentMa().orElse(0);
    }

    private int getTxCurrentMa() {
        return BluetoothProperties.getHardwareTxCurrentMa().orElse(0);
    }

    private int getRxCurrentMa() {
        return BluetoothProperties.getHardwareRxCurrentMa().orElse(0);
    }

    private double getOperatingVolt() {
        return BluetoothProperties.getHardwareOperatingVoltageMv().orElse(0) / 1000.0;
    }

    public RemoteDevices getRemoteDevices() {
        return mRemoteDevices;
    }

    private String dumpScanMode(int scanMode) {
        switch (scanMode) {
            case SCAN_MODE_NONE:
                return "SCAN_MODE_NONE";
            case SCAN_MODE_CONNECTABLE:
                return "SCAN_MODE_CONNECTABLE";
            case SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return "SCAN_MODE_CONNECTABLE_DISCOVERABLE";
            default:
                return "Unknown Scan Mode " + scanMode;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (args.length == 0) {
            writer.println("Skipping dump in APP SERVICES, see bluetooth_manager section.");
            writer.println("Use --print argument for dumpsys direct from AdapterService.");
            return;
        }

        if ("set-test-mode".equals(args[0])) {
            final boolean testModeEnabled = "enabled".equalsIgnoreCase(args[1]);
            for (ProfileService profile : mRunningProfiles) {
                profile.setTestModeEnabled(testModeEnabled);
            }
            mTestModeEnabled = testModeEnabled;
            return;
        }

        Log.v(TAG, "dumpsys arguments, check for protobuf output: " + TextUtils.join(" ", args));
        if (args[0].equals("--proto-bin")) {
            dumpMetrics(fd);
            return;
        }

        writer.println();
        mAdapterProperties.dump(fd, writer, args);

        writer.println("ScanMode: " + dumpScanMode(getScanMode()));
        writer.println("Scan Mode Changes:");
        for (String log : mScanModeChanges) {
            writer.println("    " + log);
        }
        writer.println();
        writer.println("sSnoopLogSettingAtEnable = " + sSnoopLogSettingAtEnable);
        writer.println("sDefaultSnoopLogSettingAtEnable = " + sDefaultSnoopLogSettingAtEnable);

        writer.println();
        writer.println("Enabled Profile Services:");
        for (int profileId : Config.getSupportedProfiles()) {
            writer.println("  " + BluetoothProfile.getProfileName(profileId));
        }
        writer.println();

        mAdapterStateMachine.dump(fd, writer, args);

        StringBuilder sb = new StringBuilder();
        for (ProfileService profile : mRegisteredProfiles) {
            profile.dump(sb);
        }
        mSilenceDeviceManager.dump(fd, writer, args);
        mDatabaseManager.dump(writer);

        writer.write(sb.toString());
        writer.flush();

        final int currentState = mAdapterProperties.getState();
        if (currentState == BluetoothAdapter.STATE_OFF
                || currentState == BluetoothAdapter.STATE_BLE_TURNING_ON
                || currentState == BluetoothAdapter.STATE_TURNING_OFF
                || currentState == BluetoothAdapter.STATE_BLE_TURNING_OFF) {
            writer.println();
            writer.println(
                    "Impossible to dump native stack. state="
                            + BluetoothAdapter.nameForState(currentState));
            writer.println();
        } else {
            mNativeInterface.dump(fd, args);
        }
    }

    private void dumpMetrics(FileDescriptor fd) {
        BluetoothMetricsProto.BluetoothLog.Builder metricsBuilder =
                BluetoothMetricsProto.BluetoothLog.newBuilder();
        byte[] nativeMetricsBytes = mNativeInterface.dumpMetrics();
        Log.d(TAG, "dumpMetrics: native metrics size is " + nativeMetricsBytes.length);
        if (nativeMetricsBytes.length > 0) {
            try {
                metricsBuilder.mergeFrom(nativeMetricsBytes);
            } catch (InvalidProtocolBufferException ex) {
                Log.w(TAG, "dumpMetrics: problem parsing metrics protobuf, " + ex.getMessage());
                return;
            }
        }
        metricsBuilder.setNumBondedDevices(getBondedDevices().length);
        MetricsLogger.dumpProto(metricsBuilder);
        for (ProfileService profile : mRegisteredProfiles) {
            profile.dumpProto(metricsBuilder);
        }
        byte[] metricsBytes = Base64.encode(metricsBuilder.build().toByteArray(), Base64.DEFAULT);
        Log.d(TAG, "dumpMetrics: combined metrics size is " + metricsBytes.length);
        try (FileOutputStream protoOut = new FileOutputStream(fd)) {
            protoOut.write(metricsBytes);
        } catch (IOException e) {
            Log.e(TAG, "dumpMetrics: error writing combined protobuf to fd, " + e.getMessage());
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void enforceBluetoothPrivilegedPermissionIfNeeded(
            OobData remoteP192Data, OobData remoteP256Data) {
        if (remoteP192Data != null || remoteP256Data != null) {
            enforceBluetoothPrivilegedPermission(this);
        }
    }

    @RequiresPermission(android.Manifest.permission.READ_DEVICE_CONFIG)
    private String[] getInitFlags() {
        final DeviceConfig.Properties properties =
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_BLUETOOTH);
        ArrayList<String> initFlags = new ArrayList<>();
        for (String property : properties.getKeyset()) {
            if (property.startsWith("INIT_")) {
                initFlags.add(
                        String.format("%s=%s", property, properties.getString(property, null)));
            }
        }
        return initFlags.toArray(new String[0]);
    }

    private final Object mDeviceConfigLock = new Object();

    /**
     * Predicate that can be applied to names to determine if a device is well-known to be used for
     * physical location.
     */
    @GuardedBy("mDeviceConfigLock")
    private Predicate<String> mLocationDenylistName = (v) -> false;

    /**
     * Predicate that can be applied to MAC addresses to determine if a device is well-known to be
     * used for physical location.
     */
    @GuardedBy("mDeviceConfigLock")
    private Predicate<byte[]> mLocationDenylistMac = (v) -> false;

    /**
     * Predicate that can be applied to Advertising Data payloads to determine if a device is
     * well-known to be used for physical location.
     */
    @GuardedBy("mDeviceConfigLock")
    private Predicate<byte[]> mLocationDenylistAdvertisingData = (v) -> false;

    @GuardedBy("mDeviceConfigLock")
    private int mScanQuotaCount = DeviceConfigListener.DEFAULT_SCAN_QUOTA_COUNT;

    @GuardedBy("mDeviceConfigLock")
    private long mScanQuotaWindowMillis = DeviceConfigListener.DEFAULT_SCAN_QUOTA_WINDOW_MILLIS;

    @GuardedBy("mDeviceConfigLock")
    private long mScanTimeoutMillis = DeviceConfigListener.DEFAULT_SCAN_TIMEOUT_MILLIS;

    @GuardedBy("mDeviceConfigLock")
    private int mScanUpgradeDurationMillis =
            DeviceConfigListener.DEFAULT_SCAN_UPGRADE_DURATION_MILLIS;

    @GuardedBy("mDeviceConfigLock")
    private int mScanDowngradeDurationMillis =
            DeviceConfigListener.DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING_MILLIS;

    @GuardedBy("mDeviceConfigLock")
    private int mScreenOffLowPowerWindowMillis =
            ScanManager.SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW_MS;

    @GuardedBy("mDeviceConfigLock")
    private int mScreenOffLowPowerIntervalMillis =
            ScanManager.SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL_MS;

    @GuardedBy("mDeviceConfigLock")
    private int mScreenOffBalancedWindowMillis =
            ScanManager.SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW_MS;

    @GuardedBy("mDeviceConfigLock")
    private int mScreenOffBalancedIntervalMillis =
            ScanManager.SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL_MS;

    @GuardedBy("mDeviceConfigLock")
    private String mLeAudioAllowList;

    public @NonNull Predicate<String> getLocationDenylistName() {
        synchronized (mDeviceConfigLock) {
            return mLocationDenylistName;
        }
    }

    public @NonNull Predicate<byte[]> getLocationDenylistMac() {
        synchronized (mDeviceConfigLock) {
            return mLocationDenylistMac;
        }
    }

    public @NonNull Predicate<byte[]> getLocationDenylistAdvertisingData() {
        synchronized (mDeviceConfigLock) {
            return mLocationDenylistAdvertisingData;
        }
    }

    /** Returns scan quota count. */
    public int getScanQuotaCount() {
        synchronized (mDeviceConfigLock) {
            return mScanQuotaCount;
        }
    }

    /** Returns scan quota window in millis. */
    public long getScanQuotaWindowMillis() {
        synchronized (mDeviceConfigLock) {
            return mScanQuotaWindowMillis;
        }
    }

    /** Returns scan timeout in millis. */
    public long getScanTimeoutMillis() {
        synchronized (mDeviceConfigLock) {
            return mScanTimeoutMillis;
        }
    }

    /** Returns scan upgrade duration in millis. */
    public long getScanUpgradeDurationMillis() {
        synchronized (mDeviceConfigLock) {
            return mScanUpgradeDurationMillis;
        }
    }

    /** Returns scan downgrade duration in millis. */
    public long getScanDowngradeDurationMillis() {
        synchronized (mDeviceConfigLock) {
            return mScanDowngradeDurationMillis;
        }
    }

    /** Returns SCREEN_OFF_BALANCED scan window in millis. */
    public int getScreenOffBalancedWindowMillis() {
        synchronized (mDeviceConfigLock) {
            return mScreenOffBalancedWindowMillis;
        }
    }

    /** Returns SCREEN_OFF_BALANCED scan interval in millis. */
    public int getScreenOffBalancedIntervalMillis() {
        synchronized (mDeviceConfigLock) {
            return mScreenOffBalancedIntervalMillis;
        }
    }

    /** Returns SCREEN_OFF low power scan window in millis. */
    public int getScreenOffLowPowerWindowMillis() {
        synchronized (mDeviceConfigLock) {
            return mScreenOffLowPowerWindowMillis;
        }
    }

    /** Returns SCREEN_OFF low power scan interval in millis. */
    public int getScreenOffLowPowerIntervalMillis() {
        synchronized (mDeviceConfigLock) {
            return mScreenOffLowPowerIntervalMillis;
        }
    }

    private class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        private static final String LOCATION_DENYLIST_NAME = "location_denylist_name";
        private static final String LOCATION_DENYLIST_MAC = "location_denylist_mac";
        private static final String LOCATION_DENYLIST_ADVERTISING_DATA =
                "location_denylist_advertising_data";
        private static final String SCAN_QUOTA_COUNT = "scan_quota_count";
        private static final String SCAN_QUOTA_WINDOW_MILLIS = "scan_quota_window_millis";
        private static final String SCAN_TIMEOUT_MILLIS = "scan_timeout_millis";
        private static final String SCAN_UPGRADE_DURATION_MILLIS = "scan_upgrade_duration_millis";
        private static final String SCAN_DOWNGRADE_DURATION_MILLIS =
                "scan_downgrade_duration_millis";
        private static final String SCREEN_OFF_LOW_POWER_WINDOW_MILLIS =
                "screen_off_low_power_window_millis";
        private static final String SCREEN_OFF_LOW_POWER_INTERVAL_MILLIS =
                "screen_off_low_power_interval_millis";
        private static final String SCREEN_OFF_BALANCED_WINDOW_MILLIS =
                "screen_off_balanced_window_millis";
        private static final String SCREEN_OFF_BALANCED_INTERVAL_MILLIS =
                "screen_off_balanced_interval_millis";
        private static final String LE_AUDIO_ALLOW_LIST = "le_audio_allow_list";

        /**
         * Default denylist which matches Eddystone (except for Eddystone-E2EE-EID) and iBeacon
         * payloads.
         */
        private static final String DEFAULT_LOCATION_DENYLIST_ADVERTISING_DATA =
                "⊈0016AAFE40/00FFFFFFF0,⊆0016AAFE/00FFFFFF,⊆00FF4C0002/00FFFFFFFF";

        private static final int DEFAULT_SCAN_QUOTA_COUNT = 5;
        private static final long DEFAULT_SCAN_QUOTA_WINDOW_MILLIS = 30 * SECOND_IN_MILLIS;
        private static final long DEFAULT_SCAN_TIMEOUT_MILLIS = 10 * MINUTE_IN_MILLIS;
        private static final int DEFAULT_SCAN_UPGRADE_DURATION_MILLIS = (int) SECOND_IN_MILLIS * 6;
        private static final int DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING_MILLIS =
                (int) SECOND_IN_MILLIS * 6;

        @RequiresPermission(android.Manifest.permission.READ_DEVICE_CONFIG)
        public void start() {
            DeviceConfig.addOnPropertiesChangedListener(
                    DeviceConfig.NAMESPACE_BLUETOOTH, BackgroundThread.getExecutor(), this);
            onPropertiesChanged(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_BLUETOOTH));
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            synchronized (mDeviceConfigLock) {
                final String name = properties.getString(LOCATION_DENYLIST_NAME, null);
                mLocationDenylistName =
                        !TextUtils.isEmpty(name)
                                ? Pattern.compile(name).asPredicate()
                                : (v) -> false;
                mLocationDenylistMac =
                        BytesMatcher.decode(properties.getString(LOCATION_DENYLIST_MAC, null));
                mLocationDenylistAdvertisingData =
                        BytesMatcher.decode(
                                properties.getString(
                                        LOCATION_DENYLIST_ADVERTISING_DATA,
                                        DEFAULT_LOCATION_DENYLIST_ADVERTISING_DATA));
                mScanQuotaCount = properties.getInt(SCAN_QUOTA_COUNT, DEFAULT_SCAN_QUOTA_COUNT);
                mScanQuotaWindowMillis =
                        properties.getLong(
                                SCAN_QUOTA_WINDOW_MILLIS, DEFAULT_SCAN_QUOTA_WINDOW_MILLIS);
                mScanTimeoutMillis =
                        properties.getLong(SCAN_TIMEOUT_MILLIS, DEFAULT_SCAN_TIMEOUT_MILLIS);
                mScanUpgradeDurationMillis =
                        properties.getInt(
                                SCAN_UPGRADE_DURATION_MILLIS, DEFAULT_SCAN_UPGRADE_DURATION_MILLIS);
                mScanDowngradeDurationMillis =
                        properties.getInt(
                                SCAN_DOWNGRADE_DURATION_MILLIS,
                                DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING_MILLIS);
                mScreenOffLowPowerWindowMillis =
                        properties.getInt(
                                SCREEN_OFF_LOW_POWER_WINDOW_MILLIS,
                                ScanManager.SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW_MS);
                mScreenOffLowPowerIntervalMillis =
                        properties.getInt(
                                SCREEN_OFF_LOW_POWER_INTERVAL_MILLIS,
                                ScanManager.SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL_MS);
                mScreenOffBalancedWindowMillis =
                        properties.getInt(
                                SCREEN_OFF_BALANCED_WINDOW_MILLIS,
                                ScanManager.SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW_MS);
                mScreenOffBalancedIntervalMillis =
                        properties.getInt(
                                SCREEN_OFF_BALANCED_INTERVAL_MILLIS,
                                ScanManager.SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL_MS);
                mLeAudioAllowList = properties.getString(LE_AUDIO_ALLOW_LIST, "");

                if (!mLeAudioAllowList.isEmpty()) {
                    List<String> leAudioAllowlistFromDeviceConfig =
                            Arrays.asList(mLeAudioAllowList.split(","));
                    BluetoothProperties.le_audio_allow_list(leAudioAllowlistFromDeviceConfig);
                }

                List<String> leAudioAllowlistProp = BluetoothProperties.le_audio_allow_list();
                if (leAudioAllowlistProp != null && !leAudioAllowlistProp.isEmpty()) {
                    mLeAudioAllowDevices.clear();
                    mLeAudioAllowDevices.addAll(leAudioAllowlistProp);
                }
            }
        }
    }

    /** A callback that will be called when AdapterState is changed */
    public interface BluetoothStateCallback {
        /**
         * Called when the status of bluetooth adapter is changing. {@code prevState} and {@code
         * newState} takes one of following values defined in BluetoothAdapter.java: STATE_OFF,
         * STATE_TURNING_ON, STATE_ON, STATE_TURNING_OFF, STATE_BLE_TURNING_ON, STATE_BLE_ON,
         * STATE_BLE_TURNING_OFF
         *
         * @param prevState the previous Bluetooth state.
         * @param newState the new Bluetooth state.
         */
        void onBluetoothStateChange(int prevState, int newState);
    }

    /**
     * Obfuscate Bluetooth MAC address into a PII free ID string
     *
     * @param device Bluetooth device whose MAC address will be obfuscated
     * @return a byte array that is unique to this MAC address on this device, or empty byte array
     *     when either device is null or obfuscateAddressNative fails
     */
    public byte[] obfuscateAddress(BluetoothDevice device) {
        if (device == null) {
            return new byte[0];
        }
        return mNativeInterface.obfuscateAddress(Utils.getByteAddress(device));
    }

    /**
     * Get dynamic audio buffer size supported type
     *
     * @return support
     *     <p>Possible values are {@link BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_NONE}, {@link
     *     BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_A2DP_OFFLOAD}, {@link
     *     BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_A2DP_SOFTWARE_ENCODING}.
     */
    public int getDynamicBufferSupport() {
        return mAdapterProperties.getDynamicBufferSupport();
    }

    /**
     * Get dynamic audio buffer size
     *
     * @return BufferConstraints
     */
    public BufferConstraints getBufferConstraints() {
        return mAdapterProperties.getBufferConstraints();
    }

    /**
     * Set dynamic audio buffer size
     *
     * @param codec Audio codec
     * @param value buffer millis
     * @return true if the settings is successful, false otherwise
     */
    public boolean setBufferLengthMillis(int codec, int value) {
        return mAdapterProperties.setBufferLengthMillis(codec, value);
    }

    /**
     * Get an incremental id of Bluetooth metrics and log
     *
     * @param device Bluetooth device
     * @return int of id for Bluetooth metrics and logging, 0 if the device is invalid
     */
    public int getMetricId(BluetoothDevice device) {
        if (device == null) {
            return 0;
        }
        return mNativeInterface.getMetricId(Utils.getByteAddress(device));
    }

    public CompanionManager getCompanionManager() {
        return mBtCompanionManager;
    }

    /**
     * Call for the AdapterService receives bond state change
     *
     * @param device Bluetooth device
     * @param state bond state
     */
    public void onBondStateChanged(BluetoothDevice device, int state) {
        if (mBtCompanionManager != null) {
            mBtCompanionManager.onBondStateChanged(device, state);
        }
    }

    /**
     * Get audio policy feature support status
     *
     * @param device Bluetooth device to be checked for audio policy support
     * @return int status of the remote support for audio policy feature
     */
    public int isRequestAudioPolicyAsSinkSupported(BluetoothDevice device) {
        if (mHeadsetClientService != null) {
            return mHeadsetClientService.getAudioPolicyRemoteSupported(device);
        } else {
            Log.e(TAG, "No audio transport connected");
            return BluetoothStatusCodes.FEATURE_NOT_CONFIGURED;
        }
    }

    /**
     * Set audio policy for remote device
     *
     * @param device Bluetooth device to be set policy for
     * @return int result status for requestAudioPolicyAsSink API
     */
    public int requestAudioPolicyAsSink(BluetoothDevice device, BluetoothSinkAudioPolicy policies) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED;
        }

        if (mHeadsetClientService != null) {
            if (isRequestAudioPolicyAsSinkSupported(device)
                    != BluetoothStatusCodes.FEATURE_SUPPORTED) {
                throw new UnsupportedOperationException(
                        "Request Audio Policy As Sink not supported");
            }
            deviceProp.setHfAudioPolicyForRemoteAg(policies);
            mHeadsetClientService.setAudioPolicy(device, policies);
            return BluetoothStatusCodes.SUCCESS;
        } else {
            Log.e(TAG, "HeadsetClient not connected");
            return BluetoothStatusCodes.ERROR_PROFILE_NOT_CONNECTED;
        }
    }

    /**
     * Get audio policy for remote device
     *
     * @param device Bluetooth device to be set policy for
     * @return {@link BluetoothSinkAudioPolicy} policy stored for the device
     */
    public BluetoothSinkAudioPolicy getRequestedAudioPolicyAsSink(BluetoothDevice device) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return null;
        }

        if (mHeadsetClientService != null) {
            return deviceProp.getHfAudioPolicyForRemoteAg();
        } else {
            Log.e(TAG, "HeadsetClient not connected");
            return null;
        }
    }

    /**
     * Allow audio low latency
     *
     * @param allowed true if audio low latency is being allowed
     * @param device device whose audio low latency will be allowed or disallowed
     * @return boolean true if audio low latency is successfully allowed or disallowed
     */
    public boolean allowLowLatencyAudio(boolean allowed, BluetoothDevice device) {
        return mNativeInterface.allowLowLatencyAudio(allowed, Utils.getByteAddress(device));
    }

    /**
     * get remote PBAP PCE version.
     *
     * @param address of remote device
     * @return int value other than 0 if remote PBAP PCE version is found
     */
    public int getRemotePbapPceVersion(String address) {
        return mNativeInterface.getRemotePbapPceVersion(address);
    }

    /**
     * check, if PBAP PSE dynamic version upgrade is enabled.
     *
     * @return true/false.
     */
    public boolean pbapPseDynamicVersionUpgradeIsEnabled() {
        return mNativeInterface.pbapPseDynamicVersionUpgradeIsEnabled();
    }

    /** Sets the battery level of the remote device */
    public void setBatteryLevel(BluetoothDevice device, int batteryLevel, boolean isBas) {
        if (batteryLevel == BATTERY_LEVEL_UNKNOWN) {
            mRemoteDevices.resetBatteryLevel(device, isBas);
        } else {
            mRemoteDevices.updateBatteryLevel(device, batteryLevel, isBas);
        }
    }

    public boolean interopMatchAddr(InteropFeature feature, String address) {
        return mNativeInterface.interopMatchAddr(feature.name(), address);
    }

    public boolean interopMatchName(InteropFeature feature, String name) {
        return mNativeInterface.interopMatchName(feature.name(), name);
    }

    public boolean interopMatchAddrOrName(InteropFeature feature, String address) {
        return mNativeInterface.interopMatchAddrOrName(feature.name(), address);
    }

    public void interopDatabaseAddAddr(InteropFeature feature, String address, int length) {
        mNativeInterface.interopDatabaseAddRemoveAddr(true, feature.name(), address, length);
    }

    public void interopDatabaseRemoveAddr(InteropFeature feature, String address) {
        mNativeInterface.interopDatabaseAddRemoveAddr(false, feature.name(), address, 0);
    }

    public void interopDatabaseAddName(InteropFeature feature, String name) {
        mNativeInterface.interopDatabaseAddRemoveName(true, feature.name(), name);
    }

    public void interopDatabaseRemoveName(InteropFeature feature, String name) {
        mNativeInterface.interopDatabaseAddRemoveName(false, feature.name(), name);
    }

    /**
     * Checks the remote device is in the LE Audio allow list or not.
     *
     * @param device the device to check
     * @return boolean true if the device is in the allow list, false otherwise.
     */
    public boolean isLeAudioAllowed(BluetoothDevice device) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);

        if (deviceProp == null
                || deviceProp.getModelName() == null
                || !mLeAudioAllowDevices.contains(deviceProp.getModelName())) {

            return false;
        }

        return true;
    }

    /**
     * Get type of the remote device
     *
     * @param device the device to check
     * @return int device type
     */
    public int getRemoteType(BluetoothDevice device) {
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        return deviceProp != null
                ? deviceProp.getDeviceType()
                : BluetoothDevice.DEVICE_TYPE_UNKNOWN;
    }

    /**
     * Sends service discovery UUIDs internally within the stack. This is meant to remove internal
     * dependencies on the broadcast {@link BluetoothDevice#ACTION_UUID}.
     *
     * @param device is the remote device whose UUIDs have been discovered
     * @param uuids are the services supported on the remote device
     */
    void sendUuidsInternal(BluetoothDevice device, ParcelUuid[] uuids) {
        if (device == null) {
            Log.w(TAG, "sendUuidsInternal: null device");
            return;
        }
        if (uuids == null) {
            Log.w(TAG, "sendUuidsInternal: uuids is null");
            return;
        }
        Log.i(TAG, "sendUuidsInternal: Received service discovery UUIDs for device " + device);
        for (int i = 0; i < uuids.length; i++) {
            Log.d(TAG, "sendUuidsInternal: index=" + i + " uuid=" + uuids[i]);
        }
        if (mPhonePolicy != null) {
            mPhonePolicy.onUuidsDiscovered(device, uuids);
        }
    }

    // TODO: b/339548431 delete isMock
    // Returns if this is a mock object. This is currently used in testing so that we may not call
    // System.exit() while finalizing the object. Otherwise GC of mock objects unfortunately ends up
    // calling finalize() which in turn calls System.exit() and the process crashes.
    //
    // Mock this in your testing framework to return true to avoid the mentioned behavior. In
    // production this has no effect.
    public boolean isMock() {
        return false;
    }
}
