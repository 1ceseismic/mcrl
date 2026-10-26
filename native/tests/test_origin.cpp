#include <doctest/doctest.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <string>
#include <thread>
#include <vector>
#include "mcrl.h"
#include "terrain_fixtures.h"
#include "world.h"

using namespace fx;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;
constexpr double BALL_REST = 1.863;

double dist(const McrlBody& a, const McrlBody& b) {
    return std::sqrt((a.px - b.px) * (a.px - b.px) + (a.py - b.py) * (a.py - b.py) + (a.pz - b.pz) * (a.pz - b.pz));
}

bool sameMotion(const McrlBody& a, const McrlBody& b) {
    return a.vx == b.vx && a.vy == b.vy && a.vz == b.vz && a.wx == b.wx && a.wy == b.wy && a.wz == b.wz &&
           a.qw == b.qw && a.qx == b.qx && a.qy == b.qy && a.qz == b.qz;
}

void origin(McrlWorld* w, int32_t out[3]) { REQUIRE(mcrl_world_get_origin(w, out) == MCRL_OK); }

void setBallBody(McrlWorld* w, double x, double y, double z, double vx = 0, double vy = 0, double vz = 0) {
    McrlBallState b{};
    b.body.px = x;
    b.body.py = y;
    b.body.pz = z;
    b.body.qw = 1;
    b.body.vx = vx;
    b.body.vy = vy;
    b.body.vz = vz;
    REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
}

struct RestRun {
    McrlCarState car{};
    McrlBallState ball{};
};

RestRun restRun(const McrlShapes* s, int32_t dx) {
    McrlWorld* w = makeWorld(s);
    const Scene sc = Scene().fill(dx - 16, 63, -16, dx + 31, 63, 31, CUBE);
    const int32_t sx = dx / 16;
    sc.commitRange(w, s, sx - 1, 3, -1, sx + 1, 5, -1);
    REQUIRE(mcrl_world_shift_origin(w, dx, 0, 0) == MCRL_OK);
    sc.commitRange(w, s, sx - 1, 3, 0, sx + 1, 5, 1);
    const McrlCarState c = carAt(dx + 8.5, FLOOR_TOP + CAR_REST + 0.2, 8.5);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    setBallBody(w, dx - 5, FLOOR_TOP + BALL_REST + 0.2, -5);
    step(w, 240);
    RestRun r;
    r.car = getCar(w, 1);
    r.ball = getBall(w);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    return r;
}

struct DriveRun {
    std::vector<std::array<double, 3>> path;
    int grounded = 0;
};

DriveRun driveRun(const McrlShapes* s, int32_t dx, int32_t ox) {
    McrlWorld* w = makeWorld(s, ox, 0, 0);
    const Scene sc = Scene().fill(dx - 32, 63, -32, dx + 79, 63, 63, CUBE);
    sc.commitRange(w, s, dx / 16 - 2, 3, -2, dx / 16 + 4, 3, 3);
    const McrlCarState c = carAt(dx + 8.5, FLOOR_TOP + CAR_REST, 8.5);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    setControls(w, 1, 1, 0.3f);
    DriveRun r;
    for (int t = 0; t < 240; t++) {
        step(w, 1);
        const McrlCarState o = getCar(w, 1);
        r.path.push_back({o.body.px - dx, o.body.py, o.body.pz});
        if (o.flags & MCRL_CAR_ON_GROUND) r.grounded++;
    }
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    return r;
}

double maxPathDiv(const DriveRun& a, const DriveRun& b) {
    double m = 0;
    for (size_t t = 0; t < a.path.size() && t < b.path.size(); t++) {
        double d2 = 0;
        for (int i = 0; i < 3; i++) d2 += (a.path[t][i] - b.path[t][i]) * (a.path[t][i] - b.path[t][i]);
        m = std::max(m, std::sqrt(d2));
    }
    return m;
}

}

TEST_CASE("origin: shift keeps a driving car and rolling ball on the same path") {
    McrlShapes* s = makeShapes();
    const Scene sc = Scene().fill(-16, 63, -32, 400, 63, 47, CUBE);
    constexpr double CAR_X = 0, CAR_Z = 0, CAR_YAW = 60, BALL_X = 8, BALL_Z = 30;
    McrlWorld* w[2] = {makeWorld(s), makeWorld(s)};
    for (McrlWorld* x : w) {
        sc.commitRange(x, s, -1, 3, -2, 25, 3, 2);
        const McrlCarState c = carAt(CAR_X, FLOOR_TOP + CAR_REST, CAR_Z, CAR_YAW);
        REQUIRE(mcrl_car_add(x, 1, 0, 0, &c) == MCRL_OK);
        setBallBody(x, BALL_X, FLOOR_TOP + BALL_REST, BALL_Z, 25, 0, 3);
        setControls(x, 1, 1, 0.2f);
    }
    double maxCarDiv = 0, maxBallDiv = 0;
    int carGrounded = 0, carGroundedShifted = 0;
    for (int t = 0; t < 600; t++) {
        if (t == 300) {
            const McrlCarState c0 = getCar(w[1], 1);
            const McrlBallState b0 = getBall(w[1]);
            REQUIRE(mcrl_world_shift_origin(w[1], 256, 0, 0) == MCRL_OK);
            int32_t o[3];
            origin(w[1], o);
            CHECK(o[0] == 256);
            CHECK(o[1] == 0);
            CHECK(o[2] == 0);
            const McrlCarState c1 = getCar(w[1], 1);
            const McrlBallState b1 = getBall(w[1]);
            INFO("car moved by the shift ", dist(c0.body, c1.body), ", ball ", dist(b0.body, b1.body));
            CHECK(dist(c0.body, c1.body) < 1e-4);
            CHECK(dist(b0.body, b1.body) < 1e-4);
            CHECK(sameMotion(c0.body, c1.body));
            CHECK(sameMotion(b0.body, b1.body));
            CHECK(c0.flags == c1.flags);
            CHECK(c0.wheelContact == c1.wheelContact);
            CHECK(std::memcmp(c0.wheelLocal, c1.wheelLocal, sizeof c0.wheelLocal) == 0);
        }
        for (McrlWorld* x : w) step(x, 1);
        const McrlCarState a = getCar(w[0], 1), b = getCar(w[1], 1);
        maxCarDiv = std::max(maxCarDiv, dist(a.body, b.body));
        maxBallDiv = std::max(maxBallDiv, dist(getBall(w[0]).body, getBall(w[1]).body));
        if (a.flags & MCRL_CAR_ON_GROUND) carGrounded++;
        if (b.flags & MCRL_CAR_ON_GROUND) carGroundedShifted++;
    }
    const McrlCarState a = getCar(w[0], 1), b = getCar(w[1], 1);
    const McrlBallState ba = getBall(w[0]), bb = getBall(w[1]);
    CHECK(dist(a.body, b.body) <= 0.01);
    CHECK(dist(ba.body, bb.body) <= 0.01);
    CHECK(maxCarDiv <= 0.01);
    CHECK(maxBallDiv <= 0.01);
    CHECK(carGrounded == 600);
    CHECK(carGroundedShifted == 600);
    CHECK(a.body.px > CAR_X + 40);
    CHECK(std::abs(a.body.py - FLOOR_TOP - CAR_REST) < 0.05);
    CHECK(ba.body.px > BALL_X + 40);
    CHECK(std::abs(ba.body.py - FLOOR_TOP - BALL_REST) < 0.05);
    CHECK(a.body.pz > -32);
    CHECK(a.body.pz < 48);
    CHECK(ba.body.pz < 48 - 2);
    for (McrlWorld* x : w) CHECK(mcrl_world_destroy(x) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("origin: far origin precision") {
    McrlShapes* s = makeShapes();
    constexpr int32_t FAR = 1000000;
    const RestRun near = restRun(s, 0), far = restRun(s, FAR);
    INFO("rest heights: car ", near.car.body.py, " / ", far.car.body.py, ", ball ", near.ball.body.py, " / ",
         far.ball.body.py);
    CHECK(std::abs(near.car.body.py - FLOOR_TOP - CAR_REST) <= 0.02);
    CHECK(std::abs(near.ball.body.py - FLOOR_TOP - BALL_REST) <= 0.06);
    CHECK(std::abs(far.car.body.py - near.car.body.py) <= 1e-3);
    CHECK(std::abs(far.ball.body.py - near.ball.body.py) <= 1e-3);
    CHECK(std::abs(far.car.body.px - FAR - near.car.body.px) <= 1e-3);
    CHECK(std::abs(far.car.body.pz - near.car.body.pz) <= 1e-3);
    CHECK(std::abs(far.ball.body.px - FAR - near.ball.body.px) <= 1e-3);
    CHECK(std::abs(far.ball.body.pz - near.ball.body.pz) <= 1e-3);

    const DriveRun ref = driveRun(s, 0, 0), farO = driveRun(s, FAR, FAR);
    const double divO = maxPathDiv(ref, farO);
    const auto& end = ref.path.back();
    CHECK(ref.grounded == 240);
    CHECK(end[0] > 8.5 + 20);
    CHECK(end[2] > 8.5 + 2);
    CHECK(farO.grounded == 240);
    CHECK(divO <= 0.01);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("origin: rejects non-multiples of 16 and other threads") {
    McrlWorld* w = makeWorld(nullptr, -32, 0, 48);
    int32_t o[3];
    const int32_t bad[][3] = {{17, 0, 0}, {0, 8, 0}, {0, 0, -1}, {-24, 0, 0}, {1, 1, 1}};
    for (const auto& b : bad) {
        CAPTURE(b[0]);
        CAPTURE(b[1]);
        CAPTURE(b[2]);
        CHECK(mcrl_world_shift_origin(w, b[0], b[1], b[2]) == MCRL_ERR_ARG);
        const std::string err = mcrl_last_error();
        CHECK(err.find("mcrl_world_shift_origin") == 0);
        CHECK(err.find("16") != std::string::npos);
        origin(w, o);
        CHECK(o[0] == -32);
        CHECK(o[1] == 0);
        CHECK(o[2] == 48);
    }
    CHECK(mcrl_world_shift_origin(nullptr, 0, 0, 0) == MCRL_ERR_ARG);

    int32_t rcShift = 0, rcGet = 0;
    std::string errShift, errGet;
    std::thread t([&] {
        rcShift = mcrl_world_shift_origin(w, 16, 0, 16);
        errShift = mcrl_last_error();
        int32_t tmp[3];
        rcGet = mcrl_world_get_origin(w, tmp);
        errGet = mcrl_last_error();
    });
    t.join();
    CHECK(rcShift == MCRL_ERR_THREAD);
    CHECK(errShift.find("mcrl_world_shift_origin") == 0);
    CHECK(errShift.find("owner thread") != std::string::npos);
    CHECK(rcGet == MCRL_ERR_THREAD);
    CHECK(errGet.find("mcrl_world_get_origin") == 0);
    origin(w, o);
    CHECK(o[0] == -32);
    CHECK(o[2] == 48);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
}
