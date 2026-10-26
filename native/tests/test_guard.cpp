#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>
#include <thread>
#include <tuple>
#include <vector>
#include "mcrl.h"
#include "terrain_fixtures.h"
#include "world.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;
constexpr double BALL_REST = 1.863;
constexpr int RING = 120;

struct Rig {
    McrlShapes* s = nullptr;
    McrlWorld* w = nullptr;

    Rig() {
        s = makeShapes();
        w = makeWorld(s);
        Scene().fill(-32, 63, -32, 47, 63, 47, CUBE).commitRange(w, s, -2, 3, -2, 2, 4, 2);
        const McrlCarState c = carAt(8, FLOOR_TOP + CAR_REST, 8);
        REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
        setControls(w, 1, 1, 1);
        McrlBallState b{};
        b.body.px = -28;
        b.body.py = FLOOR_TOP + BALL_REST;
        b.body.pz = -29;
        b.body.qw = 1;
        b.body.vx = 5.5;
        b.body.vz = 3.3;
        REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
    }
    ~Rig() {
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
        CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
    }
    Rig(const Rig&) = delete;
    Rig& operator=(const Rig&) = delete;
};

uint32_t tick1(McrlWorld* w) {
    uint32_t flags = 0xFFFFFFFFu;
    REQUIRE(mcrl_step(w, 1, &flags) == 1);
    return flags;
}

bool finiteBody(const McrlBody& b) {
    const double v[] = {b.px, b.py, b.pz, b.qw, b.qx, b.qy, b.qz, b.vx, b.vy, b.vz, b.wx, b.wy, b.wz};
    return std::all_of(std::begin(v), std::end(v), [](double x) { return std::isfinite(x); });
}

bool rel(double a, double b) { return std::abs(a - b) <= 1e-5 * std::max(std::abs(a), std::abs(b)); }

#define CHECK_REL(a, b) CHECK_MESSAGE(rel((a), (b)), #a " = ", (a), ", " #b " = ", (b))

void checkPos(const McrlBody& got, const McrlBody& want) {
    CHECK_REL(got.px, want.px);
    CHECK_REL(got.py, want.py);
    CHECK_REL(got.pz, want.pz);
}

void checkRot(const McrlBody& got, const McrlBody& want) {
    CHECK(got.qw == want.qw);
    CHECK(got.qx == want.qx);
    CHECK(got.qy == want.qy);
    CHECK(got.qz == want.qz);
}

void checkRestored(const McrlBody& got, const McrlBody& want) {
    checkPos(got, want);
    checkRot(got, want);
    CHECK_REL(got.vx, want.vx);
    CHECK_REL(got.vy, want.vy);
    CHECK_REL(got.vz, want.vz);
    CHECK(got.wx == want.wx);
    CHECK(got.wy == want.wy);
    CHECK(got.wz == want.wz);
}

void checkAngVelZero(const McrlBody& got) {
    CHECK(got.wx == 0);
    CHECK(got.wy == 0);
    CHECK(got.wz == 0);
}

void checkCarAtRest(const McrlBody& got, const McrlBody& want) {
    checkPos(got, want);
    checkRot(got, want);
    CHECK(got.vx == 0);
    CHECK(got.vy == 0);
    CHECK(got.vz == 0);
    checkAngVelZero(got);
}

double restingBallVy() {
    McrlWorld* w = makeWorld(nullptr);
    setBall(w, 0, 50, 0);
    const double vy = getBall(w).body.vy;
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    REQUIRE(vy < 0);
    REQUIRE(vy > -1e-4);
    return vy;
}

void checkBallAtRest(const McrlBody& got, const McrlBody& want) {
    checkPos(got, want);
    checkRot(got, want);
    CHECK(got.vx == 0);
    CHECK(got.vy == restingBallVy());
    CHECK(got.vz == 0);
    checkAngVelZero(got);
}

void checkNear(const McrlBody& got, const McrlBody& want) {
    CHECK(std::abs(got.px - want.px) <= 1e-3);
    CHECK(std::abs(got.py - want.py) <= 1e-3);
    CHECK(std::abs(got.pz - want.pz) <= 1e-3);
}

struct Snap {
    McrlCarState car{};
    McrlBallState ball{};
};

Snap snap(McrlWorld* w) { return {getCar(w, 1), getBall(w)}; }

int manifoldsOf(McrlWorld* w, const btCollisionObject* body, const btCollisionObject* other = nullptr) {
    btCollisionDispatcher* d = w->arena->_bulletWorld.getDispatcher();
    int n = 0;
    for (int i = 0; i < d->getNumManifolds(); i++) {
        const btPersistentManifold* m = d->getManifoldByIndexInternal(i);
        const bool hasBody = m->getBody0() == body || m->getBody1() == body;
        const bool hasOther = !other || m->getBody0() == other || m->getBody1() == other;
        if (hasBody && hasOther) n++;
    }
    return n;
}

void checkTwin(const McrlBody& got, const McrlBody& twin) {
    for (const auto& [a, b] : {std::pair{got.px, twin.px}, std::pair{got.py, twin.py}, std::pair{got.pz, twin.pz},
                               std::pair{got.vx, twin.vx}, std::pair{got.vy, twin.vy}, std::pair{got.vz, twin.vz}})
        CHECK_MESSAGE(std::abs(a - b) <= 1e-6, a, " vs the twin's ", b);
}

bool carRestored(McrlWorld* w, int32_t id) { return (getCar(w, id).flags & MCRL_CAR_RESTORED) != 0; }
bool demoed(McrlWorld* w, int32_t id) { return (getCar(w, id).flags & MCRL_CAR_DEMOED) != 0; }
}

TEST_CASE("nan_guard: one trip restores only the bad body") {
    Rig r;
    Rig twin;
    step(r.w, 200);
    step(twin.w, 200);
    const Snap before = snap(r.w);
    REQUIRE(speed(before.car.body) > 5);
    REQUIRE(speed(before.ball.body) > 1);
    REQUIRE(mcrl_debug_poison(r.w, 1) == MCRL_OK);
    CHECK(tick1(r.w) == MCRL_STEP_RESTORED);
    REQUIRE(tick1(twin.w) == 0);
    const Snap after = snap(r.w);
    CHECK(finiteBody(after.car.body));
    checkRestored(after.car.body, before.car.body);
    CHECK(after.car.flags == (before.car.flags | MCRL_CAR_RESTORED));
    CHECK(after.car.wheelContact == before.car.wheelContact);
    CHECK(after.car.boost == 100);
    CHECK(after.car.stepDz == 0);
    checkTwin(after.ball.body, getBall(twin.w).body);

    for (int t = 0; t < RING; t++) {
        CAPTURE(t);
        CHECK(tick1(r.w) == 0);
        const Snap s = snap(r.w);
        CHECK(finiteBody(s.car.body));
        CHECK(finiteBody(s.ball.body));
        CHECK((s.car.flags & MCRL_CAR_RESTORED) == 0);
    }
    const McrlCarState end = getCar(r.w, 1);
    CHECK(speed(end.body) > 5);
    CHECK(end.body.py > FLOOR_TOP);
}

TEST_CASE("nan_guard: second trip of the same body within 120 ticks restores the oldest entry at rest") {
    Rig r;
    std::vector<Snap> rec;
    auto run = [&](int n) {
        for (int t = 0; t < n; t++) {
            REQUIRE(tick1(r.w) == 0);
            rec.push_back(snap(r.w));
        }
    };
    auto trip = [&]() {
        REQUIRE(mcrl_debug_poison(r.w, 1) == MCRL_OK);
        CHECK(tick1(r.w) == MCRL_STEP_RESTORED);
        return snap(r.w);
    };
    run(200);
    INFO("trip 1: none in the previous 120 ticks");
    Snap s = trip();
    checkRestored(s.car.body, rec.back().car.body);
    rec.push_back(s);

    run(10);
    INFO("trip 2: 11 ticks after trip 1");
    REQUIRE(rec.size() >= (size_t)RING);
    const Snap oldest2 = rec[rec.size() - RING];
    s = trip();
    const McrlBody& newest = rec.back().car.body;
    CHECK(std::abs(oldest2.car.body.px - newest.px) + std::abs(oldest2.car.body.pz - newest.pz) > 1);
    checkCarAtRest(s.car.body, oldest2.car.body);
    rec.push_back(s);

    run(119);
    INFO("trip 3: 120 ticks after trip 2, still within");
    const Snap oldest3 = rec[rec.size() - RING];
    s = trip();
    checkCarAtRest(s.car.body, oldest3.car.body);
    rec.push_back(s);

    run(120);
    INFO("trip 4: 121 ticks after trip 3");
    s = trip();
    checkRestored(s.car.body, rec.back().car.body);
    CHECK(speed(s.car.body) > 1);
}

TEST_CASE("nan_guard: entries survive an origin shift") {
    Rig r;
    step(r.w, 150);
    RocketSim::BallHitInfo& hit = r.w->cars.at(1)->_internalState.ballHitInfo;
    REQUIRE_FALSE(hit.isValid);
    hit.isValid = true;
    hit.ballPos = RocketSim::Vec(100, 200, 300);
    step(r.w, 1);
    const Snap before = snap(r.w);

    REQUIRE(mcrl_world_shift_origin(r.w, 256, 16, -64) == MCRL_OK);
    CHECK(hit.ballPos.x == -12700);
    CHECK(hit.ballPos.y == 3400);
    CHECK(hit.ballPos.z == -500);
    REQUIRE(mcrl_debug_poison(r.w, 1) == MCRL_OK);
    CHECK(tick1(r.w) == MCRL_STEP_RESTORED);
    const Snap after = snap(r.w);
    checkNear(after.car.body, before.car.body);
    checkRot(after.car.body, before.car.body);
    CHECK_REL(after.car.body.vx, before.car.body.vx);
    CHECK_REL(after.car.body.vz, before.car.body.vz);
    const RocketSim::BallHitInfo& hit2 = r.w->cars.at(1)->_internalState.ballHitInfo;
    CHECK(hit2.isValid);
    CHECK(hit2.ballPos.x == -12700);
    CHECK(hit2.ballPos.y == 3400);
    CHECK(hit2.ballPos.z == -500);

    const McrlCarState car2 = carAt(-20.5, FLOOR_TOP + CAR_REST, 30.5, 45);
    REQUIRE(mcrl_car_add(r.w, 2, 0, 0, &car2) == MCRL_OK);
    const McrlCarState added = getCar(r.w, 2);
    REQUIRE(mcrl_world_shift_origin(r.w, -32, 0, 48) == MCRL_OK);
    REQUIRE(mcrl_debug_poison(r.w, 2) == MCRL_OK);
    CHECK(tick1(r.w) == MCRL_STEP_RESTORED);
    const McrlCarState after2 = getCar(r.w, 2);
    checkNear(after2.body, added.body);
    checkRot(after2.body, added.body);
    CHECK(speed(after2.body) == 0);

    step(r.w, 121);
    McrlBallState b{};
    b.body.px = -10.25;
    b.body.py = FLOOR_TOP + 6;
    b.body.pz = -12.75;
    b.body.qw = 1;
    b.body.vx = 3;
    b.body.vy = 2;
    REQUIRE(mcrl_ball_set_state(r.w, &b) == MCRL_OK);
    const McrlBallState set = getBall(r.w);
    REQUIRE(mcrl_world_shift_origin(r.w, 0, 0, 0) == MCRL_OK);
    REQUIRE(mcrl_debug_poison(r.w, -1) == MCRL_OK);
    CHECK(tick1(r.w) == (MCRL_STEP_RESTORED | MCRL_STEP_BALL_RESTORED));
    const McrlBallState after3 = getBall(r.w);
    checkNear(after3.body, set.body);
    checkRot(after3.body, set.body);
    CHECK(after3.body.vx == 0);
    CHECK(after3.body.vy == restingBallVy());
    CHECK(after3.body.vz == 0);
    checkAngVelZero(after3.body);
}

TEST_CASE("nan_guard: ball") {
    Rig r;
    McrlCarState roof = carAt(-10.5, FLOOR_TOP + (20.755 + 38.6591 / 2) / 50 + 0.05, 20.5);
    roof.body.qw = 0;
    roof.body.qx = 1;
    REQUIRE(mcrl_car_add(r.w, 2, 0, 0, &roof) == MCRL_OK);
    step(r.w, 150);
    const Snap before = snap(r.w);
    const btCollisionObject* ballBody = &r.w->arena->ball->_rigidBody;
    const btCollisionObject* carBody = &r.w->cars.at(2)->_rigidBody;
    REQUIRE(manifoldsOf(r.w, ballBody) > 0);
    REQUIRE(manifoldsOf(r.w, carBody) > 0);
    REQUIRE(mcrl_debug_poison(r.w, -1) == MCRL_OK);
    CHECK(tick1(r.w) == (MCRL_STEP_RESTORED | MCRL_STEP_BALL_RESTORED));
    CHECK(manifoldsOf(r.w, ballBody) == 0);
    CHECK(manifoldsOf(r.w, carBody) > 0);
    CHECK(manifoldsOf(r.w, carBody, ballBody) == 0);
    CHECK(manifoldsOf(r.w, &r.w->cars.at(1)->_rigidBody, ballBody) == 0);
    CHECK_FALSE(carRestored(r.w, 1));
    CHECK_FALSE(carRestored(r.w, 2));
    const Snap after = snap(r.w);
    checkRestored(after.ball.body, before.ball.body);
    step(r.w, 2);
    CHECK(manifoldsOf(r.w, ballBody) > 0);
    const Snap rolling = snap(r.w);
    CHECK(speed(rolling.ball.body) > 1);
    CHECK(std::abs(rolling.ball.body.py - (FLOOR_TOP + BALL_REST)) < 0.05);

    step(r.w, 121);
    McrlBallState b{};
    b.body.px = 20.5;
    b.body.py = FLOOR_TOP + 8;
    b.body.pz = -20.5;
    b.body.qw = 0.8f;
    b.body.qx = 0.6f;
    b.body.vx = -4;
    b.body.vy = 6;
    b.body.wz = 3;
    REQUIRE(mcrl_ball_set_state(r.w, &b) == MCRL_OK);
    const McrlBallState set = getBall(r.w);
    REQUIRE(mcrl_debug_poison(r.w, -1) == MCRL_OK);
    CHECK(tick1(r.w) == (MCRL_STEP_RESTORED | MCRL_STEP_BALL_RESTORED));
    const McrlBallState o = getBall(r.w);
    CHECK(o.body.px == set.body.px);
    CHECK(o.body.py == set.body.py);
    CHECK(o.body.pz == set.body.pz);
    checkBallAtRest(o.body, set.body);
    step(r.w, 60);
    CHECK(getBall(r.w).body.py < set.body.py - 1);
}

TEST_CASE("nan_guard: a restore keeps a demoed car demoed") {
    Rig r;
    const McrlCarState c2 = carAt(-20.5, FLOOR_TOP + CAR_REST, -20.5);
    REQUIRE(mcrl_car_add(r.w, 2, 0, 0, &c2) == MCRL_OK);
    step(r.w, 100);
    const McrlCarState recorded = getCar(r.w, 2);
    REQUIRE((recorded.flags & MCRL_CAR_DEMOED) == 0);
    REQUIRE(mcrl_car_demolish(r.w, 2) == MCRL_OK);
    REQUIRE(mcrl_debug_poison(r.w, 2) == MCRL_OK);
    CHECK(tick1(r.w) == MCRL_STEP_RESTORED);
    CHECK(demoed(r.w, 2));
    CHECK(carRestored(r.w, 2));
    const McrlCarState restored = getCar(r.w, 2);
    checkRestored(restored.body, recorded.body);
    step(r.w, 10);
    CHECK(demoed(r.w, 2));
    CHECK(getCar(r.w, 2).body.py == restored.body.py);

    const McrlCarState c3 = carAt(30.5, FLOOR_TOP + CAR_REST + 1, -20.5);
    REQUIRE(mcrl_car_add(r.w, 3, 0, 0, &c3) == MCRL_OK);
    const McrlCarState added3 = getCar(r.w, 3);
    REQUIRE(mcrl_car_demolish(r.w, 3) == MCRL_OK);
    REQUIRE(mcrl_debug_poison(r.w, 3) == MCRL_OK);
    CHECK(tick1(r.w) == MCRL_STEP_RESTORED);
    CHECK(demoed(r.w, 3));
    checkCarAtRest(getCar(r.w, 3).body, added3.body);

    REQUIRE(mcrl_debug_poison(r.w, 1) == MCRL_OK);
    CHECK(tick1(r.w) == MCRL_STEP_RESTORED);
    CHECK(demoed(r.w, 2));
    CHECK(demoed(r.w, 3));
    CHECK_FALSE(carRestored(r.w, 2));
    CHECK(getCar(r.w, 2).body.py == restored.body.py);
}

TEST_CASE("nan_guard: poison unknown id is NOT_FOUND") {
    McrlWorld* w = makeWorld(nullptr);
    const McrlCarState c = carAt(0, 100, 0);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    CHECK(mcrl_debug_poison(w, 2) == MCRL_ERR_NOT_FOUND);
    CHECK(std::string(mcrl_last_error()).find("unknown car id") != std::string::npos);
    CHECK(mcrl_debug_poison(w, -2) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_debug_poison(nullptr, 1) == MCRL_ERR_ARG);
    int32_t rc = 0;
    std::thread t([&] { rc = mcrl_debug_poison(w, 1); });
    t.join();
    CHECK(rc == MCRL_ERR_THREAD);
    CHECK(tick1(w) == 0);

    REQUIRE(mcrl_debug_poison(w, 1) == MCRL_OK);
    REQUIRE(mcrl_car_remove(w, 1) == MCRL_OK);
    CHECK(tick1(w) == 0);
    REQUIRE(mcrl_debug_poison(w, -1) == MCRL_OK);
    CHECK(tick1(w) == (MCRL_STEP_RESTORED | MCRL_STEP_BALL_RESTORED));
    CHECK(tick1(w) == 0);
    CHECK(finiteBody(getBall(w).body));
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
}
