#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include "mcrl.h"
#include "terrain_fixtures.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double BALL_REST = 1.863;

McrlWorld* seamWorld(const McrlShapes* s) {
    McrlWorld* w = makeWorld(s);
    Scene().fill(-16, 63, -16, 95, 63, 31, CUBE).commitRange(w, s, -1, 3, -1, 5, 5, 1);
    return w;
}
}

TEST_CASE("seams: ball rolls across section borders without a hop") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = seamWorld(s);
    McrlBallState b{};
    b.body.px = 2;
    b.body.py = FLOOR_TOP + BALL_REST;
    b.body.pz = 8.5;
    b.body.qw = 1;
    b.body.vx = 30;
    REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
    double maxVy = 0, worstPx = 0;
    int worstTick = -1;
    for (int t = 1; t <= 240; t++) {
        step(w, 1);
        const McrlBallState o = getBall(w);
        if (t > 10 && std::abs(o.body.vy) > maxVy) {
            maxVy = std::abs(o.body.vy);
            worstTick = t;
            worstPx = o.body.px;
        }
    }
    const McrlBallState o = getBall(w);
    INFO("max |vy| ", maxVy, " blocks/s at tick ", worstTick, " (x ", worstPx, "); end (", o.body.px, ", ",
         o.body.py, ", ", o.body.pz, "), vx ", o.body.vx);
    CHECK(o.body.px > 48);
    CHECK(maxVy <= 0.02);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("seams: car lands on a section corner without a kick") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = seamWorld(s);
    const McrlCarState c = carAt(16, 65.2, 16);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    int contactTick = -1;
    for (int t = 1; t <= 120 && contactTick < 0; t++) {
        step(w, 1);
        if (getCar(w, 1).wheelContact != 0) contactTick = t;
    }
    REQUIRE(contactTick > 0);
    step(w, 60);
    const McrlCarState o = getCar(w, 1);
    const double spin = std::sqrt(o.body.wx * o.body.wx + o.body.wy * o.body.wy + o.body.wz * o.body.wz);
    const double drift = std::hypot(o.body.px - 16, o.body.pz - 16);
    INFO("first contact at tick ", contactTick, "; 60 ticks later (", o.body.px, ", ", o.body.py, ", ", o.body.pz,
         "), |w| ", spin, " rad/s, drift ", drift, " blocks, wheels ", o.wheelContact);
    CHECK(spin < 0.05);
    CHECK(drift < 0.02);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("seams: a ball dropped beside a section border lands without a sideways kick") {
    const double spots[][2] = {{15.9, 8.5}, {16.3, 8.5}, {16.5, 8.5}, {8.5, 15.7}, {8.5, 16.1}, {8.5, 16.4}};
    McrlShapes* s = makeShapes();
    for (const auto& p : spots) {
        CAPTURE(p[0]);
        CAPTURE(p[1]);
        McrlWorld* w = seamWorld(s);
        setBall(w, p[0], 76, p[1]);
        int contactTick = -1;
        double prevVy = 0;
        for (int t = 1; t <= 240 && contactTick < 0; t++) {
            step(w, 1);
            const double vy = getBall(w).body.vy;
            if (vy > prevVy) contactTick = t;
            prevVy = vy;
        }
        REQUIRE(contactTick > 0);
        double maxH = 0;
        for (int t = 0; t <= 60; t++) {
            if (t > 0) step(w, 1);
            const McrlBallState o = getBall(w);
            maxH = std::max(maxH, std::hypot(o.body.vx, o.body.vz));
        }
        const McrlBallState o = getBall(w);
        INFO("first contact at tick ", contactTick, "; max horizontal speed ", maxH, " blocks/s; end (", o.body.px,
             ", ", o.body.py, ", ", o.body.pz, ")");
        CHECK(maxH <= 0.02);
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
    }
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
