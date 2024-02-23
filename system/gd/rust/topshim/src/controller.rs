#[cxx::bridge(namespace = bluetooth::topshim::rust)]
mod ffi {
    unsafe extern "C++" {
        include!("gd/rust/topshim/common/type_alias.h");
        type RawAddress = crate::btif::RawAddress;
    }

    unsafe extern "C++" {
        include!("controller/controller_shim.h");

        type ControllerIntf;

        fn GetControllerInterface() -> UniquePtr<ControllerIntf>;
        fn read_local_addr(self: &ControllerIntf) -> RawAddress;
    }
}

pub struct Controller {
    internal: cxx::UniquePtr<ffi::ControllerIntf>,
}

unsafe impl Send for Controller {}

impl Controller {
    pub fn new() -> Controller {
        let intf = ffi::GetControllerInterface();
        Controller { internal: intf }
    }

    pub fn read_local_addr(&mut self) -> [u8; 6] {
        self.internal.read_local_addr().address
    }
}
