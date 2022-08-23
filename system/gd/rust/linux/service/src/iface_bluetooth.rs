extern crate bt_shim;

use bt_topshim::btif::{
    BtDeviceType, BtPropertyType, BtSspVariant, BtStatus, BtTransport, Uuid, Uuid128Bit,
};
use bt_topshim::profiles::socket::SocketType;

use btstack::bluetooth::{
    Bluetooth, BluetoothDevice, IBluetooth, IBluetoothCallback, IBluetoothConnectionCallback,
};
use btstack::socket_manager::{
    BluetoothServerSocket, BluetoothSocket, BluetoothSocketManager, CallbackId,
    IBluetoothSocketManager, IBluetoothSocketManagerCallbacks, SocketId, SocketResult,
};
use btstack::suspend::{ISuspend, ISuspendCallback, Suspend, SuspendType};
use btstack::uuid::Profile;
use btstack::RPCProxy;

use dbus::arg::RefArg;
use dbus::nonblock::SyncConnection;
use dbus::strings::Path;
use dbus_macros::{dbus_method, dbus_propmap, dbus_proxy_obj, generate_dbus_exporter};

use dbus_projection::DisconnectWatcher;
use dbus_projection::{dbus_generated, impl_dbus_arg_enum, impl_dbus_arg_from_into};

use num_traits::cast::{FromPrimitive, ToPrimitive};

use std::convert::{TryFrom, TryInto};
use std::sync::{Arc, Mutex};

use crate::dbus_arg::{DBusArg, DBusArgError, RefArgToRust};

// Represents Uuid as an array in D-Bus.
impl_dbus_arg_from_into!(Uuid, Vec<u8>);

impl RefArgToRust for Uuid {
    type RustType = Vec<u8>;

    fn ref_arg_to_rust(
        arg: &(dyn dbus::arg::RefArg + 'static),
        name: String,
    ) -> Result<Self::RustType, Box<dyn std::error::Error>> {
        <Vec<u8> as RefArgToRust>::ref_arg_to_rust(arg, name)
    }
}

impl_dbus_arg_from_into!(BtStatus, u32);

/// A mixin of the several interfaces. The naming of the fields in the mixin must match
/// what is listed in the `generate_dbus_exporter` invocation.
pub struct BluetoothMixin {
    pub adapter: Arc<Mutex<Box<Bluetooth>>>,
    pub suspend: Arc<Mutex<Box<Suspend>>>,
    pub socket_mgr: Arc<Mutex<Box<BluetoothSocketManager>>>,
}

#[dbus_propmap(BluetoothDevice)]
pub struct BluetoothDeviceDBus {
    address: String,
    name: String,
}

#[allow(dead_code)]
struct BluetoothCallbackDBus {}

#[dbus_proxy_obj(BluetoothCallback, "org.chromium.bluetooth.BluetoothCallback")]
impl IBluetoothCallback for BluetoothCallbackDBus {
    #[dbus_method("OnAdapterPropertyChanged")]
    fn on_adapter_property_changed(&self, prop: BtPropertyType) {
        dbus_generated!()
    }
    #[dbus_method("OnAddressChanged")]
    fn on_address_changed(&self, addr: String) {
        dbus_generated!()
    }
    #[dbus_method("OnNameChanged")]
    fn on_name_changed(&self, name: String) {
        dbus_generated!()
    }
    #[dbus_method("OnDiscoverableChanged")]
    fn on_discoverable_changed(&self, discoverable: bool) {
        dbus_generated!()
    }
    #[dbus_method("OnDeviceFound")]
    fn on_device_found(&self, remote_device: BluetoothDevice) {
        dbus_generated!()
    }
    #[dbus_method("OnDeviceCleared")]
    fn on_device_cleared(&self, remote_device: BluetoothDevice) {
        dbus_generated!()
    }
    #[dbus_method("OnDiscoveringChanged")]
    fn on_discovering_changed(&self, discovering: bool) {
        dbus_generated!()
    }
    #[dbus_method("OnSspRequest")]
    fn on_ssp_request(
        &self,
        remote_device: BluetoothDevice,
        cod: u32,
        variant: BtSspVariant,
        passkey: u32,
    ) {
        dbus_generated!()
    }
    #[dbus_method("OnBondStateChanged")]
    fn on_bond_state_changed(&self, status: u32, address: String, state: u32) {
        dbus_generated!()
    }
}

impl_dbus_arg_enum!(BtDeviceType);
impl_dbus_arg_enum!(BtPropertyType);
impl_dbus_arg_enum!(BtSspVariant);
impl_dbus_arg_enum!(BtTransport);
impl_dbus_arg_enum!(Profile);

#[allow(dead_code)]
struct BluetoothConnectionCallbackDBus {}

#[dbus_proxy_obj(BluetoothConnectionCallback, "org.chromium.bluetooth.BluetoothConnectionCallback")]
impl IBluetoothConnectionCallback for BluetoothConnectionCallbackDBus {
    #[dbus_method("OnDeviceConnected")]
    fn on_device_connected(&self, remote_device: BluetoothDevice) {
        dbus_generated!()
    }

    #[dbus_method("OnDeviceDisconnected")]
    fn on_device_disconnected(&self, remote_device: BluetoothDevice) {
        dbus_generated!()
    }
}

#[allow(dead_code)]
struct IBluetoothDBus {}

#[generate_dbus_exporter(
    export_bluetooth_dbus_intf,
    "org.chromium.bluetooth.Bluetooth",
    BluetoothMixin,
    adapter
)]
impl IBluetooth for IBluetoothDBus {
    #[dbus_method("RegisterCallback")]
    fn register_callback(&mut self, callback: Box<dyn IBluetoothCallback + Send>) {
        dbus_generated!()
    }

    #[dbus_method("RegisterConnectionCallback")]
    fn register_connection_callback(
        &mut self,
        callback: Box<dyn IBluetoothConnectionCallback + Send>,
    ) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("UnregisterConnectionCallback")]
    fn unregister_connection_callback(&mut self, id: u32) -> bool {
        dbus_generated!()
    }

    // Not exposed over D-Bus. The stack is automatically enabled when the daemon starts.
    fn enable(&mut self) -> bool {
        dbus_generated!()
    }

    // Not exposed over D-Bus. The stack is automatically disabled when the daemon exits.
    // TODO(b/189495858): Handle shutdown properly when SIGTERM is received.
    fn disable(&mut self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetAddress")]
    fn get_address(&self) -> String {
        dbus_generated!()
    }

    #[dbus_method("GetUuids")]
    fn get_uuids(&self) -> Vec<Uuid128Bit> {
        dbus_generated!()
    }

    #[dbus_method("GetName")]
    fn get_name(&self) -> String {
        dbus_generated!()
    }

    #[dbus_method("SetName")]
    fn set_name(&self, name: String) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetBluetoothClass")]
    fn get_bluetooth_class(&self) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("SetBluetoothClass")]
    fn set_bluetooth_class(&self, cod: u32) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetDiscoverable")]
    fn get_discoverable(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetDiscoverableTimeout")]
    fn get_discoverable_timeout(&self) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("SetDiscoverable")]
    fn set_discoverable(&self, mode: bool, duration: u32) -> bool {
        dbus_generated!()
    }

    #[dbus_method("IsMultiAdvertisementSupported")]
    fn is_multi_advertisement_supported(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("IsLeExtendedAdvertisingSupported")]
    fn is_le_extended_advertising_supported(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("StartDiscovery")]
    fn start_discovery(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("CancelDiscovery")]
    fn cancel_discovery(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("IsDiscovering")]
    fn is_discovering(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetDiscoveryEndMillis")]
    fn get_discovery_end_millis(&self) -> u64 {
        dbus_generated!()
    }

    #[dbus_method("CreateBond")]
    fn create_bond(&self, device: BluetoothDevice, transport: BtTransport) -> bool {
        dbus_generated!()
    }

    #[dbus_method("CancelBondProcess")]
    fn cancel_bond_process(&self, device: BluetoothDevice) -> bool {
        dbus_generated!()
    }

    #[dbus_method("RemoveBond")]
    fn remove_bond(&self, device: BluetoothDevice) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetBondedDevices")]
    fn get_bonded_devices(&self) -> Vec<BluetoothDevice> {
        dbus_generated!()
    }

    #[dbus_method("GetBondState")]
    fn get_bond_state(&self, device: BluetoothDevice) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("SetPin")]
    fn set_pin(&self, device: BluetoothDevice, accept: bool, pin_code: Vec<u8>) -> bool {
        dbus_generated!()
    }

    #[dbus_method("SetPasskey")]
    fn set_passkey(&self, device: BluetoothDevice, accept: bool, passkey: Vec<u8>) -> bool {
        dbus_generated!()
    }

    #[dbus_method("SetPairingConfirmation")]
    fn set_pairing_confirmation(&self, device: BluetoothDevice, accept: bool) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteName")]
    fn get_remote_name(&self, _device: BluetoothDevice) -> String {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteType")]
    fn get_remote_type(&self, _device: BluetoothDevice) -> BtDeviceType {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteAlias")]
    fn get_remote_alias(&self, _device: BluetoothDevice) -> String {
        dbus_generated!()
    }

    #[dbus_method("SetRemoteAlias")]
    fn set_remote_alias(&mut self, _device: BluetoothDevice, new_alias: String) {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteClass")]
    fn get_remote_class(&self, _device: BluetoothDevice) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("GetConnectionState")]
    fn get_connection_state(&self, device: BluetoothDevice) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("GetProfileConnectionState")]
    fn get_profile_connection_state(&self, profile: Profile) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteUuids")]
    fn get_remote_uuids(&self, device: BluetoothDevice) -> Vec<Uuid128Bit> {
        dbus_generated!()
    }

    #[dbus_method("FetchRemoteUuids")]
    fn fetch_remote_uuids(&self, device: BluetoothDevice) -> bool {
        dbus_generated!()
    }

    #[dbus_method("SdpSearch")]
    fn sdp_search(&self, device: BluetoothDevice, uuid: Uuid128Bit) -> bool {
        dbus_generated!()
    }

    #[dbus_method("ConnectAllEnabledProfiles")]
    fn connect_all_enabled_profiles(&mut self, device: BluetoothDevice) -> bool {
        dbus_generated!()
    }

    #[dbus_method("DisconnectAllEnabledProfiles")]
    fn disconnect_all_enabled_profiles(&mut self, device: BluetoothDevice) -> bool {
        dbus_generated!()
    }
}

impl_dbus_arg_enum!(SocketType);

#[dbus_propmap(BluetoothServerSocket)]
pub struct BluetoothServerSocketDBus {
    id: SocketId,
    sock_type: SocketType,
    flags: i32,
    psm: Option<i32>,
    channel: Option<i32>,
    name: Option<String>,
    uuid: Option<Uuid>,
}

#[dbus_propmap(BluetoothSocket)]
pub struct BluetoothSocketDBus {
    id: SocketId,
    remote_device: BluetoothDevice,
    sock_type: SocketType,
    flags: i32,
    fd: Option<std::fs::File>,
    port: i32,
    uuid: Option<Uuid>,
    max_rx_size: i32,
    max_tx_size: i32,
}

#[dbus_propmap(SocketResult)]
pub struct SocketResultDBus {
    status: BtStatus,
    id: u64,
}

struct IBluetoothSocketManagerCallbacksDBus {}

#[dbus_proxy_obj(BluetoothSocketCallback, "org.chromium.bluetooth.SocketManagerCallback")]
impl IBluetoothSocketManagerCallbacks for IBluetoothSocketManagerCallbacksDBus {
    #[dbus_method("OnIncomingSocketReady")]
    fn on_incoming_socket_ready(&mut self, socket: BluetoothServerSocket, status: BtStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnIncomingSocketClosed")]
    fn on_incoming_socket_closed(&mut self, listener_id: SocketId, reason: BtStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnHandleIncomingConnection")]
    fn on_handle_incoming_connection(
        &mut self,
        listener_id: SocketId,
        connection: BluetoothSocket,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnOutgoingConnectionResult")]
    fn on_outgoing_connection_result(
        &mut self,
        connecting_id: SocketId,
        result: BtStatus,
        socket: Option<BluetoothSocket>,
    ) {
        dbus_generated!()
    }
}

struct IBluetoothSocketManagerDBus {}

#[generate_dbus_exporter(
    export_socket_mgr_intf,
    "org.chromium.bluetooth.SocketManager",
    BluetoothMixin,
    socket_mgr
)]
impl IBluetoothSocketManager for IBluetoothSocketManagerDBus {
    #[dbus_method("RegisterCallback")]
    fn register_callback(
        &mut self,
        callback: Box<dyn IBluetoothSocketManagerCallbacks + Send>,
    ) -> CallbackId {
        dbus_generated!()
    }

    #[dbus_method("ListenUsingInsecureL2capChannel")]
    fn listen_using_insecure_l2cap_channel(&mut self, callback: CallbackId) -> SocketResult {
        dbus_generated!()
    }

    #[dbus_method("ListenUsingL2capChannel")]
    fn listen_using_l2cap_channel(&mut self, callback: CallbackId) -> SocketResult {
        dbus_generated!()
    }

    #[dbus_method("ListenUsingInsecureRfcommWithServiceRecord")]
    fn listen_using_insecure_rfcomm_with_service_record(
        &mut self,
        callback: CallbackId,
        name: String,
        uuid: Uuid,
    ) -> SocketResult {
        dbus_generated!()
    }

    #[dbus_method("ListenUsingRfcommWithServiceRecord")]
    fn listen_using_rfcomm_with_service_record(
        &mut self,
        callback: CallbackId,
        name: String,
        uuid: Uuid,
    ) -> SocketResult {
        dbus_generated!()
    }

    #[dbus_method("CreateInsecureL2capChannel")]
    fn create_insecure_l2cap_channel(
        &mut self,
        callback: CallbackId,
        device: BluetoothDevice,
        psm: i32,
    ) -> SocketResult {
        dbus_generated!()
    }

    #[dbus_method("CreateL2capChannel")]
    fn create_l2cap_channel(
        &mut self,
        callback: CallbackId,
        device: BluetoothDevice,
        psm: i32,
    ) -> SocketResult {
        dbus_generated!()
    }

    #[dbus_method("CreateInsecureRfcommSocketToServiceRecord")]
    fn create_insecure_rfcomm_socket_to_service_record(
        &mut self,
        callback: CallbackId,
        device: BluetoothDevice,
        uuid: Uuid,
    ) -> SocketResult {
        dbus_generated!()
    }

    #[dbus_method("CreateRfcommSocketToServiceRecord")]
    fn create_rfcomm_socket_to_service_record(
        &mut self,
        callback: CallbackId,
        device: BluetoothDevice,
        uuid: Uuid,
    ) -> SocketResult {
        dbus_generated!()
    }

    #[dbus_method("Accept")]
    fn accept(&mut self, callback: CallbackId, id: SocketId, timeout_ms: Option<u32>) -> BtStatus {
        dbus_generated!()
    }

    #[dbus_method("Close")]
    fn close(&mut self, callback: CallbackId, id: SocketId) -> BtStatus {
        dbus_generated!()
    }
}

impl_dbus_arg_enum!(SuspendType);

#[allow(dead_code)]
struct ISuspendDBus {}

#[generate_dbus_exporter(
    export_suspend_dbus_intf,
    "org.chromium.bluetooth.Suspend",
    BluetoothMixin,
    suspend
)]
impl ISuspend for ISuspendDBus {
    #[dbus_method("RegisterCallback")]
    fn register_callback(&mut self, callback: Box<dyn ISuspendCallback + Send>) -> bool {
        dbus_generated!()
    }

    #[dbus_method("UnregisterCallback")]
    fn unregister_callback(&mut self, callback_id: u32) -> bool {
        dbus_generated!()
    }

    #[dbus_method("Suspend")]
    fn suspend(&self, suspend_type: SuspendType) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("Resume")]
    fn resume(&self) -> bool {
        dbus_generated!()
    }
}

#[allow(dead_code)]
struct SuspendCallbackDBus {}

#[dbus_proxy_obj(SuspendCallback, "org.chromium.bluetooth.SuspendCallback")]
impl ISuspendCallback for SuspendCallbackDBus {
    #[dbus_method("OnCallbackRegistered")]
    fn on_callback_registered(&self, callback_id: u32) {
        dbus_generated!()
    }
    #[dbus_method("OnSuspendReady")]
    fn on_suspend_ready(&self, suspend_id: u32) {
        dbus_generated!()
    }
    #[dbus_method("OnResumed")]
    fn on_resumed(&self, suspend_id: u32) {
        dbus_generated!()
    }
}
