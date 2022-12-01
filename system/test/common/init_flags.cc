

#include <map>
#include <string>

#include "gd/common/init_flags.h"

namespace bluetooth {
namespace common {

bool InitFlags::logging_debug_enabled_for_all = false;
bool InitFlags::btm_dm_flush_discovery_queue_on_search_cancel = false;
std::unordered_map<std::string, bool>
    InitFlags::logging_debug_explicit_tag_settings = {};
void InitFlags::Load(const char** flags) {}
void InitFlags::SetAll(bool value) {
  InitFlags::logging_debug_enabled_for_all = value;
}
void InitFlags::SetAllForTesting() {
  InitFlags::logging_debug_enabled_for_all = true;
}

}  // namespace common
}  // namespace bluetooth
