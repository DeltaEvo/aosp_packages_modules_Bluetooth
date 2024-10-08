use async_trait::async_trait;
use bitflags::bitflags;

use crate::{
    core::uuid::Uuid,
    gatt::ids::AttHandle,
    packets::att::{self, AttErrorCode},
};

impl From<att::AttHandle> for AttHandle {
    fn from(value: att::AttHandle) -> Self {
        AttHandle(value.handle)
    }
}

impl From<AttHandle> for att::AttHandle {
    fn from(value: AttHandle) -> Self {
        att::AttHandle { handle: value.0 }
    }
}

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub struct AttAttribute {
    pub handle: AttHandle,
    pub type_: Uuid,
    pub permissions: AttPermissions,
}

bitflags! {
    /// The attribute properties supported by the current GATT server implementation
    /// Unimplemented properties will default to false.
    ///
    /// These values are from Core Spec 5.3 Vol 3G 3.3.1.1 Characteristic Properties,
    /// and also match what Android uses in JNI.
    #[derive(Copy, Clone, Debug, PartialEq, Eq)]
    pub struct AttPermissions : u8 {
        /// Attribute can be read using READ_REQ
        const READABLE = 0x02;
        /// Attribute can be written to using WRITE_CMD
        const WRITABLE_WITHOUT_RESPONSE = 0x04;
        /// Attribute can be written to using WRITE_REQ
        const WRITABLE_WITH_RESPONSE = 0x08;
        /// Attribute value may be sent using indications
        const INDICATE = 0x20;
    }
}

impl AttPermissions {
    /// Attribute can be read using READ_REQ
    pub fn readable(&self) -> bool {
        self.contains(AttPermissions::READABLE)
    }
    /// Attribute can be written to using WRITE_REQ
    pub fn writable_with_response(&self) -> bool {
        self.contains(AttPermissions::WRITABLE_WITH_RESPONSE)
    }
    /// Attribute can be written to using WRITE_CMD
    pub fn writable_without_response(&self) -> bool {
        self.contains(AttPermissions::WRITABLE_WITHOUT_RESPONSE)
    }
    /// Attribute value may be sent using indications
    pub fn indicate(&self) -> bool {
        self.contains(AttPermissions::INDICATE)
    }
}

#[async_trait(?Send)]
pub trait AttDatabase {
    /// Read an attribute by handle
    async fn read_attribute(&self, handle: AttHandle) -> Result<Vec<u8>, AttErrorCode>;

    /// Write to an attribute by handle
    async fn write_attribute(&self, handle: AttHandle, data: &[u8]) -> Result<(), AttErrorCode>;

    /// Write to an attribute by handle
    fn write_no_response_attribute(&self, handle: AttHandle, data: &[u8]);

    /// List all the attributes in this database.
    ///
    /// Expected to return them in sorted order.
    fn list_attributes(&self) -> Vec<AttAttribute>;

    /// Produce an implementation of StableAttDatabase
    fn snapshot(&self) -> SnapshottedAttDatabase<'_>
    where
        Self: Sized,
    {
        SnapshottedAttDatabase { attributes: self.list_attributes(), backing: self }
    }
}

/// Marker trait indicating that the backing attribute list of this
/// database is guaranteed to remain unchanged across async points.
///
/// Useful if we want to call list_attributes() multiple times, rather than
/// caching its result the first time.
pub trait StableAttDatabase: AttDatabase {
    fn find_attribute(&self, handle: AttHandle) -> Option<AttAttribute> {
        self.list_attributes().into_iter().find(|attr| attr.handle == handle)
    }
}

/// A snapshot of an AttDatabase implementing StableAttDatabase.
pub struct SnapshottedAttDatabase<'a> {
    attributes: Vec<AttAttribute>,
    backing: &'a (dyn AttDatabase),
}

#[async_trait(?Send)]
impl AttDatabase for SnapshottedAttDatabase<'_> {
    async fn read_attribute(&self, handle: AttHandle) -> Result<Vec<u8>, AttErrorCode> {
        self.backing.read_attribute(handle).await
    }

    async fn write_attribute(&self, handle: AttHandle, data: &[u8]) -> Result<(), AttErrorCode> {
        self.backing.write_attribute(handle, data).await
    }

    fn write_no_response_attribute(&self, handle: AttHandle, data: &[u8]) {
        self.backing.write_no_response_attribute(handle, data);
    }

    fn list_attributes(&self) -> Vec<AttAttribute> {
        self.attributes.clone()
    }
}

impl StableAttDatabase for SnapshottedAttDatabase<'_> {}
