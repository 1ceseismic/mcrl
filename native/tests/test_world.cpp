#include <doctest/doctest.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>
#include <thread>
#include "mcrl.h"
#include "terrain_fixtures.h"

namespace {
McrlBody bodyAt(double x, double y, double z) {
    McrlBody b{};
    b.px = x; b.py = y; b.pz = z;
    b.qw = 1;
    return b;
}

McrlCarState carAt(double x, double y, double z) {
    McrlCarState s{};
    s.body = bodyAt(x, y, z);
    return s;
}

McrlWorld* makeWorld() {
    REQUIRE(mcrl_init() == MCRL_OK);
    McrlWorld* w = mcrl_world_create(nullptr, 0, 0, 0);
    REQUIRE(w != nullptr);
    return w;
}

McrlCarState getCar(McrlWorld* w, int32_t id) {
    McrlCarState s{};
    REQUIRE(mcrl_car_get_state(w, id, &s) == MCRL_OK);
    return s;
}

float steerAfterOneTick(float steer) {
    McrlWorld* w = makeWorld();
    McrlCarState s = carAt(0, 100, 0);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &s) == MCRL_OK);
    McrlControls c{};
    c.steer = steer;
    REQUIRE(mcrl_car_set_controls(w, 1, &c) == MCRL_OK);
    REQUIRE(mcrl_step(w, 1, nullptr) == 1);
    const float a = getCar(w, 1).steerAngle;
    REQUIRE(mcrl_world_destroy(w) == MCRL_OK);
    return a;
}
}

TEST_CASE("sizeof") {
    CHECK(mcrl_sizeof(0) == (int32_t)sizeof(McrlBody));
    CHECK(mcrl_sizeof(1) == (int32_t)sizeof(McrlCarState));
    CHECK(mcrl_sizeof(2) == (int32_t)sizeof(McrlBallState));
    CHECK(mcrl_sizeof(3) == (int32_t)sizeof(McrlControls));
    CHECK(sizeof(McrlBody) == 88);
    CHECK(sizeof(McrlCarState) == 168);
    CHECK(sizeof(McrlBallState) == 96);
    CHECK(sizeof(McrlControls) == 24);
    CHECK(mcrl_sizeof(11) == MCRL_ERR_ARG);
    CHECK(mcrl_sizeof(-1) == MCRL_ERR_ARG);
}

TEST_CASE("ball_state_roundtrip") {
    McrlWorld* w = makeWorld();
    McrlBallState b{};
    b.body = bodyAt(3.25, 40.5, -7.75);
    b.body.vx = 4; b.body.vy = 2; b.body.vz = -1;
    b.body.wx = 0.5; b.body.wy = -1.5; b.body.wz = 2;
    REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
    McrlBallState o{};
    REQUIRE(mcrl_ball_get_state(w, &o) == MCRL_OK);
    CHECK(o.body.px == doctest::Approx(3.25));
    CHECK(o.body.py == doctest::Approx(40.5));
    CHECK(o.body.pz == doctest::Approx(-7.75));
    CHECK(o.body.vx == doctest::Approx(4));
    CHECK(o.body.vy == doctest::Approx(2));
    CHECK(o.body.vz == doctest::Approx(-1));
    CHECK(o.body.wx == doctest::Approx(0.5));
    CHECK(o.body.wy == doctest::Approx(-1.5));
    CHECK(o.body.wz == doctest::Approx(2));
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
}

TEST_CASE("car_state_roundtrip") {
    McrlWorld* w = makeWorld();
    McrlCarState s = carAt(-12.5, 70.25, 33.0);
    s.body.qw = 0.70105738f; s.body.qx = 0.09229596f; s.body.qy = -0.70105738f; s.body.qz = 0.09229596f;
    s.body.vx = 10; s.body.vy = -3; s.body.vz = 5;
    s.body.wx = 1; s.body.wy = 0.25; s.body.wz = -0.5;
    REQUIRE(mcrl_car_add(w, 3, 0, 0, &s) == MCRL_OK);
    McrlCarState o = getCar(w, 3);
    CHECK(o.body.px == doctest::Approx(-12.5));
    CHECK(o.body.py == doctest::Approx(70.25));
    CHECK(o.body.pz == doctest::Approx(33.0));
    const double dot = (double)o.body.qw * s.body.qw + (double)o.body.qx * s.body.qx +
                       (double)o.body.qy * s.body.qy + (double)o.body.qz * s.body.qz;
    CHECK(std::abs(dot) > 1 - 1e-5);
    CHECK(o.body.vx == doctest::Approx(10));
    CHECK(o.body.vy == doctest::Approx(-3));
    CHECK(o.body.vz == doctest::Approx(5));
    CHECK(o.body.wx == doctest::Approx(1));
    CHECK(o.body.wy == doctest::Approx(0.25));
    CHECK(o.body.wz == doctest::Approx(-0.5));
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
}

TEST_CASE("controls_clamped_and_nan_zeroed") {
    const float right = steerAfterOneTick(1);
    CHECK(right == doctest::Approx(-0.53356f).epsilon(1e-3));
    CHECK(steerAfterOneTick(7) == right);
    CHECK(steerAfterOneTick(-1) == doctest::Approx(-right));
    CHECK(steerAfterOneTick(std::numeric_limits<float>::quiet_NaN()) == 0);
    CHECK(steerAfterOneTick(0) == 0);
}

TEST_CASE("thread_guard") {
    McrlWorld* w = makeWorld();
    McrlCarState s = carAt(0, 100, 0);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &s) == MCRL_OK);
    int32_t rcStep = 0, rcAdd = 0, rcGet = 0, rcBall = 0, rcCtl = 0, rcDestroy = 0;
    std::string err;
    std::thread t([&] {
        rcStep = mcrl_step(w, 1, nullptr);
        err = mcrl_last_error();
        McrlCarState tmp = carAt(0, 0, 0);
        rcAdd = mcrl_car_add(w, 2, 0, 0, &tmp);
        rcGet = mcrl_car_get_state(w, 1, &tmp);
        McrlBallState b{};
        rcBall = mcrl_ball_get_state(w, &b);
        McrlControls c{};
        rcCtl = mcrl_car_set_controls(w, 1, &c);
        rcDestroy = mcrl_world_destroy(w);
    });
    t.join();
    CHECK(rcStep == MCRL_ERR_THREAD);
    CHECK(err.find("owner thread") != std::string::npos);
    CHECK(rcAdd == MCRL_ERR_THREAD);
    CHECK(rcGet == MCRL_ERR_THREAD);
    CHECK(rcBall == MCRL_ERR_THREAD);
    CHECK(rcCtl == MCRL_ERR_THREAD);
    CHECK(rcDestroy == MCRL_ERR_THREAD);
    CHECK(mcrl_step(w, 1, nullptr) == 1);
    CHECK(std::string(mcrl_last_error()) == "");
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
}

TEST_CASE("ids_and_args") {
    McrlWorld* w = makeWorld();
    McrlCarState s = carAt(0, 100, 0);
    REQUIRE(mcrl_car_add(w, 5, 0, 0, &s) == MCRL_OK);
    CHECK(mcrl_car_add(w, 5, 0, 0, &s) == MCRL_ERR_ARG);
    CHECK(std::string(mcrl_last_error()).find("duplicate") != std::string::npos);
    CHECK(mcrl_car_add(w, 6, 0, 0, nullptr) == MCRL_ERR_ARG);
    McrlCarState bad = s;
    bad.body.py = std::numeric_limits<double>::quiet_NaN();
    CHECK(mcrl_car_add(w, 6, 0, 0, &bad) == MCRL_ERR_ARG);
    bad = s;
    bad.body.qw = 0;
    CHECK(mcrl_car_set_state(w, 5, &bad) == MCRL_ERR_ARG);
    CHECK(mcrl_car_get_state(w, 6, &s) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_car_get_state(w, 5, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_car_set_controls(w, 99, nullptr) == MCRL_ERR_ARG);
    McrlControls c{};
    CHECK(mcrl_car_set_controls(w, 99, &c) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_car_set_state(w, 99, &s) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_car_remove(w, 5) == MCRL_OK);
    CHECK(mcrl_car_remove(w, 5) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_car_add(w, 5, 0, 0, &s) == MCRL_OK);
    CHECK(mcrl_ball_set_state(w, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_ball_get_state(w, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_step(w, -1, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_step(w, 0, nullptr) == 0);
    CHECK(mcrl_step(nullptr, 1, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_world_destroy(nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
}

TEST_CASE("determinism") {
    McrlWorld* a = makeWorld();
    McrlWorld* b = makeWorld();
    McrlCarState s = carAt(0, 100, 0);
    s.body.vx = 5; s.body.vz = 3;
    s.body.wy = 1; s.body.wx = 0.5;
    REQUIRE(mcrl_car_add(a, 1, 0, 0, &s) == MCRL_OK);
    REQUIRE(mcrl_car_add(b, 1, 0, 0, &s) == MCRL_OK);
    for (int t = 0; t < 600; t++) {
        McrlControls c{};
        c.throttle = 1;
        c.steer = ((t / 30) % 2 == 0) ? 1.f : -1.f;
        c.jump = (t == 90) ? 1 : 0;
        REQUIRE(mcrl_car_set_controls(a, 1, &c) == MCRL_OK);
        REQUIRE(mcrl_car_set_controls(b, 1, &c) == MCRL_OK);
        REQUIRE(mcrl_step(a, 1, nullptr) == 1);
        REQUIRE(mcrl_step(b, 1, nullptr) == 1);
    }
    McrlCarState sa = getCar(a, 1), sb = getCar(b, 1);
    CHECK(std::memcmp(&sa, &sb, sizeof sa) == 0);
    CHECK(sa.body.py < 0);
    McrlBallState ba{}, bb{};
    REQUIRE(mcrl_ball_get_state(a, &ba) == MCRL_OK);
    REQUIRE(mcrl_ball_get_state(b, &bb) == MCRL_OK);
    CHECK(std::memcmp(&ba, &bb, sizeof ba) == 0);
    CHECK(mcrl_world_destroy(a) == MCRL_OK);
    CHECK(mcrl_world_destroy(b) == MCRL_OK);
}
