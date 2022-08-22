# Copyright 2022 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""AVRCP proxy module."""

import time
from typing import Optional

from grpc import RpcError

from mmi2grpc._audio import AudioSignal
from mmi2grpc._helpers import assert_description
from mmi2grpc._proxy import ProfileProxy
from pandora.a2dp_grpc import A2DP
from pandora.a2dp_pb2 import Sink, Source
from pandora.avrcp_grpc import AVRCP
from pandora.host_grpc import Host
from pandora.host_pb2 import Connection


class AVRCPProxy(ProfileProxy):
    """AVRCP proxy.

    Implements AVRCP and AVCTP PTS MMIs.
    """

    connection: Optional[Connection] = None
    sink: Optional[Sink] = None
    source: Optional[Source] = None

    def __init__(self, channel):
        super().__init__()

        self.host = Host(channel)
        self.a2dp = A2DP(channel)
        self.avrcp = AVRCP(channel)

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_connect(self, test: str, pts_addr: bytes, **kwargs):
        """
        If necessary, take action to accept the AVDTP Signaling Channel
        Connection initiated by the tester.

        Description: Make sure the IUT
        (Implementation Under Test) is in a state to accept incoming Bluetooth
        connections.  Some devices may need to be on a specific screen, like a
        Bluetooth settings screen, in order to pair with PTS.  If the IUT is
        still having problems pairing with PTS, try running a test case where
        the IUT connects to PTS to establish pairing.

        """
        if "CT" in test:

            self.connection = self.host.WaitConnection(address=pts_addr).connection
            try:
                self.source = self.a2dp.WaitSource(connection=self.connection).source
            except RpcError:
                pass
        else:
            self.connection = self.host.WaitConnection(address=pts_addr).connection
            try:
                self.sink = self.a2dp.WaitSink(connection=self.connection).sink
            except RpcError:
                pass
        return "OK"

    @assert_description
    def TSC_AVCTP_mmi_iut_accept_connect_control(self, **kwargs):
        """
        Please wait while PTS creates an AVCTP control channel connection.
        Action: Make sure the IUT is in a connectable state.

        """
        #TODO: Wait for connection to be established and AVCTP control channel to be open
        return "OK"

    @assert_description
    def TSC_AVCTP_mmi_iut_accept_disconnect_control(self, **kwargs):
        """
        Please wait while PTS disconnects the AVCTP control channel connection.

        """
        return "OK"

    @assert_description
    def TSC_AVRCP_mmi_iut_accept_unit_info(self, **kwargs):
        """
        Take action to send a valid response to the [Unit Info] command sent by
        the PTS.

        """
        return "OK"

    @assert_description
    def TSC_AVRCP_mmi_iut_accept_subunit_info(self, **kwargs):
        """
        Take action to send a valid response to the [Subunit Info] command sent
        by the PTS.

        """
        return "OK"

    @assert_description
    def TSC_AVCTP_mmi_iut_accept_connect_browsing(self, **kwargs):
        """
        Please wait while PTS creates an AVCTP browsing channel connection.
        Action: Make sure the IUT is in a connectable state.

        """
        return "OK"

    @assert_description
    def TSC_AVRCP_mmi_iut_accept_get_folder_items_media_player_list(self, **kwargs):
        """
        Take action to send a valid response to the [Get Folder Items] with the
        scope <Media Player List> command sent by the PTS.

        """
        return "OK"

    @assert_description
    def TSC_AVRCP_mmi_user_confirm_media_players(self, **kwargs):
        """
        Do the following media players exist on the IUT?

        Media Player:
        Bluetooth Player


        Note: Some media players may not be listed above.

        """
        #TODO: Verify the media players available
        return "OK"

    @assert_description
    def TSC_AVP_mmi_iut_initiate_disconnect(self, **kwargs):
        """
        Take action to disconnect all A2DP and/or AVRCP connections.

        """
        if self.connection is None:
            self.connection = self.host.GetConnection(address=pts_addr).connection
        self.host.Disconnect(connection=self.connection)
        self.connection = None
        self.sink = None
        self.source = None
        return "OK"

    @assert_description
    def TSC_AVRCP_mmi_iut_accept_set_addressed_player(self, **kwargs):
        """
        Take action to send a valid response to the [Set Addressed Player]
        command sent by the PTS.

        """
        return "OK"