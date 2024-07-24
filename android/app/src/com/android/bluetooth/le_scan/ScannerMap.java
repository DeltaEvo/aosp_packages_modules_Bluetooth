/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.bluetooth.le_scan;

import android.annotation.Nullable;
import android.bluetooth.le.IScannerCallback;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** List of our registered scanners. */
public class ScannerMap {
    private static final String TAG = "ScannerMap";

    /** Internal map to keep track of logging information by app name */
    private final HashMap<Integer, AppScanStats> mAppScanStatsMap = new HashMap<>();

    private final ConcurrentLinkedQueue<ScannerApp> mApps = new ConcurrentLinkedQueue<>();

    /** Add an entry to the application context list with a callback. */
    ScannerApp add(
            UUID uuid,
            WorkSource workSource,
            IScannerCallback callback,
            Context context,
            TransitionalScanHelper scanHelper) {
        return add(uuid, workSource, callback, null, context, scanHelper);
    }

    /** Add an entry to the application context list with a pending intent. */
    ScannerApp add(
            UUID uuid,
            TransitionalScanHelper.PendingIntentInfo piInfo,
            Context context,
            TransitionalScanHelper scanHelper) {
        return add(uuid, null, null, piInfo, context, scanHelper);
    }

    private ScannerApp add(
            UUID uuid,
            @Nullable WorkSource workSource,
            @Nullable IScannerCallback callback,
            @Nullable TransitionalScanHelper.PendingIntentInfo piInfo,
            Context context,
            TransitionalScanHelper scanHelper) {
        int appUid;
        String appName = null;
        if (piInfo != null) {
            appUid = piInfo.callingUid;
            appName = piInfo.callingPackage;
        } else {
            appUid = Binder.getCallingUid();
            appName = context.getPackageManager().getNameForUid(appUid);
        }
        if (appName == null) {
            // Assign an app name if one isn't found
            appName = "Unknown App (UID: " + appUid + ")";
        }
        AppScanStats appScanStats = mAppScanStatsMap.get(appUid);
        if (appScanStats == null) {
            appScanStats = new AppScanStats(appName, workSource, this, context, scanHelper);
            mAppScanStatsMap.put(appUid, appScanStats);
        }
        ScannerApp app = new ScannerApp(uuid, callback, piInfo, appName, appScanStats);
        mApps.add(app);
        appScanStats.isRegistered = true;
        return app;
    }

    /** Remove the context for a given application ID. */
    void remove(int id) {
        Iterator<ScannerApp> i = mApps.iterator();
        while (i.hasNext()) {
            ScannerApp entry = i.next();
            if (entry.mId == id) {
                entry.cleanup();
                i.remove();
                break;
            }
        }
    }

    /** Erases all application context entries. */
    public void clear() {
        for (ScannerApp entry : mApps) {
            entry.cleanup();
        }
        mApps.clear();
    }

    /** Get Logging info by application UID */
    AppScanStats getAppScanStatsByUid(int uid) {
        return mAppScanStatsMap.get(uid);
    }

    /** Get Logging info by ID */
    AppScanStats getAppScanStatsById(int id) {
        ScannerApp temp = (ScannerApp) getById(id);
        if (temp != null) {
            return temp.mAppScanStats;
        }
        return null;
    }

    /** Get an application context by ID. */
    ScannerApp getById(int id) {
        ScannerApp app = getAppByPredicate(entry -> entry.mId == id);
        if (app == null) {
            Log.e(TAG, "Context not found for ID " + id);
        }
        return app;
    }

    /** Get an application context by UUID. */
    ScannerApp getByUuid(UUID uuid) {
        ScannerApp app = getAppByPredicate(entry -> entry.mUuid.equals(uuid));
        if (app == null) {
            Log.e(TAG, "Context not found for UUID " + uuid);
        }
        return app;
    }

    /** Get an application context by the calling Apps name. */
    ScannerApp getByName(String name) {
        ScannerApp app = getAppByPredicate(entry -> entry.mName.equals(name));
        if (app == null) {
            Log.e(TAG, "Context not found for name " + name);
        }
        return app;
    }

    /** Get an application context by the pending intent info object. */
    ScannerApp getByPendingIntentInfo(TransitionalScanHelper.PendingIntentInfo info) {
        ScannerApp app =
                getAppByPredicate(entry -> entry.mInfo != null && entry.mInfo.equals(info));
        if (app == null) {
            Log.e(TAG, "Context not found for info " + info);
        }
        return app;
    }

    private ScannerApp getAppByPredicate(Predicate<ScannerApp> predicate) {
        // Intentionally using a for-loop over a stream for performance.
        for (ScannerApp app : mApps) {
            if (predicate.test(app)) {
                return app;
            }
        }
        return null;
    }

    /** Logs debug information. */
    public void dump(StringBuilder sb) {
        sb.append("  Entries: " + mAppScanStatsMap.size() + "\n\n");
        for (AppScanStats appScanStats : mAppScanStatsMap.values()) {
            appScanStats.dumpToString(sb);
        }
    }

    /** Logs all apps for debugging. */
    public void dumpApps(StringBuilder sb, BiConsumer<StringBuilder, String> bf) {
        for (ScannerApp entry : mApps) {
            bf.accept(sb, "    app_if: " + entry.mId + ", appName: " + entry.mName);
        }
    }

    public static class ScannerApp {
        /** Context information */
        @Nullable TransitionalScanHelper.PendingIntentInfo mInfo;

        /** Statistics for this app */
        AppScanStats mAppScanStats;

        /** The UUID of the application */
        final UUID mUuid;

        /** The package name of the application */
        final String mName;

        /** Application callbacks */
        @Nullable IScannerCallback mCallback;

        /** The id of the application */
        int mId;

        /** Whether the calling app has location permission */
        boolean mHasLocationPermission;

        /** The user handle of the app that started the scan */
        @Nullable UserHandle mUserHandle;

        /** Whether the calling app has the network settings permission */
        boolean mHasNetworkSettingsPermission;

        /** Whether the calling app has the network setup wizard permission */
        boolean mHasNetworkSetupWizardPermission;

        /** Whether the calling app has the network setup wizard permission */
        boolean mHasScanWithoutLocationPermission;

        /** Whether the calling app has disavowed the use of bluetooth for location */
        boolean mHasDisavowedLocation;

        boolean mEligibleForSanitizedExposureNotification;

        @Nullable List<String> mAssociatedDevices;

        /** Death recipient */
        @Nullable private IBinder.DeathRecipient mDeathRecipient;

        /** Creates a new app context. */
        ScannerApp(
                UUID uuid,
                @Nullable IScannerCallback callback,
                @Nullable TransitionalScanHelper.PendingIntentInfo info,
                String name,
                AppScanStats appScanStats) {
            this.mUuid = uuid;
            this.mCallback = callback;
            this.mName = name;
            this.mInfo = info;
            this.mAppScanStats = appScanStats;
        }

        /** Link death recipient */
        void linkToDeath(IBinder.DeathRecipient deathRecipient) {
            // It might not be a binder object
            if (mCallback == null) {
                return;
            }
            try {
                IBinder binder = ((IInterface) mCallback).asBinder();
                binder.linkToDeath(deathRecipient, 0);
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to link deathRecipient for app id " + mId);
            }
        }

        /** Unlink death recipient */
        void cleanup() {
            if (mDeathRecipient != null) {
                try {
                    IBinder binder = ((IInterface) mCallback).asBinder();
                    binder.unlinkToDeath(mDeathRecipient, 0);
                } catch (NoSuchElementException e) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id " + mId);
                }
            }
            mAppScanStats.isRegistered = false;
        }
    }
}
