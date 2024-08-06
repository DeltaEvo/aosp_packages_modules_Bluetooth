use log::warn;
use pdl_runtime::DecodeError;
use pdl_runtime::EncodeError;

use crate::{
    gatt::ids::AttHandle,
    packets::att::{self, AttErrorCode},
};

use super::{
    att_database::AttDatabase,
    transactions::{
        find_by_type_value::handle_find_by_type_value_request,
        find_information_request::handle_find_information_request,
        read_by_group_type_request::handle_read_by_group_type_request,
        read_by_type_request::handle_read_by_type_request, read_request::handle_read_request,
        write_request::handle_write_request,
    },
};

/// This struct handles all requests needing ACKs. Only ONE should exist per
/// bearer per database, to ensure serialization.
pub struct AttRequestHandler<Db: AttDatabase> {
    db: Db,
}

/// Type of errors raised by request handlers.
#[allow(dead_code)]
enum ProcessingError {
    DecodeError(DecodeError),
    EncodeError(EncodeError),
    RequestNotSupported(att::AttOpcode),
}

impl From<DecodeError> for ProcessingError {
    fn from(err: DecodeError) -> Self {
        Self::DecodeError(err)
    }
}

impl From<EncodeError> for ProcessingError {
    fn from(err: EncodeError) -> Self {
        Self::EncodeError(err)
    }
}

impl<Db: AttDatabase> AttRequestHandler<Db> {
    pub fn new(db: Db) -> Self {
        Self { db }
    }

    // Runs a task to process an incoming packet. Takes an exclusive reference to
    // ensure that only one request is outstanding at a time (notifications +
    // commands should take a different path)
    pub async fn process_packet(&mut self, packet: att::Att, mtu: usize) -> att::Att {
        match self.try_parse_and_process_packet(&packet, mtu).await {
            Ok(result) => result,
            Err(_) => {
                // parse error, assume it's an unsupported request
                // TODO(aryarahul): distinguish between REQUEST_NOT_SUPPORTED and INVALID_PDU
                att::AttErrorResponse {
                    opcode_in_error: packet.opcode,
                    handle_in_error: AttHandle(0).into(),
                    error_code: AttErrorCode::RequestNotSupported,
                }
                .try_into()
                .unwrap()
            }
        }
    }

    async fn try_parse_and_process_packet(
        &mut self,
        packet: &att::Att,
        mtu: usize,
    ) -> Result<att::Att, ProcessingError> {
        let snapshotted_db = self.db.snapshot();
        match packet.opcode {
            att::AttOpcode::ReadRequest => {
                Ok(handle_read_request(packet.try_into()?, mtu, &self.db).await?)
            }
            att::AttOpcode::ReadByGroupTypeRequest => {
                Ok(handle_read_by_group_type_request(packet.try_into()?, mtu, &snapshotted_db)
                    .await?)
            }
            att::AttOpcode::ReadByTypeRequest => {
                Ok(handle_read_by_type_request(packet.try_into()?, mtu, &snapshotted_db).await?)
            }
            att::AttOpcode::FindInformationRequest => {
                Ok(handle_find_information_request(packet.try_into()?, mtu, &snapshotted_db)?)
            }
            att::AttOpcode::FindByTypeValueRequest => {
                Ok(handle_find_by_type_value_request(packet.try_into()?, mtu, &snapshotted_db)
                    .await?)
            }
            att::AttOpcode::WriteRequest => {
                Ok(handle_write_request(packet.try_into()?, &self.db).await?)
            }
            _ => {
                warn!("Dropping unsupported opcode {:?}", packet.opcode);
                Err(ProcessingError::RequestNotSupported(packet.opcode))
            }
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use crate::{
        core::uuid::Uuid,
        gatt::server::{
            att_database::{AttAttribute, AttPermissions},
            request_handler::AttRequestHandler,
            test::test_att_db::TestAttDatabase,
        },
        packets::att,
    };

    #[test]
    fn test_read_request() {
        // arrange
        let db = TestAttDatabase::new(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            vec![1, 2, 3],
        )]);
        let mut handler = AttRequestHandler { db };
        let att_view =
            att::AttReadRequest { attribute_handle: AttHandle(3).into() }.try_into().unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(Ok(response), att::AttReadResponse { value: vec![1, 2, 3] }.try_into());
    }

    #[test]
    fn test_unsupported_request() {
        // arrange
        let db = TestAttDatabase::new(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x1234),
                permissions: AttPermissions::READABLE,
            },
            vec![1, 2, 3],
        )]);
        let mut handler = AttRequestHandler { db };
        let att_view = att::AttWriteResponse {}.try_into().unwrap();

        // act
        let response = tokio_test::block_on(handler.process_packet(att_view, 31));

        // assert
        assert_eq!(
            Ok(response),
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::WriteResponse,
                handle_in_error: AttHandle(0).into(),
                error_code: AttErrorCode::RequestNotSupported
            }
            .try_into()
        );
    }
}
