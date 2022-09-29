#[cxx::bridge(namespace = bluetooth::common::init_flags)]
mod ffi {
    extern "Rust" {
        fn load(flags: Vec<String>);
        fn set_all_for_testing();

        fn gd_core_is_enabled() -> bool;
        fn gd_security_is_enabled() -> bool;
        fn gd_l2cap_is_enabled() -> bool;
        fn gatt_robust_caching_client_is_enabled() -> bool;
        fn gatt_robust_caching_server_is_enabled() -> bool;
        fn btaa_hci_is_enabled() -> bool;
        fn gd_rust_is_enabled() -> bool;
        fn gd_link_policy_is_enabled() -> bool;
        fn irk_rotation_is_enabled() -> bool;
    }
}

use bt_common::init_flags::*;
