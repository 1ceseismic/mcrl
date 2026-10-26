#include "materials.h"

#include <algorithm>
#include <cmath>
#include <iterator>
#include <mutex>

#include "BulletCollision/CollisionDispatch/btManifoldResult.h"
#include "fluids.h"
#include "mesher.h"
#include "shapes.h"
#include "world.h"

using namespace RocketSim;

namespace mcrl::materials {

namespace {
const McrlBuilt* section_of(const btCollisionObject* obj) {
    const btCollisionShape* shape = obj->getCollisionShape();
    if (!shape || shape->getUserIndex() != TERRAIN_TAG) return nullptr;
    return static_cast<const McrlBuilt*>(shape->getUserPointer());
}
}

const McrlMaterial& of(const McrlWorld& w, const btCollisionObject* obj, int tri) {
    const McrlMaterial& none = w.materials[0];
    if (!obj || tri < 0) return none;
    const McrlBuilt* b = section_of(obj);
    if (!b || (size_t)tri >= b->triMat.size()) return none;
    return w.materials[b->triMat[tri]];
}

void refresh(McrlWorld& w) {
    if (!w.shapes) return;
    const McrlShapes& s = *w.shapes;
    if (s.materialsVersion.load(std::memory_order_acquire) == w.materialsSeen) return;
    std::shared_lock lock(s.mu);
    std::copy(std::begin(s.materials), std::end(s.materials), std::begin(w.materials));
    w.materialsSeen = s.materialsVersion.load(std::memory_order_relaxed);
}

void after_tick(McrlWorld& w, std::vector<fxfeed::Before>& cars, const fxfeed::BallBefore& ball) {
    const bool surfaces = (w.extras & MCRL_EXTRAS_SURFACES) != 0;
    if (surfaces && ball.world.found) {
        const float k = of(w, ball.world.other, ball.world.tri).ballDrag;
        btRigidBody& rb = w.arena->ball->_rigidBody;
        if (k > 0) {
            const btScalar f = std::exp(-k * w.arena->tickTime);
            rb.setLinearVelocity(rb.getLinearVelocity() * f);
            rb.setAngularVelocity(rb.getAngularVelocity() * f);
        }
    }
    for (fxfeed::Before& b : cars) {
        if (b.state.isDemoed) continue;  // RocketSim skipped its wheel update
        Car& car = *b.car;
        btVehicleRL& veh = car._bulletVehicle;
        const StepRaycaster& ray = *w.stepRays.at(b.id);
        const CarState& B = b.state;
        const bool deep =
            (w.extras & MCRL_EXTRAS_FLUIDS) && b.ev->fx.wet.immersion > fluids::TRACTION_IMMERSION;
        const int n = std::min(veh.getNumWheels(), 4);
        float drag = 0;
        int padWheel = -1;
        float padE = 0;
        double padL = 0;
        for (int i = 0; i < n; i++) {
            btWheelInfoRL& wi = veh.m_wheelInfo[i];
            if (!wi.m_raycastInfo.m_isInContact) continue;
            const StepRaycaster::WheelHit& h = ray.hits[i];
            const bool recorded = h.obj == wi.m_raycastInfo.m_groundObject;
            const McrlMaterial& m = surfaces && recorded ? of(w, h.obj, h.tri) : w.materials[0];
            wi.m_latFriction *= m.wheelLat;
            wi.m_longFriction *= m.wheelLong;
            if (deep) {
                wi.m_latFriction *= fluids::UNDERWATER_TRACTION;
                wi.m_longFriction *= fluids::UNDERWATER_TRACTION;
            }
            drag = std::max(drag, m.wheelDrag);
            if (m.padRestitution <= 0 || B.wheelsWithContact[i]) continue;
            const btVector3& nrm = wi.m_raycastInfo.m_contactNormalWS;
            const double L = std::abs((double)B.vel.x * nrm.x() + (double)B.vel.y * nrm.y() + (double)B.vel.z * nrm.z());
            if (L > fxfeed::MIN_IMPACT_UU && L > padL) {
                padWheel = i;
                padE = m.padRestitution;
                padL = L;
            }
        }
        if (b.ev->held || car._internalState.isDemoed) continue;
        btRigidBody& rb = car._rigidBody;
        if (drag > 0) rb.setLinearVelocity(rb.getLinearVelocity() * std::exp(-drag * w.arena->tickTime));
        if (padWheel >= 0) {
            const btVector3 nrm = veh.m_wheelInfo[padWheel].m_raycastInfo.m_contactNormalWS;
            const btScalar out = (btScalar)(padE * padL) * UU_TO_BT;
            const btVector3 v = rb.getLinearVelocity();
            const btScalar vn = v.dot(nrm);
            // A later axle's landing must not slow the bounce.
            if (vn < out) rb.setLinearVelocity(v + nrm * (out - vn));
        }
    }
}

bool contact_added(btManifoldPoint& cp, const btCollisionObjectWrapper* a, int partA, int indexA,
                   const btCollisionObjectWrapper* b, int partB, int indexB) {
    Arena::_BulletContactAddedCallback(cp, a, partA, indexA, b, partB, indexB);
    const btCollisionObject* oa = a->getCollisionObject();
    const btCollisionObject* ob = b->getCollisionObject();
    if (!oa->hasContactResponse() || !ob->hasContactResponse()) return true;
    const McrlBuilt* sa = section_of(oa);
    const McrlBuilt* sb = section_of(ob);
    if (!sa == !sb) return true;
    const McrlBuilt& built = sa ? *sa : *sb;
    const btCollisionObject* terrain = sa ? oa : ob;
    const btCollisionObject* other = sa ? ob : oa;
    if (!built.world || !(built.world->extras & MCRL_EXTRAS_SURFACES)) return true;
    const int type = other->getUserIndex();
    if (type != BT_USERINFO_TYPE_CAR && type != BT_USERINFO_TYPE_BALL) return true;
    const McrlMaterial& m = of(*built.world, terrain, sa ? indexA : indexB);
    const bool car = type == BT_USERINFO_TYPE_CAR;
    cp.m_combinedFriction *= car ? m.chassisFriction : m.ballFriction;
    const float restitution = car ? m.chassisRestitution : m.ballRestitution;
    if (restitution >= 0) cp.m_combinedRestitution = restitution;
    return true;
}

void install_callback() noexcept {
    if (gContactAddedCallback != &contact_added) gContactAddedCallback = &contact_added;
}

}
