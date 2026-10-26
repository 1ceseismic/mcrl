#include <doctest/doctest.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <thread>
#include <vector>
#include "BulletCollision/CollisionDispatch/btManifoldResult.h"
#include "materials.h"
#include "mcrl.h"
#include "mesher.h"
#include "stepfloor.h"
#include "terrain_fixtures.h"
#include "world.h"

using namespace fx;
using RocketSim::btWheelInfoRL;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double CAR_REST = 0.34;
constexpr double BALL_REST = 1.863;
constexpr double UU = 1.0 / 50;

constexpr int ICE_MAT = 1, SOUL_MAT = 2, SLIME_MAT = 3;
constexpr McrlMaterial ICE_VALUES{0.3175f, 0.4229f, 0, 0.2383f, -1, 0.2383f, -1, 0, 0, 0};
constexpr McrlMaterial SOUL_VALUES{1, 1, 3.6f, 1, -1, 1, -1, 1.5f, 0, 0};
constexpr McrlMaterial SLIME_VALUES{0.6638f, 0.7354f, 0, 0.5991f, 0.9f, 0.5991f, 0.95f, 0, 0.9f, 0};

enum : uint16_t { SOUL = ICE + 1, SLIME = ICE + 2 };

bool sameBytes(const void* a, const void* b, size_t n) { return std::memcmp(a, b, n) == 0; }

Scene stripOf(uint16_t id) { return Scene().fill(-16, 63, -16, 127, 63, 31, id); }

Scene mixedStrip() { return Scene().fill(-16, 63, -16, 127, 63, 0, CUBE).fill(-16, 63, 1, 127, 63, 31, ICE); }

struct Strip {
    McrlShapes* shapes = nullptr;
    McrlWorld* w = nullptr;
    explicit Strip(uint16_t id, uint32_t extras = MCRL_EXTRAS_SURFACES) : Strip(stripOf(id), extras) {}
    Strip(const Scene& sc, uint32_t extras) {
        shapes = makeShapes();
        addIce(shapes);
        REQUIRE(registerCube(shapes, SOUL_MAT) == SOUL);
        REQUIRE(registerCube(shapes, SLIME_MAT) == SLIME);
        w = makeWorld(shapes);
        registerMaterial(shapes, ICE_MAT, ICE_VALUES);
        registerMaterial(shapes, SOUL_MAT, SOUL_VALUES);
        registerMaterial(shapes, SLIME_MAT, SLIME_VALUES);
        sc.commitRange(w, shapes, -1, 3, -1, 7, 5, 1);
        REQUIRE(mcrl_world_set_extras(w, extras) == MCRL_OK);
    }
    ~Strip() {
        CHECK(mcrl_world_destroy(w) == MCRL_OK);
        CHECK(mcrl_shapes_destroy(shapes) == MCRL_OK);
    }
    Strip(const Strip&) = delete;
    Strip& operator=(const Strip&) = delete;

    void addCar(const McrlCarState& c, float throttle) {
        REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
        setControls(w, 1, throttle);
    }
    const btWheelInfoRL& wheel(int i) const { return w->cars.at(1)->_bulletVehicle.m_wheelInfo[i]; }
    mcrl::StepRaycaster& ray() const { return *w->stepRays.at(1); }
};

McrlCarFx getFx(McrlWorld* w, int32_t id) {
    McrlCarFx f{};
    REQUIRE(mcrl_car_get_fx(w, id, &f) == MCRL_OK);
    return f;
}

double brakeDistance(uint16_t id) {
    Strip s(id);
    McrlCarState c = carAt(0.5, FLOOR_TOP + CAR_REST, 0.5);
    c.body.vx = 20;
    s.addCar(c, -1);
    for (int t = 0; t < 1200; t++) {
        step(s.w, 1);
        const McrlCarState o = getCar(s.w, 1);
        if (t > 0) REQUIRE((o.flags & MCRL_CAR_ON_GROUND) != 0);
        if (o.body.vx < 0.1) return o.body.px - c.body.px;
    }
    FAIL("the car never stopped");
    return 0;
}

struct Bounce {
    double fall, rise;
};
Bounce drop(uint16_t id) {
    Strip s(id);
    const double y0 = FLOOR_TOP + CAR_REST + 4;
    s.addCar(carAt(0.5, y0, 0.5), 0);
    double yLand = 0;
    for (int t = 0;; t++) {
        REQUIRE(t < 240);
        step(s.w, 1);
        const McrlCarFx f = getFx(s.w, 1);
        if (f.wheels[0].lands + f.wheels[1].lands + f.wheels[2].lands + f.wheels[3].lands > 0) {
            yLand = getCar(s.w, 1).body.py;
            break;
        }
    }
    double apex = yLand;
    for (int t = 0; t < 120; t++) {
        step(s.w, 1);
        apex = std::max(apex, getCar(s.w, 1).body.py);
    }
    return {y0 - yLand, apex - yLand};
}

struct BallDrop {
    double apex = 0;
    std::vector<McrlBallState> ticks;
};
BallDrop dropBall(Strip& s, double h) {
    setBall(s.w, 0.5, FLOOR_TOP + BALL_REST + h, 0.5);
    BallDrop d;
    int after = -1;
    for (int t = 0; after < 240; t++) {
        REQUIRE(t < 600);
        step(s.w, 1);
        const McrlBallState b = getBall(s.w);
        d.ticks.push_back(b);
        if (after < 0 && b.bounces > 0) after = 0;
        if (after >= 0) {
            after++;
            d.apex = std::max(d.apex, b.body.py - (FLOOR_TOP + BALL_REST));
        }
    }
    return d;
}

bool onTriangle(const btCollisionObject* obj, int tri, const btVector3& p, float tol = 1e-3f) {
    const McrlBuilt* b = static_cast<const McrlBuilt*>(obj->getCollisionShape()->getUserPointer());
    if (!b || tri < 0 || tri >= b->tris) return false;
    const btVector3 local = p - obj->getWorldTransform().getOrigin();
    btVector3 v[3];
    for (int k = 0; k < 3; k++) {
        const int32_t i = b->idx[3 * tri + k];
        v[k] = btVector3(b->verts[3 * i], b->verts[3 * i + 1], b->verts[3 * i + 2]);
    }
    const btVector3 n = (v[1] - v[0]).cross(v[2] - v[0]).normalized();
    if (std::abs((local - v[0]).dot(n)) > tol) return false;
    for (int k = 0; k < 3; k++)
        if (n.dot((v[(k + 1) % 3] - v[k]).cross(local - v[k])) < -tol) return false;
    return true;
}

bool samePhys(const RocketSim::PhysState& a, const RocketSim::PhysState& b) {
    const RocketSim::Vec va[] = {a.pos, a.vel, a.angVel, a.rotMat.forward, a.rotMat.right, a.rotMat.up};
    const RocketSim::Vec vb[] = {b.pos, b.vel, b.angVel, b.rotMat.forward, b.rotMat.right, b.rotMat.up};
    for (int i = 0; i < 6; i++)
        if (va[i].x != vb[i].x || va[i].y != vb[i].y || va[i].z != vb[i].z) return false;
    return true;
}
}

TEST_CASE("materials: braking on ice takes 2 to 3 times stone's distance") {
    const double dStone = brakeDistance(CUBE);
    const double dIce = brakeDistance(ICE);
    CAPTURE(dStone);
    CAPTURE(dIce);
    CHECK(dStone > 1);
    CHECK(dIce / dStone >= 2.0);
    CHECK(dIce / dStone <= 3.0);
}

TEST_CASE("materials: slime pad bounces a landing car") {
    const Bounce slime = drop(SLIME);
    const Bounce stone = drop(CUBE);
    CAPTURE(slime.fall);
    CAPTURE(slime.rise);
    CAPTURE(stone.rise);
    CHECK(slime.fall > 3.5);
    CHECK(slime.rise >= 0.6 * slime.fall);
    CHECK(slime.rise <= 0.95 * slime.fall);
    CHECK(stone.rise < 0.2);

    Strip s(SLIME);
    McrlCarState c = carAt(0.5, FLOOR_TOP + CAR_REST, 0.5);
    c.body.vy = -10;
    s.addCar(c, 0);
    step(s.w, 1);
    const McrlCarState o = getCar(s.w, 1);
    REQUIRE(o.wheelContact == 0xFu);
    CHECK(getFx(s.w, 1).wheels[0].lands == 0u);
    CHECK(o.body.vy > 5);
}

TEST_CASE("materials: callback survives a later Arena::Create") {
    Strip plain(SLIME);
    const BallDrop a = dropBall(plain, 10);
    Strip s(SLIME);
    RocketSim::ArenaConfig cfg;
    cfg.useCustomBroadphase = false;
    delete RocketSim::Arena::Create(RocketSim::GameMode::THE_VOID, cfg, 120);
    CHECK(gContactAddedCallback == &RocketSim::Arena::_BulletContactAddedCallback);
    const BallDrop b = dropBall(s, 10);
    CHECK(gContactAddedCallback == &mcrl::materials::contact_added);
    CHECK(a.apex > 5);
    REQUIRE(a.ticks.size() == b.ticks.size());
    for (size_t t = 0; t < a.ticks.size(); t++) REQUIRE_MESSAGE(sameBytes(&a.ticks[t], &b.ticks[t], sizeof a.ticks[t]), t);
}

TEST_CASE("materials: untagged shapes and extras off are untouched") {
    using namespace RocketSim;
    {
        McrlShapes* shapes = makeShapes();
        addIce(shapes);
        registerMaterial(shapes, ICE_MAT, ICE_VALUES);
        const Scene sc = Scene().fill(0, 0, 0, 15, 0, 15, ICE);
        struct Raw {
            McrlBuilt* built;
            Arena* arena;
            Car* car;
        } raw[2];
        for (Raw& r : raw) {
            r.built = sc.build(shapes, 0, 0, 0);
            ArenaConfig cfg;
            cfg.useCustomBroadphase = false;
            r.arena = Arena::Create(GameMode::THE_VOID, cfg, 120);
            r.arena->_AddStaticCollisionShape(r.built->shape.get());
            r.car = r.arena->AddCar(Team::BLUE, CAR_CONFIG_OCTANE);
            CarState cs;
            cs.pos = Vec(100, 400, 50 + 17);
            r.car->SetState(cs);
            r.car->controls.throttle = 1;
            BallState bs;
            bs.pos = Vec(500, 400, 50 + 93.15f);
            r.arena->ball->SetState(bs);
        }
        bool hit = false;
        int grounded = 0;
        for (int t = 0; t < 300; t++) {
            gContactAddedCallback = &Arena::_BulletContactAddedCallback;
            raw[0].arena->Step(1);
            gContactAddedCallback = &mcrl::materials::contact_added;
            raw[1].arena->Step(1);
            REQUIRE_MESSAGE(samePhys(raw[0].car->GetState(), raw[1].car->GetState()), t);
            REQUIRE_MESSAGE(samePhys(raw[0].arena->ball->GetState(), raw[1].arena->ball->GetState()), t);
            hit = hit || raw[0].car->GetState().ballHitInfo.isValid;
            grounded += raw[0].car->GetState().isOnGround ? 1 : 0;
        }
        CHECK(hit);
        CHECK(grounded > 100);
        for (Raw& r : raw) {
            delete r.arena;
            CHECK(mcrl_built_free(r.built) == MCRL_OK);
        }
        CHECK(mcrl_shapes_destroy(shapes) == MCRL_OK);
    }
    Strip stone(CUBE, 0), ice(ICE, 0);
    for (Strip* s : {&stone, &ice}) {
        s->addCar(carAt(0.5, FLOOR_TOP + CAR_REST, 0.5), 1);
        setBall(s->w, 8.5, FLOOR_TOP + BALL_REST, 0.5);
    }
    for (int t = 0; t < 300; t++) {
        step(stone.w, 1);
        step(ice.w, 1);
        const McrlCarState a = getCar(stone.w, 1), b = getCar(ice.w, 1);
        const McrlBallState ba = getBall(stone.w), bb = getBall(ice.w);
        REQUIRE_MESSAGE(sameBytes(&a, &b, sizeof a), t);
        REQUIRE_MESSAGE(sameBytes(&ba, &bb, sizeof ba), t);
    }
    CHECK(getCar(ice.w, 1).ballHits > 0u);
}

TEST_CASE("materials: a stale or foreign hit reads material 0") {
    {
        Strip ice(ICE);
        ice.addCar(carAt(0.5, FLOOR_TOP + CAR_REST, 0.5), 1);
        step(ice.w, 1);
        const McrlWorld& w = *ice.w;
        const McrlMaterial& none = w.materials[0];
        const McrlMaterial def{1, 1, 0, 1, -1, 1, -1, 0, 0, 0};
        CHECK(sameBytes(&none, &def, sizeof def));
        const mcrl::StepRaycaster::WheelHit& h = ice.ray().hits[0];
        REQUIRE(h.obj != nullptr);
        REQUIRE(h.tri >= 0);
        const McrlBuilt* b = static_cast<const McrlBuilt*>(h.obj->getCollisionShape()->getUserPointer());
        REQUIRE(b != nullptr);
        CHECK(b->world == ice.w);
        CHECK(&mcrl::materials::of(w, h.obj, h.tri) == &w.materials[ICE_MAT]);
        CHECK(&mcrl::materials::of(w, h.obj, (int)b->triMat.size()) == &none);
        CHECK(&mcrl::materials::of(w, h.obj, -1) == &none);
        CHECK(&mcrl::materials::of(w, &ice.ray().dummy, 0) == &none);
        CHECK(&mcrl::materials::of(w, &w.cars.at(1)->_rigidBody, 0) == &none);
        CHECK(&mcrl::materials::of(w, nullptr, 0) == &none);
    }
    Strip stone(CUBE), ice(ICE);
    constexpr double LIFT = 25 * UU;
    const double rect[4] = {-3, -2, 4, 3};
    for (Strip* s : {&stone, &ice}) {
        s->addCar(carAt(0.5, FLOOR_TOP + LIFT + CAR_REST, 0.5), 1);
        REQUIRE(mcrl::testing::forceFloor(*s->w, 1, FLOOR_TOP + LIFT, rect));
        step(s->w, 1);
        REQUIRE(getCar(s->w, 1).wheelContact == 0xFu);
    }
    for (int i = 0; i < 4; i++) {
        CAPTURE(i);
        CHECK(ice.ray().hits[i].obj == &ice.ray().dummy);
        CHECK(ice.ray().hits[i].tri == -1);
        CHECK(ice.wheel(i).m_latFriction == stone.wheel(i).m_latFriction);
        CHECK(ice.wheel(i).m_longFriction == stone.wheel(i).m_longFriction);
    }
}

TEST_CASE("materials: set_material errors") {
    CHECK(sizeof(McrlMaterial) == 40);
    CHECK(offsetof(McrlMaterial, padRestitution) == 32);
    CHECK(mcrl_sizeof(9) == 40);
    CHECK(mcrl_sizeof(10) == (int32_t)sizeof(McrlWetFx));

    McrlShapes* s = makeShapes();
    const McrlMaterial m = ICE_VALUES;
    CHECK(mcrl_shapes_set_material(s, 0, &m) == MCRL_ERR_ARG);
    CHECK(mcrl_shapes_set_material(s, 256, &m) == MCRL_ERR_ARG);
    CHECK(mcrl_shapes_set_material(s, -1, &m) == MCRL_ERR_ARG);
    CHECK(mcrl_shapes_set_material(s, 1, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_shapes_set_material(nullptr, 1, &m) == MCRL_ERR_ARG);
    for (const auto& [field, bad] : std::vector<std::pair<float McrlMaterial::*, float>>{
             {&McrlMaterial::wheelLat, -0.1f},           {&McrlMaterial::wheelLong, -0.1f},
             {&McrlMaterial::chassisFriction, -0.1f},    {&McrlMaterial::ballFriction, -0.1f},
             {&McrlMaterial::wheelDrag, -1.f},           {&McrlMaterial::ballDrag, -1.f},
             {&McrlMaterial::chassisRestitution, 1.01f}, {&McrlMaterial::ballRestitution, 1.01f},
             {&McrlMaterial::padRestitution, -0.1f},     {&McrlMaterial::padRestitution, 1.01f}}) {
        McrlMaterial x = m;
        x.*field = bad;
        CHECK(mcrl_shapes_set_material(s, 1, &x) == MCRL_ERR_ARG);
    }
    McrlMaterial edge{0, 0, 0, 0, -5, 0, 1, 0, 1, 0};
    CHECK(mcrl_shapes_set_material(s, 254, &edge) == MCRL_OK);
    CHECK(s->materialsVersion.load() == 1);
    edge.padRestitution = 0;
    edge.chassisRestitution = 1;
    CHECK(mcrl_shapes_set_material(s, 254, &edge) == MCRL_OK);
    using M = McrlMaterial;
    float M::* const fields[] = {&M::wheelLat, &M::wheelLong, &M::wheelDrag, &M::chassisFriction,
                                 &M::chassisRestitution, &M::ballFriction, &M::ballRestitution, &M::ballDrag,
                                 &M::padRestitution};
    for (int f = 0; f < 9; f++) {
        CAPTURE(f);
        for (const float bad : {std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
            McrlMaterial x = m;
            x.*fields[f] = bad;
            CHECK(mcrl_shapes_set_material(s, 1, &x) == MCRL_ERR_ARG);
        }
    }
    CHECK(s->materials[1].wheelLat == 1.f);
    CHECK(mcrl_shapes_set_material(s, 255, &m) == MCRL_OK);
    CHECK(s->materialsVersion.load() == 3);
    CHECK(sameBytes(&s->materials[255], &m, sizeof m));

    CHECK(mcrl_world_set_extras(nullptr, MCRL_EXTRAS_SURFACES) == MCRL_ERR_ARG);
    McrlWorld* w = makeWorld(s);
    CHECK(w->extras == 0u);
    int32_t rc = 0;
    std::thread t([&] { rc = mcrl_world_set_extras(w, MCRL_EXTRAS_SURFACES); });
    t.join();
    CHECK(rc == MCRL_ERR_THREAD);
    CHECK(w->extras == 0u);
    CHECK(mcrl_world_set_extras(w, MCRL_EXTRAS_SURFACES) == MCRL_OK);
    CHECK(w->extras == (uint32_t)MCRL_EXTRAS_SURFACES);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("materials: each contact reads its own triangle") {
    using namespace RocketSim;
    Strip s(mixedStrip(), MCRL_EXTRAS_SURFACES);
    McrlCarState c = carAt(8.5, FLOOR_TOP + 0.67, 8.5);
    c.body.qw = 0;
    c.body.qx = 1;
    c.body.vx = 10;
    s.addCar(c, 0);
    setBall(s.w, 3.5, FLOOR_TOP + BALL_REST, 12.5);
    step(s.w, 10);
    const McrlWorld& w = *s.w;
    const btCollisionObject* carBody = &w.cars.at(1)->_rigidBody;
    const btCollisionObject* ballBody = &w.arena->ball->_rigidBody;
    const float carWorld = w.arena->GetMutatorConfig().carWorldFriction;
    int carPoints = 0, ballPoints = 0;
    btCollisionDispatcher* d = w.arena->_bulletWorld.getDispatcher();
    for (int i = 0; i < d->getNumManifolds(); i++) {
        const btPersistentManifold& m = *d->getManifoldByIndexInternal(i);
        const btCollisionObject* b0 = m.getBody0();
        const btCollisionObject* b1 = m.getBody1();
        const int side = b0->getCollisionShape()->getUserIndex() == mcrl::materials::TERRAIN_TAG ? 0
                         : b1->getCollisionShape()->getUserIndex() == mcrl::materials::TERRAIN_TAG ? 1
                                                                                                    : -1;
        if (side < 0) continue;
        const btCollisionObject* terrain = side == 0 ? b0 : b1;
        const btCollisionObject* other = side == 0 ? b1 : b0;
        if (other != carBody && other != ballBody) continue;
        for (int k = 0; k < m.getNumContacts(); k++) {
            const btManifoldPoint& pt = m.getContactPoint(k);
            const int tri = side == 0 ? pt.m_index0 : pt.m_index1;
            const btVector3 onTerrain = side == 0 ? pt.getPositionWorldOnA() : pt.getPositionWorldOnB();
            CAPTURE(tri);
            CHECK(onTriangle(terrain, tri, onTerrain, 0.02f));
            const McrlMaterial& mat = mcrl::materials::of(w, terrain, tri);
            CHECK(&mat == &w.materials[ICE_MAT]);
            if (other == carBody) {
                carPoints++;
                CHECK(pt.m_combinedFriction == carWorld * mat.chassisFriction);
            } else {
                ballPoints++;
                CHECK(pt.m_combinedFriction ==
                      gCalculateCombinedFrictionCallback(b0, b1) * mat.ballFriction);
            }
        }
    }
    CHECK(carPoints > 0);
    CHECK(ballPoints > 0);
}
