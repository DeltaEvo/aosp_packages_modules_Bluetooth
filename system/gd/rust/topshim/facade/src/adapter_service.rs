//! Adapter service facade

use bt_topshim::btif;
use bt_topshim::btif::{BaseCallbacks, BaseCallbacksDispatcher, BluetoothInterface};

use bt_topshim_facade_protobuf::empty::Empty;
use bt_topshim_facade_protobuf::facade::{
    EventType, FetchEventsRequest, FetchEventsResponse, SetDefaultEventMaskExceptRequest,
    SetDiscoveryModeRequest, SetDiscoveryModeResponse, ToggleStackRequest, ToggleStackResponse,
};
use bt_topshim_facade_protobuf::facade_grpc::{create_adapter_service, AdapterService};
use futures::sink::SinkExt;
use grpcio::*;

use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tokio::sync::Mutex as TokioMutex;
fn get_bt_dispatcher(
    btif: Arc<Mutex<BluetoothInterface>>,
    tx: mpsc::Sender<BaseCallbacks>,
) -> BaseCallbacksDispatcher {
    BaseCallbacksDispatcher {
        dispatch: Box::new(move |cb: BaseCallbacks| {
            if tx.clone().try_send(cb.clone()).is_err() {
                println!("Cannot send event {:?}", cb);
            }
            match cb {
                BaseCallbacks::AdapterState(state) => {
                    println!("State changed to {:?}", state);
                }
                BaseCallbacks::SspRequest(addr, _, _, variant, passkey) => {
                    btif.lock().unwrap().ssp_reply(&addr, variant, 1, passkey);
                }
                _ => (),
            }
        }),
    }
}

/// Main object for Adapter facade service
#[derive(Clone)]
pub struct AdapterServiceImpl {
    #[allow(dead_code)]
    rt: Arc<Runtime>,
    btif_intf: Arc<Mutex<BluetoothInterface>>,
    event_rx: Arc<TokioMutex<mpsc::Receiver<BaseCallbacks>>>,
    #[allow(dead_code)]
    event_tx: mpsc::Sender<BaseCallbacks>,
}

impl AdapterServiceImpl {
    /// Create a new instance of the root facade service
    pub fn create(rt: Arc<Runtime>, btif_intf: Arc<Mutex<BluetoothInterface>>) -> grpcio::Service {
        let (event_tx, rx) = mpsc::channel(10);
        btif_intf.lock().unwrap().initialize(
            get_bt_dispatcher(btif_intf.clone(), event_tx.clone()),
            vec!["INIT_gd_hci=true".to_string()],
        );
        create_adapter_service(Self {
            rt,
            btif_intf,
            event_rx: Arc::new(TokioMutex::new(rx)),
            event_tx,
        })
    }
}

impl AdapterService for AdapterServiceImpl {
    fn fetch_events(
        &mut self,
        ctx: RpcContext<'_>,
        _req: FetchEventsRequest,
        mut sink: ServerStreamingSink<FetchEventsResponse>,
    ) {
        let rx = self.event_rx.clone();
        ctx.spawn(async move {
            while let Some(event) = rx.lock().await.recv().await {
                match event {
                    BaseCallbacks::AdapterState(_state) => {
                        let mut rsp = FetchEventsResponse::new();
                        rsp.event_type = EventType::ADAPTER_STATE;
                        rsp.data = "ON".to_string();
                        sink.send((rsp, WriteFlags::default())).await.unwrap();
                    }
                    BaseCallbacks::SspRequest(_, _, _, _, _) => {}
                    BaseCallbacks::LeRandCallback(random) => {
                        let mut rsp = FetchEventsResponse::new();
                        rsp.event_type = EventType::LE_RAND;
                        rsp.data = random.to_string();
                        sink.send((rsp, WriteFlags::default())).await.unwrap();
                    }
                    _ => (),
                }
            }
        })
    }

    fn toggle_stack(
        &mut self,
        ctx: RpcContext<'_>,
        req: ToggleStackRequest,
        sink: UnarySink<ToggleStackResponse>,
    ) {
        match req.start_stack {
            true => self.btif_intf.lock().unwrap().enable(),
            false => self.btif_intf.lock().unwrap().disable(),
        };
        ctx.spawn(async move {
            sink.success(ToggleStackResponse::default()).await.unwrap();
        })
    }

    fn set_discovery_mode(
        &mut self,
        ctx: RpcContext<'_>,
        _req: SetDiscoveryModeRequest,
        sink: UnarySink<SetDiscoveryModeResponse>,
    ) {
        self.btif_intf.lock().unwrap().set_adapter_property(
            btif::BluetoothProperty::AdapterScanMode(btif::BtScanMode::Connectable),
        );

        ctx.spawn(async move {
            sink.success(SetDiscoveryModeResponse::default()).await.unwrap();
        })
    }

    fn clear_event_filter(&mut self, ctx: RpcContext<'_>, _req: Empty, sink: UnarySink<Empty>) {
        self.btif_intf.lock().unwrap().clear_event_filter();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn clear_event_mask(&mut self, ctx: RpcContext<'_>, _req: Empty, sink: UnarySink<Empty>) {
        self.btif_intf.lock().unwrap().clear_event_mask();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn clear_filter_accept_list(
        &mut self,
        ctx: RpcContext<'_>,
        _req: Empty,
        sink: UnarySink<Empty>,
    ) {
        self.btif_intf.lock().unwrap().clear_filter_accept_list();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn disconnect_all_acls(&mut self, ctx: RpcContext<'_>, _req: Empty, sink: UnarySink<Empty>) {
        self.btif_intf.lock().unwrap().disconnect_all_acls();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn le_rand(&mut self, ctx: RpcContext<'_>, _req: Empty, sink: UnarySink<Empty>) {
        self.btif_intf.lock().unwrap().le_rand();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn allow_wake_by_hid(&mut self, ctx: RpcContext<'_>, _req: Empty, sink: UnarySink<Empty>) {
        self.btif_intf.lock().unwrap().allow_wake_by_hid();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn restore_filter_accept_list(
        &mut self,
        ctx: RpcContext<'_>,
        _req: Empty,
        sink: UnarySink<Empty>,
    ) {
        self.btif_intf.lock().unwrap().restore_filter_accept_list();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn set_default_event_mask_except(
        &mut self,
        ctx: RpcContext<'_>,
        req: SetDefaultEventMaskExceptRequest,
        sink: UnarySink<Empty>,
    ) {
        self.btif_intf.lock().unwrap().set_default_event_mask_except(req.mask, req.le_mask);
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn set_event_filter_inquiry_result_all_devices(
        &mut self,
        ctx: RpcContext<'_>,
        _req: Empty,
        sink: UnarySink<Empty>,
    ) {
        self.btif_intf.lock().unwrap().set_event_filter_inquiry_result_all_devices();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }

    fn set_event_filter_connection_setup_all_devices(
        &mut self,
        ctx: RpcContext<'_>,
        _req: Empty,
        sink: UnarySink<Empty>,
    ) {
        self.btif_intf.lock().unwrap().set_event_filter_connection_setup_all_devices();
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }
}
