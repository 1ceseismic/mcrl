#include "guard.h"

#include <cmath>
#include <limits>

#include "fxfeed.h"
#include "world.h"

using namespace RocketSim;

namespace mcrl::guard {
namespace {

constexpr size_t SLOTS = RING_TICKS + 1;

bool finite(float v) { return std::isfinite(v); }
bool finite(const Vec& v) { return finite(v.x) && finite(v.y) && finite(v.z); }
bool finite(const btVector3& v) { return finite(v.x()) && finite(v.y()) && finite(v.z()); }

bool finite(const PhysState& s) {
    return finite(s.pos) && finite(s.vel) && finite(s.angVel) && finite(s.rotMat.forward) && finite(s.rotMat.right) &&
           finite(s.rotMat.up);
}

size_t oldest(const Guard& g) { return (g.newest + SLOTS - (g.count - 1)) % SLOTS; }

struct Delta {
    double du[3];
};

Delta delta(const McrlWorld& w, int32_t ox, int32_t oy, int32_t oz) {
    return {{(double)((int64_t)w.ox - ox) * BT_TO_UU, (double)((int64_t)w.oz - oz) * BT_TO_UU,
             (double)((int64_t)w.oy - oy) * BT_TO_UU}};
}

Vec moved(const Vec& p, const Delta& d) {
    return Vec((float)((double)p.x - d.du[0]), (float)((double)p.y - d.du[1]), (float)((double)p.z - d.du[2]));
}

void reexpress(CarState& s, const Delta& d) {
    s.pos = moved(s.pos, d);
    if (s.ballHitInfo.isValid) s.ballHitInfo.ballPos = moved(s.ballHitInfo.ballPos, d);
}

void reexpress(BallState& s, const Delta& d) { s.pos = moved(s.pos, d); }

void at_rest(PhysState& s) {
    s.vel = Vec(0, 0, 0);
    s.angVel = Vec(0, 0, 0);
}

const CarState* find(const Entry& e, int32_t id) {
    for (const auto& [cid, s] : e.cars)
        if (cid == id) return &s;
    return nullptr;
}

#ifdef MCRL_TEST_HOOKS
constexpr int32_t BALL_ID = -1;

void fire_poison(McrlWorld& w) {
    Guard& g = w.guard;
    if (!g.poisonArmed) return;
    g.poisonArmed = false;
    const btScalar nan = std::numeric_limits<btScalar>::quiet_NaN();
    const btVector3 v(nan, nan, nan);
    if (g.poisonId == BALL_ID) {
        if (!soccar::ball_out(w)) w.arena->ball->_rigidBody.setLinearVelocity(v);
    } else if (auto it = w.cars.find(g.poisonId); it != w.cars.end()) {
        it->second->_rigidBody.setLinearVelocity(v);
    }
}
#endif

bool record(McrlWorld& w, Entry& e) {
    e.tick = w.arena->tickCount;
    e.ox = w.ox;
    e.oy = w.oy;
    e.oz = w.oz;
    e.cars.clear();
    for (const auto& [id, car] : w.cars) {
        e.cars.emplace_back(id, car->GetState());
        if (!finite(e.cars.back().second)) return false;
    }
    e.ball = w.arena->ball->GetState();
    if (!finite(e.ball)) return false;
    if (w.ballParked) {
        e.ball.vel = w.parkedVel * BT_TO_UU;
        e.ball.angVel = w.parkedAngVel;
        if (!finite(e.ball.vel) || !finite(e.ball.angVel)) return false;
    }
    return true;
}

const Entry* trip_entry(McrlWorld& w, Trip& trip, bool& again) {
    const Guard& g = w.guard;
    const uint64_t tick = w.arena->tickCount;
    again = trip.tripped && tick - trip.last <= RING_TICKS;
    trip = {true, tick};
    return g.count == 0 ? nullptr : &g.slots[again ? oldest(g) : g.newest];
}

void restore_car(McrlWorld& w, int32_t id, Car& car) {
    McrlCarEvents& ev = w.carEvents.at(id);
    bool again = false;
    const Entry* e = trip_entry(w, ev.trip, again);
    const CarState* recorded = e && ev.lastSet.tick < e->tick ? find(*e, id) : nullptr;
    CarState s;
    if (recorded) {
        s = *recorded;
        reexpress(s, delta(w, e->ox, e->oy, e->oz));
        if (again) at_rest(s);
    } else {
        s = ev.lastSet.state;
        reexpress(s, delta(w, ev.lastSet.ox, ev.lastSet.oy, ev.lastSet.oz));
        at_rest(s);
    }
    s.isDemoed = car._internalState.isDemoed;
    s.demoRespawnTimer = car._internalState.demoRespawnTimer;
    if (w.unlimitedBoost) s.boost = RLConst::BOOST_MAX;
    car.SetState(s);
    drop_contacts(w.arena->_bulletWorld, car._rigidBody);
    w.stepRays.at(id)->floor.clear();
    ev.wheelsStale = true;
    ev.stepDz = 0;
    ev.restored = true;
    ev.fxStale = true;
    fxfeed::clear_tick(ev.fx);
}

bool ball_finite(McrlWorld& w) {
    if (!finite(w.arena->ball->GetState())) return false;
    return !w.ballParked || (finite(w.parkedVel) && finite(w.parkedAngVel));
}

void restore_ball(McrlWorld& w) {
    bool again = false;
    const Entry* e = trip_entry(w, w.ballTrip, again);
    BallState b;
    if (e && w.ballLastSet.tick < e->tick) {
        b = e->ball;
        reexpress(b, delta(w, e->ox, e->oy, e->oz));
        if (again) {
            at_rest(b);
            wake_at_rest(b);
        }
    } else {
        const LastSet<BallState>& set = w.ballLastSet;
        b = set.state;
        reexpress(b, delta(w, set.ox, set.oy, set.oz));
        at_rest(b);
        wake_at_rest(b);
    }
    Ball* ball = w.arena->ball;
    ball->SetState(b);
    drop_contacts(w.arena->_bulletWorld, ball->_rigidBody);
    w.ballParked = false;
    w.ballFxStale = true;
    fxfeed::clear_tick(w.ballFx);
}

}

void reserve(Guard& g, size_t nCars) {
    for (Entry& e : g.slots) e.cars.reserve(nCars);
}

void drop_contacts(btDiscreteDynamicsWorld& bw, btRigidBody& rb) {
    if (btBroadphaseProxy* p = rb.getBroadphaseHandle()) bw.getPairCache()->cleanProxyFromPairs(p, bw.getDispatcher());
}

uint32_t after_tick(McrlWorld& w) {
#ifdef MCRL_TEST_HOOKS
    fire_poison(w);
#endif
    uint32_t flags = 0;
    for (const auto& [id, car] : w.cars) {
        if (finite(car->GetState())) continue;
        restore_car(w, id, *car);
        flags |= MCRL_STEP_RESTORED;
    }
    if (!soccar::ball_out(w) && !ball_finite(w)) {
        restore_ball(w);
        flags |= MCRL_STEP_RESTORED | MCRL_STEP_BALL_RESTORED;
    }
    Guard& g = w.guard;
    const size_t s = (g.newest + 1) % SLOTS;
    if (record(w, g.slots[s])) {
        g.newest = s;
        if (g.count < RING_TICKS) g.count++;
    }
    return flags;
}

#ifdef MCRL_TEST_HOOKS
int32_t arm_poison(McrlWorld& w, int32_t carId) {
    if (carId != BALL_ID && w.cars.find(carId) == w.cars.end()) {
        mcrl_internal::set_last_error("mcrl_debug_poison: unknown car id");
        return MCRL_ERR_NOT_FOUND;
    }
    w.guard.poisonArmed = true;
    w.guard.poisonId = carId;
    return MCRL_OK;
}
#endif

}
