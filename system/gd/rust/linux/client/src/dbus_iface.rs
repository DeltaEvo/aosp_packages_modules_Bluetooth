//! D-Bus proxy implementations of the APIs.

use bt_topshim::btif::{
    BtBondState, BtConnectionState, BtDeviceType, BtPropertyType, BtSspVariant, BtStatus,
    BtTransport, Uuid, Uuid128Bit,
};
use bt_topshim::profiles::gatt::{GattStatus, LePhy};
use bt_topshim::profiles::socket::SocketType;

use btstack::bluetooth::{
    BluetoothDevice, IBluetooth, IBluetoothCallback, IBluetoothConnectionCallback, IBluetoothQA,
};
use btstack::bluetooth_adv::{
    AdvertiseData, AdvertisingSetParameters, IAdvertisingSetCallback, PeriodicAdvertisingParameters,
};
use btstack::bluetooth_gatt::{
    BluetoothGattCharacteristic, BluetoothGattDescriptor, BluetoothGattService,
    GattWriteRequestStatus, GattWriteType, IBluetoothGatt, IBluetoothGattCallback,
    IScannerCallback, ScanFilter, ScanFilterCondition, ScanFilterPattern, ScanResult, ScanSettings,
    ScanType,
};
use btstack::socket_manager::{
    BluetoothServerSocket, BluetoothSocket, CallbackId, IBluetoothSocketManager,
    IBluetoothSocketManagerCallbacks, SocketId, SocketResult,
};
use btstack::{RPCProxy, SuspendMode};

use btstack::suspend::{ISuspend, ISuspendCallback, SuspendType};

use btstack::uuid::Profile;
use dbus::arg::RefArg;
use dbus::nonblock::SyncConnection;

use dbus_projection::{
    dbus_generated, impl_dbus_arg_enum, impl_dbus_arg_from_into, ClientDBusProxy, DisconnectWatcher,
};

use dbus_macros::{
    dbus_method, dbus_propmap, generate_dbus_exporter, generate_dbus_interface_client,
};

use manager_service::iface_bluetooth_manager::{
    AdapterWithEnabled, IBluetoothManager, IBluetoothManagerCallback,
};

use num_traits::{FromPrimitive, ToPrimitive};

use std::collections::HashMap;
use std::convert::{TryFrom, TryInto};
use std::sync::Arc;

use crate::dbus_arg::{DBusArg, DBusArgError, RefArgToRust};

fn make_object_path(idx: i32, name: &str) -> dbus::Path {
    dbus::Path::new(format!("/org/chromium/bluetooth/hci{}/{}", idx, name)).unwrap()
}

impl_dbus_arg_enum!(BtBondState);
impl_dbus_arg_enum!(BtConnectionState);
impl_dbus_arg_enum!(BtDeviceType);
impl_dbus_arg_enum!(BtPropertyType);
impl_dbus_arg_enum!(BtSspVariant);
impl_dbus_arg_enum!(BtStatus);
impl_dbus_arg_enum!(BtTransport);
impl_dbus_arg_enum!(GattStatus);
impl_dbus_arg_enum!(GattWriteRequestStatus);
impl_dbus_arg_enum!(GattWriteType);
impl_dbus_arg_enum!(LePhy);
impl_dbus_arg_enum!(Profile);
impl_dbus_arg_enum!(ScanType);
impl_dbus_arg_enum!(SocketType);
impl_dbus_arg_enum!(SuspendMode);
impl_dbus_arg_enum!(SuspendType);
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

// Represents Uuid128Bit as an array in D-Bus.
impl DBusArg for Uuid128Bit {
    type DBusType = Vec<u8>;

    fn from_dbus(
        data: Vec<u8>,
        _conn: Option<Arc<SyncConnection>>,
        _remote: Option<dbus::strings::BusName<'static>>,
        _disconnect_watcher: Option<Arc<std::sync::Mutex<DisconnectWatcher>>>,
    ) -> Result<[u8; 16], Box<dyn std::error::Error>> {
        return Ok(data.try_into().unwrap());
    }

    fn to_dbus(data: [u8; 16]) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        return Ok(data.to_vec());
    }
}

#[dbus_propmap(BluetoothGattDescriptor)]
pub struct BluetoothGattDescriptorDBus {
    uuid: Uuid128Bit,
    instance_id: i32,
    permissions: i32,
}

#[dbus_propmap(BluetoothGattCharacteristic)]
pub struct BluetoothGattCharacteristicDBus {
    uuid: Uuid128Bit,
    instance_id: i32,
    properties: i32,
    permissions: i32,
    key_size: i32,
    write_type: GattWriteType,
    descriptors: Vec<BluetoothGattDescriptor>,
}

#[dbus_propmap(BluetoothGattService)]
pub struct BluetoothGattServiceDBus {
    pub uuid: Uuid128Bit,
    pub instance_id: i32,
    pub service_type: i32,
    pub characteristics: Vec<BluetoothGattCharacteristic>,
    pub included_services: Vec<BluetoothGattService>,
}

#[dbus_propmap(BluetoothDevice)]
pub struct BluetoothDeviceDBus {
    address: String,
    name: String,
}

#[dbus_propmap(ScanSettings)]
struct ScanSettingsDBus {
    interval: i32,
    window: i32,
    scan_type: ScanType,
}

#[dbus_propmap(ScanFilterPattern)]
struct ScanFilterPatternDBus {
    start_position: u8,
    ad_type: u8,
    content: Vec<u8>,
}

// Manually converts enum variant from/into D-Bus.
//
// The ScanFilterCondition enum variant is represented as a D-Bus dictionary with one and only one
// member which key determines which variant it refers to and the value determines the data.
//
// For example, ScanFilterCondition::Patterns(data: Vec<u8>) is represented as:
//     array [
//        dict entry(
//           string "patterns"
//           variant array [ ... ]
//        )
//     ]
//
// And ScanFilterCondition::All is represented as:
//     array [
//        dict entry(
//           string "all"
//           variant string "unit"
//        )
//     ]
//
// If enum variant is used many times, we should find a way to avoid boilerplate.
impl DBusArg for ScanFilterCondition {
    type DBusType = dbus::arg::PropMap;
    fn from_dbus(
        data: dbus::arg::PropMap,
        _conn: Option<std::sync::Arc<dbus::nonblock::SyncConnection>>,
        _remote: Option<dbus::strings::BusName<'static>>,
        _disconnect_watcher: Option<
            std::sync::Arc<std::sync::Mutex<dbus_projection::DisconnectWatcher>>,
        >,
    ) -> Result<ScanFilterCondition, Box<dyn std::error::Error>> {
        let variant = match data.get("patterns") {
            Some(variant) => variant,
            None => {
                return Err(Box::new(DBusArgError::new(String::from(format!(
                    "ScanFilterCondition does not contain any enum variant",
                )))));
            }
        };

        match variant.arg_type() {
            dbus::arg::ArgType::Variant => {}
            _ => {
                return Err(Box::new(DBusArgError::new(String::from(format!(
                    "ScanFilterCondition::Patterns must be a variant",
                )))));
            }
        };

        let patterns =
            <<Vec<ScanFilterPattern> as DBusArg>::DBusType as RefArgToRust>::ref_arg_to_rust(
                variant.as_static_inner(0).unwrap(),
                format!("ScanFilterCondition::Patterns"),
            )?;

        let patterns = Vec::<ScanFilterPattern>::from_dbus(patterns, None, None, None)?;
        return Ok(ScanFilterCondition::Patterns(patterns));
    }

    fn to_dbus(
        condition: ScanFilterCondition,
    ) -> Result<dbus::arg::PropMap, Box<dyn std::error::Error>> {
        let mut map: dbus::arg::PropMap = std::collections::HashMap::new();
        match condition {
            ScanFilterCondition::Patterns(patterns) => {
                map.insert(
                    String::from("patterns"),
                    dbus::arg::Variant(Box::new(DBusArg::to_dbus(patterns)?)),
                );
            }
            _ => {}
        }
        return Ok(map);
    }
}

#[dbus_propmap(ScanFilter)]
struct ScanFilterDBus {
    rssi_high_threshold: i16,
    rssi_low_threshold: i16,
    rssi_low_timeout: u16,
    rssi_sampling_period: u16,
    condition: ScanFilterCondition,
}

#[dbus_propmap(ScanResult)]
struct ScanResultDBus {
    name: String,
    address: String,
    addr_type: u8,
    event_type: u16,
    primary_phy: u8,
    secondary_phy: u8,
    advertising_sid: u8,
    tx_power: i8,
    rssi: i8,
    periodic_adv_int: u16,
    flags: u8,
    service_uuids: Vec<Uuid128Bit>,
    service_data: HashMap<String, Vec<u8>>,
    manufacturer_data: HashMap<u16, Vec<u8>>,
    adv_data: Vec<u8>,
}

struct IBluetoothCallbackDBus {}

impl RPCProxy for IBluetoothCallbackDBus {}

#[generate_dbus_exporter(
    export_bluetooth_callback_dbus_intf,
    "org.chromium.bluetooth.BluetoothCallback"
)]
impl IBluetoothCallback for IBluetoothCallbackDBus {
    #[dbus_method("OnAdapterPropertyChanged")]
    fn on_adapter_property_changed(&self, prop: BtPropertyType) {}

    #[dbus_method("OnAddressChanged")]
    fn on_address_changed(&self, addr: String) {}

    #[dbus_method("OnNameChanged")]
    fn on_name_changed(&self, name: String) {}

    #[dbus_method("OnDiscoverableChanged")]
    fn on_discoverable_changed(&self, discoverable: bool) {}

    #[dbus_method("OnDeviceFound")]
    fn on_device_found(&self, remote_device: BluetoothDevice) {}

    #[dbus_method("OnDeviceCleared")]
    fn on_device_cleared(&self, remote_device: BluetoothDevice) {}

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

struct IBluetoothConnectionCallbackDBus {}

impl RPCProxy for IBluetoothConnectionCallbackDBus {}

#[generate_dbus_exporter(
    export_bluetooth_connection_callback_dbus_intf,
    "org.chromium.bluetooth.BluetoothConnectionCallback"
)]
impl IBluetoothConnectionCallback for IBluetoothConnectionCallbackDBus {
    #[dbus_method("OnDeviceConnected")]
    fn on_device_connected(&self, remote_device: BluetoothDevice) {}

    #[dbus_method("OnDeviceDisconnected")]
    fn on_device_disconnected(&self, remote_device: BluetoothDevice) {}
}

struct IScannerCallbackDBus {}

impl RPCProxy for IScannerCallbackDBus {}

#[generate_dbus_exporter(
    export_scanner_callback_dbus_intf,
    "org.chromium.bluetooth.ScannerCallback"
)]
impl IScannerCallback for IScannerCallbackDBus {
    #[dbus_method("OnScannerRegistered")]
    fn on_scanner_registered(&self, uuid: Uuid128Bit, scanner_id: u8, status: GattStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnScanResult")]
    fn on_scan_result(&self, scan_result: ScanResult) {
        dbus_generated!()
    }

    #[dbus_method("OnSuspendModeChange")]
    fn on_suspend_mode_change(&self, suspend_mode: SuspendMode) {
        dbus_generated!()
    }
}

// Implements RPC-friendly wrapper methods for calling IBluetooth, generated by
// `generate_dbus_interface_client` below.
pub(crate) struct BluetoothDBusRPC {
    client_proxy: ClientDBusProxy,
}

pub(crate) struct BluetoothDBus {
    client_proxy: ClientDBusProxy,
    pub rpc: BluetoothDBusRPC,
}

impl BluetoothDBus {
    fn make_client_proxy(conn: Arc<SyncConnection>, index: i32) -> ClientDBusProxy {
        ClientDBusProxy::new(
            conn.clone(),
            String::from("org.chromium.bluetooth"),
            make_object_path(index, "adapter"),
            String::from("org.chromium.bluetooth.Bluetooth"),
        )
    }

    pub(crate) fn new(conn: Arc<SyncConnection>, index: i32) -> BluetoothDBus {
        BluetoothDBus {
            client_proxy: Self::make_client_proxy(conn.clone(), index),
            rpc: BluetoothDBusRPC { client_proxy: Self::make_client_proxy(conn.clone(), index) },
        }
    }
}

#[generate_dbus_interface_client(BluetoothDBusRPC)]
impl IBluetooth for BluetoothDBus {
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

    fn enable(&mut self) -> bool {
        // Not implemented by server
        true
    }

    fn disable(&mut self) -> bool {
        // Not implemented by server
        true
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
    fn set_discoverable(&mut self, mode: bool, duration: u32) -> bool {
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
    fn get_bond_state(&self, device: BluetoothDevice) -> BtBondState {
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
    fn get_remote_name(&self, device: BluetoothDevice) -> String {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteType")]
    fn get_remote_type(&self, device: BluetoothDevice) -> BtDeviceType {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteAlias")]
    fn get_remote_alias(&self, device: BluetoothDevice) -> String {
        dbus_generated!()
    }

    #[dbus_method("SetRemoteAlias")]
    fn set_remote_alias(&mut self, device: BluetoothDevice, new_alias: String) {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteClass")]
    fn get_remote_class(&self, device: BluetoothDevice) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteAppearance")]
    fn get_remote_appearance(&self, device: BluetoothDevice) -> u16 {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteConnected")]
    fn get_remote_connected(&self, device: BluetoothDevice) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetRemoteWakeAllowed")]
    fn get_remote_wake_allowed(&self, _device: BluetoothDevice) -> bool {
        dbus_generated!()
    }

    #[dbus_method("GetConnectedDevices")]
    fn get_connected_devices(&self) -> Vec<BluetoothDevice> {
        dbus_generated!()
    }

    #[dbus_method("GetConnectionState")]
    fn get_connection_state(&self, device: BluetoothDevice) -> BtConnectionState {
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

    #[dbus_method("IsWbsSupported")]
    fn is_wbs_supported(&self) -> bool {
        dbus_generated!()
    }
}

pub(crate) struct BluetoothQADBus {
    client_proxy: ClientDBusProxy,
}

impl BluetoothQADBus {
    pub(crate) fn new(conn: Arc<SyncConnection>, index: i32) -> BluetoothQADBus {
        BluetoothQADBus {
            client_proxy: ClientDBusProxy::new(
                conn.clone(),
                String::from("org.chromium.bluetooth"),
                make_object_path(index, "adapter"),
                String::from("org.chromium.bluetooth.BluetoothQA"),
            ),
        }
    }
}

#[generate_dbus_interface_client]
impl IBluetoothQA for BluetoothQADBus {
    #[dbus_method("GetConnectable")]
    fn get_connectable(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("SetConnectable")]
    fn set_connectable(&mut self, mode: bool) -> bool {
        dbus_generated!()
    }
}

#[dbus_propmap(AdapterWithEnabled)]
pub struct AdapterWithEnabledDbus {
    hci_interface: i32,
    enabled: bool,
}

// Implements RPC-friendly wrapper methods for calling IBluetoothManager, generated by
// `generate_dbus_interface_client` below.
pub(crate) struct BluetoothManagerDBusRPC {
    client_proxy: ClientDBusProxy,
}

pub(crate) struct BluetoothManagerDBus {
    client_proxy: ClientDBusProxy,
    pub rpc: BluetoothManagerDBusRPC,
}

impl BluetoothManagerDBus {
    fn make_client_proxy(conn: Arc<SyncConnection>) -> ClientDBusProxy {
        ClientDBusProxy::new(
            conn,
            String::from("org.chromium.bluetooth.Manager"),
            dbus::Path::new("/org/chromium/bluetooth/Manager").unwrap(),
            String::from("org.chromium.bluetooth.Manager"),
        )
    }

    pub(crate) fn new(conn: Arc<SyncConnection>) -> BluetoothManagerDBus {
        BluetoothManagerDBus {
            client_proxy: Self::make_client_proxy(conn.clone()),
            rpc: BluetoothManagerDBusRPC { client_proxy: Self::make_client_proxy(conn.clone()) },
        }
    }

    pub(crate) fn is_valid(&self) -> bool {
        let result: Result<(bool,), _> = self.client_proxy.method_withresult("GetFlossEnabled", ());
        return result.is_ok();
    }
}

#[generate_dbus_interface_client(BluetoothManagerDBusRPC)]
impl IBluetoothManager for BluetoothManagerDBus {
    #[dbus_method("Start")]
    fn start(&mut self, hci_interface: i32) {
        dbus_generated!()
    }

    #[dbus_method("Stop")]
    fn stop(&mut self, hci_interface: i32) {
        dbus_generated!()
    }

    #[dbus_method("GetAdapterEnabled")]
    fn get_adapter_enabled(&mut self, hci_interface: i32) -> bool {
        dbus_generated!()
    }

    #[dbus_method("RegisterCallback")]
    fn register_callback(&mut self, callback: Box<dyn IBluetoothManagerCallback + Send>) {
        dbus_generated!()
    }

    #[dbus_method("GetFlossEnabled")]
    fn get_floss_enabled(&mut self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("SetFlossEnabled")]
    fn set_floss_enabled(&mut self, enabled: bool) {
        dbus_generated!()
    }

    #[dbus_method("GetAvailableAdapters")]
    fn get_available_adapters(&mut self) -> Vec<AdapterWithEnabled> {
        dbus_generated!()
    }

    #[dbus_method("GetDefaultAdapter")]
    fn get_default_adapter(&mut self) -> i32 {
        dbus_generated!()
    }

    #[dbus_method("SetDesiredDefaultAdapter")]
    fn set_desired_default_adapter(&mut self, adapter: i32) {
        dbus_generated!()
    }
}

struct IBluetoothManagerCallbackDBus {}

impl RPCProxy for IBluetoothManagerCallbackDBus {}

#[generate_dbus_exporter(
    export_bluetooth_manager_callback_dbus_intf,
    "org.chromium.bluetooth.ManagerCallback"
)]
impl IBluetoothManagerCallback for IBluetoothManagerCallbackDBus {
    #[dbus_method("OnHciDeviceChanged")]
    fn on_hci_device_changed(&self, hci_interface: i32, present: bool) {}

    #[dbus_method("OnHciEnabledChanged")]
    fn on_hci_enabled_changed(&self, hci_interface: i32, enabled: bool) {}

    #[dbus_method("OnDefaultAdapterChanged")]
    fn on_default_adapter_changed(&self, hci_interface: i32) {}
}

#[allow(dead_code)]
struct IAdvertisingSetCallbackDBus {}

impl RPCProxy for IAdvertisingSetCallbackDBus {}

#[generate_dbus_exporter(
    export_advertising_set_callback_dbus_intf,
    "org.chromium.bluetooth.AdvertisingSetCallback"
)]
impl IAdvertisingSetCallback for IAdvertisingSetCallbackDBus {
    #[dbus_method("OnAdvertisingSetStarted")]
    fn on_advertising_set_started(
        &self,
        reg_id: i32,
        advertiser_id: i32,
        tx_power: i32,
        status: GattStatus,
    ) {
    }

    #[dbus_method("OnOwnAddressRead")]
    fn on_own_address_read(&self, advertiser_id: i32, address_type: i32, address: String) {}

    #[dbus_method("OnAdvertisingSetStopped")]
    fn on_advertising_set_stopped(&self, advertiser_id: i32) {}

    #[dbus_method("OnAdvertisingEnabled")]
    fn on_advertising_enabled(&self, advertiser_id: i32, enable: bool, status: GattStatus) {}

    #[dbus_method("OnAdvertisingDataSet")]
    fn on_advertising_data_set(&self, advertiser_id: i32, status: GattStatus) {}

    #[dbus_method("OnScanResponseDataSet")]
    fn on_scan_response_data_set(&self, advertiser_id: i32, status: GattStatus) {}

    #[dbus_method("OnAdvertisingParametersUpdated")]
    fn on_advertising_parameters_updated(
        &self,
        advertiser_id: i32,
        tx_power: i32,
        status: GattStatus,
    ) {
    }

    #[dbus_method("OnPeriodicAdvertisingParametersUpdated")]
    fn on_periodic_advertising_parameters_updated(&self, advertiser_id: i32, status: GattStatus) {}

    #[dbus_method("OnPeriodicAdvertisingDataSet")]
    fn on_periodic_advertising_data_set(&self, advertiser_id: i32, status: GattStatus) {}

    #[dbus_method("OnPeriodicAdvertisingEnabled")]
    fn on_periodic_advertising_enabled(
        &self,
        advertiser_id: i32,
        enable: bool,
        status: GattStatus,
    ) {
    }
}

#[dbus_propmap(AdvertisingSetParameters)]
struct AdvertisingSetParametersDBus {
    connectable: bool,
    scannable: bool,
    is_legacy: bool,
    is_anonymous: bool,
    include_tx_power: bool,
    primary_phy: LePhy,
    secondary_phy: LePhy,
    interval: i32,
    tx_power_level: i32,
    own_address_type: i32,
}

#[dbus_propmap(AdvertiseData)]
pub struct AdvertiseDataDBus {
    service_uuids: Vec<String>,
    solicit_uuids: Vec<String>,
    transport_discovery_data: Vec<Vec<u8>>,
    manufacturer_data: HashMap<i32, Vec<u8>>,
    service_data: HashMap<String, Vec<u8>>,
    include_tx_power_level: bool,
    include_device_name: bool,
}

#[dbus_propmap(PeriodicAdvertisingParameters)]
pub struct PeriodicAdvertisingParametersDBus {
    pub include_tx_power: bool,
    pub interval: i32,
}

pub(crate) struct BluetoothGattDBusRPC {
    client_proxy: ClientDBusProxy,
}

pub(crate) struct BluetoothGattDBus {
    client_proxy: ClientDBusProxy,
    pub rpc: BluetoothGattDBusRPC,
}

impl BluetoothGattDBus {
    fn make_client_proxy(conn: Arc<SyncConnection>, index: i32) -> ClientDBusProxy {
        ClientDBusProxy::new(
            conn,
            String::from("org.chromium.bluetooth"),
            make_object_path(index, "gatt"),
            String::from("org.chromium.bluetooth.BluetoothGatt"),
        )
    }

    pub(crate) fn new(conn: Arc<SyncConnection>, index: i32) -> BluetoothGattDBus {
        BluetoothGattDBus {
            client_proxy: Self::make_client_proxy(conn.clone(), index),
            rpc: BluetoothGattDBusRPC {
                client_proxy: Self::make_client_proxy(conn.clone(), index),
            },
        }
    }
}

#[generate_dbus_interface_client(BluetoothGattDBusRPC)]
impl IBluetoothGatt for BluetoothGattDBus {
    // Scanning

    #[dbus_method("RegisterScannerCallback")]
    fn register_scanner_callback(&mut self, _callback: Box<dyn IScannerCallback + Send>) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("UnregisterScannerCallback")]
    fn unregister_scanner_callback(&mut self, _callback_id: u32) -> bool {
        dbus_generated!()
    }

    #[dbus_method("RegisterScanner")]
    fn register_scanner(&mut self, callback_id: u32) -> Uuid128Bit {
        dbus_generated!()
    }

    #[dbus_method("UnregisterScanner")]
    fn unregister_scanner(&mut self, scanner_id: u8) -> bool {
        dbus_generated!()
    }

    #[dbus_method("StartScan")]
    fn start_scan(
        &mut self,
        _scanner_id: u8,
        _settings: ScanSettings,
        _filter: ScanFilter,
    ) -> BtStatus {
        dbus_generated!()
    }

    #[dbus_method("StopScan")]
    fn stop_scan(&mut self, _scanner_id: u8) -> BtStatus {
        dbus_generated!()
    }

    #[dbus_method("GetScanSuspendMode")]
    fn get_scan_suspend_mode(&self) -> SuspendMode {
        dbus_generated!()
    }

    // Advertising
    #[dbus_method("RegisterAdvertiserCallback")]
    fn register_advertiser_callback(
        &mut self,
        callback: Box<dyn IAdvertisingSetCallback + Send>,
    ) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("UnregisterAdvertiserCallback")]
    fn unregister_advertiser_callback(&mut self, callback_id: u32) {
        dbus_generated!()
    }

    #[dbus_method("StartAdvertisingSet")]
    fn start_advertising_set(
        &mut self,
        parameters: AdvertisingSetParameters,
        advertise_data: AdvertiseData,
        scan_response: Option<AdvertiseData>,
        periodic_parameters: Option<PeriodicAdvertisingParameters>,
        periodic_data: Option<AdvertiseData>,
        duration: i32,
        max_ext_adv_events: i32,
        callback_id: u32,
    ) -> i32 {
        dbus_generated!()
    }

    #[dbus_method("StopAdvertisingSet")]
    fn stop_advertising_set(&mut self, advertiser_id: i32) {
        dbus_generated!()
    }

    #[dbus_method("GetOwnAddress")]
    fn get_own_address(&mut self, advertiser_id: i32) {
        dbus_generated!()
    }

    #[dbus_method("EnableAdvertisingSet")]
    fn enable_advertising_set(
        &mut self,
        advertiser_id: i32,
        enable: bool,
        duration: i32,
        max_ext_adv_events: i32,
    ) {
        dbus_generated!()
    }

    #[dbus_method("SetAdvertisingData")]
    fn set_advertising_data(&mut self, advertiser_id: i32, data: AdvertiseData) {
        dbus_generated!()
    }

    #[dbus_method("SetScanResponseData")]
    fn set_scan_response_data(&mut self, advertiser_id: i32, data: AdvertiseData) {
        dbus_generated!()
    }

    #[dbus_method("SetAdvertisingParameters")]
    fn set_advertising_parameters(
        &mut self,
        advertiser_id: i32,
        parameters: AdvertisingSetParameters,
    ) {
        dbus_generated!()
    }

    #[dbus_method("SetPeriodicAdvertisingParameters")]
    fn set_periodic_advertising_parameters(
        &mut self,
        advertiser_id: i32,
        parameters: PeriodicAdvertisingParameters,
    ) {
        dbus_generated!()
    }

    #[dbus_method("SetPeriodicAdvertisingData")]
    fn set_periodic_advertising_data(&mut self, advertiser_id: i32, data: AdvertiseData) {
        dbus_generated!()
    }

    /// Enable/Disable periodic advertising of the advertising set.
    #[dbus_method("SetPeriodicAdvertisingEnable")]
    fn set_periodic_advertising_enable(&mut self, advertiser_id: i32, enable: bool) {
        dbus_generated!()
    }

    // GATT Client

    #[dbus_method("RegisterClient")]
    fn register_client(
        &mut self,
        app_uuid: String,
        callback: Box<dyn IBluetoothGattCallback + Send>,
        eatt_support: bool,
    ) {
        dbus_generated!()
    }

    #[dbus_method("UnregisterClient")]
    fn unregister_client(&mut self, client_id: i32) {
        dbus_generated!()
    }

    #[dbus_method("ClientConnect")]
    fn client_connect(
        &self,
        client_id: i32,
        addr: String,
        is_direct: bool,
        transport: BtTransport,
        opportunistic: bool,
        phy: LePhy,
    ) {
        dbus_generated!()
    }

    #[dbus_method("ClientDisconnect")]
    fn client_disconnect(&self, client_id: i32, addr: String) {
        dbus_generated!()
    }

    #[dbus_method("ClientSetPreferredPhy")]
    fn client_set_preferred_phy(
        &self,
        client_id: i32,
        addr: String,
        tx_phy: LePhy,
        rx_phy: LePhy,
        phy_options: i32,
    ) {
        dbus_generated!()
    }

    #[dbus_method("ClientReadPhy")]
    fn client_read_phy(&mut self, client_id: i32, addr: String) {
        dbus_generated!()
    }

    #[dbus_method("RefreshDevice")]
    fn refresh_device(&self, client_id: i32, addr: String) {
        dbus_generated!()
    }

    #[dbus_method("DiscoverServices")]
    fn discover_services(&self, client_id: i32, addr: String) {
        dbus_generated!()
    }

    #[dbus_method("DiscoverServiceByUuid")]
    fn discover_service_by_uuid(&self, client_id: i32, addr: String, uuid: String) {
        dbus_generated!()
    }

    #[dbus_method("ReadCharacteristic")]
    fn read_characteristic(&self, client_id: i32, addr: String, handle: i32, auth_req: i32) {
        dbus_generated!()
    }

    #[dbus_method("ReadUsingCharacteristicUuid")]
    fn read_using_characteristic_uuid(
        &self,
        client_id: i32,
        addr: String,
        uuid: String,
        start_handle: i32,
        end_handle: i32,
        auth_req: i32,
    ) {
        dbus_generated!()
    }

    #[dbus_method("WriteCharacteristic")]
    fn write_characteristic(
        &self,
        client_id: i32,
        addr: String,
        handle: i32,
        write_type: GattWriteType,
        auth_req: i32,
        value: Vec<u8>,
    ) -> GattWriteRequestStatus {
        dbus_generated!()
    }

    #[dbus_method("ReadDescriptor")]
    fn read_descriptor(&self, client_id: i32, addr: String, handle: i32, auth_req: i32) {
        dbus_generated!()
    }

    #[dbus_method("WriteDescriptor")]
    fn write_descriptor(
        &self,
        client_id: i32,
        addr: String,
        handle: i32,
        auth_req: i32,
        value: Vec<u8>,
    ) {
        dbus_generated!()
    }

    #[dbus_method("RegisterForNotification")]
    fn register_for_notification(&self, client_id: i32, addr: String, handle: i32, enable: bool) {
        dbus_generated!()
    }

    #[dbus_method("BeginReliableWrite")]
    fn begin_reliable_write(&mut self, client_id: i32, addr: String) {
        dbus_generated!()
    }

    #[dbus_method("EndReliableWrite")]
    fn end_reliable_write(&mut self, client_id: i32, addr: String, execute: bool) {
        dbus_generated!()
    }

    #[dbus_method("ReadRemoteRssi")]
    fn read_remote_rssi(&self, client_id: i32, addr: String) {
        dbus_generated!()
    }

    #[dbus_method("ConfigureMtu")]
    fn configure_mtu(&self, client_id: i32, addr: String, mtu: i32) {
        dbus_generated!()
    }

    #[dbus_method("ConnectionParameterUpdate")]
    fn connection_parameter_update(
        &self,
        client_id: i32,
        addr: String,
        min_interval: i32,
        max_interval: i32,
        latency: i32,
        timeout: i32,
        min_ce_len: u16,
        max_ce_len: u16,
    ) {
        dbus_generated!()
    }
}

struct IBluetoothGattCallbackDBus {}

impl RPCProxy for IBluetoothGattCallbackDBus {}

#[generate_dbus_exporter(
    export_bluetooth_gatt_callback_dbus_intf,
    "org.chromium.bluetooth.BluetoothGattCallback"
)]
impl IBluetoothGattCallback for IBluetoothGattCallbackDBus {
    #[dbus_method("OnClientRegistered")]
    fn on_client_registered(&self, status: GattStatus, client_id: i32) {}

    #[dbus_method("OnClientConnectionState")]
    fn on_client_connection_state(
        &self,
        status: GattStatus,
        client_id: i32,
        connected: bool,
        addr: String,
    ) {
    }

    #[dbus_method("OnPhyUpdate")]
    fn on_phy_update(&self, addr: String, tx_phy: LePhy, rx_phy: LePhy, status: GattStatus) {}

    #[dbus_method("OnPhyRead")]
    fn on_phy_read(&self, addr: String, tx_phy: LePhy, rx_phy: LePhy, status: GattStatus) {}

    #[dbus_method("OnSearchComplete")]
    fn on_search_complete(
        &self,
        addr: String,
        services: Vec<BluetoothGattService>,
        status: GattStatus,
    ) {
    }

    #[dbus_method("OnCharacteristicRead")]
    fn on_characteristic_read(
        &self,
        addr: String,
        status: GattStatus,
        handle: i32,
        value: Vec<u8>,
    ) {
    }

    #[dbus_method("OnCharacteristicWrite")]
    fn on_characteristic_write(&self, addr: String, status: GattStatus, handle: i32) {}

    #[dbus_method("OnExecuteWrite")]
    fn on_execute_write(&self, addr: String, status: GattStatus) {}

    #[dbus_method("OnDescriptorRead")]
    fn on_descriptor_read(&self, addr: String, status: GattStatus, handle: i32, value: Vec<u8>) {}

    #[dbus_method("OnDescriptorWrite")]
    fn on_descriptor_write(&self, addr: String, status: GattStatus, handle: i32) {}

    #[dbus_method("OnNotify")]
    fn on_notify(&self, addr: String, handle: i32, value: Vec<u8>) {}

    #[dbus_method("OnReadRemoteRssi")]
    fn on_read_remote_rssi(&self, addr: String, rssi: i32, status: GattStatus) {}

    #[dbus_method("OnConfigureMtu")]
    fn on_configure_mtu(&self, addr: String, mtu: i32, status: GattStatus) {}

    #[dbus_method("OnConnectionUpdated")]
    fn on_connection_updated(
        &self,
        addr: String,
        interval: i32,
        latency: i32,
        timeout: i32,
        status: GattStatus,
    ) {
    }

    #[dbus_method("OnServiceChanged")]
    fn on_service_changed(&self, addr: String) {}
}

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

pub(crate) struct BluetoothSocketManagerDBusRPC {
    client_proxy: ClientDBusProxy,
}

pub(crate) struct BluetoothSocketManagerDBus {
    client_proxy: ClientDBusProxy,
    pub rpc: BluetoothSocketManagerDBusRPC,
}

impl BluetoothSocketManagerDBus {
    fn make_client_proxy(conn: Arc<SyncConnection>, index: i32) -> ClientDBusProxy {
        ClientDBusProxy::new(
            conn,
            String::from("org.chromium.bluetooth"),
            make_object_path(index, "adapter"),
            String::from("org.chromium.bluetooth.SocketManager"),
        )
    }

    pub(crate) fn new(conn: Arc<SyncConnection>, index: i32) -> Self {
        BluetoothSocketManagerDBus {
            client_proxy: Self::make_client_proxy(conn.clone(), index),
            rpc: BluetoothSocketManagerDBusRPC {
                client_proxy: Self::make_client_proxy(conn.clone(), index),
            },
        }
    }
}

#[generate_dbus_interface_client(BluetoothSocketManagerDBusRPC)]
impl IBluetoothSocketManager for BluetoothSocketManagerDBus {
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

struct IBluetoothSocketManagerCallbacksDBus {}

impl RPCProxy for IBluetoothSocketManagerCallbacksDBus {}

#[generate_dbus_exporter(
    export_socket_callback_dbus_intf,
    "org.chromium.bluetooth.SocketManagerCallback"
)]
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

pub(crate) struct SuspendDBus {
    client_proxy: ClientDBusProxy,
}

impl SuspendDBus {
    pub(crate) fn new(conn: Arc<SyncConnection>, index: i32) -> SuspendDBus {
        SuspendDBus {
            client_proxy: ClientDBusProxy::new(
                conn.clone(),
                String::from("org.chromium.bluetooth"),
                make_object_path(index, "adapter"),
                String::from("org.chromium.bluetooth.Suspend"),
            ),
        }
    }
}

#[generate_dbus_interface_client]
impl ISuspend for SuspendDBus {
    #[dbus_method("RegisterCallback")]
    fn register_callback(&mut self, _callback: Box<dyn ISuspendCallback + Send>) -> bool {
        dbus_generated!()
    }

    #[dbus_method("UnregisterCallback")]
    fn unregister_callback(&mut self, _callback_id: u32) -> bool {
        dbus_generated!()
    }

    #[dbus_method("Suspend")]
    fn suspend(&mut self, _suspend_type: SuspendType) {
        dbus_generated!()
    }

    #[dbus_method("Resume")]
    fn resume(&self) -> bool {
        dbus_generated!()
    }
}

struct ISuspendCallbackDBus {}

impl RPCProxy for ISuspendCallbackDBus {}

#[generate_dbus_exporter(
    export_suspend_callback_dbus_intf,
    "org.chromium.bluetooth.SuspendCallback"
)]
impl ISuspendCallback for ISuspendCallbackDBus {
    #[dbus_method("OnCallbackRegistered")]
    fn on_callback_registered(&self, callback_id: u32) {}
    #[dbus_method("OnSuspendReady")]
    fn on_suspend_ready(&self, suspend_id: u32) {}
    #[dbus_method("OnResumed")]
    fn on_resumed(&self, suspend_id: i32) {}
}
