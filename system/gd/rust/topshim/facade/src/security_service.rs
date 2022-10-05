//! Security service facade

use bt_topshim::btif::BluetoothInterface;

use bt_topshim_facade_protobuf::empty::Empty;
use bt_topshim_facade_protobuf::facade::RemoveBondRequest;
use bt_topshim_facade_protobuf::facade_grpc::{create_security_service, SecurityService};
use grpcio::*;

use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;

/// Main object for Adapter facade service
#[derive(Clone)]
pub struct SecurityServiceImpl {
    #[allow(dead_code)]
    rt: Arc<Runtime>,
    #[allow(dead_code)]
    btif_intf: Arc<Mutex<BluetoothInterface>>,
}

#[allow(dead_code)]
impl SecurityServiceImpl {
    /// Create a new instance of the root facade service
    pub fn create(rt: Arc<Runtime>, btif_intf: Arc<Mutex<BluetoothInterface>>) -> grpcio::Service {
        create_security_service(Self { rt, btif_intf })
    }
}

impl SecurityService for SecurityServiceImpl {
    fn remove_bond(
        &mut self,
        ctx: RpcContext<'_>,
        _req: RemoveBondRequest,
        sink: UnarySink<Empty>,
    ) {
        ctx.spawn(async move {
            sink.success(Empty::default()).await.unwrap();
        })
    }
}
