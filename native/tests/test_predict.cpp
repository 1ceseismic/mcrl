#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <initializer_list>
#include <random>
#include <vector>
#include "mcrl.h"
#include "arena_fixtures.h"
#include "blob.h"
#include "terrain_fixtures.h"

using namespace fx;
namespace blob = mcrl::blob;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;
constexpr double BALL_REST = 1.863;
constexpr int32_t CAR = 1;
constexpr int32_t PARKED = 2;

using Blob = std::vector<uint8_t>;

int32_t carBlobSize() { return mcrl_sizeof(16); }
int32_t ballBlobSize() { return mcrl_sizeof(17); }

Blob saveCar(McrlWorld* w, int32_t id) {
    Blob b((size_t)carBlobSize());
    REQUIRE(mcrl_car_save(w, id, b.data()) == MCRL_OK);
    return b;
}

Blob saveBall(McrlWorld* w) {
    Blob b((size_t)ballBlobSize());
    REQUIRE(mcrl_ball_save(w, b.data()) == MCRL_OK);
    return b;
}

Scene predictScene() { return floorScene().fill(5, 64, -16, 6, 64, 31, SLAB); }

void commitScene(McrlWorld* w, const McrlShapes* s) { predictScene().commitRange(w, s, -1, 3, -1, 1, 5, 1); }

void script(McrlWorld* w, int t) {
    const bool boost = (t >= 40 && t <= 50) || (t >= 75 && t <= 90) || (t >= 140 && t <= 150);
    setControls(w, CAR, 1, t >= 20 && t < 30 ? 0.4f : 0, boost, t == 30);
}

void run(McrlWorld* w, int from, int to) {
    for (int t = from + 1; t <= to; t++) {
        script(w, t);
        step(w, 1);
    }
}

struct Rig {
    McrlShapes* s = nullptr;
    McrlWorld* w = nullptr;

    explicit Rig(bool reverse = false) {
        s = makeShapes();
        w = makeWorld(s);
        commitScene(w, s);
        const McrlCarState scripted = carAt(-6.5, FLOOR_TOP + CAR_REST, 0.5);
        const McrlCarState parked = carAt(-6.5, FLOOR_TOP + CAR_REST, 24.5);
        if (reverse) {
            REQUIRE(mcrl_car_add(w, PARKED, 1, 0, &parked) == MCRL_OK);
            REQUIRE(mcrl_car_add(w, CAR, 0, 0, &scripted) == MCRL_OK);
        } else {
            REQUIRE(mcrl_car_add(w, CAR, 0, 0, &scripted) == MCRL_OK);
            REQUIRE(mcrl_car_add(w, PARKED, 1, 0, &parked) == MCRL_OK);
        }
        setBall(w, -2.0, FLOOR_TOP + BALL_REST, 0.5);
    }
    ~Rig() {
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
        CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
    }
    Rig(const Rig&) = delete;
    Rig& operator=(const Rig&) = delete;
};

void loadAll(McrlWorld* w, uint64_t tick, const Blob& car, const Blob& parked, const Blob& ball) {
    REQUIRE(mcrl_world_set_tick(w, tick) == MCRL_OK);
    REQUIRE(mcrl_car_load(w, CAR, car.data()) == MCRL_OK);
    REQUIRE(mcrl_car_load(w, PARKED, parked.data()) == MCRL_OK);
    REQUIRE(mcrl_ball_load(w, ball.data()) == MCRL_OK);
}

bool sameState(const McrlCarState& a, const McrlCarState& b) { return std::memcmp(&a, &b, sizeof a) == 0; }

McrlCarFx carFx(McrlWorld* w, int32_t id) {
    McrlCarFx f{};
    REQUIRE(mcrl_car_get_fx(w, id, &f) == MCRL_OK);
    return f;
}

bool sameFx(const McrlCarFx& a, const McrlCarFx& b) { return std::memcmp(&a, &b, sizeof a) == 0; }

blob::CarBlob carBlob(const Blob& b) {
    blob::CarBlob c{};
    std::memcpy(&c, b.data(), sizeof c);
    return c;
}

Blob bytes(const blob::CarBlob& c) {
    Blob b(sizeof c);
    std::memcpy(b.data(), &c, sizeof c);
    return b;
}

bool finite(const McrlBody& b) {
    for (double v : std::initializer_list<double>{b.px, b.py, b.pz, b.qw, b.qx, b.qy, b.qz, b.vx, b.vy, b.vz, b.wx, b.wy,
                                                   b.wz})
        if (!std::isfinite(v)) return false;
    return true;
}

}

TEST_CASE("predict: save, load, save is identical") {
    Rig r;
    run(r.w, 0, 60);
    const McrlCarState s = getCar(r.w, CAR);
    REQUIRE_MESSAGE(s.ballHits >= 1, "the script hits the ball before tick 60");
    REQUIRE_MESSAGE((s.flags & MCRL_CAR_ON_GROUND) == 0, "the car is mid-air at tick 60 (jumped at 30)");
    const Blob car = saveCar(r.w, CAR), parked = saveCar(r.w, PARKED), ball = saveBall(r.w);
    CHECK(car[0] == MCRL_BLOB_VERSION);
    CHECK(ball[0] == MCRL_BLOB_VERSION);

    loadAll(r.w, 60, car, parked, ball);
    CHECK(saveCar(r.w, CAR) == car);
    CHECK(saveCar(r.w, PARKED) == parked);
    CHECK(saveBall(r.w) == ball);
    CHECK(sameState(getCar(r.w, CAR), s));

    Rig b(true);
    loadAll(b.w, 60, car, parked, ball);
    CHECK(saveCar(b.w, CAR) == car);
    CHECK(saveCar(b.w, PARKED) == parked);
    CHECK(saveBall(b.w) == ball);
}

TEST_CASE("predict: a replay from a load is bit-identical") {
    for (const int saved : {60, 25, 135, 137}) {
        CAPTURE(saved);
        Rig r;
        run(r.w, 0, saved);
        const Blob car = saveCar(r.w, CAR), parked = saveCar(r.w, PARKED), ball = saveBall(r.w);

        const int end = std::min(180, saved + 120);
        loadAll(r.w, (uint64_t)saved, car, parked, ball);
        REQUIRE(mcrl_world_rewind_events(r.w, (uint64_t)saved) == MCRL_OK);
        run(r.w, saved, end);
        const Blob x = saveCar(r.w, CAR), xb = saveBall(r.w);
        const McrlCarState xs = getCar(r.w, CAR);
        const McrlBallState xball = getBall(r.w);
        const McrlCarFx xfx = carFx(r.w, CAR), xpfx = carFx(r.w, PARKED);

        loadAll(r.w, (uint64_t)saved, car, parked, ball);
        REQUIRE(mcrl_world_rewind_events(r.w, (uint64_t)saved) == MCRL_OK);
        run(r.w, saved, end);
        CHECK(saveCar(r.w, CAR) == x);
        CHECK(saveBall(r.w) == xb);
        CHECK(sameState(getCar(r.w, CAR), xs));
        const McrlBallState yball = getBall(r.w);
        CHECK(std::memcmp(&xball, &yball, sizeof xball) == 0);
        CHECK(sameFx(carFx(r.w, CAR), xfx));
        CHECK(sameFx(carFx(r.w, PARKED), xpfx));
        if (end == 180) {
            CHECK(xs.ballHits == 2);
            CHECK(xs.body.px > 9);
        }
    }
}

TEST_CASE("predict: car-car contact ids survive the remap") {
    McrlShapes* s = makeShapes();
    McrlWorld* a = makeWorld(s);
    commitScene(a, s);
    McrlCarState c1 = carAt(-8.5, FLOOR_TOP + CAR_REST, 0.5);
    c1.body.vx = 20;
    const McrlCarState c2 = carAt(-4.5, FLOOR_TOP + CAR_REST, 0.5, 90);
    REQUIRE(mcrl_car_add(a, 7, 0, 0, &c1) == MCRL_OK);
    REQUIRE(mcrl_car_add(a, 9, 1, 0, &c2) == MCRL_OK);
    setBall(a, 20.5, FLOOR_TOP + BALL_REST, 20.5);
    int bumped = -1;
    for (int t = 1; t <= 60 && bumped < 0; t++) {
        step(a, 1);
        if (carFx(a, 9).bumps > 0) bumped = t;
    }
    REQUIRE_MESSAGE(bumped > 0, "car 7 bumps car 9");
    const Blob a1 = saveCar(a, 7), a2 = saveCar(a, 9);

    McrlWorld* b = makeWorld(s);
    commitScene(b, s);
    REQUIRE(mcrl_car_add(b, 9, 1, 0, &c2) == MCRL_OK);
    REQUIRE(mcrl_car_add(b, 7, 0, 0, &c1) == MCRL_OK);
    REQUIRE(mcrl_world_set_tick(b, (uint64_t)bumped) == MCRL_OK);
    REQUIRE(mcrl_car_load(b, 7, a1.data()) == MCRL_OK);
    REQUIRE(mcrl_car_load(b, 9, a2.data()) == MCRL_OK);
    CHECK(saveCar(b, 7) == a1);
    CHECK(saveCar(b, 9) == a2);

    blob::CarBlob named{};
    std::memcpy(&named, a1.data(), sizeof named);
    REQUIRE_MESSAGE(named.otherCarId == 9, "the bumper's contact record names car 9");
    McrlWorld* c = makeWorld(s);
    commitScene(c, s);
    REQUIRE(mcrl_car_add(c, 7, 0, 0, &c1) == MCRL_OK);
    REQUIRE(mcrl_world_set_tick(c, (uint64_t)bumped) == MCRL_OK);
    REQUIRE(mcrl_car_load(c, 7, a1.data()) == MCRL_OK);
    blob::CarBlob alone{};
    const Blob c1b = saveCar(c, 7);
    std::memcpy(&alone, c1b.data(), sizeof alone);
    CHECK(alone.otherCarId == -1);
    alone.otherCarId = named.otherCarId;
    CHECK(std::memcmp(&alone, &named, sizeof named) == 0);
    mcrl_world_destroy(c);
    mcrl_world_destroy(b);
    mcrl_world_destroy(a);
    mcrl_shapes_destroy(s);
}

TEST_CASE("predict: rewind_events") {
    Rig r;
    for (int t = 1; t <= 90; t++) {
        setControls(r.w, CAR, 0, 0, false, t == 70);
        step(r.w, 1);
    }
    CHECK(carFx(r.w, CAR).jumps == 1);
    REQUIRE(mcrl_world_set_tick(r.w, 60) == MCRL_OK);
    REQUIRE(mcrl_world_rewind_events(r.w, 60) == MCRL_OK);
    CHECK(carFx(r.w, CAR).jumps == 0);
    const McrlCarState s = getCar(r.w, CAR);
    CHECK(s.ballHits == 0);

    REQUIRE(mcrl_world_set_tick(r.w, 90) == MCRL_OK);
    for (int t = 0; t < 60; t++) step(r.w, 1);
    CHECK(mcrl_world_rewind_events(r.w, 150 - 129) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_world_rewind_events(r.w, 151) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_world_rewind_events(r.w, 150 - 127) == MCRL_OK);
}

TEST_CASE("predict: a wrong blob version is rejected") {
    Rig r;
    run(r.w, 0, 30);
    const Blob car = saveCar(r.w, CAR), ball = saveBall(r.w);
    run(r.w, 30, 40);
    const Blob carNow = saveCar(r.w, CAR), ballNow = saveBall(r.w);
    Blob badCar = car, badBall = ball;
    badCar[0] = 2;
    badBall[0] = 2;
    CHECK(mcrl_car_load(r.w, CAR, badCar.data()) == MCRL_ERR_ARG);
    CHECK(mcrl_ball_load(r.w, badBall.data()) == MCRL_ERR_ARG);
    CHECK(saveCar(r.w, CAR) == carNow);
    CHECK(saveBall(r.w) == ballNow);
    CHECK(mcrl_car_load(r.w, 99, car.data()) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_car_save(r.w, 99, badCar.data()) == MCRL_ERR_NOT_FOUND);
    CHECK(mcrl_car_load(r.w, CAR, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_ball_save(r.w, nullptr) == MCRL_ERR_ARG);
}

TEST_CASE("predict: a load checks the whole blob") {
    Rig r;
    run(r.w, 0, 60);
    const Blob good = saveCar(r.w, CAR), ballGood = saveBall(r.w);
    run(r.w, 60, 61);
    const Blob now = saveCar(r.w, CAR), ballNow = saveBall(r.w);
    const blob::CarBlob g = carBlob(good);
    auto refused = [&](const blob::CarBlob& c, const char* what) {
        CAPTURE(what);
        CHECK(mcrl_car_load(r.w, CAR, bytes(c).data()) == MCRL_ERR_ARG);
        CHECK(saveCar(r.w, CAR) == now);
    };
    blob::CarBlob c = g;
    c.boost = std::nanf("");
    refused(c, "a NaN timer");
    c = g;
    c.rayContactZ[3] = INFINITY;
    refused(c, "an infinite wheel field");
    c = g;
    for (float& v : c.basis) v *= 1.01f;
    refused(c, "a scaled basis");
    c = g;
    for (int i = 0; i < 3; i++) c.basis[3 * i] = -c.basis[3 * i];
    refused(c, "a mirrored basis");
    c = g;
    c.origin[1] = 2e6f;
    refused(c, "a body 2e6 blocks from O");
    c = g;
    c.riserAxis = 9;
    refused(c, "an unknown riser axis");
    c = g;
    c.bits |= blob::IS_DEMOED;
    c.demoRespawnTimer = 0.5f;
    refused(c, "a demoed car RocketSim would respawn in 0.5 s");
    c = g;
    c.preset = 1;
    refused(c, "another preset");
    c = g;
    c.carFlags = blob::THREE_WHEELS;
    refused(c, "three wheels on a four-wheel car");
    CHECK(mcrl_car_load(r.w, CAR, good.data()) == MCRL_OK);

    const McrlCarState third = carAt(-6.5, FLOOR_TOP + CAR_REST, -10.5);
    REQUIRE(mcrl_car_add(r.w, 5, 0, 6, &third) == MCRL_OK);
    CHECK(mcrl_car_load(r.w, 5, good.data()) == MCRL_ERR_ARG);
    const Blob psyclops = saveCar(r.w, 5);
    CHECK(carBlob(psyclops).preset == 6);
    CHECK(carBlob(psyclops).carFlags == blob::THREE_WHEELS);
    CHECK(mcrl_car_load(r.w, CAR, psyclops.data()) == MCRL_ERR_ARG);
    CHECK(mcrl_car_load(r.w, 5, psyclops.data()) == MCRL_OK);

    blob::BallBlob bb{};
    std::memcpy(&bb, ballGood.data(), sizeof bb);
    Blob bad = ballGood;
    bb.parkedAngVel[2] = std::nanf("");
    std::memcpy(bad.data(), &bb, sizeof bb);
    CHECK(mcrl_ball_load(r.w, bad.data()) == MCRL_ERR_ARG);
    std::memcpy(&bb, ballGood.data(), sizeof bb);
    bb.parked = 2;
    std::memcpy(bad.data(), &bb, sizeof bb);
    CHECK(mcrl_ball_load(r.w, bad.data()) == MCRL_ERR_ARG);
    std::memcpy(&bb, ballGood.data(), sizeof bb);
    bb.basis[4] = 0;
    std::memcpy(bad.data(), &bb, sizeof bb);
    CHECK(mcrl_ball_load(r.w, bad.data()) == MCRL_ERR_ARG);
    CHECK(saveBall(r.w) == ballNow);
}

TEST_CASE("predict: random blobs never load garbage") {
    Rig r;
    run(r.w, 0, 60);
    const Blob good = saveCar(r.w, CAR), ballGood = saveBall(r.w);
    std::mt19937 rng(7);
    std::uniform_int_distribution<int> byte(0, 255);
    int loaded = 0, refused = 0;
    for (int i = 0; i < 1500; i++) {
        const bool car = i % 2 == 0;
        Blob b = car ? good : ballGood;
        if (i % 3 == 0) {
            for (uint8_t& x : b) x = (uint8_t)byte(rng);
            b[0] = MCRL_BLOB_VERSION;
            if (car) b[4] = good[4], b[5] = good[5];
        } else {
            const int n = 1 + byte(rng) % 8;
            for (int k = 0; k < n; k++) b[(size_t)byte(rng) % b.size()] = (uint8_t)byte(rng);
        }
        const int32_t rc = car ? mcrl_car_load(r.w, CAR, b.data()) : mcrl_ball_load(r.w, b.data());
        CHECK((rc == MCRL_OK || rc == MCRL_ERR_ARG));
        if (rc != MCRL_OK) {
            refused++;
            continue;
        }
        loaded++;
        REQUIRE(mcrl_step(r.w, 1, nullptr) == 1);
        CHECK(finite(getCar(r.w, CAR).body));
        CHECK(finite(getBall(r.w).body));
        REQUIRE(mcrl_car_load(r.w, CAR, good.data()) == MCRL_OK);
        REQUIRE(mcrl_ball_load(r.w, ballGood.data()) == MCRL_OK);
    }
    CHECK(loaded > 0);
    CHECK(refused > 0);
}

namespace {
Blob saveSoccar(McrlWorld* w) {
    Blob b((size_t)mcrl_sizeof(18));
    REQUIRE(mcrl_soccar_save(w, b.data()) == MCRL_OK);
    return b;
}

struct Stadium {
    McrlWorld* w = afx::makeStadium();
    Stadium() {
        const McrlCarState c = carAt(0.5, 64.34, -20.5);
        REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
        REQUIRE(mcrl_car_add(w, 2, 1, 0, &c) == MCRL_OK);
        REQUIRE(mcrl_soccar_kickoff(w, 3, 60) == MCRL_OK);
        setControls(w, 1, 1, 0, true);
        setControls(w, 2, 1, 0.2f, false);
    }
    ~Stadium() { CHECK(mcrl_world_destroy(w) == MCRL_OK); }
    Stadium(const Stadium&) = delete;
    Stadium& operator=(const Stadium&) = delete;
};

}

TEST_CASE("predict: soccar replay is bit-identical") {
    Stadium s;
    step(s.w, 30);
    const Blob c1 = saveCar(s.w, 1), c2 = saveCar(s.w, 2), ball = saveBall(s.w), arena = saveSoccar(s.w);
    auto replay = [&] {
        REQUIRE(mcrl_world_set_tick(s.w, 30) == MCRL_OK);
        REQUIRE(mcrl_car_load(s.w, 1, c1.data()) == MCRL_OK);
        REQUIRE(mcrl_car_load(s.w, 2, c2.data()) == MCRL_OK);
        REQUIRE(mcrl_soccar_load(s.w, arena.data()) == MCRL_OK);
        REQUIRE(mcrl_ball_load(s.w, ball.data()) == MCRL_OK);
        REQUIRE(mcrl_world_rewind_events(s.w, 30) == MCRL_OK);
        const McrlCarState still = getCar(s.w, 1);
        step(s.w, 29);
        CHECK(getCar(s.w, 1).body.px == still.body.px);
        CHECK(getCar(s.w, 1).body.pz == still.body.pz);
        step(s.w, 91);
        CHECK(getCar(s.w, 1).body.pz > still.body.pz + 2);
        return std::vector<Blob>{saveCar(s.w, 1), saveCar(s.w, 2), saveBall(s.w), saveSoccar(s.w)};
    };
    const std::vector<Blob> x = replay();
    const std::vector<Blob> y = replay();
    CHECK(x[0] == y[0]);
    CHECK(x[1] == y[1]);
    CHECK(x[2] == y[2]);
    CHECK(x[3] == y[3]);
    CHECK(afx::getSoccar(s.w).lockTicks == 0);
}

namespace {
constexpr McrlMaterial ICE_MATERIAL{0.3175f, 0.4229f, 0, 0.2383f, -1, 0.2383f, -1, 0, 0, 0};

struct ExtrasRig {
    McrlShapes* s = nullptr;
    McrlWorld* w = nullptr;

    ExtrasRig() {
        s = makeShapes();
        addIce(s);
        w = makeWorld(s);
        registerMaterial(s, 1, ICE_MATERIAL);
        const uint8_t water = (uint8_t)(MCRL_FLUID_WATER | 9 << 2);
        const Scene sc = Scene().fill(-16, 63, -16, 127, 63, 31, ICE).fluid(24, 64, -16, 127, 65, 31, water);
        sc.commitRange(w, s, -1, 3, -1, 7, 5, 1);
        REQUIRE(mcrl_world_set_extras(w, MCRL_EXTRAS_SURFACES | MCRL_EXTRAS_FLUIDS) == MCRL_OK);
        McrlCarState c = carAt(0.5, FLOOR_TOP + CAR_REST, 0.5);
        c.body.vx = 20;
        REQUIRE(mcrl_car_add(w, CAR, 0, 0, &c) == MCRL_OK);
        setControls(w, CAR, 1);
        setBall(w, 30.5, FLOOR_TOP + BALL_REST, 0.5);
    }
    ~ExtrasRig() {
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
        CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
    }
    ExtrasRig(const ExtrasRig&) = delete;
    ExtrasRig& operator=(const ExtrasRig&) = delete;
};
}

TEST_CASE("predict: a replay with surfaces and fluids on is bit-identical") {
    ExtrasRig a, b;
    step(a.w, 60);
    const Blob car = saveCar(a.w, CAR), ball = saveBall(a.w);
    for (ExtrasRig* r : {&a, &b}) {
        REQUIRE(mcrl_world_set_tick(r->w, 60) == MCRL_OK);
        REQUIRE(mcrl_car_load(r->w, CAR, car.data()) == MCRL_OK);
        REQUIRE(mcrl_ball_load(r->w, ball.data()) == MCRL_OK);
    }
    bool wet = false;
    for (int t = 61; t <= 240; t++) {
        CAPTURE(t);
        step(a.w, 1);
        step(b.w, 1);
        REQUIRE(saveCar(b.w, CAR) == saveCar(a.w, CAR));
        REQUIRE(saveBall(b.w) == saveBall(a.w));
        wet = wet || carFx(a.w, CAR).wet.immersion > 0;
    }
    CHECK(wet);
    CHECK(getCar(a.w, CAR).body.px > 24);
}
