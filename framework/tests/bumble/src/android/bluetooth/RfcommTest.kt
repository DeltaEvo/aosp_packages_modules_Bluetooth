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
package android.bluetooth

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pandora.RfcommProto
import pandora.RfcommProto.ServerId
import pandora.RfcommProto.StartServerRequest
import pandora.SecurityProto.PairingEvent
import pandora.SecurityProto.PairingEventAnswer

@kotlinx.coroutines.ExperimentalCoroutinesApi
fun bondingFlow(context: Context, peer: BluetoothDevice, state: Int): Flow<Intent> {
    val channel = Channel<Intent>(Channel.UNLIMITED)
    val receiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (
                    peer ==
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                ) {
                    if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == state) {
                        channel.trySendBlocking(intent)
                    }
                }
            }
        }
    context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    channel.invokeOnClose { context.unregisterReceiver(receiver) }
    return channel.consumeAsFlow()
}

class PairingResponder(
    private val mPeer: BluetoothDevice,
    private val mPairingEventIterator: Iterator<PairingEvent>,
    private val mPairingEventAnswerObserver: StreamObserver<PairingEventAnswer>
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                if (
                    mPeer ==
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                ) {
                    if (
                        BluetoothDevice.PAIRING_VARIANT_CONSENT ==
                            intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    ) {
                        mPeer.setPairingConfirmation(true)
                        val pairingEvent: PairingEvent = mPairingEventIterator.next()
                        Truth.assertThat(pairingEvent.hasJustWorks()).isTrue()
                        mPairingEventAnswerObserver.onNext(
                            PairingEventAnswer.newBuilder()
                                .setEvent(pairingEvent)
                                .setConfirm(true)
                                .build()
                        )
                    }
                }
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class RfcommTest {
    private val mContext = ApplicationProvider.getApplicationContext<Context>()
    private val mManager = mContext.getSystemService(BluetoothManager::class.java)
    private val mAdapter = mManager!!.adapter

    // Gives shell permissions during the test.
    @Rule
    @JvmField
    val mPermissionsRule =
        AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_PRIVILEGED
        )

    // Set up a Bumble Pandora device for the duration of the test.
    @Rule @JvmField val mBumble = PandoraDevice()

    private lateinit var mBumbleDevice: BluetoothDevice
    private lateinit var mPairingResponder: PairingResponder
    private lateinit var mPairingEventAnswerObserver: StreamObserver<PairingEventAnswer>
    private val mPairingEventStreamObserver: StreamObserverSpliterator<PairingEvent> =
        StreamObserverSpliterator()
    private var mConnectionCounter = 1

    @Before
    fun setUp() {
        mBumbleDevice = mBumble.remoteDevice
        mPairingEventAnswerObserver =
            mBumble
                .security()
                .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .onPairing(mPairingEventStreamObserver)

        val pairingFilter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
        mPairingResponder =
            PairingResponder(
                mBumbleDevice,
                mPairingEventStreamObserver.iterator(),
                mPairingEventAnswerObserver
            )
        mContext.registerReceiver(mPairingResponder, pairingFilter)

        // TODO: Ideally we shouldn't need this, remove
        runBlocking { removeBondIfBonded(mBumbleDevice) }
    }

    @After
    fun tearDown() {
        mContext.unregisterReceiver(mPairingResponder)
    }

    @Test
    fun clientConnectToOpenServerSocketBondedInsecure() {
        startServer { serverId ->
            runBlocking { withTimeout(BOND_TIMEOUT.toMillis()) { bondDevice(mBumbleDevice) } }

            createConnectAcceptSocket(isSecure = false, serverId)
        }
    }

    @Test
    fun clientConnectToOpenServerSocketBondedSecure() {
        startServer { serverId ->
            runBlocking { withTimeout(BOND_TIMEOUT.toMillis()) { bondDevice(mBumbleDevice) } }

            createConnectAcceptSocket(isSecure = true, serverId)
        }
    }

    @Test
    fun clientSendDataOverInsecureSocket() {
        startServer { serverId ->
            runBlocking { withTimeout(BOND_TIMEOUT.toMillis()) { bondDevice(mBumbleDevice) } }

            val (insecureSocket, connection) = createConnectAcceptSocket(isSecure = false, serverId)
            val data: ByteArray = "Test data for clientSendDataOverInsecureSocket".toByteArray()
            val socketOs = insecureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    @Test
    fun clientSendDataOverSecureSocket() {
        startServer { serverId ->
            runBlocking { withTimeout(BOND_TIMEOUT.toMillis()) { bondDevice(mBumbleDevice) } }

            val (secureSocket, connection) = createConnectAcceptSocket(isSecure = true, serverId)
            val data: ByteArray = "Test data for clientSendDataOverSecureSocket".toByteArray()
            val socketOs = secureSocket.outputStream

            socketOs.write(data)
            val rxResponse: RfcommProto.RxResponse =
                mBumble
                    .rfcommBlocking()
                    .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .receive(RfcommProto.RxRequest.newBuilder().setConnection(connection).build())
            Truth.assertThat(rxResponse.data).isEqualTo(ByteString.copyFrom(data))
        }
    }

    @Test
    fun clientReceiveDataOverInsecureSocket() {
        startServer { serverId ->
            runBlocking { withTimeout(BOND_TIMEOUT.toMillis()) { bondDevice(mBumbleDevice) } }

            val (insecureSocket, connection) = createConnectAcceptSocket(isSecure = false, serverId)
            val buffer = ByteArray(64)
            val socketIs = insecureSocket.inputStream
            val data: ByteString =
                ByteString.copyFromUtf8("Test data for clientReceiveDataOverInsecureSocket")

            val txRequest =
                RfcommProto.TxRequest.newBuilder().setConnection(connection).setData(data).build()
            mBumble.rfcommBlocking().send(txRequest)
            val numBytesFromBumble = socketIs.read(buffer)
            Truth.assertThat(ByteString.copyFrom(buffer).substring(0, numBytesFromBumble))
                .isEqualTo(data)
        }
    }

    @Test
    fun clientReceiveDataOverSecureSocket() {
        startServer { serverId ->
            runBlocking { withTimeout(BOND_TIMEOUT.toMillis()) { bondDevice(mBumbleDevice) } }

            val (secureSocket, connection) = createConnectAcceptSocket(isSecure = true, serverId)
            val buffer = ByteArray(64)
            val socketIs = secureSocket.inputStream
            val data: ByteString =
                ByteString.copyFromUtf8("Test data for clientReceiveDataOverSecureSocket")

            val txRequest =
                RfcommProto.TxRequest.newBuilder().setConnection(connection).setData(data).build()
            mBumble.rfcommBlocking().send(txRequest)
            val numBytesFromBumble = socketIs.read(buffer)
            Truth.assertThat(ByteString.copyFrom(buffer).substring(0, numBytesFromBumble))
                .isEqualTo(data)
        }
    }

    private fun createConnectAcceptSocket(
        isSecure: Boolean,
        server: ServerId,
        uuid: String = TEST_UUID
    ): Pair<BluetoothSocket, RfcommProto.RfcommConnection> {
        val socket = createSocket(mBumbleDevice, isSecure, uuid)

        val connection = acceptSocket(server)
        Truth.assertThat(socket.isConnected).isTrue()

        return Pair(socket, connection)
    }

    private fun createSocket(
        device: BluetoothDevice,
        isSecure: Boolean,
        uuid: String
    ): BluetoothSocket {
        val socket =
            if (isSecure) {
                device.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
            } else {
                device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(uuid))
            }
        socket.connect()
        return socket
    }

    private fun acceptSocket(server: ServerId): RfcommProto.RfcommConnection {
        val connectionResponse =
            mBumble
                .rfcommBlocking()
                .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .acceptConnection(
                    RfcommProto.AcceptConnectionRequest.newBuilder().setServer(server).build()
                )
        Truth.assertThat(connectionResponse.connection.id).isEqualTo(mConnectionCounter)

        mConnectionCounter += 1
        return connectionResponse.connection
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun bondDevice(remoteDevice: BluetoothDevice) {
        if (mAdapter.bondedDevices.contains(remoteDevice)) {
            Log.d(TAG, "bondDevice(): The device is already bonded")
            return
        }

        val flow = bondingFlow(mContext, remoteDevice, BluetoothDevice.BOND_BONDED)

        Truth.assertThat(remoteDevice.createBond()).isTrue()

        flow.first()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun removeBondIfBonded(deviceToRemove: BluetoothDevice) {
        if (!mAdapter.bondedDevices.contains(deviceToRemove)) {
            Log.d(TAG, "removeBondIfBonded(): Tried to remove a device that isn't bonded")
            return
        }
        val flow = bondingFlow(mContext, deviceToRemove, BluetoothDevice.BOND_NONE)

        Truth.assertThat(deviceToRemove.removeBond()).isTrue()

        flow.first()
    }

    private fun startServer(
        name: String = TEST_SERVER_NAME,
        uuid: String = TEST_UUID,
        block: (ServerId) -> Unit
    ) {
        val request = StartServerRequest.newBuilder().setName(name).setUuid(uuid).build()
        val response = mBumble.rfcommBlocking().startServer(request)

        try {
            block(response.server)
        } finally {
            mBumble
                .rfcommBlocking()
                .withDeadlineAfter(GRPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .stopServer(
                    RfcommProto.StopServerRequest.newBuilder().setServer(response.server).build()
                )
            runBlocking { removeBondIfBonded(mBumbleDevice) }
        }
    }

    companion object {
        private val TAG = RfcommTest::class.java.getSimpleName()
        private val GRPC_TIMEOUT = Duration.ofSeconds(10)
        private val BOND_TIMEOUT = Duration.ofSeconds(20)
        private const val TEST_UUID = "2ac5d8f1-f58d-48ac-a16b-cdeba0892d65"
        private const val SERIAL_PORT_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val TEST_SERVER_NAME = "RFCOMM Server"
    }
}
