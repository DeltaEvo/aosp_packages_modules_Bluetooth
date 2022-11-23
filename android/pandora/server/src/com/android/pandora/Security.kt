/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.pandora

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST
import android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC
import android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE
import android.bluetooth.BluetoothDevice.EXTRA_PAIRING_VARIANT
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import pandora.HostProto.*
import pandora.SecurityGrpc.SecurityImplBase
import pandora.SecurityProto.*

private const val TAG = "PandoraSecurity"

@kotlinx.coroutines.ExperimentalCoroutinesApi
class Security(private val context: Context) : SecurityImplBase() {

  private val globalScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
  private val flow: Flow<Intent>

  private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)!!
  private val bluetoothAdapter = bluetoothManager.adapter

  init {
    val intentFilter = IntentFilter()
    intentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
    intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)

    flow = intentFlow(context, intentFilter).shareIn(globalScope, SharingStarted.Eagerly)
  }

  fun deinit() {
    globalScope.cancel()
  }

  override fun pair(request: PairRequest, responseObserver: StreamObserver<Empty>) {
    grpcUnary(globalScope, responseObserver) {
      val bluetoothDevice = request.connection.toBluetoothDevice(bluetoothAdapter)
      Log.i(
        TAG,
        "pair: ${bluetoothDevice.address} (current bond state: ${bluetoothDevice.bondState})"
      )
      Log.d(TAG, "transport: ${request.connection.transport}")
      bluetoothDevice.createBond(request.connection.transport)

      Empty.getDefaultInstance()
    }
  }

  override fun deletePairing(
    request: DeletePairingRequest,
    responseObserver: StreamObserver<DeletePairingResponse>
  ) {
    grpcUnary(globalScope, responseObserver) {
      val bluetoothDevice = request.address.toBluetoothDevice(bluetoothAdapter)
      Log.i(TAG, "DeletePairing: device=$bluetoothDevice")

      val unbonded =
        globalScope.async {
          flow
            .filter { it.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED }
            .filter { it.getBluetoothDeviceExtra() == bluetoothDevice }
            .filter {
              it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothAdapter.ERROR) ==
                BluetoothDevice.BOND_NONE
            }
            .first()
        }

      if (bluetoothDevice.removeBond()) {
        Log.i(TAG, "DeletePairing: device=$bluetoothDevice - wait BOND_NONE intent")
        unbonded.await()
      } else {
        Log.i(TAG, "DeletePairing: device=$bluetoothDevice - Already unpaired")
        unbonded.cancel()
      }
      DeletePairingResponse.getDefaultInstance()
    }
  }

  override fun onPairing(
    responseObserver: StreamObserver<PairingEvent>
  ): StreamObserver<PairingEventAnswer> =
    grpcBidirectionalStream(globalScope, responseObserver) {
      Log.i(TAG, "OnPairing: Starting stream")
      it
        .map { answer ->
          Log.i(
            TAG,
            "OnPairing: Handling PairingEventAnswer ${answer.answerCase} for device ${answer.event.address}"
          )
          val device = answer.event.address.toBluetoothDevice(bluetoothAdapter)
          when (answer.answerCase!!) {
            PairingEventAnswer.AnswerCase.CONFIRM -> device.setPairingConfirmation(true)
            PairingEventAnswer.AnswerCase.PASSKEY ->
              device.setPin(answer.passkey.toString().toByteArray())
            PairingEventAnswer.AnswerCase.PIN -> device.setPin(answer.pin.toByteArray())
            PairingEventAnswer.AnswerCase.ANSWER_NOT_SET -> error("unexpected pairing answer type")
          }
        }
        .launchIn(this)

      flow
        .filter { intent -> intent.action == ACTION_PAIRING_REQUEST }
        .map { intent ->
          val device = intent.getBluetoothDeviceExtra()
          val variant = intent.getIntExtra(EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
          Log.i(TAG, "OnPairing: Handling PairingEvent ${variant} for device ${device.address}")
          val eventBuilder = PairingEvent.newBuilder().setAddress(device.toByteString())
          when (variant) {
            // SSP / LE Just Works
            BluetoothDevice.PAIRING_VARIANT_CONSENT ->
              eventBuilder.justWorks = Empty.getDefaultInstance()

            // SSP / LE Numeric Comparison
            BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ->
              eventBuilder.numericComparison =
                intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
            BluetoothDevice.PAIRING_VARIANT_DISPLAY_PASSKEY -> {
              val passkey =
                intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
              Log.i(TAG, "OnPairing: passkey=${passkey}")
              eventBuilder.passkeyEntryNotification = passkey
            }

            // Out-Of-Band not currently supported
            BluetoothDevice.PAIRING_VARIANT_OOB_CONSENT ->
              error("Received OOB pairing confirmation (UNSUPPORTED)")

            // Legacy PIN entry, or LE legacy passkey entry, depending on transport
            BluetoothDevice.PAIRING_VARIANT_PIN ->
              when (device.type) {
                DEVICE_TYPE_CLASSIC -> eventBuilder.pinCodeRequest = Empty.getDefaultInstance()
                DEVICE_TYPE_LE -> eventBuilder.passkeyEntryRequest = Empty.getDefaultInstance()
                else ->
                  error(
                    "cannot determine pairing variant, since transport is unknown: ${device.type}"
                  )
              }
            BluetoothDevice.PAIRING_VARIANT_PIN_16_DIGITS ->
              eventBuilder.pinCodeRequest = Empty.getDefaultInstance()

            // Legacy PIN entry or LE legacy passkey entry, except we just generate the PIN in
            // the
            // stack and display it to the user for convenience
            BluetoothDevice.PAIRING_VARIANT_DISPLAY_PIN -> {
              val passkey =
                intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
              when (device.type) {
                DEVICE_TYPE_CLASSIC ->
                  eventBuilder.pinCodeNotification =
                    ByteString.copyFrom(passkey.toString().toByteArray())
                DEVICE_TYPE_LE -> eventBuilder.passkeyEntryNotification = passkey
                else -> error("cannot determine pairing variant, since transport is unknown")
              }
            }
            else -> {
              error("Received unknown pairing variant $variant")
            }
          }
          eventBuilder.build()
        }
    }
}
