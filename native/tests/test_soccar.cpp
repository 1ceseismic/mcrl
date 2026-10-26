#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <set>
#include <string>
#include <vector>
#include "arena_fixtures.h"
#include "mcrl.h"
#include "soccar.h"
#include "terrain_fixtures.h"
#include "world.h"

using namespace fx;

namespace {
constexpr double CAR_KICKOFF_Y = 64.34;
constexpr double BALL_REST_Y = 65.863;
constexpr double SPAWN_BOOST = 100.0 / 3;
constexpr double TICK = 1.0 / 120;

#define CHECK_NEAR(a, b, tol) CHECK_MESSAGE(std::abs((double)(a) - (double)(b)) <= (tol), #a " = ", (a), ", want ", (b))

struct XZ {
    double x, z;
};

const std::vector<XZ> KICKOFF = {{-40.96, -51.2}, {40.96, -51.2}, {-5.12, -76.8}, {5.12, -76.8}, {0, -92.16}};
const std::vector<XZ> RESPAWN = {{-46.08, -92.16}, {-53.76, -92.16}, {46.08, -92.16}, {53.76, -92.16}};

int spotOf(double x, double z, const std::vector<XZ>& spots, double sx = 1, double sz = 1, double tol = 1e-3) {
    for (size_t i = 0; i < spots.size(); i++)
        if (std::abs(x - sx * spots[i].x) <= tol && std::abs(z - sz * spots[i].z) <= tol) return (int)i;
    return -1;
}

struct Stadium {
    McrlWorld* w = afx::makeStadium();
    ~Stadium() { CHECK(mcrl_world_destroy(w) == MCRL_OK); }
    Stadium() = default;
    Stadium(const Stadium&) = delete;
    Stadium& operator=(const Stadium&) = delete;
};

void addCar(McrlWorld* w, int32_t id, int32_t team, double x, double z, double yawDeg = 0, float boost = 0) {
    McrlCarState c = carAt(x, CAR_KICKOFF_Y, z, yawDeg);
    c.boost = boost;
    REQUIRE(mcrl_car_add(w, id, team, 0, &c) == MCRL_OK);
}

}

TEST_CASE("soccar: bad input") {
    const std::vector<std::vector<uint8_t>> good = afx::syntheticFiles();
    const uint8_t* ptrs[afx::FILES];
    int32_t sizes[afx::FILES];
    for (int i = 0; i < afx::FILES; i++) {
        ptrs[i] = good[i].data();
        sizes[i] = (int32_t)good[i].size();
    }
    uint32_t hashes[afx::FILES];
    CHECK(mcrl_arena_meshes_load(ptrs, sizes, 0, hashes) == nullptr);
    CHECK(std::string(mcrl_last_error()) != "");
    CHECK(mcrl_arena_meshes_load(nullptr, sizes, 1, hashes) == nullptr);
    CHECK(mcrl_arena_meshes_load(ptrs, nullptr, 1, hashes) == nullptr);

    std::vector<uint8_t> bad(8 + 3, 0);
    const int32_t counts[2] = {10, 3};
    std::memcpy(bad.data(), counts, 8);
    ptrs[3] = bad.data();
    sizes[3] = (int32_t)bad.size();
    CHECK(mcrl_arena_meshes_load(ptrs, sizes, afx::FILES, hashes) == nullptr);
    CHECK_MESSAGE(std::string(mcrl_last_error()).find("file 3") != std::string::npos, mcrl_last_error());
    std::vector<uint8_t> badIndex = good[5];
    const int32_t past = 1000;
    std::memcpy(badIndex.data() + 8, &past, 4);
    ptrs[3] = good[3].data();
    sizes[3] = (int32_t)good[3].size();
    ptrs[5] = badIndex.data();
    CHECK(mcrl_arena_meshes_load(ptrs, sizes, afx::FILES, hashes) == nullptr);
    CHECK_MESSAGE(std::string(mcrl_last_error()).find("file 5") != std::string::npos, mcrl_last_error());

    CHECK(mcrl_world_create_soccar(nullptr, 0, 64, 0) == nullptr);
    CHECK(std::string(mcrl_last_error()) != "");

    REQUIRE(mcrl_init() == MCRL_OK);
    McrlWorld* v = mcrl_world_create(nullptr, 0, 0, 0);
    REQUIRE(v != nullptr);
    McrlSoccarState st{};
    McrlCarState cs{};
    float xyz[102];
    uint8_t big[34];
    CHECK(mcrl_soccar_kickoff(v, 7, 360) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_get_state(v, &st) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_respawn_state(v, 0, 0, &cs) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_pad_layout(v, xyz, big) == MCRL_ERR_ARG);
    CHECK(v->soccar == nullptr);
    CHECK(v->arena->GetBoostPads().empty());
    CHECK(mcrl_world_destroy(v) == MCRL_OK);

    Stadium s;
    CHECK(s.w->soccar != nullptr);
    CHECK(mcrl_soccar_kickoff(s.w, 7, -1) == MCRL_ERR_ARG);
    CHECK(afx::getSoccar(s.w).kickoffs == 0);
    CHECK(mcrl_soccar_kickoff(nullptr, 7, 0) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_get_state(s.w, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_respawn_state(s.w, 0, 0, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_respawn_state(s.w, 2, 0, &cs) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_respawn_state(s.w, -1, 0, &cs) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_respawn_state(s.w, 0, 4, &cs) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_respawn_state(s.w, 0, -1, &cs) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_pad_layout(s.w, nullptr, big) == MCRL_ERR_ARG);
    CHECK(mcrl_soccar_pad_layout(s.w, xyz, nullptr) == MCRL_ERR_ARG);
    for (int32_t id = 100; id < 110; id++) addCar(s.w, id, 0, -40 + 6 * (id - 100), 20);
    CHECK(mcrl_soccar_kickoff(s.w, 7, 0) == MCRL_ERR_ARG);
    CHECK_MESSAGE(std::string(mcrl_last_error()).find("at most 9 cars per team") != std::string::npos,
                  mcrl_last_error());
    CHECK(afx::getSoccar(s.w).kickoffs == 0);
    CHECK(afx::getSoccar(s.w).ballOutOfPlay == 1);
    CHECK(getCar(s.w, 100).body.px == -40);
    REQUIRE(mcrl_car_remove(s.w, 109) == MCRL_OK);
    for (int32_t id = 200; id < 209; id++) addCar(s.w, id, 1, -40 + 6 * (id - 200), -20);
    REQUIRE(mcrl_soccar_kickoff(s.w, 7, 0) == MCRL_OK);
    CHECK(afx::getSoccar(s.w).kickoffs == 1);
    for (int32_t id = 100; id < 109; id++) {
        CAPTURE(id);
        const McrlCarState c = getCar(s.w, id);
        CHECK((spotOf(c.body.px, c.body.pz, KICKOFF) >= 0 || spotOf(c.body.px, c.body.pz, RESPAWN) >= 0));
    }
    step(s.w, 2);

    CHECK(sizeof(McrlSoccarState) == 504);
    CHECK(mcrl_sizeof(12) == 504);
    CHECK(mcrl_sizeof(13) == MCRL_ERR_ARG);
}

TEST_CASE("soccar: kickoff") {
    Stadium s;
    addCar(s.w, 1, 0, 10, 10);
    addCar(s.w, 2, 1, -10, -10);
    const McrlSoccarState s0 = afx::getSoccar(s.w);
    CHECK(s0.ballOutOfPlay == 1);
    CHECK(s0.kickoffs == 0);
    CHECK(s0.lockTicks == 0);
    CHECK(s0.goals == 0);
    CHECK(s0.goalTeam == -1);
    CHECK(s0.goalToucher == -1);

    REQUIRE(mcrl_soccar_kickoff(s.w, 7, 360) == MCRL_OK);
    const McrlCarState c1 = getCar(s.w, 1), c2 = getCar(s.w, 2);
    const int i1 = spotOf(c1.body.px, c1.body.pz, KICKOFF), i2 = spotOf(c2.body.px, c2.body.pz, KICKOFF, -1, -1);
    INFO("car 1 at (", c1.body.px, ", ", c1.body.pz, "), car 2 at (", c2.body.px, ", ", c2.body.pz, ")");
    CHECK(i1 >= 0);
    CHECK(i2 >= 0);
    for (const McrlCarState& c : {c1, c2}) {
        CHECK_NEAR(c.body.py, CAR_KICKOFF_Y, 1e-3);
        CHECK_NEAR(c.boost, SPAWN_BOOST, 0.01);
        CHECK(speed(c.body) == 0);
    }
    const McrlBallState b = getBall(s.w);
    CHECK_NEAR(b.body.px, 0, 1e-3);
    CHECK_NEAR(b.body.py, BALL_REST_Y, 1e-3);
    CHECK_NEAR(b.body.pz, 0, 1e-3);
    CHECK(speed(b.body) == 0);
    CHECK((b.body.wx == 0 && b.body.wy == 0 && b.body.wz == 0));
    const McrlSoccarState s1 = afx::getSoccar(s.w);
    CHECK(std::count(std::begin(s1.padActive), std::end(s1.padActive), 1) == 34);
    CHECK(s1.kickoffs == 1);
    CHECK(s1.lockTicks == 360);
    CHECK(s1.ballOutOfPlay == 0);
}

TEST_CASE("soccar: kickoff is the last set state") {
    Stadium s;
    addCar(s.w, 1, 0, 10, 10);
    setBall(s.w, 0, BALL_REST_Y, 20);
    step(s.w, 5);
    REQUIRE(mcrl_soccar_kickoff(s.w, 7, 360) == MCRL_OK);
    REQUIRE(mcrl_debug_poison(s.w, -1) == MCRL_OK);
    uint32_t flags = 0;
    REQUIRE(mcrl_step(s.w, 1, &flags) == 1);
    CHECK((flags & MCRL_STEP_BALL_RESTORED) != 0);
    const McrlBallState b = getBall(s.w);
    CHECK_NEAR(b.body.pz, 0, 1e-3);
    CHECK_NEAR(b.body.py, BALL_REST_Y, 1e-3);

    const McrlCarState moved = carAt(10, CAR_KICKOFF_Y, 10);
    REQUIRE(mcrl_car_set_state(s.w, 1, &moved) == MCRL_OK);
    step(s.w, 3);
    REQUIRE(mcrl_soccar_kickoff(s.w, 7, 360) == MCRL_OK);
    const McrlCarState spot = getCar(s.w, 1);
    REQUIRE(spotOf(spot.body.px, spot.body.pz, KICKOFF) >= 0);
    REQUIRE(mcrl_debug_poison(s.w, 1) == MCRL_OK);
    REQUIRE(mcrl_step(s.w, 1, &flags) == 1);
    CHECK((flags & MCRL_STEP_RESTORED) != 0);
    const McrlCarState c = getCar(s.w, 1);
    CHECK((c.flags & MCRL_CAR_RESTORED) != 0);
    CHECK_NEAR(c.body.px, spot.body.px, 1e-3);
    CHECK_NEAR(c.body.pz, spot.body.pz, 1e-3);
}

TEST_CASE("soccar: kickoff lock counts down once per tick") {
    constexpr double BURN = SPAWN_BOOST / 120;
    Stadium s;
    addCar(s.w, 1, 0, 0, -20);
    REQUIRE(mcrl_soccar_kickoff(s.w, 3, 3) == MCRL_OK);
    setControls(s.w, 1, 1, 0, true);
    CHECK(s.w->soccar->kickoffs == 1);
    for (int t = 1; t <= 3; t++) {
        CAPTURE(t);
        CHECK(s.w->soccar->lockTicks == 4 - t);
        step(s.w, 1);
        CHECK(s.w->soccar->lockTicks == 3 - t);
        CHECK(afx::getSoccar(s.w).lockTicks == (uint32_t)(3 - t));
        CHECK_NEAR(getCar(s.w, 1).boost, SPAWN_BOOST, 1e-4);
    }
    step(s.w, 1);
    CHECK(s.w->soccar->lockTicks == 0);
    CHECK_NEAR(getCar(s.w, 1).boost, SPAWN_BOOST - BURN, 1e-3);

    REQUIRE(mcrl_soccar_kickoff(s.w, 3, 2) == MCRL_OK);
    CHECK(s.w->soccar->kickoffs == 2);
    REQUIRE(mcrl_step(s.w, 5, nullptr) == 5);
    CHECK(s.w->soccar->lockTicks == 0);
    CHECK_NEAR(getCar(s.w, 1).boost, SPAWN_BOOST - 3 * BURN, 1e-3);

    setControls(s.w, 1, 0);
    REQUIRE(mcrl_soccar_kickoff(s.w, 3, 1) == MCRL_OK);
    setControls(s.w, 1, 1, 0, true);
    step(s.w, 3);
    CHECK_NEAR(getCar(s.w, 1).boost, SPAWN_BOOST - 2 * BURN, 1e-3);
}

TEST_CASE("soccar: pads") {
    Stadium s;
    float xyz[102];
    uint8_t big[34];
    REQUIRE(mcrl_soccar_pad_layout(s.w, xyz, big) == MCRL_OK);
    SUBCASE("layout") {
        const std::vector<XZ> bigSpots = {{-71.68, 0}, {71.68, 0}, {-61.44, 81.92}, {61.44, 81.92}, {-61.44, -81.92},
                                          {61.44, -81.92}};
        std::set<int> seen;
        for (int i = 0; i < 34; i++) {
            CAPTURE(i);
            CHECK(big[i] == (i < 6 ? 1 : 0));
            CHECK_NEAR(xyz[3 * i + 1], i < 6 ? 65.46 : 65.4, 1e-4);
            if (i < 6) {
                const int k = spotOf(xyz[3 * i], xyz[3 * i + 2], bigSpots);
                CHECK(k >= 0);
                seen.insert(k);
            }
        }
        CHECK(seen.size() == 6);
        CHECK(s.w->arena->GetBoostPads().size() == 34);
    }
    SUBCASE("big pad 0") {
        McrlCarState c = carAt(xyz[0], CAR_KICKOFF_Y, xyz[2]);
        c.boost = 0;
        REQUIRE(mcrl_car_add(s.w, 3, 0, 0, &c) == MCRL_OK);
        step(s.w, 1);
        CHECK_NEAR(getCar(s.w, 3).boost, 100, 1e-4);
        McrlSoccarState st = afx::getSoccar(s.w);
        CHECK(st.padActive[0] == 0);
        CHECK_NEAR(st.padCooldown[0], 10 - TICK, 1e-3);
        CHECK(st.padPickups[0] == 1);
        CHECK(st.padCar[0] == 3);
        for (int i = 1; i < 34; i++) {
            CAPTURE(i);
            CHECK(st.padActive[i] == 1);
            CHECK(st.padCooldown[i] == 0);
            CHECK(st.padPickups[i] == 0);
            CHECK(st.padCar[i] == -1);
        }
        int back = 0;
        for (int t = 2; t <= 1300 && back == 0; t++) {
            step(s.w, 1);
            st = afx::getSoccar(s.w);
            if (st.padActive[0]) back = t;
            if (t == 1201) CHECK(st.padCooldown[0] == 0);
            if (t == 1200) CHECK(st.padCooldown[0] > 0);
        }
        CHECK(back == 1202);
        st = afx::getSoccar(s.w);
        CHECK(st.padCooldown[0] == 0);
        CHECK(st.padPickups[0] == 1);
    }
    SUBCASE("small pad 6") {
        McrlCarState c = carAt(xyz[18], CAR_KICKOFF_Y, xyz[20]);
        c.boost = 0;
        REQUIRE(mcrl_car_add(s.w, 4, 1, 0, &c) == MCRL_OK);
        step(s.w, 1);
        CHECK_NEAR(getCar(s.w, 4).boost, 12, 1e-4);
        const McrlSoccarState st = afx::getSoccar(s.w);
        CHECK(st.padActive[6] == 0);
        CHECK_NEAR(st.padCooldown[6], 4 - TICK, 1e-3);
        CHECK(st.padPickups[6] == 1);
        CHECK(st.padCar[6] == 4);
    }
    SUBCASE("a pickup on the tick the pad comes back") {
        McrlCarState c = carAt(xyz[18], CAR_KICKOFF_Y, xyz[20]);
        c.boost = 0;
        REQUIRE(mcrl_car_add(s.w, 5, 0, 0, &c) == MCRL_OK);
        McrlControls ctl{};
        ctl.boost = 1;
        REQUIRE(mcrl_car_set_controls(s.w, 5, &ctl) == MCRL_OK);
        int second = 0;
        for (int t = 1; t <= 4 * 120 + 5; t++) {
            step(s.w, 1);
            McrlCarState put = carAt(xyz[18], CAR_KICKOFF_Y, xyz[20]);
            put.boost = getCar(s.w, 5).boost;
            REQUIRE(mcrl_car_set_state(s.w, 5, &put) == MCRL_OK);
            if (second == 0 && afx::getSoccar(s.w).padPickups[6] == 2) second = t;
        }
        CHECK(second == 481);
        const McrlSoccarState st = afx::getSoccar(s.w);
        CHECK(st.padPickups[6] == 2);
        CHECK(st.padActive[6] == 0);
        CHECK(st.padCar[6] == 5);
    }
}

namespace {
constexpr double GOAL_LINE = 104.31;

void shootBall(McrlWorld* w, double z, double vz) {
    McrlBallState b{};
    b.body.py = BALL_REST_Y;
    b.body.pz = z;
    b.body.vz = vz;
    b.body.qw = 1;
    REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
}

struct GoalRun {
    int tick = -1;
    double zBefore = 0;
    uint32_t goalsBefore = 0;
};
GoalRun untilGoal(McrlWorld* w, uint32_t goals, int maxTicks = 240) {
    GoalRun r;
    for (int t = 1; t <= maxTicks; t++) {
        r.zBefore = getBall(w).body.pz;
        r.goalsBefore = afx::getSoccar(w).goals;
        step(w, 1);
        if (afx::getSoccar(w).goals == goals) {
            r.tick = t;
            return r;
        }
    }
    return r;
}

void checkBallOut(McrlWorld* w) {
    const McrlBallState b = getBall(w);
    CHECK_NEAR(b.body.px, 0, 1e-4);
    CHECK_NEAR(b.body.py, BALL_REST_Y, 1e-4);
    CHECK_NEAR(b.body.pz, 0, 1e-4);
    CHECK(speed(b.body) == 0);
    CHECK((b.body.wx == 0 && b.body.wy == 0 && b.body.wz == 0));
    CHECK(afx::getSoccar(w).ballOutOfPlay == 1);
}
}

TEST_CASE("soccar: goal latch blue") {
    Stadium s;
    REQUIRE(mcrl_soccar_kickoff(s.w, 1, 0) == MCRL_OK);
    shootBall(s.w, 96, 40);
    const GoalRun g = untilGoal(s.w, 1);
    REQUIRE(g.tick > 0);
    CHECK(g.goalsBefore == 0);
    CHECK(g.zBefore <= GOAL_LINE);
    McrlSoccarState st = afx::getSoccar(s.w);
    CHECK(st.goalTeam == 0);
    CHECK(st.goalZ > GOAL_LINE);
    CHECK(st.goalZ < GOAL_LINE + 40.0 / 120 + 1e-3);
    CHECK_NEAR(st.goalX, 0, 1e-3);
    CHECK(st.goalVz > 30);
    CHECK(st.goalToucher == -1);
    checkBallOut(s.w);
    step(s.w, 600);
    st = afx::getSoccar(s.w);
    CHECK(st.goals == 1);
    checkBallOut(s.w);
}

TEST_CASE("soccar: worlds share a mesh set") {
    McrlWorld* first = afx::makeStadium();
    McrlWorld* second = afx::makeStadium();
    REQUIRE(mcrl_soccar_kickoff(first, 1, 0) == MCRL_OK);
    REQUIRE(mcrl_soccar_kickoff(second, 1, 0) == MCRL_OK);
    step(first, 10);
    CHECK(mcrl_world_destroy(first) == MCRL_OK);
    McrlBallState b{};
    b.body.px = -40;
    b.body.py = BALL_REST_Y;
    b.body.pz = -90;
    b.body.vz = -40;
    b.body.qw = 1;
    REQUIRE(mcrl_ball_set_state(second, &b) == MCRL_OK);
    step(second, 120);
    CHECK(getBall(second).body.pz > -102.4);
    CHECK(getBall(second).body.vz > 0);
    CHECK(afx::getSoccar(second).goals == 0);
    CHECK(mcrl_world_destroy(second) == MCRL_OK);
}

TEST_CASE("soccar: kickoff clears the touch") {
    Stadium s;
    addCar(s.w, 7, 0, 0, -20);
    REQUIRE(mcrl_soccar_kickoff(s.w, 1, 0) == MCRL_OK);
    setBall(s.w, 0, BALL_REST_Y, 20);
    McrlCarState c = carAt(0, CAR_KICKOFF_Y, 14, -90);
    c.body.vz = 20;
    REQUIRE(mcrl_car_set_state(s.w, 7, &c) == MCRL_OK);
    setControls(s.w, 7, 1);
    step(s.w, 30);
    REQUIRE(getCar(s.w, 7).ballHits >= 1);
    McrlBallFx f{};
    REQUIRE(mcrl_ball_get_fx(s.w, &f) == MCRL_OK);
    CHECK(f.lastTouchTeam == 0);

    REQUIRE(mcrl_soccar_kickoff(s.w, 1, 0) == MCRL_OK);
    REQUIRE(mcrl_ball_get_fx(s.w, &f) == MCRL_OK);
    CHECK(f.lastTouchTeam == -1);
    const McrlCarState away = carAt(-30, CAR_KICKOFF_Y, -30);
    REQUIRE(mcrl_car_set_state(s.w, 7, &away) == MCRL_OK);
    setControls(s.w, 7, 0);
    shootBall(s.w, 96, 40);
    REQUIRE(untilGoal(s.w, 1).tick > 0);
    CHECK(afx::getSoccar(s.w).goalToucher == -1);
}
