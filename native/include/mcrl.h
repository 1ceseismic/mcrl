#pragma once
#include <stdint.h>

#define MCRL_ABI_VERSION 7

#define MCRL_OK 0
#define MCRL_ERR_EXCEPTION -1
#define MCRL_ERR_THREAD -2
#define MCRL_ERR_ARG -3
#define MCRL_ERR_NOT_FOUND -4
#define MCRL_STALE 1

#if defined(__GNUC__)
#define MCRL_API __attribute__((visibility("default")))
#else
#define MCRL_API
#endif

#ifdef __cplusplus
#define MCRL_NOEXCEPT noexcept
#else
#define MCRL_NOEXCEPT
#endif

#define MCRL_CAR_ON_GROUND 1
#define MCRL_CAR_SUPERSONIC 2
#define MCRL_CAR_JUMPED 4
#define MCRL_CAR_DOUBLE_JUMPED 8
#define MCRL_CAR_FLIPPED 16
#define MCRL_CAR_DEMOED 32
#define MCRL_CAR_BOOSTING 64
#define MCRL_CAR_HELD 128
#define MCRL_CAR_RESTORED 256

#define MCRL_STEP_FROZEN 1
#define MCRL_STEP_BALL_PARKED 2
#define MCRL_STEP_RESTORED 4
#define MCRL_STEP_BALL_RESTORED 8

#define MCRL_WHEEL_CONTACT 1
#define MCRL_WHEEL_WORLD 2

#define MCRL_FX_JUMPING 1
#define MCRL_FX_DOUBLE_JUMPING 2
#define MCRL_FX_FLIPPING 4

#define MCRL_BUILT_PROVISIONAL 1

#define MCRL_SHAPE_THIN 1
#define MCRL_SHAPE_NONFLOOR 2
#define MCRL_SHAPE_MATERIAL_SHIFT 8

#define MCRL_EXTRAS_SURFACES 1
#define MCRL_EXTRAS_FLUIDS 2

/* Fluid cell byte: type | heightInNinths << 2. */
#define MCRL_FLUID_WATER 1
#define MCRL_FLUID_LAVA 2
#define MCRL_FLUID_MEDIUM 3

#ifdef __cplusplus
extern "C" {
#endif

/* Minecraft world frame: blocks, blocks/s, rad/s. */
typedef struct { double px, py, pz; float qw, qx, qy, qz; double vx, vy, vz; double wx, wy, wz; } McrlBody;

/* Wheels in RocketSim order: FR, FL, BR, BL. */
typedef struct {
    McrlBody body; float boost; uint32_t flags; float wheelLocal[12]; float steerAngle; uint32_t wheelContact;
    uint32_t ballHits; float ballHitSpeed; float stepDz; uint32_t reserved;
} McrlCarState;
typedef struct { McrlBody body; uint32_t bounces; float bounceSpeed; } McrlBallState;
typedef struct { float throttle, steer, pitch, yaw, roll; uint8_t jump, boost, handbrake, pad; } McrlControls;

/* Fx speeds are in UU/s (50 UU per block). */
typedef struct { double px, py, pz; float nx, ny, nz; uint32_t contact; uint32_t lands; float landSpeed; } McrlWheelFx;
typedef struct { double px, py, pz; float nx, ny, nz; uint32_t pad; } McrlHitFx;
typedef struct { float immersion; uint32_t fluid; uint32_t splashes; float splashSpeed; double sx, sy, sz; } McrlWetFx;
typedef struct {
    McrlWheelFx wheels[4]; float handbrake; uint32_t jumps, doubleJumps, dodges, flipResets, worldHits;
    float worldHitSpeed; uint32_t bodyContact, activeStates, bumps; double bpx, bpy, bpz;
    float bnx, bny, bnz, slideSpeed; McrlHitFx worldHit, ballHit; int32_t bumpedBy; float bumpSpeed; McrlWetFx wet;
} McrlCarFx;
typedef struct { uint32_t worldContact; int32_t lastTouchTeam; McrlHitFx bounce; McrlWetFx wet; } McrlBallFx;
typedef struct { float hitboxSize[3], hitboxOffset[3], wheelRadius[2], suspensionRest[2], connection[6];
                 int32_t threeWheels, preset, team, pad; } McrlCarConfig;
typedef struct {
    double goalX, goalY, goalZ; float goalVx, goalVy, goalVz;
    uint32_t goals; int32_t goalTeam, goalToucher; uint32_t ballOutOfPlay, kickoffs, lockTicks;
    uint8_t padActive[34]; uint8_t reserved[2]; float padCooldown[34]; uint32_t padPickups[34]; int32_t padCar[34];
} McrlSoccarState;

typedef struct { float wheelLat, wheelLong, wheelDrag, chassisFriction, chassisRestitution, ballFriction,
                 ballRestitution, ballDrag, padRestitution; uint32_t pad; } McrlMaterial;
typedef struct McrlWorld McrlWorld;
typedef struct McrlArenaMeshes McrlArenaMeshes;

/* Cell-local Minecraft coords in 1/4096 block. */
typedef struct { int16_t mn[3], mx[3]; } McrlBoxQ;
/* cells: N^3 ids with N = 16+2*border; borrowed. */
typedef struct {
    int32_t sx, sy, sz; uint32_t version; int32_t border; uint32_t flags; const uint16_t* cells;
} McrlSectionInput;
typedef struct { int32_t tris; float buildMs; uint32_t flags; } McrlBuildStats;
typedef struct McrlShapes McrlShapes;
/* Owned by the caller until committed or freed. */
typedef struct McrlBuilt McrlBuilt;

MCRL_API int32_t mcrl_abi_version(void) MCRL_NOEXCEPT;
/* Thread-local; valid until the next call on this thread. */
MCRL_API const char* mcrl_last_error(void) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_init(void) MCRL_NOEXCEPT;

MCRL_API int32_t mcrl_sizeof(int32_t which) MCRL_NOEXCEPT;

/* The calling thread becomes the world's owner thread. */
MCRL_API McrlWorld* mcrl_world_create(const McrlShapes* shapes, int32_t ox, int32_t oy, int32_t oz) MCRL_NOEXCEPT;

/* Any thread. Loaded mesh sets are never freed. */
MCRL_API McrlArenaMeshes* mcrl_arena_meshes_load(const uint8_t* const* files, const int32_t* sizes, int32_t n,
                                                 uint32_t* outHashes) MCRL_NOEXCEPT;
MCRL_API McrlWorld* mcrl_world_create_soccar(const McrlArenaMeshes* meshes, int32_t ox, int32_t oy,
                                             int32_t oz) MCRL_NOEXCEPT;

/* Owner thread only, else MCRL_ERR_THREAD. */
MCRL_API int32_t mcrl_world_destroy(McrlWorld* world) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_add(McrlWorld* world, int32_t carId, int32_t team, int32_t preset,
                              const McrlCarState* s) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_get_config(McrlWorld* world, int32_t carId, McrlCarConfig* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_remove(McrlWorld* world, int32_t carId) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_set_state(McrlWorld* world, int32_t carId, const McrlCarState* s) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_demolish(McrlWorld* world, int32_t carId) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_respawn(McrlWorld* world, int32_t carId, const McrlCarState* s) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_get_state(McrlWorld* world, int32_t carId, McrlCarState* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_set_controls(McrlWorld* world, int32_t carId, const McrlControls* c) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_ball_set_state(McrlWorld* world, const McrlBallState* s) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_ball_get_state(McrlWorld* world, McrlBallState* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_get_fx(McrlWorld* world, int32_t carId, McrlCarFx* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_ball_get_fx(McrlWorld* world, McrlBallFx* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_add_velocity(McrlWorld* world, int32_t carId, double dvx, double dvy,
                                       double dvz) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_ball_add_velocity(McrlWorld* world, double dvx, double dvy, double dvz) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_ball_clear_touch(McrlWorld* world) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_step(McrlWorld* world, int32_t ticks, uint32_t* outFlags) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_set_spawn_gate(McrlWorld* world, int32_t enabled) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_set_demos(McrlWorld* world, int32_t mode, int32_t teamDemos) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_set_gate_mode(McrlWorld* world, int32_t perCar) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_set_step_up(McrlWorld* world, int32_t enabled) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_set_extras(McrlWorld* world, uint32_t flags) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_shift_origin(McrlWorld* world, int32_t ox, int32_t oy, int32_t oz) MCRL_NOEXCEPT;
#define MCRL_BLOB_VERSION 1
MCRL_API int32_t mcrl_world_set_tick(McrlWorld* world, uint64_t tick) MCRL_NOEXCEPT;
/* Blobs: mcrl_sizeof(16) bytes per car, (17) ball. */
MCRL_API int32_t mcrl_car_save(McrlWorld* world, int32_t carId, uint8_t* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_load(McrlWorld* world, int32_t carId, const uint8_t* in) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_ball_save(McrlWorld* world, uint8_t* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_ball_load(McrlWorld* world, const uint8_t* in) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_rewind_events(McrlWorld* world, uint64_t tick) MCRL_NOEXCEPT;

#ifdef MCRL_TEST_HOOKS
MCRL_API int32_t mcrl_debug_poison(McrlWorld* world, int32_t carId) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_set_unlimited_boost(McrlWorld* world, int32_t on) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_world_get_origin(McrlWorld* world, int32_t* out3) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_section_is_ready(McrlWorld* world, int32_t sx, int32_t sy, int32_t sz) MCRL_NOEXCEPT;
#endif

MCRL_API int32_t mcrl_soccar_park_ball(McrlWorld* world) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_soccar_kickoff(McrlWorld* world, int32_t seed, int32_t lockTicks) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_soccar_get_state(McrlWorld* world, McrlSoccarState* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_soccar_respawn_state(McrlWorld* world, int32_t team, int32_t index,
                                           McrlCarState* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_soccar_pad_layout(McrlWorld* world, float* outXyz102, uint8_t* outBig34) MCRL_NOEXCEPT;
/* Blob buffers: mcrl_sizeof(18) bytes. */
MCRL_API int32_t mcrl_soccar_save(McrlWorld* world, uint8_t* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_soccar_load(McrlWorld* world, const uint8_t* in) MCRL_NOEXCEPT;

/* out: 89 floats. */
MCRL_API int32_t mcrl_bot_observe(McrlWorld* world, int32_t carId, int32_t prevAction, float* out) MCRL_NOEXCEPT;

/* Any thread. Reference counted: bots keep their own reference. */
typedef struct McrlBotPolicy McrlBotPolicy;
MCRL_API McrlBotPolicy* mcrl_bot_policy_create(const int32_t* dims, int32_t nLayers,
                                               const float* const* params) MCRL_NOEXCEPT;
MCRL_API void mcrl_bot_policy_free(McrlBotPolicy* p) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_bot_policy_forward(const McrlBotPolicy* p, const float* obs89, float* logits90) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_bot_add(McrlWorld* world, int32_t carId, const McrlBotPolicy* p, float temperature,
                              uint64_t seed) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_bot_remove(McrlWorld* world, int32_t carId) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_car_get_controls(McrlWorld* world, int32_t carId, McrlControls* out) MCRL_NOEXCEPT;

/* Always takes ownership of built, even on error. */
MCRL_API int32_t mcrl_section_commit(McrlWorld* world, McrlBuilt* built) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_section_remove(McrlWorld* world, int32_t sx, int32_t sy, int32_t sz) MCRL_NOEXCEPT;
/* cells: 4096 fluid bytes, index (y*16+z)*16+x. */
MCRL_API int32_t mcrl_section_set_fluid(McrlWorld* world, int32_t sx, int32_t sy, int32_t sz,
                                        const uint8_t* cells) MCRL_NOEXCEPT;

/* Shape registry and mesher: any thread. */
MCRL_API McrlShapes* mcrl_shapes_create(void) MCRL_NOEXCEPT;
/* Only once no world or build uses it. */
MCRL_API int32_t mcrl_shapes_destroy(McrlShapes* shapes) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_shapes_register(McrlShapes* shapes, const McrlBoxQ* boxes, int32_t n,
                                      uint32_t flags) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_shapes_count(const McrlShapes* shapes) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_shapes_set_material(McrlShapes* shapes, int32_t id, const McrlMaterial* m) MCRL_NOEXCEPT;
MCRL_API McrlBuilt* mcrl_section_build(const McrlShapes* shapes, const McrlSectionInput* in,
                                       McrlBuildStats* out) MCRL_NOEXCEPT;
MCRL_API int32_t mcrl_built_free(McrlBuilt* built) MCRL_NOEXCEPT;
/* cells: as mcrl_section_set_fluid; NULL removes the plane. */
MCRL_API int32_t mcrl_built_set_fluid(McrlBuilt* built, const uint8_t* cells) MCRL_NOEXCEPT;

#ifdef __cplusplus
}
#endif
