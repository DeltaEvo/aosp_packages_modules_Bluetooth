/*
 * Copyright 2023 The Android Open Source Project
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

#pragma once

#include <fmt/core.h>
#include <fmt/format.h>
#include <fmt/std.h>

#ifndef LOG_TAG
#define LOG_TAG "bluetooth"
#endif  // LOG_TAG

namespace bluetooth::log_internal {

/// Android framework log priority levels.
/// They are defined in system/logging/liblog/include/android/log.h by
/// the Android Framework code.
enum Level {
  kVerbose = 2,
  kDebug = 3,
  kInfo = 4,
  kWarn = 5,
  kError = 6,
  kFatal = 7,
};

/// Information about the location a log is printed from.
/// Passing this parameter by default value will fill in
/// the correct information.
struct source_location {
  source_location(char const* file_name = __builtin_FILE(), int line = __builtin_LINE(),
                  char const* function_name = __builtin_FUNCTION())
      : line(line), file_name(file_name), function_name(function_name) {}

  int line;
  char const* file_name;
  char const* function_name;
};

/// Write a single log line.
/// The implementation of this function is dependent on the backend.
void vlog(Level level, char const* tag, source_location location, fmt::string_view fmt,
          fmt::format_args vargs);

/// Capture invalid parameter values that would cause runtime
/// formatting errors.
template <class T>
[[maybe_unused]] static inline T& format_replace(T& arg) {
  return arg;
}

/// Specialization of format_replace for nullptr string parameters.
template <>
char const*& format_replace(char const*& arg) {
  static char const* nullptr_str = "(nullptr)";
  if (arg) {
    return arg;
  }
  return nullptr_str;
}

/// Specialization of format_replace for nullptr string parameters.
template <>
char*& format_replace(char*& arg) {
  static char* nullptr_str = (char*)"(nullptr)";
  if (arg) {
    return arg;
  }
  return nullptr_str;
}

template <Level level, typename... T>
struct log {
  log(fmt::format_string<T...> fmt, T&&... args, source_location location = source_location()) {
    vlog(level, LOG_TAG, location, static_cast<fmt::string_view>(fmt),
         fmt::make_format_args(format_replace(args)...));
  }
};

#if (__cplusplus >= 202002L && defined(__GNUC__) && !defined(__clang__))

template <int level, typename... T>
log(fmt::format_string<T...>, T&&...) -> log<level, T...>;

#endif

}  // namespace bluetooth::log_internal

namespace bluetooth::log {

#if (__cplusplus >= 202002L && defined(__GNUC__) && !defined(__clang__))

template <typename... T>
using error = log_internal::log<log_internal::kError, T...>;
template <typename... T>
using warning = log_internal::log<log_internal::kWarning, T...>;
template <typename... T>
using info = log_internal::log<log_internal::kInfo, T...>;
template <typename... T>
using debug = log_internal::log<log_internal::kDebug, T...>;
template <typename... T>
using verbose = log_internal::log<log_internal::kVerbose, T...>;

#else

template <typename... T>
struct error : log_internal::log<log_internal::kError, T...> {
  using log_internal::log<log_internal::kError, T...>::log;
};
template <typename... T>
struct warn : log_internal::log<log_internal::kWarn, T...> {
  using log_internal::log<log_internal::kWarn, T...>::log;
};
template <typename... T>
struct info : log_internal::log<log_internal::kInfo, T...> {
  using log_internal::log<log_internal::kInfo, T...>::log;
};
template <typename... T>
struct debug : log_internal::log<log_internal::kDebug, T...> {
  using log_internal::log<log_internal::kDebug, T...>::log;
};
template <typename... T>
struct verbose : log_internal::log<log_internal::kVerbose, T...> {
  using log_internal::log<log_internal::kVerbose, T...>::log;
};

template <typename... T>
error(fmt::format_string<T...>, T&&...) -> error<T...>;
template <typename... T>
warn(fmt::format_string<T...>, T&&...) -> warn<T...>;
template <typename... T>
info(fmt::format_string<T...>, T&&...) -> info<T...>;
template <typename... T>
debug(fmt::format_string<T...>, T&&...) -> debug<T...>;
template <typename... T>
verbose(fmt::format_string<T...>, T&&...) -> verbose<T...>;

#endif  // GCC / C++20

[[noreturn]] [[maybe_unused]] static void fatal(
        fmt::format_string<> fmt,
        log_internal::source_location location = log_internal::source_location()) {
  vlog(log_internal::kFatal, LOG_TAG, location, static_cast<fmt::string_view>(fmt),
       fmt::make_format_args());
  std::abort();  // Enforce [[noreturn]]
}

template <typename T0>
[[noreturn]] [[maybe_unused]] static void fatal(
        fmt::format_string<T0> fmt, T0&& arg0,
        log_internal::source_location location = log_internal::source_location()) {
  vlog(log_internal::kFatal, LOG_TAG, location, static_cast<fmt::string_view>(fmt),
       fmt::make_format_args(log_internal::format_replace(arg0)));
  std::abort();  // Enforce [[noreturn]]
}

template <typename T0, typename T1>
[[noreturn]] [[maybe_unused]] static void fatal(
        fmt::format_string<T0, T1> fmt, T0&& arg0, T1&& arg1,
        log_internal::source_location location = log_internal::source_location()) {
  vlog(log_internal::kFatal, LOG_TAG, location, static_cast<fmt::string_view>(fmt),
       fmt::make_format_args(log_internal::format_replace(arg0),
                             log_internal::format_replace(arg1)));
  std::abort();  // Enforce [[noreturn]]
}

template <typename T0, typename T1, typename T2>
[[noreturn]] [[maybe_unused]] static void fatal(
        fmt::format_string<T0, T1, T2> fmt, T0&& arg0, T1&& arg1, T2&& arg2,
        log_internal::source_location location = log_internal::source_location()) {
  vlog(log_internal::kFatal, LOG_TAG, location, static_cast<fmt::string_view>(fmt),
       fmt::make_format_args(log_internal::format_replace(arg0), log_internal::format_replace(arg1),
                             log_internal::format_replace(arg2)));
  std::abort();  // Enforce [[noreturn]]
}

template <typename T0, typename T1, typename T2, typename T3>
[[noreturn]] [[maybe_unused]] static void fatal(
        fmt::format_string<T0, T1, T2, T3> fmt, T0&& arg0, T1&& arg1, T2&& arg2, T3&& arg3,
        log_internal::source_location location = log_internal::source_location()) {
  vlog(log_internal::kFatal, LOG_TAG, location, static_cast<fmt::string_view>(fmt),
       fmt::make_format_args(log_internal::format_replace(arg0), log_internal::format_replace(arg1),
                             log_internal::format_replace(arg2),
                             log_internal::format_replace(arg3)));
  std::abort();  // Enforce [[noreturn]]
}

template <typename... T>
struct assert_that {
  assert_that(bool cond, fmt::format_string<T...> fmt, T&&... args,
              log_internal::source_location location = log_internal::source_location()) {
    if (!cond) {
      vlog(log_internal::kFatal, LOG_TAG, location, static_cast<fmt::string_view>(fmt),
           fmt::make_format_args(log_internal::format_replace(args)...));
    }
  }
};

template <typename... T>
assert_that(bool, fmt::format_string<T...>, T&&...) -> assert_that<T...>;

}  // namespace bluetooth::log

namespace fmt {

/// Default formatter implementation for formatting
/// enum class values to the underlying type.
///
/// Enable this formatter in the code by declaring:
/// ```
/// template<>
/// struct fmt::formatter<EnumT> : enum_formatter<EnumT> {};
/// ```
template <typename EnumT, class CharT = char>
struct enum_formatter : fmt::formatter<std::underlying_type_t<EnumT>, CharT> {
  template <class Context>
  typename Context::iterator format(EnumT value, Context& ctx) const {
    return fmt::formatter<std::underlying_type_t<EnumT>, CharT>::format(
            static_cast<std::underlying_type_t<EnumT>>(value), ctx);
  }
};

/// Default formatter implementation for formatting
/// values of type T for which a string conversion function
/// T_to_str is implemented.
///
/// Enable this formatter in the code by declaring:
/// ```
/// template<>
/// struct fmt::formatter<T> : string_formatter<T, &T_to_str> {};
/// ```
template <typename T, std::string (*F)(const T&), class CharT = char>
struct string_formatter : fmt::formatter<std::string> {
  template <class Context>
  typename Context::iterator format(const T& value, Context& ctx) const {
    return fmt::formatter<std::string>::format(F(value), ctx);
  }
};

}  // namespace fmt
