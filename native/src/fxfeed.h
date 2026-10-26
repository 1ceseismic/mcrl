#pragma once
#include <cstdint>
#include <vector>
#include "RocketSim.h"
#include "mcrl.h"

struct McrlWorld;
struct McrlCarEvents;

namespace mcrl::fxfeed {
// RL's MinImpactMomentum (UU/s), MinImpactDelay, DoubleJump span; 120 Hz.
constexpr float MIN_IMPACT_UU = 50;
constexpr uint64_t MIN_IMPACT_DELAY_TICKS = 18;
constexpr int DOUBLE_JUMP_ACTIVE_TICKS = 12;

struct Touch {
    bool found = false;
    btScalar dist = 0;
    btVector3 p = btVector3(0, 0, 0);
    btVector3 n = btVector3(0, 0, 0);
    const btCollisionObject* other = nullptr;
    int tri = -1;
};

struct Before {
    int32_t id;
    RocketSim::Car* car;
    McrlCarEvents* ev;
    RocketSim::CarState state;
    Touch world;
    Touch ball;
};

struct BallBefore {
    RocketSim::Vec vel;
    Touch world;
};

Before capture(McrlWorld& w, int32_t carId, RocketSim::Car& car);
BallBefore capture(RocketSim::Ball& ball);

void after(McrlWorld& w, std::vector<Before>& cars, BallBefore& ball);

void clear_tick(McrlCarFx& f);
void clear_tick(McrlBallFx& f);

}
