#pragma once
#include <cstdint>
#include "RocketSim.h"
#include "stepfloor.h"

struct McrlWorld;

namespace mcrl::stepassist {
constexpr double UU = 1.0 / 50;
constexpr double HALF_STEP_MAX = (25 + 50.0 / 4096) * UU;
constexpr double FULL_STEP_MAX = 52 * UU;
constexpr double STEP_MIN = 10 * UU;
constexpr double LEVEL_UP_Z = 0.90;
constexpr double FLOOR_NORMAL_Z = 0.85;
constexpr double RISER_FACING_DOT = -0.2;
constexpr double SKIN = 1 * UU;
constexpr double PROBE_WIDEN = 3 * UU;
constexpr double PROBE_MARGIN = 2 * UU;
constexpr double MIN_PROBE_TRAVEL = 2 * UU;
constexpr double FLOOR_MARGIN = 8 * UU;
constexpr int FLOOR_IDLE_TICKS = 2;
constexpr double RISER_NORMAL_Z = 0.3;
constexpr double FLOOR_MATCH = 1 * UU;
constexpr double PAST_RISER = 2 * UU;
constexpr double INTENT_SPEED = 10 * UU;

constexpr double maxRise(bool fullStep) { return fullStep ? FULL_STEP_MAX : HALF_STEP_MAX; }

enum class Outcome : uint8_t {
    NONE,
    SNAP,
    SKIPPED,
    NO_SHAPES,
    BLOCKED,
    WHEELS,
    TILTED,
    NO_DIR,
    NOT_READY,
    THIN,
    RISE,
    FACE,
    HEADROOM,
    BALL,
    CAR,
    COUNT
};

struct PreTickPose {
    btTransform t;
    btVector3 vel;
    btVector3 angVel;
    bool blocked = true;
    bool fullStep = false;
};

PreTickPose capture(const RocketSim::Car& car, bool fullStep);

float pre(McrlWorld& w, RocketSim::Car* car, VirtualFloor& floor, bool fullStep, Outcome* why = nullptr);

float post(McrlWorld& w, RocketSim::Car* car, VirtualFloor& floor, bool snapped, const PreTickPose& before);

// Detection reads RocketSim's cached last raycast.
void moveWheelCache(RocketSim::Car& car, const btVector3& delta);

}
