#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <vector>
#include "mcrl.h"
#include "terrain_fixtures.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double BALL_REST = 1.863;

struct Rig {
    McrlShapes* shapes = makeShapes();
    McrlWorld* w = makeWorld(shapes);
    Rig() { commitStandard(w, shapes, floorScene()); }
    ~Rig() {
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
        CHECK(mcrl_shapes_destroy(shapes) == MCRL_OK);
    }
};

}

TEST_CASE("events: rolling is not bouncing") {
    Rig r;
    setBall(r.w, 0, FLOOR_TOP + BALL_REST, 0);
    step(r.w, 120);
    McrlBallState b = getBall(r.w);
    b.body.vx = 20;
    REQUIRE(mcrl_ball_set_state(r.w, &b) == MCRL_OK);
    const uint32_t before = getBall(r.w).bounces;
    step(r.w, 120);
    CHECK(getBall(r.w).bounces == before);
    CHECK(getBall(r.w).body.px > 10);
}

namespace {
constexpr uint16_t TOP_SLAB = CARPET + 1, FENCE_X = CARPET + 2;
using World = uint16_t (*)(int32_t x, int32_t y, int32_t z);

McrlShapes* subBlockShapes() {
    McrlShapes* s = makeShapes();
    REQUIRE(registerBox(s, Q / 2, Q) == TOP_SLAB);
    const McrlBoxQ fence[] = {{{1536, 0, 1536}, {2560, 6144, 2560}}, {{0, 0, 1792}, {4096, 6144, 2304}}};
    REQUIRE(mcrl_shapes_register(s, fence, 2, 0) == FENCE_X);
    return s;
}

bool onFloor(int32_t x, int32_t z) { return x >= -32 && x <= 127 && z >= -16 && z <= 31; }

uint16_t checkerWorld(int32_t x, int32_t y, int32_t z) {
    if (y == 63) return onFloor(x, z) ? CUBE : AIR;
    if (y == 64 && x >= -24 && x <= 119 && z >= -8 && z <= 23) return ((x + z) & 1) == 0 ? CUBE : TOP_SLAB;
    return AIR;
}

uint16_t fenceRowWorld(int32_t x, int32_t y, int32_t z) {
    if (y == 63) return onFloor(x, z) ? CUBE : AIR;
    return y == 64 && z == 8 && x >= -24 && x <= 119 ? FENCE_X : AIR;
}

McrlWorld* subBlockWorld(const McrlShapes* s, World at) {
    McrlWorld* w = makeWorld(s);
    std::vector<uint16_t> c(N * N * N);
    for (int32_t sy = 3; sy <= 5; sy++)
        for (int32_t sz = -1; sz <= 1; sz++)
            for (int32_t sx = -2; sx <= 7; sx++) {
                for (int y = -BORDER; y < 16 + BORDER; y++)
                    for (int z = -BORDER; z < 16 + BORDER; z++)
                        for (int x = -BORDER; x < 16 + BORDER; x++)
                            c[((y + BORDER) * N + (z + BORDER)) * N + (x + BORDER)] =
                                at(16 * sx + x, 16 * sy + y, 16 * sz + z);
                const McrlSectionInput in{sx, sy, sz, 1, BORDER, 0, c.data()};
                McrlBuilt* b = mcrl_section_build(s, &in, nullptr);
                REQUIRE_MESSAGE(b != nullptr, mcrl_last_error());
                REQUIRE(mcrl_section_commit(w, b) == MCRL_OK);
            }
    return w;
}

struct Roll {
    uint32_t bounces = 0;
    float lastBounceSpeed = 0;
    double maxVy = 0, maxDv = 0, endX = 0, endY = 0;
    int contacts = 0;
    std::vector<McrlHitFx> at;
};

Roll roll(McrlWorld* w, double x, double y, double z, double vx, double vz, int ticks) {
    setBall(w, x, y, z);
    step(w, 120);
    McrlBallState b = getBall(w);
    b.body.vx = vx;
    b.body.vz = vz;
    REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
    Roll r;
    const uint32_t before = getBall(w).bounces;
    McrlBallState prev = getBall(w);
    for (int t = 0; t < ticks; t++) {
        step(w, 1);
        const McrlBallState o = getBall(w);
        r.maxVy = std::max(r.maxVy, std::abs((double)o.body.vy));
        const double dvx = o.body.vx - prev.body.vx, dvy = o.body.vy - prev.body.vy + 13.0 / 120, dvz = o.body.vz - prev.body.vz;
        r.maxDv = std::max(r.maxDv, std::sqrt(dvx * dvx + dvy * dvy + dvz * dvz));
        if (prev.body.vz > 0 && o.body.vz <= 0) r.contacts++;
        if (o.bounces != prev.bounces) {
            McrlBallFx f{};
            REQUIRE(mcrl_ball_get_fx(w, &f) == MCRL_OK);
            r.at.push_back(f.bounce);
        }
        prev = o;
    }
    r.bounces = getBall(w).bounces - before;
    r.lastBounceSpeed = getBall(w).bounceSpeed;
    r.endX = prev.body.px;
    r.endY = prev.body.py;
    return r;
}
}

TEST_CASE("events: no bounces rolling over sub-block seams") {
    McrlShapes* s = subBlockShapes();
    SUBCASE("(c) top slab / full cube checkerboard, across the borders x = 0 and 16") {
        McrlWorld* w = subBlockWorld(s, checkerWorld);
        const Roll r = roll(w, -20, 65 + BALL_REST, 4.3, 20, 2.5, 360);
        INFO("last bounce ", r.lastBounceSpeed, " UU/s, max |vy| ", r.maxVy, ", max dv ", r.maxDv);
        CHECK(r.endX > 16 + 2);
        CHECK(std::abs(r.endY - 65 - BALL_REST) < 0.05);
        CHECK(r.bounces == 0);
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
    }
    SUBCASE("(d) fence row across the borders, the ball rolling along it against the posts' top edges") {
        const double zTouch = 8.375 - std::sqrt(BALL_REST * BALL_REST - (BALL_REST - 1.5) * (BALL_REST - 1.5));
        for (const double x0 : {8.0, 10.5, 12.0, 13.0, 13.5, 14.0, 29.0}) {
            CAPTURE(x0);
            McrlWorld* w = subBlockWorld(s, fenceRowWorld);
            const Roll r = roll(w, x0, FLOOR_TOP + BALL_REST, zTouch - 0.03, 20, 0.2, 60);
            INFO("last bounce ", r.lastBounceSpeed, " UU/s, max |vy| ", r.maxVy, ", max dv ", r.maxDv);
            CHECK(r.contacts >= 1);
            CHECK(r.endX > x0 + 8);
            CHECK(r.bounces <= 1);
            for (const McrlHitFx& h : r.at) {
                const double fx = h.px - std::floor(h.px);
                CHECK(std::abs(h.py - 65.5) < 1e-3);
                CHECK(std::abs(h.pz - 8.375) < 1e-3);
                CHECK((std::abs(fx - 0.375) < 1e-3 || std::abs(fx - 0.625) < 1e-3));
            }
            CHECK(r.lastBounceSpeed < 100);
            CHECK(mcrl_world_destroy(w) == MCRL_OK);
        }
    }
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
