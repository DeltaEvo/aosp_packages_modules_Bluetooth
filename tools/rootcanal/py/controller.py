import asyncio
import collections
import enum
import hci_packets as hci
import link_layer_packets as ll
import py.bluetooth
import sys
import typing
import unittest
from typing import Optional
from hci_packets import ErrorCode

from ctypes import *

rootcanal = cdll.LoadLibrary("lib_rootcanal_ffi.so")
rootcanal.ffi_controller_new.restype = c_void_p

SEND_HCI_FUNC = CFUNCTYPE(None, c_int, POINTER(c_ubyte), c_size_t)
SEND_LL_FUNC = CFUNCTYPE(None, POINTER(c_ubyte), c_size_t, c_int, c_int)


class Idc(enum.IntEnum):
    Cmd = 1
    Acl = 2
    Sco = 3
    Evt = 4
    Iso = 5


class Phy(enum.IntEnum):
    LowEnergy = 0
    BrEdr = 1


class LeFeatures:

    def __init__(self, le_features: int):
        self.mask = le_features
        self.ll_privacy = (le_features & hci.LLFeaturesBits.LL_PRIVACY) != 0
        self.le_extended_advertising = (le_features & hci.LLFeaturesBits.LE_EXTENDED_ADVERTISING) != 0
        self.le_periodic_advertising = (le_features & hci.LLFeaturesBits.LE_PERIODIC_ADVERTISING) != 0


def generate_rpa(irk: bytes) -> hci.Address:
    rpa = bytearray(6)
    rpa_type = c_char * 6
    rootcanal.ffi_generate_rpa(c_char_p(irk), rpa_type.from_buffer(rpa))
    return hci.Address(bytes(rpa))


class Controller:
    """Binder class over RootCanal's ffi interfaces.
    The methods send_cmd, send_hci, send_ll are used to inject HCI or LL
    packets into the controller, and receive_hci, receive_ll to
    catch outgoing HCI packets of LL pdus."""

    def __init__(self, address: hci.Address):
        # Write the callbacks for handling HCI and LL send events.
        @SEND_HCI_FUNC
        def send_hci(idc: c_int, data: POINTER(c_ubyte), data_len: c_size_t):
            packet = []
            for n in range(data_len):
                packet.append(data[n])
            self.receive_hci_(int(idc), bytes(packet))

        @SEND_LL_FUNC
        def send_ll(data: POINTER(c_ubyte), data_len: c_size_t, phy: c_int, tx_power: c_int):
            packet = []
            for n in range(data_len):
                packet.append(data[n])
            self.receive_ll_(bytes(packet), int(phy), int(tx_power))

        self.send_hci_callback = SEND_HCI_FUNC(send_hci)
        self.send_ll_callback = SEND_LL_FUNC(send_ll)

        # Create a c++ controller instance.
        self.instance = rootcanal.ffi_controller_new(c_char_p(address.address), self.send_hci_callback,
                                                     self.send_ll_callback)

        self.address = address
        self.evt_queue = collections.deque()
        self.acl_queue = collections.deque()
        self.ll_queue = collections.deque()
        self.evt_queue_event = asyncio.Event()
        self.acl_queue_event = asyncio.Event()
        self.ll_queue_event = asyncio.Event()

    def __del__(self):
        rootcanal.ffi_controller_delete(c_void_p(self.instance))

    def receive_hci_(self, idc: int, packet: bytes):
        if idc == Idc.Evt:
            print(f"<-- received HCI event data={len(packet)}[..]")
            self.evt_queue.append(packet)
            self.evt_queue_event.set()
        elif idc == Idc.Acl:
            print(f"<-- received HCI ACL packet data={len(packet)}[..]")
            self.acl_queue.append(packet)
            self.acl_queue_event.set()
        else:
            print(f"ignoring HCI packet typ={typ}")

    def receive_ll_(self, packet: bytes, phy: int, tx_power: int):
        print(f"<-- received LL pdu data={len(packet)}[..]")
        self.ll_queue.append(packet)
        self.ll_queue_event.set()

    def send_cmd(self, cmd: hci.Command):
        print(f"--> sending HCI command {cmd.__class__.__name__}")
        data = cmd.serialize()
        rootcanal.ffi_controller_receive_hci(c_void_p(self.instance), c_int(Idc.Cmd), c_char_p(data), c_int(len(data)))

    def send_ll(self, pdu: ll.LinkLayerPacket, phy: Phy = Phy.LowEnergy, rssi: int = -90):
        print(f"--> sending LL pdu {pdu.__class__.__name__}")
        data = pdu.serialize()
        rootcanal.ffi_controller_receive_ll(c_void_p(self.instance), c_char_p(data), c_int(len(data)), c_int(phy),
                                            c_int(rssi))

    async def start(self):

        async def timer():
            while True:
                await asyncio.sleep(0.005)
                rootcanal.ffi_controller_tick(c_void_p(self.instance))

        # Spawn the controller timer task.
        self.timer_task = asyncio.create_task(timer())

    def stop(self):
        # Cancel the controller timer task.
        del self.timer_task

        if self.evt_queue:
            print("evt queue not empty at stop():")
            for packet in self.evt_queue:
                evt = hci.Event.parse_all(packet)
                evt.show()
            raise Exception("evt queue not empty at stop()")

        if self.ll_queue:
            for (packet, _) in self.ll_queue:
                pdu = ll.LinkLayerPacket.parse_all(packet)
                pdu.show()
            raise Exception("ll queue not empty at stop()")

    async def receive_evt(self):
        while not self.evt_queue:
            await self.evt_queue_event.wait()
            self.evt_queue_event.clear()
        return self.evt_queue.popleft()

    async def expect_evt(self, expected_evt: hci.Event):
        packet = await self.receive_evt()
        evt = hci.Event.parse_all(packet)
        if evt != expected_evt:
            print("received unexpected event")
            print("expected event:")
            expected_evt.show()
            print("received event:")
            evt.show()
            raise Exception(f"unexpected evt {evt.__class__.__name__}")

    async def receive_ll(self):
        while not self.ll_queue:
            await self.ll_queue_event.wait()
            self.ll_queue_event.clear()
        return self.ll_queue.popleft()


class ControllerTest(unittest.IsolatedAsyncioTestCase):
    """Helper class for writing controller tests using the python bindings.
    The test setups the controller sending the Reset command and configuring
    the event masks to allow all events. The local device address is
    always configured as 11:11:11:11:11:11."""

    def setUp(self):
        self.controller = Controller(hci.Address('11:11:11:11:11:11'))

    async def asyncSetUp(self):
        controller = self.controller

        # Start the controller timer.
        await controller.start()

        # Reset the controller and enable all events and LE events.
        controller.send_cmd(hci.Reset())
        await controller.expect_evt(hci.ResetComplete(status=ErrorCode.SUCCESS, num_hci_command_packets=1))
        controller.send_cmd(hci.SetEventMask(event_mask=0xffffffffffffffff))
        await controller.expect_evt(hci.SetEventMaskComplete(status=ErrorCode.SUCCESS, num_hci_command_packets=1))
        controller.send_cmd(hci.LeSetEventMask(le_event_mask=0xffffffffffffffff))
        await controller.expect_evt(hci.LeSetEventMaskComplete(status=ErrorCode.SUCCESS, num_hci_command_packets=1))

        # Load the local supported features to be able to disable tests
        # that rely on unsupported features.
        controller.send_cmd(hci.LeReadLocalSupportedFeatures())
        evt = await self.expect_cmd_complete(hci.LeReadLocalSupportedFeaturesComplete)
        controller.le_features = LeFeatures(evt.le_features)

    async def expect_evt(self, expected_evt: hci.Event, timeout: int = 3):
        packet = await asyncio.wait_for(self.controller.receive_evt(), timeout=timeout)
        evt = hci.Event.parse_all(packet)

        if evt != expected_evt:
            print("received unexpected event")
            print("expected event:")
            expected_evt.show()
            print("received event:")
            evt.show()
            self.assertTrue(False)

    async def expect_cmd_complete(self, expected_evt: type, timeout: int = 3) -> hci.Event:
        packet = await asyncio.wait_for(self.controller.receive_evt(), timeout=timeout)
        evt = hci.Event.parse_all(packet)

        if not isinstance(evt, expected_evt):
            print("received unexpected event")
            print("expected event:")
            print(expected_evt)
            print("received event:")
            evt.show()
            self.assertTrue(False)

        assert evt.status == ErrorCode.SUCCESS
        assert evt.num_hci_command_packets == 1
        return evt

    async def expect_ll(self,
                        expected_pdus: typing.Union[list, typing.Union[ll.LinkLayerPacket, type]],
                        timeout: int = 3) -> int:
        if not isinstance(expected_pdus, list):
            expected_pdus = [expected_pdus]

        packet = await asyncio.wait_for(self.controller.receive_ll(), timeout=timeout)
        pdu = ll.LinkLayerPacket.parse_all(packet)

        matched_index = -1
        for (index, expected_pdu) in enumerate(expected_pdus):
            if isinstance(expected_pdu, type) and isinstance(pdu, expected_pdu):
                return index
            if isinstance(expected_pdu, ll.LinkLayerPacket) and pdu == expected_pdu:
                return index

        print("received unexpected pdu:")
        pdu.show()
        print("expected pdus:")
        for expected_pdu in expected_pdus:
            if isinstance(expected_pdu, type):
                print(f"- {expected_pdu.__name__}")
            if isinstance(expected_pdu, ll.LinkLayerPacket):
                print(f"- {expected_pdu.__class__.__name__}")
                expected_pdu.show()

        self.assertTrue(False)

    def tearDown(self):
        self.controller.stop()
