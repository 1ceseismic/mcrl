#pragma once
#include <exception>
#include <string>
#include "mcrl.h"

namespace mcrl_internal {
void set_last_error(const char* msg) noexcept;
void clear_last_error() noexcept;
}

namespace mcrl {
int32_t fail(int32_t code, const char* fn, const std::string& what);
}

#define MCRL_GUARD(...)                                                         \
    do {                                                                        \
        try {                                                                   \
            mcrl_internal::clear_last_error();                                  \
            __VA_ARGS__                                                         \
        } catch (const std::exception& e_) {                                    \
            mcrl_internal::set_last_error(e_.what());                           \
            return MCRL_ERR_EXCEPTION;                                          \
        } catch (...) {                                                         \
            mcrl_internal::set_last_error("unknown exception");                 \
            return MCRL_ERR_EXCEPTION;                                          \
        }                                                                       \
        return MCRL_OK;                                                         \
    } while (0)
