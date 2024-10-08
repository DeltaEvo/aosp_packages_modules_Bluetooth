/******************************************************************************
 *
 *  Copyright 2013 Google, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#pragma once

#include <hardware/bluetooth.h>

#include "types/raw_address.h"

typedef struct thread_t thread_t;

bt_status_t btsock_sco_init(thread_t* thread);
bt_status_t btsock_sco_cleanup(void);
bt_status_t btsock_sco_listen(int* sock_fd, int flags);
bt_status_t btsock_sco_connect(const RawAddress* bd_addr, int* sock_fd, int flags);
