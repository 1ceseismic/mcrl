#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <limits>
#include <string>
#include <thread>
#include "mcrl.h"
#include "terrain_fixtures.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;
constexpr double BALL_REST = 1.863;
constexpr int MAX_TICKS = 60;

#define CHECK_NEAR(a, b, tol) CHECK_MESSAGE(std::abs((double)(a) - (double)(b)) <= (tol), #a " = ", (a), ", want ", (b))

struct Rig {
    McrlShapes* s = nullptr;
    McrlWorld* w = nullptr;

    Rig() {
        s = makeShapes();
        w = makeWorld(s);
        Scene().fill(-32, 63, -32, 47, 63, 47, CUBE).commitRange(w, s, -2, 3, -2, 2, 4, 2);
        setBall(w, 40.5, FLOOR_TOP + BALL_REST, 40.5);
    }
    ~Rig() {
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
        CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
    }
    Rig(const Rig&) = delete;
    Rig& operator=(const Rig&) = delete;
};

void addCars(McrlWorld* w, int32_t team1, int32_t team2, double v1 = 45, bool boost = true) {
    McrlCarState c1 = carAt(-20.5, FLOOR_TOP + CAR_REST, 8.5);
    c1.body.vx = v1;
    REQUIRE(mcrl_car_add(w, 1, team1, 0, &c1) == MCRL_OK);
    setControls(w, 1, 1, 0, boost);
    const McrlCarState c2 = carAt(-8.5, FLOOR_TOP + CAR_REST, 8.5, 90);
    REQUIRE(mcrl_car_add(w, 2, team2, 0, &c2) == MCRL_OK);
}

McrlCarFx getFx(McrlWorld* w, int32_t id) {
    McrlCarFx f{};
    REQUIRE(mcrl_car_get_fx(w, id, &f) == MCRL_OK);
    return f;
}

bool demoed(McrlWorld* w, int32_t id) { return (getCar(w, id).flags & MCRL_CAR_DEMOED) != 0; }

int untilDemoed(McrlWorld* w, int32_t id, int maxTicks = MAX_TICKS) {
    for (int t = 1; t <= maxTicks; t++) {
        step(w, 1);
        if (demoed(w, id)) return t;
    }
    return -1;
}

double horizontalSpeed(const McrlBody& b) { return std::sqrt(b.vx * b.vx + b.vz * b.vz); }

struct BumpRun {
    int firstBump = -1;
    double maxSpeedAfter = 0;
};
BumpRun bumpOnly(McrlWorld* w) {
    BumpRun r;
    for (int t = 1; t <= MAX_TICKS; t++) {
        step(w, 1);
        CHECK_FALSE(demoed(w, 2));
        if (r.firstBump < 0 && getFx(w, 2).bumps > 0) r.firstBump = t;
        if (r.firstBump > 0 && t <= r.firstBump + 5)
            r.maxSpeedAfter = std::max(r.maxSpeedAfter, horizontalSpeed(getCar(w, 2).body));
    }
    return r;
}
}

TEST_CASE("demos: supersonic bumper hit demolishes") {
    Rig r;
    REQUIRE(mcrl_world_set_demos(r.w, 1, 1) == MCRL_OK);
    addCars(r.w, 0, 0);
    const int t = untilDemoed(r.w, 2);
    REQUIRE(t > 0);
    CHECK(t <= 40);
    CHECK_FALSE(demoed(r.w, 1));
    const McrlCarFx f2 = getFx(r.w, 2);
    CHECK(f2.bumps == 1);
    CHECK(f2.bumpedBy == 1);
    CHECK(f2.bumpSpeed >= 2200);
    const McrlCarFx f1 = getFx(r.w, 1);
    CHECK(f1.bumps == 0);
    CHECK(f1.bumpedBy == -1);
    CHECK(f1.bumpSpeed == 0);
}

TEST_CASE("demos: same team without team demos is a bump") {
    Rig r;
    REQUIRE(mcrl_world_set_demos(r.w, 1, 0) == MCRL_OK);
    addCars(r.w, 0, 0);
    const BumpRun b = bumpOnly(r.w);
    REQUIRE(b.firstBump > 0);
    CHECK(getFx(r.w, 2).bumps >= 1);
    CHECK(getFx(r.w, 2).bumpedBy == 1);
    CHECK(b.maxSpeedAfter > 5);
}

TEST_CASE("demos: mode 0 never, mode 2 on contact") {
    SUBCASE("mode 0 at 45 blocks/s") {
        Rig r;
        REQUIRE(mcrl_world_set_demos(r.w, 0, 1) == MCRL_OK);
        addCars(r.w, 0, 1);
        const BumpRun b = bumpOnly(r.w);
        CHECK(b.firstBump > 0);
        CHECK(b.maxSpeedAfter > 5);
    }
    SUBCASE("mode 2 at 10 blocks/s") {
        Rig r;
        REQUIRE(mcrl_world_set_demos(r.w, 2, 1) == MCRL_OK);
        addCars(r.w, 0, 0, 10, false);
        const int t = untilDemoed(r.w, 2, 2 * MAX_TICKS);
        CHECK(t > 0);
        CHECK(getFx(r.w, 2).bumpSpeed < 2200);
    }
}

TEST_CASE("demos: respawn") {
    Rig r;
    REQUIRE(mcrl_world_set_demos(r.w, 1, 1) == MCRL_OK);
    addCars(r.w, 0, 0);
    REQUIRE(untilDemoed(r.w, 2) > 0);
    REQUIRE(mcrl_car_remove(r.w, 1) == MCRL_OK);

    McrlCarState at = carAt(20.5, FLOOR_TOP + CAR_REST, 8.5);
    at.boost = 7;
    REQUIRE(mcrl_car_respawn(r.w, 2, &at) == MCRL_OK);
    McrlCarState c = getCar(r.w, 2);
    CHECK((c.flags & MCRL_CAR_DEMOED) == 0);
    CHECK_NEAR(c.body.px, at.body.px, 1e-4);
    CHECK_NEAR(c.body.py, at.body.py, 1e-4);
    CHECK_NEAR(c.body.pz, at.body.pz, 1e-4);
    CHECK_NEAR(c.body.qw, 1, 1e-6);
    CHECK_NEAR(speed(c.body), 0, 1e-6);
    CHECK(c.boost == 100);
    for (int t = 0; t < 120; t++) step(r.w, 1);
    c = getCar(r.w, 2);
    CHECK((c.flags & MCRL_CAR_DEMOED) == 0);
    CHECK(c.wheelContact == 0b1111);
    CHECK_NEAR(c.body.py, FLOOR_TOP + CAR_REST, 0.02);

    setControls(r.w, 2, 0, 0, false, true);
    for (int t = 0; t < 10; t++) step(r.w, 1);
    REQUIRE((getCar(r.w, 2).flags & MCRL_CAR_JUMPED) != 0);
    McrlCarState turned = carAt(20.5, FLOOR_TOP + CAR_REST, 8.5, 90);
    turned.body.vx = 5;
    turned.body.wy = 1;
    REQUIRE(mcrl_car_respawn(r.w, 2, &turned) == MCRL_OK);
    c = getCar(r.w, 2);
    CHECK((c.flags & (MCRL_CAR_JUMPED | MCRL_CAR_DEMOED)) == 0);
    CHECK_NEAR(c.body.py, turned.body.py, 1e-4);
    CHECK_NEAR(c.body.qw, turned.body.qw, 1e-6);
    CHECK_NEAR(c.body.qy, turned.body.qy, 1e-6);
    CHECK_NEAR(c.body.vx, 5, 1e-4);
    CHECK_NEAR(c.body.vy, 0, 1e-6);
    CHECK_NEAR(c.body.wy, 1, 1e-6);

    REQUIRE(mcrl_world_set_unlimited_boost(r.w, 0) == MCRL_OK);
    REQUIRE(mcrl_car_respawn(r.w, 2, &at) == MCRL_OK);
    const float spawnBoost = getCar(r.w, 2).boost;
    CHECK_NEAR(spawnBoost, 100.0 / 3, 1e-3);
}

TEST_CASE("demos: args") {
    Rig r;
    addCars(r.w, 0, 0);
    const McrlCarFx f = getFx(r.w, 2);
    CHECK(f.bumps == 0);
    CHECK(f.bumpedBy == -1);
    CHECK(f.bumpSpeed == 0);

    CHECK(mcrl_world_set_demos(r.w, 3, 0) == MCRL_ERR_ARG);
    CHECK(std::string(mcrl_last_error()).find("mcrl_world_set_demos") == 0);
    CHECK(mcrl_world_set_demos(r.w, -1, 0) == MCRL_ERR_ARG);
    CHECK(mcrl_car_demolish(r.w, 99) == MCRL_ERR_NOT_FOUND);
    const McrlCarState s = carAt(0.5, FLOOR_TOP + CAR_REST, 0.5);
    CHECK(mcrl_car_respawn(r.w, 99, &s) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_car_respawn(r.w, 2, nullptr) == MCRL_ERR_ARG);
    McrlCarState bad = s;
    bad.body.px = std::numeric_limits<double>::quiet_NaN();
    CHECK(mcrl_car_respawn(r.w, 2, &bad) == MCRL_ERR_ARG);
    CHECK_FALSE(demoed(r.w, 2));

    CHECK(mcrl_car_demolish(r.w, 2) == MCRL_OK);
    CHECK(demoed(r.w, 2));
    CHECK(mcrl_car_demolish(r.w, 2) == MCRL_OK);
    CHECK(demoed(r.w, 2));

    CHECK(mcrl_world_set_demos(nullptr, 1, 1) == MCRL_ERR_ARG);
    CHECK(mcrl_car_demolish(nullptr, 1) == MCRL_ERR_ARG);
    CHECK(mcrl_car_respawn(nullptr, 1, &s) == MCRL_ERR_ARG);
    int32_t rcDemos = 0, rcDemolish = 0, rcRespawn = 0;
    std::thread t([&] {
        rcDemos = mcrl_world_set_demos(r.w, 1, 1);
        rcDemolish = mcrl_car_demolish(r.w, 1);
        rcRespawn = mcrl_car_respawn(r.w, 2, &s);
    });
    t.join();
    CHECK(rcDemos == MCRL_ERR_THREAD);
    CHECK(rcDemolish == MCRL_ERR_THREAD);
    CHECK(rcRespawn == MCRL_ERR_THREAD);
    CHECK_FALSE(demoed(r.w, 1));
    CHECK(demoed(r.w, 2));
}

TEST_CASE("demos: a held car does not dodge a demolition") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    Scene().fill(-32, 63, -32, 47, 63, 47, CUBE).commitRange(w, s, -2, 3, -2, 0, 4, 2);
    setBall(w, -20.5, FLOOR_TOP + BALL_REST, -20.5);
    REQUIRE(mcrl_world_set_demos(w, 1, 1) == MCRL_OK);
    REQUIRE(mcrl_world_set_gate_mode(w, 1) == MCRL_OK);
    REQUIRE(mcrl_world_set_spawn_gate(w, 1) == MCRL_OK);
    McrlCarState c1 = carAt(2.5, FLOOR_TOP + CAR_REST, 8.5);
    c1.body.vx = 45;
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c1) == MCRL_OK);
    setControls(w, 1, 1, 0, true);
    const McrlCarState c2 = carAt(14.5, FLOOR_TOP + CAR_REST, 8.5, 90);
    REQUIRE(mcrl_car_add(w, 2, 0, 0, &c2) == MCRL_OK);
    const McrlBody set2 = getCar(w, 2).body;

    int hit = -1;
    for (int t = 1; t <= MAX_TICKS && hit < 0; t++) {
        step(w, 1);
        const McrlCarState o2 = getCar(w, 2);
        if (getFx(w, 2).bumps > 0) {
            hit = t;
        } else {
            REQUIRE((o2.flags & MCRL_CAR_HELD) != 0);
            REQUIRE((getCar(w, 1).flags & MCRL_CAR_HELD) == 0);
        }
    }
    REQUIRE(hit > 0);
    CHECK(demoed(w, 2));
    step(w, 10);
    const McrlCarState o2 = getCar(w, 2);
    CHECK((o2.flags & MCRL_CAR_DEMOED) != 0);
    CHECK((o2.flags & MCRL_CAR_HELD) == 0);
    CHECK(o2.body.px == set2.px);
    CHECK(o2.body.py == set2.py);
    CHECK(o2.body.pz == set2.pz);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
