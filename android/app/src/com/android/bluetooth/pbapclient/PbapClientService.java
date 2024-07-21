/*
 * Copyright (c) 2016 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothPbapClient;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.provider.CallLog;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.hfpclient.HfpClientConnectionService;
import com.android.bluetooth.sdp.SdpManagerNativeInterface;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Provides Bluetooth Phone Book Access Profile Client profile. */
public class PbapClientService extends ProfileService {
    private static final String TAG = PbapClientService.class.getSimpleName();

    private static final String SERVICE_NAME = "Phonebook Access PCE";

    /** The component names for the owned authenticator service */
    private static final String AUTHENTICATOR_SERVICE =
            AuthenticationService.class.getCanonicalName();

    // MAXIMUM_DEVICES set to 10 to prevent an excessive number of simultaneous devices.
    private static final int MAXIMUM_DEVICES = 10;

    @VisibleForTesting
    final Map<BluetoothDevice, PbapClientStateMachine> mPbapClientStateMachineMap =
            new ConcurrentHashMap<>();

    private static PbapClientService sPbapClientService;
    @VisibleForTesting PbapBroadcastReceiver mPbapBroadcastReceiver = new PbapBroadcastReceiver();
    private int mSdpHandle = -1;

    private DatabaseManager mDatabaseManager;

    /**
     * There's an ~1-2 second latency between when our Authentication service is set as available to
     * the system and when the Authentication/Account framework code will recognize it and allow us
     * to alter accounts. In lieu of the Accounts team dealing with this race condition, we're going
     * to periodically poll over 3 seconds until our accounts are visible, remove old accounts, and
     * then notify device state machines that they can create accounts and download contacts.
     */
    // TODO(233361365): Remove this pattern when the framework solves their race condition
    private static final int ACCOUNT_VISIBILITY_CHECK_MS = 500;

    private static final int ACCOUNT_VISIBILITY_CHECK_TRIES_MAX = 6;
    private int mAccountVisibilityCheckTries = 0;
    private final Handler mAuthServiceHandler = new Handler();
    private Handler mHandler;
    private final Runnable mCheckAuthService =
            new Runnable() {
                @Override
                public void run() {
                    // If our accounts are finally visible to use, clean up old ones and tell
                    // devices they can issue downloads if they're ready. Otherwise, wait and try
                    // again.
                    if (isAuthenticationServiceReady()) {
                        Log.i(
                                TAG,
                                "Service ready! Clean up old accounts and try contacts downloads");
                        removeUncleanAccounts();
                        for (PbapClientStateMachine stateMachine :
                                mPbapClientStateMachineMap.values()) {
                            stateMachine.tryDownloadIfConnected();
                        }
                    } else if (mAccountVisibilityCheckTries < ACCOUNT_VISIBILITY_CHECK_TRIES_MAX) {
                        mAccountVisibilityCheckTries += 1;
                        Log.w(
                                TAG,
                                "AccountManager hasn't registered our service yet. Retry "
                                        + mAccountVisibilityCheckTries
                                        + "/"
                                        + ACCOUNT_VISIBILITY_CHECK_TRIES_MAX);
                        mAuthServiceHandler.postDelayed(this, ACCOUNT_VISIBILITY_CHECK_MS);
                    } else {
                        Log.e(
                                TAG,
                                "Failed to register Authentication Service and get account"
                                        + " visibility");
                    }
                }
            };

    public PbapClientService(Context ctx) {
        super(ctx);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfilePbapClientEnabled().orElse(false);
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothPbapClientBinder(this);
    }

    @Override
    public void start() {
        Log.v(TAG, "onStart");

        mDatabaseManager =
                Objects.requireNonNull(
                        AdapterService.getAdapterService().getDatabase(),
                        "DatabaseManager cannot be null when PbapClientService starts");

        setComponentAvailable(AUTHENTICATOR_SERVICE, true);

        mHandler = new Handler(Looper.getMainLooper());
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        // delay initial download until after the user is unlocked to add an account.
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        try {
            registerReceiver(mPbapBroadcastReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "Unable to register pbapclient receiver", e);
        }

        initializeAuthenticationService();
        registerSdpRecord();
        setPbapClientService(this);
    }

    @Override
    public void stop() {
        setPbapClientService(null);
        cleanUpSdpRecord();
        try {
            unregisterReceiver(mPbapBroadcastReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Unable to unregister pbapclient receiver", e);
        }
        for (PbapClientStateMachine pbapClientStateMachine : mPbapClientStateMachineMap.values()) {
            pbapClientStateMachine.doQuit();
        }
        mPbapClientStateMachineMap.clear();

        // Unregister Handler and stop all queued messages.
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }

        cleanupAuthenticationService();
        setComponentAvailable(AUTHENTICATOR_SERVICE, false);
    }

    void cleanupDevice(BluetoothDevice device) {
        Log.d(TAG, "Cleanup device: " + device);
        synchronized (mPbapClientStateMachineMap) {
            PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
            if (pbapClientStateMachine != null) {
                mPbapClientStateMachineMap.remove(device);
                pbapClientStateMachine.doQuit();
            }
        }
    }

    /**
     * Periodically check if the account framework has recognized our service and will allow us to
     * interact with our accounts. Notify state machines once our service is ready so we can trigger
     * account downloads.
     */
    private void initializeAuthenticationService() {
        mAuthServiceHandler.postDelayed(mCheckAuthService, ACCOUNT_VISIBILITY_CHECK_MS);
    }

    private void cleanupAuthenticationService() {
        mAuthServiceHandler.removeCallbacks(mCheckAuthService);
        removeUncleanAccounts();
    }

    /**
     * Determine if our account type is visible to us yet. If it is, then our service is ready and
     * our account type is ready to use.
     *
     * <p>Make a placeholder device account and determine our visibility relative to it. Note that
     * this function uses the same restrictions as the other add and remove functions, but is *also*
     * available to all system apps instead of throwing a runtime SecurityException.
     */
    protected boolean isAuthenticationServiceReady() {
        Account account = new Account("00:00:00:00:00:00", getString(R.string.pbap_account_type));
        AccountManager accountManager = AccountManager.get(this);
        int visibility = accountManager.getAccountVisibility(account, getPackageName());
        Log.d(TAG, "Checking visibility, visibility=" + visibility);
        return visibility == AccountManager.VISIBILITY_VISIBLE
                || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
    }

    private void removeUncleanAccounts() {
        if (!isAuthenticationServiceReady()) {
            Log.w(TAG, "Can't remove accounts. AccountManager hasn't registered our service yet.");
            return;
        }

        // Find all accounts that match the type "pbap" and delete them.
        AccountManager accountManager = AccountManager.get(this);
        Account[] accounts =
                accountManager.getAccountsByType(getString(R.string.pbap_account_type));
        Log.v(TAG, "Found " + accounts.length + " unclean accounts");
        for (Account acc : accounts) {
            Log.w(TAG, "Deleting " + acc);
            try {
                getContentResolver()
                        .delete(
                                CallLog.Calls.CONTENT_URI,
                                CallLog.Calls.PHONE_ACCOUNT_ID + "=?",
                                new String[] {acc.name});
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Call Logs could not be deleted, they may not exist yet.");
            }
            // The device ID is the name of the account.
            accountManager.removeAccountExplicitly(acc);
        }
    }

    private void removeHfpCallLog(String accountName, Context context) {
        Log.d(TAG, "Removing call logs from " + accountName);
        // Delete call logs belonging to accountName==BD_ADDR that also match
        // component name "hfpclient".
        ComponentName componentName = new ComponentName(context, HfpClientConnectionService.class);
        String selectionFilter =
                CallLog.Calls.PHONE_ACCOUNT_ID
                        + "=? AND "
                        + CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME
                        + "=?";
        String[] selectionArgs = new String[] {accountName, componentName.flattenToString()};
        try {
            BluetoothMethodProxy.getInstance()
                    .contentResolverDelete(
                            getContentResolver(),
                            CallLog.Calls.CONTENT_URI,
                            selectionFilter,
                            selectionArgs);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Call Logs could not be deleted, they may not exist yet.");
        }
    }

    private void registerSdpRecord() {
        SdpManagerNativeInterface nativeInterface = SdpManagerNativeInterface.getInstance();
        if (!nativeInterface.isAvailable()) {
            Log.e(TAG, "SdpManagerNativeInterface is not available");
            return;
        }
        mSdpHandle =
                nativeInterface.createPbapPceRecord(
                        SERVICE_NAME, PbapClientConnectionHandler.PBAP_V1_2);
    }

    private void cleanUpSdpRecord() {
        if (mSdpHandle < 0) {
            Log.e(TAG, "cleanUpSdpRecord, SDP record never created");
            return;
        }
        int sdpHandle = mSdpHandle;
        mSdpHandle = -1;
        SdpManagerNativeInterface nativeInterface = SdpManagerNativeInterface.getInstance();
        if (!nativeInterface.isAvailable()) {
            Log.e(
                    TAG,
                    "cleanUpSdpRecord failed, SdpManagerNativeInterface is not available,"
                            + " sdpHandle="
                            + sdpHandle);
            return;
        }
        Log.i(TAG, "cleanUpSdpRecord, mSdpHandle=" + sdpHandle);
        if (!nativeInterface.removeSdpRecord(sdpHandle)) {
            Log.e(TAG, "cleanUpSdpRecord, removeSdpRecord failed, sdpHandle=" + sdpHandle);
        }
    }

    @VisibleForTesting
    class PbapBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, "onReceive" + action);
            if (action.equals(Intent.ACTION_USER_UNLOCKED)) {
                for (PbapClientStateMachine stateMachine : mPbapClientStateMachineMap.values()) {
                    stateMachine.tryDownloadIfConnected();
                }
            }
        }
    }

    public void aclDisconnected(BluetoothDevice device, int transport) {
        mHandler.post(() -> handleAclDisconnected(device, transport));
    }

    private void handleAclDisconnected(BluetoothDevice device, int transport) {
        Log.i(
                TAG,
                "Received ACL disconnection event, device="
                        + device.toString()
                        + ", transport="
                        + transport);

        if (transport != BluetoothDevice.TRANSPORT_BREDR) {
            return;
        }

        if (getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            disconnect(device);
        }
    }

    /**
     * Ensure that after HFP disconnects, we remove call logs. This addresses the situation when
     * PBAP was never connected while calls were made. Ideally {@link PbapClientConnectionHandler}
     * has code to remove calllogs when PBAP disconnects.
     */
    public void handleHeadsetClientConnectionStateChanged(
            BluetoothDevice device, int oldState, int newState) {
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.d(TAG, "Received intent to disconnect HFP with " + device);
            // HFP client stores entries in calllog.db by BD_ADDR and component name
            // Using the current Service as the context.
            removeHfpCallLog(device.getAddress(), this);
        }
    }

    /** Handler for incoming service calls */
    @VisibleForTesting
    static class BluetoothPbapClientBinder extends IBluetoothPbapClient.Stub
            implements IProfileServiceBinder {
        private PbapClientService mService;

        BluetoothPbapClientBinder(PbapClientService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        private PbapClientService getService(AttributionSource source) {
            // Cache mService because it can change while getService is called
            PbapClientService service = mService;

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
        public boolean connect(BluetoothDevice device, AttributionSource source) {
            Log.d(TAG, "PbapClient Binder connect");

            PbapClientService service = getService(source);
            if (service == null) {
                Log.e(TAG, "PbapClient Binder connect no service");
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            PbapClientService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            PbapClientService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            PbapClientService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            PbapClientService service = getService(source);
            if (service == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            PbapClientService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            PbapClientService service = getService(source);
            if (service == null) {
                return BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectionPolicy(device);
        }
    }

    // API methods
    public static synchronized PbapClientService getPbapClientService() {
        if (sPbapClientService == null) {
            Log.w(TAG, "getPbapClientService(): service is null");
            return null;
        }
        if (!sPbapClientService.isAvailable()) {
            Log.w(TAG, "getPbapClientService(): service is not available");
            return null;
        }
        return sPbapClientService;
    }

    @VisibleForTesting
    static synchronized void setPbapClientService(PbapClientService instance) {
        Log.v(TAG, "setPbapClientService(): set to: " + instance);
        sPbapClientService = instance;
    }

    public boolean connect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        Log.d(TAG, "Received request to ConnectPBAPPhonebook " + device.getAddress());
        if (getConnectionPolicy(device) <= BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            return false;
        }
        synchronized (mPbapClientStateMachineMap) {
            PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
            if (pbapClientStateMachine == null
                    && mPbapClientStateMachineMap.size() < MAXIMUM_DEVICES) {
                pbapClientStateMachine = new PbapClientStateMachine(this, device);
                pbapClientStateMachine.start();
                mPbapClientStateMachineMap.put(device, pbapClientStateMachine);
                return true;
            } else {
                Log.w(TAG, "Received connect request while already connecting/connected.");
                return false;
            }
        }
    }

    /**
     * Disconnects the pbap client profile from the passed in device
     *
     * @param device is the device with which we will disconnect the pbap client profile
     * @return true if we disconnected the pbap client profile, false otherwise
     */
    public boolean disconnect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
        if (pbapClientStateMachine != null) {
            pbapClientStateMachine.disconnect(device);
            return true;
        } else {
            Log.w(TAG, "disconnect() called on unconnected device.");
            return false;
        }
    }

    public List<BluetoothDevice> getConnectedDevices() {
        int[] desiredStates = {BluetoothProfile.STATE_CONNECTED};
        return getDevicesMatchingConnectionStates(desiredStates);
    }

    @VisibleForTesting
    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>(0);
        for (Map.Entry<BluetoothDevice, PbapClientStateMachine> stateMachineEntry :
                mPbapClientStateMachineMap.entrySet()) {
            int currentDeviceState = stateMachineEntry.getValue().getConnectionState();
            for (int state : states) {
                if (currentDeviceState == state) {
                    deviceList.add(stateMachineEntry.getKey());
                    break;
                }
            }
        }
        return deviceList;
    }

    public void receiveSdpSearchRecord(
            BluetoothDevice device, int status, Parcelable record, ParcelUuid uuid) {
        PbapClientStateMachine stateMachine = mPbapClientStateMachineMap.get(device);
        if (stateMachine == null) {
            Log.e(TAG, "No Statemachine found for the device=" + device.toString());
            return;
        }
        Log.v(
                TAG,
                "Received SDP record for UUID="
                        + uuid.toString()
                        + " (expected UUID="
                        + BluetoothUuid.PBAP_PSE.toString()
                        + ")");
        if (uuid.equals(BluetoothUuid.PBAP_PSE)) {
            stateMachine
                    .obtainMessage(PbapClientStateMachine.MSG_SDP_COMPLETE, record)
                    .sendToTarget();
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
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
        if (pbapClientStateMachine == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        } else {
            return pbapClientStateMachine.getConnectionState(device);
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
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        if (!mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.PBAP_CLIENT, connectionPolicy)) {
            return false;
        }
        if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
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
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.PBAP_CLIENT);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        ProfileService.println(sb, "isAuthServiceReady: " + isAuthenticationServiceReady());
        for (PbapClientStateMachine stateMachine : mPbapClientStateMachineMap.values()) {
            stateMachine.dump(sb);
        }
    }
}
