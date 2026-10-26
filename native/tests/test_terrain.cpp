#include <doctest/doctest.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <random>
#include <string>
#include <thread>
#include <vector>
#include "mcrl.h"
#include "terrain.h"
#include "world.h"

namespace {
constexpr int N = 18;
enum : uint16_t { CUBE = 1, SLAB = 2 };

McrlShapes* makeShapes() {
    McrlShapes* s = mcrl_shapes_create();
    REQUIRE(s != nullptr);
    const McrlBoxQ cube{{0, 0, 0}, {4096, 4096, 4096}};
    const McrlBoxQ slab{{0, 0, 0}, {4096, 2048, 4096}};
    REQUIRE(mcrl_shapes_register(s, &cube, 1, 0) == CUBE);
    REQUIRE(mcrl_shapes_register(s, &slab, 1, 0) == SLAB);
    return s;
}

McrlWorld* makeWorld(McrlShapes* s, int32_t ox = 0, int32_t oy = 0, int32_t oz = 0) {
    REQUIRE(mcrl_init() == MCRL_OK);
    McrlWorld* w = mcrl_world_create(s, ox, oy, oz);
    REQUIRE(w != nullptr);
    return w;
}

McrlBuilt* buildCells(McrlShapes* s, const std::vector<uint16_t>& cells, int32_t sx, int32_t sy, int32_t sz,
                      uint32_t version) {
    McrlSectionInput in{sx, sy, sz, version, 1, 0, cells.data()};
    McrlBuilt* b = mcrl_section_build(s, &in, nullptr);
    REQUIRE_MESSAGE(b != nullptr, mcrl_last_error());
    return b;
}

McrlBuilt* floorAt(McrlShapes* s, int32_t sx, int32_t sy, int32_t sz, uint32_t version = 1, int localY = 15) {
    std::vector<uint16_t> cells(N * N * N, 0);
    for (int x = 0; x < 16; x++)
        for (int z = 0; z < 16; z++) cells[((localY + 1) * N + (z + 1)) * N + (x + 1)] = CUBE;
    return buildCells(s, cells, sx, sy, sz, version);
}

McrlBuilt* emptyAt(McrlShapes* s, int32_t sx, int32_t sy, int32_t sz, uint32_t version = 1) {
    return buildCells(s, std::vector<uint16_t>(N * N * N, 0), sx, sy, sz, version);
}

McrlCarState carAt(double x, double y, double z) {
    McrlCarState c{};
    c.body.px = x; c.body.py = y; c.body.pz = z;
    c.body.qw = 1;
    return c;
}

McrlCarState getCar(McrlWorld* w, int32_t id) {
    McrlCarState s{};
    REQUIRE(mcrl_car_get_state(w, id, &s) == MCRL_OK);
    return s;
}

McrlBallState getBall(McrlWorld* w) {
    McrlBallState b{};
    REQUIRE(mcrl_ball_get_state(w, &b) == MCRL_OK);
    return b;
}

void setBall(McrlWorld* w, double x, double y, double z) {
    McrlBallState b{};
    b.body.px = x; b.body.py = y; b.body.pz = z;
    b.body.qw = 1;
    REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
}

double speed(const McrlBody& b) { return std::sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz); }

int numObjects(McrlWorld* w) { return w->arena->_bulletWorld.getNumCollisionObjects(); }
}

TEST_CASE("commit_then_ready") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s, 16, 0, -32);
    const int base = numObjects(w);
    CHECK(mcrl_section_is_ready(w, 2, 2, 1) == 0);
    REQUIRE(mcrl_section_commit(w, floorAt(s, 2, 2, 1)) == MCRL_OK);
    CHECK(mcrl_section_is_ready(w, 2, 2, 1) == 1);
    CHECK(mcrl_section_is_ready(w, 2, 3, 1) == 0);
    CHECK(mcrl_section_is_ready(w, 1, 2, 2) == 0);
    CHECK(numObjects(w) == base + 1);

    const btRigidBody* rb = w->terrain->body({2, 2, 1});
    REQUIRE(rb != nullptr);
    CHECK(rb->isStaticObject());
    CHECK(rb->getInvMass() == 0);
    CHECK(rb->getUserPointer() == (void*)w->arena);
    CHECK(rb->getUserIndex() == -1);
    CHECK(rb->getFriction() == doctest::Approx(0.6));
    CHECK(rb->getRestitution() == doctest::Approx(0.3));
    CHECK(rb->getRollingFriction() == 0);
    REQUIRE(rb->getBroadphaseHandle() != nullptr);
    CHECK(rb->getBroadphaseHandle()->m_collisionFilterGroup == btBroadphaseProxy::StaticFilter);
    CHECK(rb->getBroadphaseHandle()->m_collisionFilterMask ==
          (btBroadphaseProxy::AllFilter ^ btBroadphaseProxy::StaticFilter));
    const btTransform& tr = rb->getWorldTransform();
    CHECK(tr.getOrigin().x() == 16);
    CHECK(tr.getOrigin().y() == 48);
    CHECK(tr.getOrigin().z() == 32);
    CHECK(tr.getBasis() == btMatrix3x3::getIdentity());
    const auto* shape = dynamic_cast<const btBvhTriangleMeshShape*>(rb->getCollisionShape());
    REQUIRE(shape != nullptr);
    CHECK(shape->getTriangleInfoMap() != nullptr);

    REQUIRE(mcrl_section_commit(w, emptyAt(s, 2, 3, 1)) == MCRL_OK);
    CHECK(mcrl_section_is_ready(w, 2, 3, 1) == 1);
    CHECK(w->terrain->body({2, 3, 1}) == nullptr);
    CHECK(numObjects(w) == base + 1);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("stale_commit") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    const int base = numObjects(w);
    REQUIRE(mcrl_section_commit(w, floorAt(s, 0, 3, 0, 2)) == MCRL_OK);
    const btRigidBody* rb = w->terrain->body({0, 3, 0});
    REQUIRE(rb != nullptr);
    CHECK(mcrl_section_commit(w, emptyAt(s, 0, 3, 0, 1)) == MCRL_STALE);
    CHECK(w->terrain->body({0, 3, 0}) == rb);
    CHECK(mcrl_section_is_ready(w, 0, 3, 0) == 1);
    CHECK(numObjects(w) == base + 1);
    CHECK(mcrl_section_commit(w, floorAt(s, 0, 3, 0, 2)) == MCRL_OK);
    CHECK(w->terrain->body({0, 3, 0}) != nullptr);
    CHECK(numObjects(w) == base + 1);
    CHECK(mcrl_section_commit(w, emptyAt(s, 0, 3, 0, 3)) == MCRL_OK);
    CHECK(w->terrain->body({0, 3, 0}) == nullptr);
    CHECK(mcrl_section_is_ready(w, 0, 3, 0) == 1);
    CHECK(numObjects(w) == base);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("remove_clears_ready") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    const int base = numObjects(w);
    REQUIRE(mcrl_section_commit(w, floorAt(s, -1, 3, 7, 5)) == MCRL_OK);
    CHECK(numObjects(w) == base + 1);
    CHECK(mcrl_section_remove(w, -1, 3, 7) == MCRL_OK);
    CHECK(mcrl_section_is_ready(w, -1, 3, 7) == 0);
    CHECK(w->terrain->body({-1, 3, 7}) == nullptr);
    CHECK(numObjects(w) == base);
    CHECK(mcrl_section_remove(w, -1, 3, 7) == MCRL_OK);
    CHECK(mcrl_section_commit(w, floorAt(s, -1, 3, 7, 1)) == MCRL_OK);
    CHECK(mcrl_section_is_ready(w, -1, 3, 7) == 1);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("frozen_until_box_sections_ready") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    REQUIRE(mcrl_world_set_spawn_gate(w, 1) == MCRL_OK);
    McrlCarState c = carAt(15.5, 66, 8);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    REQUIRE(mcrl_section_commit(w, floorAt(s, 0, 3, 0)) == MCRL_OK);
    REQUIRE(mcrl_section_commit(w, floorAt(s, 1, 3, 0)) == MCRL_OK);
    REQUIRE(mcrl_section_commit(w, emptyAt(s, 0, 4, 0)) == MCRL_OK);
    const McrlCarState before = getCar(w, 1);
    uint32_t flags = 0;
    CHECK(mcrl_step(w, 6, &flags) == 0);
    CHECK(flags == MCRL_STEP_FROZEN);
    const McrlCarState after = getCar(w, 1);
    CHECK(std::memcmp(&before.body, &after.body, sizeof before.body) == 0);

    REQUIRE(mcrl_section_commit(w, emptyAt(s, 1, 4, 0)) == MCRL_OK);
    CHECK(mcrl_step(w, 6, &flags) == 6);
    CHECK(flags == MCRL_STEP_BALL_PARKED);
    CHECK(getCar(w, 1).body.py < 66);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("ball_touch_no_crash") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s, 16, 0, -32);
    REQUIRE(mcrl_section_commit(w, floorAt(s, 0, 3, 0)) == MCRL_OK);
    setBall(w, 8, 70, 8);
    REQUIRE(mcrl_step(w, 720, nullptr) == 720);
    const McrlBallState b = getBall(w);
    CHECK(b.body.py == doctest::Approx(65.86).epsilon(0.06 / 65.86));
    CHECK(b.body.px == doctest::Approx(8).epsilon(0.01));
    CHECK(b.body.pz == doctest::Approx(8).epsilon(0.01));
    CHECK(speed(b.body) < 0.05);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("remove_under_resting_car") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    REQUIRE(mcrl_section_commit(w, floorAt(s, 0, 3, 0)) == MCRL_OK);
    McrlCarState c = carAt(8, 64.5, 8);
    REQUIRE(mcrl_car_add(w, 1, 0, 0, &c) == MCRL_OK);
    REQUIRE(mcrl_step(w, 240, nullptr) == 240);
    McrlCarState o = getCar(w, 1);
    CHECK((o.flags & MCRL_CAR_ON_GROUND) != 0);
    CHECK(o.body.py == doctest::Approx(64.34).epsilon(0.03 / 64.34));
    CHECK(speed(o.body) < 0.05);

    REQUIRE(mcrl_section_remove(w, 0, 3, 0) == MCRL_OK);
    REQUIRE(mcrl_step(w, 60, nullptr) == 60);
    o = getCar(w, 1);
    CHECK(o.body.py < 63.5);
    CHECK(o.body.vy < -3);
    CHECK((o.flags & MCRL_CAR_ON_GROUND) == 0);

    REQUIRE(mcrl_section_commit(w, floorAt(s, 0, 3, 0, 2)) == MCRL_OK);
    CHECK(mcrl_step(w, 60, nullptr) == 60);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("terrain_owner_thread_and_args") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = makeWorld(s);
    int32_t rcCommit = 0, rcRemove = 0, rcReady = 0, rcGate = 0;
    std::thread t([&] {
        rcCommit = mcrl_section_commit(w, emptyAt(s, 0, 0, 0));
        rcRemove = mcrl_section_remove(w, 0, 0, 0);
        rcReady = mcrl_section_is_ready(w, 0, 0, 0);
        rcGate = mcrl_world_set_spawn_gate(w, 1);
    });
    t.join();
    CHECK(rcCommit == MCRL_ERR_THREAD);
    CHECK(rcRemove == MCRL_ERR_THREAD);
    CHECK(rcReady == MCRL_ERR_THREAD);
    CHECK(rcGate == MCRL_ERR_THREAD);
    CHECK(mcrl_section_is_ready(w, 0, 0, 0) == 0);

    CHECK(mcrl_section_commit(nullptr, emptyAt(s, 0, 0, 0)) == MCRL_ERR_ARG);
    CHECK(mcrl_section_commit(w, nullptr) == MCRL_ERR_ARG);
    CHECK(mcrl_section_remove(nullptr, 0, 0, 0) == MCRL_ERR_ARG);
    CHECK(mcrl_section_is_ready(nullptr, 0, 0, 0) == MCRL_ERR_ARG);
    CHECK(mcrl_world_set_spawn_gate(nullptr, 1) == MCRL_ERR_ARG);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("build_from_worker_threads") {
    McrlShapes* s = makeShapes();
    constexpr int THREADS = 4, BUILDS = 50, NEW_SHAPES = 20;
    std::atomic<bool> go{false};
    std::vector<std::vector<McrlBuilt*>> built(THREADS);
    std::vector<std::string> errors(THREADS);
    std::vector<int> maxIdSeen(THREADS, 0);
    std::vector<std::thread> workers;
    for (int t = 0; t < THREADS; t++) {
        workers.emplace_back([&, t] {
            std::mt19937 rng(100 + t);
            while (!go.load()) std::this_thread::yield();
            std::vector<uint16_t> cells(N * N * N);
            for (int i = 0; i < BUILDS; i++) {
                const int32_t count = mcrl_shapes_count(s);
                for (uint16_t& c : cells) {
                    const uint32_t r = rng() % 100;
                    c = r < 94 ? 0 : r < 95 ? 0xFFFF : (uint16_t)(1 + rng() % (count - 1));
                    if (c != 0xFFFF) maxIdSeen[t] = std::max(maxIdSeen[t], (int)c);
                }
                McrlSectionInput in{t, 0, i, 1, 1, 0, cells.data()};
                McrlBuildStats st{};
                McrlBuilt* b = mcrl_section_build(s, &in, &st);
                if (!b) { errors[t] = mcrl_last_error(); return; }
                if (st.tris != b->tris) { errors[t] = "stats mismatch"; built[t].push_back(b); return; }
                built[t].push_back(b);
            }
        });
    }
    go = true;
    std::mt19937 rng(7);
    for (int k = 0; k < NEW_SHAPES; k++) {
        McrlBoxQ boxes[3];
        const int n = 1 + (int)(rng() % 3);
        for (int i = 0; i < n; i++)
            for (int a = 0; a < 3; a++) {
                boxes[i].mn[a] = (int16_t)((int)(rng() % 6144) - 2048);
                boxes[i].mx[a] = (int16_t)(boxes[i].mn[a] + 1 + (int)(rng() % 2048));
            }
        CHECK(mcrl_shapes_register(s, boxes, n, 0) == 3 + k);
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    for (auto& th : workers) th.join();
    CHECK(mcrl_shapes_count(s) == 3 + NEW_SHAPES);
    for (int t = 0; t < THREADS; t++) {
        CHECK_MESSAGE(errors[t] == "", "thread ", t, ": ", errors[t]);
        CHECK(built[t].size() == (size_t)BUILDS);
    }
    int maxId = 0;
    for (int m : maxIdSeen) maxId = std::max(maxId, m);
    CHECK(maxId > 2);

    McrlWorld* w = makeWorld(s);
    for (int t = 0; t < THREADS; t++)
        for (McrlBuilt* b : built[t]) CHECK(mcrl_section_commit(w, b) == MCRL_OK);
    CHECK(mcrl_section_is_ready(w, 3, 0, 49) == 1);
    setBall(w, 24, 10, 24);
    CHECK(mcrl_step(w, 60, nullptr) == 60);
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
