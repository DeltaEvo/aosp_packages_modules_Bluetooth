/******************************************************************************
 *
 *  Copyright 2015 Google, Inc.
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

#include <features.h>
#include <sys/types.h>

#if __GLIBC__

#include <unistd.h>

/* Get thread identification. */
pid_t gettid(void) throw();

/* glibc>=2.38 supplies its own strlcpy which conflicts with this declaration */
#if !(__GLIBC_PREREQ(2, 38))
/* Copy src to string dst of size siz. */
size_t strlcpy(char* dst, const char* src, size_t siz);
#endif /* !(__GLIBC_PREREQ(2, 38)) */

#endif
