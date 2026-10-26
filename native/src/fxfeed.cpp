#include "fxfeed.h"

#include <algorithm>
#include <cmath>

#include "world.h"

using namespace RocketSim;

namespace mcrl::fxfeed {
namespace {
// m_normalWorldOnB points from body 1 to body 0.
void keep_deepest(Touch& t, const btPersistentManifold& m, int self) {
    for (int i = 0; i < m.getNumContacts(); i++) {
        const btManifoldPoint& pt = m.getContactPoint(i);
        if (t.found && !(pt.getDistance() < t.dist)) continue;
        t.found = true;
        t.dist = pt.getDistance();
        t.p = self == 0 ? pt.getPositionWorldOnB() : pt.getPositionWorldOnA();
        t.n = self == 0 ? pt.m_normalWorldOnB : -pt.m_normalWorldOnB;
        t.other = self == 0 ? m.getBody1() : m.getBody0();
        t.tri = self == 0 ? pt.m_index1 : pt.m_index0;
    }
}

void walk(McrlWorld& w, std::vector<Before>& cars, BallBefore& ball) {
    const btCollisionObject* ballBody = &w.arena->ball->_rigidBody;
    btCollisionDispatcher* d = w.arena->_bulletWorld.getDispatcher();
    for (int i = 0; i < d->getNumManifolds(); i++) {
        const btPersistentManifold& m = *d->getManifoldByIndexInternal(i);
        if (m.getNumContacts() == 0) continue;
        const btCollisionObject* body[2] = {m.getBody0(), m.getBody1()};
        for (int self = 0; self < 2; self++) {
            const btCollisionObject* other = body[1 - self];
            if (body[self] == ballBody) {
                if (other->isStaticObject()) keep_deepest(ball.world, m, self);
                continue;
            }
            for (Before& b : cars) {
                if (body[self] != &b.car->_rigidBody) continue;
                if (other->isStaticObject()) keep_deepest(b.world, m, self);
                else if (other == ballBody) keep_deepest(b.ball, m, self);
                break;
            }
        }
    }
}

struct Momentum {
    double impact, slide;
};

Momentum momentum(const Vec& relV, const btVector3& n) {
    const double impact = std::abs((double)relV.x * n.x() + (double)relV.y * n.y() + (double)relV.z * n.z());
    const double len = std::sqrt((double)relV.x * relV.x + (double)relV.y * relV.y + (double)relV.z * relV.z);
    return {impact, std::max(len - impact, 0.0)};
}

McrlHitFx hit_fx(const McrlWorld& w, const Touch& t) {
    McrlHitFx h{};
    h.px = w.ox + (double)t.p.x();
    h.py = w.oy + (double)t.p.z();
    h.pz = w.oz + (double)t.p.y();
    h.nx = t.n.x();
    h.ny = t.n.z();
    h.nz = t.n.y();
    return h;
}

bool delay_passed(uint64_t last, uint64_t tick) { return last == 0 || tick - last >= MIN_IMPACT_DELAY_TICKS; }

bool car_after(McrlWorld& w, Before& b, const BallBefore& ball, uint64_t tick) {
    McrlCarEvents& ev = *b.ev;
    McrlCarFx& fx = ev.fx;
    Car& car = *b.car;
    const CarState& B = b.state;
    const CarState A = car.GetState();
    const bool count = !ev.fxStale;

    const btVehicleRL& veh = car._bulletVehicle;
    const int n = std::min(veh.getNumWheels(), 4);
    int onWorld = 0;
    for (int i = 0; i < n; i++) {
        const btWheelInfoRL& wi = veh.m_wheelInfo[i];
        const btVector3& p = wi.m_raycastInfo.m_contactPointWS;
        const btVector3& nrm = wi.m_raycastInfo.m_contactNormalWS;
        McrlWheelFx& wf = fx.wheels[i];
        wf.px = w.ox + (double)p.x();
        wf.py = w.oy + (double)p.z();
        wf.pz = w.oz + (double)p.y();
        wf.nx = nrm.x();
        wf.ny = nrm.z();
        wf.nz = nrm.y();
        wf.contact = (A.wheelsWithContact[i] ? MCRL_WHEEL_CONTACT : 0u) |
                     (wi.m_isInContactWithWorld ? MCRL_WHEEL_WORLD : 0u);
        onWorld += wi.m_isInContactWithWorld ? 1 : 0;
        if (count && !B.wheelsWithContact[i] && A.wheelsWithContact[i]) {
            const double r[3] = {p.x() * (double)BT_TO_UU - B.pos.x, p.y() * (double)BT_TO_UU - B.pos.y,
                                 p.z() * (double)BT_TO_UU - B.pos.z};
            const Vec& v = B.vel;
            const Vec& om = B.angVel;
            const double vp[3] = {v.x + om.y * r[2] - om.z * r[1], v.y + om.z * r[0] - om.x * r[2],
                                  v.z + om.x * r[1] - om.y * r[0]};
            const double s = std::abs(vp[0] * nrm.x() + vp[1] * nrm.y() + vp[2] * nrm.z());
            if (s > MIN_IMPACT_UU) {
                wf.lands++;
                wf.landSpeed = (float)s;
            }
        }
    }

    fx.handbrake = A.handbrakeVal;
    fx.bumps = ev.bumps;
    fx.bumpedBy = ev.bumpedBy;
    fx.bumpSpeed = ev.bumpSpeed;
    if (count) {
        if (!B.isJumping && A.isJumping) fx.jumps++;
        if (!B.hasDoubleJumped && A.hasDoubleJumped) {
            fx.doubleJumps++;
            ev.doubleJumpTicks = DOUBLE_JUMP_ACTIVE_TICKS;
        }
        if (!B.hasFlipped && A.hasFlipped) fx.dodges++;
        if (!B.isOnGround && (B.hasJumped || B.hasDoubleJumped || B.hasFlipped) && A.isOnGround && onWorld < 3)
            fx.flipResets++;
    }
    fx.activeStates = (A.isJumping ? MCRL_FX_JUMPING : 0u) | (ev.doubleJumpTicks > 0 ? MCRL_FX_DOUBLE_JUMPING : 0u) |
                      (A.isFlipping ? MCRL_FX_FLIPPING : 0u);
    if (ev.doubleJumpTicks > 0) ev.doubleJumpTicks--;

    const bool contact = A.worldContact.hasContact && b.world.found;
    fx.bodyContact = contact ? 1u : 0u;
    fx.bpx = fx.bpy = fx.bpz = 0;
    fx.bnx = fx.bny = fx.bnz = fx.slideSpeed = 0;
    if (contact) {
        const Momentum m = momentum(B.vel, b.world.n);
        const McrlHitFx h = hit_fx(w, b.world);
        fx.bpx = h.px;
        fx.bpy = h.py;
        fx.bpz = h.pz;
        fx.bnx = h.nx;
        fx.bny = h.ny;
        fx.bnz = h.nz;
        fx.slideSpeed = (float)m.slide;
        if (count && m.impact >= MIN_IMPACT_UU && delay_passed(ev.worldHitTick, tick)) {
            fx.worldHits++;
            fx.worldHitSpeed = (float)m.impact;
            fx.worldHit = h;
            ev.worldHitTick = tick;
        }
    }
    ev.fxStale = false;

    const BallHitInfo& hb = B.ballHitInfo;
    const BallHitInfo& ha = A.ballHitInfo;
    if (!ha.isValid || (hb.isValid && ha.tickCountWhenHit == hb.tickCountWhenHit)) return false;
    if (hb.isValid && ha.tickCountWhenHit == hb.tickCountWhenHit + 1) return false;
    ev.hits++;
    ev.hitSpeed = 0;
    fx.ballHit = McrlHitFx{};
    if (b.ball.found) {
        ev.hitSpeed = (float)momentum(B.vel - ball.vel, b.ball.n).impact;
        fx.ballHit = hit_fx(w, b.ball);
    }
    return true;
}

}

Before capture(McrlWorld& w, int32_t carId, Car& car) {
    return {carId, &car, &w.carEvents.at(carId), car.GetState(), {}, {}};
}

BallBefore capture(Ball& ball) { return {ball.GetState().vel, {}}; }

void after(McrlWorld& w, std::vector<Before>& cars, BallBefore& ball) {
    walk(w, cars, ball);
    const uint64_t tick = w.arena->tickCount;
    bool hitStart = false;
    int32_t toucher = 0;
    Team team = Team::BLUE;
    for (Before& b : cars) {
        if (car_after(w, b, ball, tick) && (!hitStart || b.id > toucher)) {
            hitStart = true;
            toucher = b.id;
            team = b.car->team;
        }
    }

    McrlBallFx& bf = w.ballFx;
    bf.worldContact = ball.world.found ? 1u : 0u;
    if (hitStart) {
        bf.lastTouchTeam = (int32_t)team;
        w.lastToucher = toucher;
    }
    if (!hitStart && !w.ballFxStale && ball.world.found) {
        const double s = momentum(ball.vel, ball.world.n).impact;
        if (s >= MIN_IMPACT_UU && delay_passed(w.bounceTick, tick)) {
            w.ballBounces++;
            w.ballBounceSpeed = (float)s;
            bf.bounce = hit_fx(w, ball.world);
            w.bounceTick = tick;
        }
    }
    w.ballFxStale = false;
}

void clear_tick(McrlCarFx& f) {
    for (McrlWheelFx& wh : f.wheels) {
        wh.px = wh.py = wh.pz = 0;
        wh.nx = wh.ny = wh.nz = 0;
        wh.contact = 0;
    }
    f.bodyContact = 0;
    f.bpx = f.bpy = f.bpz = 0;
    f.bnx = f.bny = f.bnz = 0;
    f.slideSpeed = 0;
    f.wet.immersion = 0;
    f.wet.fluid = 0;
}

void clear_tick(McrlBallFx& f) {
    f.worldContact = 0;
    f.wet.immersion = 0;
    f.wet.fluid = 0;
}

}
