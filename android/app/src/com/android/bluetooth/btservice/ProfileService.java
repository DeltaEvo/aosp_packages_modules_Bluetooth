/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Base class for a background service that runs a Bluetooth profile
 */
public abstract class ProfileService extends Service {
    private static final boolean DBG = false;

    public static final String BLUETOOTH_PERM =
            android.Manifest.permission.BLUETOOTH;
    public static final String BLUETOOTH_PRIVILEGED =
            android.Manifest.permission.BLUETOOTH_PRIVILEGED;

    public interface IProfileServiceBinder extends IBinder {
        /**
         * Called in {@link #onDestroy()}
         */
        void cleanup();
    }

    //Profile services will not be automatically restarted.
    //They must be explicitly restarted by AdapterService
    private static final int PROFILE_SERVICE_MODE = Service.START_NOT_STICKY;
    private BluetoothAdapter mAdapter;
    private IProfileServiceBinder mBinder;
    private final String mName;
    private AdapterService mAdapterService;
    private boolean mProfileStarted = false;
    private volatile boolean mTestModeEnabled = false;

    public String getName() {
        return getClass().getSimpleName();
    }

    public boolean isAvailable() {
        return mProfileStarted;
    }

    protected boolean isTestModeEnabled() {
        return mTestModeEnabled;
    }

    /**
     * Called in {@link #onCreate()} to init binder interface for this profile service
     *
     * @return initialized binder interface for this profile service
     */
    protected abstract IProfileServiceBinder initBinder();

    /**
     * Called in {@link #onCreate()} to init basic stuff in this service
     */
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    protected void create() {}

    /**
     * Called in {@link #onStartCommand(Intent, int, int)} when the service is started by intent
     *
     * @return True in successful condition, False otherwise
     */
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    protected abstract boolean start();

    /**
     * Called in {@link #onStartCommand(Intent, int, int)} when the service is stopped by intent
     *
     * @return True in successful condition, False otherwise
     */
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    protected abstract boolean stop();

    /**
     * Called in {@link #onDestroy()} when this object is completely discarded
     */
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    protected void cleanup() {}

    /**
     * @param testModeEnabled if the profile should enter or exit a testing mode
     */
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    protected void setTestModeEnabled(boolean testModeEnabled) {
        mTestModeEnabled = testModeEnabled;
    }

    protected ProfileService() {
        mName = getName();
    }

    @Override
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void onCreate() {
        if (DBG) {
            Log.d(mName, "onCreate");
        }
        super.onCreate();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mBinder = initBinder();
        create();
    }

    @Override
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DBG) {
            Log.d(mName, "onStartCommand()");
        }

        if (checkCallingOrSelfPermission(BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(mName, "Permission denied!");
            return PROFILE_SERVICE_MODE;
        }

        if (intent == null) {
            Log.d(mName, "onStartCommand ignoring null intent.");
            return PROFILE_SERVICE_MODE;
        }

        String action = intent.getStringExtra(AdapterService.EXTRA_ACTION);
        if (AdapterService.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_OFF) {
                doStop();
            } else if (state == BluetoothAdapter.STATE_ON) {
                doStart();
            }
        }
        return PROFILE_SERVICE_MODE;
    }

    @Override
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public IBinder onBind(Intent intent) {
        if (DBG) {
            Log.d(mName, "onBind");
        }
        if (mAdapter != null && mBinder == null) {
            // initBinder returned null, you can't bind
            throw new UnsupportedOperationException("Cannot bind to " + mName);
        }
        return mBinder;
    }

    IBinder getBinder() {
        requireNonNull(mBinder, "Binder is null. onCreate need to be called first");
        return mBinder;
    }

    @Override
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public boolean onUnbind(Intent intent) {
        if (DBG) {
            Log.d(mName, "onUnbind");
        }
        return super.onUnbind(intent);
    }

    /**
     * Set the availability of an owned/managed component (Service, Activity, Provider, etc.)
     * using a string class name assumed to be in the Bluetooth package.
     *
     * It's expected that profiles can have a set of components that they may use to provide
     * features or interact with other services/the user. Profiles are expected to enable those
     * components when they start, and disable them when they stop.
     *
     * @param className The class name of the owned component residing in the Bluetooth package
     * @param enable True to enable the component, False to disable it
     */
    @RequiresPermission(android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
    protected void setComponentAvailable(String className, boolean enable) {
        if (DBG) {
            Log.d(mName, "setComponentAvailable(className=" + className + ", enable=" + enable
                    + ")");
        }
        if (className == null) {
            return;
        }
        ComponentName component = new ComponentName(getPackageName(), className);
        setComponentAvailable(component, enable);
    }

    /**
     * Set the availability of an owned/managed component (Service, Activity, Provider, etc.)
     *
     * It's expected that profiles can have a set of components that they may use to provide
     * features or interact with other services/the user. Profiles are expected to enable those
     * components when they start, and disable them when they stop.
     *
     * @param component The component name of owned component
     * @param enable True to enable the component, False to disable it
     */
    @RequiresPermission(android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
    protected void setComponentAvailable(ComponentName component, boolean enable) {
        if (DBG) {
            Log.d(mName, "setComponentAvailable(component=" + component + ", enable=" + enable
                    + ")");
        }
        if (component == null) {
            return;
        }
        getPackageManager().setComponentEnabledSetting(
                component,
                enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                       : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP | PackageManager.SYNCHRONOUS);
    }

    /**
     * Support dumping profile-specific information for dumpsys
     *
     * @param sb StringBuilder from the profile.
     */
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void dump(StringBuilder sb) {
        sb.append("\nProfile: ");
        sb.append(mName);
        sb.append("\n");
    }

    /**
     * Support dumping scan events from GattService
     *
     * @param builder metrics proto builder
     */
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void dumpProto(BluetoothMetricsProto.BluetoothLog.Builder builder) {
        // Do nothing
    }

    /**
     * Append an indented String for adding dumpsys support to subclasses.
     *
     * @param sb StringBuilder from the profile.
     * @param s String to indent and append.
     */
    public static void println(StringBuilder sb, String s) {
        sb.append("  ");
        sb.append(s);
        sb.append("\n");
    }

    @Override
    // Suppressed since this is called from framework
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void onDestroy() {
        Log.v(mName, "onDestroy");
        cleanup();
        if (mBinder != null) {
            mBinder.cleanup();
            mBinder = null;
        }
        mAdapter = null;
        super.onDestroy();
    }

    /** start the profile and inform AdapterService */
    @RequiresPermission(
            anyOf = {
                android.Manifest.permission.MANAGE_USERS,
                android.Manifest.permission.INTERACT_ACROSS_USERS
            })
    @VisibleForTesting
    public void doStart() {
        Log.v(mName, "doStart");
        if (mAdapter == null) {
            Log.w(mName, "Can't start profile service: device does not have BT");
            return;
        }

        mAdapterService = AdapterService.getAdapterService();
        if (mAdapterService == null) {
            Log.w(mName, "Could not add this profile because AdapterService is null.");
            return;
        }
        if (!mAdapterService.isStartedProfile(mName)) {
            Log.w(mName, "Unexpectedly do Start, don't start");
            return;
        }
        mAdapterService.addProfile(this);

        mProfileStarted = start();
        if (!mProfileStarted) {
            Log.e(mName, "Error starting profile. start() returned false.");
            return;
        }
        mAdapterService.onProfileServiceStateChanged(this, BluetoothAdapter.STATE_ON);
    }

    /** stop the profile and inform AdapterService */
    @VisibleForTesting
    public void doStop() {
        Log.v(mName, "doStop");
        if (mAdapterService == null || mAdapterService.isStartedProfile(mName)) {
            Log.w(mName, "Unexpectedly do Stop, don't stop.");
            return;
        }
        if (!mProfileStarted) {
            Log.w(mName, "doStop() called, but the profile is not running.");
            return;
        }
        mProfileStarted = false;
        if (mAdapterService != null) {
            mAdapterService.onProfileServiceStateChanged(this, BluetoothAdapter.STATE_OFF);
        }
        if (!stop()) {
            Log.e(mName, "Unable to stop profile");
        }
        if (mAdapterService != null) {
            mAdapterService.removeProfile(this);
        }
        stopSelf();
    }
}
