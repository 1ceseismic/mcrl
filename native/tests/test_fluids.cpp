#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>
#include "mcrl.h"
#include "terrain_fixtures.h"
#include "world.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;

constexpr uint8_t full(uint8_t type) { return (uint8_t)(type | 9 << 2); }
constexpr double SURFACE = 69 + 8.0 / 9;

Scene strip(uint8_t type = 0) {
    Scene sc = Scene().fill(-16, 63, -16, 127, 63, 31, CUBE);
    if (type != 0)
        sc.fluid(-16, 64, -16, 127, 68, 31, full(type)).fluid(-16, 69, -16, 127, 69, 31, (uint8_t)(type | 8 << 2));
    return sc;
}

struct Pool {
    McrlShapes* shapes = nullptr;
    McrlWorld* w = nullptr;
    explicit Pool(const Scene& sc, uint32_t extras = MCRL_EXTRAS_FLUIDS, int32_t ox = 0) {
        shapes = makeShapes();
        w = makeWorld(shapes, ox);
        sc.commitRange(w, shapes, -1, 3, -1, 7, 5, 1);
        REQUIRE(mcrl_world_set_extras(w, extras) == MCRL_OK);
    }
    ~Pool() {
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
        CHECK(mcrl_shapes_destroy(shapes) == MCRL_OK);
    }
    Pool(const Pool&) = delete;
    Pool& operator=(const Pool&) = delete;

    void addCar(const McrlCarState& c, float throttle = 0) {
        REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
        setControls(w, 1, throttle);
    }
    McrlWetFx carWet() const {
        McrlCarFx f{};
        REQUIRE(mcrl_car_get_fx(w, 1, &f) == MCRL_OK);
        return f.wet;
    }
    McrlWetFx ballWet() const {
        McrlBallFx f{};
        REQUIRE(mcrl_ball_get_fx(w, &f) == MCRL_OK);
        return f.wet;
    }
};

McrlCarState carIn(double x, double y, double z, double vx = 0, double vy = 0) {
    McrlCarState c = carAt(x, y, z);
    c.body.vx = vx;
    c.body.vy = vy;
    return c;
}

}

TEST_CASE("fluids: the ball floats") {
    Pool p(strip(MCRL_FLUID_WATER));
    setBall(p.w, 8.5, 66, 8.5);
    step(p.w, 2400);
    const McrlBallState b = getBall(p.w);
    CHECK(std::abs(b.body.py - SURFACE) <= 0.05);
    CHECK(std::abs(b.body.vy) <= 0.05);
    const McrlWetFx wet = p.ballWet();
    CHECK(wet.fluid == (uint32_t)MCRL_FLUID_WATER);
    CHECK(std::abs(wet.immersion - 0.5) < 0.05);
}

TEST_CASE("fluids: the car sinks at about 2.2 blocks/s and drives on the bottom") {
    Pool p(strip(MCRL_FLUID_WATER));
    p.addCar(carIn(0.5, 68, 0.5));
    step(p.w, 180);
    const McrlCarState sinking = getCar(p.w, 1);
    CHECK(sinking.body.vy >= -2.3);
    CHECK(sinking.body.vy <= -1.8);
    CHECK(sinking.wheelContact == 0u);
    int t = 180;
    while (getCar(p.w, 1).wheelContact != 0xFu) {
        REQUIRE(t < 600);
        step(p.w, 1);
        t++;
    }
    setControls(p.w, 1, 1);
    step(p.w, 480);
    const McrlCarState o = getCar(p.w, 1);
    CHECK(speed(o.body) >= 5);
    CHECK(speed(o.body) <= 11);
    CHECK(p.carWet().immersion == 1.f);

    Pool dry(strip(MCRL_FLUID_WATER), MCRL_EXTRAS_SURFACES);
    dry.addCar(carIn(0.5, 68, 0.5));
    step(dry.w, t);
    setControls(dry.w, 1, 1);
    step(dry.w, 480);
    CHECK(speed(getCar(dry.w, 1).body) > 20);
    CHECK(dry.carWet().immersion == 0.f);
}

TEST_CASE("fluids: a wreck reads dry") {
    Pool p(strip(MCRL_FLUID_LAVA));
    p.addCar(carIn(0.5, FLOOR_TOP + CAR_REST, 0.5));
    step(p.w, 30);
    CHECK(p.carWet().fluid == (uint32_t)MCRL_FLUID_LAVA);
    REQUIRE(mcrl_car_demolish(p.w, 1) == MCRL_OK);
    step(p.w, 1);
    const McrlWetFx wet = p.carWet();
    CHECK(wet.fluid == 0u);
    CHECK(wet.immersion == 0.f);
}

TEST_CASE("fluids: extras 0 is bit-identical") {
    Pool pool(strip(MCRL_FLUID_WATER), 0), plain(strip(), 0);
    for (Pool* p : {&pool, &plain}) {
        p->addCar(carIn(0.5, 68, 0.5), 1);
        setBall(p->w, 12.5, 70, 0.5);
    }
    for (int t = 0; t < 600; t++) {
        step(pool.w, 1);
        step(plain.w, 1);
        const McrlCarState ca = getCar(pool.w, 1), cb = getCar(plain.w, 1);
        const McrlBallState ba = getBall(pool.w), bb = getBall(plain.w);
        REQUIRE_MESSAGE(std::memcmp(&ca, &cb, sizeof ca) == 0, t);
        REQUIRE_MESSAGE(std::memcmp(&ba, &bb, sizeof ba) == 0, t);
        McrlCarFx fa{}, fb{};
        REQUIRE(mcrl_car_get_fx(pool.w, 1, &fa) == MCRL_OK);
        REQUIRE(mcrl_car_get_fx(plain.w, 1, &fb) == MCRL_OK);
        REQUIRE_MESSAGE(std::memcmp(&fa, &fb, sizeof fa) == 0, t);
    }
    CHECK(getCar(pool.w, 1).ballHits > 0u);
}
