#include <doctest/doctest.h>
#include <cmath>
#include <random>
#include "coords.h"

using namespace mcrl::coords;
using RocketSim::RotMat;
using RocketSim::Vec;

namespace {
constexpr int OX = 16, OY = 0, OZ = -32;

void quatRotate(const float q[4], const double v[3], double out[3]) {
    const double w = q[0], ux = q[1], uy = q[2], uz = q[3];
    const double tx = 2 * (uy * v[2] - uz * v[1]);
    const double ty = 2 * (uz * v[0] - ux * v[2]);
    const double tz = 2 * (ux * v[1] - uy * v[0]);
    out[0] = v[0] + w * tx + (uy * tz - uz * ty);
    out[1] = v[1] + w * ty + (uz * tx - ux * tz);
    out[2] = v[2] + w * tz + (ux * ty - uy * tx);
}

RotMat rsRotFromRsQuat(double w, double x, double y, double z) {
    return RotMat(btMatrix3x3(btQuaternion((float)x, (float)y, (float)z, (float)w)));
}

void rotMul(const RotMat& m, const double v[3], double out[3]) {
    for (int i = 0; i < 3; i++) out[i] = m.forward[i] * v[0] + m.right[i] * v[1] + m.up[i] * v[2];
}
}

TEST_CASE("coords: position MC -> RS -> MC") {
    Vec p = mcToRsPos(17, 2, -29, OX, OY, OZ);
    CHECK(p.x == doctest::Approx(50));
    CHECK(p.y == doctest::Approx(150));
    CHECK(p.z == doctest::Approx(100));
    double back[3];
    rsToMcPos(p, OX, OY, OZ, back);
    CHECK(std::abs(back[0] - 17) < 1e-9);
    CHECK(std::abs(back[1] - 2) < 1e-9);
    CHECK(std::abs(back[2] - (-29)) < 1e-9);
}

TEST_CASE("coords: velocity MC blocks/s -> RS UU/s") {
    Vec v = mcToRsVel(1, 0, 0);
    CHECK(v.x == doctest::Approx(50));
    CHECK(v.y == doctest::Approx(0));
    CHECK(v.z == doctest::Approx(0));
    v = mcToRsVel(0, 1, 0);
    CHECK(v.x == doctest::Approx(0));
    CHECK(v.y == doctest::Approx(0));
    CHECK(v.z == doctest::Approx(50));
    double back[3];
    rsToMcVel(Vec(0, 0, 50), back);
    CHECK(back[0] == doctest::Approx(0));
    CHECK(back[1] == doctest::Approx(1));
    CHECK(back[2] == doctest::Approx(0));
}

TEST_CASE("coords: RS yaw +90 -> MC quaternion (forward east turns south = right)") {
    const double c = std::cos(M_PI / 4), s = std::sin(M_PI / 4);
    RotMat rs = rsRotFromRsQuat(c, 0, 0, s);
    CHECK(rs.forward.y == doctest::Approx(1).epsilon(1e-6));

    float q[4];
    rsRotToMcQuat(rs, q);
    CHECK(std::abs(q[0] - c) < 1e-6);
    CHECK(std::abs(q[1] - 0) < 1e-6);
    CHECK(std::abs(q[2] - (-s)) < 1e-6);
    CHECK(std::abs(q[3] - 0) < 1e-6);

    const double east[3] = {1, 0, 0};
    double out[3];
    quatRotate(q, east, out);
    CHECK(std::abs(out[0] - 0) < 1e-6);
    CHECK(std::abs(out[1] - 0) < 1e-6);
    CHECK(std::abs(out[2] - 1) < 1e-6);

    RotMat back = mcQuatToRs(q[0], q[1], q[2], q[3]);
    for (int i = 0; i < 3; i++) {
        CHECK(std::abs(back.forward[i] - rs.forward[i]) < 1e-6);
        CHECK(std::abs(back.right[i] - rs.right[i]) < 1e-6);
        CHECK(std::abs(back.up[i] - rs.up[i]) < 1e-6);
    }
}

TEST_CASE("coords: angular velocity is a pseudovector (w_mc = -P w_rs)") {
    double w[3];
    rsToMcAngVel(Vec(0, 0, 1), w);
    CHECK(w[0] == doctest::Approx(0));
    CHECK(w[1] == doctest::Approx(-1));
    CHECK(w[2] == doctest::Approx(0));
    Vec r = mcToRsAngVel(0, -1, 0);
    CHECK(r.x == doctest::Approx(0));
    CHECK(r.y == doctest::Approx(0));
    CHECK(r.z == doctest::Approx(1));
}

TEST_CASE("coords: car-local RS vector -> car-local MC axes (x fwd, y up, z right)") {
    float out[3];
    rsLocalToMc(btVector3(1, 2, 3), out);
    CHECK(out[0] == 1);
    CHECK(out[1] == 3);
    CHECK(out[2] == 2);
}

TEST_CASE("coords: random round trips (seed 1234)") {
    std::mt19937 rng(1234);
    std::uniform_real_distribution<double> pos(-1000, 1000), vel(-100, 100), ang(-10, 10);
    std::normal_distribution<double> gauss(0, 1);
    for (int n = 0; n < 100; n++) {
        const double p[3] = {pos(rng), pos(rng), pos(rng)};
        double pb[3];
        rsToMcPos(mcToRsPos(p[0], p[1], p[2], OX, OY, OZ), OX, OY, OZ, pb);
        for (int i = 0; i < 3; i++) CHECK(std::abs(pb[i] - p[i]) < 1e-3);

        const double v[3] = {vel(rng), vel(rng), vel(rng)};
        double vb[3];
        rsToMcVel(mcToRsVel(v[0], v[1], v[2]), vb);
        for (int i = 0; i < 3; i++) CHECK(std::abs(vb[i] - v[i]) < 1e-4);

        const double w[3] = {ang(rng), ang(rng), ang(rng)};
        double wb[3];
        rsToMcAngVel(mcToRsAngVel(w[0], w[1], w[2]), wb);
        for (int i = 0; i < 3; i++) CHECK(std::abs(wb[i] - w[i]) < 1e-5);

        double g[4] = {gauss(rng), gauss(rng), gauss(rng), gauss(rng)};
        const double len = std::sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2] + g[3] * g[3]);
        const float q[4] = {(float)(g[0] / len), (float)(g[1] / len), (float)(g[2] / len), (float)(g[3] / len)};
        RotMat m = mcQuatToRs(q[0], q[1], q[2], q[3]);

        Vec up = m.forward.Cross(m.right);
        for (int i = 0; i < 3; i++) CHECK(std::abs(up[i] - m.up[i]) < 1e-5);
        CHECK(std::abs(m.forward.Dot(m.forward) - 1) < 1e-5);
        CHECK(std::abs(m.forward.Dot(m.right)) < 1e-5);

        const double vm[3] = {vel(rng), vel(rng), vel(rng)};
        const double pv[3] = {vm[0], vm[2], vm[1]};
        double rpv[3], viaQuat[3];
        rotMul(m, pv, rpv);
        quatRotate(q, vm, viaQuat);
        CHECK(std::abs(viaQuat[0] - rpv[0]) < 1e-3);
        CHECK(std::abs(viaQuat[1] - rpv[2]) < 1e-3);
        CHECK(std::abs(viaQuat[2] - rpv[1]) < 1e-3);

        float qb[4];
        rsRotToMcQuat(m, qb);
        const double dot = (double)q[0] * qb[0] + (double)q[1] * qb[1] + (double)q[2] * qb[2] + (double)q[3] * qb[3];
        CHECK(std::abs(dot) > 1 - 1e-6);
        CHECK(qb[0] >= 0);
    }
}
