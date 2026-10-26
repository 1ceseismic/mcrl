#pragma once
#include <array>
#include <cstdint>
#include <limits>
#include <vector>
#include "mcrl.h"

struct McrlWorld;

namespace mcrl::blob {
constexpr int32_t NO_TICK = std::numeric_limits<int32_t>::min();

enum CarBit : uint32_t {
    ON_GROUND = 1u << 0,
    WHEEL0 = 1u << 1,
    HAS_JUMPED = 1u << 5,
    HAS_DOUBLE_JUMPED = 1u << 6,
    HAS_FLIPPED = 1u << 7,
    IS_FLIPPING = 1u << 8,
    IS_JUMPING = 1u << 9,
    IS_BOOSTING = 1u << 10,
    IS_SUPERSONIC = 1u << 11,
    IS_AUTO_FLIPPING = 1u << 12,
    WORLD_CONTACT = 1u << 13,
    IS_DEMOED = 1u << 14,
    BALL_HIT_VALID = 1u << 15,
    LAST_JUMP = 1u << 16,
    LAST_BOOST = 1u << 17,
    LAST_HANDBRAKE = 1u << 18,
    RAY_CONTACT0 = 1u << 19,
    RAY_FLOOR0 = 1u << 23,
    FLOOR_ACTIVE = 1u << 27,
    FLOOR_USED = 1u << 28,
    WHEELS_STALE = 1u << 29,
};

enum RiserAxis : uint8_t { RISER_NONE = 0, RISER_POS_X, RISER_NEG_X, RISER_POS_Y, RISER_NEG_Y };

enum CarFlag : uint8_t { THREE_WHEELS = 1 };

// Native bytes: loadable by the same build only.
// Tick fields: deltas to the world tick.
struct CarBlob {
    uint8_t version;
    uint8_t riserAxis;
    int8_t floorIdle;
    int8_t doubleJumpTicks;
    uint8_t preset;
    uint8_t carFlags;
    uint8_t pad[2];
    uint32_t bits;
    float origin[3], basis[9], linVel[3], angVel[3];
    float flipRelTorque[3];
    float jumpTime, flipTime, airTime, airTimeSinceJump, boost, timeSinceBoosted, boostingTime, supersonicTime,
        handbrakeVal, autoFlipTimer, autoFlipTorqueScale;
    float worldContactNormal[3];
    int32_t otherCarId;
    float cooldownTimer, demoRespawnTimer;
    int32_t hitTick, extraImpulseTick;
    float lastControls[5];
    float steerAngle, engineForce, brake;
    float suspensionLength[4], latFriction[4], longFriction[4], extraPushback[4], rayContactZ[4];
    float floorZ, floorRect[4], riserP[2];
    float stepDz;
    int32_t worldHitTick;
};
static_assert(sizeof(CarBlob) == 320, "a car blob is mcrl_sizeof(16) = 320 bytes (Java: Layouts.CAR_BLOB_SIZE)");

struct BallBlob {
    uint8_t version;
    uint8_t parked;
    uint8_t pad[2];
    int32_t bounceTick;
    float origin[3], basis[9], linVel[3], angVel[3];
    float parkedVel[3], parkedAngVel[3];
};
static_assert(sizeof(BallBlob) == 104, "a ball blob is mcrl_sizeof(17) = 104 bytes (Java: Layouts.BALL_BLOB_SIZE)");

struct SoccarBlob {
    uint8_t version;
    uint8_t ballOutOfPlay;
    uint8_t pad[2];
    int32_t lockTicks;
    uint32_t kickoffs, goals;
    int32_t goalTeam, goalToucher;
    int32_t lastToucher;
    float goalVel[3];
    double goalPos[3];
    uint64_t padActive;
    float padCooldown[34];
    int32_t padPrevCar[34];
    uint32_t padPickups[34];
    int32_t padCar[34];
};
static_assert(sizeof(SoccarBlob) == 616, "a soccar blob is mcrl_sizeof(18) = 616 bytes (Java: Layouts.SOCCAR_BLOB_SIZE)");

int32_t car_save(McrlWorld& w, int32_t carId, uint8_t* out);
int32_t car_load(McrlWorld& w, int32_t carId, const uint8_t* in);
int32_t ball_save(McrlWorld& w, uint8_t* out);
int32_t ball_load(McrlWorld& w, const uint8_t* in);
int32_t world_set_tick(McrlWorld& w, uint64_t tick);
int32_t soccar_save(McrlWorld& w, uint8_t* out);
int32_t soccar_load(McrlWorld& w, const uint8_t* in);

constexpr uint64_t EVENT_TICKS = 128;

struct CarCounters {
    uint32_t lands[4];
    uint32_t jumps, doubleJumps, dodges, flipResets, worldHits, bumps, ballHits;
};

struct EventCar {
    int32_t id;
    uint32_t gen;
    CarCounters counters;
};

struct EventEntry {
    uint64_t tick = std::numeric_limits<uint64_t>::max();
    std::vector<EventCar> cars;
    uint32_t bounces = 0;
};

struct EventRing {
    std::array<EventEntry, EVENT_TICKS> slots;
};

void reserve(EventRing& r, size_t nCars);
void record_events(McrlWorld& w);
int32_t rewind_events(McrlWorld& w, uint64_t tick);

}
