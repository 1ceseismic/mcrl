#pragma once
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>
#include "RocketSim.h"
#include "blob.h"
#include "bot.h"
#include "error.h"
#include "fxfeed.h"
#include "guard.h"
#include "shapes.h"
#include "soccar.h"
#include "stepassist.h"
#include "stepfloor.h"
#include "terrain.h"
#include "voxels.h"

struct McrlCarEvents {
    uint32_t hits = 0;
    float hitSpeed = 0;
    uint32_t bumps = 0;
    int32_t bumpedBy = -1;
    float bumpSpeed = 0;
    float stepDz = 0;
    bool wheelsStale = false;
    McrlCarFx fx{.bumpedBy = -1};
    int doubleJumpTicks = 0;
    uint64_t worldHitTick = 0;
    bool fxStale = false;
    mcrl::stepassist::Outcome preOutcome = mcrl::stepassist::Outcome::NONE;
    mcrl::guard::LastSet<RocketSim::CarState> lastSet;
    mcrl::guard::Trip trip;
    bool wet = false;
    bool restored = false;
    bool held = false;
    int32_t preset = 0;
    int32_t team = 0;
    uint32_t gen = 0;
};

struct McrlHeldCar {
    RocketSim::Car* car;
    btTransform t, it;
    btVector3 v, av, iv, iav;
    RocketSim::CarState state;
    RocketSim::CarControls controls;
    mcrl::VirtualFloor* floor;
    bool floorUsed;
};

struct McrlAssistSlot {
    RocketSim::Car* car;
    mcrl::VirtualFloor* floor;
    McrlCarEvents* ev;
    mcrl::stepassist::PreTickPose pose;
    float dz;
};

// Owner thread only.
struct McrlWorld {
    RocketSim::Arena* arena = nullptr;
    std::unordered_map<int32_t, RocketSim::Car*> cars;
    int32_t ox = 0, oy = 0, oz = 0;
    std::thread::id owner;
    const McrlShapes* shapes = nullptr;
    std::unique_ptr<mcrl::TerrainStore> terrain;
    std::unique_ptr<mcrl::soccar::Soccar> soccar;
    std::unique_ptr<mcrl::bot::Bots> bots;
    std::unordered_map<int32_t, McrlCarEvents> carEvents;
    std::unordered_map<uint32_t, int32_t> rsIds;
    // Vehicles point at these: free after their car.
    std::unordered_map<int32_t, std::unique_ptr<mcrl::StepRaycaster>> stepRays;
    uint32_t ballBounces = 0;
    float ballBounceSpeed = 0;
    McrlBallFx ballFx{0, -1, {}};
    int32_t lastToucher = -1;
    uint64_t bounceTick = 0;
    bool ballFxStale = false;
    bool ballWet = false;
    bool stepUp = false;
    bool spawnGate = false;
    bool perCarGate = false;
    bool unlimitedBoost = true;
    uint32_t extras = 0;
    McrlMaterial materials[mcrl::MATERIAL_COUNT];
    uint32_t materialsSeen = 0;
    bool ballParked = false;
    btVector3 parkedVel = btVector3(0, 0, 0);
    btVector3 parkedAngVel = btVector3(0, 0, 0);
    mcrl::guard::Guard guard;
    mcrl::guard::LastSet<RocketSim::BallState> ballLastSet;
    mcrl::guard::Trip ballTrip;
    mcrl::blob::EventRing events;
    uint32_t carGens = 0;
    std::vector<McrlAssistSlot> assistScratch;
    std::vector<mcrl::fxfeed::Before> fxScratch;
    std::vector<McrlHeldCar> heldScratch;
    std::vector<mcrl::voxels::WBox> boxScratch, hitScratch;
};

namespace mcrl_internal {
void ensure_init();
// Also guards RocketSim's global arena mesh vectors.
std::mutex& init_mutex();
}

namespace mcrl {
// RocketSim sleeps a ball at exactly zero velocity.
inline void wake_at_rest(RocketSim::BallState& s) {
    if (s.vel.IsZero() && s.angVel.IsZero()) s.vel.z = -1e-3f;
}

McrlWorld* world_create(const McrlShapes* shapes, int32_t ox, int32_t oy, int32_t oz);
McrlWorld* world_create_soccar(const McrlArenaMeshes& meshes, int32_t ox, int32_t oy, int32_t oz);

int32_t check_world(const McrlWorld* w, const char* fn);
RocketSim::Car* find_car(McrlWorld& w, int32_t carId);

void rs_to_body(const McrlWorld& w, const RocketSim::PhysState& ps, McrlBody& b);
void car_was_set(McrlWorld& w, int32_t carId);
void ball_was_set(McrlWorld& w);

int32_t world_destroy(McrlWorld* w);
int32_t car_add(McrlWorld& w, int32_t carId, int32_t team, int32_t preset, const McrlCarState* s);
int32_t car_get_config(McrlWorld& w, int32_t carId, McrlCarConfig* out);
int32_t car_remove(McrlWorld& w, int32_t carId);
int32_t car_set_state(McrlWorld& w, int32_t carId, const McrlCarState* s);
int32_t car_demolish(McrlWorld& w, int32_t carId);
int32_t car_respawn(McrlWorld& w, int32_t carId, const McrlCarState* s);
int32_t car_get_state(McrlWorld& w, int32_t carId, McrlCarState* out);
int32_t car_set_controls(McrlWorld& w, int32_t carId, const McrlControls* c);
int32_t ball_set_state(McrlWorld& w, const McrlBallState* s);
int32_t ball_get_state(McrlWorld& w, McrlBallState* out);
int32_t car_get_fx(McrlWorld& w, int32_t carId, McrlCarFx* out);
int32_t ball_get_fx(McrlWorld& w, McrlBallFx* out);
int32_t ball_clear_touch(McrlWorld& w);
int32_t car_add_velocity(McrlWorld& w, int32_t carId, double dvx, double dvy, double dvz);
int32_t ball_add_velocity(McrlWorld& w, double dvx, double dvy, double dvz);
int32_t step(McrlWorld& w, int32_t ticks, uint32_t* outFlags);
int32_t set_spawn_gate(McrlWorld& w, int32_t enabled);
int32_t set_step_up(McrlWorld& w, int32_t enabled);
int32_t set_gate_mode(McrlWorld& w, int32_t perCar);
int32_t set_demos(McrlWorld& w, int32_t mode, int32_t teamDemos);
int32_t set_extras(McrlWorld& w, uint32_t flags);
int32_t world_shift_origin(McrlWorld& w, int32_t ox, int32_t oy, int32_t oz);
int32_t section_commit(McrlWorld& w, std::unique_ptr<McrlBuilt> b);
int32_t section_remove(McrlWorld& w, int32_t sx, int32_t sy, int32_t sz);
int32_t section_set_fluid(McrlWorld& w, int32_t sx, int32_t sy, int32_t sz, const uint8_t* cells);
#ifdef MCRL_TEST_HOOKS
int32_t set_unlimited_boost(McrlWorld& w, int32_t on);
int32_t world_get_origin(McrlWorld& w, int32_t* out3);
int32_t section_is_ready(McrlWorld& w, int32_t sx, int32_t sy, int32_t sz);
#endif
}
