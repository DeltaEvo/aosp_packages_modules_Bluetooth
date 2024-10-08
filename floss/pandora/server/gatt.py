# Copyright 2023 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""GATT grpc interface."""

import asyncio
import logging
from uuid import UUID

from floss.pandora.floss import adapter_client
from floss.pandora.floss import floss_enums
from floss.pandora.floss import gatt_client
from floss.pandora.floss import gatt_server
from floss.pandora.floss import utils
from floss.pandora.server import bluetooth as bluetooth_module
import grpc
from pandora_experimental import gatt_grpc_aio
from pandora_experimental import gatt_pb2


class GATTService(gatt_grpc_aio.GATTServicer):
    """Service to trigger Bluetooth GATT procedures.

    This class implements the Pandora bluetooth test interfaces,
    where the metaclass definition is automatically generated by the protobuf.
    The interface definition can be found in:
    https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Bluetooth/pandora/interfaces/pandora_experimental/gatt.proto?q=gatt.proto
    """

    # Write characteristic, requesting acknowledgement by the remote device.
    WRITE_TYPE_DEFAULT = 2
    # No authentication required.
    AUTHENTICATION_NONE = 0
    # Value used to enable indication for a client configuration descriptor.
    ENABLE_INDICATION_VALUE = [0x02, 0x00]
    # Value used to enable notification for a client configuration descriptor.
    ENABLE_NOTIFICATION_VALUE = [0x01, 0x00]
    # Key size (default = 16).
    KEY_SIZE = 16
    # Service type primary.
    SERVICE_TYPE_PRIMARY = 0
    # Instance id for service or characteristic or descriptor.
    DEFAULT_INSTANCE_ID = 0

    def __init__(self, bluetooth: bluetooth_module.Bluetooth):
        self.bluetooth = bluetooth
        self.characteristic_changed_map = {}

        # Register the observer for characteristic notifications.
        observer = self.SendCharacteristicNotificationObserver(asyncio.get_running_loop(), self)
        name = utils.create_observer_name(observer)
        self.bluetooth.gatt_client.register_callback_observer(name, observer)

    class SendCharacteristicNotificationObserver(gatt_client.GattClientCallbacks):

        def __init__(self, loop: asyncio.AbstractEventLoop, gatt_service):
            self.loop = loop
            self.gatt_service = gatt_service

        @utils.glib_callback()
        def on_notify(self, addr, handle, value):
            logging.info('Characteristic Notification Received. addr: %s, handle: %s', addr, handle)
            if addr not in self.gatt_service.characteristic_changed_map:
                self.gatt_service.characteristic_changed_map[addr] = {}
            char_map = self.gatt_service.characteristic_changed_map[addr]

            if handle in char_map:
                # Set the future to indicate that the characteristic has changed.
                char_future = char_map[handle]
                char_future.get_loop().call_soon_threadsafe(char_future.set_result, True)
            else:
                # Create and set the future in the map.
                char_future = self.loop.create_future()
                self.gatt_service.characteristic_changed_map[addr][handle] = char_future
                char_future.get_loop().call_soon_threadsafe(char_future.set_result, True)

    async def ExchangeMTU(self, request: gatt_pb2.ExchangeMTURequest,
                          context: grpc.ServicerContext) -> gatt_pb2.ExchangeMTUResponse:

        class MTUChangeObserver(gatt_client.GattClientCallbacks):
            """Observer to observe MTU change state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_configure_mtu(self, addr, mtu, status):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to configure MTU. Status: %s', status)
                future = self.task['configure_mtu']
                future.get_loop().call_soon_threadsafe(future.set_result, status)

        address = utils.connection_from(request.connection).address
        try:
            configure_mtu = asyncio.get_running_loop().create_future()
            observer = MTUChangeObserver({'configure_mtu': configure_mtu, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            self.bluetooth.configure_mtu(address, request.mtu)
            status = await configure_mtu
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Failed to configure MTU.')
        finally:
            self.bluetooth.gatt_client.unregister_callback_observer(name, observer)
        return gatt_pb2.ExchangeMTUResponse()

    async def WriteAttFromHandle(self, request: gatt_pb2.WriteRequest,
                                 context: grpc.ServicerContext) -> gatt_pb2.WriteResponse:

        class WriteAttObserver(gatt_client.GattClientCallbacks):
            """Observer to observe write attribute state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_characteristic_write(self, addr, status, handle):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to write characteristic from handle. Status: %s', status)
                future = self.task['write_attribute']
                future.get_loop().call_soon_threadsafe(future.set_result, (status, handle))

            @utils.glib_callback()
            def on_descriptor_write(self, addr, status, handle):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to write descriptor from handle. Status: %s', status)
                future = self.task['write_attribute']
                future.get_loop().call_soon_threadsafe(future.set_result, (status, handle))

        class ReadCharacteristicFromHandleObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the read characteristics state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_characteristic_read(self, addr, status, handle, value):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to read characteristic from handle. Status: %s', status)
                future = self.task['characteristics']
                future.get_loop().call_soon_threadsafe(future.set_result, status)

        class ReadCharacteristicDescriptorFromHandleObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the read characteristic descriptor state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_descriptor_read(self, addr, status, handle, value):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to read descriptors. Status: %s', status)
                future = self.task['descriptors']
                future.get_loop().call_soon_threadsafe(future.set_result, status)

        address = utils.connection_from(request.connection).address
        observers = []
        valid_handle = True
        try:
            write_attribute = asyncio.get_running_loop().create_future()
            observer = WriteAttObserver({'write_attribute': write_attribute, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            observers.append((name, observer))

            characteristics = asyncio.get_running_loop().create_future()
            observer = ReadCharacteristicFromHandleObserver({'characteristics': characteristics, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            observers.append((name, observer))

            self.bluetooth.read_characteristic(address, request.handle, self.AUTHENTICATION_NONE)
            char_status = await characteristics
            if char_status != floss_enums.GattStatus.SUCCESS:
                descriptors = asyncio.get_running_loop().create_future()
                observer = ReadCharacteristicDescriptorFromHandleObserver({
                    'descriptors': descriptors,
                    'address': address
                })
                name = utils.create_observer_name(observer)
                self.bluetooth.gatt_client.register_callback_observer(name, observer)
                observers.append((name, observer))
                self.bluetooth.gatt_client.read_descriptor(address, request.handle, self.AUTHENTICATION_NONE)
                desc_status = await descriptors
                if desc_status != floss_enums.GattStatus.SUCCESS:
                    valid_handle = False
                else:
                    self.bluetooth.write_descriptor(address, request.handle, self.AUTHENTICATION_NONE, request.value)
            else:
                self.bluetooth.write_characteristic(address, request.handle, self.WRITE_TYPE_DEFAULT,
                                                    self.AUTHENTICATION_NONE, request.value)
            if valid_handle:
                status, handle = await write_attribute

        finally:
            for name, observer in observers:
                self.bluetooth.gatt_client.unregister_callback_observer(name, observer)
        if valid_handle:
            return gatt_pb2.WriteResponse(handle=handle, status=status)
        return gatt_pb2.WriteResponse(handle=request.handle, status=gatt_pb2.INVALID_HANDLE)

    async def DiscoverServiceByUuid(self, request: gatt_pb2.DiscoverServiceByUuidRequest,
                                    context: grpc.ServicerContext) -> gatt_pb2.DiscoverServicesResponse:

        address = utils.connection_from(request.connection).address
        self.bluetooth.btif_gattc_discover_service_by_uuid(address, request.uuid)

        return gatt_pb2.DiscoverServicesResponse()

    async def DiscoverServices(self, request: gatt_pb2.DiscoverServicesRequest,
                               context: grpc.ServicerContext) -> gatt_pb2.DiscoverServicesResponse:

        class DiscoveryObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the discovery service state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_search_complete(self, addr, services, status):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to complete search. Status: %s', status)
                future = self.task['search_services']
                future.get_loop().call_soon_threadsafe(future.set_result, (services, status))

        address = utils.connection_from(request.connection).address
        try:
            search_services = asyncio.get_running_loop().create_future()
            observer = DiscoveryObserver({'search_services': search_services, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            self.bluetooth.discover_services(address)

            services, status = await search_services
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Failed to find services.')
            response = gatt_pb2.DiscoverServicesResponse()
            for serv in services:
                response.services.append(self.create_gatt_service(serv))
        finally:
            self.bluetooth.gatt_client.unregister_callback_observer(name, observer)
        return response

    async def DiscoverServicesSdp(self, request: gatt_pb2.DiscoverServicesSdpRequest,
                                  context: grpc.ServicerContext) -> gatt_pb2.DiscoverServicesSdpResponse:

        class DiscoverySDPObserver(adapter_client.BluetoothCallbacks):
            """Observer to observe the SDP discovery service state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_device_properties_changed(self, remote_device, props):
                if remote_device['address'] != self.task['address']:
                    return
                if floss_enums.BtPropertyType.Uuids in props:
                    future = self.task['device_uuids_changed']
                    future.get_loop().call_soon_threadsafe(future.set_result, ())

        address = utils.address_from(request.address)
        try:
            uuids = self.bluetooth.get_remote_uuids(address)
            if self.bluetooth.get_bond_state(address) == floss_enums.BondState.BONDING and (uuids is None or
                                                                                            len(uuids)) == 0:
                logging.error('Failed to get UUIDs.')
                return gatt_pb2.DiscoverServicesSdpResponse()
            if self.bluetooth.get_bond_state(address) != floss_enums.BondState.BONDING:
                device_uuids_changed = asyncio.get_running_loop().create_future()
                observer = DiscoverySDPObserver({'device_uuids_changed': device_uuids_changed, 'address': address})
                name = utils.create_observer_name(observer)
                self.bluetooth.adapter_client.register_callback_observer(name, observer)

                status = self.bluetooth.fetch_remote(address)
                if not status:
                    await context.abort(grpc.StatusCode.INTERNAL, f'Failed to fetch remote device {address} uuids.')
                await device_uuids_changed
                uuids = self.bluetooth.get_remote_uuids(address)
            response = gatt_pb2.DiscoverServicesSdpResponse()
            if uuids:
                for uuid in uuids:
                    response.service_uuids.append(str(UUID(bytes=bytes(uuid))).upper())
        finally:
            self.bluetooth.adapter_client.unregister_callback_observer(name, observer)

        return response

    async def ClearCache(self, request: gatt_pb2.ClearCacheRequest,
                         context: grpc.ServicerContext) -> gatt_pb2.ClearCacheResponse:

        class ClearCacheObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the clear cache state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_connection_updated(self, addr, interval, latency, timeout, status):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to update connection. Status: %s', status)
                future = self.task['refresh']
                future.get_loop().call_soon_threadsafe(future.set_result, status)

        address = utils.connection_from(request.connection).address
        try:
            refresh = asyncio.get_running_loop().create_future()
            observer = ClearCacheObserver({'refresh': refresh, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            self.bluetooth.refresh_device(address)
            status = await refresh
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Failed to clear cache.')
        finally:
            self.bluetooth.gatt_client.unregister_callback_observer(name, observer)
        return gatt_pb2.ClearCacheResponse()

    async def ReadCharacteristicFromHandle(self, request: gatt_pb2.ReadCharacteristicRequest,
                                           context: grpc.ServicerContext) -> gatt_pb2.ReadCharacteristicResponse:

        class ReadCharacteristicFromHandleObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the read characteristic from handle state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_characteristic_read(self, addr, status, handle, value):
                if addr != self.task['address'] or handle != self.task['handle']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to read characteristic from handle. Status: %s', status)
                future = self.task['characteristic_from_handle']
                future.get_loop().call_soon_threadsafe(future.set_result, (value, status))

        address = utils.connection_from(request.connection).address
        try:
            characteristic_from_handle = asyncio.get_running_loop().create_future()
            observer = ReadCharacteristicFromHandleObserver({
                'characteristic_from_handle': characteristic_from_handle,
                'address': address,
                'handle': request.handle
            })
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            self.bluetooth.read_characteristic(address, request.handle, self.AUTHENTICATION_NONE)
            value, status = await characteristic_from_handle
        finally:
            self.bluetooth.gatt_client.unregister_callback_observer(name, observer)

        return gatt_pb2.ReadCharacteristicResponse(value=gatt_pb2.AttValue(handle=request.handle, value=bytes(value)),
                                                   status=status)

    async def ReadCharacteristicsFromUuid(
            self, request: gatt_pb2.ReadCharacteristicsFromUuidRequest,
            context: grpc.ServicerContext) -> gatt_pb2.ReadCharacteristicsFromUuidResponse:

        class DiscoveryObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the discovery service state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_search_complete(self, addr, services, status):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to complete search. Status: %s', status)
                future = self.task['search_services']
                future.get_loop().call_soon_threadsafe(future.set_result, (services, status))

        class ReadCharacteristicsFromUuidObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the read characteristics from uuid state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_characteristic_read(self, addr, status, handle, value):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to read characteristic from handle. Status: %s', status)
                future = self.task['characteristic_from_uuid']
                future.get_loop().call_soon_threadsafe(future.set_result, (status, handle, value))

        address = utils.connection_from(request.connection).address
        observers = []
        characteristics = []
        try:
            search_services = asyncio.get_running_loop().create_future()
            observer = DiscoveryObserver({'search_services': search_services, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            observers.append((name, observer))

            self.bluetooth.discover_services(address)
            services, status = await search_services
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Found no services.')
            characteristic_from_uuid = asyncio.get_running_loop().create_future()
            observer = ReadCharacteristicsFromUuidObserver({
                'characteristic_from_uuid': characteristic_from_uuid,
                'address': address,
                'start_handle': request.start_handle,
                'end_handle': request.end_handle
            })
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            observers.append((name, observer))

            for serv in services:
                for characteristic in serv['characteristics']:
                    if (str(UUID(bytes=bytes(characteristic['uuid']))).upper() == request.uuid and
                            request.start_handle <= characteristic['instance_id'] <= request.end_handle):
                        self.bluetooth.read_using_characteristic_uuid(address, request.uuid,
                                                                      characteristic['instance_id'],
                                                                      characteristic['instance_id'],
                                                                      self.AUTHENTICATION_NONE)
                        status, handle, value = await characteristic_from_uuid
                        characteristics.append((status, handle, value))
            if not characteristics:
                self.bluetooth.read_using_characteristic_uuid(address, request.uuid, request.start_handle,
                                                              request.end_handle, self.AUTHENTICATION_NONE)
                status, handle, value = await characteristic_from_uuid
                result = gatt_pb2.ReadCharacteristicsFromUuidResponse(characteristics_read=[
                    gatt_pb2.ReadCharacteristicResponse(value=gatt_pb2.AttValue(value=bytes(value), handle=handle),
                                                        status=status)
                ])
            else:
                result = gatt_pb2.ReadCharacteristicsFromUuidResponse(characteristics_read=[
                    gatt_pb2.ReadCharacteristicResponse(
                        value=gatt_pb2.AttValue(value=bytes(value), handle=handle),
                        status=status,
                    ) for status, handle, value in characteristics
                ])
        finally:
            for name, observer in observers:
                self.bluetooth.gatt_client.unregister_callback_observer(name, observer)
        return result

    async def ReadCharacteristicDescriptorFromHandle(
            self, request: gatt_pb2.ReadCharacteristicDescriptorRequest,
            context: grpc.ServicerContext) -> gatt_pb2.ReadCharacteristicDescriptorResponse:

        class ReadCharacteristicDescriptorFromHandleObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the read descriptor state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_descriptor_read(self, addr, status, handle, value):
                if addr != self.task['address'] or handle != self.task['handle']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to read descriptor. Status: %s', status)
                future = self.task['descriptor']
                future.get_loop().call_soon_threadsafe(future.set_result, (value, status))

        address = utils.connection_from(request.connection).address
        try:
            descriptor = asyncio.get_running_loop().create_future()
            observer = ReadCharacteristicDescriptorFromHandleObserver({
                'descriptor': descriptor,
                'address': address,
                'handle': request.handle
            })
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            self.bluetooth.read_descriptor(address, request.handle, self.AUTHENTICATION_NONE)
            value, status = await descriptor
        finally:
            self.bluetooth.gatt_client.unregister_callback_observer(name, observer)

        return gatt_pb2.ReadCharacteristicDescriptorResponse(value=gatt_pb2.AttValue(handle=request.handle,
                                                                                     value=bytes(value)),
                                                             status=status)

    async def RegisterService(self, request: gatt_pb2.RegisterServiceRequest,
                              context: grpc.ServicerContext) -> gatt_pb2.RegisterServiceResponse:

        class RegisterServiceObserver(gatt_server.GattServerCallbacks):
            """Observer to observe the service registration."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_service_added(self, status, service):
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to add service. Status: %s', status)
                future = self.task['register_service']
                future.get_loop().call_soon_threadsafe(future.set_result, (status, service))

        def convert_req_to_dictionary(request):
            service_dict = {
                'service_type': self.SERVICE_TYPE_PRIMARY,
                'uuid': request.uuid,
                'instance_id': self.DEFAULT_INSTANCE_ID,
                'included_services': [],
                'characteristics': [],
            }

            # Iterate through the characteristics in the request.
            for char in request.characteristics:
                char_dict = {
                    'uuid': char.uuid,
                    'instance_id': self.DEFAULT_INSTANCE_ID,
                    'properties': char.properties,
                    'permissions': char.permissions,
                    'key_size': self.KEY_SIZE,
                    'write_type': self.WRITE_TYPE_DEFAULT,
                    'descriptors': [],
                }

                # Iterate through the descriptors in the characteristic.
                for desc in char.descriptors:
                    desc_dict = {
                        'uuid': desc.uuid,
                        'instance_id': self.DEFAULT_INSTANCE_ID,
                        'permissions': desc.permissions,
                    }
                    char_dict['descriptors'].append(desc_dict)

                service_dict['characteristics'].append(char_dict)
            return service_dict

        try:
            register_service = asyncio.get_running_loop().create_future()
            observer = RegisterServiceObserver({'register_service': register_service})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_server.register_callback_observer(name, observer)
            serv_dic = convert_req_to_dictionary(request.service)
            self.bluetooth.add_service(serv_dic)
            status, service = await register_service
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Failed to register service.')
        finally:
            self.bluetooth.gatt_server.unregister_callback_observer(name, observer)

        return gatt_pb2.RegisterServiceResponse(service=self.create_gatt_service(service))

    async def SetCharacteristicNotificationFromHandle(
            self, request: gatt_pb2.SetCharacteristicNotificationFromHandleRequest,
            context: grpc.ServicerContext) -> gatt_pb2.SetCharacteristicNotificationFromHandleResponse:

        class DiscoveryObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the discovery service state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_search_complete(self, addr, services, status):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to complete search. Status: %s', status)
                future = self.task['search_services']
                future.get_loop().call_soon_threadsafe(future.set_result, (services, status))

        class DescriptorObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the read/write descriptor state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_descriptor_read(self, addr, status, handle, value):
                if addr != self.task['address'] or handle != self.task['handle']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to read descriptors. Status: %s', status)
                future = self.task['read_descriptor']
                future.get_loop().call_soon_threadsafe(future.set_result, (value, status))

            @utils.glib_callback()
            def on_descriptor_write(self, addr, status, handle):
                if addr != self.task['address'] or handle != self.task['handle']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to write descriptor. Status: %s', status)
                future = self.task['write_descriptor']
                future.get_loop().call_soon_threadsafe(future.set_result, status)

        address = utils.connection_from(request.connection).address
        observers = []
        try:
            descriptor_futures = {
                'read_descriptor': asyncio.get_running_loop().create_future(),
                'write_descriptor': asyncio.get_running_loop().create_future(),
                'address': address,
                'handle': request.handle
            }
            observer = DescriptorObserver(descriptor_futures)
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            observers.append((name, observer))
            self.bluetooth.read_descriptor(address, request.handle, self.AUTHENTICATION_NONE)
            value, status = await descriptor_futures['read_descriptor']
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Found no descriptor with supported handle.')

            search_services = asyncio.get_running_loop().create_future()
            observer = DiscoveryObserver({'search_services': search_services, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            observers.append((name, observer))
            self.bluetooth.discover_services(address)
            services, status = await search_services
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Found no device services.')

            characteristic_handle = None
            for serv in services:
                for characteristic in serv['characteristics']:
                    for desc in characteristic['descriptors']:
                        if desc['instance_id'] == request.handle:
                            characteristic_handle = characteristic['instance_id']
                            break

            self.bluetooth.register_for_notification(address, characteristic_handle, True)

            if request.enable_value == gatt_pb2.ENABLE_INDICATION_VALUE:
                # Write descriptor value that used to enable indication for a client configuration descriptor.
                self.bluetooth.write_descriptor(address, request.handle, self.AUTHENTICATION_NONE,
                                                self.ENABLE_INDICATION_VALUE)
            else:
                # Write descriptor value that used to enable notification for a client configuration descriptor.
                self.bluetooth.write_descriptor(address, request.handle, self.AUTHENTICATION_NONE,
                                                self.ENABLE_NOTIFICATION_VALUE)
            status = await descriptor_futures['write_descriptor']
        finally:
            for name, observer in observers:
                self.bluetooth.gatt_client.unregister_callback_observer(name, observer)

        return gatt_pb2.SetCharacteristicNotificationFromHandleResponse(handle=request.handle, status=status)

    async def WaitCharacteristicNotification(
            self, request: gatt_pb2.WaitCharacteristicNotificationRequest,
            context: grpc.ServicerContext) -> gatt_pb2.WaitCharacteristicNotificationResponse:

        class DiscoveryObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the discovery service state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_search_complete(self, addr, services, status):
                if addr != self.task['address']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to complete search. Status: %s', status)
                future = self.task['search_services']
                future.get_loop().call_soon_threadsafe(future.set_result, (services, status))

        class ReadDescriptorObserver(gatt_client.GattClientCallbacks):
            """Observer to observe the read descriptor state."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_descriptor_read(self, addr, status, handle, value):
                if addr != self.task['address'] or handle != self.task['handle']:
                    return
                if floss_enums.GattStatus(status) != floss_enums.GattStatus.SUCCESS:
                    logging.error('Failed to read descriptors. Status: %s', status)
                future = self.task['read_descriptor']
                future.get_loop().call_soon_threadsafe(future.set_result, (value, status))

        address = utils.connection_from(request.connection).address
        observers = []
        try:
            read_descriptor = asyncio.get_running_loop().create_future()
            observer = ReadDescriptorObserver({
                'read_descriptor': read_descriptor,
                'address': address,
                'handle': request.handle
            })
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            observers.append((name, observer))
            self.bluetooth.read_descriptor(address, request.handle, self.AUTHENTICATION_NONE)
            value, status = await read_descriptor
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Found no descriptor with supported handle.')

            search_services = asyncio.get_running_loop().create_future()
            observer = DiscoveryObserver({'search_services': search_services, 'address': address})
            name = utils.create_observer_name(observer)
            self.bluetooth.gatt_client.register_callback_observer(name, observer)
            observers.append((name, observer))
            self.bluetooth.discover_services(address)
            services, status = await search_services
            if status != floss_enums.GattStatus.SUCCESS:
                await context.abort(grpc.StatusCode.INTERNAL, 'Found no device services.')

            characteristic_handle = None
            for serv in services:
                for characteristic in serv['characteristics']:
                    for desc in characteristic['descriptors']:
                        if desc['instance_id'] == request.handle:
                            characteristic_handle = characteristic['instance_id']
                            break
            # Wait for the characteristic notification.
            if address not in self.characteristic_changed_map:
                self.characteristic_changed_map[address] = {}

            if characteristic_handle not in self.characteristic_changed_map[address]:
                char_future = asyncio.get_running_loop().create_future()
                self.characteristic_changed_map[address][characteristic_handle] = char_future
            else:
                char_future = self.characteristic_changed_map[address][characteristic_handle]

            await char_future
        finally:
            for name, observer in observers:
                self.bluetooth.gatt_client.unregister_callback_observer(name, observer)

        return gatt_pb2.WaitCharacteristicNotificationResponse(
            characteristic_notification_received=self.characteristic_changed_map[address]
            [characteristic_handle].result())

    def create_gatt_characteristic_descriptor(self, descriptor):
        return gatt_pb2.GattCharacteristicDescriptor(handle=descriptor['instance_id'],
                                                     permissions=descriptor['permissions'],
                                                     uuid=str(UUID(bytes=bytes(descriptor['uuid']))).upper())

    def create_gatt_characteristic(self, characteristic):
        return gatt_pb2.GattCharacteristic(
            properties=characteristic['properties'],
            permissions=characteristic['permissions'],
            uuid=str(UUID(bytes=bytes(characteristic['uuid']))).upper(),
            handle=characteristic['instance_id'],
            descriptors=[
                self.create_gatt_characteristic_descriptor(descriptor) for descriptor in characteristic['descriptors']
            ])

    def create_gatt_service(self, service):
        return gatt_pb2.GattService(
            handle=service['instance_id'],
            type=service['service_type'],
            uuid=str(UUID(bytes=bytes(service['uuid']))).upper(),
            included_services=[
                self.create_gatt_service(included_service) for included_service in service['included_services']
            ],
            characteristics=[
                self.create_gatt_characteristic(characteristic) for characteristic in service['characteristics']
            ])
