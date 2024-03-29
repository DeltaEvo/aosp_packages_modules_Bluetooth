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

package com.android.bluetooth.le_audio;

import static org.mockito.Mockito.*;

import android.annotation.Nullable;
import android.bluetooth.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.storage.DatabaseManager;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class LeAudioBroadcastServiceTest {
    private static final int TIMEOUT_MS = 1000;
    @Rule
    public final ServiceTestRule mServiceRule = new ServiceTestRule();
    private BluetoothAdapter mAdapter;
    private BluetoothDevice mDevice;
    private Context mTargetContext;
    private LeAudioService mService;
    private LeAudioIntentReceiver mLeAudioIntentReceiver;
    private LinkedBlockingQueue<Intent> mIntentQueue;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock
    private AdapterService mAdapterService;
    @Mock
    private DatabaseManager mDatabaseManager;
    @Mock
    private AudioManager mAudioManager;
    @Mock private LeAudioBroadcasterNativeInterface mLeAudioBroadcasterNativeInterface;
    @Mock private LeAudioNativeInterface mLeAudioNativeInterface;
    @Mock private LeAudioTmapGattServer mTmapGattServer;
    @Spy private LeAudioObjectsFactory mObjectsFactory = LeAudioObjectsFactory.getInstance();

    private static final String TEST_MAC_ADDRESS = "00:11:22:33:44:55";
    private static final int TEST_BROADCAST_ID = 42;
    private static final int TEST_ADVERTISER_SID = 1234;
    private static final int TEST_PA_SYNC_INTERVAL = 100;
    private static final int TEST_PRESENTATION_DELAY_MS = 345;

    private static final int TEST_CODEC_ID = 42;
    private static final int TEST_CHANNEL_INDEX = 56;

    // For BluetoothLeAudioCodecConfigMetadata
    private static final long TEST_AUDIO_LOCATION_FRONT_LEFT = 0x01;
    private static final long TEST_AUDIO_LOCATION_FRONT_RIGHT = 0x02;

    // For BluetoothLeAudioContentMetadata
    private static final String TEST_PROGRAM_INFO = "Test";
    // German language code in ISO 639-3
    private static final String TEST_LANGUAGE = "deu";
    private static final String TEST_BROADCAST_NAME = "Name Test";

    private boolean mOnBroadcastStartedCalled = false;
    private boolean mOnBroadcastStartFailedCalled = false;
    private boolean mOnBroadcastStoppedCalled = false;
    private boolean mOnBroadcastStopFailedCalled = false;
    private boolean mOnPlaybackStartedCalled = false;
    private boolean mOnPlaybackStoppedCalled = false;
    private boolean mOnBroadcastUpdatedCalled = false;
    private boolean mOnBroadcastUpdateFailedCalled = false;
    private boolean mOnBroadcastMetadataChangedCalled = false;
    private int mOnBroadcastStartFailedReason = BluetoothStatusCodes.SUCCESS;

    private final IBluetoothLeBroadcastCallback mCallbacks =
            new IBluetoothLeBroadcastCallback.Stub() {
                @Override
                public void onBroadcastStarted(int reason, int broadcastId) {
                    mOnBroadcastStartedCalled = true;
                }

                @Override
                public void onBroadcastStartFailed(int reason) {
                    mOnBroadcastStartFailedCalled = true;
                    mOnBroadcastStartFailedReason = reason;
                }

                @Override
                public void onBroadcastStopped(int reason, int broadcastId) {
                    mOnBroadcastStoppedCalled = true;
                }

                @Override
                public void onBroadcastStopFailed(int reason) {
                    mOnBroadcastStopFailedCalled = true;
                }

                @Override
                public void onPlaybackStarted(int reason, int broadcastId) {
                    mOnPlaybackStartedCalled = true;
                }

                @Override
                public void onPlaybackStopped(int reason, int broadcastId) {
                    mOnPlaybackStoppedCalled = true;
                }

                @Override
                public void onBroadcastUpdated(int reason, int broadcastId) {
                    mOnBroadcastUpdatedCalled = true;
                }

                @Override
                public void onBroadcastUpdateFailed(int reason, int broadcastId) {
                    mOnBroadcastUpdateFailedCalled = true;
                }

                @Override
                public void onBroadcastMetadataChanged(
                        int broadcastId, BluetoothLeBroadcastMetadata metadata) {
                    mOnBroadcastMetadataChangedCalled = true;
                }
            };

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getTargetContext();

        // Set up mocks and test assets
        MockitoAnnotations.initMocks(this);

        // Use spied objects factory
        doNothing().when(mTmapGattServer).start(anyInt());
        doNothing().when(mTmapGattServer).stop();
        LeAudioObjectsFactory.setInstanceForTesting(mObjectsFactory);
        doReturn(mTmapGattServer).when(mObjectsFactory).getTmapGattServer(any());

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        TestUtils.setAdapterService(mAdapterService);
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(true, false).when(mAdapterService).isStartedProfile(anyString());
        doReturn(true).when(mAdapterService).isLeAudioBroadcastSourceSupported();
        doReturn((long)(1 << BluetoothProfile.LE_AUDIO_BROADCAST) | (1 << BluetoothProfile.LE_AUDIO))
                .when(mAdapterService).getSupportedProfilesBitMask();
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();

        mAdapter = BluetoothAdapter.getDefaultAdapter();

        LeAudioBroadcasterNativeInterface.setInstance(mLeAudioBroadcasterNativeInterface);
        LeAudioNativeInterface.setInstance(mLeAudioNativeInterface);
        startService();
        mService.mAudioManager = mAudioManager;

        // Set up the State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);

        mLeAudioIntentReceiver = new LeAudioIntentReceiver();
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        mTargetContext.registerReceiver(mLeAudioIntentReceiver, filter);

        mDevice = TestUtils.getTestDevice(mAdapter, 0);
        when(mLeAudioBroadcasterNativeInterface.getDevice(any(byte[].class))).thenReturn(mDevice);

        mIntentQueue = new LinkedBlockingQueue<Intent>();
    }

    @After
    public void tearDown() throws Exception {
        if (mService == null) {
            return;
        }

        stopService();
        LeAudioBroadcasterNativeInterface.setInstance(null);
        LeAudioNativeInterface.setInstance(null);
        mTargetContext.unregisterReceiver(mLeAudioIntentReceiver);
        TestUtils.clearAdapterService(mAdapterService);
        reset(mAudioManager);
    }

    private void startService() throws TimeoutException {
        TestUtils.startService(mServiceRule, LeAudioService.class);
        mService = LeAudioService.getLeAudioService();
        Assert.assertNotNull(mService);
    }

    private void stopService() throws TimeoutException {
        TestUtils.stopService(mServiceRule, LeAudioService.class);
        mService = LeAudioService.getLeAudioService();
        Assert.assertNull(mService);
    }

    /**
     * Test getting LeAudio Service
     */
    @Test
    public void testGetLeAudioService() {
        Assert.assertEquals(mService, LeAudioService.getLeAudioService());
    }

    @Test
    public void testStopLeAudioService() {
        Assert.assertEquals(mService, LeAudioService.getLeAudioService());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                Assert.assertTrue(mService.stop());
            }
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                Assert.assertTrue(mService.start());
            }
        });
    }

    void verifyBroadcastStarted(int broadcastId, BluetoothLeBroadcastSettings settings) {
        mService.createBroadcast(settings);

        List<BluetoothLeBroadcastSubgroupSettings> settingsList =
                settings.getSubgroupSettings();

        int[] expectedQualityArray =
                settingsList.stream()
                        .mapToInt(setting -> setting.getPreferredQuality()).toArray();
        byte[][] expectedDataArray =
                settingsList.stream()
                        .map(setting -> setting.getContentMetadata().getRawMetadata())
                        .toArray(byte[][]::new);

        verify(mLeAudioBroadcasterNativeInterface, times(1))
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(settings.getBroadcastCode()),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));

        // Check if broadcast is started automatically when created
        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = true;
        mService.messageFromNative(create_event);

        // Verify if broadcast is auto-started on start
        verify(mLeAudioBroadcasterNativeInterface, times(1)).startBroadcast(eq(broadcastId));

        // Notify initial paused state
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);

        // Switch to active streaming
        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STREAMING;
        mService.messageFromNative(state_event);

        // Check if metadata is requested when the broadcast starts to stream
        verify(mLeAudioBroadcasterNativeInterface, times(1)).getBroadcastMetadata(eq(broadcastId));
        Assert.assertFalse(mOnBroadcastStartFailedCalled);
        Assert.assertTrue(mOnBroadcastStartedCalled);
    }

    void verifyBroadcastStopped(int broadcastId) {
        mService.stopBroadcast(broadcastId);
        verify(mLeAudioBroadcasterNativeInterface, times(1)).stopBroadcast(eq(broadcastId));

        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STOPPED;
        mService.messageFromNative(state_event);

        // Verify if broadcast is auto-destroyed on stop
        verify(mLeAudioBroadcasterNativeInterface, times(1)).destroyBroadcast(eq(broadcastId));

        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED);
        state_event.valueInt1 = broadcastId;
        mService.messageFromNative(state_event);

        Assert.assertTrue(mOnBroadcastStoppedCalled);
        Assert.assertFalse(mOnBroadcastStopFailedCalled);
    }

    @Test
    public void testCreateBroadcastNative() {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        mService.mBroadcastCallbacks.register(mCallbacks);

        BluetoothLeAudioContentMetadata.Builder meta_builder =
                new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("deu");
        meta_builder.setProgramInfo("Subgroup broadcast info");
        BluetoothLeAudioContentMetadata meta = meta_builder.build();

        verifyBroadcastStarted(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
    }

    @Test
    public void testCreateBroadcastNativeMultiGroups() {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        mService.mBroadcastCallbacks.register(mCallbacks);

        BluetoothLeAudioContentMetadata.Builder meta_builder =
                new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("deu");
        meta_builder.setProgramInfo("Subgroup broadcast info");
        BluetoothLeAudioContentMetadata meta = meta_builder.build();

        verifyBroadcastStarted(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 3));
    }

    @Test
    public void testCreateBroadcastNativeFailed() {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        mService.mBroadcastCallbacks.register(mCallbacks);

        BluetoothLeAudioContentMetadata.Builder meta_builder =
                new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("deu");
        meta_builder.setProgramInfo("Public broadcast info");
        BluetoothLeAudioContentMetadata meta = meta_builder.build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);
        mService.createBroadcast(settings);

        // Test data with only one subgroup
        int[] expectedQualityArray =
                {settings.getSubgroupSettings().get(0).getPreferredQuality()};
        byte[][] expectedDataArray =
                {settings.getSubgroupSettings().get(0).getContentMetadata().getRawMetadata()};

        verify(mLeAudioBroadcasterNativeInterface, times(1))
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(code),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = false;
        mService.messageFromNative(create_event);

        Assert.assertFalse(mOnBroadcastStartedCalled);
        Assert.assertTrue(mOnBroadcastStartFailedCalled);
    }

    @Test
    public void testStartStopBroadcastNative() {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        mService.mBroadcastCallbacks.register(mCallbacks);

        BluetoothLeAudioContentMetadata.Builder meta_builder =
                new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("deu");
        meta_builder.setProgramInfo("Subgroup broadcast info");
        BluetoothLeAudioContentMetadata meta = meta_builder.build();

        verifyBroadcastStarted(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
        verifyBroadcastStopped(broadcastId);
    }

    @Test
    public void testBroadcastInvalidBroadcastIdRequest() {
        int broadcastId = 243;

        mService.mBroadcastCallbacks.register(mCallbacks);

        // Stop non-existing broadcast
        mService.stopBroadcast(broadcastId);
        Assert.assertFalse(mOnBroadcastStoppedCalled);
        Assert.assertTrue(mOnBroadcastStopFailedCalled);

        // Update metadata for non-existing broadcast
        BluetoothLeAudioContentMetadata.Builder meta_builder =
        new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("eng");
        meta_builder.setProgramInfo("Public broadcast info");
        mService.updateBroadcast(broadcastId,
                buildBroadcastSettingsFromMetadata(meta_builder.build(), null, 1));
        Assert.assertFalse(mOnBroadcastUpdatedCalled);
        Assert.assertTrue(mOnBroadcastUpdateFailedCalled);
    }

    private BluetoothLeBroadcastSubgroup createBroadcastSubgroup() {
        BluetoothLeAudioCodecConfigMetadata codecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_LEFT).build();
        BluetoothLeAudioContentMetadata contentMetadata =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setProgramInfo(TEST_PROGRAM_INFO).setLanguage(TEST_LANGUAGE).build();
        BluetoothLeBroadcastSubgroup.Builder builder = new BluetoothLeBroadcastSubgroup.Builder()
                .setCodecId(TEST_CODEC_ID)
                .setCodecSpecificConfig(codecMetadata)
                .setContentMetadata(contentMetadata);

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_RIGHT).build();

        // builder expect at least one channel
        BluetoothLeBroadcastChannel channel =
        new BluetoothLeBroadcastChannel.Builder()
                .setSelected(true)
                .setChannelIndex(TEST_CHANNEL_INDEX)
                .setCodecMetadata(channelCodecMetadata)
                .build();
        builder.addChannel(channel);
        return builder.build();
    }

    private BluetoothLeBroadcastMetadata createBroadcastMetadata() {
        BluetoothDevice testDevice =
        mAdapter.getRemoteLeDevice(TEST_MAC_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        BluetoothLeBroadcastMetadata.Builder builder = new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(testDevice, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(TEST_BROADCAST_ID)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        return builder.build();
    }

    @Test
    public void testGetAllBroadcastMetadata() {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        BluetoothLeAudioContentMetadata.Builder meta_builder =
                new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("ENG");
        meta_builder.setProgramInfo("Public broadcast info");
        BluetoothLeAudioContentMetadata meta = meta_builder.build();
        mService.createBroadcast(buildBroadcastSettingsFromMetadata(meta, code, 1));

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = true;
        mService.messageFromNative(create_event);

        // Inject metadata stack event and verify if getter API works as expected
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED);
        state_event.valueInt1 = broadcastId;
        state_event.broadcastMetadata = createBroadcastMetadata();
        mService.messageFromNative(state_event);

        List<BluetoothLeBroadcastMetadata> meta_list = mService.getAllBroadcastMetadata();
        Assert.assertNotNull(meta_list);
        Assert.assertNotEquals(meta_list.size(), 0);
        Assert.assertEquals(meta_list.get(0), state_event.broadcastMetadata);
    }

    private void verifyConnectionStateIntent(
            int timeoutMs, BluetoothDevice device, int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mIntentQueue);
        Assert.assertNotNull(intent);
        Assert.assertEquals(
                BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED, intent.getAction());
        Assert.assertEquals(
                (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE), device);
        Assert.assertEquals(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1), newState);
        Assert.assertEquals(
                intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1), prevState);

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            // ActiveDeviceManager calls deviceConnected when connected.
            mService.deviceConnected(device);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            // ActiveDeviceManager calls deviceDisconnected when connected.
            mService.deviceDisconnected(device, false);
        }
    }

    @Test
    public void testCreatePendingBroadcast() {
        int groupId = 1;
        int direction = 3;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        BluetoothLeAudioContentMetadata.Builder meta_builder =
                new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("ENG");
        meta_builder.setProgramInfo("Public broadcast info");
        BluetoothLeAudioContentMetadata meta = meta_builder.build();

        /* Prepare active group to cause pending broadcast */
        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(true).when(mLeAudioNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.LE_AUDIO))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(new ParcelUuid[] {BluetoothUuid.LE_AUDIO})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        Assert.assertTrue(mService.connect(mDevice));

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mDevice,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(
                BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(mDevice));

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        create_event.device = mDevice;
        create_event.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(create_event);

        verifyConnectionStateIntent(
                TIMEOUT_MS,
                mDevice,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(mDevice));

        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        create_event.device = mDevice;
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(create_event);

        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        create_event.device = mDevice;
        create_event.valueInt1 = direction;
        create_event.valueInt2 = groupId;
        create_event.valueInt3 = snkAudioLocation;
        create_event.valueInt4 = srcAudioLocation;
        create_event.valueInt5 = availableContexts;
        mService.messageFromNative(create_event);

        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);

        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);
        mService.createBroadcast(settings);

        /* Check if broadcast is queued, active group should not change */
        int activeGroup = mService.getActiveGroupId();
        Assert.assertEquals(activeGroup, groupId);

        /* Imitate group inactivity to cause create broadcast */
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(create_event);

        List<BluetoothLeBroadcastSubgroupSettings> settingsList = settings.getSubgroupSettings();

        int[] expectedQualityArray =
                settingsList.stream().mapToInt(setting -> setting.getPreferredQuality()).toArray();
        byte[][] expectedDataArray =
                settingsList.stream()
                        .map(setting -> setting.getContentMetadata().getRawMetadata())
                        .toArray(byte[][]::new);

        verify(mLeAudioBroadcasterNativeInterface, times(1))
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(settings.getBroadcastCode()),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));

        activeGroup = mService.getActiveGroupId();
        Assert.assertEquals(-1, activeGroup);
    }

    @Test
    public void testCreateBroadcastMoreThanMaxFailed() {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        mService.mBroadcastCallbacks.register(mCallbacks);

        BluetoothLeAudioContentMetadata.Builder meta_builder =
                new BluetoothLeAudioContentMetadata.Builder();
        meta_builder.setLanguage("deu");
        meta_builder.setProgramInfo("Subgroup broadcast info");
        BluetoothLeAudioContentMetadata meta = meta_builder.build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);

        verifyBroadcastStarted(broadcastId, settings);
        mOnBroadcastStartedCalled = false;
        mOnBroadcastStartFailedCalled = false;

        // verify creating another broadcast will fail
        mService.createBroadcast(settings);

        Assert.assertFalse(mOnBroadcastStartedCalled);
        Assert.assertTrue(mOnBroadcastStartFailedCalled);
        Assert.assertEquals(
                BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES,
                mOnBroadcastStartFailedReason);
    }

    private class LeAudioIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                mIntentQueue.put(intent);
            } catch (InterruptedException e) {
                Assert.fail("Cannot add Intent to the queue: " + e.getMessage());
            }
        }
    }

    private BluetoothLeBroadcastSettings buildBroadcastSettingsFromMetadata(
            BluetoothLeAudioContentMetadata contentMetadata,
            @Nullable byte[] broadcastCode,
            int numOfGroups) {
        BluetoothLeAudioContentMetadata.Builder publicMetaBuilder =
                new BluetoothLeAudioContentMetadata.Builder();
        publicMetaBuilder.setProgramInfo("Public broadcast info");

        BluetoothLeBroadcastSubgroupSettings.Builder subgroupBuilder =
                new BluetoothLeBroadcastSubgroupSettings.Builder()
                .setContentMetadata(contentMetadata);

        BluetoothLeBroadcastSettings.Builder builder = new BluetoothLeBroadcastSettings.Builder()
                        .setPublicBroadcast(true)
                        .setBroadcastName(TEST_BROADCAST_NAME)
                        .setBroadcastCode(broadcastCode)
                        .setPublicBroadcastMetadata(publicMetaBuilder.build());
        // builder expect at least one subgroup setting
        for (int i = 0; i < numOfGroups; i++) {
            // add subgroup settings with the same content
            builder.addSubgroupSettings(subgroupBuilder.build());
        }
        return builder.build();
    }
}
