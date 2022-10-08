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

import android.content.Context
import android.util.Log
import io.grpc.Server as GrpcServer
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder

@kotlinx.coroutines.ExperimentalCoroutinesApi
class Server(context: Context) {

  private val TAG = "PandoraServer"
  private val GRPC_PORT = 8999

  private var host: Host
  private var a2dp: A2dp
  private var avrcp: Avrcp
  private var gatt: Gatt
  private var hfp: Hfp
  private var hid: Hid
  private var security: Security
  private var grpcServer: GrpcServer

  init {
    host = Host(context, this)
    a2dp = A2dp(context)
    avrcp = Avrcp(context)
    gatt = Gatt(context)
    hfp = Hfp(context)
    hid = Hid(context)
    security = Security(context)
    grpcServer =
      NettyServerBuilder.forPort(GRPC_PORT)
        .addService(host)
        .addService(a2dp)
        .addService(avrcp)
        .addService(gatt)
        .addService(hfp)
        .addService(hid)
        .addService(security)
        .build()

    Log.d(TAG, "Starting Pandora Server")
    grpcServer.start()
    Log.d(TAG, "Pandora Server started at $GRPC_PORT")
  }

  fun shutdown() = grpcServer.shutdown()

  fun awaitTermination() = grpcServer.awaitTermination()

  fun deinit() {
    host.deinit()
    a2dp.deinit()
    avrcp.deinit()
    gatt.deinit()
    hfp.deinit()
    hid.deinit()
    security.deinit()
  }
}
