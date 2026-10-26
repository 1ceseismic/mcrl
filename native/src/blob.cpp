#include "blob.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <initializer_list>
#include <utility>

#include "guard.h"
#include "soccar.h"
#include "stepassist.h"
#include "world.h"

using namespace RocketSim;

namespace mcrl::blob {
namespace {

int32_t delta(uint64_t t, uint64_t now, uint64_t none) {
    if (t == none) return NO_TICK;
    const int64_t d = (int64_t)(t - now);
    return (int32_t)std::clamp<int64_t>(d, (int64_t)NO_TICK + 1, std::numeric_limits<int32_t>::max());
}

uint64_t tick_of(int32_t d, uint64_t now, uint64_t none) { return d == NO_TICK ? none : now + (uint64_t)(int64_t)d; }

void put3(float out[3], const btVector3& v) {
    out[0] = v.x();
    out[1] = v.y();
    out[2] = v.z();
}

void put3(float out[3], const Vec& v) {
    out[0] = v.x;
    out[1] = v.y;
    out[2] = v.z;
}

btVector3 bt3(const float v[3]) { return btVector3(v[0], v[1], v[2]); }
Vec vec3(const float v[3]) { return Vec(v[0], v[1], v[2]); }

void put_body(const btRigidBody& rb, float origin[3], float basis[9], float linVel[3], float angVel[3]) {
    const btTransform& t = rb.getWorldTransform();
    put3(origin, t.getOrigin());
    const btMatrix3x3& m = t.getBasis();
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++) basis[3 * r + c] = m[r][c];
    put3(linVel, rb.getLinearVelocity());
    put3(angVel, rb.getAngularVelocity());
}

btTransform transform(const float origin[3], const float basis[9]) {
    btMatrix3x3 m(basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], basis[6], basis[7], basis[8]);
    return btTransform(m, bt3(origin));
}

bool all_finite(const float* v, size_t n) {
    for (size_t i = 0; i < n; i++)
        if (!std::isfinite(v[i])) return false;
    return true;
}

bool all_finite(std::initializer_list<std::pair<const float*, size_t>> fields) {
    for (const auto& [v, n] : fields)
        if (!all_finite(v, n)) return false;
    return true;
}

bool rotation(const float b[9]) {
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) {
            double d = 0;
            for (int k = 0; k < 3; k++) d += (double)b[3 * k + i] * b[3 * k + j];
            if (std::abs(d - (i == j ? 1.0 : 0.0)) > 1e-3) return false;
        }
    const double det = (double)b[0] * ((double)b[4] * b[8] - (double)b[5] * b[7]) -
                       (double)b[1] * ((double)b[3] * b[8] - (double)b[5] * b[6]) +
                       (double)b[2] * ((double)b[3] * b[7] - (double)b[4] * b[6]);
    return det > 0;
}

bool body_in_range(const float origin[3], const float linVel[3], const float angVel[3]) {
    for (int i = 0; i < 3; i++)
        if (std::abs(origin[i]) > 1e6f || std::abs(linVel[i]) > 1e4f || std::abs(angVel[i]) > 1e4f) return false;
    return true;
}

const char* car_blob_invalid(const CarBlob& b) {
    if (!all_finite({{b.origin, 3}, {b.basis, 9}, {b.linVel, 3}, {b.angVel, 3}, {b.flipRelTorque, 3},
                     {&b.jumpTime, 1}, {&b.flipTime, 1}, {&b.airTime, 1}, {&b.airTimeSinceJump, 1}, {&b.boost, 1},
                     {&b.timeSinceBoosted, 1}, {&b.boostingTime, 1}, {&b.supersonicTime, 1}, {&b.handbrakeVal, 1},
                     {&b.autoFlipTimer, 1}, {&b.autoFlipTorqueScale, 1}, {b.worldContactNormal, 3},
                     {&b.cooldownTimer, 1}, {&b.demoRespawnTimer, 1}, {b.lastControls, 5}, {&b.steerAngle, 1},
                     {&b.engineForce, 1}, {&b.brake, 1}, {b.suspensionLength, 4}, {b.latFriction, 4},
                     {b.longFriction, 4}, {b.extraPushback, 4}, {b.rayContactZ, 4}, {&b.floorZ, 1}, {b.floorRect, 4},
                     {b.riserP, 2}, {&b.stepDz, 1}}))
        return "a non-finite value";
    if (!rotation(b.basis)) return "a basis that is no rotation";
    if (!body_in_range(b.origin, b.linVel, b.angVel)) return "a body out of range";
    if (b.riserAxis > RISER_NEG_Y) return "an unknown riser axis";
    if ((b.bits & IS_DEMOED) && b.demoRespawnTimer < 1) return "a demoed car whose respawn timer is under a second";
    return nullptr;
}

const char* ball_blob_invalid(const BallBlob& b) {
    if (!all_finite({{b.origin, 3}, {b.basis, 9}, {b.linVel, 3}, {b.angVel, 3}, {b.parkedVel, 3}, {b.parkedAngVel, 3}}))
        return "a non-finite value";
    if (!rotation(b.basis)) return "a basis that is no rotation";
    if (!body_in_range(b.origin, b.linVel, b.angVel) || !body_in_range(b.origin, b.parkedVel, b.parkedAngVel))
        return "a body out of range";
    if (b.parked > 1) return "an unknown parked flag";
    return nullptr;
}

uint64_t rebased(uint64_t t, int64_t by, uint64_t none, uint64_t floor) {
    if (t == none) return t;
    const int64_t r = (int64_t)t + by;
    return r < (int64_t)floor ? floor : (uint64_t)r;
}

void put_rigid_body(btDiscreteDynamicsWorld& bw, btRigidBody& rb, const btTransform& t, const btVector3& v,
                    const btVector3& av) {
    rb.setWorldTransform(t);
    rb.setInterpolationWorldTransform(t);
    rb.setLinearVelocity(v);
    rb.setAngularVelocity(av);
    rb.setInterpolationLinearVelocity(v);
    rb.setInterpolationAngularVelocity(av);
    rb.updateInertiaTensor();
    bw.updateSingleAabb(&rb);
    guard::drop_contacts(bw, rb);
}

uint8_t riser_axis(const VirtualFloor& f) {
    if (f.riserN[0] > 0) return RISER_POS_X;
    if (f.riserN[0] < 0) return RISER_NEG_X;
    if (f.riserN[1] > 0) return RISER_POS_Y;
    if (f.riserN[1] < 0) return RISER_NEG_Y;
    return RISER_NONE;
}

void riser_normal(uint8_t axis, double n[3]) {
    n[0] = axis == RISER_POS_X ? 1 : axis == RISER_NEG_X ? -1 : 0;
    n[1] = axis == RISER_POS_Y ? 1 : axis == RISER_NEG_Y ? -1 : 0;
    n[2] = 0;
}

}

int32_t car_save(McrlWorld& w, int32_t carId, uint8_t* out) {
    static const char* fn = "mcrl_car_save";
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    const McrlCarEvents& ev = w.carEvents.at(carId);
    const VirtualFloor& floor = w.stepRays.at(carId)->floor;
    const uint64_t now = w.arena->tickCount;
    const CarState& s = car->_internalState;

    CarBlob b{};
    b.version = MCRL_BLOB_VERSION;
    b.preset = (uint8_t)ev.preset;
    b.carFlags = car->config.threeWheels ? THREE_WHEELS : 0;
    put_body(car->_rigidBody, b.origin, b.basis, b.linVel, b.angVel);

    uint32_t bits = 0;
    auto set = [&bits](bool on, uint32_t bit) {
        if (on) bits |= bit;
    };
    set(s.isOnGround, ON_GROUND);
    for (int i = 0; i < 4; i++) set(s.wheelsWithContact[i], WHEEL0 << i);
    set(s.hasJumped, HAS_JUMPED);
    set(s.hasDoubleJumped, HAS_DOUBLE_JUMPED);
    set(s.hasFlipped, HAS_FLIPPED);
    set(s.isFlipping, IS_FLIPPING);
    set(s.isJumping, IS_JUMPING);
    set(s.isBoosting, IS_BOOSTING);
    set(s.isSupersonic, IS_SUPERSONIC);
    set(s.isAutoFlipping, IS_AUTO_FLIPPING);
    set(s.worldContact.hasContact, WORLD_CONTACT);
    set(s.isDemoed, IS_DEMOED);
    set(s.ballHitInfo.isValid, BALL_HIT_VALID);
    set(s.lastControls.jump, LAST_JUMP);
    set(s.lastControls.boost, LAST_BOOST);
    set(s.lastControls.handbrake, LAST_HANDBRAKE);

    put3(b.flipRelTorque, s.flipRelTorque);
    b.jumpTime = s.jumpTime;
    b.flipTime = s.flipTime;
    b.airTime = s.airTime;
    b.airTimeSinceJump = s.airTimeSinceJump;
    b.boost = s.boost;
    b.timeSinceBoosted = s.timeSinceBoosted;
    b.boostingTime = s.boostingTime;
    b.supersonicTime = s.supersonicTime;
    b.handbrakeVal = s.handbrakeVal;
    b.autoFlipTimer = s.autoFlipTimer;
    b.autoFlipTorqueScale = s.autoFlipTorqueScale;
    put3(b.worldContactNormal, s.worldContact.contactNormal);
    const auto other = w.rsIds.find(s.carContact.otherCarID);
    b.otherCarId = other == w.rsIds.end() ? -1 : other->second;
    b.cooldownTimer = s.carContact.cooldownTimer;
    b.demoRespawnTimer = s.demoRespawnTimer;
    b.hitTick = delta(s.ballHitInfo.tickCountWhenHit, now, ~0ULL);
    b.extraImpulseTick = delta(s.ballHitInfo.tickCountWhenExtraImpulseApplied, now, ~0ULL);
    const CarControls& lc = s.lastControls;
    b.lastControls[0] = lc.throttle;
    b.lastControls[1] = lc.steer;
    b.lastControls[2] = lc.pitch;
    b.lastControls[3] = lc.yaw;
    b.lastControls[4] = lc.roll;

    const btVehicleRL& veh = car->_bulletVehicle;
    const int n = std::min(veh.getNumWheels(), 4);
    if (n > 0) {
        b.steerAngle = veh.m_wheelInfo[0].m_steerAngle;
        b.engineForce = veh.m_wheelInfo[0].m_engineForce;
        b.brake = veh.m_wheelInfo[0].m_brake;
    }
    for (int i = 0; i < n; i++) {
        const btWheelInfoRL& wi = veh.m_wheelInfo[i];
        const btWheelInfo::RaycastInfo& r = wi.m_raycastInfo;
        b.suspensionLength[i] = r.m_suspensionLength;
        b.latFriction[i] = wi.m_latFriction;
        b.longFriction[i] = wi.m_longFriction;
        b.extraPushback[i] = wi.m_extraPushback;
        b.rayContactZ[i] = r.m_contactPointWS.z();
        set(r.m_isInContact, RAY_CONTACT0 << i);
        set(r.m_contactNormalWS.z() >= stepassist::FLOOR_NORMAL_Z, RAY_FLOOR0 << i);
    }

    set(floor.active, FLOOR_ACTIVE);
    set(floor.used, FLOOR_USED);
    set(ev.wheelsStale, WHEELS_STALE);
    b.riserAxis = riser_axis(floor);
    b.floorIdle = (int8_t)std::clamp(floor.idle, -128, 127);
    b.floorZ = (float)floor.zBT;
    for (int i = 0; i < 4; i++) b.floorRect[i] = (float)floor.rect[i];
    b.riserP[0] = (float)floor.riserP[0];
    b.riserP[1] = (float)floor.riserP[1];
    b.stepDz = ev.stepDz;
    b.doubleJumpTicks = (int8_t)std::clamp(ev.doubleJumpTicks, -128, 127);
    b.worldHitTick = delta(ev.worldHitTick, now, 0);
    b.bits = bits;
    std::memcpy(out, &b, sizeof b);
    return MCRL_OK;
}

int32_t car_load(McrlWorld& w, int32_t carId, const uint8_t* in) {
    static const char* fn = "mcrl_car_load";
    if (!in) return fail(MCRL_ERR_ARG, fn, "in is NULL");
    CarBlob b;
    std::memcpy(&b, in, sizeof b);
    if (b.version != MCRL_BLOB_VERSION) return fail(MCRL_ERR_ARG, fn, "not a blob of this version");
    if (const char* why = car_blob_invalid(b)) return fail(MCRL_ERR_ARG, fn, why);
    Car* car = find_car(w, carId);
    if (!car) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    McrlCarEvents& ev = w.carEvents.at(carId);
    if (b.preset != ev.preset || ((b.carFlags & THREE_WHEELS) != 0) != car->config.threeWheels)
        return fail(MCRL_ERR_ARG, fn, "a blob of another hitbox preset");
    VirtualFloor& floor = w.stepRays.at(carId)->floor;
    const uint64_t now = w.arena->tickCount;
    const uint32_t bits = b.bits;
    auto on = [bits](uint32_t bit) { return (bits & bit) != 0; };

    const btTransform t = transform(b.origin, b.basis);
    const btVector3 v = bt3(b.linVel), av = bt3(b.angVel);
    CarState s = car->_internalState;
    s.pos = t.getOrigin() * BT_TO_UU;
    s.rotMat = RotMat(t.getBasis());
    s.vel = v * BT_TO_UU;
    s.angVel = av;
    s.isOnGround = on(ON_GROUND);
    for (int i = 0; i < 4; i++) s.wheelsWithContact[i] = on(WHEEL0 << i);
    s.hasJumped = on(HAS_JUMPED);
    s.hasDoubleJumped = on(HAS_DOUBLE_JUMPED);
    s.hasFlipped = on(HAS_FLIPPED);
    s.isFlipping = on(IS_FLIPPING);
    s.isJumping = on(IS_JUMPING);
    s.isBoosting = on(IS_BOOSTING);
    s.isSupersonic = on(IS_SUPERSONIC);
    s.isAutoFlipping = on(IS_AUTO_FLIPPING);
    s.worldContact.hasContact = on(WORLD_CONTACT);
    s.isDemoed = on(IS_DEMOED);
    s.ballHitInfo.isValid = on(BALL_HIT_VALID);
    s.flipRelTorque = vec3(b.flipRelTorque);
    s.jumpTime = b.jumpTime;
    s.flipTime = b.flipTime;
    s.airTime = b.airTime;
    s.airTimeSinceJump = b.airTimeSinceJump;
    s.boost = b.boost;
    s.timeSinceBoosted = b.timeSinceBoosted;
    s.boostingTime = b.boostingTime;
    s.supersonicTime = b.supersonicTime;
    s.handbrakeVal = b.handbrakeVal;
    s.autoFlipTimer = b.autoFlipTimer;
    s.autoFlipTorqueScale = b.autoFlipTorqueScale;
    s.worldContact.contactNormal = vec3(b.worldContactNormal);
    Car* other = b.otherCarId >= 0 ? find_car(w, b.otherCarId) : nullptr;
    s.carContact.otherCarID = other ? other->id : 0;  // RocketSim car ids start at 1
    s.carContact.cooldownTimer = b.cooldownTimer;
    s.demoRespawnTimer = b.demoRespawnTimer;
    s.ballHitInfo.tickCountWhenHit = tick_of(b.hitTick, now, ~0ULL);
    s.ballHitInfo.tickCountWhenExtraImpulseApplied = tick_of(b.extraImpulseTick, now, ~0ULL);
    CarControls lc;
    lc.throttle = b.lastControls[0];
    lc.steer = b.lastControls[1];
    lc.pitch = b.lastControls[2];
    lc.yaw = b.lastControls[3];
    lc.roll = b.lastControls[4];
    lc.jump = on(LAST_JUMP);
    lc.boost = on(LAST_BOOST);
    lc.handbrake = on(LAST_HANDBRAKE);
    s.lastControls = lc;
    car->SetState(s);
    // After SetState, so the body skips its UU rounding.
    put_rigid_body(w.arena->_bulletWorld, car->_rigidBody, t, v, av);

    btVehicleRL& veh = car->_bulletVehicle;
    const int n = std::min(veh.getNumWheels(), 4);
    for (int i = 0; i < n; i++) {
        btWheelInfoRL& wi = veh.m_wheelInfo[i];
        btWheelInfo::RaycastInfo& r = wi.m_raycastInfo;
        if (i < 2) wi.m_steerAngle = b.steerAngle;
        wi.m_engineForce = b.engineForce;
        wi.m_brake = b.brake;
        r.m_suspensionLength = b.suspensionLength[i];
        wi.m_latFriction = b.latFriction[i];
        wi.m_longFriction = b.longFriction[i];
        wi.m_extraPushback = b.extraPushback[i];
        r.m_isInContact = on(RAY_CONTACT0 << i);
        r.m_contactNormalWS = btVector3(0, 0, on(RAY_FLOOR0 << i) ? 1 : 0);
        r.m_contactPointWS.setZ(b.rayContactZ[i]);
    }

    floor.clear();
    floor.active = on(FLOOR_ACTIVE);
    floor.used = on(FLOOR_USED);
    floor.idle = b.floorIdle;
    floor.zBT = b.floorZ;
    for (int i = 0; i < 4; i++) floor.rect[i] = b.floorRect[i];
    floor.riserP[0] = b.riserP[0];
    floor.riserP[1] = b.riserP[1];
    floor.riserP[2] = b.floorZ;
    riser_normal(b.riserAxis, floor.riserN);

    ev.stepDz = b.stepDz;
    ev.wheelsStale = on(WHEELS_STALE);
    ev.doubleJumpTicks = b.doubleJumpTicks;
    ev.worldHitTick = tick_of(b.worldHitTick, now, 0);
    ev.lastSet = {car->GetState(), now, w.ox, w.oy, w.oz};
    ev.fxStale = false;
    return MCRL_OK;
}

int32_t ball_save(McrlWorld& w, uint8_t* out) {
    if (!out) return fail(MCRL_ERR_ARG, "mcrl_ball_save", "out is NULL");
    BallBlob b{};
    b.version = MCRL_BLOB_VERSION;
    b.parked = w.ballParked ? 1 : 0;
    b.bounceTick = delta(w.bounceTick, w.arena->tickCount, 0);
    put_body(w.arena->ball->_rigidBody, b.origin, b.basis, b.linVel, b.angVel);
    put3(b.parkedVel, w.parkedVel);
    put3(b.parkedAngVel, w.parkedAngVel);
    std::memcpy(out, &b, sizeof b);
    return MCRL_OK;
}

int32_t ball_load(McrlWorld& w, const uint8_t* in) {
    static const char* fn = "mcrl_ball_load";
    if (!in) return fail(MCRL_ERR_ARG, fn, "in is NULL");
    BallBlob b;
    std::memcpy(&b, in, sizeof b);
    if (b.version != MCRL_BLOB_VERSION) return fail(MCRL_ERR_ARG, fn, "not a blob of this version");
    if (const char* why = ball_blob_invalid(b)) return fail(MCRL_ERR_ARG, fn, why);
    Ball* ball = w.arena->ball;
    const btTransform t = transform(b.origin, b.basis);
    const btVector3 v = bt3(b.linVel), av = bt3(b.angVel);
    BallState s = ball->_internalState;
    s.pos = t.getOrigin() * BT_TO_UU;
    s.rotMat = RotMat(t.getBasis());
    s.vel = v * BT_TO_UU;
    s.angVel = av;
    ball->SetState(s);
    if (soccar::ball_out(w)) {
        btRigidBody& rb = ball->_rigidBody;
        rb.setWorldTransform(t);
        rb.setInterpolationWorldTransform(t);
        rb.setLinearVelocity(v);
        rb.setAngularVelocity(av);
        rb.setInterpolationLinearVelocity(v);
        rb.setInterpolationAngularVelocity(av);
    } else {
        put_rigid_body(w.arena->_bulletWorld, ball->_rigidBody, t, v, av);
    }
    w.ballParked = b.parked != 0;
    w.parkedVel = bt3(b.parkedVel);
    w.parkedAngVel = bt3(b.parkedAngVel);
    w.bounceTick = tick_of(b.bounceTick, w.arena->tickCount, 0);
    w.ballLastSet = {ball->GetState(), w.arena->tickCount, w.ox, w.oy, w.oz};
    w.ballFxStale = false;
    return MCRL_OK;
}

int32_t world_set_tick(McrlWorld& w, uint64_t tick) {
    const int64_t by = (int64_t)(tick - w.arena->tickCount);
    w.arena->tickCount = tick;
    for (auto& [id, car] : w.cars) {
        BallHitInfo& h = car->_internalState.ballHitInfo;
        h.tickCountWhenHit = rebased(h.tickCountWhenHit, by, ~0ULL, 0);
        h.tickCountWhenExtraImpulseApplied = rebased(h.tickCountWhenExtraImpulseApplied, by, ~0ULL, 0);
    }
    for (auto& [id, ev] : w.carEvents) ev.worldHitTick = rebased(ev.worldHitTick, by, 0, 1);
    w.bounceTick = rebased(w.bounceTick, by, 0, 1);
    w.guard.count = 0;
    for (auto& [id, ev] : w.carEvents) ev.lastSet.tick = std::min(ev.lastSet.tick, tick);
    w.ballLastSet.tick = std::min(w.ballLastSet.tick, tick);
    return MCRL_OK;
}

namespace {

const char* soccar_blob_invalid(const SoccarBlob& b) {
    if (b.ballOutOfPlay > 1) return "an unknown out-of-play code";
    if (b.lockTicks < 0) return "a negative kickoff lock";
    if (b.goalTeam < -1 || b.goalTeam > 1) return "an unknown goal team";
    if (!all_finite({{b.goalVel, 3}, {b.padCooldown, (size_t)soccar::PAD_COUNT}})) return "a non-finite float";
    for (double v : b.goalPos)
        if (!std::isfinite(v)) return "a non-finite goal position";
    for (float c : b.padCooldown)
        if (c < 0) return "a negative pad cooldown";
    if (b.padActive >> soccar::PAD_COUNT) return "an unknown pad";
    return nullptr;
}

}

int32_t soccar_save(McrlWorld& w, uint8_t* out) {
    static const char* fn = "mcrl_soccar_save";
    if (!w.soccar) return fail(MCRL_ERR_ARG, fn, "not a SOCCAR world");
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    const soccar::Soccar& s = *w.soccar;
    SoccarBlob b{};
    b.version = MCRL_BLOB_VERSION;
    b.ballOutOfPlay = s.ballOutOfPlay ? 1 : 0;
    b.lockTicks = s.lockTicks;
    b.kickoffs = s.kickoffs;
    b.goals = s.goals;
    b.goalTeam = s.goalTeam;
    b.goalToucher = s.goalToucher;
    b.lastToucher = w.lastToucher;
    for (int i = 0; i < 3; i++) {
        b.goalVel[i] = s.goalVel[i];
        b.goalPos[i] = s.goalPos[i];
    }
    const std::vector<BoostPad*>& pads = w.arena->GetBoostPads();
    for (int i = 0; i < soccar::PAD_COUNT; i++) {
        const BoostPadState& p = pads[i]->_internalState;
        if (p.isActive) b.padActive |= 1ull << i;
        b.padCooldown[i] = p.cooldown;
        const auto id = w.rsIds.find(p.prevLockedCarID);
        b.padPrevCar[i] = id == w.rsIds.end() ? -1 : id->second;
        b.padPickups[i] = s.padPickups[i];
        b.padCar[i] = s.padCar[i];
    }
    std::memcpy(out, &b, sizeof b);
    return MCRL_OK;
}

int32_t soccar_load(McrlWorld& w, const uint8_t* in) {
    static const char* fn = "mcrl_soccar_load";
    if (!w.soccar) return fail(MCRL_ERR_ARG, fn, "not a SOCCAR world");
    if (!in) return fail(MCRL_ERR_ARG, fn, "in is NULL");
    SoccarBlob b;
    std::memcpy(&b, in, sizeof b);
    if (b.version != MCRL_BLOB_VERSION) return fail(MCRL_ERR_ARG, fn, "not a blob of this version");
    if (const char* why = soccar_blob_invalid(b)) return fail(MCRL_ERR_ARG, fn, why);
    soccar::Soccar& s = *w.soccar;
    if (b.ballOutOfPlay) {
        if (!s.ballOutOfPlay) soccar::take_ball_out(w);
    } else {
        soccar::put_ball_in(w);
    }
    s.lockTicks = b.lockTicks;
    s.kickoffs = b.kickoffs;
    s.goals = b.goals;
    s.goalTeam = b.goalTeam;
    s.goalToucher = b.goalToucher;
    w.lastToucher = b.lastToucher;
    for (int i = 0; i < 3; i++) {
        s.goalVel[i] = b.goalVel[i];
        s.goalPos[i] = b.goalPos[i];
    }
    const std::vector<BoostPad*>& pads = w.arena->GetBoostPads();
    for (int i = 0; i < soccar::PAD_COUNT; i++) {
        BoostPadState p{};
        p.isActive = (b.padActive >> i) & 1;
        p.cooldown = b.padCooldown[i];
        Car* car = find_car(w, b.padPrevCar[i]);
        p.prevLockedCarID = car ? car->id : 0;
        p.curLockedCar = nullptr;
        pads[i]->SetState(p);
        s.padPickups[i] = b.padPickups[i];
        s.padCar[i] = b.padCar[i];
    }
    return MCRL_OK;
}

void reserve(EventRing& r, size_t nCars) {
    for (EventEntry& e : r.slots) e.cars.reserve(nCars);
}

void record_events(McrlWorld& w) {
    const uint64_t tick = w.arena->tickCount;
    EventEntry& e = w.events.slots[tick % EVENT_TICKS];
    e.tick = tick;
    e.cars.clear();
    for (const auto& [id, ev] : w.carEvents) {
        CarCounters c{};
        for (int i = 0; i < 4; i++) c.lands[i] = ev.fx.wheels[i].lands;
        c.jumps = ev.fx.jumps;
        c.doubleJumps = ev.fx.doubleJumps;
        c.dodges = ev.fx.dodges;
        c.flipResets = ev.fx.flipResets;
        c.worldHits = ev.fx.worldHits;
        c.bumps = ev.bumps;
        c.ballHits = ev.hits;
        e.cars.push_back({id, ev.gen, c});
    }
    e.bounces = w.ballBounces;
}

int32_t rewind_events(McrlWorld& w, uint64_t tick) {
    const EventEntry& e = w.events.slots[tick % EVENT_TICKS];
    if (e.tick != tick || tick > w.arena->tickCount)
        return fail(MCRL_ERR_NOT_FOUND, "mcrl_world_rewind_events", "the ring holds no such tick");
    for (const auto& [id, gen, c] : e.cars) {
        const auto it = w.carEvents.find(id);
        if (it == w.carEvents.end() || it->second.gen != gen) continue;
        McrlCarEvents& ev = it->second;
        for (int i = 0; i < 4; i++) ev.fx.wheels[i].lands = c.lands[i];
        ev.fx.jumps = c.jumps;
        ev.fx.doubleJumps = c.doubleJumps;
        ev.fx.dodges = c.dodges;
        ev.fx.flipResets = c.flipResets;
        ev.fx.worldHits = c.worldHits;
        ev.bumps = c.bumps;
        ev.fx.bumps = c.bumps;
        ev.hits = c.ballHits;
    }
    w.ballBounces = e.bounces;
    return MCRL_OK;
}

}
