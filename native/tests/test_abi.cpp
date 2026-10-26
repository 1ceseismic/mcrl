#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>
#include <string>
#include "error.h"
#include "mcrl.h"

TEST_CASE("abi_version") { CHECK(mcrl_abi_version() == 7); }
TEST_CASE("init_idempotent") { CHECK(mcrl_init() == MCRL_OK); CHECK(mcrl_init() == MCRL_OK); CHECK(std::string(mcrl_last_error()) == ""); }

#include <stdexcept>
static int32_t guard_std() noexcept { MCRL_GUARD(throw std::runtime_error("boom");); }
static int32_t guard_other() noexcept { MCRL_GUARD(throw 42;); }
static int32_t guard_fallthrough() noexcept { MCRL_GUARD(int x = 1; (void)x;); }
static int32_t guard_commas() noexcept { MCRL_GUARD(std::pair<int, int> p{1, 2}; if (p.first == 1) return 7;); }

TEST_CASE("guard") {
    CHECK(guard_std() == MCRL_ERR_EXCEPTION);
    CHECK(std::string(mcrl_last_error()).find("boom") != std::string::npos);
    CHECK(guard_other() == MCRL_ERR_EXCEPTION);
    CHECK(std::string(mcrl_last_error()) == "unknown exception");
    CHECK(guard_fallthrough() == MCRL_OK);
    CHECK(std::string(mcrl_last_error()) == "");
    CHECK(guard_commas() == 7);
}
