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

package com.android.bluetooth.le_scan;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;

import android.app.PendingIntent;
import android.bluetooth.le.IScannerCallback;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.UUID;

/** Test cases for {@link ScannerMap}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScannerMapTest {
    private static final String APP_NAME = "com.android.what.a.name";
    private static final int UID = 12345;
    private static final int SCANNER_ID = 321;

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private AdapterService mAdapterService;
    @Mock private PackageManager mMockPackageManager;
    @Mock private TransitionalScanHelper mMockTransitionalScanHelper;
    @Mock private IScannerCallback mMockScannerCallback;

    @Spy private BluetoothMethodProxy mMapMethodProxy = BluetoothMethodProxy.getInstance();

    @Before
    public void setUp() throws Exception {
        BluetoothMethodProxy.setInstanceForTesting(mMapMethodProxy);
        TestUtils.setAdapterService(mAdapterService);
        doReturn(mMockPackageManager).when(mAdapterService).getPackageManager();
        doReturn(APP_NAME).when(mMockPackageManager).getNameForUid(anyInt());
    }

    @After
    public void tearDown() throws Exception {
        BluetoothMethodProxy.setInstanceForTesting(null);
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void getByMethodsWithPii() {
        ScannerMap scannerMap = new ScannerMap();
        TransitionalScanHelper.PendingIntentInfo info =
                new TransitionalScanHelper.PendingIntentInfo();
        info.callingUid = UID;
        info.callingPackage = APP_NAME;
        info.intent =
                PendingIntent.getBroadcast(
                        InstrumentationRegistry.getTargetContext(),
                        0,
                        new Intent(),
                        PendingIntent.FLAG_IMMUTABLE);
        UUID uuid = UUID.randomUUID();
        ScannerMap.ScannerApp app =
                scannerMap.add(uuid, info, mAdapterService, mMockTransitionalScanHelper);
        app.mId = SCANNER_ID;

        ScannerMap.ScannerApp scannerMapById = scannerMap.getById(SCANNER_ID);
        assertThat(scannerMapById.mName).isEqualTo(APP_NAME);

        ScannerMap.ScannerApp scannerMapByUuid = scannerMap.getByUuid(uuid);
        assertThat(scannerMapByUuid.mName).isEqualTo(APP_NAME);

        ScannerMap.ScannerApp scannerMapByName = scannerMap.getByName(APP_NAME);
        assertThat(scannerMapByName.mName).isEqualTo(APP_NAME);

        ScannerMap.ScannerApp scannerMapByPii = scannerMap.getByPendingIntentInfo(info);
        assertThat(scannerMapByPii.mName).isEqualTo(APP_NAME);

        assertThat(scannerMap.getAppScanStatsById(SCANNER_ID)).isNotNull();
        assertThat(scannerMap.getAppScanStatsByUid(UID)).isNotNull();
    }

    @Test
    public void getByMethodsWithoutPii() {
        ScannerMap scannerMap = new ScannerMap();
        UUID uuid = UUID.randomUUID();
        ScannerMap.ScannerApp app =
                scannerMap.add(
                        uuid,
                        null,
                        mMockScannerCallback,
                        mAdapterService,
                        mMockTransitionalScanHelper);
        int appUid = Binder.getCallingUid();
        app.mId = SCANNER_ID;

        ScannerMap.ScannerApp scannerMapById = scannerMap.getById(SCANNER_ID);
        assertThat(scannerMapById.mName).isEqualTo(APP_NAME);
        assertThat(scannerMapById.mCallback).isEqualTo(mMockScannerCallback);

        ScannerMap.ScannerApp scannerMapByUuid = scannerMap.getByUuid(uuid);
        assertThat(scannerMapByUuid.mName).isEqualTo(APP_NAME);

        ScannerMap.ScannerApp scannerMapByName = scannerMap.getByName(APP_NAME);
        assertThat(scannerMapByName.mName).isEqualTo(APP_NAME);

        assertThat(scannerMap.getAppScanStatsById(SCANNER_ID)).isNotNull();
        assertThat(scannerMap.getAppScanStatsByUid(appUid)).isNotNull();
    }

    @Test
    public void removeById() {
        ScannerMap scannerMap = new ScannerMap();
        UUID uuid = UUID.randomUUID();
        ScannerMap.ScannerApp app =
                scannerMap.add(
                        uuid,
                        null,
                        mMockScannerCallback,
                        mAdapterService,
                        mMockTransitionalScanHelper);
        app.mId = SCANNER_ID;

        ScannerMap.ScannerApp scannerMapById = scannerMap.getById(SCANNER_ID);
        assertThat(scannerMapById.mName).isEqualTo(APP_NAME);

        scannerMap.remove(SCANNER_ID);
        assertThat(scannerMap.getById(SCANNER_ID)).isNull();
    }

    @Test
    public void testDump_doesNotCrash() throws Exception {
        StringBuilder sb = new StringBuilder();
        ScannerMap scannerMap = new ScannerMap();
        scannerMap.add(
                UUID.randomUUID(),
                null,
                mMockScannerCallback,
                mAdapterService,
                mMockTransitionalScanHelper);
        scannerMap.dump(sb);
        scannerMap.dumpApps(sb, ProfileService::println);
    }
}
