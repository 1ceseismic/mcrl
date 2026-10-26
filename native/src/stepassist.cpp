#include "stepassist.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

#include "BulletCollision/CollisionDispatch/btCollisionDispatcher.h"
#include "BulletCollision/NarrowPhaseCollision/btPersistentManifold.h"
#include "voxels.h"
#include "world.h"

using namespace RocketSim;

namespace mcrl::stepassist {
namespace {
using voxels::Obb;
using voxels::WBox;

constexpr double INF = std::numeric_limits<double>::infinity();
constexpr uint32_t NOT_A_STEP = MCRL_SHAPE_THIN | MCRL_SHAPE_NONFLOOR;

Obb hitbox(const Car& car, const btTransform& t) {
    const btVector3 half = (car.config.hitboxSize * UU_TO_BT) / 2;
    const btVector3 c = t(car.config.hitboxPosOffset * UU_TO_BT);
    const btMatrix3x3& b = t.getBasis();
    Obb o;
    for (int j = 0; j < 3; j++) {
        o.c[j] = c[j];
        o.half[j] = half[j];
        for (int k = 0; k < 3; k++) o.axis[j][k] = b[k][j];
    }
    return o;
}

Obb grown(Obb o, double by) {
    for (double& h : o.half) h += by;
    return o;
}

Obb moved(Obb o, double dx, double dy, double dz) {
    o.c[0] += dx;
    o.c[1] += dy;
    o.c[2] += dz;
    return o;
}

Obb toMc(const McrlWorld& w, const Obb& b) {
    Obb m;
    m.c[0] = b.c[0] + w.ox;
    m.c[1] = b.c[2] + w.oy;
    m.c[2] = b.c[1] + w.oz;
    for (int j = 0; j < 3; j++) {
        m.axis[j][0] = b.axis[j][0];
        m.axis[j][1] = b.axis[j][2];
        m.axis[j][2] = b.axis[j][1];
        m.half[j] = b.half[j];
    }
    return m;
}

double dot3(const double a[3], const double b[3]) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

void corners(const Obb& o, double out[8][3]) {
    for (int i = 0; i < 8; i++)
        for (int k = 0; k < 3; k++)
            out[i][k] = o.c[k] + (i & 1 ? 1 : -1) * o.half[0] * o.axis[0][k] +
                        (i & 2 ? 1 : -1) * o.half[1] * o.axis[1][k] + (i & 4 ? 1 : -1) * o.half[2] * o.axis[2][k];
}

bool overlaps(const Obb& a, const Obb& b) {
    double d[3];
    for (int k = 0; k < 3; k++) d[k] = b.c[k] - a.c[k];
    auto separated = [&](const double L[3]) {
        double ra = 0, rb = 0;
        for (int j = 0; j < 3; j++) {
            ra += a.half[j] * std::abs(dot3(a.axis[j], L));
            rb += b.half[j] * std::abs(dot3(b.axis[j], L));
        }
        return std::abs(dot3(d, L)) >= ra + rb;
    };
    for (int j = 0; j < 3; j++)
        if (separated(a.axis[j]) || separated(b.axis[j])) return false;
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) {
            const double* p = a.axis[i];
            const double* q = b.axis[j];
            const double L[3] = {p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]};
            if (dot3(L, L) < 1e-18) continue;
            if (separated(L)) return false;
        }
    return true;
}

bool overlaps(const Obb& o, const btVector3& c, double r) {
    double dist2 = 0;
    for (int j = 0; j < 3; j++) {
        double s = 0;
        for (int k = 0; k < 3; k++) s += ((double)c[k] - o.c[k]) * o.axis[j][k];
        const double out = std::abs(s) - o.half[j];
        if (out > 0) dist2 += out * out;
    }
    return dist2 < r * r;
}

bool blockedNow(const Car& car) {
    const CarState& s = car._internalState;
    return s.isDemoed || s.isJumping || (car.controls.jump && !s.lastControls.jump);
}

struct Support {
    int wheels = 0;
    double z = 0;
};

Support support(const Car& car) {
    Support s;
    double sum = 0;
    const btVehicleRL& v = car._bulletVehicle;
    for (int i = 0; i < v.getNumWheels(); i++) {
        const btWheelInfo::RaycastInfo& r = v.m_wheelInfo[i].m_raycastInfo;
        if (!r.m_isInContact || r.m_contactNormalWS.z() < FLOOR_NORMAL_Z) continue;
        sum += r.m_contactPointWS.z();
        s.wheels++;
    }
    if (s.wheels) s.z = sum / s.wheels;
    return s;
}

void lift(btDiscreteDynamicsWorld& bw, btRigidBody& rb, double dz) {
    btTransform t = rb.getWorldTransform();
    t.getOrigin().setZ((btScalar)((double)t.getOrigin().z() + dz));
    rb.setWorldTransform(t);
    btTransform it = rb.getInterpolationWorldTransform();
    it.getOrigin().setZ((btScalar)((double)it.getOrigin().z() + dz));
    rb.setInterpolationWorldTransform(it);
    bw.updateSingleAabb(&rb);
}

bool throttleDir(const Car& car, const btMatrix3x3& basis, double dir[2]) {
    const float th = car.controls.throttle;
    const double fx = basis[0][0], fy = basis[1][0], fl = std::hypot(fx, fy);
    if (!(std::abs(th) >= RLConst::THROTTLE_DEADZONE) || fl < 1e-6) return false;
    const double sign = th > 0 ? 1 : -1;
    dir[0] = sign * fx / fl;
    dir[1] = sign * fy / fl;
    return true;
}

struct Found {
    double dz = 0;
    VirtualFloor floor{};
};

struct Entry {
    double s = INF;
    int axis = -1;
    double sign = 0;
};

struct Footprint {
    double c[2];
    double f[2];
    double r[2];
    double hf, hr;
};

Entry entry(const Footprint& fp, const double dir[2], const double bmn[2], const double bmx[2]) {
    const double axes[4][2] = {{1, 0}, {0, 1}, {fp.f[0], fp.f[1]}, {fp.r[0], fp.r[1]}};
    double in = -INF, out = INF, inBox[2] = {-INF, -INF}, depth[2] = {0, 0}, side[2] = {0, 0}, toward[2] = {0, 0};
    for (int a = 0; a < 4; a++) {
        const double* L = axes[a];
        const double rc = fp.c[0] * L[0] + fp.c[1] * L[1];
        const double rr = fp.hf * std::abs(fp.f[0] * L[0] + fp.f[1] * L[1]) +
                          fp.hr * std::abs(fp.r[0] * L[0] + fp.r[1] * L[1]);
        const double bc = 0.5 * ((bmn[0] + bmx[0]) * L[0] + (bmn[1] + bmx[1]) * L[1]);
        const double br = 0.5 * ((bmx[0] - bmn[0]) * std::abs(L[0]) + (bmx[1] - bmn[1]) * std::abs(L[1]));
        const double u = dir[0] * L[0] + dir[1] * L[1];
        double a0, a1;
        if (std::abs(u) < 1e-12) {
            const bool over = std::abs(rc - bc) < rr + br;
            a0 = over ? -INF : INF;
            a1 = over ? INF : -INF;
        } else {
            a0 = ((bc - br) - (rc + rr)) / u;
            a1 = ((bc + br) - (rc - rr)) / u;
            if (a0 > a1) std::swap(a0, a1);
        }
        in = std::max(in, a0);
        out = std::min(out, a1);
        if (a < 2) {
            inBox[a] = a0;
            depth[a] = rr + br - std::abs(rc - bc);
            side[a] = rc < bc ? -1 : 1;
            toward[a] = u > 0 ? -1 : u < 0 ? 1 : 0;
        }
    }
    Entry e;
    if (!(in < out) || in == INF) return e;
    e.s = in;
    if (in > 0 && (inBox[0] > -INF || inBox[1] > -INF)) {
        e.axis = inBox[0] >= inBox[1] ? 0 : 1;
        e.sign = toward[e.axis];
    } else {
        e.axis = depth[0] <= depth[1] ? 0 : 1;
        e.sign = side[e.axis];
    }
    return e;
}

Outcome detect(McrlWorld& w, const Car& car, const btTransform& t, const btVector3& vel, double rise, Found& out) {
    if (!w.shapes) return Outcome::NO_SHAPES;
    const McrlShapes& shapes = *w.shapes;
    const TerrainStore& terrain = *w.terrain;

    const Support sup = support(car);
    if (sup.wheels < 3) return Outcome::WHEELS;
    const btMatrix3x3& basis = t.getBasis();
    if (basis[2][2] < LEVEL_UP_Z) return Outcome::TILTED;

    double dir[2];
    const double vx = vel.x(), vy = vel.y(), sp = std::hypot(vx, vy);
    if (sp >= INTENT_SPEED) {
        dir[0] = vx / sp;
        dir[1] = vy / sp;
    } else if (!throttleDir(car, basis, dir)) {
        return Outcome::NO_DIR;
    }
    const double d = std::max(sp * w.arena->tickTime, MIN_PROBE_TRAVEL) + PROBE_MARGIN;

    const Obb hb = hitbox(car, t);
    const double upz = hb.axis[2][2];
    const double lower = (hb.c[2] - hb.half[2] * upz - (sup.z + STEP_MIN)) / upz;
    Obb v0 = hb;
    v0.half[2] += lower / 2;
    if (!(v0.half[2] > SKIN)) return Outcome::NONE;
    for (int k = 0; k < 3; k++) v0.c[k] -= hb.axis[2][k] * lower / 2;
    Obb v1 = moved(v0, dir[0] * d, dir[1] * d, 0);
    v1.half[1] += PROBE_WIDEN;
    const Obb v1m = toMc(w, v1), v0sm = toMc(w, grown(v0, -SKIN));

    double mn[3], mx[3];
    voxels::aabbOf(v1m, mn, mx);
    if (!voxels::ready(terrain, shapes, mn, mx)) return Outcome::NOT_READY;
    std::vector<WBox>& boxes = w.boxScratch;
    std::vector<WBox>& hits = w.hitScratch;
    hits.clear();
    voxels::boxesIn(terrain, shapes, mn, mx, boxes);
    for (const WBox& b : boxes)
        if (voxels::overlaps(v1m, b) && !voxels::overlaps(v0sm, b)) hits.push_back(b);
    if (hits.empty()) return Outcome::NONE;
    double topMc = -INF;
    for (const WBox& b : hits) {
        if (b.flags & NOT_A_STEP) return Outcome::THIN;
        topMc = std::max(topMc, b.mx[1]);
    }
    const double top = topMc - w.oy;
    const double dz = top - sup.z;
    if (!(dz >= STEP_MIN && dz <= rise)) return Outcome::RISE;

    Footprint fp;
    fp.c[0] = v0.c[0];
    fp.c[1] = v0.c[1];
    const double fl = std::hypot(hb.axis[0][0], hb.axis[0][1]);
    fp.f[0] = hb.axis[0][0] / fl;
    fp.f[1] = hb.axis[0][1] / fl;
    fp.r[0] = -fp.f[1];
    fp.r[1] = fp.f[0];
    fp.hf = fp.hr = 0;
    for (int j = 0; j < 3; j++) {
        fp.hf += v0.half[j] * std::abs(v0.axis[j][0] * fp.f[0] + v0.axis[j][1] * fp.f[1]);
        fp.hr += v0.half[j] * std::abs(v0.axis[j][0] * fp.r[0] + v0.axis[j][1] * fp.r[1]);
    }
    Entry first;
    const WBox* firstBox = nullptr;
    for (const WBox& b : hits) {
        const double bmn[2] = {b.mn[0] - w.ox, b.mn[2] - w.oz}, bmx[2] = {b.mx[0] - w.ox, b.mx[2] - w.oz};
        const Entry e = entry(fp, dir, bmn, bmx);
        if (e.s < first.s) {
            first = e;
            firstBox = &b;
        }
    }
    if (!firstBox || first.axis < 0 || first.sign == 0) return Outcome::FACE;
    const int a = first.axis, o = 1 - a;
    const double n = first.sign;
    if (!(n * dir[a] <= RISER_FACING_DOT)) return Outcome::FACE;
    const double bmnA = a == 0 ? firstBox->mn[0] - w.ox : firstBox->mn[2] - w.oz;
    const double bmxA = a == 0 ? firstBox->mx[0] - w.ox : firstBox->mx[2] - w.oz;
    const double bmnO = o == 0 ? firstBox->mn[0] - w.ox : firstBox->mn[2] - w.oz;
    const double bmxO = o == 0 ? firstBox->mx[0] - w.ox : firstBox->mx[2] - w.oz;
    const double plane = n < 0 ? bmnA : bmxA;

    const Obb l0 = moved(hb, 0, 0, dz), l1 = moved(l0, dir[0] * d, dir[1] * d, 0);
    const Obb h0m = toMc(w, grown(l0, -SKIN)), h1m = toMc(w, grown(l1, -SKIN));
    double amn[3], amx[3], bmn3[3], bmx3[3];
    voxels::aabbOf(toMc(w, l0), amn, amx);
    voxels::aabbOf(toMc(w, l1), bmn3, bmx3);
    for (int k = 0; k < 3; k++) {
        amn[k] = std::min(amn[k], bmn3[k]);
        amx[k] = std::max(amx[k], bmx3[k]);
    }
    if (!voxels::ready(terrain, shapes, amn, amx)) return Outcome::NOT_READY;
    if (voxels::obbOverlapsAny(terrain, shapes, h0m) || voxels::obbOverlapsAny(terrain, shapes, h1m))
        return Outcome::HEADROOM;
    const Ball* ball = w.arena->ball;
    const btVector3& bc = ball->_rigidBody.getWorldTransform().getOrigin();
    if (overlaps(l0, bc, ball->GetRadiusBullet()) || overlaps(l1, bc, ball->GetRadiusBullet())) return Outcome::BALL;
    for (const auto& [id, other] : w.cars) {
        if (other == &car || other->_internalState.isDemoed) continue;
        const Obb ob = hitbox(*other, other->_rigidBody.getWorldTransform());
        if (overlaps(l0, ob) || overlaps(l1, ob)) return Outcome::CAR;
    }

    double cs[8][3];
    corners(hb, cs);
    double far = 0, tmin = INF, tmax = -INF;
    for (const auto& c : cs) {
        far = std::max(far, (c[a] - plane) * n);
        tmin = std::min(tmin, c[o]);
        tmax = std::max(tmax, c[o]);
    }
    const double drift = (far + PAST_RISER) / -(n * dir[a]) * dir[o];
    VirtualFloor& f = out.floor;
    f = VirtualFloor{};
    f.zBT = top;
    const double nearA = plane - n * FLOOR_MARGIN, farA = plane + n * (far + FLOOR_MARGIN);
    f.rect[a] = std::min(nearA, farA);
    f.rect[a + 2] = std::max(nearA, farA);
    f.rect[o] = tmin + std::min(0.0, drift) - FLOOR_MARGIN;
    f.rect[o + 2] = tmax + std::max(0.0, drift) + FLOOR_MARGIN;
    f.riserP[a] = plane;
    f.riserP[o] = 0.5 * (bmnO + bmxO);
    f.riserP[2] = top;
    f.riserN[a] = n;
    out.dz = dz;
    return Outcome::SNAP;
}

bool hasRiser(const VirtualFloor& f) { return f.riserN[0] != 0 || f.riserN[1] != 0 || f.riserN[2] != 0; }

bool pastRiser(const Obb& hb, const VirtualFloor& f) {
    double cs[8][3];
    corners(hb, cs);
    for (const auto& c : cs) {
        const double u = (c[0] - f.riserP[0]) * f.riserN[0] + (c[1] - f.riserP[1]) * f.riserN[1] +
                         (c[2] - f.riserP[2]) * f.riserN[2];
        if (u > -PAST_RISER) return false;
    }
    return true;
}

bool stepUnder(McrlWorld& w, const Obb& hb, const VirtualFloor& f) {
    double mn[3], mx[3];
    voxels::aabbOf(toMc(w, hb), mn, mx);
    const double y = f.zBT + w.oy;
    const double qmn[3] = {mn[0] - FLOOR_MARGIN, y - FLOOR_MATCH, mn[2] - FLOOR_MARGIN};
    const double qmx[3] = {mx[0] + FLOOR_MARGIN, y + FLOOR_MATCH, mx[2] + FLOOR_MARGIN};
    if (!voxels::ready(*w.terrain, *w.shapes, qmn, qmx)) return false;
    std::vector<WBox>& boxes = w.boxScratch;
    voxels::boxesIn(*w.terrain, *w.shapes, qmn, qmx, boxes);
    for (const WBox& b : boxes)
        if (std::abs(b.mx[1] - y) <= FLOOR_MATCH) return true;
    return false;
}

bool crossing(const Car& car, const VirtualFloor& f) {
    const btVector3& v = car._rigidBody.getLinearVelocity();
    const double tx = -f.riserN[0], ty = -f.riserN[1];
    if (std::hypot(v.x(), v.y()) >= INTENT_SPEED) return v.x() * tx + v.y() * ty > 0;
    double dir[2];
    return throttleDir(car, car._rigidBody.getWorldTransform().getBasis(), dir) && dir[0] * tx + dir[1] * ty > 0;
}

bool wheelOverStep(McrlWorld& w, const Car& car, const VirtualFloor& f) {
    const btTransform& t = car._rigidBody.getWorldTransform();
    const double y = f.zBT + w.oy;
    constexpr double E = 1.0 / 4096;
    std::vector<WBox>& boxes = w.boxScratch;
    const btVehicleRL& v = car._bulletVehicle;
    for (int i = 0; i < v.getNumWheels(); i++) {
        const btVector3 p = t(v.m_wheelInfo[i].m_chassisConnectionPointCS);
        const double x = p.x() + w.ox, z = p.y() + w.oz;
        const double qmn[3] = {x - E, y - FLOOR_MATCH, z - E}, qmx[3] = {x + E, y + FLOOR_MATCH, z + E};
        if (!voxels::ready(*w.terrain, *w.shapes, qmn, qmx)) continue;
        voxels::boxesIn(*w.terrain, *w.shapes, qmn, qmx, boxes);
        if (!boxes.empty()) return true;
    }
    return false;
}

bool atRise(double h, double rise) { return h >= STEP_MIN && h <= rise; }

bool riserContact(const McrlWorld& w, const Car& car, double groundZ, double rise) {
    btCollisionDispatcher* disp = w.arena->_bulletWorld.getDispatcher();
    for (int i = 0; i < disp->getNumManifolds(); i++) {
        const btPersistentManifold* m = disp->getManifoldByIndexInternal(i);
        const btCollisionObject* other = m->getBody0() == &car._rigidBody   ? m->getBody1()
                                         : m->getBody1() == &car._rigidBody ? m->getBody0()
                                                                            : nullptr;
        if (!other || !other->isStaticObject()) continue;
        for (int j = 0; j < m->getNumContacts(); j++) {
            const btManifoldPoint& p = m->getContactPoint(j);
            if (p.getDistance() > 0 && p.getAppliedImpulse() <= 0) continue;
            if (std::abs(p.m_normalWorldOnB.z()) >= RISER_NORMAL_Z) continue;
            const double h = 0.5 * ((double)p.getPositionWorldOnA().z() + p.getPositionWorldOnB().z()) - groundZ;
            if (atRise(h, rise)) return true;
        }
    }
    return false;
}

// Bullet only finds this contact on the next tick.
bool movedIntoRiser(McrlWorld& w, const Car& car, double groundZ, double rise) {
    const Obb hm = toMc(w, grown(hitbox(car, car._rigidBody.getWorldTransform()), -SKIN));
    double mn[3], mx[3];
    voxels::aabbOf(hm, mn, mx);
    std::vector<WBox>& boxes = w.boxScratch;
    voxels::boxesIn(*w.terrain, *w.shapes, mn, mx, boxes);
    for (const WBox& b : boxes)
        if (atRise(b.mx[1] - w.oy - groundZ, rise) && voxels::overlaps(hm, b)) return true;
    return false;
}
}

PreTickPose capture(const Car& car, bool fullStep) {
    PreTickPose p;
    p.t = car._rigidBody.getWorldTransform();
    p.vel = car._rigidBody.getLinearVelocity();
    p.angVel = car._rigidBody.getAngularVelocity();
    p.blocked = blockedNow(car);
    p.fullStep = fullStep;
    return p;
}

float pre(McrlWorld& w, Car* car, VirtualFloor& floor, bool fullStep, Outcome* why) {
    Outcome o = Outcome::NO_SHAPES;
    Found f;
    if (w.shapes) {
        o = blockedNow(*car) ? Outcome::BLOCKED
                             : detect(w, *car, car->_rigidBody.getWorldTransform(), car->_rigidBody.getLinearVelocity(),
                                      maxRise(fullStep), f);
    }
    if (why) *why = o;
    if (o != Outcome::SNAP) return 0;
    lift(w.arena->_bulletWorld, car->_rigidBody, f.dz);
    floor.activate(f.floor);
    return (float)f.dz;
}

float post(McrlWorld& w, Car* car, VirtualFloor& floor, bool snapped, const PreTickPose& before) {
    const CarState& cs = car->_internalState;

    if (floor.active) {
        floor.afterTick();
        if (floor.active && (before.blocked || cs.isJumping || cs.isDemoed)) floor.clear();
        if (floor.active && hasRiser(floor)) {
            const Obb hb = hitbox(*car, car->_rigidBody.getWorldTransform());
            if (pastRiser(hb, floor)) floor.clear();
            else if (w.shapes && !stepUnder(w, hb, floor)) floor.clear();
            else if (w.shapes && !crossing(*car, floor) && !wheelOverStep(w, *car, floor)) floor.clear();
        }
    }

    if (snapped || before.blocked || !w.shapes || cs.isDemoed) return 0;
    const Support sup = support(*car);
    if (sup.wheels < 3) return 0;
    const double rise = maxRise(before.fullStep);
    if (!riserContact(w, *car, sup.z, rise) && !movedIntoRiser(w, *car, sup.z, rise)) return 0;
    Found f;
    const Outcome o = detect(w, *car, before.t, before.vel, rise, f);
    if (o != Outcome::SNAP) return 0;
    btRigidBody& rb = car->_rigidBody;
    btTransform t = before.t;
    t.getOrigin().setZ((btScalar)((double)t.getOrigin().z() + f.dz));
    rb.setWorldTransform(t);
    rb.setInterpolationWorldTransform(t);
    rb.setLinearVelocity(btVector3(before.vel.x(), before.vel.y(), std::min(before.vel.z(), (btScalar)0)));
    rb.setAngularVelocity(before.angVel);
    rb.updateInertiaTensor();
    w.arena->_bulletWorld.updateSingleAabb(&rb);
    car->_internalState.rotMat = t.getBasis();  // RocketSim cached the post-tick rotation
    moveWheelCache(*car, btVector3(0, 0, (btScalar)f.dz));
    floor.activate(f.floor);
    return (float)f.dz;
}

void moveWheelCache(Car& car, const btVector3& delta) {
    btVehicleRL& v = car._bulletVehicle;
    for (int i = 0; i < v.getNumWheels(); i++) {
        btWheelInfoRL& wi = v.m_wheelInfo[i];
        wi.m_raycastInfo.m_hardPointWS += delta;
        wi.m_raycastInfo.m_contactPointWS += delta;
        wi.m_worldTransform.getOrigin() += delta;
    }
}

}
