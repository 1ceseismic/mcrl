#include "world.h"

#include <algorithm>
#include <cmath>
#include <iterator>
#include <memory>
#include <vector>

#include "coords.h"
#include "fluids.h"
#include "fxfeed.h"
#include "materials.h"

using namespace RocketSim;

namespace mcrl {
namespace {

bool finite(double v) { return std::isfinite(v); }

bool body_ok(const McrlBody& b) {
    const double q2 = (double)b.qw * b.qw + (double)b.qx * b.qx + (double)b.qy * b.qy + (double)b.qz * b.qz;
    return finite(b.px) && finite(b.py) && finite(b.pz) && finite(b.vx) && finite(b.vy) && finite(b.vz) &&
           finite(b.wx) && finite(b.wy) && finite(b.wz) && finite(b.qw) && finite(b.qx) && finite(b.qy) &&
           finite(b.qz) && q2 > 1e-12;
}

void body_to_rs(const McrlWorld& w, const McrlBody& b, PhysState& ps) {
    ps.pos = coords::mcToRsPos(b.px, b.py, b.pz, w.ox, w.oy, w.oz);
    ps.rotMat = coords::mcQuatToRs(b.qw, b.qx, b.qy, b.qz);
    ps.vel = coords::mcToRsVel(b.vx, b.vy, b.vz);
    ps.angVel = coords::mcToRsAngVel(b.wx, b.wy, b.wz);
}

bool boost_ok(const McrlWorld& w, const McrlCarState& s) { return w.unlimitedBoost || std::isfinite(s.boost); }

void put_car_state(McrlWorld& w, Car* car, McrlCarEvents& ev, const CarState& cs) {
    car->SetState(cs);
    ev.lastSet = {cs, w.arena->tickCount, w.ox, w.oy, w.oz};
    ev.fxStale = true;
}

void apply_car_state(McrlWorld& w, Car* car, McrlCarEvents& ev, const McrlCarState& s) {
    CarState cs = car->GetState();
    body_to_rs(w, s.body, cs);
    cs.boost = w.unlimitedBoost ? RLConst::BOOST_MAX : std::clamp(s.boost, 0.f, RLConst::BOOST_MAX);
    put_car_state(w, car, ev, cs);
}

void moved(McrlWorld& w, int32_t carId, McrlCarEvents& ev) {
    w.stepRays.at(carId)->floor.clear();
    ev.wheelsStale = true;
    ev.stepDz = 0;
}

const CarConfig* const PRESETS[] = {&CAR_CONFIG_OCTANE, &CAR_CONFIG_DOMINUS, &CAR_CONFIG_PLANK, &CAR_CONFIG_BREAKOUT,
                                    &CAR_CONFIG_HYBRID, &CAR_CONFIG_MERC, &CAR_CONFIG_PSYCLOPS};
constexpr int32_t PRESET_COUNT = (int32_t)(sizeof PRESETS / sizeof PRESETS[0]);

// Keeps RocketSim's own demo respawn from ever firing.
constexpr float NO_RESPAWN_DELAY = 1e9f;

constexpr const char* NO_TERRAIN = "a SOCCAR world has no terrain";

constexpr DemoMode DEMO_MODES[] = {DemoMode::DISABLED, DemoMode::NORMAL, DemoMode::ON_CONTACT};

// Runs inside Bullet's narrowphase: must not allocate or throw.
void on_bump(Arena*, Car* bumper, Car* victim, bool, void* userInfo) {
    McrlWorld& w = *static_cast<McrlWorld*>(userInfo);
    const auto victimId = w.rsIds.find(victim->id);
    if (victimId == w.rsIds.end()) return;
    const auto it = w.carEvents.find(victimId->second);
    if (it == w.carEvents.end()) return;
    const auto bumperId = w.rsIds.find(bumper->id);
    const CarState bs = bumper->GetState();
    const CarState vs = victim->GetState();
    McrlCarEvents& ev = it->second;
    ev.bumps++;
    ev.bumpedBy = bumperId == w.rsIds.end() ? -1 : bumperId->second;
    ev.bumpSpeed = bs.vel.Dot((vs.pos - bs.pos).Normalized());
}

void set_boost_mutators(MutatorConfig& mut, bool unlimited) {
    mut.boostUsedPerSecond = unlimited ? 0 : RLConst::BOOST_USED_PER_SECOND;
    mut.carSpawnBoostAmount = unlimited ? RLConst::BOOST_MAX : RLConst::BOOST_SPAWN_AMOUNT;
}

void local_uu_to_mc(const Vec& v, float out[3]) { coords::rsLocalToMc(btVector3(v.x, v.y, v.z) * UU_TO_BT, out); }

btVector3 mc_vel_bt(double vx, double vy, double vz) { return btVector3((btScalar)vx, (btScalar)vz, (btScalar)vy); }

float axis(float v) { return std::isnan(v) ? 0.f : std::clamp(v, -1.f, 1.f); }

btVector3 minus(const btVector3& p, const double d[3]) {
    return btVector3((btScalar)((double)p.x() - d[0]), (btScalar)((double)p.y() - d[1]),
                     (btScalar)((double)p.z() - d[2]));
}

// Not through SetState, which resets RocketSim's per-tick bookkeeping.
void shift_body(btDiscreteDynamicsWorld& bw, btRigidBody& rb, const double d[3]) {
    btTransform t = rb.getWorldTransform();
    t.setOrigin(minus(t.getOrigin(), d));
    rb.setWorldTransform(t);
    // updateSingleAabb also spans the interpolation transform.
    btTransform it = rb.getInterpolationWorldTransform();
    it.setOrigin(minus(it.getOrigin(), d));
    rb.setInterpolationWorldTransform(it);
    bw.updateSingleAabb(&rb);
}

constexpr double SWEEP_S = 2.0 / 120;
constexpr double PAD = 1;

bool swept_box_ready(const McrlWorld& w, const btVector3& lo, const btVector3& hi, const btVector3& v) {
    const double o[3] = {(double)w.ox, (double)w.oz, (double)w.oy};
    int32_t smin[3], smax[3];
    for (int a = 0; a < 3; a++) {
        const double d = (double)v[a] * SWEEP_S;
        const double mn = o[a] + lo[a] + std::min(d, 0.0) - PAD;
        const double mx = o[a] + hi[a] + std::max(d, 0.0) + PAD;
        if (!(std::abs(mn) < 1e9 && std::abs(mx) < 1e9)) return false;
        smin[a] = (int32_t)std::floor(mn / 16);
        smax[a] = (int32_t)std::floor(mx / 16);
    }
    for (int32_t sy = smin[2]; sy <= smax[2]; sy++)
        for (int32_t sz = smin[1]; sz <= smax[1]; sz++)
            for (int32_t sx = smin[0]; sx <= smax[0]; sx++)
                if (!w.terrain->IsReady({sx, sy, sz})) return false;
    return true;
}

bool car_ready(const McrlWorld& w, const Car& car) {
    btVector3 lo, hi;
    car._rigidBody.getAabb(lo, hi);
    return swept_box_ready(w, lo, hi, car._rigidBody.getLinearVelocity());
}

bool cars_ready(const McrlWorld& w) {
    for (const auto& [id, car] : w.cars)
        if (!car->_internalState.isDemoed && !car_ready(w, *car)) return false;
    return true;
}

bool begin_tick(McrlWorld& w) {
    const bool perCar = w.spawnGate && w.perCarGate;
    bool any = false;
    for (const auto& [id, car] : w.cars) {
        McrlCarEvents& ev = w.carEvents.at(id);
        ev.held = perCar && !car->_internalState.isDemoed && !car_ready(w, *car);
        any = any || ev.held;
    }
    return any;
}

McrlHeldCar hold(McrlWorld& w, int32_t carId, Car& car) {
    btRigidBody& rb = car._rigidBody;
    VirtualFloor& floor = w.stepRays.at(carId)->floor;
    McrlHeldCar h{&car,
                  rb.getWorldTransform(),
                  rb.getInterpolationWorldTransform(),
                  rb.getLinearVelocity(),
                  rb.getAngularVelocity(),
                  rb.getInterpolationLinearVelocity(),
                  rb.getInterpolationAngularVelocity(),
                  car._internalState,
                  car.controls,
                  &floor,
                  floor.used};
    rb.setLinearVelocity(btVector3(0, 0, 0));
    rb.setAngularVelocity(btVector3(0, 0, 0));
    car.controls = CarControls();
    car._internalState.isSupersonic = false;
    return h;
}

void put_back(McrlWorld& w, const McrlHeldCar& h) {
    const CarState& tick = h.car->_internalState;
    const bool demoed = tick.isDemoed;
    const float respawnTimer = tick.demoRespawnTimer;
    const auto carContact = tick.carContact;
    const BallHitInfo ballHit = tick.ballHitInfo;
    btRigidBody& rb = h.car->_rigidBody;
    rb.setWorldTransform(h.t);
    rb.setInterpolationWorldTransform(h.it);
    rb.setLinearVelocity(h.v);
    rb.setAngularVelocity(h.av);
    rb.setInterpolationLinearVelocity(h.iv);
    rb.setInterpolationAngularVelocity(h.iav);
    rb.updateInertiaTensor();
    h.car->_internalState = h.state;
    if (demoed) {
        h.car->_internalState.isDemoed = true;
        h.car->_internalState.demoRespawnTimer = respawnTimer;
    }
    h.car->_internalState.carContact = carContact;
    h.car->_internalState.ballHitInfo = ballHit;
    h.car->controls = h.controls;
    h.floor->used = h.floorUsed;
    btDiscreteDynamicsWorld& bw = w.arena->_bulletWorld;
    bw.updateSingleAabb(&rb);
    guard::drop_contacts(bw, rb);
}

// Not getAabb: RocketSim pads sphere AABBs.
bool ball_ready(const McrlWorld& w, const btVector3& v) {
    const Ball* ball = w.arena->ball;
    const btVector3& c = ball->_rigidBody.getWorldTransform().getOrigin();
    const btScalar r = ball->GetRadiusBullet();
    const btVector3 e(r, r, r);
    return swept_box_ready(w, c - e, c + e, v);
}

void park_ball(McrlWorld& w) {
    btRigidBody& rb = w.arena->ball->_rigidBody;
    w.parkedVel = rb.getLinearVelocity();
    w.parkedAngVel = rb.getAngularVelocity();
    rb.setLinearVelocity(btVector3(0, 0, 0));
    rb.setAngularVelocity(btVector3(0, 0, 0));
    w.ballParked = true;
}

void unpark_ball(McrlWorld& w) {
    btRigidBody& rb = w.arena->ball->_rigidBody;
    rb.setLinearVelocity(w.parkedVel);
    rb.setAngularVelocity(w.parkedAngVel);
    w.ballParked = false;
}

void update_ball_parking(McrlWorld& w) {
    if (w.ballParked) {
        if (ball_ready(w, w.parkedVel)) unpark_ball(w);
    } else if (!ball_ready(w, w.arena->ball->_rigidBody.getLinearVelocity())) {
        park_ball(w);
    }
}

class SavedMotionOnBall {
public:
    explicit SavedMotionOnBall(McrlWorld& world) : w(world) {
        if (!w.ballParked) return;
        btRigidBody& rb = w.arena->ball->_rigidBody;
        rb.setLinearVelocity(w.parkedVel);
        rb.setAngularVelocity(w.parkedAngVel);
    }
    ~SavedMotionOnBall() {
        if (w.ballParked) park_ball(w);
    }
    SavedMotionOnBall(const SavedMotionOnBall&) = delete;
    SavedMotionOnBall& operator=(const SavedMotionOnBall&) = delete;

private:
    McrlWorld& w;
};

void step_tick(McrlWorld& w) {
    // Another thread's Arena::Create may have reset it.
    materials::install_callback();
    if (w.extras & MCRL_EXTRAS_FLUIDS) fluids::before_tick(w);
    std::vector<fxfeed::Before>& cars = w.fxScratch;
    std::vector<McrlHeldCar>& held = w.heldScratch;
    cars.clear();
    held.clear();
    for (auto& [id, car] : w.cars) {
        if (w.carEvents.at(id).held) held.push_back(hold(w, id, *car));
        cars.push_back(fxfeed::capture(w, id, *car));
    }
    fxfeed::BallBefore ball = fxfeed::capture(*w.arena->ball);

    soccar::before_step(w);
    w.arena->Step(1);
    soccar::after_step(w);

    for (const McrlHeldCar& h : held) put_back(w, h);
    fxfeed::after(w, cars, ball);
    if (w.extras != 0) materials::after_tick(w, cars, ball);
    soccar::after_tick(w);
}

void assisted_tick(McrlWorld& w) {
    std::vector<McrlAssistSlot>& assist = w.assistScratch;
    assist.clear();
    for (auto& [id, car] : w.cars) {
        McrlCarEvents& ev = w.carEvents[id];
        if (car->_internalState.isDemoed || ev.held) {
            ev.preOutcome = ev.held ? stepassist::Outcome::SKIPPED : stepassist::Outcome::BLOCKED;
            ev.stepDz = 0;
            continue;
        }
        VirtualFloor& floor = w.stepRays.at(id)->floor;
        McrlAssistSlot a{car, &floor, &ev, stepassist::capture(*car, w.stepUp), 0};
        if (ev.wheelsStale) {
            ev.preOutcome = stepassist::Outcome::SKIPPED;
        } else {
            a.dz = stepassist::pre(w, car, floor, w.stepUp, &ev.preOutcome);
        }
        assist.push_back(a);
    }
    step_tick(w);
    for (McrlAssistSlot& a : assist) {
        const float net = stepassist::post(w, a.car, *a.floor, a.dz > 0, a.pose);
        a.ev->stepDz = a.dz + net;
        a.ev->wheelsStale = false;
    }
}

std::unique_ptr<McrlWorld> new_world(Arena& arena, int32_t ox, int32_t oy, int32_t oz) {
    auto w = std::make_unique<McrlWorld>();
    std::fill(std::begin(w->materials), std::end(w->materials), DEFAULT_MATERIAL);
    w->ox = ox;
    w->oy = oy;
    w->oz = oz;
    w->owner = std::this_thread::get_id();
    w->ballLastSet = {arena.ball->GetState(), 0, ox, oy, oz};
    arena.SetCarBumpCallback(on_bump, w.get());
    return w;
}

}

void rs_to_body(const McrlWorld& w, const PhysState& ps, McrlBody& b) {
    double v[3];
    coords::rsToMcPos(ps.pos, w.ox, w.oy, w.oz, v);
    b.px = v[0]; b.py = v[1]; b.pz = v[2];
    float q[4];
    coords::rsRotToMcQuat(ps.rotMat, q);
    b.qw = q[0]; b.qx = q[1]; b.qy = q[2]; b.qz = q[3];
    coords::rsToMcVel(ps.vel, v);
    b.vx = v[0]; b.vy = v[1]; b.vz = v[2];
    coords::rsToMcAngVel(ps.angVel, v);
    b.wx = v[0]; b.wy = v[1]; b.wz = v[2];
}

McrlWorld* world_create(const McrlShapes* shapes, int32_t ox, int32_t oy, int32_t oz) {
    mcrl_internal::ensure_init();

    ArenaConfig cfg;
    // RocketSim's grid broadphase only covers the soccar field.
    cfg.useCustomBroadphase = false;
    cfg.noBallRot = false;
    std::unique_ptr<Arena> arena(Arena::Create(GameMode::THE_VOID, cfg, 120));
    materials::install_callback();

    MutatorConfig mut = arena->GetMutatorConfig();
    mut.demoMode = DemoMode::DISABLED;
    mut.respawnDelay = NO_RESPAWN_DELAY;
    set_boost_mutators(mut, true);
    arena->SetMutatorConfig(mut);

    std::unique_ptr<McrlWorld> w = new_world(*arena, ox, oy, oz);
    w->shapes = shapes;
    w->terrain = std::make_unique<TerrainStore>(w.get(), arena.get(), ox, oy, oz);
    w->arena = arena.release();
    return w.release();
}

McrlWorld* world_create_soccar(const McrlArenaMeshes& meshes, int32_t ox, int32_t oy, int32_t oz) {
    std::unique_ptr<Arena> arena = soccar::create_arena(meshes);

    MutatorConfig mut = arena->GetMutatorConfig();
    mut.demoMode = DemoMode::NORMAL;
    mut.enableTeamDemos = false;
    mut.respawnDelay = NO_RESPAWN_DELAY;
    set_boost_mutators(mut, false);
    arena->SetMutatorConfig(mut);

    std::unique_ptr<McrlWorld> w = new_world(*arena, ox, oy, oz);
    w->unlimitedBoost = false;
    w->soccar = std::make_unique<soccar::Soccar>();
    w->arena = arena.release();
    soccar::take_ball_out(*w);
    return w.release();
}

void car_was_set(McrlWorld& w, int32_t carId) {
    McrlCarEvents& ev = w.carEvents.at(carId);
    ev.lastSet = {w.cars.at(carId)->GetState(), w.arena->tickCount, w.ox, w.oy, w.oz};
    ev.fxStale = true;
    moved(w, carId, ev);
}

void ball_was_set(McrlWorld& w) {
    w.ballLastSet = {w.arena->ball->GetState(), w.arena->tickCount, w.ox, w.oy, w.oz};
    w.ballParked = false;
    w.ballFxStale = true;
}

Car* find_car(McrlWorld& w, int32_t carId) {
    auto it = w.cars.find(carId);
    return it == w.cars.end() ? nullptr : it->second;
}

int32_t check_world(const McrlWorld* w, const char* fn) {
    if (!w) return fail(MCRL_ERR_ARG, fn, "world is NULL");
    if (std::this_thread::get_id() != w->owner) return fail(MCRL_ERR_THREAD, fn, "called off the owner thread");
    return MCRL_OK;
}

int32_t world_destroy(McrlWorld* w) {
    // Terrain bodies leave before the arena, raycasters after it.
    w->terrain.reset();
    delete w->arena;
    w->arena = nullptr;
    delete w;
    return MCRL_OK;
}

int32_t car_add(McrlWorld& w, int32_t carId, int32_t team, int32_t preset, const McrlCarState* s) {
    static const char* fn = "mcrl_car_add";
    if (!s) return fail(MCRL_ERR_ARG, fn, "state is NULL");
    if (team != 0 && team != 1) return fail(MCRL_ERR_ARG, fn, "team must be 0 (BLUE) or 1 (ORANGE)");
    if (preset < 0 || preset >= PRESET_COUNT) return fail(MCRL_ERR_ARG, fn, "preset must be 0..6");
    if (!body_ok(s->body) || !boost_ok(w, *s)) return fail(MCRL_ERR_ARG, fn, "non-finite value or zero quaternion");
    auto [it, inserted] = w.cars.try_emplace(carId, nullptr);
    if (!inserted) return fail(MCRL_ERR_ARG, fn, "duplicate car id");
    Car* car = nullptr;
    StepRaycaster* ray = nullptr;
    try {
        auto r = std::make_unique<StepRaycaster>();
        ray = r.get();
        w.stepRays[carId] = std::move(r);
        w.carEvents[carId] = McrlCarEvents{};
        guard::reserve(w.guard, w.cars.size());
        blob::reserve(w.events, w.cars.size());
        car = w.arena->AddCar(team == 0 ? Team::BLUE : Team::ORANGE, *PRESETS[preset]);
        w.rsIds[car->id] = carId;
    } catch (...) {
        if (car) {
            w.rsIds.erase(car->id);
            w.arena->RemoveCar(car);
        }
        w.cars.erase(it);
        w.carEvents.erase(carId);
        w.stepRays.erase(carId);
        throw;
    }
    ray->installInto(*car);
    it->second = car;
    McrlCarEvents& ev = w.carEvents.at(carId);
    ev.preset = preset;
    ev.team = team;
    ev.gen = ++w.carGens;
    // RocketSim leaves suspension length unset until the first raycast.
    for (int i = 0; i < car->_bulletVehicle.getNumWheels(); i++) {
        btWheelInfoRL& wi = car->_bulletVehicle.m_wheelInfo[i];
        wi.m_raycastInfo.m_suspensionLength = wi.getSuspensionRestLength() + wi.m_maxSuspensionTravelCm / 100;
    }
    apply_car_state(w, car, ev, *s);
    return MCRL_OK;
}

int32_t car_remove(McrlWorld& w, int32_t carId) {
    auto it = w.cars.find(carId);
    if (it == w.cars.end()) return fail(MCRL_ERR_NOT_FOUND, "mcrl_car_remove", "unknown car id");
    Car* car = it->second;
    bot::remove(w, carId);
    w.cars.erase(it);
    w.carEvents.erase(carId);
    w.rsIds.erase(car->id);
    w.arena->RemoveCar(car);
    w.stepRays.erase(carId);
    return MCRL_OK;
}

int32_t car_set_state(McrlWorld& w, int32_t carId, const McrlCarState* s) {
    static const char* fn = "mcrl_car_set_state";
    if (!s) return fail(MCRL_ERR_ARG, fn, "state is NULL");
    if (!body_ok(s->body) || !boost_ok(w, *s)) return fail(MCRL_ERR_ARG, fn, "non-finite value or zero quaternion");
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    McrlCarEvents& ev = w.carEvents[carId];
    apply_car_state(w, car, ev, *s);
    moved(w, carId, ev);
    return MCRL_OK;
}

int32_t car_demolish(McrlWorld& w, int32_t carId) {
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, "mcrl_car_demolish", "unknown car id");
    if (car->_internalState.isDemoed) return MCRL_OK;
    car->Demolish(w.arena->GetMutatorConfig().respawnDelay);
    w.stepRays.at(carId)->floor.clear();
    return MCRL_OK;
}

int32_t car_respawn(McrlWorld& w, int32_t carId, const McrlCarState* s) {
    static const char* fn = "mcrl_car_respawn";
    if (!s) return fail(MCRL_ERR_ARG, fn, "state is NULL");
    if (!body_ok(s->body)) return fail(MCRL_ERR_ARG, fn, "non-finite value or zero quaternion");
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    McrlCarEvents& ev = w.carEvents.at(carId);
    CarState cs;
    body_to_rs(w, s->body, cs);
    cs.boost = w.unlimitedBoost ? RLConst::BOOST_MAX : w.arena->GetMutatorConfig().carSpawnBoostAmount;
    put_car_state(w, car, ev, cs);
    moved(w, carId, ev);
    return MCRL_OK;
}

int32_t car_get_state(McrlWorld& w, int32_t carId, McrlCarState* out) {
    static const char* fn = "mcrl_car_get_state";
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");

    const CarState cs = car->GetState();
    McrlCarState o{};
    rs_to_body(w, cs, o.body);
    o.boost = cs.boost;
    const McrlCarEvents& ev = w.carEvents[carId];
    o.flags = (cs.isOnGround ? MCRL_CAR_ON_GROUND : 0) | (cs.isSupersonic ? MCRL_CAR_SUPERSONIC : 0) |
              (cs.hasJumped ? MCRL_CAR_JUMPED : 0) | (cs.hasDoubleJumped ? MCRL_CAR_DOUBLE_JUMPED : 0) |
              (cs.hasFlipped ? MCRL_CAR_FLIPPED : 0) | (cs.isDemoed ? MCRL_CAR_DEMOED : 0) |
              (cs.isBoosting ? MCRL_CAR_BOOSTING : 0) | (ev.held ? MCRL_CAR_HELD : 0) |
              (ev.restored ? MCRL_CAR_RESTORED : 0);
    o.ballHits = ev.hits;
    o.ballHitSpeed = ev.hitSpeed;
    o.stepDz = ev.stepDz;

    // Not m_worldTransform: it lags a tick behind the chassis.
    const btVehicleRL& veh = car->_bulletVehicle;
    const int n = std::min(veh.getNumWheels(), 4);
    for (int i = 0; i < n; i++) {
        const btWheelInfoRL& wi = veh.m_wheelInfo[i];
        const btVector3 local = wi.m_chassisConnectionPointCS + wi.m_wheelDirectionCS * wi.m_raycastInfo.m_suspensionLength;
        coords::rsLocalToMc(local, &o.wheelLocal[3 * i]);
        if (cs.wheelsWithContact[i]) o.wheelContact |= 1u << i;
    }
    o.steerAngle = n > 0 ? -veh.m_wheelInfo[0].m_steerAngle : 0.f;
    *out = o;
    return MCRL_OK;
}

int32_t car_get_config(McrlWorld& w, int32_t carId, McrlCarConfig* out) {
    static const char* fn = "mcrl_car_get_config";
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    const CarConfig& cfg = car->config;
    const McrlCarEvents& ev = w.carEvents.at(carId);
    McrlCarConfig o{};
    local_uu_to_mc(cfg.hitboxSize, o.hitboxSize);
    local_uu_to_mc(cfg.hitboxPosOffset, o.hitboxOffset);
    const WheelPairConfig* axles[2] = {&cfg.frontWheels, &cfg.backWheels};
    for (int i = 0; i < 2; i++) {
        o.wheelRadius[i] = axles[i]->wheelRadius * UU_TO_BT;
        o.suspensionRest[i] = axles[i]->suspensionRestLength * UU_TO_BT;
        local_uu_to_mc(axles[i]->connectionPointOffset, &o.connection[3 * i]);
    }
    o.threeWheels = cfg.threeWheels ? 1 : 0;
    o.preset = ev.preset;
    o.team = ev.team;
    *out = o;
    return MCRL_OK;
}

int32_t car_set_controls(McrlWorld& w, int32_t carId, const McrlControls* c) {
    static const char* fn = "mcrl_car_set_controls";
    if (!c) return fail(MCRL_ERR_ARG, fn, "controls is NULL");
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    CarControls cc;
    cc.throttle = axis(c->throttle);
    cc.steer = axis(c->steer);
    cc.pitch = axis(c->pitch);
    cc.yaw = axis(c->yaw);
    cc.roll = axis(c->roll);
    cc.jump = c->jump != 0;
    cc.boost = c->boost != 0;
    cc.handbrake = c->handbrake != 0;
    car->controls = cc;
    return MCRL_OK;
}

int32_t ball_set_state(McrlWorld& w, const McrlBallState* s) {
    static const char* fn = "mcrl_ball_set_state";
    if (!s) return fail(MCRL_ERR_ARG, fn, "state is NULL");
    if (!body_ok(s->body)) return fail(MCRL_ERR_ARG, fn, "non-finite value or zero quaternion");
    if (w.soccar) soccar::put_ball_in(w);
    Ball* ball = w.arena->ball;
    BallState bs = ball->GetState();
    body_to_rs(w, s->body, bs);
    wake_at_rest(bs);
    ball->SetState(bs);
    w.ballLastSet = {bs, w.arena->tickCount, w.ox, w.oy, w.oz};
    w.ballParked = false;
    w.ballFxStale = true;
    return MCRL_OK;
}

int32_t ball_get_state(McrlWorld& w, McrlBallState* out) {
    if (!out) return fail(MCRL_ERR_ARG, "mcrl_ball_get_state", "out is NULL");
    McrlBallState o{};
    rs_to_body(w, w.arena->ball->GetState(), o.body);
    o.bounces = w.ballBounces;
    o.bounceSpeed = w.ballBounceSpeed;
    *out = o;
    return MCRL_OK;
}

int32_t car_get_fx(McrlWorld& w, int32_t carId, McrlCarFx* out) {
    static const char* fn = "mcrl_car_get_fx";
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    auto it = w.carEvents.find(carId);
    if (it == w.carEvents.end()) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    *out = it->second.fx;
    return MCRL_OK;
}

int32_t ball_get_fx(McrlWorld& w, McrlBallFx* out) {
    if (!out) return fail(MCRL_ERR_ARG, "mcrl_ball_get_fx", "out is NULL");
    *out = w.ballFx;
    return MCRL_OK;
}

int32_t ball_clear_touch(McrlWorld& w) {
    w.ballFx.lastTouchTeam = -1;
    w.lastToucher = -1;
    return MCRL_OK;
}

int32_t car_add_velocity(McrlWorld& w, int32_t carId, double dvx, double dvy, double dvz) {
    static const char* fn = "mcrl_car_add_velocity";
    if (!finite(dvx) || !finite(dvy) || !finite(dvz)) return fail(MCRL_ERR_ARG, fn, "non-finite value");
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    btRigidBody& rb = car->_rigidBody;
    rb.setLinearVelocity(rb.getLinearVelocity() + mc_vel_bt(dvx, dvy, dvz));
    rb.activate(true);
    return MCRL_OK;
}

int32_t ball_add_velocity(McrlWorld& w, double dvx, double dvy, double dvz) {
    if (!finite(dvx) || !finite(dvy) || !finite(dvz))
        return fail(MCRL_ERR_ARG, "mcrl_ball_add_velocity", "non-finite value");
    if (soccar::ball_out(w)) return MCRL_OK;
    const btVector3 dv = mc_vel_bt(dvx, dvy, dvz);
    if (w.ballParked) {
        w.parkedVel += dv;
        return MCRL_OK;
    }
    btRigidBody& rb = w.arena->ball->_rigidBody;
    rb.setLinearVelocity(rb.getLinearVelocity() + dv);
    rb.activate(true);
    return MCRL_OK;
}

int32_t step(McrlWorld& w, int32_t ticks, uint32_t* outFlags) {
    if (outFlags) *outFlags = 0;
    if (ticks < 0) return fail(MCRL_ERR_ARG, "mcrl_step", "ticks < 0");
    materials::refresh(w);
    materials::install_callback();
    int32_t n = 0;
    for (; n < ticks; n++) {
        if (w.spawnGate && !w.perCarGate && !cars_ready(w)) {
            if (outFlags) *outFlags |= MCRL_STEP_FROZEN;
            break;
        }
        if (n == 0)
            for (auto& [id, ev] : w.carEvents) ev.restored = false;
        if (begin_tick(w) && outFlags) *outFlags |= MCRL_STEP_FROZEN;
        if (w.spawnGate) update_ball_parking(w);
        bot::before_tick(w);
        if (w.soccar) step_tick(w);
        else assisted_tick(w);
        const btRigidBody& rb = w.arena->ball->_rigidBody;
        if (w.ballParked && (!rb.getLinearVelocity().isZero() || !rb.getAngularVelocity().isZero()))
            w.ballParked = false;
        const uint32_t restored = guard::after_tick(w);
        if (outFlags) *outFlags |= restored;
        blob::record_events(w);
    }
    if (outFlags && w.ballParked) *outFlags |= MCRL_STEP_BALL_PARKED;
    return n;
}

int32_t set_spawn_gate(McrlWorld& w, int32_t enabled) {
    if (enabled != 0 && w.soccar) return fail(MCRL_ERR_ARG, "mcrl_world_set_spawn_gate", NO_TERRAIN);
    w.spawnGate = enabled != 0;
    if (!w.spawnGate && w.ballParked) unpark_ball(w);
    return MCRL_OK;
}

int32_t set_gate_mode(McrlWorld& w, int32_t perCar) {
    w.perCarGate = perCar != 0;
    return MCRL_OK;
}

int32_t set_step_up(McrlWorld& w, int32_t enabled) {
    w.stepUp = enabled != 0;
    return MCRL_OK;
}

int32_t set_demos(McrlWorld& w, int32_t mode, int32_t teamDemos) {
    if (mode < 0 || mode > 2) return fail(MCRL_ERR_ARG, "mcrl_world_set_demos", "mode must be 0..2");
    MutatorConfig mut = w.arena->GetMutatorConfig();
    mut.demoMode = DEMO_MODES[mode];
    mut.enableTeamDemos = teamDemos != 0;
    w.arena->SetMutatorConfig(mut);
    return MCRL_OK;
}

int32_t set_extras(McrlWorld& w, uint32_t flags) {
    if (flags != 0 && w.soccar) return fail(MCRL_ERR_ARG, "mcrl_world_set_extras", NO_TERRAIN);
    w.extras = flags;
    if (flags & MCRL_EXTRAS_FLUIDS) return MCRL_OK;
    for (auto& [id, ev] : w.carEvents) {
        ev.fx.wet.immersion = 0;
        ev.fx.wet.fluid = 0;
        ev.wet = false;
    }
    w.ballFx.wet.immersion = 0;
    w.ballFx.wet.fluid = 0;
    w.ballWet = false;
    return MCRL_OK;
}

int32_t world_shift_origin(McrlWorld& w, int32_t ox, int32_t oy, int32_t oz) {
    if (w.soccar) return fail(MCRL_ERR_ARG, "mcrl_world_shift_origin", "a SOCCAR world's origin is fixed");
    if (ox % 16 != 0 || oy % 16 != 0 || oz % 16 != 0)
        return fail(MCRL_ERR_ARG, "mcrl_world_shift_origin", "each origin component must be a multiple of 16");
    if (ox == w.ox && oy == w.oy && oz == w.oz) return MCRL_OK;
    const double d[3] = {(double)((int64_t)ox - w.ox), (double)((int64_t)oz - w.oz), (double)((int64_t)oy - w.oy)};
    w.terrain->Shift(ox, oy, oz);
    btDiscreteDynamicsWorld& bw = w.arena->_bulletWorld;
    const btVector3 dv((btScalar)-d[0], (btScalar)-d[1], (btScalar)-d[2]);
    for (Car* car : w.arena->_cars) {
        shift_body(bw, car->_rigidBody, d);
        stepassist::moveWheelCache(*car, dv);
        BallHitInfo& hit = car->_internalState.ballHitInfo;
        if (hit.isValid) {
            const double du[3] = {d[0] * BT_TO_UU, d[1] * BT_TO_UU, d[2] * BT_TO_UU};
            hit.ballPos = minus(hit.ballPos, du);
        }
    }
    shift_body(bw, w.arena->ball->_rigidBody, d);
    for (auto& [id, ray] : w.stepRays) ray->floor.shift(d);
    w.ox = ox;
    w.oy = oy;
    w.oz = oz;
    return MCRL_OK;
}

int32_t section_commit(McrlWorld& w, std::unique_ptr<McrlBuilt> b) {
    if (w.soccar) return fail(MCRL_ERR_ARG, "mcrl_section_commit", NO_TERRAIN);
    SavedMotionOnBall m(w);
    return w.terrain->Commit(std::move(b));
}

int32_t section_remove(McrlWorld& w, int32_t sx, int32_t sy, int32_t sz) {
    if (w.soccar) return fail(MCRL_ERR_ARG, "mcrl_section_remove", NO_TERRAIN);
    SavedMotionOnBall m(w);
    w.terrain->Remove({sx, sy, sz});
    return MCRL_OK;
}

int32_t section_set_fluid(McrlWorld& w, int32_t sx, int32_t sy, int32_t sz, const uint8_t* cells) {
    static const char* fn = "mcrl_section_set_fluid";
    if (w.soccar) return fail(MCRL_ERR_ARG, fn, NO_TERRAIN);
    if (cells && !fluids::valid_plane(cells)) return fail(MCRL_ERR_ARG, fn, "a fluid byte with a height outside 1..9");
    if (!w.terrain->SetFluid({sx, sy, sz}, cells)) return fail(MCRL_ERR_NOT_FOUND, fn, "section not committed");
    return MCRL_OK;
}

#ifdef MCRL_TEST_HOOKS
int32_t set_unlimited_boost(McrlWorld& w, int32_t on) {
    w.unlimitedBoost = on != 0;
    MutatorConfig mut = w.arena->GetMutatorConfig();
    set_boost_mutators(mut, w.unlimitedBoost);
    w.arena->SetMutatorConfig(mut);
    if (w.unlimitedBoost)
        for (Car* car : w.arena->_cars) car->_internalState.boost = RLConst::BOOST_MAX;
    return MCRL_OK;
}

int32_t world_get_origin(McrlWorld& w, int32_t* out3) {
    if (!out3) return fail(MCRL_ERR_ARG, "mcrl_world_get_origin", "out is NULL");
    out3[0] = w.ox;
    out3[1] = w.oy;
    out3[2] = w.oz;
    return MCRL_OK;
}

int32_t section_is_ready(McrlWorld& w, int32_t sx, int32_t sy, int32_t sz) {
    if (w.soccar) return fail(MCRL_ERR_ARG, "mcrl_section_is_ready", NO_TERRAIN);
    return w.terrain->IsReady({sx, sy, sz}) ? 1 : 0;
}
#endif

}
