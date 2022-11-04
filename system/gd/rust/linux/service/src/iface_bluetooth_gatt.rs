use bt_topshim::btif::{BtStatus, BtTransport, Uuid, Uuid128Bit};
use bt_topshim::profiles::gatt::{AdvertisingStatus, GattStatus, LePhy};

use btstack::bluetooth_adv::{
    AdvertiseData, AdvertisingSetParameters, IAdvertisingSetCallback, ManfId,
    PeriodicAdvertisingParameters,
};
use btstack::bluetooth_gatt::{
    BluetoothGattCharacteristic, BluetoothGattDescriptor, BluetoothGattService,
    GattWriteRequestStatus, GattWriteType, IBluetoothGatt, IBluetoothGattCallback,
    IScannerCallback, ScanFilter, ScanFilterCondition, ScanFilterPattern, ScanResult, ScanSettings,
    ScanType,
};
use btstack::{RPCProxy, SuspendMode};

use dbus::arg::RefArg;

use dbus::nonblock::SyncConnection;
use dbus::strings::Path;

use dbus_macros::{dbus_method, dbus_propmap, dbus_proxy_obj, generate_dbus_exporter};

use dbus_projection::DisconnectWatcher;
use dbus_projection::{dbus_generated, impl_dbus_arg_enum};

use num_traits::cast::{FromPrimitive, ToPrimitive};

use std::collections::HashMap;
use std::convert::TryInto;
use std::sync::Arc;

use crate::dbus_arg::{DBusArg, DBusArgError, RefArgToRust};

#[allow(dead_code)]
struct BluetoothGattCallbackDBus {}

#[dbus_proxy_obj(BluetoothGattCallback, "org.chromium.bluetooth.BluetoothGattCallback")]
impl IBluetoothGattCallback for BluetoothGattCallbackDBus {
    #[dbus_method("OnClientRegistered")]
    fn on_client_registered(&self, status: GattStatus, scanner_id: i32) {
        dbus_generated!()
    }

    #[dbus_method("OnClientConnectionState")]
    fn on_client_connection_state(
        &self,
        status: GattStatus,
        client_id: i32,
        connected: bool,
        addr: String,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnPhyUpdate")]
    fn on_phy_update(&self, addr: String, tx_phy: LePhy, rx_phy: LePhy, status: GattStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnPhyRead")]
    fn on_phy_read(&self, addr: String, tx_phy: LePhy, rx_phy: LePhy, status: GattStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnSearchComplete")]
    fn on_search_complete(
        &self,
        addr: String,
        services: Vec<BluetoothGattService>,
        status: GattStatus,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnCharacteristicRead")]
    fn on_characteristic_read(
        &self,
        addr: String,
        status: GattStatus,
        handle: i32,
        value: Vec<u8>,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnCharacteristicWrite")]
    fn on_characteristic_write(&self, addr: String, status: GattStatus, handle: i32) {
        dbus_generated!()
    }

    #[dbus_method("OnExecuteWrite")]
    fn on_execute_write(&self, addr: String, status: GattStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnDescriptorRead")]
    fn on_descriptor_read(&self, addr: String, status: GattStatus, handle: i32, value: Vec<u8>) {
        dbus_generated!()
    }

    #[dbus_method("OnDescriptorWrite")]
    fn on_descriptor_write(&self, addr: String, status: GattStatus, handle: i32) {
        dbus_generated!()
    }

    #[dbus_method("OnNotify")]
    fn on_notify(&self, addr: String, handle: i32, value: Vec<u8>) {
        dbus_generated!()
    }

    #[dbus_method("OnReadRemoteRssi")]
    fn on_read_remote_rssi(&self, addr: String, rssi: i32, status: GattStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnConfigureMtu")]
    fn on_configure_mtu(&self, addr: String, mtu: i32, status: GattStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnConnectionUpdated")]
    fn on_connection_updated(
        &self,
        addr: String,
        interval: i32,
        latency: i32,
        timeout: i32,
        status: GattStatus,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnServiceChanged")]
    fn on_service_changed(&self, addr: String) {
        dbus_generated!()
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

#[allow(dead_code)]
struct ScannerCallbackDBus {}

#[dbus_proxy_obj(ScannerCallback, "org.chromium.bluetooth.ScannerCallback")]
impl IScannerCallback for ScannerCallbackDBus {
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
    uuid: Uuid128Bit,
    instance_id: i32,
    service_type: i32,
    characteristics: Vec<BluetoothGattCharacteristic>,
    included_services: Vec<BluetoothGattService>,
}

#[dbus_propmap(ScanSettings)]
struct ScanSettingsDBus {
    interval: i32,
    window: i32,
    scan_type: ScanType,
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

impl_dbus_arg_enum!(AdvertisingStatus);
impl_dbus_arg_enum!(GattStatus);
impl_dbus_arg_enum!(GattWriteRequestStatus);
impl_dbus_arg_enum!(GattWriteType);
impl_dbus_arg_enum!(LePhy);
impl_dbus_arg_enum!(ScanType);
impl_dbus_arg_enum!(SuspendMode);

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

#[allow(dead_code)]
struct AdvertisingSetCallbackDBus {}

#[dbus_proxy_obj(AdvertisingSetCallback, "org.chromium.bluetooth.AdvertisingSetCallback")]
impl IAdvertisingSetCallback for AdvertisingSetCallbackDBus {
    #[dbus_method("OnAdvertisingSetStarted")]
    fn on_advertising_set_started(
        &self,
        reg_id: i32,
        advertiser_id: i32,
        tx_power: i32,
        status: AdvertisingStatus,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnOwnAddressRead")]
    fn on_own_address_read(&self, advertiser_id: i32, address_type: i32, address: String) {
        dbus_generated!()
    }

    #[dbus_method("OnAdvertisingSetStopped")]
    fn on_advertising_set_stopped(&self, advertiser_id: i32) {
        dbus_generated!()
    }

    #[dbus_method("OnAdvertisingEnabled")]
    fn on_advertising_enabled(&self, advertiser_id: i32, enable: bool, status: AdvertisingStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnAdvertisingDataSet")]
    fn on_advertising_data_set(&self, advertiser_id: i32, status: AdvertisingStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnScanResponseDataSet")]
    fn on_scan_response_data_set(&self, advertiser_id: i32, status: AdvertisingStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnAdvertisingParametersUpdated")]
    fn on_advertising_parameters_updated(
        &self,
        advertiser_id: i32,
        tx_power: i32,
        status: AdvertisingStatus,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnPeriodicAdvertisingParametersUpdated")]
    fn on_periodic_advertising_parameters_updated(
        &self,
        advertiser_id: i32,
        status: AdvertisingStatus,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnPeriodicAdvertisingDataSet")]
    fn on_periodic_advertising_data_set(&self, advertiser_id: i32, status: AdvertisingStatus) {
        dbus_generated!()
    }

    #[dbus_method("OnPeriodicAdvertisingEnabled")]
    fn on_periodic_advertising_enabled(
        &self,
        advertiser_id: i32,
        enable: bool,
        status: AdvertisingStatus,
    ) {
        dbus_generated!()
    }

    #[dbus_method("OnSuspendModeChange")]
    fn on_suspend_mode_change(&self, suspend_mode: SuspendMode) {
        dbus_generated!()
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
    service_uuids: Vec<Uuid>,
    solicit_uuids: Vec<Uuid>,
    transport_discovery_data: Vec<Vec<u8>>,
    manufacturer_data: HashMap<ManfId, Vec<u8>>,
    service_data: HashMap<String, Vec<u8>>,
    include_tx_power_level: bool,
    include_device_name: bool,
}

#[dbus_propmap(PeriodicAdvertisingParameters)]
pub struct PeriodicAdvertisingParametersDBus {
    pub include_tx_power: bool,
    pub interval: i32,
}

#[allow(dead_code)]
struct IBluetoothGattDBus {}

#[generate_dbus_exporter(export_bluetooth_gatt_dbus_intf, "org.chromium.bluetooth.BluetoothGatt")]
impl IBluetoothGatt for IBluetoothGattDBus {
    // Scanning

    #[dbus_method("IsMsftSupported")]
    fn is_msft_supported(&self) -> bool {
        dbus_generated!()
    }

    #[dbus_method("RegisterScannerCallback")]
    fn register_scanner_callback(&mut self, callback: Box<dyn IScannerCallback + Send>) -> u32 {
        dbus_generated!()
    }

    #[dbus_method("UnregisterScannerCallback")]
    fn unregister_scanner_callback(&mut self, callback_id: u32) -> bool {
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
        scanner_id: u8,
        settings: ScanSettings,
        filter: ScanFilter,
    ) -> BtStatus {
        dbus_generated!()
    }

    #[dbus_method("StopScan")]
    fn stop_scan(&mut self, scanner_id: u8) -> BtStatus {
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
}
