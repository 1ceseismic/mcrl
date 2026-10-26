#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <string>
#include <thread>
#include "mcrl.h"
#include "terrain_fixtures.h"

using namespace fx;

namespace {
void addCar(McrlWorld* w, double x, double y, double z, double vy = 0) {
    McrlCarState c = carAt(x, y, z);
    c.body.vy = vy;
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
}

}

TEST_CASE("fx: sizes and errors") {
    CHECK(sizeof(McrlWheelFx) == 48);
    CHECK(sizeof(McrlHitFx) == 40);
    CHECK(sizeof(McrlCarFx) == 400);
    CHECK(offsetof(McrlCarFx, bumps) == 228);
    CHECK(offsetof(McrlCarFx, bumpedBy) == 352);
    CHECK(offsetof(McrlCarFx, bumpSpeed) == 356);
    CHECK(sizeof(McrlBallFx) == 88);
    CHECK(mcrl_sizeof(6) == (int32_t)sizeof(McrlCarFx));
    CHECK(mcrl_sizeof(7) == (int32_t)sizeof(McrlBallFx));
    CHECK(mcrl_sizeof(11) == MCRL_ERR_ARG);

    McrlWorld* w = makeWorld(nullptr);
    addCar(w, 0.5, 100, 0.5);
    McrlCarFx f{};
    std::memset(&f, 0xAB, sizeof f);
    REQUIRE(mcrl_car_get_fx(w, 1, &f) == MCRL_OK);
    McrlCarFx zero{};
    zero.bumpedBy = -1;
    CHECK(std::memcmp(&f, &zero, sizeof f) == 0);
    CHECK(mcrl_car_get_fx(w, 2, &f) == MCRL_ERR_NOT_FOUND);
    CHECK(std::string(mcrl_last_error()).find("unknown car id") != std::string::npos);
    CHECK(mcrl_car_get_fx(w, 1, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_car_get_fx(nullptr, 1, &f) == MCRL_ERR_ARG);
    McrlBallFx b{};
    std::memset(&b, 0xAB, sizeof b);
    REQUIRE(mcrl_ball_get_fx(w, &b) == MCRL_OK);
    const McrlBallFx noTouch{0, -1, {}};
    CHECK(std::memcmp(&b, &noTouch, sizeof b) == 0);
    CHECK(mcrl_ball_get_fx(w, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_ball_get_fx(nullptr, &b) == MCRL_ERR_ARG);
    int32_t rcCar = 0, rcBall = 0;
    std::thread t([&] {
        rcCar = mcrl_car_get_fx(w, 1, &f);
        rcBall = mcrl_ball_get_fx(w, &b);
    });
    t.join();
    CHECK(rcCar == MCRL_ERR_THREAD);
    CHECK(rcBall == MCRL_ERR_THREAD);
    REQUIRE(mcrl_car_remove(w, 1) == MCRL_OK);
    CHECK(mcrl_car_get_fx(w, 1, &f) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
}
