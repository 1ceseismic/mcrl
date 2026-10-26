#pragma once
#include <array>
#include <cstdint>
#include <map>
#include <memory>
#include <vector>
#include "RocketSim.h"
#include "mcrl.h"

struct McrlWorld;

namespace mcrl::bot {

constexpr int OBS = 89;
constexpr int ACTIONS = 90;
constexpr int PADS = 34;

struct Action {
    float throttle, steer, pitch, yaw, roll, jump, boost, handbrake;
};

const std::array<Action, ACTIONS>& actions();

// GigaLearn observation pad order to RocketSim's.
inline constexpr int OBS_PAD_TO_SOCCAR[PADS] = {6,  7,  8,  4,  5,  9,  10, 11, 12, 13, 14, 15, 16, 17, 18, 0,  19,
                                                20, 1,  21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 2,  3,  31, 32, 33};

struct Policy {
    std::vector<int32_t> dims;
    std::vector<std::vector<float>> wt, b;
    int32_t maxWidth = 0;
    void forward(const float* obs, float* logits, float* h0, float* h1) const;
};

struct BotState {
    int32_t current = -1, pending = -1, phase = 0;
    uint32_t decisions = 0;
    bool held = false;
};

struct Bot {
    std::shared_ptr<const Policy> policy;
    float temperature = 0;
    uint64_t rng = 1;
    BotState st;
};

struct Bots {
    std::map<int32_t, Bot> bots;
    std::vector<float> h0, h1;
    float obs[OBS] = {};
    float logits[ACTIONS] = {};
    uint8_t allowed[ACTIONS] = {};
};

void before_tick(McrlWorld& w);

int32_t observe_checked(McrlWorld& w, int32_t carId, int32_t prevAction, float* out);
McrlBotPolicy* policy_create(const int32_t* dims, int32_t nLayers, const float* const* params);
int32_t policy_forward(const McrlBotPolicy* p, const float* obs, float* logits);
int32_t car_get_controls(McrlWorld& w, int32_t carId, McrlControls* out);
int32_t add(McrlWorld& w, int32_t carId, const McrlBotPolicy* p, float temperature, uint64_t seed);
int32_t remove(McrlWorld& w, int32_t carId);

}

struct McrlBotPolicy {
    std::shared_ptr<const mcrl::bot::Policy> p;
};
