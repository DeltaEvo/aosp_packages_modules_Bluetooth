/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "mock_state_machine.h"

static MockLeAudioGroupStateMachine* mock_machine = nullptr;

void bluetooth::le_audio::LeAudioGroupStateMachine::Initialize(
        bluetooth::le_audio::LeAudioGroupStateMachine::Callbacks* state_machine_callbacks) {
  log::assert_that(mock_machine, "Mock State Machine not set!");
  mock_machine->Initialize(state_machine_callbacks);
}

void bluetooth::le_audio::LeAudioGroupStateMachine::Cleanup(void) {
  log::assert_that(mock_machine, "Mock State Machine not set!");
  mock_machine->Cleanup();
}

bluetooth::le_audio::LeAudioGroupStateMachine* bluetooth::le_audio::LeAudioGroupStateMachine::Get(
        void) {
  return mock_machine;
}

void MockLeAudioGroupStateMachine::SetMockInstanceForTesting(
        MockLeAudioGroupStateMachine* machine) {
  mock_machine = machine;
}
