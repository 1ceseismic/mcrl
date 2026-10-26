#include <doctest/doctest.h>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>
#include <thread>
#include "mcrl.h"
#include "terrain_fixtures.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;
constexpr int PRESETS = 7;
constexpr double CFG_TOL = 1e-5;

#define CHECK_NEAR(a, b, tol) CHECK_MESSAGE(std::abs((double)(a) - (double)(b)) <= (tol), #a " = ", (a), ", want ", (b))

struct Rig {
    McrlShapes* shapes = makeShapes();
    McrlWorld* w = nullptr;
    Rig() {
        w = makeWorld(shapes);
        commitStandard(w, shapes, floorScene());
    }
    ~Rig() {
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
        CHECK(mcrl_shapes_destroy(shapes) == MCRL_OK);
    }
    Rig(const Rig&) = delete;
    Rig& operator=(const Rig&) = delete;
};

McrlCarConfig getConfig(McrlWorld* w, int32_t id) {
    McrlCarConfig c{};
    REQUIRE(mcrl_car_get_config(w, id, &c) == MCRL_OK);
    return c;
}

void checkVec3(const float* v, double x, double y, double z) {
    CHECK_NEAR(v[0], x, CFG_TOL);
    CHECK_NEAR(v[1], y, CFG_TOL);
    CHECK_NEAR(v[2], z, CFG_TOL);
}
}

TEST_CASE("cars: config read-back per preset") {
    Rig r;
    for (int32_t id = 1; id <= PRESETS; id++) {
        const McrlCarState s = carAt(0.5, FLOOR_TOP + CAR_REST, 0.5 + 6 * id);
        REQUIRE(mcrl_car_add(r.w, id, id % 2, id - 1, &s) == MCRL_OK);
    }
    for (int32_t id = 1; id <= PRESETS; id++) {
        CAPTURE(id);
        const McrlCarConfig c = getConfig(r.w, id);
        CHECK(c.preset == id - 1);
        CHECK(c.team == id % 2);
        CHECK(c.threeWheels == (id - 1 == 6 ? 1 : 0));
        CHECK(c.pad == 0);
        for (int a = 0; a < 3; a++) CHECK(c.hitboxSize[a] > 0);
        CHECK(c.connection[2] > 0);
        CHECK(c.connection[5] > 0);
    }

    const McrlCarConfig octane = getConfig(r.w, 1);
    checkVec3(octane.hitboxSize, 2.41014, 0.773182, 1.733988);
    checkVec3(octane.hitboxOffset, 0.277514, 0.4151, 0);
    CHECK_NEAR(octane.wheelRadius[0], 0.25, CFG_TOL);
    CHECK_NEAR(octane.wheelRadius[1], 0.3, CFG_TOL);
    CHECK_NEAR(octane.suspensionRest[0], 0.7751, CFG_TOL);
    CHECK_NEAR(octane.suspensionRest[1], 0.7411, CFG_TOL);
    checkVec3(&octane.connection[0], 1.025, 0.4151, 0.518);
    checkVec3(&octane.connection[3], -0.675, 0.4151, 0.59);

    const McrlCarConfig dominus = getConfig(r.w, 2);
    checkVec3(dominus.hitboxSize, 2.60854, 0.676, 1.715598);
    checkVec3(dominus.hitboxOffset, 0.18, 0.315, 0);

    const McrlCarConfig psyclops = getConfig(r.w, 7);
    CHECK_NEAR(psyclops.connection[2], 0.1, CFG_TOL);
}

TEST_CASE("cars: bad team or preset") {
    Rig r;
    const McrlCarState s = carAt(0.5, FLOOR_TOP + CAR_REST, 0.5);
    const int32_t bad[][2] = {{2, 0}, {-1, 0}, {0, 7}, {0, -1}};
    for (const auto& tp : bad) {
        CAPTURE(tp[0]);
        CAPTURE(tp[1]);
        CHECK(mcrl_car_add(r.w, 1, tp[0], tp[1], &s) == MCRL_ERR_ARG);
        CHECK(std::string(mcrl_last_error()).find("mcrl_car_add") == 0);
        McrlCarState out{};
        CHECK(mcrl_car_get_state(r.w, 1, &out) == MCRL_ERR_NOT_FOUND);
    }
    REQUIRE(mcrl_car_add(r.w, 1, 1, 6, &s) == MCRL_OK);
    CHECK(getConfig(r.w, 1).preset == 6);

    McrlCarConfig c{};
    CHECK(mcrl_car_get_config(r.w, 99, &c) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_car_get_config(r.w, 1, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_car_add(r.w, 2, 0, 0, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_car_add(r.w, 1, 0, 0, &s) == MCRL_ERR_ARG);
}

TEST_CASE("cars: a restore keeps unlimited boost") {
    Rig r;
    REQUIRE(mcrl_world_set_unlimited_boost(r.w, 0) == MCRL_OK);
    McrlCarState c = carAt(0.5, FLOOR_TOP + CAR_REST, 0.5);
    c.boost = 10;
    REQUIRE(mcrl_car_add(r.w, 1, 0, 0, &c) == MCRL_OK);
    step(r.w, 5);
    REQUIRE(getCar(r.w, 1).boost == 10);
    REQUIRE(mcrl_world_set_unlimited_boost(r.w, 1) == MCRL_OK);
    REQUIRE(mcrl_debug_poison(r.w, 1) == MCRL_OK);
    uint32_t flags = 0;
    REQUIRE(mcrl_step(r.w, 1, &flags) == 1);
    REQUIRE((flags & MCRL_STEP_RESTORED) != 0);
    CHECK(getCar(r.w, 1).boost == 100);
}
