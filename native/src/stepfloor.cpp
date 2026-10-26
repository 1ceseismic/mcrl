#include "stepfloor.h"

#include <algorithm>

#include "stepassist.h"
#include "world.h"

namespace mcrl {

namespace {
// Float slack so ground at floor height wins.
constexpr double AT_PLANE_FRACTION = 1e-5;

struct TriangleRayCallback final : btCollisionWorld::ClosestRayResultCallback {
    using ClosestRayResultCallback::ClosestRayResultCallback;
    int tri = -1;
    btScalar addSingleResult(btCollisionWorld::LocalRayResult& r, bool normalInWorldSpace) override {
        tri = r.m_localShapeInfo ? r.m_localShapeInfo->m_triangleIndex : -1;
        return ClosestRayResultCallback::addSingleResult(r, normalInWorldSpace);
    }
};
}

void VirtualFloor::activate(const VirtualFloor& f) {
    *this = f;
    active = true;
    idle = 0;
    used = false;
}

void VirtualFloor::afterTick() {
    if (!active) return;
    idle = used ? 0 : idle + 1;
    used = false;
    if (idle >= stepassist::FLOOR_IDLE_TICKS) clear();
}

void VirtualFloor::clear() { *this = VirtualFloor{}; }

void VirtualFloor::shift(const double d[3]) {
    zBT -= d[2];
    rect[0] -= d[0];
    rect[2] -= d[0];
    rect[1] -= d[1];
    rect[3] -= d[1];
    for (int i = 0; i < 3; i++) riserP[i] -= d[i];
}

bool VirtualFloor::contains(double x, double y) const {
    return x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3];
}

StepRaycaster::StepRaycaster() : btDefaultVehicleRaycaster(nullptr), dummy(0, nullptr, nullptr) {}

void StepRaycaster::installInto(RocketSim::Car& c) {
    const btDefaultVehicleRaycaster& stock = c._bulletVehicleRaycaster;
    m_dynamicsWorld = stock.m_dynamicsWorld;
    addedFilterMask = stock.addedFilterMask;
    c._bulletVehicle.m_vehicleRaycaster = this;
    car = &c;
}

StepRaycaster::WheelHit* StepRaycaster::slot(const btVector3& from) {
    if (!car) return nullptr;
    const RocketSim::btVehicleRL& veh = car->_bulletVehicle;
    const int n = std::min(veh.getNumWheels(), 4);
    for (int i = 0; i < n; i++)
        if (veh.m_wheelInfo[i].m_raycastInfo.m_hardPointWS == from) return &hits[i];
    return nullptr;
}

void* StepRaycaster::castRay(const btVector3& from, const btVector3& to, const btCollisionObject* ignoreObj,
                             btVehicleRaycasterResult& result) {
    // btDefaultVehicleRaycaster::castRay, also keeping the triangle index.
    TriangleRayCallback cb(from, to, ignoreObj);
    cb.m_collisionFilterGroup |= addedFilterMask;
    m_dynamicsWorld->rayTest(from, to, cb);
    const btRigidBody* body = nullptr;
    if (cb.hasHit()) {
        body = btRigidBody::upcast(cb.m_collisionObject);
        if (body && body->hasContactResponse()) {
            result.m_hitPointInWorld = cb.m_hitPointWorld;
            result.m_hitNormalInWorld = cb.m_hitNormalWorld;
            result.m_hitNormalInWorld.normalize();
            result.m_distFraction = cb.m_closestHitFraction;
        } else {
            body = nullptr;
        }
    }
    void* hit = (void*)body;
    WheelHit* h = slot(from);
    if (h) *h = body ? WheelHit{body, cb.tri} : WheelHit{};

    if (!floor.active) return hit;
    const double fz = from.z(), tz = to.z();
    if (!(fz > floor.zBT && tz < floor.zBT)) return hit;
    const double t = (fz - floor.zBT) / (fz - tz);
    if (hit && result.m_distFraction <= t + AT_PLANE_FRACTION) return hit;
    const double x = from.x() + ((double)to.x() - from.x()) * t;
    const double y = from.y() + ((double)to.y() - from.y()) * t;
    if (!floor.contains(x, y)) return hit;
    result.m_hitPointInWorld = btVector3((btScalar)x, (btScalar)y, (btScalar)floor.zBT);
    result.m_hitNormalInWorld = btVector3(0, 0, 1);
    result.m_distFraction = (btScalar)t;
    floor.used = true;
    if (h) *h = WheelHit{&dummy, -1};
    return &dummy;
}

#ifdef MCRL_TEST_HOOKS
namespace testing {
bool forceFloor(McrlWorld& w, int32_t carId, double zMc, const double rectMc[4]) {
    auto it = w.stepRays.find(carId);
    if (it == w.stepRays.end()) return false;
    VirtualFloor f{};
    f.zBT = zMc - w.oy;
    f.rect[0] = rectMc[0] - w.ox;
    f.rect[1] = rectMc[1] - w.oz;
    f.rect[2] = rectMc[2] - w.ox;
    f.rect[3] = rectMc[3] - w.oz;
    it->second->floor.activate(f);
    return true;
}
}
#endif

}
