use crate::{
    gatt::{
        ids::AttHandle,
        server::att_database::{AttAttribute, AttDatabase, StableAttDatabase},
    },
    packets::att::AttErrorCode,
};

use async_trait::async_trait;
use log::{info, warn};
use std::{cell::RefCell, collections::BTreeMap, rc::Rc};

#[derive(Clone, Debug)]
pub struct TestAttDatabase {
    attributes: Rc<BTreeMap<AttHandle, TestAttributeWithData>>,
}

#[derive(Debug)]
struct TestAttributeWithData {
    attribute: AttAttribute,
    data: RefCell<Vec<u8>>,
}

impl TestAttDatabase {
    pub fn new(attributes: Vec<(AttAttribute, Vec<u8>)>) -> Self {
        Self {
            attributes: Rc::new(
                attributes
                    .into_iter()
                    .map(|(attribute, data)| {
                        (attribute.handle, TestAttributeWithData { attribute, data: data.into() })
                    })
                    .collect(),
            ),
        }
    }
}

#[async_trait(?Send)]
impl AttDatabase for TestAttDatabase {
    async fn read_attribute(&self, handle: AttHandle) -> Result<Vec<u8>, AttErrorCode> {
        info!("reading {handle:?}");
        match self.attributes.get(&handle) {
            Some(TestAttributeWithData { attribute: AttAttribute { permissions, .. }, .. })
                if !permissions.readable() =>
            {
                Err(AttErrorCode::ReadNotPermitted)
            }
            Some(TestAttributeWithData { data, .. }) => Ok(data.borrow().clone()),
            None => Err(AttErrorCode::InvalidHandle),
        }
    }
    async fn write_attribute(&self, handle: AttHandle, data: &[u8]) -> Result<(), AttErrorCode> {
        match self.attributes.get(&handle) {
            Some(TestAttributeWithData { attribute: AttAttribute { permissions, .. }, .. })
                if !permissions.writable_with_response() =>
            {
                Err(AttErrorCode::WriteNotPermitted)
            }
            Some(TestAttributeWithData { data: data_cell, .. }) => {
                data_cell.replace(data.to_vec());
                Ok(())
            }
            None => Err(AttErrorCode::InvalidHandle),
        }
    }
    fn write_no_response_attribute(&self, handle: AttHandle, data: &[u8]) {
        match self.attributes.get(&handle) {
            Some(TestAttributeWithData {
                attribute: AttAttribute { permissions, .. },
                data: data_cell,
            }) if !permissions.writable_with_response() => {
                data_cell.replace(data.to_vec());
            }
            _ => {
                warn!("rejecting write command to {handle:?}")
            }
        }
    }
    fn list_attributes(&self) -> Vec<AttAttribute> {
        self.attributes.values().map(|attr| attr.attribute).collect()
    }
}

// We guarantee that the contents of a TestAttDatabase will remain stable
impl StableAttDatabase for TestAttDatabase {}
