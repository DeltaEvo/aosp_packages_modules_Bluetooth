extern crate bt_shim;

use bt_topshim::btif::{BtSspVariant, BtTransport, Uuid128Bit};

use btstack::bluetooth::{
    BluetoothDevice, IBluetooth, IBluetoothCallback, IBluetoothConnectionCallback,
};
use btstack::RPCProxy;

use dbus::arg::RefArg;

use dbus::nonblock::SyncConnection;
use dbus::strings::Path;

use dbus_macros::{dbus_method, dbus_propmap, dbus_proxy_obj, generate_dbus_exporter};

use dbus_projection::impl_dbus_arg_enum;
use dbus_projection::DisconnectWatcher;

use num_traits::cast::{FromPrimitive, ToPrimitive};

use std::sync::Arc;

use crate::dbus_arg::{DBusArg, DBusArgError, RefArgToRust};

#[dbus_propmap(BluetoothDevice)]
pub struct BluetoothDeviceDBus {
    address: String,
    name: String,
}

#[allow(dead_code)]
struct BluetoothCallbackDBus {}

#[dbus_proxy_obj(BluetoothCallback, "org.chromium.bluetooth.BluetoothCallback")]
impl IBluetoothCallback for BluetoothCallbackDBus {
    #[dbus_method("OnAddressChanged")]
    fn on_address_changed(&self, addr: String) {}
    #[dbus_method("OnDeviceFound")]
    fn on_device_found(&self, remote_device: BluetoothDevice) {}
    #[dbus_method("OnDiscoveringChanged")]
    fn on_discovering_changed(&self, discovering: bool) {}
    #[dbus_method("OnSspRequest")]
    fn on_ssp_request(
        &self,
        remote_device: BluetoothDevice,
        cod: u32,
        variant: BtSspVariant,
        passkey: u32,
    ) {
    }
    #[dbus_method("OnBondStateChanged")]
    fn on_bond_state_changed(&self, status: u32, address: String, state: u32) {}
}

impl_dbus_arg_enum!(BtTransport);
impl_dbus_arg_enum!(BtSspVariant);

#[allow(dead_code)]
struct BluetoothConnectionCallbackDBus {}

#[dbus_proxy_obj(BluetoothConnectionCallback, "org.chromium.bluetooth.BluetoothConnectionCallback")]
impl IBluetoothConnectionCallback for BluetoothConnectionCallbackDBus {
    #[dbus_method("OnDeviceConnected")]
    fn on_device_connected(&self, remote_device: BluetoothDevice) {}

    #[dbus_method("OnDeviceDisconnected")]
    fn on_device_disconnected(&self, remote_device: BluetoothDevice) {}
}

#[allow(dead_code)]
struct IBluetoothDBus {}

#[generate_dbus_exporter(export_bluetooth_dbus_obj, "org.chromium.bluetooth.Bluetooth")]
impl IBluetooth for IBluetoothDBus {
    #[dbus_method("RegisterCallback")]
    fn register_callback(&mut self, callback: Box<dyn IBluetoothCallback + Send>) {}

    #[dbus_method("RegisterConnectionCallback")]
    fn register_connection_callback(
        &mut self,
        callback: Box<dyn IBluetoothConnectionCallback + Send>,
    ) -> u32 {
        0
    }

    #[dbus_method("UnregisterConnectionCallback")]
    fn unregister_connection_callback(&mut self, id: u32) -> bool {
        false
    }

    // Not exposed over D-Bus. The stack is automatically enabled when the daemon starts.
    fn enable(&mut self) -> bool {
        false
    }

    // Not exposed over D-Bus. The stack is automatically disabled when the daemon exits.
    // TODO(b/189495858): Handle shutdown properly when SIGTERM is received.
    fn disable(&mut self) -> bool {
        false
    }

    #[dbus_method("GetAddress")]
    fn get_address(&self) -> String {
        String::from("")
    }

    #[dbus_method("GetUuids")]
    fn get_uuids(&self) -> Vec<Uuid128Bit> {
        vec![]
    }

    #[dbus_method("GetName")]
    fn get_name(&self) -> String {
        String::new()
    }

    #[dbus_method("SetName")]
    fn set_name(&self, name: String) -> bool {
        true
    }

    #[dbus_method("StartDiscovery")]
    fn start_discovery(&self) -> bool {
        true
    }

    #[dbus_method("CancelDiscovery")]
    fn cancel_discovery(&self) -> bool {
        true
    }

    #[dbus_method("IsDiscovering")]
    fn is_discovering(&self) -> bool {
        true
    }

    #[dbus_method("GetDiscoveryEndMillis")]
    fn get_discovery_end_millis(&self) -> u64 {
        0
    }

    #[dbus_method("CreateBond")]
    fn create_bond(&self, _device: BluetoothDevice, _transport: BtTransport) -> bool {
        true
    }

    #[dbus_method("CancelBondProcess")]
    fn cancel_bond_process(&self, _device: BluetoothDevice) -> bool {
        true
    }

    #[dbus_method("RemoveBond")]
    fn remove_bond(&self, _device: BluetoothDevice) -> bool {
        true
    }

    #[dbus_method("GetBondedDevices")]
    fn get_bonded_devices(&self) -> Vec<BluetoothDevice> {
        vec![]
    }

    #[dbus_method("GetBondState")]
    fn get_bond_state(&self, _device: BluetoothDevice) -> u32 {
        0
    }

    #[dbus_method("SetPin")]
    fn set_pin(&self, _device: BluetoothDevice, _accept: bool, _pin_code: Vec<u8>) -> bool {
        false
    }

    #[dbus_method("SetPasskey")]
    fn set_passkey(&self, _device: BluetoothDevice, _accept: bool, _passkey: Vec<u8>) -> bool {
        false
    }

    #[dbus_method("SetPairingConfirmation")]
    fn set_pairing_confirmation(&self, _device: BluetoothDevice, _accept: bool) -> bool {
        false
    }

    #[dbus_method("GetConnectionState")]
    fn get_connection_state(&self, _device: BluetoothDevice) -> u32 {
        0
    }

    #[dbus_method("GetRemoteUuids")]
    fn get_remote_uuids(&self, _device: BluetoothDevice) -> Vec<Uuid128Bit> {
        vec![]
    }

    #[dbus_method("FetchRemoteUuids")]
    fn fetch_remote_uuids(&self, _device: BluetoothDevice) -> bool {
        true
    }

    #[dbus_method("SdpSearch")]
    fn sdp_search(&self, _device: BluetoothDevice, _uuid: Uuid128Bit) -> bool {
        true
    }

    #[dbus_method("ConnectAllEnabledProfiles")]
    fn connect_all_enabled_profiles(&self, _device: BluetoothDevice) -> bool {
        true
    }

    #[dbus_method("DisconnectAllEnabledProfiles")]
    fn disconnect_all_enabled_profiles(&self, _device: BluetoothDevice) -> bool {
        true
    }
}
