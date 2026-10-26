#pragma once
#include <array>
#include <cstdint>
#include <utility>
#include <vector>
#include "RocketSim.h"

struct McrlWorld;

namespace mcrl::guard {

constexpr uint64_t RING_TICKS = 120;

template <class S>
struct LastSet {
    S state;
    uint64_t tick = 0;
    int32_t ox = 0, oy = 0, oz = 0;
};

struct Trip {
    bool tripped = false;
    uint64_t last = 0;
};

struct Entry {
    uint64_t tick = 0;
    int32_t ox = 0, oy = 0, oz = 0;
    std::vector<std::pair<int32_t, RocketSim::CarState>> cars;
    RocketSim::BallState ball;
};

struct Guard {
    std::array<Entry, RING_TICKS + 1> slots;
    size_t newest = 0;
    size_t count = 0;
#ifdef MCRL_TEST_HOOKS
    bool poisonArmed = false;
    int32_t poisonId = 0;
#endif
};

void reserve(Guard& g, size_t nCars);

uint32_t after_tick(McrlWorld& w);

// Stale contacts would warm-start a restored body.
void drop_contacts(btDiscreteDynamicsWorld& bw, btRigidBody& rb);

#ifdef MCRL_TEST_HOOKS
int32_t arm_poison(McrlWorld& w, int32_t carId);
#endif

}
