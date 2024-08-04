# Copyright (C) 2024 The Android Open Source Project
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import asyncio
import logging
from typing import Dict, Optional

from bumble import core
from bumble.device import Device
from bumble.rfcomm import (
    Server,
    make_service_sdp_records,
    DLC,
)
from bumble.pandora import utils
import grpc
from pandora_experimental.rfcomm_grpc_aio import RFCOMMServicer
from pandora_experimental.rfcomm_pb2 import (
    AcceptConnectionRequest,
    AcceptConnectionResponse,
    ConnectionRequest,
    ConnectionResponse,
    RfcommConnection,
    RxRequest,
    RxResponse,
    ServerId,
    StartServerRequest,
    StartServerResponse,
    StopServerRequest,
    StopServerResponse,
    TxRequest,
    TxResponse,
)

FIRST_SERVICE_RECORD_HANDLE = 0x00010010


class RFCOMMService(RFCOMMServicer):
    device: Device

    def __init__(self, device: Device) -> None:
        super().__init__()
        self.server = None
        self.device = device
        self.server_ports = {}  # key = channel, value = ServerInstance
        self.connections = {}  # key = id, value = dlc
        self.next_conn_id = 1
        self.next_scn = 7

    class Connection:

        def __init__(self, dlc):
            self.dlc = dlc
            self.data_queue = asyncio.Queue()

    class ServerPort:

        def __init__(self, name, uuid, wait_dlc):
            self.name = name
            self.uuid = uuid
            self.wait_dlc = wait_dlc
            self.accepted = False
            self.saved_dlc = None

        def accept(self):
            self.accepted = True
            if self.saved_dlc is not None:
                self.wait_dlc.set_result(self.saved_dlc)

        def acceptor(self, dlc):
            if self.accepted:
                self.wait_dlc.set_result(dlc)
            else:
                self.saved_dlc = dlc

    @utils.rpc
    async def StartServer(self, request: StartServerRequest, context: grpc.ServicerContext) -> StartServerResponse:
        uuid = core.UUID(request.uuid)
        logging.info(f"StartServer {uuid}")

        if self.server is None:
            self.server = Server(self.device)

        for existing_id, port in self.server_ports.items():
            if port.uuid == uuid:
                logging.warning(f"Server port already started for {uuid}, returning existing port")
                return StartServerResponse(server=ServerId(id=existing_id))

        wait_dlc = asyncio.get_running_loop().create_future()
        server_port = self.ServerPort(name=request.name, uuid=uuid, wait_dlc=wait_dlc)
        open_channel = self.server.listen(acceptor=server_port.acceptor, channel=self.next_scn)
        self.next_scn += 1
        handle = FIRST_SERVICE_RECORD_HANDLE + open_channel
        self.device.sdp_service_records[handle] = make_service_sdp_records(handle, open_channel, uuid)
        self.server_ports[open_channel] = server_port
        return StartServerResponse(server=ServerId(id=open_channel))

    @utils.rpc
    async def AcceptConnection(self, request: AcceptConnectionRequest,
                               context: grpc.ServicerContext) -> AcceptConnectionResponse:
        logging.info(f"AcceptConnection")
        assert self.server_ports[request.server.id] is not None
        self.server_ports[request.server.id].accept()
        dlc = await self.server_ports[request.server.id].wait_dlc
        id = self.next_conn_id
        self.next_conn_id += 1
        self.connections[id] = self.Connection(dlc=dlc)
        self.connections[id].dlc.sink = self.connections[id].data_queue.put_nowait
        return AcceptConnectionResponse(connection=RfcommConnection(id=id))

    @utils.rpc
    async def StopServer(self, request: StopServerRequest, context: grpc.ServicerContext) -> StopServerResponse:
        logging.info(f"StopServer")
        assert self.server_ports[request.server.id] is not None
        self.server_ports[request.server.id] = None

        return StopServerResponse()

    @utils.rpc
    async def Send(self, request: TxRequest, context: grpc.ServicerContext) -> TxResponse:
        logging.info(f"Send")
        assert self.connections[request.connection.id] is not None
        self.connections[request.connection.id].dlc.write(request.data)
        return TxResponse()

    @utils.rpc
    async def Receive(self, request: RxRequest, context: grpc.ServicerContext) -> RxResponse:
        logging.info(f"Receive")
        assert self.connections[request.connection.id] is not None
        received_data = await self.connections[request.connection.id].data_queue.get()
        return RxResponse(data=received_data)
