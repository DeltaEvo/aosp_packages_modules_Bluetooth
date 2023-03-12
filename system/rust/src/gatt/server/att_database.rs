use async_trait::async_trait;
use bitflags::bitflags;

use crate::{
    core::uuid::Uuid,
    gatt::ids::AttHandle,
    packets::{
        AttAttributeDataChild, AttAttributeDataView, AttErrorCode, AttHandleBuilder, AttHandleView,
    },
};

// UUIDs from Bluetooth Assigned Numbers Sec 3.6
pub const PRIMARY_SERVICE_DECLARATION_UUID: Uuid = Uuid::new(0x2800);
pub const SECONDARY_SERVICE_DECLARATION_UUID: Uuid = Uuid::new(0x2801);
pub const CHARACTERISTIC_UUID: Uuid = Uuid::new(0x2803);

impl From<AttHandleView<'_>> for AttHandle {
    fn from(value: AttHandleView) -> Self {
        AttHandle(value.get_handle())
    }
}

impl From<AttHandle> for AttHandleBuilder {
    fn from(value: AttHandle) -> Self {
        AttHandleBuilder { handle: value.0 }
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
    pub struct AttPermissions : u8 {
        /// Attribute can be read using READ_REQ
        const READABLE = 0x02;
        /// Attribute can be written to using WRITE_REQ
        const WRITABLE = 0x08;
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
    pub fn writable(&self) -> bool {
        self.contains(AttPermissions::WRITABLE)
    }
    /// Attribute value may be sent using indications
    pub fn indicate(&self) -> bool {
        self.contains(AttPermissions::INDICATE)
    }
}

#[async_trait(?Send)]
pub trait AttDatabase {
    /// Read an attribute by handle
    async fn read_attribute(
        &self,
        handle: AttHandle,
    ) -> Result<AttAttributeDataChild, AttErrorCode>;

    /// Write to an attribute by handle
    async fn write_attribute(
        &self,
        handle: AttHandle,
        data: AttAttributeDataView<'_>,
    ) -> Result<(), AttErrorCode>;

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
    async fn read_attribute(
        &self,
        handle: AttHandle,
    ) -> Result<AttAttributeDataChild, AttErrorCode> {
        self.backing.read_attribute(handle).await
    }

    async fn write_attribute(
        &self,
        handle: AttHandle,
        data: AttAttributeDataView<'_>,
    ) -> Result<(), AttErrorCode> {
        self.backing.write_attribute(handle, data).await
    }

    fn list_attributes(&self) -> Vec<AttAttribute> {
        self.attributes.clone()
    }
}

impl StableAttDatabase for SnapshottedAttDatabase<'_> {}
