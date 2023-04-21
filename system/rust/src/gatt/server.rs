//! This module is a simple GATT server that shares the ATT channel with the
//! existing C++ GATT client.

mod att_database;
pub mod att_server_bearer;
pub mod gatt_database;
mod indication_handler;
mod request_handler;
pub mod services;
mod transactions;

mod command_handler;
#[cfg(test)]
mod test;

use std::{collections::HashMap, rc::Rc};

use crate::{
    core::shared_box::{SharedBox, WeakBox, WeakBoxRef},
    gatt::{ids::ConnectionId, server::gatt_database::GattDatabase},
};

use self::{
    super::ids::ServerId,
    att_server_bearer::AttServerBearer,
    gatt_database::{AttDatabaseImpl, GattServiceWithHandle},
    services::register_builtin_services,
};

use super::{callbacks::RawGattDatastore, channel::AttTransport, ids::AttHandle};
use anyhow::{anyhow, bail, Result};
use log::info;

pub use indication_handler::IndicationError;

#[allow(missing_docs)]
pub struct GattModule {
    connections: HashMap<ConnectionId, GattConnection>,
    databases: HashMap<ServerId, SharedBox<GattDatabase>>,
    transport: Rc<dyn AttTransport>,
}

struct GattConnection {
    bearer: SharedBox<AttServerBearer<AttDatabaseImpl>>,
    database: WeakBox<GattDatabase>,
}

impl GattModule {
    /// Constructor.
    pub fn new(transport: Rc<dyn AttTransport>) -> Self {
        Self { connections: HashMap::new(), databases: HashMap::new(), transport }
    }

    /// Handle LE link connect
    pub fn on_le_connect(&mut self, conn_id: ConnectionId) -> Result<()> {
        info!("connected on conn_id {conn_id:?}");
        let database = self.databases.get(&conn_id.get_server_id());
        let Some(database) = database else {
            bail!(
                "got connection to conn_id {conn_id:?} (server_id {:?}) but this server does not exist!",
                conn_id.get_server_id(),
            );
        };
        let transport = self.transport.clone();
        let bearer = SharedBox::new(AttServerBearer::new(
            database.get_att_database(conn_id),
            move |packet| transport.send_packet(conn_id.get_tcb_idx(), packet),
        ));
        database.on_bearer_ready(conn_id, bearer.as_ref());
        self.connections.insert(conn_id, GattConnection { bearer, database: database.downgrade() });
        Ok(())
    }

    /// Handle an LE link disconnect
    pub fn on_le_disconnect(&mut self, conn_id: ConnectionId) -> Result<()> {
        info!("disconnected conn_id {conn_id:?}");
        let connection = self.connections.remove(&conn_id);
        let Some(connection) = connection else {
            bail!("got disconnection from {conn_id:?} but bearer does not exist");
        };
        drop(connection.bearer);
        connection.database.with(|db| db.map(|db| db.on_bearer_dropped(conn_id)));
        Ok(())
    }

    /// Register a new GATT service on a given server
    pub fn register_gatt_service(
        &mut self,
        server_id: ServerId,
        service: GattServiceWithHandle,
        datastore: Rc<dyn RawGattDatastore>,
    ) -> Result<()> {
        self.databases
            .get(&server_id)
            .ok_or_else(|| anyhow!("server {server_id:?} not opened"))?
            .add_service_with_handles(service, datastore)
    }

    /// Unregister an existing GATT service on a given server
    pub fn unregister_gatt_service(
        &mut self,
        server_id: ServerId,
        service_handle: AttHandle,
    ) -> Result<()> {
        self.databases
            .get(&server_id)
            .ok_or_else(|| anyhow!("server {server_id:?} not opened"))?
            .remove_service_at_handle(service_handle)
    }

    /// Open a GATT server
    pub fn open_gatt_server(&mut self, server_id: ServerId) -> Result<()> {
        let mut db = GattDatabase::new();
        register_builtin_services(&mut db)?;
        let old = self.databases.insert(server_id, db.into());
        if old.is_some() {
            bail!("GATT server {server_id:?} already exists but was re-opened, clobbering old value...")
        }
        Ok(())
    }

    /// Close a GATT server
    pub fn close_gatt_server(&mut self, server_id: ServerId) -> Result<()> {
        let old = self.databases.remove(&server_id);
        if old.is_none() {
            bail!("GATT server {server_id:?} did not exist")
        };

        Ok(())
    }

    /// Get an ATT bearer for a particular connection
    pub fn get_bearer(
        &self,
        conn_id: ConnectionId,
    ) -> Option<WeakBoxRef<AttServerBearer<AttDatabaseImpl>>> {
        self.connections.get(&conn_id).map(|x| x.bearer.as_ref())
    }
}
