use btstack::suspend::{ISuspend, ISuspendCallback, SuspendType};
use btstack::RPCProxy;
use dbus::channel::MatchingReceiver;
use dbus::message::MatchRule;
use dbus::nonblock::SyncConnection;
use dbus_crossroads::Crossroads;
use dbus_projection::DisconnectWatcher;
use protobuf::{CodedInputStream, CodedOutputStream, Message};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::dbus_iface::{export_suspend_callback_dbus_intf, SuspendDBus};
use crate::service_watcher::ServiceWatcher;
use crate::suspend::{
    RegisterSuspendDelayReply, RegisterSuspendDelayRequest, SuspendDone, SuspendImminent,
    SuspendImminent_Reason, SuspendReadinessInfo,
};

const POWERD_SERVICE: &str = "org.chromium.PowerManager";
const POWERD_INTERFACE: &str = "org.chromium.PowerManager";
const POWERD_PATH: &str = "/org/chromium/PowerManager";
const ADAPTER_SERVICE: &str = "org.chromium.bluetooth";
const ADAPTER_SUSPEND_INTERFACE: &str = "org.chromium.bluetooth.Suspend";
const SUSPEND_IMMINENT_SIGNAL: &str = "SuspendImminent";
const SUSPEND_DONE_SIGNAL: &str = "SuspendDone";
const BTMANAGERD_NAME: &str = "Bluetooth Manager";
const DBUS_TIMEOUT: Duration = Duration::from_secs(2);
const BLUEZ_SERVICE: &str = "org.bluez";

#[derive(Debug)]
enum SuspendManagerMessage {
    PowerdStarted,
    PowerdStopped,
    SuspendImminentReceived(SuspendImminent),
    SuspendDoneReceived(SuspendDone),
    AdapterFound(dbus::Path<'static>),
    AdapterRemoved,
}

struct PowerdSession {
    delay_id: i32,
    powerd_proxy: dbus::nonblock::Proxy<'static, Arc<SyncConnection>>,
}

/// Callback container for suspend interface callbacks.
pub(crate) struct SuspendCallback {
    objpath: String,

    dbus_connection: Arc<SyncConnection>,
    dbus_crossroads: Arc<Mutex<Crossroads>>,

    context: Arc<Mutex<SuspendManagerContext>>,
}

impl SuspendCallback {
    pub(crate) fn new(
        objpath: String,
        dbus_connection: Arc<SyncConnection>,
        dbus_crossroads: Arc<Mutex<Crossroads>>,
        context: Arc<Mutex<SuspendManagerContext>>,
    ) -> Self {
        Self { objpath, dbus_connection, dbus_crossroads, context }
    }
}

fn generate_proto_bytes<T: protobuf::Message>(request: &T) -> Option<Vec<u8>> {
    let mut proto_bytes: Vec<u8> = vec![];
    let mut output_stream = CodedOutputStream::vec(&mut proto_bytes);
    let write_result = request.write_to_with_cached_sizes(&mut output_stream);
    if let Err(e) = write_result {
        log::error!("Error serializing proto to bytes: {}", e);
        return None;
    }
    Some(proto_bytes)
}

// Convenient function to call HandleSuspendReadiness to powerd when we want to tell it that
// Bluetooth is ready to suspend.
fn send_handle_suspend_readiness(
    powerd_proxy: dbus::nonblock::Proxy<'static, Arc<SyncConnection>>,
    delay_id: i32,
    suspend_id: i32,
) {
    let mut suspend_readiness_info = SuspendReadinessInfo::new();
    suspend_readiness_info.set_delay_id(delay_id);
    suspend_readiness_info.set_suspend_id(suspend_id);

    if let Some(suspend_readiness_info_proto) = generate_proto_bytes(&suspend_readiness_info) {
        tokio::spawn(async move {
            log::debug!(
                "Sending HandleSuspendReadiness, delay id = {}, suspend id = {}",
                suspend_readiness_info.get_delay_id(),
                suspend_readiness_info.get_suspend_id()
            );
            let ret: Result<(), dbus::Error> = powerd_proxy
                .method_call(
                    POWERD_INTERFACE,
                    "HandleSuspendReadiness",
                    (suspend_readiness_info_proto,),
                )
                .await;

            log::debug!("HandleSuspendReadiness returns {:?}", ret);
            if let Err(e) = ret {
                log::error!("Error calling HandleSuspendReadiness: {}", e)
            }
        });
    } else {
        log::error!("Error writing SuspendReadinessInfo");
    }
}

impl ISuspendCallback for SuspendCallback {
    fn on_callback_registered(&self, callback_id: u32) {
        log::debug!("Suspend callback registered, callback_id = {}", callback_id);
    }

    fn on_suspend_ready(&self, suspend_id: u32) {
        // Received when adapter is ready to suspend. Tell powerd that suspend is ready.
        log::debug!("Suspend ready, adapter suspend_id = {}", suspend_id);

        {
            let context = self.context.lock().unwrap();

            if let (Some(pending_suspend_imminent), Some(powerd_session)) =
                (&context.pending_suspend_imminent, &context.powerd_session)
            {
                send_handle_suspend_readiness(
                    powerd_session.powerd_proxy.clone(),
                    powerd_session.delay_id,
                    pending_suspend_imminent.get_suspend_id(),
                );
            } else {
                log::warn!("Suspend ready but no SuspendImminent signal or powerd session");
            }
        }
    }

    fn on_resumed(&self, suspend_id: u32) {
        // Received when adapter is ready to suspend. This is just for our information and powerd
        // doesn't need to know about this.
        log::debug!("Suspend resumed, adapter suspend_id = {}", suspend_id);
    }
}

impl RPCProxy for SuspendCallback {
    fn register_disconnect(&mut self, _f: Box<dyn Fn(u32) + Send>) -> u32 {
        0
    }

    fn get_object_id(&self) -> String {
        self.objpath.clone()
    }

    fn unregister(&mut self, _id: u32) -> bool {
        false
    }

    fn export_for_rpc(self: Box<Self>) {
        let cr = self.dbus_crossroads.clone();
        let iface = export_suspend_callback_dbus_intf(
            self.dbus_connection.clone(),
            &mut cr.lock().unwrap(),
            Arc::new(Mutex::new(DisconnectWatcher::new())),
        );
        cr.lock().unwrap().insert(self.get_object_id(), &[iface], Arc::new(Mutex::new(self)));
    }
}

/// Holds the necessary information to coordinate suspend between powerd and btadapterd.
pub struct SuspendManagerContext {
    dbus_crossroads: Arc<Mutex<Crossroads>>,
    powerd_session: Option<PowerdSession>,
    adapter_suspend_dbus: Option<SuspendDBus>,
    pending_suspend_imminent: Option<SuspendImminent>,
}

/// Coordinates suspend events of Chromium OS's powerd with btadapter Suspend API.
pub struct PowerdSuspendManager {
    context: Arc<Mutex<SuspendManagerContext>>,
    conn: Arc<SyncConnection>,
    tx: tokio::sync::mpsc::Sender<SuspendManagerMessage>,
    rx: tokio::sync::mpsc::Receiver<SuspendManagerMessage>,
}

impl PowerdSuspendManager {
    /// Instantiates the suspend manager.
    ///
    /// `conn` and `dbus_crossroads` are D-Bus objects from `dbus` crate, to be used for both
    /// communication with powerd and btadapterd.
    pub fn new(conn: Arc<SyncConnection>, dbus_crossroads: Arc<Mutex<Crossroads>>) -> Self {
        let (tx, rx) = tokio::sync::mpsc::channel::<SuspendManagerMessage>(10);
        Self {
            context: Arc::new(Mutex::new(SuspendManagerContext {
                dbus_crossroads,
                powerd_session: None,
                adapter_suspend_dbus: None,
                pending_suspend_imminent: None,
            })),
            conn,
            tx,
            rx,
        }
    }

    /// Sets up all required D-Bus listeners.
    pub async fn init(&mut self) {
        // Watch events of powerd appearing or disappearing.
        let powerd_watcher = ServiceWatcher::new(self.conn.clone(), String::from(POWERD_SERVICE));
        let tx1 = self.tx.clone();
        let tx2 = self.tx.clone();
        powerd_watcher
            .start_watch(
                Box::new(move || {
                    let tx_clone = tx1.clone();
                    tokio::spawn(async move {
                        let _ = tx_clone.send(SuspendManagerMessage::PowerdStarted).await;
                    });
                }),
                Box::new(move || {
                    let tx_clone = tx2.clone();
                    tokio::spawn(async move {
                        let _ = tx_clone.send(SuspendManagerMessage::PowerdStopped).await;
                    });
                }),
            )
            .await;

        // Watch events of btadapterd appearing or disappearing.
        let mut adapter_watcher =
            ServiceWatcher::new(self.conn.clone(), String::from(ADAPTER_SERVICE));
        let tx1 = self.tx.clone();
        let tx2 = self.tx.clone();
        adapter_watcher
            .start_watch_interface(
                String::from(ADAPTER_SUSPEND_INTERFACE),
                Box::new(move |path| {
                    let tx_clone = tx1.clone();
                    tokio::spawn(async move {
                        let _ = tx_clone.send(SuspendManagerMessage::AdapterFound(path)).await;
                    });
                }),
                Box::new(move || {
                    let tx_clone = tx2.clone();
                    tokio::spawn(async move {
                        let _ = tx_clone.send(SuspendManagerMessage::AdapterRemoved).await;
                    });
                }),
            )
            .await;

        // Watch events of bluez appearing or disappearing.
        // This is with the assumption that only one instance of btadapterd and bluez can be alive
        // at a time.
        let mut bluez_watcher = ServiceWatcher::new(self.conn.clone(), String::from(BLUEZ_SERVICE));
        let tx1 = self.tx.clone();
        let tx2 = self.tx.clone();
        bluez_watcher
            .start_watch_interface(
                String::from(ADAPTER_SUSPEND_INTERFACE),
                Box::new(move |path| {
                    let tx_clone = tx1.clone();
                    tokio::spawn(async move {
                        let _ = tx_clone.send(SuspendManagerMessage::AdapterFound(path)).await;
                    });
                }),
                Box::new(move || {
                    let tx_clone = tx2.clone();
                    tokio::spawn(async move {
                        let _ = tx_clone.send(SuspendManagerMessage::AdapterRemoved).await;
                    });
                }),
            )
            .await;

        // Watch for SuspendImminent signal from powerd.
        let mr = MatchRule::new_signal(POWERD_INTERFACE, SUSPEND_IMMINENT_SIGNAL)
            .with_sender(POWERD_SERVICE)
            .with_path(POWERD_PATH);
        self.conn.add_match_no_cb(&mr.match_str()).await.unwrap();

        let tx = self.tx.clone();
        self.conn.start_receive(
            mr,
            Box::new(move |msg, _conn| {
                if let Some(bytes) = msg.get1::<Vec<u8>>() {
                    let mut suspend_imminent = SuspendImminent::new();
                    let mut input_stream = CodedInputStream::from_bytes(&bytes[..]);
                    let decode_result = suspend_imminent.merge_from(&mut input_stream);
                    if let Err(e) = decode_result {
                        log::error!("Error decoding SuspendImminent signal: {}", e);
                    } else {
                        let tx_clone = tx.clone();
                        tokio::spawn(async move {
                            let _ = tx_clone
                                .send(SuspendManagerMessage::SuspendImminentReceived(
                                    suspend_imminent,
                                ))
                                .await;
                        });
                    }
                } else {
                    log::warn!("received empty SuspendImminent signal");
                }

                true
            }),
        );

        // Watch for SuspendDone signal from powerd.
        let mr = MatchRule::new_signal(POWERD_INTERFACE, SUSPEND_DONE_SIGNAL)
            .with_sender(POWERD_SERVICE)
            .with_path(POWERD_PATH);
        self.conn.add_match_no_cb(&mr.match_str()).await.unwrap();
        let tx = self.tx.clone();
        self.conn.start_receive(
            mr,
            Box::new(move |msg, _conn| {
                if let Some(bytes) = msg.get1::<Vec<u8>>() {
                    let mut suspend_done = SuspendDone::new();
                    let mut input_stream = CodedInputStream::from_bytes(&bytes[..]);
                    let decode_result = suspend_done.merge_from(&mut input_stream);
                    if let Err(e) = decode_result {
                        log::error!("Error decoding SuspendDone signal: {}", e);
                    } else {
                        let tx_clone = tx.clone();
                        tokio::spawn(async move {
                            let _ = tx_clone
                                .send(SuspendManagerMessage::SuspendDoneReceived(suspend_done))
                                .await;
                        });
                    }
                } else {
                    log::warn!("received empty SuspendDone signal");
                }

                true
            }),
        );
    }

    /// Starts the event handlers.
    pub async fn mainloop(&mut self) {
        loop {
            let m = self.rx.recv().await;

            if let Some(msg) = m {
                match msg {
                    SuspendManagerMessage::PowerdStarted => self.on_powerd_started().await,
                    SuspendManagerMessage::PowerdStopped => self.on_powerd_stopped(),
                    SuspendManagerMessage::SuspendImminentReceived(suspend_imminent) => {
                        self.on_suspend_imminent(suspend_imminent)
                    }
                    SuspendManagerMessage::SuspendDoneReceived(suspend_done) => {
                        self.on_suspend_done(suspend_done)
                    }
                    SuspendManagerMessage::AdapterFound(object_path) => {
                        self.on_adapter_found(object_path)
                    }
                    SuspendManagerMessage::AdapterRemoved => self.on_adapter_removed(),
                }
            } else {
                log::debug!("Exiting suspend manager mainloop");
                break;
            }
        }
    }

    async fn on_powerd_started(&mut self) {
        // As soon as powerd is available, we need to register to be a suspend readiness reporter.

        log::debug!("powerd started, initializing suspend manager");

        if self.context.lock().unwrap().powerd_session.is_some() {
            log::warn!("powerd session already exists, cleaning up first");
            self.on_powerd_stopped();
        }

        let conn = self.conn.clone();
        let powerd_proxy =
            dbus::nonblock::Proxy::new(POWERD_SERVICE, POWERD_PATH, DBUS_TIMEOUT, conn);

        let mut request = RegisterSuspendDelayRequest::new();
        request.set_description(String::from(BTMANAGERD_NAME));

        if let Some(register_suspend_delay_proto) = generate_proto_bytes(&request) {
            let result: Result<(Vec<u8>,), dbus::Error> = powerd_proxy
                .method_call(
                    POWERD_INTERFACE,
                    "RegisterSuspendDelay",
                    (register_suspend_delay_proto,),
                )
                .await;

            match result {
                Err(e) => {
                    log::error!("D-Bus error: {:?}", e);
                }
                Ok((return_proto,)) => {
                    let mut reply = RegisterSuspendDelayReply::new();
                    let mut input_stream = CodedInputStream::from_bytes(&return_proto[..]);
                    let decode_result = reply.merge_from(&mut input_stream);
                    if let Err(e) = decode_result {
                        log::error!("Error decoding RegisterSuspendDelayReply {:?}", e);
                    }

                    log::debug!("Suspend delay id = {}", reply.get_delay_id());

                    self.context.lock().unwrap().powerd_session =
                        Some(PowerdSession { delay_id: reply.get_delay_id(), powerd_proxy });
                }
            }
        } else {
            log::error!("Error writing RegisterSuspendDelayRequest");
        }
    }

    fn on_powerd_stopped(&mut self) {
        // TODO: Consider an edge case where powerd unexpectedly is stopped (maybe crashes) but we
        // still have pending SuspendImminent.
        log::debug!("powerd stopped, cleaning up");

        {
            let mut context = self.context.lock().unwrap();

            match context.powerd_session {
                None => log::warn!("powerd session does not exist, ignoring"),
                Some(_) => context.powerd_session = None,
            }
        }
    }

    fn on_suspend_imminent(&mut self, suspend_imminent: SuspendImminent) {
        // powerd is telling us that system is about to suspend, if available tell btadapterd to
        // prepare for suspend.

        log::debug!(
            "received suspend imminent: suspend_id = {:?}, reason = {:?}",
            suspend_imminent.get_suspend_id(),
            suspend_imminent.get_reason()
        );

        if self.context.lock().unwrap().pending_suspend_imminent.is_some() {
            log::warn!("SuspendImminent signal received while there is a pending suspend imminent");
        }

        self.context.lock().unwrap().pending_suspend_imminent = Some(suspend_imminent.clone());

        {
            // Anonymous block to contain locked `self.context` which needs to be called multiple
            // times in the `if let` block below. Prevent deadlock by locking only once.
            let context_locked = self.context.lock().unwrap();
            if let Some(adapter_suspend_dbus) = &context_locked.adapter_suspend_dbus {
                let adapter_suspend_id =
                    adapter_suspend_dbus.suspend(match suspend_imminent.get_reason() {
                        SuspendImminent_Reason::IDLE => SuspendType::Connected,
                        SuspendImminent_Reason::LID_CLOSED => SuspendType::Disconnected,
                        SuspendImminent_Reason::OTHER => SuspendType::Other,
                    });
                log::debug!("Adapter suspend id = {}", adapter_suspend_id);
            } else {
                // If there is no adapter, that means Bluetooth is not active and we should always
                // tell powerd that we are ready to suspend.
                log::debug!("Adapter not available, suspend is ready.");
                if let Some(session) = &context_locked.powerd_session {
                    send_handle_suspend_readiness(
                        session.powerd_proxy.clone(),
                        session.delay_id,
                        suspend_imminent.get_suspend_id(),
                    );
                } else {
                    log::warn!("SuspendImminent is received when there is no powerd session");
                }
            }
        }
    }

    fn on_suspend_done(&mut self, suspend_done: SuspendDone) {
        // powerd is telling us that suspend is done (system has resumed), so we tell btadapterd
        // to resume too.

        log::debug!("SuspendDone received: {:?}", suspend_done);

        if self.context.lock().unwrap().pending_suspend_imminent.is_none() {
            log::warn!("Receveid SuspendDone signal when there is no pending SuspendImminent");
        }

        self.context.lock().unwrap().pending_suspend_imminent = None;

        if let Some(adapter_suspend_dbus) = &self.context.lock().unwrap().adapter_suspend_dbus {
            let success = adapter_suspend_dbus.resume();
            log::debug!("Adapter resume is successful = {}", success);
        } else {
            log::debug!("Adapter is not available, nothing to resume.");
        }
    }

    fn on_adapter_found(&mut self, path: dbus::Path<'static>) {
        log::debug!("Found adapter suspend {:?}", path);

        let conn = self.conn.clone();
        self.context.lock().unwrap().adapter_suspend_dbus =
            Some(SuspendDBus::new(conn.clone(), path));

        let crossroads = self.context.lock().unwrap().dbus_crossroads.clone();

        if let Some(adapter_suspend_dbus) = &mut self.context.lock().unwrap().adapter_suspend_dbus {
            let suspend_cb_objpath: String =
                format!("/org/chromium/bluetooth/Manager/suspend_callback");
            adapter_suspend_dbus.register_callback(Box::new(SuspendCallback::new(
                suspend_cb_objpath,
                conn,
                crossroads,
                self.context.clone(),
            )));
        }
    }

    fn on_adapter_removed(&mut self) {
        log::debug!("Adapter suspend removed");
        self.context.lock().unwrap().adapter_suspend_dbus = None;
    }
}
