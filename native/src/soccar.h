#pragma once
#include <array>
#include <cstdint>
#include <memory>
#include <vector>
#include "RocketSim.h"
#include "mcrl.h"

struct McrlWorld;

struct McrlArenaMeshes {
    std::vector<btBvhTriangleMeshShape*> shapes;
};

namespace mcrl::soccar {

constexpr int PAD_COUNT = 34;
static_assert(RocketSim::RLConst::BoostPads::LOCS_AMOUNT_BIG +
                  RocketSim::RLConst::BoostPads::LOCS_AMOUNT_SMALL_SOCCAR ==
              PAD_COUNT);

constexpr int MAX_KICKOFF_CARS_PER_TEAM =
    RocketSim::RLConst::CAR_SPAWN_LOCATION_AMOUNT + RocketSim::RLConst::CAR_RESPAWN_LOCATION_AMOUNT;
static_assert(MAX_KICKOFF_CARS_PER_TEAM == 9);

// RL's goal explosion (Ball_Default).
constexpr double GOAL_PUSH_DV_UU = 5000;
constexpr double GOAL_PUSH_RADIUS_UU = 3000;
constexpr float GOAL_PUSH_FALLOFF = 0.1f;

struct Soccar {
    uint32_t kickoffs = 0;
    int32_t lockTicks = 0;
    // Never change ball radius or mass: RocketSim re-adds it.
    bool ballOutOfPlay = false;
    uint32_t goals = 0;
    int32_t goalTeam = -1, goalToucher = -1;
    std::array<double, 3> goalPos{};
    std::array<float, 3> goalVel{};
    std::array<uint32_t, PAD_COUNT> padPickups{};
    std::array<int32_t, PAD_COUNT> padCar;
    bool locked = false;
    std::vector<RocketSim::CarControls> ownControls;
    std::array<float, PAD_COUNT> cooldownBefore{};

    Soccar() { padCar.fill(-1); }
};

McrlArenaMeshes* meshes_load(const uint8_t* const* files, const int32_t* sizes, int32_t n, uint32_t* outHashes);
std::unique_ptr<RocketSim::Arena> create_arena(const McrlArenaMeshes& meshes);

int32_t kickoff(McrlWorld& w, int32_t seed, int32_t lockTicks);
int32_t get_state(McrlWorld& w, McrlSoccarState* out);
int32_t respawn_state(McrlWorld& w, int32_t team, int32_t index, McrlCarState* out);
int32_t pad_layout(McrlWorld& w, float* outXyz102, uint8_t* outBig34);
int32_t park_ball(McrlWorld& w);

void take_ball_out(McrlWorld& w);
void put_ball_in(McrlWorld& w);
bool ball_out(const McrlWorld& w);

void before_step(McrlWorld& w);
void after_step(McrlWorld& w);
void after_tick(McrlWorld& w);
}
