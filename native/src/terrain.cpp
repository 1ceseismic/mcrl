#include "terrain.h"

#include <initializer_list>

#include "materials.h"

namespace mcrl {

TerrainStore::TerrainStore(McrlWorld* w, RocketSim::Arena* a, int32_t x, int32_t y, int32_t z)
    : world(w), arena(a), ox(x), oy(y), oz(z) {}

TerrainStore::~TerrainStore() { Clear(); }

std::unique_ptr<btRigidBody> TerrainStore::MakeBody(btBvhTriangleMeshShape* shape, const SecKey& k) const {
    auto rb = std::make_unique<btRigidBody>(0.f, nullptr, shape);
    rb->setWorldTransform(SectionTransform(k));
    rb->setUserPointer(arena);  // RocketSim's contact callback reads it
    // RocketSim's arena statics.
    rb->setFriction(0.6f);
    rb->setRestitution(0.3f);
    rb->setRollingFriction(0.f);
    return rb;
}

btTransform TerrainStore::SectionTransform(const SecKey& k) const {
    const btVector3 origin((btScalar)((int64_t)k.x * 16 - ox), (btScalar)((int64_t)k.z * 16 - oz),
                           (btScalar)((int64_t)k.y * 16 - oy));
    return btTransform(btMatrix3x3::getIdentity(), origin);
}

int32_t TerrainStore::Commit(std::unique_ptr<McrlBuilt> b) {
    const SecKey k{b->sx, b->sy, b->sz};
    auto it = live.find(k);
    if (it != live.end() && b->version < it->second.data->version) return MCRL_STALE;

    std::unique_ptr<btRigidBody> body = b->shape ? MakeBody(b->shape.get(), k) : nullptr;
    b->world = world;
    if (b->shape) {
        b->shape->setUserPointer(b.get());
        b->shape->setUserIndex(materials::TERRAIN_TAG);
    }
    if (it == live.end()) it = live.emplace(k, Live{}).first;
    Live& L = it->second;
    std::unique_ptr<btRigidBody> oldBody = std::move(L.body);
    std::unique_ptr<McrlBuilt> oldData = std::move(L.data);
    if (oldBody) arena->_bulletWorld.removeRigidBody(oldBody.get());
    if (body) arena->_bulletWorld.addRigidBody(body.get());
    WakeBallIfOverlapping(k, oldBody.get(), body.get());
    L.data = std::move(b);
    L.body = std::move(body);
    oldBody.reset();
    oldData.reset();
    return MCRL_OK;
}

void TerrainStore::Remove(const SecKey& k) {
    auto it = live.find(k);
    if (it == live.end()) return;
    btRigidBody* rb = it->second.body.get();
    if (rb) arena->_bulletWorld.removeRigidBody(rb);
    WakeBallIfOverlapping(k, rb, nullptr);
    live.erase(it);
}

bool TerrainStore::IsReady(const SecKey& k) const { return live.find(k) != live.end(); }

const btRigidBody* TerrainStore::body(const SecKey& k) const {
    auto it = live.find(k);
    return it == live.end() ? nullptr : it->second.body.get();
}

const uint16_t* TerrainStore::cells(const SecKey& k) const {
    auto it = live.find(k);
    return it == live.end() ? nullptr : it->second.data->cells.data();
}

const uint8_t* TerrainStore::fluid(const SecKey& k) const {
    auto it = live.find(k);
    if (it == live.end() || it->second.data->fluid.empty()) return nullptr;
    return it->second.data->fluid.data();
}

bool TerrainStore::SetFluid(const SecKey& k, const uint8_t* cells) {
    auto it = live.find(k);
    if (it == live.end()) return false;
    std::vector<uint8_t>& plane = it->second.data->fluid;
    if (cells) plane.assign(cells, cells + SECTION_CELLS);
    else plane.clear();
    return true;
}

void TerrainStore::Shift(int32_t x, int32_t y, int32_t z) {
    ox = x;
    oy = y;
    oz = z;
    for (auto& [k, L] : live) {
        if (!L.body) continue;
        L.body->setWorldTransform(SectionTransform(k));
        arena->_bulletWorld.updateSingleAabb(L.body.get());
    }
}

void TerrainStore::Clear() {
    for (auto& [k, L] : live)
        if (L.body) arena->_bulletWorld.removeRigidBody(L.body.get());
    live.clear();
}

// A sleeping ball ignores terrain changing around it.
void TerrainStore::WakeBallIfOverlapping(const SecKey& k, const btRigidBody* a, const btRigidBody* b) {
    RocketSim::Ball* ball = arena->ball;
    if (!ball) return;
    btRigidBody& brb = ball->_rigidBody;
    if (brb.getLinearVelocity().length2() != 0 || brb.getAngularVelocity().length2() != 0) return;

    btVector3 mn = SectionTransform(k).getOrigin();
    btVector3 mx = mn + btVector3(16, 16, 16);
    for (const btRigidBody* rb : {a, b}) {
        if (!rb) continue;
        btVector3 lo, hi;
        rb->getCollisionShape()->getAabb(rb->getWorldTransform(), lo, hi);
        mn.setMin(lo);
        mx.setMax(hi);
    }
    btVector3 bl, bh;
    brb.getAabb(bl, bh);
    constexpr btScalar margin = 0.5f;
    for (int i = 0; i < 3; i++)
        if (bl[i] > mx[i] + margin || bh[i] < mn[i] - margin) return;

    brb.setLinearVelocity(btVector3(0, 0, -1e-3f * UU_TO_BT));
    brb.setActivationState(ACTIVE_TAG);
}
}
