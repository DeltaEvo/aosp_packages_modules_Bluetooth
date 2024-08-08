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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@kotlinx.coroutines.ExperimentalCoroutinesApi
public class Host(context: Context) : Closeable {
    private val TAG = "PandoraHost"

    private val flow: Flow<Intent>
    private val scope: CoroutineScope
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter = bluetoothManager!!.adapter

    init {
        scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)

        flow = intentFlow(context, intentFilter, scope).shareIn(scope, SharingStarted.Eagerly)
    }

    override fun close() {
        scope.cancel()
    }

    public fun createBondAndVerify(remoteDevice: BluetoothDevice) {
        Log.d(TAG, "createBondAndVerify: $remoteDevice")
        if (bluetoothAdapter.bondedDevices.contains(remoteDevice)) {
            Log.d(TAG, "createBondAndVerify: already bonded")
            return
        }

        runBlocking(scope.coroutineContext) {
            withTimeout(TIMEOUT) {
                Truth.assertThat(remoteDevice.createBond()).isTrue()
                val pairingRequestJob = launch {
                    Log.d(TAG, "Waiting for ACTION_PAIRING_REQUEST")
                    flow
                        .filter { it.action == BluetoothDevice.ACTION_PAIRING_REQUEST }
                        .filter { it.getBluetoothDeviceExtra() == remoteDevice }
                        .first()

                    remoteDevice.setPairingConfirmation(true)
                }

                Log.d(TAG, "Waiting for ACTION_BOND_STATE_CHANGED")
                flow
                    .filter { it.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED }
                    .filter { it.getBluetoothDeviceExtra() == remoteDevice }
                    .filter {
                        it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR) ==
                            BluetoothDevice.BOND_BONDED
                    }
                    .first()

                if (pairingRequestJob.isActive) {
                    pairingRequestJob.cancel()
                }

                Log.d(TAG, "createBondAndVerify: bonded")
            }
        }
    }

    fun removeBondAndVerify(remoteDevice: BluetoothDevice) {
        Log.d(TAG, "removeBondAndVerify: $remoteDevice")
        runBlocking(scope.coroutineContext) {
            withTimeout(TIMEOUT) {
                assertThat(remoteDevice.removeBond()).isTrue()
                flow
                    .filter { it.getAction() == BluetoothDevice.ACTION_BOND_STATE_CHANGED }
                    .filter { it.getBluetoothDeviceExtra() == remoteDevice }
                    .filter {
                        it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR) ==
                            BluetoothDevice.BOND_NONE
                    }
                Log.d(TAG, "removeBondAndVerify: done")
            }
        }
    }

    fun Intent.getBluetoothDeviceExtra(): BluetoothDevice =
        this.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)!!

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun intentFlow(context: Context, intentFilter: IntentFilter, scope: CoroutineScope) =
        callbackFlow {
            val broadcastReceiver: BroadcastReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.d(TAG, "intentFlow: onReceive: ${intent.action}")
                        scope.launch { trySendBlocking(intent) }
                    }
                }
            context.registerReceiver(broadcastReceiver, intentFilter)

            awaitClose { context.unregisterReceiver(broadcastReceiver) }
        }

    companion object {
        private val TIMEOUT = 20.seconds
    }
}
