#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>
#include "mcrl.h"
#include "terrain_fixtures.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;
constexpr double BALL_REST = 1.863;
constexpr double BALL_R = 91.25 / 50;
constexpr double HITBOX_FRONT = (13.8757 + 120.507 / 2) / 50;

Scene longFloor() { return Scene().fill(-16, 63, -16, 63, 63, 31, CUBE); }

void commitRows(McrlWorld* w, const McrlShapes* s, const Scene& sc, int32_t x0, int32_t x1, int32_t sz = 0) {
    sc.commitRange(w, s, x0, 3, sz, x1, 4, sz);
}

constexpr double BALL_MAX_SPIN = 6;

void rollBall(McrlWorld* w, double x, double z, double vx) {
    McrlBallState b{};
    b.body.px = x;
    b.body.py = FLOOR_TOP + BALL_REST;
    b.body.pz = z;
    b.body.qw = 1;
    b.body.vx = vx;
    b.body.wz = -std::clamp(vx / BALL_R, -BALL_MAX_SPIN, BALL_MAX_SPIN);
    REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
}

bool atRest(const McrlBody& b) {
    return b.vx == 0 && b.vy == 0 && b.vz == 0 && b.wx == 0 && b.wy == 0 && b.wz == 0;
}

bool samePos(const McrlBody& a, const McrlBody& b) {
    return a.px == b.px && a.py == b.py && a.pz == b.pz && a.qw == b.qw && a.qx == b.qx && a.qy == b.qy &&
           a.qz == b.qz;
}

bool frozen(uint32_t flags) { return (flags & MCRL_STEP_FROZEN) != 0; }

McrlBallState stepUntilParked(McrlWorld* w, int limit, McrlBallState& parked) {
    McrlBallState prev = getBall(w);
    for (int t = 0; t < limit; t++) {
        uint32_t flags = 0;
        REQUIRE(mcrl_step(w, 1, &flags) == 1);
        const McrlBallState b = getBall(w);
        if (flags & MCRL_STEP_BALL_PARKED) {
            parked = b;
            return prev;
        }
        prev = b;
    }
    FAIL("the ball never parked in ", limit, " ticks");
    return prev;
}
}

TEST_CASE("gate: a moving car freezes before entering an unready section") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    REQUIRE(mcrl_world_set_spawn_gate(w, 1) == MCRL_OK);
    const Scene sc = longFloor();
    commitRows(w, s, sc, 0, 1);
    McrlCarState c = carAt(20, FLOOR_TOP + CAR_REST, 8);
    c.body.vx = 20;
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    setControls(w, 1, 1);

    uint32_t flags = 0;
    const int32_t n = mcrl_step(w, 240, &flags);
    const McrlCarState o = getCar(w, 1);
    const double front = o.body.px + HITBOX_FRONT;
    const double reach = front + o.body.vx * 2 / 120 + 1;
    INFO("froze after ", n, " ticks: hitbox front ", front, ", vx ", o.body.vx, ", reach ", reach);
    CHECK(frozen(flags));
    CHECK(n > 0);
    CHECK(n < 240);
    CHECK(front < 32);
    CHECK(reach >= 32 - 0.02);
    CHECK(reach - o.body.vx / 120 < 32 + 0.02);
    CHECK(mcrl_step(w, 5, &flags) == 0);
    CHECK(frozen(flags));
    CHECK(getCar(w, 1).body.px == o.body.px);

    commitRows(w, s, sc, 2, 2);
    CHECK(mcrl_step(w, 30, &flags) == 30);
    CHECK(!frozen(flags));
    CHECK(getCar(w, 1).body.px + HITBOX_FRONT > 32);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("ball: parks at unready terrain and resumes with its velocity") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    REQUIRE(mcrl_world_set_spawn_gate(w, 1) == MCRL_OK);
    const Scene sc = longFloor();
    commitRows(w, s, sc, 0, 1);
    rollBall(w, 28.5, 8, 15);

    McrlBallState parked{};
    const McrlBallState saved = stepUntilParked(w, 120, parked);
    const double reach = saved.body.px + BALL_R + saved.body.vx * 2 / 120 + 1;
    INFO("parked at x ", parked.body.px, ", saved vx ", saved.body.vx, ", reach ", reach);
    CHECK(std::abs(saved.body.vx - 15) < 0.5);
    CHECK(reach >= 32 - 0.01);
    CHECK(reach - saved.body.vx / 120 < 32);
    CHECK(atRest(parked.body));
    CHECK(parked.body.px < 32 - BALL_R);

    uint32_t flags = 0;
    CHECK(mcrl_step(w, 60, &flags) == 60);
    CHECK(flags == MCRL_STEP_BALL_PARKED);
    const McrlBallState held = getBall(w);
    CHECK(samePos(held.body, parked.body));
    CHECK(atRest(held.body));

    commitRows(w, s, sc, 2, 2);
    CHECK(mcrl_step(w, 1, &flags) == 1);
    CHECK(flags == 0);
    const McrlBallState resumed = getBall(w);
    INFO("resumed vx ", resumed.body.vx, " (saved ", saved.body.vx, "), wz ", resumed.body.wz, " (saved ",
         saved.body.wz, ")");
    CHECK(std::abs(resumed.body.vx - 15) < 0.5);
    CHECK(std::abs(resumed.body.vx - saved.body.vx) < 0.1);
    CHECK(std::abs(resumed.body.wz - saved.body.wz) < 0.05);
    CHECK(resumed.body.px > parked.body.px);
    CHECK(mcrl_step(w, 60, &flags) == 60);
    CHECK(flags == 0);
    CHECK(getBall(w).body.px > 33);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("readiness: per-car gate holds only the unready car") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    Scene().fill(-32, 63, -32, 47, 63, 47, CUBE).commitRange(w, s, -2, 3, -2, 2, 4, 2);
    McrlCarState c1 = carAt(8, FLOOR_TOP + CAR_REST, 8);
    c1.body.vx = 15;
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c1) == MCRL_OK);
    setControls(w, 1, 1, 1);
    rollBall(w, -28, -29, 5.5);
    REQUIRE(mcrl_world_set_gate_mode(w, 1) == MCRL_OK);
    REQUIRE(mcrl_world_set_spawn_gate(w, 1) == MCRL_OK);
    McrlCarState c2 = carAt(8.5, 200, 8.5);
    c2.body.vy = -5;
    REQUIRE(mcrl_car_add(w, 2, 0, 0, &c2) == MCRL_OK);
    const McrlCarState set2 = getCar(w, 2);

    uint32_t flags = 0;
    CHECK(mcrl_step(w, 60, &flags) == 60);
    CHECK(frozen(flags));
    const McrlCarState held = getCar(w, 2);
    CHECK(std::memcmp(&held.body, &set2.body, sizeof held.body) == 0);
    CHECK((held.flags & MCRL_CAR_HELD) != 0);
    CHECK((getCar(w, 1).flags & MCRL_CAR_HELD) == 0);
    const McrlCarState o1 = getCar(w, 1);
    const double moved = std::hypot(o1.body.px - c1.body.px, o1.body.pz - c1.body.pz);
    CHECK(moved > 5);
    CHECK(speed(getBall(w).body) > 1);

    Scene().commitRange(w, s, -1, 11, -1, 1, 13, 1);
    CHECK(mcrl_step(w, 1, &flags) == 1);
    CHECK(!frozen(flags));
    const McrlCarState free2 = getCar(w, 2);
    CHECK((free2.flags & MCRL_CAR_HELD) == 0);
    CHECK(free2.body.vy < -5.0);
    CHECK(free2.body.vy > -5.2);

    REQUIRE(mcrl_world_set_gate_mode(w, 0) == MCRL_OK);
    CHECK(mcrl_world_set_gate_mode(nullptr, 1) == MCRL_ERR_ARG);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
