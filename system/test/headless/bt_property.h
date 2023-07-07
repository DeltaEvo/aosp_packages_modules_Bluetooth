

#pragma once

#include "include/hardware/bluetooth.h"

namespace bluetooth {
namespace test {
namespace headless {

void process_property(const RawAddress& bd_addr, const bt_property_t* prop);
}
}  // namespace test
}  // namespace bluetooth
