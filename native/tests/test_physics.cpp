#include <doctest/doctest.h>
#include <algorithm>
#include <cstring>
#include "mcrl.h"
#include "terrain_fixtures.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;
constexpr double BALL_REST = 1.863;

constexpr uint16_t TOP_SLAB = CARPET + 1;
constexpr double HITBOX_FRONT = (13.8757 + 120.507 / 2) / 50;

struct WallRun {
    double maxPx = -1e9, maxPy = -1e9, finalPy = 0, finalPx = 0;
    int groundedHigh = 0;
    int pxPast = 0;
    int contactTick = -1;
    double contactSpeed = -1;
    double front2 = -1, front1 = -1, front0 = -1;
};

WallRun wallRun(uint16_t id, double v0) {
    McrlShapes* s = makeShapes();
    REQUIRE(registerBox(s, Q / 2, Q) == TOP_SLAB);
    McrlWorld* w = makeWorld(s);
    const Scene sc = floorScene().fill(20, 64, -16, 20, 64, 31, id);
    commitStandard(w, s, sc);
    McrlCarState c = carAt(20 - 0.5 - HITBOX_FRONT, FLOOR_TOP + CAR_REST, 8.5);
    c.body.vx = v0;
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    const bool top = v0 >= 46;
    setControls(w, 1, top ? 1.f : 0.f, 0, top);
    WallRun r;
    double prevVx = v0, prevSpeed = v0, prevPx = c.body.px, prev2Px = c.body.px;
    for (int t = 1; t <= 360; t++) {
        step(w, 1);
        const McrlCarState o = getCar(w, 1);
        if (r.contactTick < 0 && prevVx - o.body.vx > 1) {
            r.contactTick = t;
            r.contactSpeed = prevSpeed;
            r.front2 = prev2Px + HITBOX_FRONT;
            r.front1 = prevPx + HITBOX_FRONT;
            r.front0 = o.body.px + HITBOX_FRONT;
        }
        prevVx = o.body.vx;
        prevSpeed = speed(o.body);
        prev2Px = prevPx;
        prevPx = o.body.px;
        r.maxPx = std::max(r.maxPx, o.body.px);
        r.maxPy = std::max(r.maxPy, o.body.py);
        if (o.body.px >= 20) r.pxPast++;
        if ((o.flags & MCRL_CAR_ON_GROUND) && o.body.py > 64.6) r.groundedHigh++;
        r.finalPx = o.body.px;
        r.finalPy = o.body.py;
    }
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
    return r;
}

void checkWall(const WallRun& r) {
    INFO("max px ", r.maxPx, ", max py ", r.maxPy, ", final (", r.finalPx, ", ", r.finalPy, "); hitbox front ",
         r.front2, ", ", r.front1, ", ", r.front0, " on the 2 ticks before the response and on it");
    CHECK(r.pxPast == 0);
    CHECK(r.groundedHigh == 0);
    CHECK(r.finalPy < 64.6);
    CHECK(r.front2 < 20);
    CHECK(r.front1 >= 20 - 0.05);
}

void checkWallAtAllSpeeds(uint16_t id) {
    for (double v0 : {6.0, 20.0, 46.0}) {
        CAPTURE(v0);
        const WallRun r = wallRun(id, v0);
        CHECK(r.contactTick > 0);
        CHECK(std::abs(r.contactSpeed - v0) <= 0.2 * v0);
        checkWall(r);
    }
}

McrlCarState driveOverRow(McrlShapes* s, uint16_t id, int ticks) {
    McrlWorld* w = makeWorld(s);
    const Scene sc = floorScene().fill(14, 64, -16, 14, 64, 31, id);
    commitStandard(w, s, sc);
    McrlCarState c = carAt(8, FLOOR_TOP + CAR_REST, 8.5);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    setControls(w, 1, 1);
    for (int t = 0; t < ticks; t++) step(w, 1);
    const McrlCarState end = getCar(w, 1);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    return end;
}
}

TEST_CASE("rest_height_flat") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    CHECK(commitStandard(w, s, floorScene()) > 0);
    McrlCarState c = carAt(8.5, 65, 8.5);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    step(w, 240);
    const McrlCarState o = getCar(w, 1);
    INFO("py ", o.body.py, ", |v| ", speed(o.body), ", flags ", o.flags, ", wheels ", o.wheelContact);
    CHECK(speed(o.body) < 0.02);
    CHECK(std::abs(o.body.py - FLOOR_TOP - CAR_REST) <= 0.02);
    CHECK((o.flags & MCRL_CAR_ON_GROUND) != 0);
    CHECK(o.wheelContact == 0xFu);
    CHECK(o.body.px == doctest::Approx(8.5).epsilon(0.01 / 8.5));
    CHECK(o.body.pz == doctest::Approx(8.5).epsilon(0.01 / 8.5));
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("steer_right_turns_right") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    commitStandard(w, s, floorScene());
    const McrlCarState start = carAt(8.5, FLOOR_TOP + CAR_REST, 8.5);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &start) == MCRL_OK);
    setControls(w, 1, 1, 1);
    int grounded = 0;
    for (int t = 0; t < 120; t++) {
        step(w, 1);
        if (getCar(w, 1).flags & MCRL_CAR_ON_GROUND) grounded++;
    }
    const McrlCarState o = getCar(w, 1);
    INFO("end (", o.body.px, ", ", o.body.py, ", ", o.body.pz, "), steerAngle ", o.steerAngle);
    CHECK(o.body.pz > start.body.pz + 0.5);
    CHECK(o.body.px > start.body.px);
    CHECK(o.steerAngle < 0);
    CHECK(grounded == 120);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("wall_stops_car") { checkWallAtAllSpeeds(CUBE); }

TEST_CASE("carpet_rolls_over") {
    McrlShapes* s = makeShapes();
    const McrlCarState o = driveOverRow(s, CARPET, 120);
    INFO("end (", o.body.px, ", ", o.body.py, ", ", o.body.pz, ")");
    CHECK(o.body.px > 18);
    CHECK(o.body.py < 64.6);
    CHECK(o.body.py > 64);
    CHECK((o.flags & MCRL_CAR_ON_GROUND) != 0);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("ball_rests") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    commitStandard(w, s, floorScene());
    setBall(w, 8.5, 72, 8.5);
    step(w, 480);
    const McrlBallState b = getBall(w);
    INFO("ball (", b.body.px, ", ", b.body.py, ", ", b.body.pz, "), |v| ", speed(b.body));
    CHECK(std::abs(b.body.py - (FLOOR_TOP + BALL_REST)) <= 0.06);
    CHECK(speed(b.body) < 0.05);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("car_hits_ball") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    commitStandard(w, s, floorScene());
    setBall(w, 16, 66, 8.5);
    step(w, 240);
    const McrlBallState rest = getBall(w);
    REQUIRE(speed(rest.body) < 0.05);
    REQUIRE(std::abs(rest.body.px - 16) < 0.01);

    McrlCarState c = carAt(6, FLOOR_TOP + CAR_REST, 8.5);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    setControls(w, 1, 1, 0, true);
    double maxSpeed = 0;
    int hitTick = -1;
    for (int t = 0; t < 240 && hitTick < 0; t++) {
        step(w, 1);
        maxSpeed = std::max(maxSpeed, speed(getBall(w).body));
        if (maxSpeed > 10) hitTick = t + 1;
    }
    INFO("max ball speed ", maxSpeed, " blocks/s");
    CHECK(hitTick > 0);
    const McrlBallState b = getBall(w);
    CHECK(b.body.vx > 0);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("determinism_on_ground") {
    McrlShapes* s = makeShapes();
    const Scene sc = Scene().fill(-64, 63, -64, 79, 63, 79, CUBE);
    McrlWorld* w[2] = {makeWorld(s), makeWorld(s)};
    for (McrlWorld* x : w) {
        sc.commitRange(x, s, -4, 3, -4, 4, 5, 4);
        const McrlCarState c = carAt(8, FLOOR_TOP + CAR_REST, 8);
        REQUIRE(mcrl_car_add(x, 1, 0, 0, &c) == MCRL_OK);
        setBall(x, 20, 66, 12);
    }
    int grounded = 0, airborne = 0;
    for (int t = 0; t < 600; t++) {
        const float steer = (t / 30) % 3 == 2 ? -1.f : 1.f;
        const bool jump = t >= 90 && t < 100;
        const bool boost = t >= 150 && t < 180;
        const bool handbrake = t >= 300 && t < 360;
        for (McrlWorld* x : w) {
            setControls(x, 1, 1, steer, boost, jump, handbrake);
            step(x, 1);
        }
        const McrlCarState o = getCar(w[0], 1);
        if (o.flags & MCRL_CAR_ON_GROUND) grounded++;
        else airborne++;
    }
    const McrlCarState a = getCar(w[0], 1), b = getCar(w[1], 1);
    CHECK(std::memcmp(&a, &b, sizeof a) == 0);
    const McrlBallState ba = getBall(w[0]), bb = getBall(w[1]);
    CHECK(std::memcmp(&ba, &bb, sizeof ba) == 0);
    INFO("car end (", a.body.px, ", ", a.body.py, ", ", a.body.pz, "), grounded ", grounded, ", airborne ",
         airborne);
    CHECK(grounded > 400);
    CHECK(airborne > 10);
    CHECK(a.body.py > FLOOR_TOP);
    CHECK(a.body.py < FLOOR_TOP + 1);
    for (McrlWorld* x : w) CHECK(mcrl_world_destroy(x) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("gate_negative_coords") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s, -32, 0, 48);
    REQUIRE(mcrl_world_set_spawn_gate(w, 1) == MCRL_OK);
    const double cx = -16.9, cy = 65, cz = 40.5;
    REQUIRE(sectionOf(cx) == -2);
    REQUIRE(sectionOf(cy) == 4);
    REQUIRE(sectionOf(cz) == 2);
    const Scene sc = Scene().fill(-32, 63, 32, -1, 63, 47, CUBE);
    McrlCarState c = carAt(cx, cy, cz);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    sc.commitRange(w, s, -1, 3, 2, 0, 4, 2);
    uint32_t flags = 0;
    CHECK(mcrl_step(w, 6, &flags) == 0);
    CHECK(flags == MCRL_STEP_FROZEN);
    CHECK(getCar(w, 1).body.py == cy);

    sc.commitRange(w, s, -2, 3, 2, -2, 4, 2);
    CHECK(mcrl_step(w, 6, &flags) == 6);
    CHECK(flags == MCRL_STEP_BALL_PARKED);
    const McrlCarState o = getCar(w, 1);
    CHECK(o.body.py < cy);
    CHECK(o.body.px == doctest::Approx(cx));
    CHECK(o.body.pz == doctest::Approx(cz));
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
