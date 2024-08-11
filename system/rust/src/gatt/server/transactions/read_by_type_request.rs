use crate::{
    core::uuid::Uuid,
    gatt::server::att_database::StableAttDatabase,
    packets::att::{self, AttErrorCode},
};
use pdl_runtime::EncodeError;

use super::helpers::{
    att_filter_by_size_type::{filter_read_attributes_by_size_type, AttributeWithValue},
    att_range_filter::filter_to_range,
    payload_accumulator::PayloadAccumulator,
};

pub async fn handle_read_by_type_request(
    request: att::AttReadByTypeRequest,
    mtu: usize,
    db: &impl StableAttDatabase,
) -> Result<att::Att, EncodeError> {
    // As per spec (5.3 Vol 3F 3.4.4.1)
    // > If an attribute in the set of requested attributes would cause an
    // > ATT_ERROR_RSP PDU then this attribute cannot be included in an
    // > ATT_READ_BY_TYPE_RSP PDU and the attributes before this attribute
    // > shall be returned.
    //
    // Thus, we populate this response on failure, but only return it if no prior
    // matches were accumulated.
    let mut failure_response = att::AttErrorResponse {
        opcode_in_error: att::AttOpcode::ReadByTypeRequest,
        handle_in_error: request.starting_handle.clone(),
        // the default error code if we just fail to find anything
        error_code: AttErrorCode::AttributeNotFound,
    };

    let Ok(request_type): Result<Uuid, _> = request.attribute_type.try_into() else {
        failure_response.error_code = AttErrorCode::InvalidPdu;
        return failure_response.try_into();
    };

    let Some(attrs) = filter_to_range(
        request.starting_handle.into(),
        request.ending_handle.into(),
        db.list_attributes().into_iter(),
    ) else {
        failure_response.error_code = AttErrorCode::InvalidHandle;
        return failure_response.try_into();
    };

    // MTU-2 limit comes from Core Spec 5.3 Vol 3F 3.4.4.1
    let mut out = PayloadAccumulator::new(mtu - 2);

    // MTU-4 limit comes from Core Spec 5.3 Vol 3F 3.4.4.1
    match filter_read_attributes_by_size_type(db, attrs, request_type, mtu - 4).await {
        Ok(attrs) => {
            for AttributeWithValue { attr, value } in attrs {
                if !out.push(att::AttReadByTypeDataElement { handle: attr.handle.into(), value }) {
                    break;
                }
            }
        }
        Err(err) => {
            failure_response.error_code = err;
            return failure_response.try_into();
        }
    }

    if out.is_empty() {
        failure_response.try_into()
    } else {
        att::AttReadByTypeResponse { data: out.into_vec() }.try_into()
    }
}

#[cfg(test)]
mod test {
    use super::*;

    use crate::{
        core::uuid::Uuid,
        gatt::{
            ids::AttHandle,
            server::{
                att_database::AttAttribute, gatt_database::AttPermissions,
                test::test_att_db::TestAttDatabase,
            },
        },
        packets::att,
    };

    const UUID: Uuid = Uuid::new(1234);
    const ANOTHER_UUID: Uuid = Uuid::new(2345);

    #[test]
    fn test_single_matching_attr() {
        // arrange
        let db = TestAttDatabase::new(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: UUID,
                permissions: AttPermissions::READABLE,
            },
            vec![4, 5],
        )]);

        // act
        let att_view = att::AttReadByTypeRequest {
            starting_handle: AttHandle(2).into(),
            ending_handle: AttHandle(6).into(),
            attribute_type: UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_type_request(att_view, 31, &db));

        // assert
        assert_eq!(
            response,
            att::AttReadByTypeResponse {
                data: vec![att::AttReadByTypeDataElement {
                    handle: AttHandle(3).into(),
                    value: vec![4, 5],
                },]
            }
            .try_into()
        )
    }

    #[test]
    fn test_type_filtering() {
        // arrange
        let db = TestAttDatabase::new(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: ANOTHER_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(6),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![6, 7],
            ),
        ]);

        // act
        let att_view = att::AttReadByTypeRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(6).into(),
            attribute_type: UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_type_request(att_view, 31, &db));

        // assert: we correctly filtered by type (so we are using the filter_by_type
        // utility)
        assert_eq!(
            response,
            att::AttReadByTypeResponse {
                data: vec![
                    att::AttReadByTypeDataElement {
                        handle: AttHandle(3).into(),
                        value: vec![4, 5],
                    },
                    att::AttReadByTypeDataElement {
                        handle: AttHandle(6).into(),
                        value: vec![6, 7],
                    },
                ]
            }
            .try_into()
        )
    }

    #[test]
    fn test_limit_total_size() {
        // arrange
        let db = TestAttDatabase::new(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![5, 6, 7],
            ),
        ]);

        // act: read with MTU = 8, so we can only fit the first attribute (untruncated)
        let att_view = att::AttReadByTypeRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(6).into(),
            attribute_type: UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_type_request(att_view, 8, &db));

        // assert: we return only the first attribute
        assert_eq!(
            response,
            att::AttReadByTypeResponse {
                data: vec![att::AttReadByTypeDataElement {
                    handle: AttHandle(3).into(),
                    value: vec![4, 5, 6],
                },]
            }
            .try_into()
        )
    }

    #[test]
    fn test_no_results() {
        // arrange: read out of the bounds where attributes of interest exist
        let db = TestAttDatabase::new(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: ANOTHER_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5, 6],
            ),
        ]);

        // act
        let att_view = att::AttReadByTypeRequest {
            starting_handle: AttHandle(4).into(),
            ending_handle: AttHandle(6).into(),
            attribute_type: UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_type_request(att_view, 31, &db));

        // assert: we return ATTRIBUTE_NOT_FOUND
        assert_eq!(
            response,
            att::AttErrorResponse {
                handle_in_error: AttHandle(4).into(),
                opcode_in_error: att::AttOpcode::ReadByTypeRequest,
                error_code: AttErrorCode::AttributeNotFound,
            }
            .try_into()
        )
    }

    #[test]
    fn test_range_validation() {
        // arrange: put a non-readable attribute in the db with the right type
        let db = TestAttDatabase::new(vec![]);

        // act
        let att_view = att::AttReadByTypeRequest {
            starting_handle: AttHandle(0).into(),
            ending_handle: AttHandle(6).into(),
            attribute_type: UUID.into(),
        };
        let response = tokio_test::block_on(handle_read_by_type_request(att_view, 31, &db));

        // assert: we return an INVALID_HANDLE error
        assert_eq!(
            response,
            att::AttErrorResponse {
                handle_in_error: AttHandle(0).into(),
                opcode_in_error: att::AttOpcode::ReadByTypeRequest,
                error_code: AttErrorCode::InvalidHandle,
            }
            .try_into()
        )
    }
}
