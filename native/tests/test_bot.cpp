#include <doctest/doctest.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <random>
#include <set>
#include <string>
#include <utility>
#include <vector>
#include "arena_fixtures.h"
#include "bot.h"
#include "mcrl.h"
#include "soccar.h"
#include "terrain_fixtures.h"
#include "world.h"
using namespace fx;
namespace {
constexpr double CAR_Y = 64.34;
constexpr double POS_X = 1.0 / 4096, POS_Y = 1.0 / 5120, POS_Z = 1.0 / 2044, VEL = 1.0 / 2300, ANG = 1.0 / 5.5;

struct R3 {
    double x, y, z;
};
R3 rsPos(double x, double y, double z) { return {50 * (x - afx::OX), 50 * (z - afx::OZ), 50 * (y - afx::OY)}; }
R3 rsVel(double x, double y, double z) { return {50 * x, 50 * z, 50 * y}; }
R3 rsAng(double x, double y, double z) { return {-x, -z, -y}; }
R3 inv(R3 v, bool on) { return on ? R3{-v.x, -v.y, v.z} : v; }

struct Body {
    double p[3], v[3], w[3];
};
struct CarIn {
    Body b;
    int team;
    double boost;
    bool ground = true, flip = true, demoed = false;
};

void addVec(std::vector<double>& e, R3 v, double cx, double cy, double cz) {
    e.push_back(v.x * cx);
    e.push_back(v.y * cy);
    e.push_back(v.z * cz);
}
void addPlayer(std::vector<double>& e, const CarIn& c, bool invert) {
    addVec(e, inv(rsPos(c.b.p[0], c.b.p[1], c.b.p[2]), invert), POS_X, POS_Y, POS_Z);
    addVec(e, inv(R3{0, c.team == 0 ? 1.0 : -1.0, 0}, invert), 1, 1, 1);
    addVec(e, inv(R3{0, 0, 1}, invert), 1, 1, 1);
    addVec(e, inv(rsVel(c.b.v[0], c.b.v[1], c.b.v[2]), invert), VEL, VEL, VEL);
    addVec(e, inv(rsAng(c.b.w[0], c.b.w[1], c.b.w[2]), invert), ANG, ANG, ANG);
    e.push_back(c.boost / 100);
    e.push_back(c.ground ? 1 : 0);
    e.push_back(c.flip ? 1 : 0);
    e.push_back(c.demoed ? 1 : 0);
}

struct BotScene {
    McrlWorld* w = afx::makeStadium();
    Body ball{{10, 70, -20}, {2, -1, 4}, {0.5, 1, -2}};
    CarIn cars[5] = {
        {},
        {{{-4, CAR_Y, 30}, {6, 0, -8}, {0, 0.3, 0}}, 1, 40},
        {{{12, CAR_Y, -18}, {0, 0, 20}, {0, 0, 0}}, 0, 100},
        {{{-60, CAR_Y, 80}, {0, 0, 0}, {0, 0, 0}}, 0, 20},
        {{{11, CAR_Y, -20}, {0, 0, 0}, {0, 0, 0}}, 1, 50},
    };
    std::set<int> takenPads = {4, 9};

    BotScene() {
        for (int id = 1; id <= 4; id++) {
            McrlCarState c = carAt(0, CAR_Y, -40.0 + 20 * id);
            REQUIRE(mcrl_car_add(w, id, cars[id].team, 0, &c) == MCRL_OK);
        }
        REQUIRE(mcrl_soccar_kickoff(w, 0, 0) == MCRL_OK);
        REQUIRE(mcrl_step(w, 120, nullptr) == 120);
        McrlBallState bs{};
        setBody(bs.body, ball, nullptr);
        REQUIRE(mcrl_ball_set_state(w, &bs) == MCRL_OK);
        for (int id = 1; id <= 4; id++) {
            McrlCarState s{};
            REQUIRE(mcrl_soccar_respawn_state(w, cars[id].team, 0, &s) == MCRL_OK);
            McrlCarState c = s;
            setBody(c.body, cars[id].b, &s.body);
            c.boost = (float)cars[id].boost;
            REQUIRE(mcrl_car_set_state(w, id, &c) == MCRL_OK);
        }
        const auto& pads = w->arena->GetBoostPads();
        for (int i : takenPads) {
            RocketSim::BoostPadState st = pads[i]->GetState();
            st.isActive = false;
            st.cooldown = 5;
            pads[i]->SetState(st);
        }
    }
    ~BotScene() { CHECK(mcrl_world_destroy(w) == MCRL_OK); }
    BotScene(const BotScene&) = delete;
    BotScene& operator=(const BotScene&) = delete;

    static void setBody(McrlBody& out, const Body& b, const McrlBody* rot) {
        out.px = b.p[0];
        out.py = b.p[1];
        out.pz = b.p[2];
        if (rot) {
            out.qw = rot->qw;
            out.qx = rot->qx;
            out.qy = rot->qy;
            out.qz = rot->qz;
        } else {
            out.qw = 1;
        }
        out.vx = b.v[0];
        out.vy = b.v[1];
        out.vz = b.v[2];
        out.wx = b.w[0];
        out.wy = b.w[1];
        out.wz = b.w[2];
    }

    std::vector<double> expected(int self, int opponent, int prev) const {
        const bool o = cars[self].team == 1;
        std::vector<double> e;
        addVec(e, inv(rsPos(ball.p[0], ball.p[1], ball.p[2]), o), POS_X, POS_Y, POS_Z);
        addVec(e, inv(rsVel(ball.v[0], ball.v[1], ball.v[2]), o), VEL, VEL, VEL);
        addVec(e, inv(rsAng(ball.w[0], ball.w[1], ball.w[2]), o), ANG, ANG, ANG);
        for (int k = 0; k < 8; k++) {
            if (prev < 0) {
                e.push_back(0);
            } else {
                const mcrl::bot::Action& a = mcrl::bot::actions()[prev];
                const float v[8] = {a.throttle, a.steer, a.pitch, a.yaw, a.roll, a.jump, a.boost, a.handbrake};
                e.push_back(v[k]);
            }
        }
        for (int i = 0; i < 34; i++) {
            const int obsPad = o ? 33 - i : i;
            e.push_back(takenPads.count(mcrl::bot::OBS_PAD_TO_SOCCAR[obsPad]) ? 0 : 1);
        }
        addPlayer(e, cars[self], o);
        if (opponent > 0) {
            addPlayer(e, cars[opponent], o);
        } else {
            const double g[19] = {0, 4608 * POS_Y, 17 * POS_Z, 0, -1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1};
            e.insert(e.end(), g, g + 19);
        }
        return e;
    }
};

std::array<float, 89> observe(McrlWorld* w, int32_t carId, int32_t prev) {
    std::array<float, 89> out{};
    REQUIRE_MESSAGE(mcrl_bot_observe(w, carId, prev, out.data()) == MCRL_OK, std::string(mcrl_last_error()));
    return out;
}

void checkAll(const std::array<float, 89>& got, const std::vector<double>& want) {
    REQUIRE(want.size() == 89);
    for (int i = 0; i < 89; i++)
        CHECK_MESSAGE(std::abs(got[i] - want[i]) <= 1e-5, "obs[", i, "] = ", got[i], ", want ", want[i]);
}

#define CHECK_OBS(got, i, want) CHECK_MESSAGE(std::abs((got)[i] - (want)) <= 1e-5, "obs[", i, "] = ", (got)[i])

}

TEST_CASE("bot obs: golden orange") {
    BotScene s;
    auto got = observe(s.w, 1, 25);
    checkAll(got, s.expected(1, 2, 25));
    CHECK_OBS(got, 0, -0.12207031);
    CHECK_OBS(got, 1, 0.1953125);
    CHECK_OBS(got, 2, 0.14677104);
    const float row25[8] = {1, -1, -1, -1, -1, 0, 1, 0};
    for (int k = 0; k < 8; k++) CHECK_OBS(got, 9 + k, row25[k]);
    CHECK_OBS(got, 17 + 28, 0);
    CHECK_OBS(got, 17 + 30, 0);
    CHECK_OBS(got, 17 + 3, 1);
    CHECK_OBS(got, 51, 0.048828125);
    CHECK_OBS(got, 52, -0.29296875);
    CHECK_OBS(got, 53, 0.0083170254);
    CHECK_OBS(got, 54, 0);
    CHECK_OBS(got, 55, 1);
    CHECK_OBS(got, 56, 0);
    CHECK_OBS(got, 66, 0.4);
    CHECK_OBS(got, 67, 1);
    CHECK_OBS(got, 68, 1);
    CHECK_OBS(got, 69, 0);
    CHECK_OBS(got, 70, -0.146484375);
    CHECK_OBS(got, 71, 0.17578125);
    CHECK_OBS(got, 72, 0.0083170254);
    CHECK_OBS(got, 79, 0);
    CHECK_OBS(got, 80, -0.43478261);
    CHECK_OBS(got, 81, 0);
    CHECK_OBS(got, 85, 1);
}

namespace {
McrlBotPolicy* randomPolicy(std::vector<int32_t> dims, unsigned seed) {
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> u(-0.3f, 0.3f);
    std::vector<std::vector<float>> params;
    std::vector<const float*> ptrs;
    for (size_t l = 0; l + 1 < dims.size(); l++) {
        params.emplace_back((size_t)dims[l] * dims[l + 1]);
        for (float& x : params.back()) x = u(rng);
        params.emplace_back((size_t)dims[l + 1]);
        for (float& x : params.back()) x = u(rng);
    }
    for (auto& v : params) ptrs.push_back(v.data());
    McrlBotPolicy* p = mcrl_bot_policy_create(dims.data(), (int32_t)dims.size() - 1, ptrs.data());
    REQUIRE_MESSAGE(p != nullptr, std::string(mcrl_last_error()));
    return p;
}

struct Duel {
    McrlWorld* w = afx::makeStadium();
    Duel() {
        McrlCarState a = carAt(-20, CAR_Y, -30), b = carAt(20, CAR_Y, 30, 180);
        REQUIRE(mcrl_car_add(w, 1, 0, 0, &a) == MCRL_OK);
        REQUIRE(mcrl_car_add(w, 2, 1, 0, &b) == MCRL_OK);
    }
    ~Duel() { CHECK(mcrl_world_destroy(w) == MCRL_OK); }
    Duel(const Duel&) = delete;
    Duel& operator=(const Duel&) = delete;
    void step(int n = 1) { REQUIRE(mcrl_step(w, n, nullptr) == n); }
    McrlControls controls(int32_t id) {
        McrlControls c{};
        REQUIRE(mcrl_car_get_controls(w, id, &c) == MCRL_OK);
        return c;
    }
    mcrl::bot::BotState state(int32_t id) {
        REQUIRE(w->bots);
        return w->bots->bots.at(id).st;
    }
};

bool neutral(const McrlControls& c) {
    return c.throttle == 0 && c.steer == 0 && c.pitch == 0 && c.yaw == 0 && c.roll == 0 && c.jump == 0 && c.boost == 0 &&
           c.handbrake == 0;
}
}

TEST_CASE("bot: forward") {
    std::vector<float> W0(2 * 89, 0.f), b0 = {0, 0.5f}, W1(90 * 2, 0.f), b1(90, 0.f);
    W0[0 * 89 + 0] = 1;
    W0[1 * 89 + 0] = -1;
    W1[5 * 2 + 0] = 2;
    W1[7 * 2 + 1] = 3;
    const int32_t dims[3] = {89, 2, 90};
    const float* params[4] = {W0.data(), b0.data(), W1.data(), b1.data()};
    McrlBotPolicy* p = mcrl_bot_policy_create(dims, 2, params);
    REQUIRE_MESSAGE(p != nullptr, std::string(mcrl_last_error()));
    float obs[89] = {}, logits[90];
    obs[0] = 0.25f;
    REQUIRE(mcrl_bot_policy_forward(p, obs, logits) == MCRL_OK);
    CHECK(logits[5] == 0.5f);
    CHECK(logits[7] == 0.75f);
    for (int i = 0; i < 90; i++)
        if (i != 5 && i != 7) CHECK(logits[i] == 0.f);
    obs[0] = -1;
    REQUIRE(mcrl_bot_policy_forward(p, obs, logits) == MCRL_OK);
    CHECK(logits[5] == 0.f);
    CHECK(logits[7] == 4.5f);
    for (int i = 0; i < 90; i++)
        if (i != 5 && i != 7) CHECK(logits[i] == 0.f);
    mcrl_bot_policy_free(p);
}

TEST_CASE("bot: policy args") {
    std::vector<float> W(90 * 89, 0.f), b(90, 0.f);
    const float* params[2] = {W.data(), b.data()};
    const int32_t good[2] = {89, 90};
    CHECK(mcrl_bot_policy_create(good, 0, params) == nullptr);
    const int32_t in88[2] = {88, 90};
    CHECK(mcrl_bot_policy_create(in88, 1, params) == nullptr);
    CHECK(std::string(mcrl_last_error()).find("89") != std::string::npos);
    const int32_t out91[2] = {89, 91};
    CHECK(mcrl_bot_policy_create(out91, 1, params) == nullptr);
    CHECK(std::string(mcrl_last_error()).find("90") != std::string::npos);
    b[3] = std::nanf("");
    CHECK(mcrl_bot_policy_create(good, 1, params) == nullptr);
    CHECK(std::string(mcrl_last_error()).find("finite") != std::string::npos);
    mcrl_bot_policy_free(nullptr);
    float obs[89] = {}, logits[90];
    CHECK(mcrl_bot_policy_forward(nullptr, obs, logits) == MCRL_ERR_ARG);
}

TEST_CASE("bot: a restore leaves the schedule alone") {
    McrlBotPolicy* p = randomPolicy({89, 16, 90}, 5);
    Duel a, b;
    for (Duel* d : {&a, &b}) {
        REQUIRE(mcrl_soccar_kickoff(d->w, 4, 0) == MCRL_OK);
        REQUIRE(mcrl_bot_add(d->w, 1, p, 1, 7) == MCRL_OK);
        REQUIRE(mcrl_bot_add(d->w, 2, p, 0, 8) == MCRL_OK);
    }
    std::vector<uint8_t> car1(mcrl_sizeof(16)), car2(mcrl_sizeof(16)), ball(mcrl_sizeof(17)), arena(mcrl_sizeof(18));
    a.step(300);
    b.step(300);
    REQUIRE(mcrl_car_save(b.w, 1, car1.data()) == MCRL_OK);
    REQUIRE(mcrl_car_save(b.w, 2, car2.data()) == MCRL_OK);
    REQUIRE(mcrl_ball_save(b.w, ball.data()) == MCRL_OK);
    REQUIRE(mcrl_soccar_save(b.w, arena.data()) == MCRL_OK);
    a.step(5);
    b.step(5);
    const mcrl::bot::BotState before1 = b.state(1), before2 = b.state(2);
    REQUIRE(mcrl_world_set_tick(b.w, 300) == MCRL_OK);
    REQUIRE(mcrl_car_load(b.w, 1, car1.data()) == MCRL_OK);
    REQUIRE(mcrl_car_load(b.w, 2, car2.data()) == MCRL_OK);
    REQUIRE(mcrl_soccar_load(b.w, arena.data()) == MCRL_OK);
    REQUIRE(mcrl_ball_load(b.w, ball.data()) == MCRL_OK);
    REQUIRE(mcrl_world_rewind_events(b.w, 300) == MCRL_OK);
    for (auto [id, was] : {std::pair{1, before1}, std::pair{2, before2}}) {
        const mcrl::bot::BotState now = b.state(id);
        CHECK_MESSAGE(now.phase == was.phase, "car ", id);
        CHECK_MESSAGE(now.current == was.current, "car ", id);
        CHECK_MESSAGE(now.pending == was.pending, "car ", id);
        CHECK_MESSAGE(now.decisions == was.decisions, "car ", id);
    }
    for (int t = 1; t <= 64; t++) {
        a.step();
        b.step();
        for (int32_t id = 1; id <= 2; id++) {
            const mcrl::bot::BotState sa = a.state(id), sb = b.state(id);
            CHECK_MESSAGE(sb.phase == sa.phase, "car ", id, " tick ", t);
            CHECK_MESSAGE(sb.decisions == sa.decisions, "car ", id, " tick ", t);
            CHECK_MESSAGE(sb.held == sa.held, "car ", id, " tick ", t);
            const McrlControls c = b.controls(id);
            if (sb.current < 0) {
                CHECK_MESSAGE(neutral(c), "car ", id, " tick ", t);
                continue;
            }
            const mcrl::bot::Action& r = mcrl::bot::actions()[sb.current];
            CHECK_MESSAGE((c.throttle == r.throttle && c.steer == r.steer && c.pitch == r.pitch && c.yaw == r.yaw &&
                           c.roll == r.roll && c.jump == (r.jump != 0) && c.boost == (r.boost != 0) &&
                           c.handbrake == (r.handbrake != 0)),
                          "car ", id, " tick ", t, ": not its row ", sb.current);
        }
    }
    CHECK(b.state(1).decisions == before1.decisions + 8);
    mcrl_bot_policy_free(p);
}
