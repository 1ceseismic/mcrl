#include <doctest/doctest.h>
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include "mcrl.h"
#include "stepassist.h"
#include "stepfloor.h"
#include "terrain_fixtures.h"
#include "world.h"

using namespace fx;
using mcrl::StepRaycaster;
using mcrl::VirtualFloor;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double UU = 1.0 / 50;
constexpr double LIFT = 25 * UU;
constexpr double FLOOR_MARGIN = mcrl::stepassist::FLOOR_MARGIN;

const RocketSim::CarConfig& octane() { return RocketSim::CAR_CONFIG_OCTANE; }

std::array<double, 4> footprint(double x, double z) {
    const double hx = octane().hitboxSize.x / 2 * UU, hz = octane().hitboxSize.y / 2 * UU;
    const double off = octane().hitboxPosOffset.x * UU;
    return {x + off - hx - FLOOR_MARGIN, z - hz - FLOOR_MARGIN, x + off + hx + FLOOR_MARGIN, z + hz + FLOOR_MARGIN};
}

StepRaycaster& rayOf(McrlWorld* w, int32_t id) { return *w->stepRays.at(id); }
VirtualFloor& floorOf(McrlWorld* w, int32_t id) { return rayOf(w, id).floor; }
RocketSim::Car* carOf(McrlWorld* w, int32_t id) { return w->cars.at(id); }

void force(McrlWorld* w, int32_t id, double zMc, const std::array<double, 4>& rect) {
    REQUIRE(mcrl::testing::forceFloor(*w, id, zMc, rect.data()));
}

bool sameBytes(const McrlCarState& a, const McrlCarState& b) { return std::memcmp(&a, &b, sizeof a) == 0; }
bool sameBytes(const McrlBallState& a, const McrlBallState& b) { return std::memcmp(&a, &b, sizeof a) == 0; }
}

TEST_CASE("floor: inactive is bit-identical") {
    McrlShapes* s = makeShapes();
    const Scene sc = Scene().fill(-64, 63, -64, 79, 63, 79, CUBE).fill(8, 63, -64, 79, 63, 79, SLAB);
    McrlWorld* w[2] = {makeWorld(s), makeWorld(s)};
    for (McrlWorld* x : w) {
        sc.commitRange(x, s, -4, 3, -4, 4, 5, 4);
        const McrlCarState c = carAt(-8, FLOOR_TOP + 0.34, 8);
        REQUIRE(mcrl_car_add(x, 1, 0, 0, &c) == MCRL_OK);
        setBall(x, 40, 66, 30);
    }
    RocketSim::Car* ca = carOf(w[0], 1);
    StepRaycaster& ra = rayOf(w[0], 1);
    CHECK(ca->_bulletVehicle.m_vehicleRaycaster == &ra);
    CHECK(ra.addedFilterMask == (int)RocketSim::CollisionMasks::DROPSHOT_FLOOR);
    CHECK(ra.addedFilterMask == ca->_bulletVehicleRaycaster.addedFilterMask);
    CHECK(ra.m_dynamicsWorld == &w[0]->arena->_bulletWorld);
    CHECK_FALSE(ra.floor.active);
    RocketSim::Car* cb = carOf(w[1], 1);
    cb->_bulletVehicle.m_vehicleRaycaster = &cb->_bulletVehicleRaycaster;
    for (McrlWorld* x : w) x->shapes = nullptr;

    int mismatches = 0, firstMismatch = -1, grounded = 0, airborne = 0, onCubes = 0, onSlabs = 0, jumped = 0;
    double minX = 1e9, maxX = -1e9, maxZ = -1e9;
    for (int t = 0; t < 600; t++) {
        const float steer = t < 150 ? 0.f : ((t / 40) % 4 == 3 ? 0.2f : 1.f);
        const bool boost = t < 40;
        const bool jump = t >= 330 && t < 340;
        for (McrlWorld* x : w) {
            setControls(x, 1, 1, steer, boost, jump);
            step(x, 1);
        }
        const McrlCarState a = getCar(w[0], 1), b = getCar(w[1], 1);
        if (!sameBytes(a, b) || !sameBytes(getBall(w[0]), getBall(w[1]))) {
            if (firstMismatch < 0) firstMismatch = t;
            mismatches++;
        }
        if (a.flags & MCRL_CAR_JUMPED) jumped++;
        if (a.flags & MCRL_CAR_ON_GROUND) {
            grounded++;
            if (a.body.px < 6 && a.body.py > FLOOR_TOP) onCubes++;
            if (a.body.px > 10 && a.body.py < FLOOR_TOP) onSlabs++;
        } else {
            airborne++;
        }
        minX = std::min(minX, a.body.px);
        maxX = std::max(maxX, a.body.px);
        maxZ = std::max(maxZ, a.body.pz);
    }
    INFO("first mismatch on tick ", firstMismatch);
    CHECK(mismatches == 0);
    CHECK_FALSE(ra.floor.active);
    CHECK(grounded > 300);
    CHECK(airborne > 10);
    CHECK(jumped > 0);
    CHECK(onCubes > 30);
    CHECK(onSlabs > 100);
    CHECK(minX < 0);
    CHECK(maxX > 16);
    CHECK(maxZ > 32);
    for (McrlWorld* x : w) CHECK(mcrl_world_destroy(x) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("floor: one raycaster per car, freed after the car") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    commitStandard(w, s, floorScene());
    for (int32_t id : {1, 2}) {
        const McrlCarState c = carAt(4.5 + 8 * id, FLOOR_TOP + 0.34 + LIFT, 8.5);
        REQUIRE(mcrl_car_add(w, id, 0, 0, &c) == MCRL_OK);
        CHECK(carOf(w, id)->_bulletVehicle.m_vehicleRaycaster == &rayOf(w, id));
        force(w, id, FLOOR_TOP + LIFT, footprint(c.body.px, c.body.pz));
    }
    CHECK(w->stepRays.size() == 2);
    CHECK(&rayOf(w, 1) != &rayOf(w, 2));
    step(w, 30);
    CHECK(getCar(w, 1).wheelContact == 0xFu);
    CHECK(getCar(w, 2).wheelContact == 0xFu);
    const McrlCarState dup = carAt(0, 70, 0);
    CHECK(mcrl_car_add(w, 2, 0, 0, &dup) == MCRL_ERR_ARG);
    CHECK(carOf(w, 2)->_bulletVehicle.m_vehicleRaycaster == &rayOf(w, 2));
    REQUIRE(mcrl_car_remove(w, 1) == MCRL_OK);
    CHECK(w->stepRays.count(1) == 0);
    step(w, 5);
    CHECK(getCar(w, 2).wheelContact == 0xFu);
    const McrlCarState c1 = carAt(12.5, FLOOR_TOP + 0.34 + LIFT, 8.5);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c1) == MCRL_OK);
    CHECK(carOf(w, 1)->_bulletVehicle.m_vehicleRaycaster == &rayOf(w, 1));
    CHECK_FALSE(floorOf(w, 1).active);
    step(w, 1);
    CHECK(getCar(w, 1).wheelContact == 0);
    CHECK(floorOf(w, 2).active);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
