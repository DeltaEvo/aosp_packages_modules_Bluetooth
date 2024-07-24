/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.bass_client;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;

import static com.android.bluetooth.flags.Flags.leaudioAllowedContextMask;
import static com.android.bluetooth.flags.Flags.leaudioBigDependsOnAudioState;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastAssistantPeripheralEntrustment;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastAudioHandoverPolicies;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastExtractPeriodicScannerFromStateMachine;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastFeatureSupport;
import static com.android.bluetooth.flags.Flags.leaudioBroadcastMonitorSourceSyncStatus;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUtils;
import android.bluetooth.BluetoothUtils.TypeValueEntry;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeBroadcastAssistant;
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.PeriodicAdvertisingCallback;
import android.bluetooth.le.PeriodicAdvertisingReport;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.sysprop.BluetoothProperties;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/** Broadcast Assistant Scan Service */
public class BassClientService extends ProfileService {
    private static final String TAG = BassClientService.class.getSimpleName();
    private static final int MAX_ACTIVE_SYNCED_SOURCES_NUM = 4;
    private static final int MAX_BIS_DISCOVERY_TRIES_NUM = 5;

    private static final int STATUS_LOCAL_STREAM_REQUESTED = 0;
    private static final int STATUS_LOCAL_STREAM_STREAMING = 1;
    private static final int STATUS_LOCAL_STREAM_SUSPENDED = 2;
    private static final int STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE = 3;

    // Do not modify without updating the HAL bt_le_audio.h files.
    // Match up with BroadcastState enum of bt_le_audio.h
    private static final int BROADCAST_STATE_STOPPED = 0;
    private static final int BROADCAST_STATE_CONFIGURING = 1;
    private static final int BROADCAST_STATE_PAUSED = 2;
    private static final int BROADCAST_STATE_STOPPING = 3;
    private static final int BROADCAST_STATE_STREAMING = 4;

    private static final int MESSAGE_SYNC_TIMEOUT = 1;

    /* 1 minute timeout for primary device reconnection in Private Broadcast case */
    private static final int DIALING_OUT_TIMEOUT_MS = 60000;

    // 30 secs timeout for keeping PSYNC active when searching is stopped
    @VisibleForTesting static Duration sSyncActiveTimeout = Duration.ofSeconds(30);

    private static BassClientService sService;

    private final Map<BluetoothDevice, BassClientStateMachine> mStateMachines = new HashMap<>();
    private final Object mSearchScanCallbackLock = new Object();
    private final Map<Integer, ScanResult> mCachedBroadcasts = new HashMap<>();

    private final List<Integer> mActiveSyncedSources = new ArrayList<>();
    private final Map<Integer, PeriodicAdvertisingCallback> mPeriodicAdvCallbacksMap =
            new HashMap<>();
    private final PriorityQueue<SourceSyncRequest> mSourceSyncRequestsQueue =
            new PriorityQueue<>(sSourceSyncRequestComparator);
    private final Map<Integer, Integer> mBisDiscoveryCounterMap = new HashMap<Integer, Integer>();
    private final List<AddSourceData> mPendingSourcesToAdd = new ArrayList<AddSourceData>();

    private final Map<BluetoothDevice, List<Pair<Integer, Object>>> mPendingGroupOp =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, List<Integer>> mGroupManagedSources =
            new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, List<Integer>> mActiveSourceMap = new ConcurrentHashMap<>();
    private final Map<BluetoothDevice, BluetoothLeBroadcastMetadata> mBroadcastMetadataMap =
            new ConcurrentHashMap<>();
    private final LinkedList<BluetoothDevice> mPausedBroadcastSinks = new LinkedList<>();
    private final Deque<AddSourceData> mPendingAddSources = new ArrayDeque<>();
    private final Map<Integer, HashSet<BluetoothDevice>> mLocalBroadcastReceivers =
            new ConcurrentHashMap<>();

    private HandlerThread mStateMachinesThread;
    private HandlerThread mCallbackHandlerThread;
    private AdapterService mAdapterService;
    private DatabaseManager mDatabaseManager;
    private BluetoothAdapter mBluetoothAdapter = null;
    private BluetoothLeScannerWrapper mBluetoothLeScannerWrapper = null;
    private DialingOutTimeoutEvent mDialingOutTimeoutEvent = null;

    /* Caching the PeriodicAdvertisementResult from Broadcast source */
    /* This is stored at service so that each device state machine can access
    and use it as needed. Once the periodic sync in cancelled, this data will bre
    removed to ensure stable data won't used */
    /* syncHandle, broadcastSrcDevice */
    private Map<Integer, BluetoothDevice> mSyncHandleToDeviceMap =
            new HashMap<Integer, BluetoothDevice>();
    /*syncHandle, parsed BaseData data*/
    private Map<Integer, BaseData> mSyncHandleToBaseDataMap = new HashMap<Integer, BaseData>();
    /*syncHandle, broadcast id */
    private Map<Integer, Integer> mSyncHandleToBroadcastIdMap = new HashMap<Integer, Integer>();
    /*bcastSrcDevice, corresponding broadcast id and PeriodicAdvertisementResult*/
    private Map<BluetoothDevice, HashMap<Integer, PeriodicAdvertisementResult>>
            mPeriodicAdvertisementResultMap =
                    new HashMap<BluetoothDevice, HashMap<Integer, PeriodicAdvertisementResult>>();
    private ScanCallback mSearchScanCallback = null;
    private Callbacks mCallbacks;
    private boolean mIsAssistantActive = false;
    private boolean mIsAllowedContextOfActiveGroupModified = false;
    Optional<Integer> mUnicastSourceStreamStatus = Optional.empty();

    private static final int LOG_NB_EVENTS = 100;
    private static final BluetoothEventLogger sEventLogger =
            new BluetoothEventLogger(LOG_NB_EVENTS, TAG + " event log");
    ;

    @VisibleForTesting ServiceFactory mServiceFactory = new ServiceFactory();

    private final Handler mHandler =
            new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MESSAGE_SYNC_TIMEOUT:
                            log("MESSAGE_SYNC_TIMEOUT: clear all sync data");
                            clearAllSyncData();
                            break;
                    }
                }
            };

    public BassClientService(Context ctx) {
        super(ctx);
    }

    public static boolean isEnabled() {
        return leaudioBroadcastFeatureSupport()
                && BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false);
    }

    private static class SourceSyncRequest {
        private ScanResult mScanResult;
        private boolean mHasPriority;

        SourceSyncRequest(ScanResult scanResult, boolean hasPriority) {
            this.mScanResult = scanResult;
            this.mHasPriority = hasPriority;
        }

        public ScanResult getScanResult() {
            return mScanResult;
        }

        public int getRssi() {
            return mScanResult.getRssi();
        }

        public boolean hasPriority() {
            return mHasPriority;
        }

        @Override
        public String toString() {
            return "SourceSyncRequest{"
                    + "mScanResult="
                    + mScanResult
                    + ", mHasPriority="
                    + mHasPriority
                    + '}';
        }
    }

    private static final Comparator<SourceSyncRequest> sSourceSyncRequestComparator =
            new Comparator<SourceSyncRequest>() {
                @Override
                public int compare(SourceSyncRequest ssr1, SourceSyncRequest ssr2) {
                    if (ssr1.hasPriority() && !ssr2.hasPriority()) {
                        return -1;
                    } else if (!ssr1.hasPriority() && ssr2.hasPriority()) {
                        return 1;
                    } else {
                        return Integer.compare(ssr2.getRssi(), ssr1.getRssi());
                    }
                }
            };

    private static class AddSourceData {
        BluetoothDevice mSink;
        BluetoothLeBroadcastMetadata mSourceMetadata;
        boolean mIsGroupOp;

        AddSourceData(
                BluetoothDevice sink,
                BluetoothLeBroadcastMetadata sourceMetadata,
                boolean isGroupOp) {
            mSink = sink;
            mSourceMetadata = sourceMetadata;
            mIsGroupOp = isGroupOp;
        }
    }

    void updatePeriodicAdvertisementResultMap(
            BluetoothDevice device,
            int addressType,
            int syncHandle,
            int advSid,
            int advInterval,
            int bId,
            PublicBroadcastData pbData,
            String broadcastName) {
        log("updatePeriodicAdvertisementResultMap: device: " + device);
        log("updatePeriodicAdvertisementResultMap: syncHandle: " + syncHandle);
        log("updatePeriodicAdvertisementResultMap: advSid: " + advSid);
        log("updatePeriodicAdvertisementResultMap: addressType: " + addressType);
        log("updatePeriodicAdvertisementResultMap: advInterval: " + advInterval);
        log("updatePeriodicAdvertisementResultMap: broadcastId: " + bId);
        log("updatePeriodicAdvertisementResultMap: broadcastName: " + broadcastName);
        log("mSyncHandleToDeviceMap" + mSyncHandleToDeviceMap);
        log("mPeriodicAdvertisementResultMap" + mPeriodicAdvertisementResultMap);
        if (mPeriodicAdvertisementResultMap != null) {
            HashMap<Integer, PeriodicAdvertisementResult> paResMap =
                    mPeriodicAdvertisementResultMap.get(device);
            if (paResMap == null
                    || (bId != BassConstants.INVALID_BROADCAST_ID && !paResMap.containsKey(bId))) {
                log("PAResmap: add >>>");
                PeriodicAdvertisementResult paRes =
                        new PeriodicAdvertisementResult(
                                device,
                                addressType,
                                syncHandle,
                                advSid,
                                advInterval,
                                bId,
                                pbData,
                                broadcastName);
                if (paRes != null) {
                    paRes.print();
                    mPeriodicAdvertisementResultMap.putIfAbsent(device, new HashMap<>());
                    mPeriodicAdvertisementResultMap.get(device).put(bId, paRes);
                }
            } else {
                log("PAResmap: update >>>");
                if (bId == BassConstants.INVALID_BROADCAST_ID) {
                    // Update when onSyncEstablished, try to retrieve valid broadcast id
                    if (leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
                        bId = getBroadcastIdForSyncHandle(BassConstants.INVALID_SYNC_HANDLE);

                        if (bId == BassConstants.INVALID_BROADCAST_ID
                                || !paResMap.containsKey(bId)) {
                            Log.e(TAG, "PAResmap: error! no valid broadcast id found>>>");
                            return;
                        }

                        int oldBroadcastId = getBroadcastIdForSyncHandle(syncHandle);
                        if (oldBroadcastId != BassConstants.INVALID_BROADCAST_ID
                                && oldBroadcastId != bId) {
                            log(
                                    "updatePeriodicAdvertisementResultMap: SyncEstablished on the"
                                            + " same syncHandle="
                                            + syncHandle
                                            + ", before syncLost");
                            if (leaudioBroadcastMonitorSourceSyncStatus()) {
                                log(
                                        "Notify broadcast source lost, broadcast id: "
                                                + oldBroadcastId);
                                mCallbacks.notifySourceLost(oldBroadcastId);
                            }
                            clearAllDataForSyncHandle(syncHandle);
                            mCachedBroadcasts.remove(oldBroadcastId);
                        }
                    } else {
                        for (Map.Entry<Integer, PeriodicAdvertisementResult> entry :
                                paResMap.entrySet()) {
                            PeriodicAdvertisementResult value = entry.getValue();
                            if (value.getBroadcastId() != BassConstants.INVALID_BROADCAST_ID) {
                                bId = value.getBroadcastId();
                                break;
                            }
                        }
                        if (bId == BassConstants.INVALID_BROADCAST_ID) {
                            log("PAResmap: error! no valid broadcast id found>>>");
                            return;
                        }
                    }
                }
                PeriodicAdvertisementResult paRes = paResMap.get(bId);
                if (advSid != BassConstants.INVALID_ADV_SID) {
                    paRes.updateAdvSid(advSid);
                }
                if (syncHandle != BassConstants.INVALID_SYNC_HANDLE) {
                    if (mSyncHandleToDeviceMap != null) {
                        mSyncHandleToDeviceMap.put(syncHandle, device);
                    }
                    paRes.updateSyncHandle(syncHandle);
                    if (paRes.getBroadcastId() != BassConstants.INVALID_BROADCAST_ID) {
                        // broadcast successfully synced
                        // update the sync handle for the broadcast source
                        updateSyncHandleForBroadcastId(syncHandle, paRes.getBroadcastId());
                    }
                }
                if (addressType != BassConstants.INVALID_ADV_ADDRESS_TYPE) {
                    paRes.updateAddressType(addressType);
                }
                if (advInterval != BassConstants.INVALID_ADV_INTERVAL) {
                    paRes.updateAdvInterval(advInterval);
                }
                if (bId != BassConstants.INVALID_BROADCAST_ID) {
                    paRes.updateBroadcastId(bId);
                }
                if (pbData != null) {
                    paRes.updatePublicBroadcastData(pbData);
                }
                if (broadcastName != null) {
                    paRes.updateBroadcastName(broadcastName);
                }
                paRes.print();
                paResMap.replace(bId, paRes);
            }
        }
        log(">>mPeriodicAdvertisementResultMap" + mPeriodicAdvertisementResultMap);
    }

    PeriodicAdvertisementResult getPeriodicAdvertisementResult(
            BluetoothDevice device, int broadcastId) {
        if (mPeriodicAdvertisementResultMap == null) {
            Log.e(TAG, "getPeriodicAdvertisementResult: mPeriodicAdvertisementResultMap is null");
            return null;
        }

        if (broadcastId == BassConstants.INVALID_BROADCAST_ID) {
            Log.e(TAG, "getPeriodicAdvertisementResult: invalid broadcast id");
            return null;
        }

        if (mPeriodicAdvertisementResultMap.containsKey(device)) {
            return mPeriodicAdvertisementResultMap.get(device).get(broadcastId);
        }
        return null;
    }

    void clearNotifiedFlags() {
        log("clearNotifiedFlags");
        for (Map.Entry<BluetoothDevice, HashMap<Integer, PeriodicAdvertisementResult>> entry :
                mPeriodicAdvertisementResultMap.entrySet()) {
            HashMap<Integer, PeriodicAdvertisementResult> value = entry.getValue();
            for (PeriodicAdvertisementResult result : value.values()) {
                result.setNotified(false);
                result.print();
            }
        }
    }

    void updateBase(int syncHandlemap, BaseData base) {
        if (mSyncHandleToBaseDataMap == null) {
            Log.e(TAG, "updateBase: mSyncHandleToBaseDataMap is null");
            return;
        }
        log("updateBase : mSyncHandleToBaseDataMap>>");
        mSyncHandleToBaseDataMap.put(syncHandlemap, base);
    }

    BaseData getBase(int syncHandlemap) {
        if (mSyncHandleToBaseDataMap == null) {
            Log.e(TAG, "getBase: mSyncHandleToBaseDataMap is null");
            return null;
        }
        BaseData base = mSyncHandleToBaseDataMap.get(syncHandlemap);
        log("getBase returns " + base);
        return base;
    }

    void removeActiveSyncedSource(BluetoothDevice scanDelegator, Integer syncHandle) {
        if (leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            throw new RuntimeException(
                    "Should never be executed with"
                            + " leaudioBroadcastExtractPeriodicScannerFromStateMachine flag");
        }
        if (mActiveSourceMap == null) {
            Log.e(TAG, "removeActiveSyncedSource: mActiveSourceMap is null");
            return;
        }

        log(
                "removeActiveSyncedSource, scanDelegator: "
                        + scanDelegator
                        + ", syncHandle: "
                        + syncHandle);
        if (syncHandle == null) {
            // remove all sources for this scanDelegator
            mActiveSourceMap.remove(scanDelegator);
        } else {
            List<Integer> sources = mActiveSourceMap.get(scanDelegator);
            if (sources != null) {
                sources.removeIf(e -> e.equals(syncHandle));
                if (sources.isEmpty()) {
                    mActiveSourceMap.remove(scanDelegator);
                }
            }
        }
        sEventLogger.logd(
                TAG,
                "Broadcast Source Unsynced: scanDelegator= "
                        + scanDelegator
                        + ", syncHandle= "
                        + syncHandle);
    }

    void addActiveSyncedSource(BluetoothDevice scanDelegator, Integer syncHandle) {
        if (leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            throw new RuntimeException(
                    "Should never be executed with"
                            + " leaudioBroadcastExtractPeriodicScannerFromStateMachine flag");
        }
        if (mActiveSourceMap == null) {
            Log.e(TAG, "addActiveSyncedSource: mActiveSourceMap is null");
            return;
        }

        log(
                "addActiveSyncedSource, scanDelegator: "
                        + scanDelegator
                        + ", syncHandle: "
                        + syncHandle);
        if (syncHandle != BassConstants.INVALID_SYNC_HANDLE) {
            mActiveSourceMap.putIfAbsent(scanDelegator, new ArrayList<>());
            if (!mActiveSourceMap.get(scanDelegator).contains(syncHandle)) {
                mActiveSourceMap.get(scanDelegator).add(syncHandle);
            }
        }
        sEventLogger.logd(
                TAG,
                "Broadcast Source Synced: scanDelegator= "
                        + scanDelegator
                        + ", syncHandle= "
                        + syncHandle);
    }

    List<Integer> getActiveSyncedSources(BluetoothDevice scanDelegator) {
        if (leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            throw new RuntimeException(
                    "Should never be executed with"
                            + " leaudioBroadcastExtractPeriodicScannerFromStateMachine flag");
        }
        if (mActiveSourceMap == null) {
            Log.e(TAG, "getActiveSyncedSources: mActiveSourceMap is null");
            return null;
        }

        List<Integer> currentSources = mActiveSourceMap.get(scanDelegator);
        if (currentSources != null) {
            log(
                    "getActiveSyncedSources: scanDelegator: "
                            + scanDelegator
                            + ", sources num: "
                            + currentSources.size());
        } else {
            log(
                    "getActiveSyncedSources: scanDelegator: "
                            + scanDelegator
                            + ", currentSources is null");
        }
        return currentSources;
    }

    void removeActiveSyncedSource(Integer syncHandle) {
        log("removeActiveSyncedSource, syncHandle: " + syncHandle);
        if (syncHandle == null) {
            // remove all sources
            mActiveSyncedSources.clear();
        } else {
            mActiveSyncedSources.removeIf(e -> e.equals(syncHandle));
        }
        sEventLogger.logd(TAG, "Broadcast Source Unsynced: syncHandle= " + syncHandle);
    }

    void addActiveSyncedSource(Integer syncHandle) {
        log("addActiveSyncedSource, syncHandle: " + syncHandle);
        if (syncHandle != BassConstants.INVALID_SYNC_HANDLE) {
            if (!mActiveSyncedSources.contains(syncHandle)) {
                mActiveSyncedSources.add(syncHandle);
            }
        }
        sEventLogger.logd(TAG, "Broadcast Source Synced: syncHandle= " + syncHandle);
    }

    List<Integer> getActiveSyncedSources() {
        log("getActiveSyncedSources: sources num: " + mActiveSyncedSources.size());
        return mActiveSyncedSources;
    }

    ScanResult getCachedBroadcast(int broadcastId) {
        return mCachedBroadcasts.get(broadcastId);
    }

    public Callbacks getCallbacks() {
        return mCallbacks;
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothLeBroadcastAssistantBinder(this);
    }

    @Override
    public void start() {
        Log.d(TAG, "start()");
        if (sService != null) {
            throw new IllegalStateException("start() called twice");
        }
        mAdapterService =
                Objects.requireNonNull(
                        AdapterService.getAdapterService(),
                        "AdapterService cannot be null when BassClientService starts");
        mDatabaseManager =
                Objects.requireNonNull(
                        mAdapterService.getDatabase(),
                        "DatabaseManager cannot be null when BassClientService starts");
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        mStateMachines.clear();
        mStateMachinesThread = new HandlerThread("BassClientService.StateMachines");
        mStateMachinesThread.start();
        mCallbackHandlerThread = new HandlerThread(TAG);
        mCallbackHandlerThread.start();
        mCallbacks = new Callbacks(mCallbackHandlerThread.getLooper());

        setBassClientService(this);
        // While removing leaudioBroadcastExtractPeriodicScannerFromStateMachine remove all checks
        // against null for: mSyncHandleToDeviceMap, mPeriodicAdvertisementResultMap,
        // mSyncHandleToBaseDataMap, mSyncHandleToBroadcastIdMap as they never be null
        if (!leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            // Saving PSync stuff for future addition
            mSyncHandleToDeviceMap = new HashMap<Integer, BluetoothDevice>();
            mPeriodicAdvertisementResultMap =
                    new HashMap<BluetoothDevice, HashMap<Integer, PeriodicAdvertisementResult>>();
            mSyncHandleToBaseDataMap = new HashMap<Integer, BaseData>();
            mSyncHandleToBroadcastIdMap = new HashMap<Integer, Integer>();
            mSearchScanCallback = null;
        }
    }

    @Override
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786 - Fix BASS annotation
    public void stop() {
        Log.d(TAG, "stop()");

        mUnicastSourceStreamStatus = Optional.empty();

        if (mDialingOutTimeoutEvent != null) {
            mHandler.removeCallbacks(mDialingOutTimeoutEvent);
            mDialingOutTimeoutEvent = null;
        }

        if (mIsAssistantActive) {
            LeAudioService leAudioService = mServiceFactory.getLeAudioService();
            if (leAudioService != null) {
                leAudioService.activeBroadcastAssistantNotification(false);
            }
            mIsAssistantActive = false;
        }

        if (mIsAllowedContextOfActiveGroupModified) {
            LeAudioService leAudioService = mServiceFactory.getLeAudioService();
            if (leAudioService != null) {
                leAudioService.setActiveGroupAllowedContextMask(
                        BluetoothLeAudio.CONTEXTS_ALL, BluetoothLeAudio.CONTEXTS_ALL);
            }
            mIsAllowedContextOfActiveGroupModified = false;
        }

        synchronized (mStateMachines) {
            for (BassClientStateMachine sm : mStateMachines.values()) {
                BassObjectsFactory.getInstance().destroyStateMachine(sm);
            }
            mStateMachines.clear();
        }
        if (mCallbackHandlerThread != null) {
            mCallbackHandlerThread.quitSafely();
            mCallbackHandlerThread = null;
        }
        if (mStateMachinesThread != null) {
            mStateMachinesThread.quitSafely();
            mStateMachinesThread = null;
        }

        mHandler.removeCallbacksAndMessages(null);

        setBassClientService(null);
        if (!leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            if (mSyncHandleToDeviceMap != null) {
                mSyncHandleToDeviceMap.clear();
                mSyncHandleToDeviceMap = null;
            }
            if (mPeriodicAdvertisementResultMap != null) {
                mPeriodicAdvertisementResultMap.clear();
                mPeriodicAdvertisementResultMap = null;
            }
            if (mActiveSourceMap != null) {
                mActiveSourceMap.clear();
            }
            if (mLocalBroadcastReceivers != null) {
                mLocalBroadcastReceivers.clear();
            }
            if (mPendingGroupOp != null) {
                mPendingGroupOp.clear();
            }
            if (mCachedBroadcasts != null) {
                mCachedBroadcasts.clear();
            }
            if (mBroadcastMetadataMap != null) {
                mBroadcastMetadataMap.clear();
            }
            if (mSyncHandleToBroadcastIdMap != null) {
                mSyncHandleToBroadcastIdMap.clear();
                mSyncHandleToBroadcastIdMap = null;
            }
            if (mSyncHandleToBaseDataMap != null) {
                mSyncHandleToBaseDataMap.clear();
                mSyncHandleToBaseDataMap = null;
            }
        } else {
            synchronized (mSearchScanCallbackLock) {
                if (mBluetoothLeScannerWrapper != null && mSearchScanCallback != null) {
                    mBluetoothLeScannerWrapper.stopScan(mSearchScanCallback);
                }
                mBluetoothLeScannerWrapper = null;
                mSearchScanCallback = null;
                clearAllSyncData();
            }

            mLocalBroadcastReceivers.clear();
            mPendingGroupOp.clear();
            mBroadcastMetadataMap.clear();
        }
    }

    BluetoothDevice getDeviceForSyncHandle(int syncHandle) {
        if (mSyncHandleToDeviceMap == null) {
            return null;
        }
        return mSyncHandleToDeviceMap.get(syncHandle);
    }

    int getSyncHandleForBroadcastId(int broadcastId) {
        if (mSyncHandleToBroadcastIdMap == null) {
            return BassConstants.INVALID_SYNC_HANDLE;
        }

        int syncHandle = BassConstants.INVALID_SYNC_HANDLE;
        for (Map.Entry<Integer, Integer> entry : mSyncHandleToBroadcastIdMap.entrySet()) {
            Integer value = entry.getValue();
            if (value == broadcastId) {
                syncHandle = entry.getKey();
                break;
            }
        }
        return syncHandle;
    }

    int getBroadcastIdForSyncHandle(int syncHandle) {
        if (mSyncHandleToBroadcastIdMap == null) {
            return BassConstants.INVALID_BROADCAST_ID;
        }

        if (mSyncHandleToBroadcastIdMap.containsKey(syncHandle)) {
            return mSyncHandleToBroadcastIdMap.get(syncHandle);
        }
        return BassConstants.INVALID_BROADCAST_ID;
    }

    void updateSyncHandleForBroadcastId(int syncHandle, int broadcastId) {
        if (mSyncHandleToBroadcastIdMap == null) {
            Log.e(TAG, "mSyncHandleToBroadcastIdMap is null");
            return;
        }

        mSyncHandleToBroadcastIdMap.entrySet().removeIf(entry -> entry.getValue() == broadcastId);
        mSyncHandleToBroadcastIdMap.put(syncHandle, broadcastId);
        log("Updated mSyncHandleToBroadcastIdMap: " + mSyncHandleToBroadcastIdMap);
    }

    private static synchronized void setBassClientService(BassClientService instance) {
        Log.d(TAG, "setBassClientService(): set to: " + instance);
        sService = instance;
    }

    private void enqueueSourceGroupOp(BluetoothDevice sink, Integer msgId, Object obj) {
        log("enqueueSourceGroupOp device: " + sink + ", msgId: " + msgId);

        if (!mPendingGroupOp.containsKey(sink)) {
            mPendingGroupOp.put(sink, new ArrayList());
        }
        mPendingGroupOp.get(sink).add(new Pair<Integer, Object>(msgId, obj));
    }

    private boolean isSuccess(int status) {
        boolean ret = false;
        switch (status) {
            case BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST:
            case BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST:
            case BluetoothStatusCodes.REASON_REMOTE_REQUEST:
            case BluetoothStatusCodes.REASON_SYSTEM_POLICY:
                ret = true;
                break;
            default:
                break;
        }
        return ret;
    }

    private boolean isAnyPendingAddSourceOperation() {
        for (BluetoothDevice device : getConnectedDevices()) {
            List<Pair<Integer, Object>> operations = mPendingGroupOp.get(device);
            if (operations == null) {
                continue;
            }

            boolean isAnyPendingAddSourceOperationForDevice =
                    operations.stream()
                            .anyMatch(e -> e.first.equals(BassClientStateMachine.ADD_BCAST_SOURCE));

            if (isAnyPendingAddSourceOperationForDevice) {
                return true;
            }
        }

        return false;
    }

    private void checkForPendingGroupOpRequest(
            BluetoothDevice sink, int reason, int reqMsg, Object obj) {
        log(
                "checkForPendingGroupOpRequest device: "
                        + sink
                        + ", reason: "
                        + reason
                        + ", reqMsg: "
                        + reqMsg);

        List<Pair<Integer, Object>> operations = mPendingGroupOp.get(sink);
        if (operations == null) {
            return;
        }

        switch (reqMsg) {
            case BassClientStateMachine.ADD_BCAST_SOURCE:
                if (obj == null) {
                    return;
                }
                // Identify the operation by operation type and broadcastId
                if (isSuccess(reason)) {
                    BluetoothLeBroadcastReceiveState sourceState =
                            (BluetoothLeBroadcastReceiveState) obj;
                    boolean removed =
                            operations.removeIf(
                                    m ->
                                            (m.first.equals(
                                                            BassClientStateMachine
                                                                    .ADD_BCAST_SOURCE))
                                                    && (sourceState.getBroadcastId()
                                                            == ((BluetoothLeBroadcastMetadata)
                                                                            m.second)
                                                                    .getBroadcastId()));
                    if (removed) {
                        setSourceGroupManaged(sink, sourceState.getSourceId(), true);
                    }
                } else {
                    BluetoothLeBroadcastMetadata metadata = (BluetoothLeBroadcastMetadata) obj;
                    operations.removeIf(
                            m ->
                                    (m.first.equals(BassClientStateMachine.ADD_BCAST_SOURCE))
                                            && (metadata.getBroadcastId()
                                                    == ((BluetoothLeBroadcastMetadata) m.second)
                                                            .getBroadcastId()));

                    if (!isAnyPendingAddSourceOperation()
                            && mIsAssistantActive
                            && mPausedBroadcastSinks.isEmpty()) {
                        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
                        mIsAssistantActive = false;
                        mUnicastSourceStreamStatus = Optional.empty();

                        if (leAudioService != null) {
                            leAudioService.activeBroadcastAssistantNotification(false);
                        }
                    }
                }
                break;
            case BassClientStateMachine.REMOVE_BCAST_SOURCE:
                // Identify the operation by operation type and sourceId
                Integer sourceId = (Integer) obj;
                operations.removeIf(
                        m ->
                                m.first.equals(BassClientStateMachine.REMOVE_BCAST_SOURCE)
                                        && (sourceId.equals((Integer) m.second)));
                setSourceGroupManaged(sink, sourceId, false);
                break;
            default:
                break;
        }
    }

    private boolean isDevicePartOfActiveUnicastGroup(BluetoothDevice device) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return false;
        }

        return (leAudioService.getActiveGroupId() != LE_AUDIO_GROUP_ID_INVALID)
                && (leAudioService.getActiveDevices().contains(device));
    }

    private boolean isEmptyBluetoothDevice(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "Device is null!");
            return true;
        }

        return device.getAddress().equals("00:00:00:00:00:00");
    }

    private boolean hasAnyConnectedDeviceExternalBroadcastSource() {
        for (BluetoothDevice device : getConnectedDevices()) {
            // Check if any connected device has add some source
            if (getAllSources(device).stream()
                    .anyMatch(
                            receiveState ->
                                    (!isEmptyBluetoothDevice(receiveState.getSourceDevice())
                                            && !isLocalBroadcast(receiveState)))) {
                return true;
            }
        }

        return false;
    }

    private boolean isAnyConnectedDeviceSwitchingSource() {
        for (BluetoothDevice device : getConnectedDevices()) {
            synchronized (mStateMachines) {
                BassClientStateMachine sm = getOrCreateStateMachine(device);
                // Need to check both mPendingSourceToSwitch and mPendingMetadata
                // to guard the whole source switching flow
                if (sm != null
                        && (sm.hasPendingSwitchingSourceOperation()
                                || sm.hasPendingSourceOperation())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkAndSetGroupAllowedContextMask(BluetoothDevice sink) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return;
        }

        if (leaudioAllowedContextMask()) {
            /* Don't bother active group (external broadcaster scenario) with SOUND EFFECTS */
            if (!mIsAllowedContextOfActiveGroupModified && isDevicePartOfActiveUnicastGroup(sink)) {
                leAudioService.setActiveGroupAllowedContextMask(
                        BluetoothLeAudio.CONTEXTS_ALL
                                & ~BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS,
                        BluetoothLeAudio.CONTEXTS_ALL);
                mIsAllowedContextOfActiveGroupModified = true;
            }
        }
    }

    private void checkAndResetGroupAllowedContextMask() {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return;
        }

        if (leaudioAllowedContextMask()) {
            /* Restore allowed context mask for Unicast */
            if (mIsAllowedContextOfActiveGroupModified
                    && !hasAnyConnectedDeviceExternalBroadcastSource()
                    && !isAnyConnectedDeviceSwitchingSource()) {
                leAudioService.setActiveGroupAllowedContextMask(
                        BluetoothLeAudio.CONTEXTS_ALL, BluetoothLeAudio.CONTEXTS_ALL);
                mIsAllowedContextOfActiveGroupModified = false;
            }
        }
    }

    private void localNotifyReceiveStateChanged(BluetoothDevice sink) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return;
        }

        boolean isAssistantActive =
                areReceiversReceivingOnlyExternalBroadcast(getConnectedDevices());

        if (isAssistantActive) {
            /* Assistant become active */
            if (!mIsAssistantActive) {
                mIsAssistantActive = true;
                leAudioService.activeBroadcastAssistantNotification(true);
            }

            checkAndSetGroupAllowedContextMask(sink);
        } else {
            /* Assistant become inactive */
            if (mIsAssistantActive && mPausedBroadcastSinks.isEmpty()) {
                mIsAssistantActive = false;
                mUnicastSourceStreamStatus = Optional.empty();
                leAudioService.activeBroadcastAssistantNotification(false);
            }

            /* Restore allowed context mask for unicast in case if last connected broadcast
             * delegator device which has external source removes this source
             */
            checkAndResetGroupAllowedContextMask();
        }
    }

    private void localNotifySourceAdded(
            BluetoothDevice sink, BluetoothLeBroadcastReceiveState receiveState) {
        if (!isLocalBroadcast(receiveState)) {
            return;
        }

        int broadcastId = receiveState.getBroadcastId();

        /* Track devices bonded to local broadcast for further broadcast status handling when sink
         * device is:
         *     - disconnecting (if no more receivers, broadcast can be stopped)
         *     - connecting (resynchronize if connection lost)
         */
        if (mLocalBroadcastReceivers.containsKey(broadcastId)) {
            mLocalBroadcastReceivers.get(broadcastId).add(sink);
        } else {
            mLocalBroadcastReceivers.put(
                    broadcastId, new HashSet<BluetoothDevice>(Arrays.asList(sink)));
        }
    }

    private void setSourceGroupManaged(BluetoothDevice sink, int sourceId, boolean isGroupOp) {
        log("setSourceGroupManaged device: " + sink);
        if (isGroupOp) {
            if (!mGroupManagedSources.containsKey(sink)) {
                mGroupManagedSources.put(sink, new ArrayList<>());
            }
            mGroupManagedSources.get(sink).add(sourceId);
        } else {
            List<Integer> sources = mGroupManagedSources.get(sink);
            if (sources != null) {
                sources.removeIf(e -> e.equals(sourceId));
            }
        }
    }

    private Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>
            getGroupManagedDeviceSources(BluetoothDevice sink, Integer sourceId) {
        log("getGroupManagedDeviceSources device: " + sink + " sourceId: " + sourceId);
        Map map = new HashMap<BluetoothDevice, Integer>();

        if (mGroupManagedSources.containsKey(sink)
                && mGroupManagedSources.get(sink).contains(sourceId)) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + sink);
                return new Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>(
                        null, null);
            }

            BluetoothLeBroadcastMetadata metadata =
                    stateMachine.getCurrentBroadcastMetadata(sourceId);
            if (metadata != null) {
                int broadcastId = metadata.getBroadcastId();

                for (BluetoothDevice device : getTargetDeviceList(sink, true)) {
                    List<BluetoothLeBroadcastReceiveState> sources =
                            getOrCreateStateMachine(device).getAllSources();

                    // For each device, find the source ID having this broadcast ID
                    Optional<BluetoothLeBroadcastReceiveState> receiver =
                            sources.stream()
                                    .filter(e -> e.getBroadcastId() == broadcastId)
                                    .findAny();
                    if (receiver.isPresent()) {
                        map.put(device, receiver.get().getSourceId());
                    } else {
                        // Put invalid source ID if the remote doesn't have it
                        map.put(device, BassConstants.INVALID_SOURCE_ID);
                    }
                }
                return new Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>(
                        metadata, map);
            } else {
                Log.e(
                        TAG,
                        "Couldn't find broadcast metadata for device: "
                                + sink
                                + ", and sourceId:"
                                + sourceId);
            }
        }

        // Just put this single device if this source is not group managed
        map.put(sink, sourceId);
        return new Pair<BluetoothLeBroadcastMetadata, Map<BluetoothDevice, Integer>>(null, map);
    }

    private List<BluetoothDevice> getTargetDeviceList(BluetoothDevice device, boolean isGroupOp) {
        if (isGroupOp) {
            CsipSetCoordinatorService csipClient = mServiceFactory.getCsipSetCoordinatorService();
            if (csipClient != null) {
                // Check for coordinated set of devices in the context of CAP
                List<BluetoothDevice> csipDevices =
                        csipClient.getGroupDevicesOrdered(device, BluetoothUuid.CAP);
                if (!csipDevices.isEmpty()) {
                    return csipDevices;
                } else {
                    Log.w(TAG, "CSIP group is empty.");
                }
            } else {
                Log.e(TAG, "CSIP service is null. No grouping information available.");
            }
        }

        List<BluetoothDevice> devices = new ArrayList<>();
        devices.add(device);
        return devices;
    }

    private boolean isValidBroadcastSourceAddition(
            BluetoothDevice device, BluetoothLeBroadcastMetadata metaData) {
        boolean retval = true;
        List<BluetoothLeBroadcastReceiveState> currentAllSources = getAllSources(device);
        for (int i = 0; i < currentAllSources.size(); i++) {
            BluetoothLeBroadcastReceiveState state = currentAllSources.get(i);
            if (metaData.getSourceDevice().equals(state.getSourceDevice())
                    && metaData.getSourceAddressType() == state.getSourceAddressType()
                    && metaData.getSourceAdvertisingSid() == state.getSourceAdvertisingSid()
                    && metaData.getBroadcastId() == state.getBroadcastId()) {
                retval = false;
                Log.e(
                        TAG,
                        "isValidBroadcastSourceAddition: fail for "
                                + device
                                + " metaData: "
                                + metaData);
                break;
            }
        }
        return retval;
    }

    private boolean hasRoomForBroadcastSourceAddition(BluetoothDevice device) {
        BassClientStateMachine stateMachine = null;
        synchronized (mStateMachines) {
            stateMachine = getOrCreateStateMachine(device);
        }
        if (stateMachine == null) {
            log("stateMachine is null");
            return false;
        }
        boolean isRoomAvailable = false;
        for (BluetoothLeBroadcastReceiveState recvState : stateMachine.getAllSources()) {
            if (isEmptyBluetoothDevice(recvState.getSourceDevice())) {
                isRoomAvailable = true;
                break;
            }
        }
        log("isRoomAvailable: " + isRoomAvailable);
        return isRoomAvailable;
    }

    private Integer getSourceIdToRemove(BluetoothDevice device) {
        BassClientStateMachine stateMachine = null;

        synchronized (mStateMachines) {
            stateMachine = getOrCreateStateMachine(device);
        }
        if (stateMachine == null) {
            log("stateMachine is null");
            return BassConstants.INVALID_SOURCE_ID;
        }
        List<BluetoothLeBroadcastReceiveState> sources = stateMachine.getAllSources();
        if (sources.isEmpty()) {
            log("sources is empty");
            return BassConstants.INVALID_SOURCE_ID;
        }

        Integer sourceId = BassConstants.INVALID_SOURCE_ID;
        // Select the source by checking if there is one with PA not synced
        Optional<BluetoothLeBroadcastReceiveState> receiver =
                sources.stream()
                        .filter(
                                e ->
                                        (e.getPaSyncState()
                                                != BluetoothLeBroadcastReceiveState
                                                        .PA_SYNC_STATE_SYNCHRONIZED))
                        .findAny();
        if (receiver.isPresent()) {
            sourceId = receiver.get().getSourceId();
        } else {
            // If all sources are synced, continue to pick the 1st source
            sourceId = sources.get(0).getSourceId();
        }
        return sourceId;
    }

    private BassClientStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine != null) {
                return stateMachine;
            }

            log("Creating a new state machine for " + device);
            stateMachine =
                    BassObjectsFactory.getInstance()
                            .makeStateMachine(
                                    device,
                                    this,
                                    mAdapterService,
                                    mStateMachinesThread.getLooper());
            if (stateMachine != null) {
                mStateMachines.put(device, stateMachine);
            }

            return stateMachine;
        }
    }

    class DialingOutTimeoutEvent implements Runnable {
        Integer mBroadcastId;

        DialingOutTimeoutEvent(Integer broadcastId) {
            mBroadcastId = broadcastId;
        }

        @Override
        public void run() {
            mDialingOutTimeoutEvent = null;

            if (getBassClientService() == null) {
                Log.e(TAG, "DialingOutTimeoutEvent: No Bass service");
                return;
            }

            LeAudioService leAudioService = mServiceFactory.getLeAudioService();
            if (leAudioService == null) {
                Log.d(TAG, "DialingOutTimeoutEvent: No available LeAudioService");
                return;
            }

            sEventLogger.logd(TAG, "Broadcast timeout: " + mBroadcastId);
            mLocalBroadcastReceivers.remove(mBroadcastId);
            leAudioService.stopBroadcast(mBroadcastId);
        }

        public boolean isScheduledForBroadcast(Integer broadcastId) {
            return mBroadcastId.equals(broadcastId);
        }
    }

    /**
     * Get the BassClientService instance
     *
     * @return BassClientService instance
     */
    public static synchronized BassClientService getBassClientService() {
        if (sService == null) {
            Log.w(TAG, "getBassClientService(): service is NULL");
            return null;
        }
        if (!sService.isAvailable()) {
            Log.w(TAG, "getBassClientService(): service is not available");
            return null;
        }
        return sService;
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            BassClientStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.w(
                        TAG,
                        "removeStateMachine: device " + device + " does not have a state machine");
                return;
            }
            log("removeStateMachine: removing state machine for device: " + device);
            sm.doQuit();
            sm.cleanup();
            mStateMachines.remove(device);
        }

        // Cleanup device cache
        mPendingGroupOp.remove(device);
        mGroupManagedSources.remove(device);
        mActiveSourceMap.remove(device);
    }

    private void handleReconnectingAudioSharingModeDevice(BluetoothDevice device) {
        /* In case of reconnecting Audio Sharing mode device */
        if (mDialingOutTimeoutEvent != null) {
            for (Map.Entry<Integer, HashSet<BluetoothDevice>> entry :
                    mLocalBroadcastReceivers.entrySet()) {
                Integer broadcastId = entry.getKey();
                HashSet<BluetoothDevice> devices = entry.getValue();

                /* If associated with any broadcast, try to remove pending timeout callback */
                if ((mDialingOutTimeoutEvent.isScheduledForBroadcast(broadcastId))
                        && (devices.contains(device))) {
                    Log.i(
                            TAG,
                            "connectionStateChanged: reconnected previousely synced device: "
                                    + device);
                    mHandler.removeCallbacks(mDialingOutTimeoutEvent);
                    mDialingOutTimeoutEvent = null;
                    break;
                }
            }
        }
    }

    private void informConnectedDeviceAboutScanOffloadStop() {
        for (BluetoothDevice device : getConnectedDevices()) {
            synchronized (mStateMachines) {
                BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
                if (stateMachine == null) {
                    Log.w(
                            TAG,
                            "informConnectedDeviceAboutScanOffloadStop: Can't get state "
                                    + "machine for device: "
                                    + device);
                    continue;
                }
                stateMachine.sendMessage(BassClientStateMachine.STOP_SCAN_OFFLOAD);
            }
        }
    }

    private int areValidParametersToModifySource(
            BluetoothLeBroadcastMetadata updatedMetadata,
            BassClientStateMachine stateMachine,
            Integer deviceSourceId,
            BluetoothDevice device) {
        if (updatedMetadata == null || stateMachine == null) {
            log(
                    "areValidParametersToModifySource: Error bad parameters: sourceId = "
                            + deviceSourceId
                            + " updatedMetadata = "
                            + updatedMetadata);
            return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
        }
        if (deviceSourceId == BassConstants.INVALID_SOURCE_ID) {
            log("areValidParametersToModifySource: no such sourceId for device: " + device);
            return BluetoothStatusCodes.ERROR_LE_BROADCAST_ASSISTANT_INVALID_SOURCE_ID;
        }
        if (getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
            log("areValidParametersToModifySource: device is not connected");
            return BluetoothStatusCodes.ERROR_REMOTE_LINK_ERROR;
        }
        byte[] code = updatedMetadata.getBroadcastCode();
        if ((code != null) && (code.length != 0)) {
            if ((code.length > 16) || (code.length < 4)) {
                log(
                        "areValidParametersToModifySource: Invalid broadcast code length: "
                                + code.length
                                + ", should be between 4 and 16 octets");
                return BluetoothStatusCodes.ERROR_BAD_PARAMETERS;
            }
        }
        if (stateMachine.hasPendingSourceOperation()) {
            Log.w(
                    TAG,
                    "modifySource: source operation already pending, device: "
                            + device
                            + ", broadcastId: "
                            + updatedMetadata.getBroadcastId());
            return BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE;
        }

        return BluetoothStatusCodes.SUCCESS;
    }

    void handleConnectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> connectionStateChanged(device, fromState, toState));
    }

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

        sEventLogger.logd(
                TAG,
                "connectionStateChanged: device: "
                        + device
                        + ", fromState= "
                        + BluetoothProfile.getConnectionStateName(fromState)
                        + ", toState= "
                        + BluetoothProfile.getConnectionStateName(toState));

        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == BluetoothProfile.STATE_DISCONNECTED) {
            mPendingGroupOp.remove(device);

            int bondState = mAdapterService.getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                log("Unbonded " + device + ". Removing state machine");
                removeStateMachine(device);
            }

            /* Restore allowed context mask for unicast in case if last connected broadcast
             * delegator device which has external source disconnectes.
             */
            checkAndResetGroupAllowedContextMask();
        } else if (toState == BluetoothProfile.STATE_CONNECTED) {
            handleReconnectingAudioSharingModeDevice(device);
        }
    }

    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        log("Bond state changed for device: " + device + " state: " + bondState);

        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }

        synchronized (mStateMachines) {
            BassClientStateMachine sm = mStateMachines.get(device);
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

    /**
     * Connects the bass profile to the passed in device
     *
     * @param device is the device with which we will connect the Bass profile
     * @return true if BAss profile successfully connected, false otherwise
     */
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);
        if (device == null) {
            Log.e(TAG, "connect: device is null");
            return false;
        }
        if (getConnectionPolicy(device) == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.e(TAG, "connect: connection policy set to forbidden");
            return false;
        }
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + device);
                return false;
            }

            stateMachine.sendMessage(BassClientStateMachine.CONNECT);
        }
        return true;
    }

    /**
     * Disconnects Bassclient profile for the passed in device
     *
     * @param device is the device with which we want to disconnected the BAss client profile
     * @return true if Bass client profile successfully disconnected, false otherwise
     */
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);
        if (device == null) {
            Log.e(TAG, "disconnect: device is null");
            return false;
        }
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + device);
                return false;
            }

            stateMachine.sendMessage(BassClientStateMachine.DISCONNECT);
        }
        return true;
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
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled()) {
            Log.e(TAG, "okToConnect: cannot connect to " + device + " : quiet mode enabled");
            return false;
        }
        // Check connection policy and accept or reject the connection.
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
     * Get connection state of remote device
     *
     * @param sink the remote device
     * @return connection state
     */
    public int getConnectionState(BluetoothDevice sink) {
        synchronized (mStateMachines) {
            BassClientStateMachine sm = getOrCreateStateMachine(sink);
            if (sm == null) {
                log("getConnectionState returns STATE_DISC");
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        }
    }

    /**
     * Get a list of all LE Audio Broadcast Sinks with the specified connection states.
     *
     * @param states states array representing the connection states
     * @return a list of devices that match the provided connection states
     */
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
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.BASS)) {
                    continue;
                }
                int connectionState = BluetoothProfile.STATE_DISCONNECTED;
                BassClientStateMachine sm = getOrCreateStateMachine(device);
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
     * Get a list of all LE Audio Broadcast Sinks connected with the LE Audio Broadcast Assistant.
     *
     * @return list of connected devices
     */
    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (BassClientStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            log("getConnectedDevices: " + devices);
            return devices;
        }
    }

    /**
     * Set the connectionPolicy of the Broadcast Audio Scan Service profile.
     *
     * <p>The connection policy can be one of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        boolean setSuccessfully =
                mDatabaseManager.setProfileConnectionPolicy(
                        device, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT, connectionPolicy);
        if (setSuccessfully && connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (setSuccessfully
                && connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return setSuccessfully;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p>The connection policy can be any of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device paired bluetooth device
     * @return connection policy of the device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(
                device, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);
    }

    /**
     * Register callbacks that will be invoked during scan offloading.
     *
     * @param cb callbacks to be invoked
     */
    public void registerCallback(IBluetoothLeBroadcastAssistantCallback cb) {
        Log.i(TAG, "registerCallback");
        mCallbacks.register(cb);
    }

    /**
     * Unregister callbacks that are invoked during scan offloading.
     *
     * @param cb callbacks to be unregistered
     */
    public void unregisterCallback(IBluetoothLeBroadcastAssistantCallback cb) {
        Log.i(TAG, "unregisterCallback");
        mCallbacks.unregister(cb);
    }

    /**
     * Search for LE Audio Broadcast Sources on behalf of all devices connected via Broadcast Audio
     * Scan Service, filtered by filters
     *
     * @param filters ScanFilters for finding exact Broadcast Source
     */
    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786 - Fix BASS annotation
    public void startSearchingForSources(List<ScanFilter> filters) {
        log("startSearchingForSources");
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "startSearchingForSources: Adapter is NULL");
            mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
            return;
        }

        if (!BluetoothMethodProxy.getInstance()
                .initializePeriodicAdvertisingManagerOnDefaultAdapter()) {
            Log.e(TAG, "Failed to initialize Periodic Advertising Manager on Default Adapter");
            mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
            return;
        }

        synchronized (mSearchScanCallbackLock) {
            if (!leaudioBroadcastExtractPeriodicScannerFromStateMachine()
                    || mBluetoothLeScannerWrapper == null) {
                mBluetoothLeScannerWrapper =
                        BassObjectsFactory.getInstance()
                                .getBluetoothLeScannerWrapper(mBluetoothAdapter);
            }
            if (mBluetoothLeScannerWrapper == null) {
                Log.e(TAG, "startLeScan: cannot get BluetoothLeScanner");
                mCallbacks.notifySearchStartFailed(BluetoothStatusCodes.ERROR_UNKNOWN);
                return;
            }
            if (mSearchScanCallback != null) {
                Log.e(TAG, "LE Scan has already started");
                mCallbacks.notifySearchStartFailed(
                        BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                return;
            }
            mSearchScanCallback =
                    new ScanCallback() {
                        @Override
                        public void onScanResult(int callbackType, ScanResult result) {
                            log("onScanResult:" + result);
                            synchronized (mSearchScanCallbackLock) {
                                // check mSearchScanCallback because even after
                                // mBluetoothLeScannerWrapper.stopScan(mSearchScanCallback) that
                                // callback could be called
                                if (mSearchScanCallback == null) {
                                    log("onScanResult: scanner already stopped");
                                    return;
                                }
                                if (callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES) {
                                    // Should not happen
                                    Log.e(TAG, "LE Scan has already started");
                                    return;
                                }
                                ScanRecord scanRecord = result.getScanRecord();
                                if (scanRecord == null) {
                                    Log.e(TAG, "Null scan record");
                                    return;
                                }
                                Map<ParcelUuid, byte[]> listOfUuids = scanRecord.getServiceData();
                                if (listOfUuids == null) {
                                    Log.e(TAG, "Service data is null");
                                    return;
                                }
                                if (!listOfUuids.containsKey(BassConstants.BAAS_UUID)) {
                                    return;
                                }
                                log("Broadcast Source Found:" + result.getDevice());
                                byte[] broadcastIdArray = listOfUuids.get(BassConstants.BAAS_UUID);
                                int broadcastId =
                                        (int)
                                                (((broadcastIdArray[2] & 0xff) << 16)
                                                        | ((broadcastIdArray[1] & 0xff) << 8)
                                                        | (broadcastIdArray[0] & 0xff));

                                sEventLogger.logd(
                                        TAG,
                                        "Broadcast Source Found: Broadcast ID: " + broadcastId);

                                if (broadcastId != BassConstants.INVALID_BROADCAST_ID
                                        && mCachedBroadcasts.get(broadcastId) == null) {
                                    log("selectBroadcastSource: broadcastId " + broadcastId);
                                    mCachedBroadcasts.put(broadcastId, result);
                                    if (leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
                                        addSelectSourceRequest(result, false);
                                    } else {
                                        synchronized (mStateMachines) {
                                            for (BassClientStateMachine sm :
                                                    mStateMachines.values()) {
                                                if (sm.isConnected()) {
                                                    selectSource(sm.getDevice(), result, false);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        public void onScanFailed(int errorCode) {
                            Log.e(TAG, "Scan Failure:" + errorCode);
                            informConnectedDeviceAboutScanOffloadStop();
                        }
                    };
            mHandler.removeMessages(MESSAGE_SYNC_TIMEOUT);
            // when starting scan, clear the previously cached broadcast scan results
            mCachedBroadcasts.clear();
            // clear previous sources notify flag before scanning new result
            // this is to make sure the active sources are notified even if already synced
            if (mPeriodicAdvertisementResultMap != null) {
                clearNotifiedFlags();
            }
            ScanSettings settings =
                    new ScanSettings.Builder()
                            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .setLegacy(false)
                            .build();
            if (filters == null) {
                filters = new ArrayList<ScanFilter>();
            }
            if (!BassUtils.containUuid(filters, BassConstants.BAAS_UUID)) {
                byte[] serviceData = {0x00, 0x00, 0x00}; // Broadcast_ID
                byte[] serviceDataMask = {0x00, 0x00, 0x00};

                filters.add(
                        new ScanFilter.Builder()
                                .setServiceData(
                                        BassConstants.BAAS_UUID, serviceData, serviceDataMask)
                                .build());
            }

            for (BluetoothDevice device : getConnectedDevices()) {
                synchronized (mStateMachines) {
                    BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
                    if (stateMachine == null) {
                        Log.w(
                                TAG,
                                "startSearchingForSources: Can't get state machine for "
                                        + "device: "
                                        + device);
                        continue;
                    }
                    stateMachine.sendMessage(BassClientStateMachine.START_SCAN_OFFLOAD);
                }
            }

            mBluetoothLeScannerWrapper.startScan(filters, settings, mSearchScanCallback);
            sEventLogger.logd(TAG, "startSearchingForSources");
            mCallbacks.notifySearchStarted(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        }
    }

    /** Stops an ongoing search for nearby Broadcast Sources */
    public void stopSearchingForSources() {
        log("stopSearchingForSources");
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "stopSearchingForSources: Adapter is NULL");
            return;
        }
        if (!leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            BluetoothLeScannerWrapper scanner =
                    BassObjectsFactory.getInstance()
                            .getBluetoothLeScannerWrapper(mBluetoothAdapter);
            if (scanner == null) {
                Log.e(TAG, "startLeScan: cannot get BluetoothLeScanner");
                return;
            }
            synchronized (mSearchScanCallbackLock) {
                if (mSearchScanCallback == null) {
                    Log.e(TAG, "Scan not started yet");
                    mCallbacks.notifySearchStopFailed(
                            BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    return;
                }
                informConnectedDeviceAboutScanOffloadStop();
                scanner.stopScan(mSearchScanCallback);
                mSearchScanCallback = null;
                sEventLogger.logd(TAG, "stopSearchingForSources");
                mCallbacks.notifySearchStopped(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
            }
        } else {
            synchronized (mSearchScanCallbackLock) {
                if (mBluetoothLeScannerWrapper == null || mSearchScanCallback == null) {
                    Log.e(TAG, "Scan not started yet");
                    mCallbacks.notifySearchStopFailed(
                            BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                    return;
                }
                mBluetoothLeScannerWrapper.stopScan(mSearchScanCallback);
                mBluetoothLeScannerWrapper = null;
                mSearchScanCallback = null;
                clearAllSyncData();
                informConnectedDeviceAboutScanOffloadStop();
                sEventLogger.logd(TAG, "stopSearchingForSources");
                mCallbacks.notifySearchStopped(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
            }
        }
    }

    private void clearAllSyncData() {
        mSourceSyncRequestsQueue.clear();
        mPendingSourcesToAdd.clear();

        cancelActiveSync(null);
        mActiveSyncedSources.clear();
        mPeriodicAdvCallbacksMap.clear();
        mBisDiscoveryCounterMap.clear();

        mSyncHandleToDeviceMap.clear();
        mSyncHandleToBaseDataMap.clear();
        mSyncHandleToBroadcastIdMap.clear();
        mPeriodicAdvertisementResultMap.clear();
    }

    /**
     * Return true if a search has been started by this application
     *
     * @return true if a search has been started by this application
     */
    public boolean isSearchInProgress() {
        synchronized (mSearchScanCallbackLock) {
            return mSearchScanCallback != null;
        }
    }

    /** Internal periodc Advertising manager callback */
    final class PACallback extends PeriodicAdvertisingCallback {
        @Override
        public void onSyncEstablished(
                int syncHandle,
                BluetoothDevice device,
                int advertisingSid,
                int skip,
                int timeout,
                int status) {
            log(
                    "onSyncEstablished syncHandle: "
                            + syncHandle
                            + ", device: "
                            + device
                            + ", advertisingSid: "
                            + advertisingSid
                            + ", skip: "
                            + skip
                            + ", timeout: "
                            + timeout
                            + ", status: "
                            + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // updates syncHandle, advSid
                // set other fields as invalid or null
                updatePeriodicAdvertisementResultMap(
                        device,
                        BassConstants.INVALID_ADV_ADDRESS_TYPE,
                        syncHandle,
                        advertisingSid,
                        BassConstants.INVALID_ADV_INTERVAL,
                        BassConstants.INVALID_BROADCAST_ID,
                        null,
                        null);
                addActiveSyncedSource(syncHandle);

                synchronized (mSearchScanCallbackLock) {
                    // when searching is stopped then start timer to stop active syncs
                    if (mSearchScanCallback == null) {
                        mHandler.removeMessages(MESSAGE_SYNC_TIMEOUT);
                        log("onSyncEstablished started timeout for canceling syncs");
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_SYNC_TIMEOUT, sSyncActiveTimeout.toMillis());
                    }
                }

                // update valid sync handle in mPeriodicAdvCallbacksMap
                synchronized (mPeriodicAdvCallbacksMap) {
                    if (mPeriodicAdvCallbacksMap.containsKey(BassConstants.INVALID_SYNC_HANDLE)) {
                        PeriodicAdvertisingCallback paCb =
                                mPeriodicAdvCallbacksMap.get(BassConstants.INVALID_SYNC_HANDLE);
                        mPeriodicAdvCallbacksMap.put(syncHandle, paCb);
                        mPeriodicAdvCallbacksMap.remove(BassConstants.INVALID_SYNC_HANDLE);
                    }
                }
                mBisDiscoveryCounterMap.put(syncHandle, MAX_BIS_DISCOVERY_TRIES_NUM);

                synchronized (mPendingSourcesToAdd) {
                    Iterator<AddSourceData> iterator = mPendingSourcesToAdd.iterator();
                    while (iterator.hasNext()) {
                        AddSourceData pendingSourcesToAdd = iterator.next();
                        BluetoothDevice sourceDevice =
                                pendingSourcesToAdd.mSourceMetadata.getSourceDevice();
                        if (sourceDevice.equals(device)) {
                            addSource(
                                    pendingSourcesToAdd.mSink,
                                    pendingSourcesToAdd.mSourceMetadata,
                                    pendingSourcesToAdd.mIsGroupOp);
                            iterator.remove();
                        }
                    }
                }
            } else {
                // remove failed sync handle
                int broadcastId = getBroadcastIdForSyncHandle(BassConstants.INVALID_SYNC_HANDLE);
                log("onSyncEstablished failed for broadcast id: " + broadcastId);
                mCachedBroadcasts.remove(broadcastId);
                mPeriodicAdvCallbacksMap.remove(BassConstants.INVALID_SYNC_HANDLE);
            }
            handleSelectSourceRequest();
        }

        @Override
        public void onPeriodicAdvertisingReport(PeriodicAdvertisingReport report) {
            int syncHandle = report.getSyncHandle();
            log("onPeriodicAdvertisingReport " + syncHandle);
            Integer bisCounter = mBisDiscoveryCounterMap.get(syncHandle);

            // Parse the BIS indices from report's service data
            if (bisCounter != null && bisCounter != 0) {
                if (parseScanRecord(syncHandle, report.getData())) {
                    mBisDiscoveryCounterMap.put(syncHandle, 0);
                } else {
                    bisCounter--;
                    mBisDiscoveryCounterMap.put(syncHandle, bisCounter);
                    if (bisCounter == 0) {
                        cancelActiveSync(syncHandle);
                    }
                }
            }

            if (leaudioBigDependsOnAudioState()) {
                BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
                if (srcDevice == null) {
                    log("No device found.");
                    return;
                }
                PeriodicAdvertisementResult result =
                        getPeriodicAdvertisementResult(
                                srcDevice, getBroadcastIdForSyncHandle(syncHandle));
                if (result == null) {
                    log("No PA record found");
                    return;
                }
                BaseData baseData = getBase(syncHandle);
                if (baseData == null) {
                    log("No BaseData found");
                    return;
                }
                PublicBroadcastData pbData = result.getPublicBroadcastData();
                if (pbData == null) {
                    log("No public broadcast data found, wait for BIG");
                    return;
                }
                if (!result.isNotified()) {
                    result.setNotified(true);
                    BluetoothLeBroadcastMetadata metaData =
                            getBroadcastMetadataFromBaseData(
                                    baseData, srcDevice, syncHandle, pbData.isEncrypted());
                    log("Notify broadcast source found");
                    mCallbacks.notifySourceFound(metaData);
                }
            }
        }

        @Override
        public void onSyncLost(int syncHandle) {
            int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
            log("OnSyncLost: syncHandle=" + syncHandle + ", broadcastID=" + broadcastId);
            if (leaudioBroadcastMonitorSourceSyncStatus()) {
                if (broadcastId != BassConstants.INVALID_BROADCAST_ID) {
                    log("Notify broadcast source lost, broadcast id: " + broadcastId);
                    mCallbacks.notifySourceLost(broadcastId);
                }
            }
            clearAllDataForSyncHandle(syncHandle);
            // Clear from cache to make possible sync again
            mCachedBroadcasts.remove(broadcastId);
        }

        @Override
        public void onBigInfoAdvertisingReport(int syncHandle, boolean encrypted) {
            log(
                    "onBIGInfoAdvertisingReport: syncHandle="
                            + syncHandle
                            + ", encrypted ="
                            + encrypted);
            BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
            if (srcDevice == null) {
                log("No device found.");
                return;
            }
            PeriodicAdvertisementResult result =
                    getPeriodicAdvertisementResult(
                            srcDevice, getBroadcastIdForSyncHandle(syncHandle));
            if (result == null) {
                log("No PA record found");
                return;
            }
            BaseData baseData = getBase(syncHandle);
            if (baseData == null) {
                log("No BaseData found");
                return;
            }
            if (!result.isNotified()) {
                result.setNotified(true);
                BluetoothLeBroadcastMetadata metaData =
                        getBroadcastMetadataFromBaseData(
                                baseData, srcDevice, syncHandle, encrypted);
                log("Notify broadcast source found");
                mCallbacks.notifySourceFound(metaData);
            }
        }

        @Override
        public void onSyncTransferred(BluetoothDevice device, int status) {
            log("onSyncTransferred: device=" + device + ", status =" + status);
        }
    }

    private void clearAllDataForSyncHandle(Integer syncHandle) {
        removeActiveSyncedSource(syncHandle);
        mPeriodicAdvCallbacksMap.remove(syncHandle);
        mSyncHandleToBaseDataMap.remove(syncHandle);
        mBisDiscoveryCounterMap.remove(syncHandle);
        BluetoothDevice srcDevice = getDeviceForSyncHandle(syncHandle);
        mSyncHandleToDeviceMap.remove(syncHandle);
        int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
        mSyncHandleToBroadcastIdMap.remove(syncHandle);
        if (srcDevice != null) {
            mPeriodicAdvertisementResultMap.get(srcDevice).remove(broadcastId);
            if (mPeriodicAdvertisementResultMap.get(srcDevice).isEmpty()) {
                mPeriodicAdvertisementResultMap.remove(srcDevice);
            }
        }
    }

    private BluetoothLeBroadcastMetadata getBroadcastMetadataFromBaseData(
            BaseData baseData, BluetoothDevice device, int syncHandle, boolean encrypted) {
        BluetoothLeBroadcastMetadata.Builder metaData = new BluetoothLeBroadcastMetadata.Builder();
        int index = 0;
        for (BaseData.BaseInformation baseLevel2 : baseData.getLevelTwo()) {
            BluetoothLeBroadcastSubgroup.Builder subGroup =
                    new BluetoothLeBroadcastSubgroup.Builder();
            for (int j = 0; j < baseLevel2.numSubGroups; j++) {
                BaseData.BaseInformation baseLevel3 = baseData.getLevelThree().get(index++);
                BluetoothLeBroadcastChannel.Builder channel =
                        new BluetoothLeBroadcastChannel.Builder();
                channel.setChannelIndex(baseLevel3.index);
                channel.setSelected(false);
                try {
                    channel.setCodecMetadata(
                            BluetoothLeAudioCodecConfigMetadata.fromRawBytes(
                                    baseLevel3.codecConfigInfo));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid metadata, adding empty data. Error: " + e);
                    channel.setCodecMetadata(
                            BluetoothLeAudioCodecConfigMetadata.fromRawBytes(new byte[0]));
                }
                subGroup.addChannel(channel.build());
            }
            byte[] arrayCodecId = baseLevel2.codecId;
            long codeId =
                    ((long) (arrayCodecId[4] & 0xff)) << 32
                            | (arrayCodecId[3] & 0xff) << 24
                            | (arrayCodecId[2] & 0xff) << 16
                            | (arrayCodecId[1] & 0xff) << 8
                            | (arrayCodecId[0] & 0xff);
            subGroup.setCodecId(codeId);
            try {
                subGroup.setCodecSpecificConfig(
                        BluetoothLeAudioCodecConfigMetadata.fromRawBytes(
                                baseLevel2.codecConfigInfo));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid config, adding empty one. Error: " + e);
                subGroup.setCodecSpecificConfig(
                        BluetoothLeAudioCodecConfigMetadata.fromRawBytes(new byte[0]));
            }

            try {
                subGroup.setContentMetadata(
                        BluetoothLeAudioContentMetadata.fromRawBytes(baseLevel2.metaData));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid metadata, adding empty one. Error: " + e);
                subGroup.setContentMetadata(
                        BluetoothLeAudioContentMetadata.fromRawBytes(new byte[0]));
            }

            metaData.addSubgroup(subGroup.build());
        }
        metaData.setSourceDevice(device, device.getAddressType());
        byte[] arrayPresentationDelay = baseData.getLevelOne().presentationDelay;
        int presentationDelay =
                (int)
                        ((arrayPresentationDelay[2] & 0xff) << 16
                                | (arrayPresentationDelay[1] & 0xff) << 8
                                | (arrayPresentationDelay[0] & 0xff));
        metaData.setPresentationDelayMicros(presentationDelay);
        PeriodicAdvertisementResult result =
                getPeriodicAdvertisementResult(device, getBroadcastIdForSyncHandle(syncHandle));
        if (result != null) {
            int broadcastId = result.getBroadcastId();
            log("broadcast ID: " + broadcastId);
            metaData.setBroadcastId(broadcastId);
            metaData.setSourceAdvertisingSid(result.getAdvSid());

            PublicBroadcastData pbData = result.getPublicBroadcastData();
            if (pbData != null) {
                metaData.setPublicBroadcast(true);
                metaData.setAudioConfigQuality(pbData.getAudioConfigQuality());
                try {
                    metaData.setPublicBroadcastMetadata(
                            BluetoothLeAudioContentMetadata.fromRawBytes(pbData.getMetadata()));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid public metadata, adding empty one. Error " + e);
                    metaData.setPublicBroadcastMetadata(null);
                }
            }

            String broadcastName = result.getBroadcastName();
            if (broadcastName != null) {
                metaData.setBroadcastName(broadcastName);
            }
        }
        metaData.setEncrypted(encrypted);
        if (leaudioBroadcastMonitorSourceSyncStatus()) {
            // update the rssi value
            ScanResult scanRes = getCachedBroadcast(result.getBroadcastId());
            if (scanRes != null) {
                metaData.setRssi(scanRes.getRssi());
            }
        }
        return metaData.build();
    }

    private void cancelActiveSync(Integer syncHandle) {
        log("cancelActiveSync: syncHandle = " + syncHandle);
        if (syncHandle == null) {
            // clean up the pending sync request if syncHandle is null
            unsyncSource(BassConstants.INVALID_SYNC_HANDLE);
        }
        List<Integer> activeSyncedSrc = new ArrayList<>(getActiveSyncedSources());

        /* Stop sync if there is some running */
        if (!activeSyncedSrc.isEmpty()
                && (syncHandle == null || activeSyncedSrc.contains(syncHandle))) {
            if (syncHandle != null) {
                // only one source needs to be unsynced
                unsyncSource(syncHandle);
            } else {
                // remove all the sources
                for (int handle : activeSyncedSrc) {
                    unsyncSource(handle);
                }
            }
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786 - Fix BASS annotation
    private boolean unsyncSource(int syncHandle) {
        log("unsyncSource: syncHandle: " + syncHandle);
        if (mPeriodicAdvCallbacksMap.containsKey(syncHandle)) {
            try {
                BluetoothMethodProxy.getInstance()
                        .periodicAdvertisingManagerUnregisterSync(
                                BassClientPeriodicAdvertisingManager
                                        .getPeriodicAdvertisingManager(),
                                mPeriodicAdvCallbacksMap.get(syncHandle));
            } catch (IllegalArgumentException ex) {
                Log.w(TAG, "unregisterSync:IllegalArgumentException");
                return false;
            }
        } else {
            log("calling unregisterSync, not found syncHandle: " + syncHandle);
        }
        clearAllDataForSyncHandle(syncHandle);
        return true;
    }

    boolean parseBaseData(int syncHandle, byte[] serviceData) {
        log("parseBaseData" + Arrays.toString(serviceData));
        BaseData base = BaseData.parseBaseData(serviceData);
        if (base != null) {
            updateBase(syncHandle, base);
            base.print();
            return true;
        } else {
            Log.e(TAG, "Seems BASE is not in parsable format");
        }
        return false;
    }

    boolean parseScanRecord(int syncHandle, ScanRecord record) {
        int broadcastId = getBroadcastIdForSyncHandle(syncHandle);
        log(
                "parseScanRecord: syncHandle="
                        + syncHandle
                        + ", broadcastID="
                        + broadcastId
                        + ", record="
                        + record);
        Map<ParcelUuid, byte[]> bmsAdvDataMap = record.getServiceData();
        if (bmsAdvDataMap != null) {
            for (Map.Entry<ParcelUuid, byte[]> entry : bmsAdvDataMap.entrySet()) {
                log(
                        "ParcelUUid = "
                                + entry.getKey()
                                + ", Value = "
                                + Arrays.toString(entry.getValue()));
            }
        }
        byte[] advData = record.getServiceData(BassConstants.BASIC_AUDIO_UUID);
        if (advData != null) {
            return parseBaseData(syncHandle, advData);
        } else {
            Log.e(TAG, "No service data in Scan record");
        }
        return false;
    }

    private String checkAndParseBroadcastName(ScanRecord record) {
        log("checkAndParseBroadcastName");
        byte[] rawBytes = record.getBytes();
        List<TypeValueEntry> entries = BluetoothUtils.parseLengthTypeValueBytes(rawBytes);
        if (rawBytes.length > 0 && rawBytes[0] > 0 && entries.isEmpty()) {
            Log.e(TAG, "Invalid LTV entries in Scan record");
            return null;
        }

        String broadcastName = null;
        for (TypeValueEntry entry : entries) {
            // Only use the first value of each type
            if (broadcastName == null && entry.getType() == BassConstants.BCAST_NAME_AD_TYPE) {
                byte[] bytes = entry.getValue();
                int len = bytes.length;
                if (len < BassConstants.BCAST_NAME_LEN_MIN
                        || len > BassConstants.BCAST_NAME_LEN_MAX) {
                    Log.e(TAG, "Invalid broadcast name length in Scan record" + len);
                    return null;
                }
                broadcastName = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return broadcastName;
    }

    void addSelectSourceRequest(ScanResult scanRes, boolean hasPriority) {
        sEventLogger.logd(
                TAG,
                "Add Select Broadcast Source, result: "
                        + scanRes
                        + ", hasPriority: "
                        + hasPriority);

        ScanRecord scanRecord = scanRes.getScanRecord();
        if (scanRecord == null) {
            log("addSelectSourceRequest: ScanRecord empty");
            return;
        }

        synchronized (mSourceSyncRequestsQueue) {
            mSourceSyncRequestsQueue.add(new SourceSyncRequest(scanRes, hasPriority));
        }

        handleSelectSourceRequest();
    }

    @SuppressLint("AndroidFrameworkRequiresPermission") // TODO: b/350563786 - Fix BASS annotation
    private void handleSelectSourceRequest() {
        PeriodicAdvertisingCallback paCb;
        synchronized (mPeriodicAdvCallbacksMap) {
            if (mSourceSyncRequestsQueue.isEmpty()) {
                return;
            } else if (mPeriodicAdvCallbacksMap.containsKey(BassConstants.INVALID_SYNC_HANDLE)) {
                log("handleSelectSourceRequest: already pending sync");
                return;
            } else {
                paCb = new PACallback();
                // put INVALID_SYNC_HANDLE and update in onSyncEstablished
                mPeriodicAdvCallbacksMap.put(BassConstants.INVALID_SYNC_HANDLE, paCb);
            }
        }
        ScanResult scanRes;
        synchronized (mSourceSyncRequestsQueue) {
            scanRes = mSourceSyncRequestsQueue.poll().getScanResult();
        }
        ScanRecord scanRecord = scanRes.getScanRecord();

        sEventLogger.logd(TAG, "Select Broadcast Source, result: " + scanRes);

        // updating mainly for Address type and PA Interval here
        // extract BroadcastId from ScanResult
        Map<ParcelUuid, byte[]> listOfUuids = scanRecord.getServiceData();
        int broadcastId = BassConstants.INVALID_BROADCAST_ID;
        PublicBroadcastData pbData = null;
        if (listOfUuids != null) {
            if (listOfUuids.containsKey(BassConstants.BAAS_UUID)) {
                byte[] bId = listOfUuids.get(BassConstants.BAAS_UUID);
                broadcastId = BassUtils.parseBroadcastId(bId);
            }
            if (listOfUuids.containsKey(BassConstants.PUBLIC_BROADCAST_UUID)) {
                byte[] pbAnnouncement = listOfUuids.get(BassConstants.PUBLIC_BROADCAST_UUID);
                pbData = PublicBroadcastData.parsePublicBroadcastData(pbAnnouncement);
            }
        }

        if (broadcastId == BassConstants.INVALID_BROADCAST_ID) {
            Log.w(TAG, "Invalid broadcast ID");
            mPeriodicAdvCallbacksMap.remove(BassConstants.INVALID_SYNC_HANDLE);
            handleSelectSourceRequest();
            return;
        }

        // Check if broadcast name present in scan record and parse
        // null if no name present
        String broadcastName = checkAndParseBroadcastName(scanRecord);

        // Avoid duplicated sync request if the same broadcast BIG is synced
        List<Integer> activeSyncedSrc = getActiveSyncedSources();
        if (activeSyncedSrc.contains(getSyncHandleForBroadcastId(broadcastId))) {
            log("Skip duplicated sync request to broadcast id: " + broadcastId);
            mPeriodicAdvCallbacksMap.remove(BassConstants.INVALID_SYNC_HANDLE);
            handleSelectSourceRequest();
            return;
        }

        // Check if there are resources for sync
        if (activeSyncedSrc.size() >= MAX_ACTIVE_SYNCED_SOURCES_NUM) {
            log("handleSelectSourceRequest: reached max allowed active source");
            int syncHandle = activeSyncedSrc.get(0);
            // removing the 1st synced source before proceeding to add new
            cancelActiveSync(syncHandle);
        }

        try {
            BluetoothMethodProxy.getInstance()
                    .periodicAdvertisingManagerRegisterSync(
                            BassClientPeriodicAdvertisingManager.getPeriodicAdvertisingManager(),
                            scanRes,
                            0,
                            BassConstants.PSYNC_TIMEOUT,
                            paCb,
                            null);
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "registerSync:IllegalArgumentException");
            mPeriodicAdvCallbacksMap.remove(BassConstants.INVALID_SYNC_HANDLE);
            handleSelectSourceRequest();
            return;
        }

        updateSyncHandleForBroadcastId(BassConstants.INVALID_SYNC_HANDLE, broadcastId);
        updatePeriodicAdvertisementResultMap(
                scanRes.getDevice(),
                scanRes.getDevice().getAddressType(),
                BassConstants.INVALID_SYNC_HANDLE,
                BassConstants.INVALID_ADV_SID,
                scanRes.getPeriodicAdvertisingInterval(),
                broadcastId,
                pbData,
                broadcastName);
    }

    void selectSource(BluetoothDevice sink, ScanResult result, boolean autoTrigger) {
        if (leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            throw new RuntimeException(
                    "Should never be executed with"
                            + " leaudioBroadcastExtractPeriodicScannerFromStateMachine flag");
        }
        List<Integer> activeSyncedSrc = getActiveSyncedSources(sink);
        if (activeSyncedSrc != null && activeSyncedSrc.size() >= MAX_ACTIVE_SYNCED_SOURCES_NUM) {
            log("selectSource : reached max allowed active source");
            int syncHandle = activeSyncedSrc.get(0);
            // removing the 1st synced source before proceeding to add new
            synchronized (mStateMachines) {
                BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
                if (stateMachine == null) {
                    Log.e(TAG, "Can't get state machine for device: " + sink);
                    return;
                }
                Message message =
                        stateMachine.obtainMessage(BassClientStateMachine.REACHED_MAX_SOURCE_LIMIT);
                message.arg1 = syncHandle;
                stateMachine.sendMessage(message);
            }
        }

        synchronized (mStateMachines) {
            sEventLogger.logd(
                    TAG, "Select Broadcast Source: sink: " + sink + ", result: " + result);

            BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
            if (stateMachine == null) {
                Log.e(TAG, "Can't get state machine for device: " + sink);
                return;
            }
            Message message =
                    stateMachine.obtainMessage(BassClientStateMachine.SELECT_BCAST_SOURCE);
            message.obj = result;
            message.arg1 = autoTrigger ? BassConstants.AUTO : BassConstants.USER;
            stateMachine.sendMessage(message);
        }
    }

    /**
     * Add a Broadcast Source to the Broadcast Sink
     *
     * @param sink Broadcast Sink to which the Broadcast Source should be added
     * @param sourceMetadata Broadcast Source metadata to be added to the Broadcast Sink
     * @param isGroupOp set to true If Application wants to perform this operation for all
     *     coordinated set members, False otherwise
     */
    public void addSource(
            BluetoothDevice sink, BluetoothLeBroadcastMetadata sourceMetadata, boolean isGroupOp) {
        log(
                "addSource: "
                        + ("device: " + sink)
                        + (", sourceMetadata: " + sourceMetadata)
                        + (", isGroupOp: " + isGroupOp));

        List<BluetoothDevice> devices = getTargetDeviceList(sink, isGroupOp);
        // Don't coordinate it as a group if there's no group or there is one device only
        if (devices.size() < 2) {
            isGroupOp = false;
        }

        if (sourceMetadata == null) {
            log("addSource: Error bad parameter: sourceMetadata cannot be null");
            return;
        }

        if (leaudioBroadcastAssistantPeripheralEntrustment()) {
            if (isLocalBroadcast(sourceMetadata)) {
                LeAudioService leAudioService = mServiceFactory.getLeAudioService();
                if (leaudioBigDependsOnAudioState()) {
                    if (leAudioService == null
                            || !(leAudioService.isPaused(sourceMetadata.getBroadcastId())
                                    || leAudioService.isPlaying(sourceMetadata.getBroadcastId()))) {
                        Log.w(TAG, "addSource: Local source can't be add");

                        mCallbacks.notifySourceAddFailed(
                                sink,
                                sourceMetadata,
                                BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES);

                        return;
                    }
                } else {
                    if (leAudioService == null
                            || !leAudioService.isPlaying(sourceMetadata.getBroadcastId())) {
                        Log.w(TAG, "addSource: Local source can't be add");

                        mCallbacks.notifySourceAddFailed(
                                sink,
                                sourceMetadata,
                                BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES);

                        return;
                    }
                }
            }
        } else {
            if (!isAllowedToAddSource()) {
                Log.d(TAG, "Add source to pending list");
                mPendingAddSources.push(new AddSourceData(sink, sourceMetadata, isGroupOp));

                return;
            }
        }

        if (leaudioBroadcastExtractPeriodicScannerFromStateMachine()) {
            List<Integer> activeSyncedSrc = getActiveSyncedSources();
            BluetoothDevice sourceDevice = sourceMetadata.getSourceDevice();
            if (!isLocalBroadcast(sourceMetadata)
                    && (!activeSyncedSrc.contains(
                            getSyncHandleForBroadcastId(sourceMetadata.getBroadcastId())))) {
                log("Adding inactive source: " + sourceDevice);
                int broadcastId = sourceMetadata.getBroadcastId();
                if (broadcastId != BassConstants.INVALID_BROADCAST_ID
                        && getCachedBroadcast(broadcastId) != null) {
                    // If the source has been synced before, try to re-sync
                    // with the source by previously cached scan result
                    addSelectSourceRequest(getCachedBroadcast(broadcastId), true);
                    synchronized (mPendingSourcesToAdd) {
                        mPendingSourcesToAdd.add(
                                new AddSourceData(sink, sourceMetadata, isGroupOp));
                    }
                } else {
                    log("AddSource: broadcast not cached or invalid, broadcastId: " + broadcastId);
                    mCallbacks.notifySourceAddFailed(
                            sink, sourceMetadata, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
                }
                return;
            }
        }

        byte[] code = sourceMetadata.getBroadcastCode();
        for (BluetoothDevice device : devices) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);
            if (stateMachine == null) {
                log("addSource: Error bad parameter: no state machine for " + device);
                mCallbacks.notifySourceAddFailed(
                        device, sourceMetadata, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
                continue;
            }
            if (getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
                log("addSource: device is not connected");
                mCallbacks.notifySourceAddFailed(
                        device, sourceMetadata, BluetoothStatusCodes.ERROR_REMOTE_LINK_ERROR);
                continue;
            }
            if (stateMachine.hasPendingSourceOperation()) {
                Log.w(
                        TAG,
                        "addSource: source operation already pending, device: "
                                + device
                                + ", broadcastId: "
                                + sourceMetadata.getBroadcastId());
                mCallbacks.notifySourceAddFailed(
                        device, sourceMetadata, BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
                continue;
            }
            if (!hasRoomForBroadcastSourceAddition(device)) {
                log("addSource: device has no room");
                Integer sourceId = getSourceIdToRemove(device);
                if (sourceId != BassConstants.INVALID_SOURCE_ID) {
                    sEventLogger.logd(
                            TAG,
                            "Switch Broadcast Source: "
                                    + ("device: " + device)
                                    + (", old SourceId: " + sourceId)
                                    + (", new broadcastId: " + sourceMetadata.getBroadcastId())
                                    + (", new broadcastName: "
                                            + sourceMetadata.getBroadcastName()));

                    // new source will be added once the existing source got removed
                    if (isGroupOp) {
                        // mark group op for both remove and add source
                        // so setSourceGroupManaged will be updated accordingly in callbacks
                        enqueueSourceGroupOp(
                                device, BassClientStateMachine.REMOVE_BCAST_SOURCE, sourceId);
                        enqueueSourceGroupOp(
                                device, BassClientStateMachine.ADD_BCAST_SOURCE, sourceMetadata);
                    }

                    /* Store metadata for sink device */
                    mBroadcastMetadataMap.put(device, sourceMetadata);

                    Message message =
                            stateMachine.obtainMessage(BassClientStateMachine.SWITCH_BCAST_SOURCE);
                    message.obj = sourceMetadata;
                    message.arg1 = sourceId;
                    stateMachine.sendMessage(message);
                } else {
                    mCallbacks.notifySourceAddFailed(
                            device,
                            sourceMetadata,
                            BluetoothStatusCodes.ERROR_REMOTE_NOT_ENOUGH_RESOURCES);
                }
                continue;
            }
            if (!isValidBroadcastSourceAddition(device, sourceMetadata)) {
                log("addSource: not a valid broadcast source addition");
                mCallbacks.notifySourceAddFailed(
                        device,
                        sourceMetadata,
                        BluetoothStatusCodes.ERROR_LE_BROADCAST_ASSISTANT_DUPLICATE_ADDITION);
                continue;
            }
            if ((code != null) && (code.length != 0)) {
                if ((code.length > 16) || (code.length < 4)) {
                    log(
                            "Invalid broadcast code length: "
                                    + code.length
                                    + ", should be between 4 and 16 octets");
                    mCallbacks.notifySourceAddFailed(
                            device, sourceMetadata, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
                    continue;
                }
            }

            /* Store metadata for sink device */
            mBroadcastMetadataMap.put(device, sourceMetadata);

            if (isGroupOp) {
                enqueueSourceGroupOp(
                        device, BassClientStateMachine.ADD_BCAST_SOURCE, sourceMetadata);
            }

            if (!isLocalBroadcast(sourceMetadata)) {
                checkAndSetGroupAllowedContextMask(device);
            }

            sEventLogger.logd(
                    TAG,
                    "Add Broadcast Source: "
                            + ("device: " + device)
                            + (", broadcastId: " + sourceMetadata.getBroadcastId())
                            + (", broadcastName: " + sourceMetadata.getBroadcastName())
                            + (", isGroupOp: " + isGroupOp));

            Message message = stateMachine.obtainMessage(BassClientStateMachine.ADD_BCAST_SOURCE);
            message.obj = sourceMetadata;
            stateMachine.sendMessage(message);
            if (code != null && code.length != 0) {
                sEventLogger.logd(
                        TAG,
                        "Set Broadcast Code (Add Source context): "
                                + ("device: " + device)
                                + (", broadcastId: " + sourceMetadata.getBroadcastId())
                                + (", broadcastName: " + sourceMetadata.getBroadcastName()));

                message = stateMachine.obtainMessage(BassClientStateMachine.SET_BCAST_CODE);
                message.obj = sourceMetadata;
                message.arg1 = BassClientStateMachine.ARGTYPE_METADATA;
                stateMachine.sendMessage(message);
            }
        }
    }

    /**
     * Modify the Broadcast Source information on a Broadcast Sink
     *
     * @param sink representing the Broadcast Sink to which the Broadcast Source should be updated
     * @param sourceId source ID as delivered in onSourceAdded
     * @param updatedMetadata updated Broadcast Source metadata to be updated on the Broadcast Sink
     */
    public void modifySource(
            BluetoothDevice sink, int sourceId, BluetoothLeBroadcastMetadata updatedMetadata) {
        log(
                "modifySource: "
                        + ("device: " + sink)
                        + ("sourceId: " + sourceId)
                        + (", updatedMetadata: " + updatedMetadata));

        Map<BluetoothDevice, Integer> devices = getGroupManagedDeviceSources(sink, sourceId).second;
        if (updatedMetadata == null) {
            log("modifySource: Error bad parameters: updatedMetadata cannot be null");
            for (BluetoothDevice device : devices.keySet()) {
                mCallbacks.notifySourceModifyFailed(
                        device, sourceId, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
            }
            return;
        }

        /* Update metadata for sink device */
        mBroadcastMetadataMap.put(sink, updatedMetadata);

        byte[] code = updatedMetadata.getBroadcastCode();
        for (Map.Entry<BluetoothDevice, Integer> deviceSourceIdPair : devices.entrySet()) {
            BluetoothDevice device = deviceSourceIdPair.getKey();
            Integer deviceSourceId = deviceSourceIdPair.getValue();
            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);

            int statusCode =
                    areValidParametersToModifySource(
                            updatedMetadata, stateMachine, deviceSourceId, device);
            if (statusCode != BluetoothStatusCodes.SUCCESS) {
                mCallbacks.notifySourceModifyFailed(device, sourceId, statusCode);
                continue;
            }

            sEventLogger.logd(
                    TAG,
                    "Modify Broadcast Source: "
                            + ("device: " + device)
                            + ("sourceId: " + sourceId)
                            + (", updatedBroadcastId: " + updatedMetadata.getBroadcastId())
                            + (", updatedBroadcastName: " + updatedMetadata.getBroadcastName()));

            Message message =
                    stateMachine.obtainMessage(BassClientStateMachine.UPDATE_BCAST_SOURCE);
            message.arg1 = deviceSourceId;
            message.arg2 = BassConstants.INVALID_PA_SYNC_VALUE;
            message.obj = updatedMetadata;
            stateMachine.sendMessage(message);
            if (code != null && code.length != 0) {
                sEventLogger.logd(
                        TAG,
                        "Set Broadcast Code (Modify Source context): "
                                + ("device: " + device)
                                + ("sourceId: " + sourceId)
                                + (", updatedBroadcastId: " + updatedMetadata.getBroadcastId())
                                + (", updatedBroadcastName: "
                                        + updatedMetadata.getBroadcastName()));
                message = stateMachine.obtainMessage(BassClientStateMachine.SET_BCAST_CODE);
                message.obj = updatedMetadata;
                message.arg1 = BassClientStateMachine.ARGTYPE_METADATA;
                stateMachine.sendMessage(message);
            }
        }
    }

    /**
     * Removes the Broadcast Source from a Broadcast Sink
     *
     * @param sink representing the Broadcast Sink from which a Broadcast Source should be removed
     * @param sourceId source ID as delivered in onSourceAdded
     */
    public void removeSource(BluetoothDevice sink, int sourceId) {
        log("removeSource: device: " + sink + ", sourceId: " + sourceId);

        Map<BluetoothDevice, Integer> devices = getGroupManagedDeviceSources(sink, sourceId).second;
        for (Map.Entry<BluetoothDevice, Integer> deviceSourceIdPair : devices.entrySet()) {
            BluetoothDevice device = deviceSourceIdPair.getKey();
            Integer deviceSourceId = deviceSourceIdPair.getValue();
            BassClientStateMachine stateMachine = getOrCreateStateMachine(device);

            /* Removes metadata for sink device if not paused */
            if (!mPausedBroadcastSinks.contains(device)) {
                mBroadcastMetadataMap.remove(device);
            }

            if (stateMachine == null) {
                log("removeSource: Error bad parameters: device = " + device);
                mCallbacks.notifySourceRemoveFailed(
                        device, sourceId, BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
                continue;
            }
            if (deviceSourceId == BassConstants.INVALID_SOURCE_ID) {
                log("removeSource: no such sourceId for device: " + device);
                mCallbacks.notifySourceRemoveFailed(
                        device,
                        sourceId,
                        BluetoothStatusCodes.ERROR_LE_BROADCAST_ASSISTANT_INVALID_SOURCE_ID);
                continue;
            }
            if (getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
                log("removeSource: device is not connected");
                mCallbacks.notifySourceRemoveFailed(
                        device, sourceId, BluetoothStatusCodes.ERROR_REMOTE_LINK_ERROR);
                continue;
            }

            BluetoothLeBroadcastMetadata metaData =
                    stateMachine.getCurrentBroadcastMetadata(sourceId);
            if (metaData != null && stateMachine.isSyncedToTheSource(sourceId)) {
                sEventLogger.logd(
                        TAG,
                        "Remove Broadcast Source(Force lost PA sync): "
                                + ("device: " + device)
                                + (", sourceId: " + sourceId)
                                + (", broadcastId: " + metaData.getBroadcastId())
                                + (", broadcastName: " + metaData.getBroadcastName()));

                log("Force source to lost PA sync");
                Message message =
                        stateMachine.obtainMessage(BassClientStateMachine.UPDATE_BCAST_SOURCE);
                message.arg1 = sourceId;
                message.arg2 = BassConstants.PA_SYNC_DO_NOT_SYNC;
                /* Pending remove set. Remove source once not synchronized to PA */
                message.obj = metaData;
                stateMachine.sendMessage(message);

                continue;
            }

            sEventLogger.logd(
                    TAG, "Remove Broadcast Source: device: " + device + ", sourceId: " + sourceId);

            Message message =
                    stateMachine.obtainMessage(BassClientStateMachine.REMOVE_BCAST_SOURCE);
            message.arg1 = deviceSourceId;
            stateMachine.sendMessage(message);
        }

        for (Map.Entry<BluetoothDevice, Integer> deviceSourceIdPair : devices.entrySet()) {
            BluetoothDevice device = deviceSourceIdPair.getKey();
            Integer deviceSourceId = deviceSourceIdPair.getValue();
            enqueueSourceGroupOp(
                    device,
                    BassClientStateMachine.REMOVE_BCAST_SOURCE,
                    Integer.valueOf(deviceSourceId));
        }
    }

    /**
     * Get information about all Broadcast Sources
     *
     * @param sink Broadcast Sink from which to get all Broadcast Sources
     * @return the list of Broadcast Receive State {@link BluetoothLeBroadcastReceiveState}
     */
    public List<BluetoothLeBroadcastReceiveState> getAllSources(BluetoothDevice sink) {
        log("getAllSources for " + sink);
        synchronized (mStateMachines) {
            BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
            if (stateMachine == null) {
                log("stateMachine is null");
                return Collections.emptyList();
            }
            List<BluetoothLeBroadcastReceiveState> recvStates =
                    new ArrayList<BluetoothLeBroadcastReceiveState>();
            for (BluetoothLeBroadcastReceiveState rs : stateMachine.getAllSources()) {
                if (!isEmptyBluetoothDevice(rs.getSourceDevice())) {
                    recvStates.add(rs);
                }
            }
            return recvStates;
        }
    }

    /**
     * Get maximum number of sources that can be added to this Broadcast Sink
     *
     * @param sink Broadcast Sink device
     * @return maximum number of sources that can be added to this Broadcast Sink
     */
    int getMaximumSourceCapacity(BluetoothDevice sink) {
        log("getMaximumSourceCapacity: device = " + sink);
        BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
        if (stateMachine == null) {
            log("stateMachine is null");
            return 0;
        }
        return stateMachine.getMaximumSourceCapacity();
    }

    private boolean isLocalBroadcast(int sourceAdvertisingSid) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            return false;
        }

        boolean wasFound =
                leAudioService.getAllBroadcastMetadata().stream()
                        .anyMatch(
                                meta -> {
                                    return meta.getSourceAdvertisingSid() == sourceAdvertisingSid;
                                });
        log("isLocalBroadcast=" + wasFound);
        return wasFound;
    }

    boolean isLocalBroadcast(BluetoothLeBroadcastMetadata metaData) {
        if (metaData == null) {
            return false;
        }

        return isLocalBroadcast(metaData.getSourceAdvertisingSid());
    }

    boolean isLocalBroadcast(BluetoothLeBroadcastReceiveState receiveState) {
        if (receiveState == null) {
            return false;
        }

        return isLocalBroadcast(receiveState.getSourceAdvertisingSid());
    }

    static void log(String msg) {
        Log.d(TAG, msg);
    }

    private List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>>
            getReceiveStateDevicePairs(int broadcastId) {
        List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> list = new ArrayList<>();

        for (BluetoothDevice device : getConnectedDevices()) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                /* Check if local/last broadcast is the synced one. Invalid broadcast ID means
                 * that all receivers should be considered.
                 */
                if ((broadcastId != BassConstants.INVALID_BROADCAST_ID)
                        && (receiveState.getBroadcastId() != broadcastId)) {
                    continue;
                }

                list.add(
                        new Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>(
                                receiveState, device));
            }
        }

        return list;
    }

    private void cancelPendingSourceOperations(int broadcastId) {
        for (BluetoothDevice device : getConnectedDevices()) {
            synchronized (mStateMachines) {
                BassClientStateMachine sm = getOrCreateStateMachine(device);
                if (sm != null && sm.hasPendingSourceOperation(broadcastId)) {
                    Message message =
                            sm.obtainMessage(
                                    BassClientStateMachine.CANCEL_PENDING_SOURCE_OPERATION);
                    message.arg1 = broadcastId;
                    sm.sendMessage(message);
                }
            }
        }
    }

    private void stopSourceReceivers(int broadcastId) {
        List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> sourcesToRemove =
                getReceiveStateDevicePairs(broadcastId);

        for (Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice> pair : sourcesToRemove) {
            removeSource(pair.second, pair.first.getSourceId());
        }

        /* There may be some pending add/modify source operations */
        cancelPendingSourceOperations(broadcastId);
    }

    private void stopSourceReceivers(int broadcastId, boolean store) {
        Log.d(TAG, "stopSourceReceivers(), broadcastId: " + broadcastId + ", store: " + store);

        if (store && !mPausedBroadcastSinks.isEmpty()) {
            Log.w(TAG, "stopSourceReceivers(), paused broadcast sinks are replaced");
            sEventLogger.logd(TAG, "Clear broadcast sinks paused cache");
            mPausedBroadcastSinks.clear();
        }

        Map<BluetoothDevice, Integer> sourcesToRemove = new HashMap<>();

        for (BluetoothDevice device : getConnectedDevices()) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                /* Check if local/last broadcast is the synced one. Invalid broadcast ID means
                 * that all receivers should be considered.
                 */
                if ((broadcastId != BassConstants.INVALID_BROADCAST_ID)
                        && (receiveState.getBroadcastId() != broadcastId)) {
                    continue;
                }

                if (store && !mPausedBroadcastSinks.contains(device)) {
                    sEventLogger.logd(TAG, "Add broadcast sink to paused cache: " + device);
                    mPausedBroadcastSinks.add(device);
                }

                sourcesToRemove.put(device, receiveState.getSourceId());
            }
        }

        for (Map.Entry<BluetoothDevice, Integer> entry : sourcesToRemove.entrySet()) {
            removeSource(entry.getKey(), entry.getValue());
        }
    }

    private boolean isAllowedToAddSource() {
        if (leaudioBroadcastAudioHandoverPolicies()) {
            /* Check if should wait for status update */
            if (mUnicastSourceStreamStatus.isEmpty()) {
                /* Assistant was not active, inform about activation */
                if (!mIsAssistantActive) {
                    mIsAssistantActive = true;

                    LeAudioService leAudioService = mServiceFactory.getLeAudioService();
                    if (leAudioService != null) {
                        leAudioService.activeBroadcastAssistantNotification(true);
                    }
                }

                return false;
            }

            return mUnicastSourceStreamStatus.get() == STATUS_LOCAL_STREAM_SUSPENDED;
        }

        /* Don't block if this is not a handover case */
        return true;
    }

    /** Return true if there is any non primary device receiving broadcast */
    private boolean isAudioSharingModeOn(Integer broadcastId) {
        if (mLocalBroadcastReceivers == null) {
            Log.w(TAG, "isAudioSharingModeOn: Local Broadcaster Receivers is not initialized");
            return false;
        }

        HashSet<BluetoothDevice> devices = mLocalBroadcastReceivers.get(broadcastId);
        if (devices == null) {
            Log.w(TAG, "isAudioSharingModeOn: No receivers receiving broadcast: " + broadcastId);
            return false;
        }

        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            Log.d(TAG, "isAudioSharingModeOn: No available LeAudioService");
            return false;
        }

        return devices.stream().anyMatch(d -> !leAudioService.isPrimaryDevice(d));
    }

    /** Handle disconnection of potential broadcast sinks */
    public void handleDeviceDisconnection(BluetoothDevice sink, boolean isIntentional) {
        LeAudioService leAudioService = mServiceFactory.getLeAudioService();
        if (leAudioService == null) {
            Log.d(TAG, "BluetoothLeBroadcastReceiveState: No available LeAudioService");
            return;
        }

        Iterator<Map.Entry<Integer, HashSet<BluetoothDevice>>> iterator =
                mLocalBroadcastReceivers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, HashSet<BluetoothDevice>> entry = iterator.next();
            Integer broadcastId = entry.getKey();
            HashSet<BluetoothDevice> devices = entry.getValue();

            if (leaudioBigDependsOnAudioState()) {
                /* If somehow there is a non configured/playing broadcast, let's remove it */
                if (!(leAudioService.isPaused(broadcastId)
                        || leAudioService.isPlaying(broadcastId))) {
                    Log.w(TAG, "Non playing broadcast remove from receivers list");
                    iterator.remove();
                    continue;
                }
            } else {
                /* If somehow there is a non playing broadcast, let's remove it */
                if (!leAudioService.isPlaying(broadcastId)) {
                    Log.w(TAG, "Non playing broadcast remove from receivers list");
                    iterator.remove();
                    continue;
                }
            }

            if (isIntentional) {
                /* Check if disconnecting device participated in this broadcast reception */
                if (!devices.remove(sink)) {
                    continue;
                }

                mBroadcastMetadataMap.remove(sink);

                /* Check if there is any other primary device receiving this broadcast */
                if (devices.stream()
                        .anyMatch(
                                d ->
                                        ((getConnectionState(d) == BluetoothProfile.STATE_CONNECTED)
                                                && leAudioService.isPrimaryDevice(d)))) {
                    continue;
                }

                Log.d(
                        TAG,
                        "handleIntendedDeviceDisconnection: No more potential broadcast "
                                + "(broadcast ID: "
                                + broadcastId
                                + ") receivers - stopping broadcast");
                iterator.remove();
                leAudioService.stopBroadcast(broadcastId);
            } else {
                /* Unintentional disconnection of primary device in private broadcast mode */
                if (!isAudioSharingModeOn(broadcastId)
                        && !devices.stream()
                                .anyMatch(
                                        d ->
                                                !d.equals(sink)
                                                        && (getConnectionState(d)
                                                                == BluetoothProfile
                                                                        .STATE_CONNECTED))) {
                    iterator.remove();
                    leAudioService.stopBroadcast(broadcastId);
                    continue;
                }

                /* Unintentional disconnection of primary/secondary in broadcast sharing mode */
                if (devices.stream()
                        .anyMatch(
                                d ->
                                        !d.equals(sink)
                                                && (getConnectionState(d)
                                                        == BluetoothProfile.STATE_CONNECTED))) {
                    continue;
                } else {
                    Log.d(
                            TAG,
                            "handleUnintendedDeviceDisconnection: No more potential broadcast "
                                    + "(broadcast ID: "
                                    + broadcastId
                                    + ") receivers - stopping broadcast");
                    mDialingOutTimeoutEvent = new DialingOutTimeoutEvent(broadcastId);
                    mHandler.postDelayed(mDialingOutTimeoutEvent, DIALING_OUT_TIMEOUT_MS);
                }
            }
        }
    }

    /** Cache suspending sources */
    public void cacheSuspendingSources(int broadcastId) {
        sEventLogger.logd(TAG, "Cache suspending sources: " + broadcastId);
        List<Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice>> sourcesToCache =
                getReceiveStateDevicePairs(broadcastId);

        if (!mPausedBroadcastSinks.isEmpty()) {
            Log.w(TAG, "cacheSuspendingSources(), paused broadcast sinks are replaced");
            sEventLogger.logd(TAG, "Clear broadcast sinks paused cache");
            mPausedBroadcastSinks.clear();
        }

        for (Pair<BluetoothLeBroadcastReceiveState, BluetoothDevice> pair : sourcesToCache) {
            mPausedBroadcastSinks.add(pair.second);
        }
    }

    /** Request receivers to suspend broadcast sources synchronization */
    public void suspendReceiversSourceSynchronization(int broadcastId) {
        sEventLogger.logd(TAG, "Suspend receivers source synchronization: " + broadcastId);
        stopSourceReceivers(broadcastId, true);
    }

    /** Request all receivers to suspend broadcast sources synchronization */
    public void suspendAllReceiversSourceSynchronization() {
        sEventLogger.logd(TAG, "Suspend all receivers source synchronization");
        stopSourceReceivers(BassConstants.INVALID_BROADCAST_ID, true);
    }

    /** Request receivers to stop broadcast sources synchronization and remove them */
    public void stopReceiversSourceSynchronization(int broadcastId) {
        sEventLogger.logd(TAG, "Stop receivers source synchronization: " + broadcastId);
        if (leaudioBroadcastAssistantPeripheralEntrustment()) {
            stopSourceReceivers(broadcastId);
        } else {
            stopSourceReceivers(broadcastId, false);
        }
    }

    /** Request receivers to resume broadcast source synchronization */
    public void resumeReceiversSourceSynchronization() {
        sEventLogger.logd(TAG, "Resume receivers source synchronization");

        while (!mPausedBroadcastSinks.isEmpty()) {
            BluetoothDevice sink = mPausedBroadcastSinks.remove();
            sEventLogger.logd(TAG, "Remove broadcast sink from paused cache: " + sink);
            BluetoothLeBroadcastMetadata metadata = mBroadcastMetadataMap.get(sink);

            if (leaudioBroadcastAssistantPeripheralEntrustment()) {
                if (metadata == null) {
                    Log.w(
                            TAG,
                            "resumeReceiversSourceSynchronization: failed to get metadata to resume"
                                    + " sink: "
                                    + sink);
                    continue;
                }

                // For each device, find the source ID having this broadcast ID
                BassClientStateMachine stateMachine = getOrCreateStateMachine(sink);
                List<BluetoothLeBroadcastReceiveState> sources = stateMachine.getAllSources();
                Optional<BluetoothLeBroadcastReceiveState> receiveState =
                        sources.stream()
                                .filter(e -> e.getBroadcastId() == metadata.getBroadcastId())
                                .findAny();

                if (receiveState.isPresent()) {
                    /* Update metadata for sink device */
                    mBroadcastMetadataMap.put(sink, metadata);

                    int sourceId = receiveState.get().getSourceId();
                    int statusCode =
                            areValidParametersToModifySource(
                                    metadata, stateMachine, sourceId, sink);

                    if (statusCode != BluetoothStatusCodes.SUCCESS) {
                        mCallbacks.notifySourceModifyFailed(sink, sourceId, statusCode);
                        continue;
                    }

                    sEventLogger.logd(
                            TAG,
                            "Modify Broadcast Source (resume): "
                                    + ("device: " + sink)
                                    + ("sourceId: " + sourceId)
                                    + (", updatedBroadcastId: " + metadata.getBroadcastId())
                                    + (", updatedBroadcastName: " + metadata.getBroadcastName()));
                    Message message =
                            stateMachine.obtainMessage(BassClientStateMachine.UPDATE_BCAST_SOURCE);
                    message.arg1 = sourceId;
                    message.arg2 =
                            DeviceConfig.getBoolean(
                                            DeviceConfig.NAMESPACE_BLUETOOTH,
                                            "persist.vendor.service.bt.defNoPAS",
                                            false)
                                    ? BassConstants.PA_SYNC_PAST_NOT_AVAILABLE
                                    : BassConstants.PA_SYNC_PAST_AVAILABLE;
                    message.obj = metadata;
                    stateMachine.sendMessage(message);
                } else {
                    addSource(sink, metadata, false);
                }
            } else {
                if (metadata != null) {
                    addSource(sink, metadata, false);
                } else {
                    Log.w(
                            TAG,
                            "resumeReceiversSourceSynchronization: failed to get metadata to resume"
                                    + " sink: "
                                    + sink);
                }
            }
        }
    }

    /** Handle Unicast source stream status change */
    public void handleUnicastSourceStreamStatusChange(int status) {
        mUnicastSourceStreamStatus = Optional.of(status);

        if (status == STATUS_LOCAL_STREAM_REQUESTED) {
            if (areReceiversReceivingOnlyExternalBroadcast(getConnectedDevices())) {
                if (leaudioBroadcastAssistantPeripheralEntrustment()) {
                    cacheSuspendingSources(BassConstants.INVALID_BROADCAST_ID);
                } else {
                    suspendAllReceiversSourceSynchronization();
                }
            }
        } else if (status == STATUS_LOCAL_STREAM_SUSPENDED) {
            /* Resume paused receivers if there are some */
            if (!mPausedBroadcastSinks.isEmpty()) {
                resumeReceiversSourceSynchronization();
            }

            if (!leaudioBroadcastAssistantPeripheralEntrustment()) {
                /* Add pending sources if there are some */
                while (!mPendingAddSources.isEmpty()) {
                    AddSourceData addSourceData = mPendingAddSources.pop();

                    addSource(
                            addSourceData.mSink,
                            addSourceData.mSourceMetadata,
                            addSourceData.mIsGroupOp);
                }
            }
        } else if (status == STATUS_LOCAL_STREAM_STREAMING) {
            Log.d(TAG, "Ignore STREAMING source status");
        } else if (status == STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE) {
            suspendAllReceiversSourceSynchronization();
        }
    }

    /** Check if any sink receivers are receiving broadcast stream */
    public boolean isAnyReceiverReceivingBroadcast(List<BluetoothDevice> devices) {
        for (BluetoothDevice device : devices) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                for (int i = 0; i < receiveState.getNumSubgroups(); i++) {
                    Long syncState = receiveState.getBisSyncState().get(i);
                    /* Synced to BIS */
                    if (syncState != BassConstants.BIS_SYNC_NOT_SYNC_TO_BIS
                            && syncState != BassConstants.BIS_SYNC_FAILED_SYNC_TO_BIG) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** Check if any sink receivers are receiving broadcast stream */
    public boolean areReceiversReceivingOnlyExternalBroadcast(List<BluetoothDevice> devices) {
        boolean isReceivingExternalBroadcast = false;

        for (BluetoothDevice device : devices) {
            for (BluetoothLeBroadcastReceiveState receiveState : getAllSources(device)) {
                for (int i = 0; i < receiveState.getNumSubgroups(); i++) {
                    Long syncState = receiveState.getBisSyncState().get(i);
                    /* Synced to BIS */
                    if (syncState != BassConstants.BIS_SYNC_NOT_SYNC_TO_BIS
                            && syncState != BassConstants.BIS_SYNC_FAILED_SYNC_TO_BIG) {
                        if (isLocalBroadcast(receiveState)) {
                            return false;
                        } else {
                            isReceivingExternalBroadcast = true;
                        }
                    }

                }
            }
        }

        return isReceivingExternalBroadcast;
    }

    /** Get the active broadcast sink devices receiving broadcast stream */
    public List<BluetoothDevice> getActiveBroadcastSinks() {
        List<BluetoothDevice> activeSinks = new ArrayList<>();

        for (BluetoothDevice device : getConnectedDevices()) {
            // Check if any device's source in active sync state
            if (getAllSources(device).stream()
                    .anyMatch(
                            receiveState ->
                                    (receiveState.getBisSyncState().stream()
                                            .anyMatch(
                                                    syncState ->
                                                            syncState
                                                                            != BassConstants
                                                                                    .BIS_SYNC_NOT_SYNC_TO_BIS
                                                                    && syncState
                                                                            != BassConstants
                                                                                    .BIS_SYNC_FAILED_SYNC_TO_BIG)))) {
                activeSinks.add(device);
            }
        }
        return activeSinks;
    }

    /** Handle broadcast state changed */
    public void notifyBroadcastStateChanged(int state, int broadcastId) {
        switch (state) {
            case BROADCAST_STATE_STOPPED:
                if (mLocalBroadcastReceivers == null) {
                    Log.e(TAG, "notifyBroadcastStateChanged: mLocalBroadcastReceivers is invalid");
                    break;
                }

                if (mLocalBroadcastReceivers.remove(broadcastId) != null) {
                    sEventLogger.logd(TAG, "Broadcast ID: " + broadcastId + ", stopped");
                }
                break;
            case BROADCAST_STATE_CONFIGURING:
            case BROADCAST_STATE_PAUSED:
            case BROADCAST_STATE_STOPPING:
            case BROADCAST_STATE_STREAMING:
            default:
                break;
        }
    }

    /** Callback handler */
    static class Callbacks extends Handler {
        private static final int MSG_SEARCH_STARTED = 1;
        private static final int MSG_SEARCH_STARTED_FAILED = 2;
        private static final int MSG_SEARCH_STOPPED = 3;
        private static final int MSG_SEARCH_STOPPED_FAILED = 4;
        private static final int MSG_SOURCE_FOUND = 5;
        private static final int MSG_SOURCE_ADDED = 6;
        private static final int MSG_SOURCE_ADDED_FAILED = 7;
        private static final int MSG_SOURCE_MODIFIED = 8;
        private static final int MSG_SOURCE_MODIFIED_FAILED = 9;
        private static final int MSG_SOURCE_REMOVED = 10;
        private static final int MSG_SOURCE_REMOVED_FAILED = 11;
        private static final int MSG_RECEIVESTATE_CHANGED = 12;
        private static final int MSG_SOURCE_LOST = 13;

        @GuardedBy("mCallbacksList")
        private final RemoteCallbackList<IBluetoothLeBroadcastAssistantCallback> mCallbacksList =
                new RemoteCallbackList<>();

        Callbacks(Looper looper) {
            super(looper);
        }

        public void register(IBluetoothLeBroadcastAssistantCallback callback) {
            synchronized (mCallbacksList) {
                mCallbacksList.register(callback);
            }
        }

        public void unregister(IBluetoothLeBroadcastAssistantCallback callback) {
            synchronized (mCallbacksList) {
                mCallbacksList.unregister(callback);
            }
        }

        private void checkForPendingGroupOpRequest(Message msg) {
            if (sService == null) {
                Log.e(TAG, "Service is null");
                return;
            }

            final int reason = msg.arg1;
            BluetoothDevice sink;

            switch (msg.what) {
                case MSG_SOURCE_ADDED:
                case MSG_SOURCE_ADDED_FAILED:
                    ObjParams param = (ObjParams) msg.obj;
                    sink = (BluetoothDevice) param.mObj1;
                    sService.checkForPendingGroupOpRequest(
                            sink, reason, BassClientStateMachine.ADD_BCAST_SOURCE, param.mObj2);
                    break;
                case MSG_SOURCE_REMOVED:
                case MSG_SOURCE_REMOVED_FAILED:
                    sink = (BluetoothDevice) msg.obj;
                    sService.checkForPendingGroupOpRequest(
                            sink,
                            reason,
                            BassClientStateMachine.REMOVE_BCAST_SOURCE,
                            Integer.valueOf(msg.arg2));
                    break;
                default:
                    break;
            }
        }

        @Override
        public void handleMessage(Message msg) {
            checkForPendingGroupOpRequest(msg);

            synchronized (mCallbacksList) {
                final int n = mCallbacksList.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    final IBluetoothLeBroadcastAssistantCallback callback =
                            mCallbacksList.getBroadcastItem(i);
                    try {
                        invokeCallback(callback, msg);
                    } catch (RemoteException e) {
                        continue;
                    }
                }
                mCallbacksList.finishBroadcast();
            }
        }

        private static class ObjParams {
            Object mObj1;
            Object mObj2;

            ObjParams(Object o1, Object o2) {
                mObj1 = o1;
                mObj2 = o2;
            }
        }

        private void invokeCallback(IBluetoothLeBroadcastAssistantCallback callback, Message msg)
                throws RemoteException {
            final int reason = msg.arg1;
            final int sourceId = msg.arg2;
            ObjParams param;
            BluetoothDevice sink;

            switch (msg.what) {
                case MSG_SEARCH_STARTED:
                    callback.onSearchStarted(reason);
                    break;
                case MSG_SEARCH_STARTED_FAILED:
                    callback.onSearchStartFailed(reason);
                    break;
                case MSG_SEARCH_STOPPED:
                    callback.onSearchStopped(reason);
                    break;
                case MSG_SEARCH_STOPPED_FAILED:
                    callback.onSearchStopFailed(reason);
                    break;
                case MSG_SOURCE_FOUND:
                    callback.onSourceFound((BluetoothLeBroadcastMetadata) msg.obj);
                    break;
                case MSG_SOURCE_ADDED:
                    param = (ObjParams) msg.obj;
                    sink = (BluetoothDevice) param.mObj1;
                    callback.onSourceAdded(sink, sourceId, reason);
                    break;
                case MSG_SOURCE_ADDED_FAILED:
                    param = (ObjParams) msg.obj;
                    sink = (BluetoothDevice) param.mObj1;
                    BluetoothLeBroadcastMetadata metadata =
                            (BluetoothLeBroadcastMetadata) param.mObj2;
                    callback.onSourceAddFailed(sink, metadata, reason);
                    break;
                case MSG_SOURCE_MODIFIED:
                    callback.onSourceModified((BluetoothDevice) msg.obj, sourceId, reason);
                    break;
                case MSG_SOURCE_MODIFIED_FAILED:
                    callback.onSourceModifyFailed((BluetoothDevice) msg.obj, sourceId, reason);
                    break;
                case MSG_SOURCE_REMOVED:
                    sink = (BluetoothDevice) msg.obj;
                    callback.onSourceRemoved(sink, sourceId, reason);
                    break;
                case MSG_SOURCE_REMOVED_FAILED:
                    sink = (BluetoothDevice) msg.obj;
                    callback.onSourceRemoveFailed(sink, sourceId, reason);
                    break;
                case MSG_RECEIVESTATE_CHANGED:
                    param = (ObjParams) msg.obj;
                    sink = (BluetoothDevice) param.mObj1;
                    BluetoothLeBroadcastReceiveState state =
                            (BluetoothLeBroadcastReceiveState) param.mObj2;
                    callback.onReceiveStateChanged(sink, sourceId, state);
                    break;
                case MSG_SOURCE_LOST:
                    callback.onSourceLost(sourceId);
                    break;
                default:
                    Log.e(TAG, "Invalid msg: " + msg.what);
                    break;
            }
        }

        void notifySearchStarted(int reason) {
            sEventLogger.logd(TAG, "notifySearchStarted: reason: " + reason);
            obtainMessage(MSG_SEARCH_STARTED, reason, 0).sendToTarget();
        }

        void notifySearchStartFailed(int reason) {
            sEventLogger.loge(TAG, "notifySearchStartFailed: reason: " + reason);
            obtainMessage(MSG_SEARCH_STARTED_FAILED, reason, 0).sendToTarget();
        }

        void notifySearchStopped(int reason) {
            sEventLogger.logd(TAG, "notifySearchStopped: reason: " + reason);
            obtainMessage(MSG_SEARCH_STOPPED, reason, 0).sendToTarget();
        }

        void notifySearchStopFailed(int reason) {
            sEventLogger.loge(TAG, "notifySearchStopFailed: reason: " + reason);
            obtainMessage(MSG_SEARCH_STOPPED_FAILED, reason, 0).sendToTarget();
        }

        void notifySourceFound(BluetoothLeBroadcastMetadata source) {
            sEventLogger.logd(
                    TAG,
                    "invokeCallback: MSG_SOURCE_FOUND"
                            + ", source: "
                            + source.getSourceDevice()
                            + ", broadcastId: "
                            + source.getBroadcastId()
                            + ", broadcastName: "
                            + source.getBroadcastName()
                            + ", isPublic: "
                            + source.isPublicBroadcast()
                            + ", isEncrypted: "
                            + source.isEncrypted());
            obtainMessage(MSG_SOURCE_FOUND, 0, 0, source).sendToTarget();
        }

        void notifySourceAdded(
                BluetoothDevice sink, BluetoothLeBroadcastReceiveState recvState, int reason) {
            sService.localNotifySourceAdded(sink, recvState);

            sEventLogger.logd(
                    TAG,
                    "notifySourceAdded: "
                            + "sink: "
                            + sink
                            + ", sourceId: "
                            + recvState.getSourceId()
                            + ", reason: "
                            + reason);

            ObjParams param = new ObjParams(sink, recvState);
            obtainMessage(MSG_SOURCE_ADDED, reason, recvState.getSourceId(), param).sendToTarget();
        }

        void notifySourceAddFailed(
                BluetoothDevice sink, BluetoothLeBroadcastMetadata source, int reason) {
            sService.checkAndResetGroupAllowedContextMask();

            sEventLogger.loge(
                    TAG,
                    "notifySourceAddFailed: sink: "
                            + sink
                            + ", source: "
                            + source
                            + ", reason: "
                            + reason);
            ObjParams param = new ObjParams(sink, source);
            obtainMessage(MSG_SOURCE_ADDED_FAILED, reason, 0, param).sendToTarget();
        }

        void notifySourceModified(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.logd(
                    TAG,
                    "notifySourceModified: "
                            + "sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId
                            + ", reason: "
                            + reason);
            obtainMessage(MSG_SOURCE_MODIFIED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceModifyFailed(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.loge(
                    TAG,
                    "notifySourceModifyFailed: sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId
                            + ", reason: "
                            + reason);
            obtainMessage(MSG_SOURCE_MODIFIED_FAILED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceRemoved(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.logd(
                    TAG,
                    "notifySourceRemoved: "
                            + "sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId
                            + ", reason: "
                            + reason);
            obtainMessage(MSG_SOURCE_REMOVED, reason, sourceId, sink).sendToTarget();
        }

        void notifySourceRemoveFailed(BluetoothDevice sink, int sourceId, int reason) {
            sEventLogger.loge(
                    TAG,
                    "notifySourceRemoveFailed: "
                            + "sink: "
                            + sink
                            + ", sourceId: "
                            + sourceId
                            + ", reason: "
                            + reason);
            obtainMessage(MSG_SOURCE_REMOVED_FAILED, reason, sourceId, sink).sendToTarget();
        }

        void notifyReceiveStateChanged(
                BluetoothDevice sink, int sourceId, BluetoothLeBroadcastReceiveState state) {
            ObjParams param = new ObjParams(sink, state);

            sService.localNotifyReceiveStateChanged(sink);

            String subgroupState = " / SUB GROUPS: ";
            for (int i = 0; i < state.getNumSubgroups(); i++) {
                subgroupState += "IDX: " + i + ", SYNC: " + state.getBisSyncState().get(i);
            }

            sEventLogger.logd(
                    TAG,
                    "notifyReceiveStateChanged: "
                            + "sink: "
                            + sink
                            + ", state: SRC ID: "
                            + state.getSourceId()
                            + " / ADDR TYPE: "
                            + state.getSourceAddressType()
                            + " / SRC DEV: "
                            + state.getSourceDevice()
                            + " / ADV SID: "
                            + state.getSourceAdvertisingSid()
                            + " / BID: "
                            + state.getBroadcastId()
                            + " / PA STATE: "
                            + state.getPaSyncState()
                            + " / BENC STATE: "
                            + state.getBigEncryptionState()
                            + " / BAD CODE: "
                            + Arrays.toString(state.getBadCode())
                            + subgroupState);
            obtainMessage(MSG_RECEIVESTATE_CHANGED, 0, sourceId, param).sendToTarget();
        }

        void notifySourceLost(int broadcastId) {
            sEventLogger.logd(TAG, "notifySourceLost: broadcastId: " + broadcastId);
            obtainMessage(MSG_SOURCE_LOST, 0, broadcastId).sendToTarget();
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);

        sb.append("Broadcast Assistant Service instance:\n");

        /* Dump first connected state machines */
        for (Map.Entry<BluetoothDevice, BassClientStateMachine> entry : mStateMachines.entrySet()) {
            BassClientStateMachine sm = entry.getValue();
            if (sm.getConnectionState() == BluetoothProfile.STATE_CONNECTED) {
                sm.dump(sb);
                sb.append("\n\n");
            }
        }

        /* Dump at least all other than connected state machines */
        for (Map.Entry<BluetoothDevice, BassClientStateMachine> entry : mStateMachines.entrySet()) {
            BassClientStateMachine sm = entry.getValue();
            if (sm.getConnectionState() != BluetoothProfile.STATE_CONNECTED) {
                sm.dump(sb);
            }
        }

        sb.append("\n\n");
        sEventLogger.dump(sb);
        sb.append("\n");
    }

    /** Binder object: must be a static class or memory leak may occur */
    @VisibleForTesting
    static class BluetoothLeBroadcastAssistantBinder extends IBluetoothLeBroadcastAssistant.Stub
            implements IProfileServiceBinder {
        BassClientService mService;

        BluetoothLeBroadcastAssistantBinder(BassClientService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
        private BassClientService getServiceAndEnforceConnect(AttributionSource source) {
            // Cache mService because it can change while getService is called
            BassClientService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service;
        }

        @RequiresPermission(allOf = {BLUETOOTH_SCAN, BLUETOOTH_PRIVILEGED})
        private BassClientService getServiceAndEnforceScan(AttributionSource source) {
            // Cache mService because it can change while getService is called
            BassClientService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                    || !Utils.checkScanPermissionForDataDelivery(service, source, TAG)) {
                return null;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service;
        }

        @Override
        public int getConnectionState(BluetoothDevice sink, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return service.getConnectionState(sink);
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return Collections.emptyList();
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return Collections.emptyList();
            }
            return service.getConnectedDevices();
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return false;
            }
            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
            }
            return service.getConnectionPolicy(device);
        }

        @Override
        public void registerCallback(
                IBluetoothLeBroadcastAssistantCallback cb, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.registerCallback(cb);
        }

        @Override
        public void unregisterCallback(
                IBluetoothLeBroadcastAssistantCallback cb, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.unregisterCallback(cb);
        }

        @Override
        public void startSearchingForSources(List<ScanFilter> filters, AttributionSource source) {
            BassClientService service = getServiceAndEnforceScan(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.startSearchingForSources(filters);
        }

        @Override
        public void stopSearchingForSources(AttributionSource source) {
            BassClientService service = getServiceAndEnforceScan(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.stopSearchingForSources();
        }

        @Override
        public boolean isSearchInProgress(AttributionSource source) {
            BassClientService service = getServiceAndEnforceScan(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return false;
            }
            return service.isSearchInProgress();
        }

        @Override
        public void addSource(
                BluetoothDevice sink,
                BluetoothLeBroadcastMetadata sourceMetadata,
                boolean isGroupOp,
                AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.addSource(sink, sourceMetadata, isGroupOp);
        }

        @Override
        public void modifySource(
                BluetoothDevice sink,
                int sourceId,
                BluetoothLeBroadcastMetadata updatedMetadata,
                AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.modifySource(sink, sourceId, updatedMetadata);
        }

        @Override
        public void removeSource(BluetoothDevice sink, int sourceId, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return;
            }
            service.removeSource(sink, sourceId);
        }

        @Override
        public List<BluetoothLeBroadcastReceiveState> getAllSources(
                BluetoothDevice sink, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return Collections.emptyList();
            }
            return service.getAllSources(sink);
        }

        @Override
        public int getMaximumSourceCapacity(BluetoothDevice sink, AttributionSource source) {
            BassClientService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                Log.e(TAG, "Service is null");
                return 0;
            }
            return service.getMaximumSourceCapacity(sink);
        }
    }
}
