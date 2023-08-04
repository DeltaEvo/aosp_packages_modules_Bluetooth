package com.android.pandora

import android.bluetooth.BluetoothHidHost
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.grpc.stub.StreamObserver
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import pandora.HIDGrpc.HIDImplBase
import pandora.HidProto.SendHostReportRequest
import pandora.HidProto.SendHostReportResponse

@kotlinx.coroutines.ExperimentalCoroutinesApi
class Hid(val context: Context) : HIDImplBase(), Closeable {
    private val TAG = "PandoraHid"

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothHidHost =
        getProfileProxy<BluetoothHidHost>(context, BluetoothProfile.HID_HOST)

    override fun close() {
        // Deinit the CoroutineScope
        scope.cancel()
    }

    override fun sendHostReport(
        request: SendHostReportRequest,
        responseObserver: StreamObserver<SendHostReportResponse>,
    ) {
        grpcUnary(scope, responseObserver) {
            bluetoothHidHost.setReport(
                request.address.toBluetoothDevice(bluetoothAdapter),
                request.reportType.number.toByte(),
                request.report
            )
            SendHostReportResponse.getDefaultInstance()
        }
    }
}
