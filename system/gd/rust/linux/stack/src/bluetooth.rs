//! Anything related to the adapter API (IBluetooth).

use bt_topshim::btif::{
    BaseCallbacks, BaseCallbacksDispatcher, BluetoothInterface, BluetoothProperty, BtAclState,
    BtBondState, BtConnectionDirection, BtConnectionState, BtDeviceType, BtDiscoveryState,
    BtHciErrorCode, BtPinCode, BtPropertyType, BtScanMode, BtSspVariant, BtState, BtStatus,
    BtTransport, BtVendorProductInfo, RawAddress, ToggleableProfile, Uuid, Uuid128Bit,
};
use bt_topshim::{
    metrics,
    profiles::hid_host::{
        BthhConnectionState, BthhHidInfo, BthhProtocolMode, BthhStatus, HHCallbacks,
        HHCallbacksDispatcher, HidHost,
    },
    profiles::sdp::{BtSdpRecord, Sdp, SdpCallbacks, SdpCallbacksDispatcher},
    topstack,
};

use btif_macros::{btif_callback, btif_callbacks_dispatcher};

use log::{debug, warn};
use num_traits::cast::ToPrimitive;
use std::collections::HashMap;
use std::hash::Hash;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;
use std::time::Instant;
use tokio::sync::mpsc::Sender;
use tokio::task::JoinHandle;
use tokio::time;

use crate::bluetooth_admin::BluetoothAdmin;
use crate::bluetooth_media::{BluetoothMedia, IBluetoothMedia, MediaActions};
use crate::callbacks::Callbacks;
use crate::uuid::{Profile, UuidHelper, HOGP};
use crate::{Message, RPCProxy};

const DEFAULT_DISCOVERY_TIMEOUT_MS: u64 = 12800;
const MIN_ADV_INSTANCES_FOR_MULTI_ADV: u8 = 5;

/// Devices that were last seen longer than this duration are considered stale
/// if they haven't already bonded or connected. Once this duration expires, the
/// clear event should be sent to clients.
const FOUND_DEVICE_FRESHNESS: Duration = Duration::from_secs(30);

/// This is the value returned from Bluetooth Interface calls.
// TODO(241930383): Add enum to topshim
const BTM_SUCCESS: i32 = 0;

/// Defines the adapter API.
pub trait IBluetooth {
    /// Adds a callback from a client who wishes to observe adapter events.
    fn register_callback(&mut self, callback: Box<dyn IBluetoothCallback + Send>);

    /// Adds a callback from a client who wishes to observe connection events.
    fn register_connection_callback(
        &mut self,
        callback: Box<dyn IBluetoothConnectionCallback + Send>,
    ) -> u32;

    /// Removes registered callback.
    fn unregister_connection_callback(&mut self, callback_id: u32) -> bool;

    /// Enables the adapter.
    ///
    /// Returns true if the request is accepted.
    fn enable(&mut self) -> bool;

    /// Disables the adapter.
    ///
    /// Returns true if the request is accepted.
    fn disable(&mut self) -> bool;

    /// Returns the Bluetooth address of the local adapter.
    fn get_address(&self) -> String;

    /// Gets supported UUIDs by the local adapter.
    fn get_uuids(&self) -> Vec<Uuid128Bit>;

    /// Gets the local adapter name.
    fn get_name(&self) -> String;

    /// Sets the local adapter name.
    fn set_name(&self, name: String) -> bool;

    /// Gets the bluetooth class.
    fn get_bluetooth_class(&self) -> u32;

    /// Sets the bluetooth class.
    fn set_bluetooth_class(&self, cod: u32) -> bool;

    /// Returns whether the adapter is discoverable.
    fn get_discoverable(&self) -> bool;

    /// Returns the adapter discoverable timeout.
    fn get_discoverable_timeout(&self) -> u32;

    /// Sets discoverability. If discoverable, limits the duration with given value.
    fn set_discoverable(&mut self, mode: bool, duration: u32) -> bool;

    /// Returns whether multi-advertisement is supported.
    /// A minimum number of 5 advertising instances is required for multi-advertisment support.
    fn is_multi_advertisement_supported(&self) -> bool;

    /// Returns whether LE extended advertising is supported.
    fn is_le_extended_advertising_supported(&self) -> bool;

    /// Starts BREDR Inquiry.
    fn start_discovery(&self) -> bool;

    /// Cancels BREDR Inquiry.
    fn cancel_discovery(&self) -> bool;

    /// Checks if discovery is started.
    fn is_discovering(&self) -> bool;

    /// Checks when discovery ends in milliseconds from now.
    fn get_discovery_end_millis(&self) -> u64;

    /// Initiates pairing to a remote device. Triggers connection if not already started.
    fn create_bond(&self, device: BluetoothDevice, transport: BtTransport) -> bool;

    /// Cancels any pending bond attempt on given device.
    fn cancel_bond_process(&self, device: BluetoothDevice) -> bool;

    /// Removes pairing for given device.
    fn remove_bond(&self, device: BluetoothDevice) -> bool;

    /// Returns a list of known bonded devices.
    fn get_bonded_devices(&self) -> Vec<BluetoothDevice>;

    /// Gets the bond state of a single device.
    fn get_bond_state(&self, device: BluetoothDevice) -> BtBondState;

    /// Set pin on bonding device.
    fn set_pin(&self, device: BluetoothDevice, accept: bool, pin_code: Vec<u8>) -> bool;

    /// Set passkey on bonding device.
    fn set_passkey(&self, device: BluetoothDevice, accept: bool, passkey: Vec<u8>) -> bool;

    /// Confirm that a pairing should be completed on a bonding device.
    fn set_pairing_confirmation(&self, device: BluetoothDevice, accept: bool) -> bool;

    /// Gets the name of the remote device.
    fn get_remote_name(&self, device: BluetoothDevice) -> String;

    /// Gets the type of the remote device.
    fn get_remote_type(&self, device: BluetoothDevice) -> BtDeviceType;

    /// Gets the alias of the remote device.
    fn get_remote_alias(&self, device: BluetoothDevice) -> String;

    /// Sets the alias of the remote device.
    fn set_remote_alias(&mut self, device: BluetoothDevice, new_alias: String);

    /// Gets the class of the remote device.
    fn get_remote_class(&self, device: BluetoothDevice) -> u32;

    /// Gets the appearance of the remote device.
    fn get_remote_appearance(&self, device: BluetoothDevice) -> u16;

    /// Gets whether the remote device is connected.
    fn get_remote_connected(&self, device: BluetoothDevice) -> bool;

    /// Gets whether the remote device can wake the system.
    fn get_remote_wake_allowed(&self, device: BluetoothDevice) -> bool;

    /// Returns a list of connected devices.
    fn get_connected_devices(&self) -> Vec<BluetoothDevice>;

    /// Gets the connection state of a single device.
    fn get_connection_state(&self, device: BluetoothDevice) -> BtConnectionState;

    /// Gets the connection state of a specific profile.
    fn get_profile_connection_state(&self, profile: Profile) -> u32;

    /// Returns the cached UUIDs of a remote device.
    fn get_remote_uuids(&self, device: BluetoothDevice) -> Vec<Uuid128Bit>;

    /// Triggers SDP to get UUIDs of a remote device.
    fn fetch_remote_uuids(&self, device: BluetoothDevice) -> bool;

    /// Triggers SDP and searches for a specific UUID on a remote device.
    fn sdp_search(&self, device: BluetoothDevice, uuid: Uuid128Bit) -> bool;

    /// Connect all profiles supported by device and enabled on adapter.
    fn connect_all_enabled_profiles(&mut self, device: BluetoothDevice) -> bool;

    /// Disconnect all profiles supported by device and enabled on adapter.
    fn disconnect_all_enabled_profiles(&mut self, device: BluetoothDevice) -> bool;

    /// Returns whether WBS is supported.
    fn is_wbs_supported(&self) -> bool;
}

/// Adapter API for Bluetooth qualification and verification.
///
/// This interface is provided for testing and debugging.
/// Clients should not use this interface for production.
pub trait IBluetoothQA {
    /// Returns whether the adapter is connectable.
    fn get_connectable(&self) -> bool;

    /// Sets connectability. Returns true on success, false otherwise.
    fn set_connectable(&mut self, mode: bool) -> bool;
}

/// Serializable device used in various apis.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
pub struct BluetoothDevice {
    pub address: String,
    pub name: String,
}

impl BluetoothDevice {
    pub(crate) fn new(address: String, name: String) -> BluetoothDevice {
        BluetoothDevice { address, name }
    }

    pub(crate) fn from_properties(in_properties: &Vec<BluetoothProperty>) -> BluetoothDevice {
        let mut address = String::from("");
        let mut name = String::from("");

        for prop in in_properties {
            match &prop {
                BluetoothProperty::BdAddr(bdaddr) => {
                    address = bdaddr.to_string();
                }
                BluetoothProperty::BdName(bdname) => {
                    name = bdname.clone();
                }
                _ => {}
            }
        }

        BluetoothDevice::new(address, name)
    }
}

/// Internal data structure that keeps a map of cached properties for a remote device.
struct BluetoothDeviceContext {
    pub bond_state: BtBondState,
    pub acl_state: BtAclState,
    pub info: BluetoothDevice,
    pub last_seen: Instant,
    pub properties: HashMap<BtPropertyType, BluetoothProperty>,
}

impl BluetoothDeviceContext {
    pub(crate) fn new(
        bond_state: BtBondState,
        acl_state: BtAclState,
        info: BluetoothDevice,
        last_seen: Instant,
        properties: Vec<BluetoothProperty>,
    ) -> BluetoothDeviceContext {
        let mut device = BluetoothDeviceContext {
            bond_state,
            acl_state,
            info,
            last_seen,
            properties: HashMap::new(),
        };
        device.update_properties(&properties);
        device
    }

    pub(crate) fn update_properties(&mut self, in_properties: &Vec<BluetoothProperty>) {
        for prop in in_properties {
            match &prop {
                BluetoothProperty::BdAddr(bdaddr) => {
                    self.info.address = bdaddr.to_string();
                }
                BluetoothProperty::BdName(bdname) => {
                    self.info.name = bdname.clone();
                }
                _ => {}
            }

            self.properties.insert(prop.get_type(), prop.clone());
        }
    }

    /// Mark this device as seen.
    pub(crate) fn seen(&mut self) {
        self.last_seen = Instant::now();
    }
}

/// The interface for adapter callbacks registered through `IBluetooth::register_callback`.
pub trait IBluetoothCallback: RPCProxy {
    /// When any adapter property changes.
    fn on_adapter_property_changed(&self, prop: BtPropertyType);

    /// When any of the adapter local address is changed.
    fn on_address_changed(&self, addr: String);

    /// When the adapter name is changed.
    fn on_name_changed(&self, name: String);

    /// When the adapter's discoverable mode is changed.
    fn on_discoverable_changed(&self, discoverable: bool);

    /// When a device is found via discovery.
    fn on_device_found(&self, remote_device: BluetoothDevice);

    /// When a device is cleared from discovered devices cache.
    fn on_device_cleared(&self, remote_device: BluetoothDevice);

    /// When the discovery state is changed.
    fn on_discovering_changed(&self, discovering: bool);

    /// When there is a pairing/bonding process and requires agent to display the event to UI.
    fn on_ssp_request(
        &self,
        remote_device: BluetoothDevice,
        cod: u32,
        variant: BtSspVariant,
        passkey: u32,
    );

    /// When a bonding attempt has completed.
    fn on_bond_state_changed(&self, status: u32, device_address: String, state: u32);
}

/// An interface for other modules to track found remote devices.
pub trait IBluetoothDeviceCallback {
    /// When a device is found via discovery.
    fn on_device_found(&mut self, remote_device: BluetoothDevice);

    /// When a device is cleared from discovered devices cache.
    fn on_device_cleared(&mut self, remote_device: BluetoothDevice);

    /// When a device property is changed.
    fn on_remote_device_properties_changed(
        &mut self,
        remote_device: BluetoothDevice,
        properties: Vec<BluetoothProperty>,
    );
}

pub trait IBluetoothConnectionCallback: RPCProxy {
    /// Notification sent when a remote device completes HCI connection.
    fn on_device_connected(&self, remote_device: BluetoothDevice);

    /// Notification sent when a remote device completes HCI disconnection.
    fn on_device_disconnected(&self, remote_device: BluetoothDevice);
}

/// Implementation of the adapter API.
pub struct Bluetooth {
    intf: Arc<Mutex<BluetoothInterface>>,

    bonded_devices: HashMap<String, BluetoothDeviceContext>,
    bluetooth_media: Arc<Mutex<Box<BluetoothMedia>>>,
    bluetooth_admin: Arc<Mutex<Box<BluetoothAdmin>>>,
    callbacks: Callbacks<dyn IBluetoothCallback + Send>,
    connection_callbacks: Callbacks<dyn IBluetoothConnectionCallback + Send>,
    discovering_started: Instant,
    hh: Option<HidHost>,
    is_connectable: bool,
    is_discovering: bool,
    local_address: Option<RawAddress>,
    properties: HashMap<BtPropertyType, BluetoothProperty>,
    profiles_ready: bool,
    found_devices: HashMap<String, BluetoothDeviceContext>,
    freshness_check: Option<JoinHandle<()>>,
    sdp: Option<Sdp>,
    state: BtState,
    tx: Sender<Message>,
    /// Used to delay connection until we have SDP results.
    wait_to_connect: bool,
    // Internal API members
    discoverable_timeout: Option<JoinHandle<()>>,

    /// Used to notify signal handler that we have turned off the stack.
    sig_notifier: Arc<(Mutex<bool>, Condvar)>,
}

impl Bluetooth {
    /// Constructs the IBluetooth implementation.
    pub fn new(
        tx: Sender<Message>,
        intf: Arc<Mutex<BluetoothInterface>>,
        bluetooth_media: Arc<Mutex<Box<BluetoothMedia>>>,
        sig_notifier: Arc<(Mutex<bool>, Condvar)>,
        bluetooth_admin: Arc<Mutex<Box<BluetoothAdmin>>>,
    ) -> Bluetooth {
        Bluetooth {
            bonded_devices: HashMap::new(),
            callbacks: Callbacks::new(tx.clone(), Message::AdapterCallbackDisconnected),
            connection_callbacks: Callbacks::new(
                tx.clone(),
                Message::ConnectionCallbackDisconnected,
            ),
            hh: None,
            bluetooth_media,
            bluetooth_admin,
            discovering_started: Instant::now(),
            intf,
            is_connectable: false,
            is_discovering: false,
            local_address: None,
            properties: HashMap::new(),
            profiles_ready: false,
            found_devices: HashMap::new(),
            freshness_check: None,
            sdp: None,
            state: BtState::Off,
            tx,
            wait_to_connect: false,
            // Internal API members
            discoverable_timeout: None,
            sig_notifier,
        }
    }

    fn disable_profile(&mut self, profile: &Profile) {
        if !UuidHelper::is_profile_supported(profile) {
            return;
        }

        match profile {
            Profile::Hid => {
                self.hh.as_mut().unwrap().disable();
            }

            Profile::A2dpSource | Profile::Hfp => {
                self.bluetooth_media.lock().unwrap().disable_profile(profile);
            }
            // Ignore profiles that we don't connect.
            _ => (),
        }
    }

    fn enable_profile(&mut self, profile: &Profile) {
        if !UuidHelper::is_profile_supported(profile) {
            return;
        }

        match profile {
            Profile::Hid => {
                self.hh.as_mut().unwrap().enable();
            }

            Profile::A2dpSource | Profile::Hfp => {
                self.bluetooth_media.lock().unwrap().enable_profile(profile);
            }
            // Ignore profiles that we don't connect.
            _ => (),
        }
    }

    fn is_profile_enabled(&self, profile: &Profile) -> Option<bool> {
        if !UuidHelper::is_profile_supported(profile) {
            return None;
        }

        match profile {
            Profile::Hid => Some(!self.hh.is_none() && self.hh.as_ref().unwrap().is_enabled()),

            Profile::A2dpSource | Profile::Hfp => {
                self.bluetooth_media.lock().unwrap().is_profile_enabled(profile)
            }
            // Ignore profiles that we don't connect.
            _ => None,
        }
    }

    pub fn toggle_enabled_profiles(&mut self, allowed_services: &Vec<Uuid128Bit>) {
        for profile in UuidHelper::get_supported_profiles().clone() {
            // Only toggle initializable profiles.
            if let Some(enabled) = self.is_profile_enabled(&profile) {
                let allowed = allowed_services.len() == 0
                    || allowed_services.contains(&UuidHelper::get_profile_uuid(&profile).unwrap());

                if allowed && !enabled {
                    debug!("Enabling profile {}", &profile);
                    self.enable_profile(&profile);
                } else if !allowed && enabled {
                    debug!("Disabling profile {}", &profile);
                    self.disable_profile(&profile);
                }
            }
        }
    }

    pub fn init_profiles(&mut self) {
        let sdptx = self.tx.clone();
        self.sdp = Some(Sdp::new(&self.intf.lock().unwrap()));
        self.sdp.as_mut().unwrap().initialize(SdpCallbacksDispatcher {
            dispatch: Box::new(move |cb| {
                let txl = sdptx.clone();
                topstack::get_runtime().spawn(async move {
                    let _ = txl.send(Message::Sdp(cb)).await;
                });
            }),
        });

        let hhtx = self.tx.clone();
        self.hh = Some(HidHost::new(&self.intf.lock().unwrap()));
        self.hh.as_mut().unwrap().initialize(HHCallbacksDispatcher {
            dispatch: Box::new(move |cb| {
                let txl = hhtx.clone();
                topstack::get_runtime().spawn(async move {
                    let _ = txl.send(Message::HidHost(cb)).await;
                });
            }),
        });

        self.toggle_enabled_profiles(&vec![]);
        // Mark profiles as ready
        self.profiles_ready = true;
    }

    fn update_local_address(&mut self, addr: &RawAddress) {
        self.local_address = Some(*addr);

        self.callbacks.for_all_callbacks(|callback| {
            callback.on_address_changed(self.local_address.unwrap().to_string());
        });
    }

    pub(crate) fn adapter_callback_disconnected(&mut self, id: u32) {
        self.callbacks.remove_callback(id);
    }

    pub(crate) fn connection_callback_disconnected(&mut self, id: u32) {
        self.connection_callbacks.remove_callback(id);
    }

    fn get_remote_device_if_found(&self, address: &str) -> Option<&BluetoothDeviceContext> {
        self.bonded_devices.get(address).or_else(|| self.found_devices.get(address))
    }

    fn get_remote_device_if_found_mut(
        &mut self,
        address: &str,
    ) -> Option<&mut BluetoothDeviceContext> {
        match self.bonded_devices.get_mut(address) {
            None => self.found_devices.get_mut(address),
            some => some,
        }
    }

    fn get_remote_device_property(
        &self,
        device: &BluetoothDevice,
        property_type: &BtPropertyType,
    ) -> Option<BluetoothProperty> {
        self.get_remote_device_if_found(&device.address)
            .and_then(|d| d.properties.get(property_type).and_then(|p| Some(p.clone())))
    }

    fn set_remote_device_property(
        &mut self,
        device: &BluetoothDevice,
        property_type: BtPropertyType,
        property: BluetoothProperty,
    ) -> Result<(), ()> {
        let remote_device = match self.get_remote_device_if_found_mut(&device.address) {
            Some(d) => d,
            None => {
                return Err(());
            }
        };

        let mut addr = RawAddress::from_string(device.address.clone());
        if addr.is_none() {
            return Err(());
        }
        let addr = addr.as_mut().unwrap();

        // TODO: Determine why a callback isn't invoked to do this.
        remote_device.properties.insert(property_type, property.clone());
        self.intf.lock().unwrap().set_remote_device_property(addr, property);
        Ok(())
    }

    /// Check whether found devices are still fresh. If they're outside the
    /// freshness window, send a notification to clear the device from clients.
    pub(crate) fn trigger_freshness_check(&mut self) {
        if let Some(ref handle) = self.freshness_check {
            // Abort and drop the previous JoinHandle.
            handle.abort();
            self.freshness_check = None;
        }

        // A found device is considered fresh if:
        // * It was last seen less than |FOUND_DEVICE_FRESHNESS| ago.
        // * It is currently connected.
        fn is_fresh(d: &BluetoothDeviceContext, now: &Instant) -> bool {
            let fresh_at = d.last_seen + FOUND_DEVICE_FRESHNESS;
            now < &fresh_at || d.acl_state == BtAclState::Connected
        }

        let now = Instant::now();
        let stale_devices: Vec<BluetoothDevice> = self
            .found_devices
            .iter()
            .filter(|(_, d)| !is_fresh(d, &now))
            .map(|(_, d)| d.info.clone())
            .collect();

        // Retain only devices that are fresh.
        self.found_devices.retain(|_, d| is_fresh(d, &now));

        for d in stale_devices {
            self.callbacks.for_all_callbacks(|callback| {
                callback.on_device_cleared(d.clone());
            });

            self.bluetooth_admin.lock().unwrap().on_device_cleared(&d);
        }

        // If we have any fresh devices remaining, re-queue a freshness check.
        if self.found_devices.len() > 0 {
            let txl = self.tx.clone();

            self.freshness_check = Some(tokio::spawn(async move {
                time::sleep(FOUND_DEVICE_FRESHNESS).await;
                let _ = txl.send(Message::DeviceFreshnessCheck).await;
            }));
        }
    }

    /// Makes an LE_RAND call to the Bluetooth interface.
    pub fn le_rand(&mut self) -> bool {
        self.intf.lock().unwrap().le_rand() == BTM_SUCCESS
    }

    fn send_metrics_remote_device_info(device: &BluetoothDeviceContext) {
        if device.bond_state != BtBondState::Bonded && device.acl_state != BtAclState::Connected {
            return;
        }

        let addr = RawAddress::from_string(device.info.address.clone()).unwrap();
        let mut class_of_device = 0u32;
        let mut device_type = BtDeviceType::Unknown;
        let mut appearance = 0u16;
        let mut vpi =
            BtVendorProductInfo { vendor_id_src: 0, vendor_id: 0, product_id: 0, version: 0 };

        for prop in device.properties.values() {
            match prop {
                BluetoothProperty::TypeOfDevice(p) => device_type = p.clone(),
                BluetoothProperty::ClassOfDevice(p) => class_of_device = p.clone(),
                BluetoothProperty::Appearance(p) => appearance = p.clone(),
                BluetoothProperty::VendorProductInfo(p) => vpi = p.clone(),
                _ => (),
            }
        }

        metrics::device_info_report(
            addr,
            device_type,
            class_of_device,
            appearance,
            vpi.vendor_id,
            vpi.vendor_id_src,
            vpi.product_id,
            vpi.version,
        );
    }
}

#[btif_callbacks_dispatcher(dispatch_base_callbacks, BaseCallbacks)]
#[allow(unused_variables)]
pub(crate) trait BtifBluetoothCallbacks {
    #[btif_callback(AdapterState)]
    fn adapter_state_changed(&mut self, state: BtState) {}

    #[btif_callback(AdapterProperties)]
    fn adapter_properties_changed(
        &mut self,
        status: BtStatus,
        num_properties: i32,
        properties: Vec<BluetoothProperty>,
    ) {
    }

    #[btif_callback(DeviceFound)]
    fn device_found(&mut self, n: i32, properties: Vec<BluetoothProperty>) {}

    #[btif_callback(DiscoveryState)]
    fn discovery_state(&mut self, state: BtDiscoveryState) {}

    #[btif_callback(SspRequest)]
    fn ssp_request(
        &mut self,
        remote_addr: RawAddress,
        remote_name: String,
        cod: u32,
        variant: BtSspVariant,
        passkey: u32,
    ) {
    }

    #[btif_callback(BondState)]
    fn bond_state(
        &mut self,
        status: BtStatus,
        addr: RawAddress,
        bond_state: BtBondState,
        fail_reason: i32,
    ) {
    }

    #[btif_callback(RemoteDeviceProperties)]
    fn remote_device_properties_changed(
        &mut self,
        status: BtStatus,
        addr: RawAddress,
        num_properties: i32,
        properties: Vec<BluetoothProperty>,
    ) {
    }

    #[btif_callback(AclState)]
    fn acl_state(
        &mut self,
        status: BtStatus,
        addr: RawAddress,
        state: BtAclState,
        link_type: BtTransport,
        hci_reason: BtHciErrorCode,
        conn_direction: BtConnectionDirection,
    ) {
    }

    #[btif_callback(LeRandCallback)]
    fn le_rand_cb(&mut self, random: u64) {}
}

#[btif_callbacks_dispatcher(dispatch_hid_host_callbacks, HHCallbacks)]
pub(crate) trait BtifHHCallbacks {
    #[btif_callback(ConnectionState)]
    fn connection_state(&mut self, address: RawAddress, state: BthhConnectionState);

    #[btif_callback(HidInfo)]
    fn hid_info(&mut self, address: RawAddress, info: BthhHidInfo);

    #[btif_callback(ProtocolMode)]
    fn protocol_mode(&mut self, address: RawAddress, status: BthhStatus, mode: BthhProtocolMode);

    #[btif_callback(IdleTime)]
    fn idle_time(&mut self, address: RawAddress, status: BthhStatus, idle_rate: i32);

    #[btif_callback(GetReport)]
    fn get_report(&mut self, address: RawAddress, status: BthhStatus, data: Vec<u8>, size: i32);

    #[btif_callback(Handshake)]
    fn handshake(&mut self, address: RawAddress, status: BthhStatus);
}

#[btif_callbacks_dispatcher(dispatch_sdp_callbacks, SdpCallbacks)]
pub(crate) trait BtifSdpCallbacks {
    #[btif_callback(SdpSearch)]
    fn sdp_search(
        &mut self,
        status: BtStatus,
        address: RawAddress,
        uuid: Uuid,
        count: i32,
        records: Vec<BtSdpRecord>,
    );
}

pub fn get_bt_dispatcher(tx: Sender<Message>) -> BaseCallbacksDispatcher {
    BaseCallbacksDispatcher {
        dispatch: Box::new(move |cb| {
            let txl = tx.clone();
            topstack::get_runtime().spawn(async move {
                let _ = txl.send(Message::Base(cb)).await;
            });
        }),
    }
}

impl BtifBluetoothCallbacks for Bluetooth {
    fn adapter_state_changed(&mut self, state: BtState) {
        let prev_state = self.state.clone();
        self.state = state;
        metrics::adapter_state_changed(self.state.clone());

        // If it's the same state as before, no further action
        if self.state == prev_state {
            return;
        }

        if self.state == BtState::On {
            self.bluetooth_media.lock().unwrap().initialize();
        }

        if self.state == BtState::Off {
            self.properties.clear();

            // Let the signal notifier know we are turned off.
            *self.sig_notifier.0.lock().unwrap() = false;
            self.sig_notifier.1.notify_all();
        } else {
            // Trigger properties update
            self.intf.lock().unwrap().get_adapter_properties();

            // Also need to manually request some properties
            self.intf.lock().unwrap().get_adapter_property(BtPropertyType::ClassOfDevice);

            // Ensure device is connectable so that disconnected device can reconnect
            self.set_connectable(true);

            // Notify the signal notifier that we are turned on.
            *self.sig_notifier.0.lock().unwrap() = true;
            self.sig_notifier.1.notify_all();
        }
    }

    #[allow(unused_variables)]
    fn adapter_properties_changed(
        &mut self,
        status: BtStatus,
        num_properties: i32,
        properties: Vec<BluetoothProperty>,
    ) {
        if status != BtStatus::Success {
            return;
        }

        // Update local property cache
        for prop in properties {
            match &prop {
                BluetoothProperty::BdAddr(bdaddr) => {
                    self.update_local_address(&bdaddr);
                }
                BluetoothProperty::AdapterBondedDevices(bondlist) => {
                    for addr in bondlist.iter() {
                        let address = addr.to_string();

                        // Update bonded state if already in the list. Otherwise create a new
                        // context with empty properties and name.
                        self.bonded_devices
                            .entry(address.clone())
                            .and_modify(|d| d.bond_state = BtBondState::Bonded)
                            .or_insert(BluetoothDeviceContext::new(
                                BtBondState::Bonded,
                                BtAclState::Disconnected,
                                BluetoothDevice::new(address.clone(), "".to_string()),
                                Instant::now(),
                                vec![],
                            ));
                    }
                }
                BluetoothProperty::BdName(bdname) => {
                    self.callbacks.for_all_callbacks(|callback| {
                        callback.on_name_changed(bdname.clone());
                    });
                }
                BluetoothProperty::AdapterScanMode(mode) => {
                    self.callbacks.for_all_callbacks(|callback| {
                        callback
                            .on_discoverable_changed(*mode == BtScanMode::ConnectableDiscoverable);
                    });
                }
                _ => {}
            }

            self.properties.insert(prop.get_type(), prop.clone());

            self.callbacks.for_all_callbacks(|callback| {
                callback.on_adapter_property_changed(prop.get_type());
            });
        }
    }

    fn device_found(&mut self, _n: i32, properties: Vec<BluetoothProperty>) {
        let device = BluetoothDevice::from_properties(&properties);
        let address = device.address.clone();

        if let Some(existing) = self.found_devices.get_mut(&address) {
            existing.update_properties(&properties);
            existing.seen();
        } else {
            let device_with_props = BluetoothDeviceContext::new(
                BtBondState::NotBonded,
                BtAclState::Disconnected,
                device,
                Instant::now(),
                properties,
            );
            self.found_devices.insert(address.clone(), device_with_props);
        }

        let device = self.found_devices.get(&address).unwrap();

        self.callbacks.for_all_callbacks(|callback| {
            callback.on_device_found(device.info.clone());
        });

        self.bluetooth_admin.lock().unwrap().on_device_found(&device.info);
    }

    fn discovery_state(&mut self, state: BtDiscoveryState) {
        let is_discovering = &state == &BtDiscoveryState::Started;

        // No-op if we're updating the state to the same value again.
        if &is_discovering == &self.is_discovering {
            return;
        }

        // Cache discovering state
        self.is_discovering = &state == &BtDiscoveryState::Started;
        if self.is_discovering {
            self.discovering_started = Instant::now();
        }

        self.callbacks.for_all_callbacks(|callback| {
            callback.on_discovering_changed(state == BtDiscoveryState::Started);
        });

        // Stopped discovering and no freshness check is active. Immediately do
        // freshness check which will schedule a recurring future until all
        // entries are cleared.
        if !is_discovering && self.freshness_check.is_none() {
            self.trigger_freshness_check();
        }
    }

    fn ssp_request(
        &mut self,
        remote_addr: RawAddress,
        remote_name: String,
        cod: u32,
        variant: BtSspVariant,
        passkey: u32,
    ) {
        // Currently this supports many agent because we accept many callbacks.
        // TODO: We need a way to select the default agent.
        self.callbacks.for_all_callbacks(|callback| {
            callback.on_ssp_request(
                BluetoothDevice::new(remote_addr.to_string(), remote_name.clone()),
                cod,
                variant.clone(),
                passkey,
            );
        });
    }

    fn bond_state(
        &mut self,
        status: BtStatus,
        addr: RawAddress,
        bond_state: BtBondState,
        fail_reason: i32,
    ) {
        let address = addr.to_string();

        // Get the device type before the device is potentially deleted.
        let device_type =
            self.get_remote_type(BluetoothDevice::new(address.clone(), "".to_string()));

        // Easy case of not bonded -- we remove the device from the bonded list and change the bond
        // state in the found list (in case it was previously bonding).
        if &bond_state == &BtBondState::NotBonded {
            self.bonded_devices.remove(&address);
            self.found_devices
                .entry(address.clone())
                .and_modify(|d| d.bond_state = bond_state.clone());
        }
        // We will only insert into the bonded list after bonding is complete
        else if &bond_state == &BtBondState::Bonded && !self.bonded_devices.contains_key(&address)
        {
            // We either need to construct a new BluetoothDeviceContext or grab it from the found
            // devices map
            let device = match self.found_devices.remove(&address) {
                Some(mut v) => {
                    v.bond_state = bond_state.clone();
                    v
                }
                None => BluetoothDeviceContext::new(
                    bond_state.clone(),
                    BtAclState::Disconnected,
                    BluetoothDevice::new(address.clone(), "".to_string()),
                    Instant::now(),
                    vec![],
                ),
            };

            self.bonded_devices.insert(address.clone(), device);
        } else {
            // If we're bonding, we need to update the found devices list
            self.found_devices
                .entry(address.clone())
                .and_modify(|d| d.bond_state = bond_state.clone());
        }

        // Send bond state changed notifications
        self.callbacks.for_all_callbacks(|callback| {
            callback.on_bond_state_changed(
                status.to_u32().unwrap(),
                address.clone(),
                bond_state.to_u32().unwrap(),
            );
        });

        metrics::bond_state_changed(addr, device_type, status, bond_state, fail_reason);
    }

    fn remote_device_properties_changed(
        &mut self,
        _status: BtStatus,
        addr: RawAddress,
        _num_properties: i32,
        properties: Vec<BluetoothProperty>,
    ) {
        let address = addr.to_string();
        let device = match self.get_remote_device_if_found_mut(&address) {
            None => {
                self.found_devices.insert(
                    address.clone(),
                    BluetoothDeviceContext::new(
                        BtBondState::NotBonded,
                        BtAclState::Disconnected,
                        BluetoothDevice::new(address.clone(), String::from("")),
                        Instant::now(),
                        vec![],
                    ),
                );

                self.found_devices.get_mut(&address)
            }
            some => some,
        };

        match device {
            Some(d) => {
                d.update_properties(&properties);
                d.seen();

                Bluetooth::send_metrics_remote_device_info(d);

                let info = d.info.clone();
                let uuids = self.get_remote_uuids(info.clone());
                if self.wait_to_connect && uuids.len() > 0 {
                    self.connect_all_enabled_profiles(info.clone());
                }

                self.bluetooth_admin
                    .lock()
                    .unwrap()
                    .on_remote_device_properties_changed(&info, &properties);
            }
            None => (),
        };
    }

    fn acl_state(
        &mut self,
        status: BtStatus,
        addr: RawAddress,
        state: BtAclState,
        link_type: BtTransport,
        hci_reason: BtHciErrorCode,
        conn_direction: BtConnectionDirection,
    ) {
        if status != BtStatus::Success {
            warn!(
                "Connection to [{}] failed. Status: {:?}, Reason: {:?}",
                addr.to_string(),
                status,
                hci_reason
            );
            metrics::acl_connection_state_changed(
                addr,
                link_type,
                status,
                BtAclState::Disconnected,
                conn_direction,
                hci_reason,
            );
            return;
        }

        let address = addr.to_string();
        let device = match self.get_remote_device_if_found_mut(&address) {
            None => {
                self.found_devices.insert(
                    address.clone(),
                    BluetoothDeviceContext::new(
                        BtBondState::NotBonded,
                        BtAclState::Disconnected,
                        BluetoothDevice::new(address.clone(), String::from("")),
                        Instant::now(),
                        vec![],
                    ),
                );

                self.found_devices.get_mut(&address)
            }
            some => some,
        };

        match device {
            Some(found) => {
                // Only notify if there's been a change in state
                let prev_state = &found.acl_state;
                if prev_state != &state {
                    let device = found.info.clone();
                    found.acl_state = state.clone();

                    metrics::acl_connection_state_changed(
                        addr,
                        link_type,
                        BtStatus::Success,
                        state.clone(),
                        conn_direction,
                        hci_reason,
                    );

                    match state {
                        BtAclState::Connected => {
                            Bluetooth::send_metrics_remote_device_info(found);
                            self.connection_callbacks.for_all_callbacks(|callback| {
                                callback.on_device_connected(device.clone());
                            });
                            let tx = self.tx.clone();
                            tokio::spawn(async move {
                                let _ = tx.send(Message::OnDeviceConnected(device.clone())).await;
                            });
                        }
                        BtAclState::Disconnected => {
                            self.connection_callbacks.for_all_callbacks(|callback| {
                                callback.on_device_disconnected(device.clone());
                            });
                        }
                    };
                }
            }
            None => (),
        };
    }
}

// TODO: Add unit tests for this implementation
impl IBluetooth for Bluetooth {
    fn register_callback(&mut self, callback: Box<dyn IBluetoothCallback + Send>) {
        self.callbacks.add_callback(callback);
    }

    fn register_connection_callback(
        &mut self,
        callback: Box<dyn IBluetoothConnectionCallback + Send>,
    ) -> u32 {
        self.connection_callbacks.add_callback(callback)
    }

    fn unregister_connection_callback(&mut self, callback_id: u32) -> bool {
        self.connection_callbacks.remove_callback(callback_id)
    }

    fn enable(&mut self) -> bool {
        self.intf.lock().unwrap().enable() == 0
    }

    fn disable(&mut self) -> bool {
        self.intf.lock().unwrap().disable() == 0
    }

    fn get_address(&self) -> String {
        match self.local_address {
            None => String::from(""),
            Some(addr) => addr.to_string(),
        }
    }

    fn get_uuids(&self) -> Vec<Uuid128Bit> {
        match self.properties.get(&BtPropertyType::Uuids) {
            Some(prop) => match prop {
                BluetoothProperty::Uuids(uuids) => {
                    uuids.iter().map(|&x| x.uu.clone()).collect::<Vec<Uuid128Bit>>()
                }
                _ => vec![],
            },
            _ => vec![],
        }
    }

    fn get_name(&self) -> String {
        match self.properties.get(&BtPropertyType::BdName) {
            Some(prop) => match prop {
                BluetoothProperty::BdName(name) => name.clone(),
                _ => String::new(),
            },
            _ => String::new(),
        }
    }

    fn set_name(&self, name: String) -> bool {
        self.intf.lock().unwrap().set_adapter_property(BluetoothProperty::BdName(name)) == 0
    }

    fn get_bluetooth_class(&self) -> u32 {
        match self.properties.get(&BtPropertyType::ClassOfDevice) {
            Some(prop) => match prop {
                BluetoothProperty::ClassOfDevice(cod) => cod.clone(),
                _ => 0,
            },
            _ => 0,
        }
    }

    fn set_bluetooth_class(&self, cod: u32) -> bool {
        self.intf.lock().unwrap().set_adapter_property(BluetoothProperty::ClassOfDevice(cod)) == 0
    }

    fn get_discoverable(&self) -> bool {
        match self.properties.get(&BtPropertyType::AdapterScanMode) {
            Some(prop) => match prop {
                BluetoothProperty::AdapterScanMode(mode) => match mode {
                    BtScanMode::ConnectableDiscoverable => true,
                    _ => false,
                },
                _ => false,
            },
            _ => false,
        }
    }

    fn get_discoverable_timeout(&self) -> u32 {
        match self.properties.get(&BtPropertyType::AdapterDiscoverableTimeout) {
            Some(prop) => match prop {
                BluetoothProperty::AdapterDiscoverableTimeout(timeout) => timeout.clone(),
                _ => 0,
            },
            _ => 0,
        }
    }

    fn set_discoverable(&mut self, mode: bool, duration: u32) -> bool {
        let intf = self.intf.lock().unwrap();

        // The old timer should be overwritten regardless of what the new mode is.
        if let Some(ref handle) = self.discoverable_timeout {
            handle.abort();
            self.discoverable_timeout = None;
        }

        let off_mode =
            if self.is_connectable { BtScanMode::Connectable } else { BtScanMode::None_ };
        let new_mode = if mode { BtScanMode::ConnectableDiscoverable } else { off_mode.clone() };

        if intf.set_adapter_property(BluetoothProperty::AdapterDiscoverableTimeout(duration)) != 0
            || intf.set_adapter_property(BluetoothProperty::AdapterScanMode(new_mode)) != 0
        {
            return false;
        }

        if mode && duration != 0 {
            let intf_clone = self.intf.clone();
            self.discoverable_timeout = Some(tokio::spawn(async move {
                time::sleep(Duration::from_secs(duration.into())).await;
                intf_clone
                    .lock()
                    .unwrap()
                    .set_adapter_property(BluetoothProperty::AdapterScanMode(off_mode));
            }));
        }

        true
    }

    fn is_multi_advertisement_supported(&self) -> bool {
        match self.properties.get(&BtPropertyType::LocalLeFeatures) {
            Some(prop) => match prop {
                BluetoothProperty::LocalLeFeatures(llf) => {
                    llf.max_adv_instance >= MIN_ADV_INSTANCES_FOR_MULTI_ADV
                }
                _ => false,
            },
            _ => false,
        }
    }

    fn is_le_extended_advertising_supported(&self) -> bool {
        match self.properties.get(&BtPropertyType::LocalLeFeatures) {
            Some(prop) => match prop {
                BluetoothProperty::LocalLeFeatures(llf) => llf.le_extended_advertising_supported,
                _ => false,
            },
            _ => false,
        }
    }

    fn start_discovery(&self) -> bool {
        self.intf.lock().unwrap().start_discovery() == 0
    }

    fn cancel_discovery(&self) -> bool {
        // Reject the cancel discovery request if the underlying stack is not in a discovering
        // state. For example, previous start discovery was enqueued for ongoing discovery.
        if !self.is_discovering {
            debug!("Reject cancel_discovery as it's not in discovering state.");
            return false;
        }

        self.intf.lock().unwrap().cancel_discovery() == 0
    }

    fn is_discovering(&self) -> bool {
        self.is_discovering
    }

    fn get_discovery_end_millis(&self) -> u64 {
        if !self.is_discovering {
            return 0;
        }

        let elapsed_ms = self.discovering_started.elapsed().as_millis() as u64;
        if elapsed_ms >= DEFAULT_DISCOVERY_TIMEOUT_MS {
            0
        } else {
            DEFAULT_DISCOVERY_TIMEOUT_MS - elapsed_ms
        }
    }

    fn create_bond(&self, device: BluetoothDevice, transport: BtTransport) -> bool {
        let addr = RawAddress::from_string(device.address.clone());

        if addr.is_none() {
            metrics::bond_create_attempt(RawAddress::default(), BtDeviceType::Unknown);
            metrics::bond_state_changed(
                RawAddress::default(),
                BtDeviceType::Unknown,
                BtStatus::InvalidParam,
                BtBondState::NotBonded,
                0,
            );
            warn!("Can't create bond. Address {} is not valid", device.address);
            return false;
        }

        let address = addr.unwrap();
        let device_type = match transport {
            BtTransport::Bredr => BtDeviceType::Bredr,
            BtTransport::Le => BtDeviceType::Ble,
            _ => self.get_remote_type(device.clone()),
        };

        // We explicitly log the attempt to start the bonding separate from logging the bond state.
        // The start of the attempt is critical to help identify a bonding/pairing session.
        metrics::bond_create_attempt(address, device_type.clone());

        // BREDR connection won't work when Inquiry is in progress.
        self.cancel_discovery();

        let status = self.intf.lock().unwrap().create_bond(&address, transport);

        if status != 0 {
            metrics::bond_state_changed(
                address,
                device_type,
                BtStatus::from(status as u32),
                BtBondState::NotBonded,
                0,
            );
            return false;
        }

        // Creating bond automatically create ACL connection as well, therefore also log metrics
        // ACL connection attempt here.
        let is_connected = self
            .get_remote_device_if_found(&device.address)
            .map_or(false, |d| d.acl_state == BtAclState::Connected);
        if !is_connected {
            metrics::acl_connect_attempt(address, BtAclState::Connected);
        }

        return true;
    }

    fn cancel_bond_process(&self, device: BluetoothDevice) -> bool {
        let addr = RawAddress::from_string(device.address.clone());

        if addr.is_none() {
            warn!("Can't cancel bond. Address {} is not valid.", device.address);
            return false;
        }

        let address = addr.unwrap();
        self.intf.lock().unwrap().cancel_bond(&address) == 0
    }

    fn remove_bond(&self, device: BluetoothDevice) -> bool {
        let addr = RawAddress::from_string(device.address.clone());

        if addr.is_none() {
            warn!("Can't remove bond. Address {} is not valid.", device.address);
            return false;
        }

        let address = addr.unwrap();
        let status = self.intf.lock().unwrap().remove_bond(&address);

        if status != 0 {
            return false;
        }

        // Removing bond also disconnects the ACL if is connected. Therefore, also log ACL
        // disconnection attempt here.
        let is_connected = self
            .get_remote_device_if_found(&device.address)
            .map_or(false, |d| d.acl_state == BtAclState::Connected);
        if is_connected {
            metrics::acl_connect_attempt(address, BtAclState::Disconnected);
        }

        return true;
    }

    fn get_bonded_devices(&self) -> Vec<BluetoothDevice> {
        let mut devices: Vec<BluetoothDevice> = vec![];

        for (_, device) in self.bonded_devices.iter() {
            devices.push(device.info.clone());
        }

        devices
    }

    fn get_bond_state(&self, device: BluetoothDevice) -> BtBondState {
        match self.bonded_devices.get(&device.address) {
            Some(device) => device.bond_state.clone(),
            None => BtBondState::NotBonded,
        }
    }

    fn set_pin(&self, device: BluetoothDevice, accept: bool, pin_code: Vec<u8>) -> bool {
        let addr = RawAddress::from_string(device.address.clone());

        if addr.is_none() {
            warn!("Can't set pin. Address {} is not valid.", device.address);
            return false;
        }

        let is_bonding = match self.found_devices.get(&device.address) {
            Some(d) => d.bond_state == BtBondState::Bonding,
            None => false,
        };

        if !is_bonding {
            warn!("Can't set pin. Device {} isn't bonding.", device.address);
            return false;
        }

        let mut btpin: BtPinCode = BtPinCode { pin: [0; 16] };
        btpin.pin.copy_from_slice(pin_code.as_slice());

        self.intf.lock().unwrap().pin_reply(
            &addr.unwrap(),
            accept as u8,
            pin_code.len() as u8,
            &mut btpin,
        ) == 0
    }

    fn set_passkey(&self, device: BluetoothDevice, accept: bool, passkey: Vec<u8>) -> bool {
        let addr = RawAddress::from_string(device.address.clone());

        if addr.is_none() {
            warn!("Can't set passkey. Address {} is not valid.", device.address);
            return false;
        }

        let is_bonding = match self.found_devices.get(&device.address) {
            Some(d) => d.bond_state == BtBondState::Bonding,
            None => false,
        };

        if !is_bonding {
            warn!("Can't set passkey. Device {} isn't bonding.", device.address);
            return false;
        }

        let mut tmp: [u8; 4] = [0; 4];
        tmp.copy_from_slice(passkey.as_slice());
        let passkey = u32::from_ne_bytes(tmp);

        self.intf.lock().unwrap().ssp_reply(
            &addr.unwrap(),
            BtSspVariant::PasskeyEntry,
            accept as u8,
            passkey,
        ) == 0
    }

    fn set_pairing_confirmation(&self, device: BluetoothDevice, accept: bool) -> bool {
        let addr = RawAddress::from_string(device.address.clone());

        if addr.is_none() {
            warn!("Can't set pairing confirmation. Address {} is not valid.", device.address);
            return false;
        }

        let is_bonding = match self.found_devices.get(&device.address) {
            Some(d) => d.bond_state == BtBondState::Bonding,
            None => false,
        };

        if !is_bonding {
            warn!("Can't set pairing confirmation. Device {} isn't bonding.", device.address);
            return false;
        }

        self.intf.lock().unwrap().ssp_reply(
            &addr.unwrap(),
            BtSspVariant::PasskeyConfirmation,
            accept as u8,
            0,
        ) == 0
    }

    fn get_remote_name(&self, device: BluetoothDevice) -> String {
        match self.get_remote_device_property(&device, &BtPropertyType::BdName) {
            Some(BluetoothProperty::BdName(name)) => return name.clone(),
            _ => return "".to_string(),
        }
    }

    fn get_remote_type(&self, device: BluetoothDevice) -> BtDeviceType {
        match self.get_remote_device_property(&device, &BtPropertyType::TypeOfDevice) {
            Some(BluetoothProperty::TypeOfDevice(device_type)) => return device_type,
            _ => return BtDeviceType::Unknown,
        }
    }

    fn get_remote_alias(&self, device: BluetoothDevice) -> String {
        match self.get_remote_device_property(&device, &BtPropertyType::RemoteFriendlyName) {
            Some(BluetoothProperty::RemoteFriendlyName(name)) => return name.clone(),
            _ => "".to_string(),
        }
    }

    fn set_remote_alias(&mut self, device: BluetoothDevice, new_alias: String) {
        let _ = self.set_remote_device_property(
            &device,
            BtPropertyType::RemoteFriendlyName,
            BluetoothProperty::RemoteFriendlyName(new_alias),
        );
    }

    fn get_remote_class(&self, device: BluetoothDevice) -> u32 {
        match self.get_remote_device_property(&device, &BtPropertyType::ClassOfDevice) {
            Some(BluetoothProperty::ClassOfDevice(class)) => return class,
            _ => 0,
        }
    }

    fn get_remote_appearance(&self, device: BluetoothDevice) -> u16 {
        match self.get_remote_device_property(&device, &BtPropertyType::Appearance) {
            Some(BluetoothProperty::Appearance(appearance)) => appearance,
            _ => 0,
        }
    }

    fn get_remote_connected(&self, device: BluetoothDevice) -> bool {
        self.get_connection_state(device) != BtConnectionState::NotConnected
    }

    fn get_remote_wake_allowed(&self, device: BluetoothDevice) -> bool {
        // Wake is allowed if the device supports HIDP or HOGP only.
        match self.get_remote_device_property(&device, &BtPropertyType::Uuids) {
            Some(BluetoothProperty::Uuids(uuids)) => {
                return uuids.iter().any(|&x| {
                    UuidHelper::is_known_profile(&x.uu).map_or(false, |profile| {
                        profile == Profile::Hid || profile == Profile::Hogp
                    })
                });
            }
            _ => false,
        }
    }

    fn get_connected_devices(&self) -> Vec<BluetoothDevice> {
        let bonded_connected: HashMap<String, BluetoothDevice> = self
            .bonded_devices
            .iter()
            .filter(|(_, v)| v.acl_state == BtAclState::Connected)
            .map(|(k, v)| (k.clone(), v.info.clone()))
            .collect();
        let mut found_connected: Vec<BluetoothDevice> = self
            .found_devices
            .iter()
            .filter(|(k, v)| {
                v.acl_state == BtAclState::Connected
                    && !bonded_connected.contains_key(&k.to_string())
            })
            .map(|(_, v)| v.info.clone())
            .collect();

        let mut all =
            bonded_connected.iter().map(|(_, v)| v.clone()).collect::<Vec<BluetoothDevice>>();
        all.append(&mut found_connected);

        all
    }

    fn get_connection_state(&self, device: BluetoothDevice) -> BtConnectionState {
        let addr = RawAddress::from_string(device.address.clone());

        if addr.is_none() {
            warn!("Can't check connection state. Address {} is not valid.", device.address);
            return BtConnectionState::NotConnected;
        }

        // The underlying api adds whether this is ENCRYPTED_BREDR or ENCRYPTED_LE.
        // As long as it is non-zero, it is connected.
        self.intf.lock().unwrap().get_connection_state(&addr.unwrap())
    }

    fn get_profile_connection_state(&self, profile: Profile) -> u32 {
        match profile {
            Profile::A2dpSink | Profile::A2dpSource => {
                self.bluetooth_media.lock().unwrap().get_a2dp_connection_state()
            }
            Profile::Hfp | Profile::HfpAg => {
                self.bluetooth_media.lock().unwrap().get_hfp_connection_state()
            }
            // TODO: (b/223431229) Profile::Hid and Profile::Hogp
            _ => 0,
        }
    }

    fn get_remote_uuids(&self, device: BluetoothDevice) -> Vec<Uuid128Bit> {
        match self.get_remote_device_property(&device, &BtPropertyType::Uuids) {
            Some(BluetoothProperty::Uuids(uuids)) => {
                return uuids.iter().map(|&x| x.uu.clone()).collect::<Vec<Uuid128Bit>>()
            }
            _ => return vec![],
        }
    }

    fn fetch_remote_uuids(&self, device: BluetoothDevice) -> bool {
        if self.get_remote_device_if_found(&device.address).is_none() {
            warn!("Won't fetch UUIDs on unknown device {}", device.address);
            return false;
        }

        let addr = RawAddress::from_string(device.address.clone());
        if addr.is_none() {
            warn!("Can't fetch UUIDs. Address {} is not valid.", device.address);
            return false;
        }
        self.intf.lock().unwrap().get_remote_services(&mut addr.unwrap(), BtTransport::Auto) == 0
    }

    fn sdp_search(&self, device: BluetoothDevice, uuid: Uuid128Bit) -> bool {
        if self.sdp.is_none() {
            warn!("SDP is not initialized. Can't do SDP search.");
            return false;
        }

        let addr = RawAddress::from_string(device.address.clone());
        if addr.is_none() {
            warn!("Can't SDP search. Address {} is not valid.", device.address);
            return false;
        }

        let uu = Uuid::from(uuid);
        self.sdp.as_ref().unwrap().sdp_search(&mut addr.unwrap(), &uu) == BtStatus::Success
    }

    fn connect_all_enabled_profiles(&mut self, device: BluetoothDevice) -> bool {
        // Profile init must be complete before this api is callable
        if !self.profiles_ready {
            return false;
        }

        let addr = RawAddress::from_string(device.address.clone());
        if addr.is_none() {
            warn!("Can't connect profiles on invalid address [{}]", &device.address);
            return false;
        }

        // log ACL connection attempt if it's not already connected.
        let is_connected = self
            .get_remote_device_if_found(&device.address)
            .map_or(false, |d| d.acl_state == BtAclState::Connected);
        if !is_connected {
            metrics::acl_connect_attempt(addr.unwrap(), BtAclState::Connected);
        }

        // Check all remote uuids to see if they match enabled profiles and connect them.
        let mut has_enabled_uuids = false;
        let uuids = self.get_remote_uuids(device.clone());
        for uuid in uuids.iter() {
            match UuidHelper::is_known_profile(uuid) {
                Some(p) => {
                    if UuidHelper::is_profile_supported(&p) {
                        match p {
                            Profile::Hid | Profile::Hogp => {
                                let status = self.hh.as_ref().unwrap().connect(&mut addr.unwrap());
                                metrics::profile_connection_state_changed(
                                    addr.unwrap(),
                                    p as u32,
                                    BtStatus::Success,
                                    BthhConnectionState::Connecting as u32,
                                );

                                if status != BtStatus::Success {
                                    metrics::profile_connection_state_changed(
                                        addr.unwrap(),
                                        p as u32,
                                        status,
                                        BthhConnectionState::Disconnected as u32,
                                    );
                                }
                            }

                            Profile::A2dpSink
                            | Profile::A2dpSource
                            | Profile::Hfp
                            | Profile::AvrcpController => {
                                let txl = self.tx.clone();
                                let address = device.address.clone();
                                topstack::get_runtime().spawn(async move {
                                    let _ = txl
                                        .send(Message::Media(MediaActions::Connect(address)))
                                        .await;
                                });
                            }
                            // We don't connect most profiles
                            _ => (),
                        }
                    }
                    has_enabled_uuids = true;
                }
                _ => {}
            }
        }

        // If SDP isn't completed yet, we wait for it to complete and retry the connection again.
        // Otherwise, this connection request is done, no retry is required.
        self.wait_to_connect = !has_enabled_uuids;
        if self.wait_to_connect {
            warn!(
                "[{}] SDP hasn't completed for device, wait to connect.",
                addr.unwrap().to_string()
            );
        }

        return true;
    }

    fn disconnect_all_enabled_profiles(&mut self, device: BluetoothDevice) -> bool {
        // No need to retry connection as we are going to disconnect all enabled profiles.
        self.wait_to_connect = false;

        if !self.profiles_ready {
            return false;
        }

        let addr = RawAddress::from_string(device.address.clone());
        if addr.is_none() {
            warn!("Can't connect profiles on invalid address [{}]", &device.address);
            return false;
        }

        // log ACL disconnection attempt if it's not already disconnected.
        let is_connected = self
            .get_remote_device_if_found(&device.address)
            .map_or(false, |d| d.acl_state == BtAclState::Connected);
        if is_connected {
            metrics::acl_connect_attempt(addr.unwrap(), BtAclState::Disconnected);
        }

        let uuids = self.get_remote_uuids(device.clone());
        for uuid in uuids.iter() {
            match UuidHelper::is_known_profile(uuid) {
                Some(p) => {
                    if UuidHelper::is_profile_supported(&p) {
                        match p {
                            Profile::Hid | Profile::Hogp => {
                                self.hh.as_ref().unwrap().disconnect(&mut addr.unwrap());
                            }

                            Profile::A2dpSink
                            | Profile::A2dpSource
                            | Profile::Hfp
                            | Profile::AvrcpController => {
                                let txl = self.tx.clone();
                                let address = device.address.clone();
                                topstack::get_runtime().spawn(async move {
                                    let _ = txl
                                        .send(Message::Media(MediaActions::Disconnect(address)))
                                        .await;
                                });
                            }

                            // We don't connect most profiles
                            _ => (),
                        }
                    }
                }
                _ => {}
            }
        }

        return true;
    }

    fn is_wbs_supported(&self) -> bool {
        self.intf.lock().unwrap().get_wbs_supported()
    }
}

impl BtifSdpCallbacks for Bluetooth {
    fn sdp_search(
        &mut self,
        status: BtStatus,
        address: RawAddress,
        uuid: Uuid,
        _count: i32,
        _records: Vec<BtSdpRecord>,
    ) {
        debug!(
            "Sdp search result found: Status({:?}) Address({:?}) Uuid({:?})",
            status, address, uuid
        );
    }
}

impl BtifHHCallbacks for Bluetooth {
    fn connection_state(&mut self, address: RawAddress, state: BthhConnectionState) {
        debug!("Hid host connection state updated: Address({:?}) State({:?})", address, state);

        // HID or HOG is not differentiated by the hid host when callback this function. Assume HOG
        // if the device is LE only and HID if classic only. And assume HOG if UUID said so when
        // device type is dual or unknown.
        let device = BluetoothDevice::new(address.to_string(), "".to_string());
        let profile = match self.get_remote_type(device.clone()) {
            BtDeviceType::Ble => Profile::Hogp,
            BtDeviceType::Bredr => Profile::Hid,
            _ => {
                if self.get_remote_uuids(device).contains(&UuidHelper::from_string(HOGP).unwrap()) {
                    Profile::Hogp
                } else {
                    Profile::Hid
                }
            }
        };

        metrics::profile_connection_state_changed(
            address,
            profile as u32,
            BtStatus::Success,
            state as u32,
        );
    }

    fn hid_info(&mut self, address: RawAddress, info: BthhHidInfo) {
        debug!("Hid host info updated: Address({:?}) Info({:?})", address, info);
    }

    fn protocol_mode(&mut self, address: RawAddress, status: BthhStatus, mode: BthhProtocolMode) {
        debug!(
            "Hid host protocol mode updated: Address({:?}) Status({:?}) Mode({:?})",
            address, status, mode
        );
    }

    fn idle_time(&mut self, address: RawAddress, status: BthhStatus, idle_rate: i32) {
        debug!(
            "Hid host idle time updated: Address({:?}) Status({:?}) Idle Rate({:?})",
            address, status, idle_rate
        );
    }

    fn get_report(
        &mut self,
        mut address: RawAddress,
        status: BthhStatus,
        mut data: Vec<u8>,
        size: i32,
    ) {
        debug!(
            "Hid host got report: Address({:?}) Status({:?}) Report Size({:?})",
            address, status, size
        );
        self.hh.as_ref().unwrap().get_report_reply(&mut address, status, &mut data, size as u16);
    }

    fn handshake(&mut self, address: RawAddress, status: BthhStatus) {
        debug!("Hid host handshake: Address({:?}) Status({:?})", address, status);
    }
}

impl IBluetoothQA for Bluetooth {
    fn get_connectable(&self) -> bool {
        match self.properties.get(&BtPropertyType::AdapterScanMode) {
            Some(prop) => match prop {
                BluetoothProperty::AdapterScanMode(mode) => match *mode {
                    BtScanMode::Connectable | BtScanMode::ConnectableDiscoverable => true,
                    _ => false,
                },
                _ => false,
            },
            _ => false,
        }
    }

    fn set_connectable(&mut self, mode: bool) -> bool {
        self.is_connectable = mode;
        if mode && self.get_discoverable() {
            return true;
        }
        self.intf.lock().unwrap().set_adapter_property(BluetoothProperty::AdapterScanMode(
            if mode { BtScanMode::Connectable } else { BtScanMode::None_ },
        )) == 0
    }
}
