#include <cstddef>
#include <memory>
#include <mutex>
#include "RocketSim.h"
#include "bot.h"
#include "error.h"
#include "fluids.h"
#include "mesher.h"
#include "shapes.h"
#include "soccar.h"
#include "world.h"

static_assert(sizeof(McrlBody) == 88 && offsetof(McrlBody, qw) == 24 && offsetof(McrlBody, vx) == 40 &&
              offsetof(McrlBody, wx) == 64);
static_assert(sizeof(McrlCarState) == 168 && offsetof(McrlCarState, boost) == 88 &&
              offsetof(McrlCarState, flags) == 92 && offsetof(McrlCarState, wheelLocal) == 96 &&
              offsetof(McrlCarState, steerAngle) == 144 && offsetof(McrlCarState, wheelContact) == 148 &&
              offsetof(McrlCarState, ballHits) == 152 && offsetof(McrlCarState, ballHitSpeed) == 156 &&
              offsetof(McrlCarState, stepDz) == 160 && offsetof(McrlCarState, reserved) == 164);
static_assert(sizeof(McrlBallState) == 96 && offsetof(McrlBallState, bounces) == 88 &&
              offsetof(McrlBallState, bounceSpeed) == 92);
static_assert(sizeof(McrlControls) == 24 && offsetof(McrlControls, jump) == 20 && offsetof(McrlControls, pad) == 23);
static_assert(sizeof(McrlBoxQ) == 12 && offsetof(McrlBoxQ, mx) == 6);
static_assert(sizeof(McrlSectionInput) == 32 && offsetof(McrlSectionInput, version) == 12 &&
              offsetof(McrlSectionInput, border) == 16 && offsetof(McrlSectionInput, flags) == 20 &&
              offsetof(McrlSectionInput, cells) == 24);
static_assert(sizeof(McrlBuildStats) == 12 && offsetof(McrlBuildStats, buildMs) == 4 &&
              offsetof(McrlBuildStats, flags) == 8);
static_assert(sizeof(McrlWheelFx) == 48 && offsetof(McrlWheelFx, nx) == 24 && offsetof(McrlWheelFx, contact) == 36 &&
              offsetof(McrlWheelFx, lands) == 40 && offsetof(McrlWheelFx, landSpeed) == 44);
static_assert(sizeof(McrlHitFx) == 40 && offsetof(McrlHitFx, nx) == 24 && offsetof(McrlHitFx, pad) == 36);
static_assert(sizeof(McrlWetFx) == 40 && offsetof(McrlWetFx, fluid) == 4 && offsetof(McrlWetFx, splashes) == 8 &&
              offsetof(McrlWetFx, splashSpeed) == 12 && offsetof(McrlWetFx, sx) == 16 && offsetof(McrlWetFx, sz) == 32);
static_assert(sizeof(McrlCarFx) == 400 && offsetof(McrlCarFx, handbrake) == 192 && offsetof(McrlCarFx, jumps) == 196 &&
              offsetof(McrlCarFx, doubleJumps) == 200 && offsetof(McrlCarFx, dodges) == 204 &&
              offsetof(McrlCarFx, flipResets) == 208 && offsetof(McrlCarFx, worldHits) == 212 &&
              offsetof(McrlCarFx, worldHitSpeed) == 216 && offsetof(McrlCarFx, bodyContact) == 220 &&
              offsetof(McrlCarFx, activeStates) == 224 && offsetof(McrlCarFx, bumps) == 228 &&
              offsetof(McrlCarFx, bpx) == 232 && offsetof(McrlCarFx, bnx) == 256 &&
              offsetof(McrlCarFx, slideSpeed) == 268 && offsetof(McrlCarFx, worldHit) == 272 &&
              offsetof(McrlCarFx, ballHit) == 312 && offsetof(McrlCarFx, bumpedBy) == 352 &&
              offsetof(McrlCarFx, bumpSpeed) == 356 && offsetof(McrlCarFx, wet) == 360);
static_assert(sizeof(McrlBallFx) == 88 && offsetof(McrlBallFx, lastTouchTeam) == 4 && offsetof(McrlBallFx, bounce) == 8 &&
              offsetof(McrlBallFx, wet) == 48);
static_assert(sizeof(McrlCarConfig) == 80 && offsetof(McrlCarConfig, hitboxOffset) == 12 &&
              offsetof(McrlCarConfig, wheelRadius) == 24 && offsetof(McrlCarConfig, suspensionRest) == 32 &&
              offsetof(McrlCarConfig, connection) == 40 && offsetof(McrlCarConfig, threeWheels) == 64 &&
              offsetof(McrlCarConfig, preset) == 68 && offsetof(McrlCarConfig, team) == 72 &&
              offsetof(McrlCarConfig, pad) == 76);
static_assert(sizeof(McrlSoccarState) == 504 && offsetof(McrlSoccarState, goalVx) == 24 &&
              offsetof(McrlSoccarState, goals) == 36 && offsetof(McrlSoccarState, kickoffs) == 52 &&
              offsetof(McrlSoccarState, lockTicks) == 56 && offsetof(McrlSoccarState, padActive) == 60 &&
              offsetof(McrlSoccarState, padCooldown) == 96 && offsetof(McrlSoccarState, padPickups) == 232 &&
              offsetof(McrlSoccarState, padCar) == 368);
static_assert(sizeof(McrlMaterial) == 40 && offsetof(McrlMaterial, wheelDrag) == 8 &&
              offsetof(McrlMaterial, chassisRestitution) == 16 && offsetof(McrlMaterial, ballRestitution) == 24 &&
              offsetof(McrlMaterial, padRestitution) == 32 && offsetof(McrlMaterial, pad) == 36);

namespace {
thread_local std::string g_last_error;
std::mutex g_init_mutex;
bool g_inited = false;

template <class F>
auto guard_ptr(F&& f) noexcept -> decltype(f()) {
    try {
        mcrl_internal::clear_last_error();
        return f();
    } catch (const std::exception& e) {
        mcrl_internal::set_last_error(e.what());
    } catch (...) {
        mcrl_internal::set_last_error("unknown exception");
    }
    return nullptr;
}
}

namespace mcrl_internal {
void set_last_error(const char* msg) noexcept {
    try { g_last_error = msg ? msg : ""; } catch (...) {}
}
void clear_last_error() noexcept { g_last_error.clear(); }
std::mutex& init_mutex() { return g_init_mutex; }
void ensure_init() {
    std::lock_guard<std::mutex> lock(g_init_mutex);
    if (!g_inited) {
        RocketSim::Init("<nonexistent>");
        g_inited = true;
    }
}
}

int32_t mcrl::fail(int32_t code, const char* fn, const std::string& what) {
    mcrl_internal::set_last_error((std::string(fn) + ": " + what).c_str());
    return code;
}

#define WORLD_CALL(call)                                                                  \
    MCRL_GUARD(                                                                           \
        if (int32_t rc_ = mcrl::check_world(world, __func__); rc_ != MCRL_OK) return rc_; \
        return call;)

#define FAIL_ARG(msg)                       \
    do {                                    \
        mcrl_internal::set_last_error(msg); \
        return MCRL_ERR_ARG;                \
    } while (0)

extern "C" {

int32_t mcrl_abi_version(void) noexcept { return MCRL_ABI_VERSION; }

const char* mcrl_last_error(void) noexcept { return g_last_error.c_str(); }

int32_t mcrl_init(void) noexcept {
    MCRL_GUARD(mcrl_internal::ensure_init(););
}

int32_t mcrl_sizeof(int32_t which) noexcept {
    MCRL_GUARD(
        switch (which) {
            case 0: return (int32_t)sizeof(McrlBody);
            case 1: return (int32_t)sizeof(McrlCarState);
            case 2: return (int32_t)sizeof(McrlBallState);
            case 3: return (int32_t)sizeof(McrlControls);
            case 4: return (int32_t)sizeof(McrlSectionInput);
            case 5: return (int32_t)sizeof(McrlBuildStats);
            case 6: return (int32_t)sizeof(McrlCarFx);
            case 7: return (int32_t)sizeof(McrlBallFx);
            case 8: return (int32_t)sizeof(McrlCarConfig);
            case 9: return (int32_t)sizeof(McrlMaterial);
            case 10: return (int32_t)sizeof(McrlWetFx);
            case 12: return (int32_t)sizeof(McrlSoccarState);
            case 16: return (int32_t)sizeof(mcrl::blob::CarBlob);
            case 17: return (int32_t)sizeof(mcrl::blob::BallBlob);
            case 18: return (int32_t)sizeof(mcrl::blob::SoccarBlob);
            default: FAIL_ARG("mcrl_sizeof: unknown struct id");
        }
    );
}

McrlWorld* mcrl_world_create(const McrlShapes* shapes, int32_t ox, int32_t oy, int32_t oz) noexcept {
    return guard_ptr([&] { return mcrl::world_create(shapes, ox, oy, oz); });
}

McrlArenaMeshes* mcrl_arena_meshes_load(const uint8_t* const* files, const int32_t* sizes, int32_t n,
                                        uint32_t* outHashes) noexcept {
    return guard_ptr([&] { return mcrl::soccar::meshes_load(files, sizes, n, outHashes); });
}

McrlWorld* mcrl_world_create_soccar(const McrlArenaMeshes* meshes, int32_t ox, int32_t oy, int32_t oz) noexcept {
    return guard_ptr([&]() -> McrlWorld* {
        if (!meshes) {
            mcrl_internal::set_last_error("mcrl_world_create_soccar: meshes is NULL");
            return nullptr;
        }
        return mcrl::world_create_soccar(*meshes, ox, oy, oz);
    });
}

int32_t mcrl_world_destroy(McrlWorld* world) noexcept { WORLD_CALL(mcrl::world_destroy(world)); }

int32_t mcrl_car_add(McrlWorld* world, int32_t carId, int32_t team, int32_t preset, const McrlCarState* s) noexcept {
    WORLD_CALL(mcrl::car_add(*world, carId, team, preset, s));
}

int32_t mcrl_car_get_config(McrlWorld* world, int32_t carId, McrlCarConfig* out) noexcept {
    WORLD_CALL(mcrl::car_get_config(*world, carId, out));
}

int32_t mcrl_car_remove(McrlWorld* world, int32_t carId) noexcept { WORLD_CALL(mcrl::car_remove(*world, carId)); }

int32_t mcrl_car_set_state(McrlWorld* world, int32_t carId, const McrlCarState* s) noexcept {
    WORLD_CALL(mcrl::car_set_state(*world, carId, s));
}

int32_t mcrl_car_demolish(McrlWorld* world, int32_t carId) noexcept {
    WORLD_CALL(mcrl::car_demolish(*world, carId));
}

int32_t mcrl_car_respawn(McrlWorld* world, int32_t carId, const McrlCarState* s) noexcept {
    WORLD_CALL(mcrl::car_respawn(*world, carId, s));
}

int32_t mcrl_car_get_state(McrlWorld* world, int32_t carId, McrlCarState* out) noexcept {
    WORLD_CALL(mcrl::car_get_state(*world, carId, out));
}

int32_t mcrl_car_set_controls(McrlWorld* world, int32_t carId, const McrlControls* c) noexcept {
    WORLD_CALL(mcrl::car_set_controls(*world, carId, c));
}

int32_t mcrl_ball_set_state(McrlWorld* world, const McrlBallState* s) noexcept {
    WORLD_CALL(mcrl::ball_set_state(*world, s));
}

int32_t mcrl_ball_get_state(McrlWorld* world, McrlBallState* out) noexcept {
    WORLD_CALL(mcrl::ball_get_state(*world, out));
}

int32_t mcrl_car_get_fx(McrlWorld* world, int32_t carId, McrlCarFx* out) noexcept {
    WORLD_CALL(mcrl::car_get_fx(*world, carId, out));
}

int32_t mcrl_ball_get_fx(McrlWorld* world, McrlBallFx* out) noexcept { WORLD_CALL(mcrl::ball_get_fx(*world, out)); }

int32_t mcrl_car_add_velocity(McrlWorld* world, int32_t carId, double dvx, double dvy, double dvz) noexcept {
    WORLD_CALL(mcrl::car_add_velocity(*world, carId, dvx, dvy, dvz));
}

int32_t mcrl_ball_add_velocity(McrlWorld* world, double dvx, double dvy, double dvz) noexcept {
    WORLD_CALL(mcrl::ball_add_velocity(*world, dvx, dvy, dvz));
}

int32_t mcrl_ball_clear_touch(McrlWorld* world) noexcept { WORLD_CALL(mcrl::ball_clear_touch(*world)); }

int32_t mcrl_step(McrlWorld* world, int32_t ticks, uint32_t* outFlags) noexcept {
    if (outFlags) *outFlags = 0;
    WORLD_CALL(mcrl::step(*world, ticks, outFlags));
}

int32_t mcrl_world_set_spawn_gate(McrlWorld* world, int32_t enabled) noexcept {
    WORLD_CALL(mcrl::set_spawn_gate(*world, enabled));
}

int32_t mcrl_world_set_gate_mode(McrlWorld* world, int32_t perCar) noexcept {
    WORLD_CALL(mcrl::set_gate_mode(*world, perCar));
}

int32_t mcrl_world_set_step_up(McrlWorld* world, int32_t enabled) noexcept {
    WORLD_CALL(mcrl::set_step_up(*world, enabled));
}

int32_t mcrl_world_set_demos(McrlWorld* world, int32_t mode, int32_t teamDemos) noexcept {
    WORLD_CALL(mcrl::set_demos(*world, mode, teamDemos));
}

int32_t mcrl_world_set_extras(McrlWorld* world, uint32_t flags) noexcept {
    WORLD_CALL(mcrl::set_extras(*world, flags));
}

int32_t mcrl_world_shift_origin(McrlWorld* world, int32_t ox, int32_t oy, int32_t oz) noexcept {
    WORLD_CALL(mcrl::world_shift_origin(*world, ox, oy, oz));
}

int32_t mcrl_world_set_tick(McrlWorld* world, uint64_t tick) noexcept {
    WORLD_CALL(mcrl::blob::world_set_tick(*world, tick));
}

int32_t mcrl_car_save(McrlWorld* world, int32_t carId, uint8_t* out) noexcept {
    WORLD_CALL(mcrl::blob::car_save(*world, carId, out));
}

int32_t mcrl_car_load(McrlWorld* world, int32_t carId, const uint8_t* in) noexcept {
    WORLD_CALL(mcrl::blob::car_load(*world, carId, in));
}

int32_t mcrl_ball_save(McrlWorld* world, uint8_t* out) noexcept { WORLD_CALL(mcrl::blob::ball_save(*world, out)); }

int32_t mcrl_ball_load(McrlWorld* world, const uint8_t* in) noexcept {
    WORLD_CALL(mcrl::blob::ball_load(*world, in));
}

int32_t mcrl_soccar_save(McrlWorld* world, uint8_t* out) noexcept {
    WORLD_CALL(mcrl::blob::soccar_save(*world, out));
}

int32_t mcrl_soccar_load(McrlWorld* world, const uint8_t* in) noexcept {
    WORLD_CALL(mcrl::blob::soccar_load(*world, in));
}

int32_t mcrl_world_rewind_events(McrlWorld* world, uint64_t tick) noexcept {
    WORLD_CALL(mcrl::blob::rewind_events(*world, tick));
}

#ifdef MCRL_TEST_HOOKS
int32_t mcrl_debug_poison(McrlWorld* world, int32_t carId) noexcept {
    WORLD_CALL(mcrl::guard::arm_poison(*world, carId));
}

int32_t mcrl_world_set_unlimited_boost(McrlWorld* world, int32_t on) noexcept {
    WORLD_CALL(mcrl::set_unlimited_boost(*world, on));
}

int32_t mcrl_world_get_origin(McrlWorld* world, int32_t* out3) noexcept {
    WORLD_CALL(mcrl::world_get_origin(*world, out3));
}

int32_t mcrl_section_is_ready(McrlWorld* world, int32_t sx, int32_t sy, int32_t sz) noexcept {
    WORLD_CALL(mcrl::section_is_ready(*world, sx, sy, sz));
}
#endif

int32_t mcrl_soccar_kickoff(McrlWorld* world, int32_t seed, int32_t lockTicks) noexcept {
    WORLD_CALL(mcrl::soccar::kickoff(*world, seed, lockTicks));
}

int32_t mcrl_soccar_get_state(McrlWorld* world, McrlSoccarState* out) noexcept {
    WORLD_CALL(mcrl::soccar::get_state(*world, out));
}

int32_t mcrl_soccar_respawn_state(McrlWorld* world, int32_t team, int32_t index, McrlCarState* out) noexcept {
    WORLD_CALL(mcrl::soccar::respawn_state(*world, team, index, out));
}

int32_t mcrl_soccar_pad_layout(McrlWorld* world, float* outXyz102, uint8_t* outBig34) noexcept {
    WORLD_CALL(mcrl::soccar::pad_layout(*world, outXyz102, outBig34));
}

int32_t mcrl_bot_observe(McrlWorld* world, int32_t carId, int32_t prevAction, float* out) noexcept {
    WORLD_CALL(mcrl::bot::observe_checked(*world, carId, prevAction, out));
}

McrlBotPolicy* mcrl_bot_policy_create(const int32_t* dims, int32_t nLayers, const float* const* params) noexcept {
    return guard_ptr([&] { return mcrl::bot::policy_create(dims, nLayers, params); });
}

void mcrl_bot_policy_free(McrlBotPolicy* p) noexcept { delete p; }

int32_t mcrl_bot_policy_forward(const McrlBotPolicy* p, const float* obs89, float* logits90) noexcept {
    MCRL_GUARD(return mcrl::bot::policy_forward(p, obs89, logits90););
}

int32_t mcrl_bot_add(McrlWorld* world, int32_t carId, const McrlBotPolicy* p, float temperature,
                     uint64_t seed) noexcept {
    WORLD_CALL(mcrl::bot::add(*world, carId, p, temperature, seed));
}

int32_t mcrl_bot_remove(McrlWorld* world, int32_t carId) noexcept { WORLD_CALL(mcrl::bot::remove(*world, carId)); }

int32_t mcrl_car_get_controls(McrlWorld* world, int32_t carId, McrlControls* out) noexcept {
    WORLD_CALL(mcrl::bot::car_get_controls(*world, carId, out));
}

int32_t mcrl_soccar_park_ball(McrlWorld* world) noexcept { WORLD_CALL(mcrl::soccar::park_ball(*world)); }

int32_t mcrl_section_commit(McrlWorld* world, McrlBuilt* built) noexcept {
    std::unique_ptr<McrlBuilt> owned(built);
    MCRL_GUARD(
        if (int32_t rc_ = mcrl::check_world(world, __func__); rc_ != MCRL_OK) return rc_;
        if (!owned) FAIL_ARG("mcrl_section_commit: built is NULL");
        return mcrl::section_commit(*world, std::move(owned));
    );
}

int32_t mcrl_section_remove(McrlWorld* world, int32_t sx, int32_t sy, int32_t sz) noexcept {
    WORLD_CALL(mcrl::section_remove(*world, sx, sy, sz));
}

int32_t mcrl_section_set_fluid(McrlWorld* world, int32_t sx, int32_t sy, int32_t sz, const uint8_t* cells) noexcept {
    WORLD_CALL(mcrl::section_set_fluid(*world, sx, sy, sz, cells));
}

McrlShapes* mcrl_shapes_create(void) noexcept {
    return guard_ptr([] { return mcrl::shapes_create(); });
}

int32_t mcrl_shapes_destroy(McrlShapes* shapes) noexcept {
    MCRL_GUARD(
        if (!shapes) FAIL_ARG("mcrl_shapes_destroy: shapes is NULL");
        delete shapes;
    );
}

int32_t mcrl_shapes_register(McrlShapes* shapes, const McrlBoxQ* boxes, int32_t n, uint32_t flags) noexcept {
    MCRL_GUARD(
        if (!shapes) FAIL_ARG("mcrl_shapes_register: shapes is NULL");
        return mcrl::shapes_register(*shapes, boxes, n, flags);
    );
}

int32_t mcrl_shapes_count(const McrlShapes* shapes) noexcept {
    MCRL_GUARD(
        if (!shapes) FAIL_ARG("mcrl_shapes_count: shapes is NULL");
        return mcrl::shapes_count(*shapes);
    );
}

int32_t mcrl_shapes_set_material(McrlShapes* shapes, int32_t id, const McrlMaterial* m) noexcept {
    MCRL_GUARD(
        if (!shapes) FAIL_ARG("mcrl_shapes_set_material: shapes is NULL");
        return mcrl::shapes_set_material(*shapes, id, m);
    );
}

McrlBuilt* mcrl_section_build(const McrlShapes* shapes, const McrlSectionInput* in, McrlBuildStats* out) noexcept {
    return guard_ptr([&]() -> McrlBuilt* {
        if (!shapes) {
            mcrl_internal::set_last_error("mcrl_section_build: shapes is NULL");
            return nullptr;
        }
        if (!in) {
            mcrl_internal::set_last_error("mcrl_section_build: input is NULL");
            return nullptr;
        }
        return mcrl::section_build(*shapes, *in, out).release();
    });
}

int32_t mcrl_built_free(McrlBuilt* built) noexcept {
    MCRL_GUARD(
        if (!built) FAIL_ARG("mcrl_built_free: built is NULL");
        delete built;
    );
}

int32_t mcrl_built_set_fluid(McrlBuilt* built, const uint8_t* cells) noexcept {
    MCRL_GUARD(
        if (!built) FAIL_ARG("mcrl_built_set_fluid: built is NULL");
        if (!cells) {
            built->fluid.clear();
            return MCRL_OK;
        }
        if (!mcrl::fluids::valid_plane(cells))
            FAIL_ARG("mcrl_built_set_fluid: a fluid byte with a height outside 1..9");
        built->fluid.assign(cells, cells + mcrl::SECTION_CELLS);
    );
}

}
