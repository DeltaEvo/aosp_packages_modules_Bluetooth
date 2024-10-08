/*
 * Copyright 2019 The Android Open Source Project
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

#include "fields/size_field.h"

#include "util.h"

const std::string SizeField::kFieldType = "SizeField";

SizeField::SizeField(std::string name, int size, ParseLocation loc)
    : ScalarField(name + "_size", size, loc), sized_field_name_(name) {}

const std::string& SizeField::GetFieldType() const { return SizeField::kFieldType; }

void SizeField::GenGetter(std::ostream& s, Size start_offset, Size end_offset) const {
  s << "protected:";
  ScalarField::GenGetter(s, start_offset, end_offset);
  s << "public:\n";
}

std::string SizeField::GetBuilderParameterType() const { return ""; }

bool SizeField::GenBuilderParameter(std::ostream&) const {
  // There is no builder parameter for a size field
  return false;
}

bool SizeField::HasParameterValidator() const { return false; }

void SizeField::GenParameterValidator(std::ostream&) const {
  // There is no builder parameter for a size field
  // TODO: Check if the payload fits in the packet?
}

void SizeField::GenInserter(std::ostream&) const {
  ERROR(this) << __func__ << ": This should not be called for size fields";
}

void SizeField::GenValidator(std::ostream&) const {
  // Do nothing since the fixed size fields will be handled specially.
}

std::string SizeField::GetSizedFieldName() const { return sized_field_name_; }

void SizeField::GenStringRepresentation(std::ostream& s, std::string accessor) const {
  s << accessor;
}
