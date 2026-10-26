#include <doctest/doctest.h>
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <limits>
#include <numbers>
#include <string>
#include <vector>
#include "BulletCollision/CollisionDispatch/btCollisionDispatcher.h"
#include "BulletCollision/NarrowPhaseCollision/btPersistentManifold.h"
#include "mcrl.h"
#include "stepassist.h"
#include "stepfloor.h"
#include "terrain_fixtures.h"
#include "world.h"

using namespace fx;
namespace sa = mcrl::stepassist;
using sa::Outcome;

namespace {
constexpr double FLOOR_TOP = 64;
constexpr double UU = 1.0 / 50;
constexpr double CAR_REST = 0.34;
constexpr int RISER = 20;
constexpr int Z0 = -16, Z1 = 31;

const RocketSim::CarConfig& octane() { return RocketSim::CAR_CONFIG_OCTANE; }
double hbFront() { return (octane().hitboxPosOffset.x + octane().hitboxSize.x / 2) * UU; }
double hbBack() { return (octane().hitboxSize.x / 2 - octane().hitboxPosOffset.x) * UU; }
double hbHalfW() { return octane().hitboxSize.y / 2 * UU; }

enum : uint16_t { STAIRS_E = CARPET + 1, HALF_E, TOP_SLAB, PANE_Z, FENCE_Z, ROD_Y };

McrlShapes* makeStepShapes() {
    McrlShapes* s = makeShapes();
    const McrlBoxQ stairs[2] = {{{0, 0, 0}, {4096, 2048, 4096}}, {{2048, 2048, 0}, {4096, 4096, 4096}}};
    REQUIRE(mcrl_shapes_register(s, stairs, 2, 0) == STAIRS_E);
    const McrlBoxQ half{{2048, 0, 0}, {4096, 4096, 4096}};
    REQUIRE(mcrl_shapes_register(s, &half, 1, 0) == HALF_E);
    const McrlBoxQ top{{0, 2048, 0}, {4096, 4096, 4096}};
    REQUIRE(mcrl_shapes_register(s, &top, 1, 0) == TOP_SLAB);
    const McrlBoxQ pane{{1792, 0, 0}, {2304, 4096, 4096}};
    REQUIRE(mcrl_shapes_register(s, &pane, 1, MCRL_SHAPE_THIN) == PANE_Z);
    const McrlBoxQ fence[2] = {{{1536, 0, 1536}, {2560, 6144, 2560}}, {{1792, 0, 0}, {2304, 6144, 4096}}};
    REQUIRE(mcrl_shapes_register(s, fence, 2, MCRL_SHAPE_THIN | MCRL_SHAPE_NONFLOOR) == FENCE_Z);
    const McrlBoxQ rod{{1536, 0, 1536}, {2560, 4096, 2560}};
    REQUIRE(mcrl_shapes_register(s, &rod, 1, MCRL_SHAPE_THIN) == ROD_Y);
    return s;
}

enum class Course { FLAT, SLAB, STAIRS, BLOCK, TERRACES, SHORT_TREAD };

struct CourseInfo {
    const char* name;
    double top;
    double lastRiser;
};

CourseInfo info(Course c) {
    switch (c) {
        case Course::FLAT: return {"flat", 64, 0};
        case Course::SLAB: return {"bottom slab", 64.5, 20};
        case Course::STAIRS: return {"0.5/0.5 stairs", 70, 25.5};
        case Course::BLOCK: return {"block", 65, 20};
        case Course::TERRACES: return {"1/1 terraces", 69, 24};
        case Course::SHORT_TREAD: return {"short-tread start", 69, 23.5};
    }
    return {"?", 0, 0};
}

Scene courseScene(Course c) {
    Scene sc = Scene().fill(0, 63, Z0, 47, 63, Z1, CUBE);
    switch (c) {
        case Course::FLAT: break;
        case Course::SLAB: sc.fill(RISER, 64, Z0, 47, 64, Z1, SLAB); break;
        case Course::STAIRS:
            for (int k = 0; k < 6; k++) {
                if (k > 0) sc.fill(RISER + k, 64, Z0, RISER + k, 64 + k - 1, Z1, CUBE);
                sc.fill(RISER + k, 64 + k, Z0, RISER + k, 64 + k, Z1, STAIRS_E);
            }
            sc.fill(RISER + 6, 64, Z0, 47, 69, Z1, CUBE);
            break;
        case Course::BLOCK: sc.fill(RISER, 64, Z0, 47, 64, Z1, CUBE); break;
        case Course::TERRACES:
            for (int k = 0; k < 4; k++) sc.fill(RISER + k, 64, Z0, RISER + k, 64 + k, Z1, CUBE);
            sc.fill(RISER + 4, 64, Z0, 47, 68, Z1, CUBE);
            break;
        case Course::SHORT_TREAD:
            for (int k = 0; k < 4; k++) {
                sc.fill(RISER + k, 64, Z0, RISER + k, 64 + k, Z1, CUBE);
                sc.fill(RISER + k, 65 + k, Z0, RISER + k, 65 + k, Z1, HALF_E);
            }
            sc.fill(RISER + 4, 64, Z0, 47, 68, Z1, CUBE);
            break;
    }
    return sc;
}

struct World {
    McrlWorld* w = nullptr;
    int32_t nextId = 1;
    explicit World(const McrlShapes* s, const Scene& sc, int32_t ox = 0, int32_t oy = 0, int32_t oz = 0) {
        w = makeWorld(s, ox, oy, oz);
        sc.commitRange(w, s, 0, 3, -1, 2, 4, 1);
    }
    ~World() { CHECK(mcrl_world_destroy(w) == MCRL_OK); }
    World(const World&) = delete;
    World& operator=(const World&) = delete;
};

RocketSim::Car* carOf(McrlWorld* w, int32_t id) { return w->cars.at(id); }
mcrl::VirtualFloor& floorOf(McrlWorld* w, int32_t id) { return w->stepRays.at(id)->floor; }
const McrlCarEvents& eventsOf(McrlWorld* w, int32_t id) { return w->carEvents.at(id); }
int wheels(const McrlCarState& c) { return std::popcount(c.wheelContact); }
double hspeed(const McrlBody& b) { return std::hypot(b.vx, b.vz); }

int riserContacts(McrlWorld* w, int32_t id) {
    RocketSim::Car* car = carOf(w, id);
    btCollisionDispatcher* d = w->arena->_bulletWorld.getDispatcher();
    int n = 0;
    for (int i = 0; i < d->getNumManifolds(); i++) {
        const btPersistentManifold* m = d->getManifoldByIndexInternal(i);
        const btCollisionObject* other = m->getBody0() == &car->_rigidBody   ? m->getBody1()
                                         : m->getBody1() == &car->_rigidBody ? m->getBody0()
                                                                             : nullptr;
        if (!other || !other->isStaticObject()) continue;
        for (int j = 0; j < m->getNumContacts(); j++) {
            const btManifoldPoint& p = m->getContactPoint(j);
            if (p.getDistance() > 0 && p.getAppliedImpulse() <= 0) continue;
            if (std::abs(p.m_normalWorldOnB.z()) < 0.3) n++;
        }
    }
    const auto& wc = car->_internalState.worldContact;
    if (wc.hasContact && std::abs(wc.contactNormal.z) < 0.3) n++;
    return n;
}

struct Approach {
    double v0;
    bool reverse;
    double angleDeg;
};

struct Placement {
    double yaw, lead, trail;
};

Placement place(const Approach& a) {
    Placement p;
    p.yaw = a.reverse ? 180 - a.angleDeg : -a.angleDeg;
    const double phi = p.yaw * std::numbers::pi / 180;
    const double hx = std::cos(phi), rx = std::sin(phi);
    p.lead = -1e9;
    p.trail = 1e9;
    for (double f : {-hbBack(), hbFront()})
        for (double r : {-hbHalfW(), hbHalfW()}) {
            p.lead = std::max(p.lead, f * hx + r * rx);
            p.trail = std::min(p.trail, f * hx + r * rx);
        }
    return p;
}

double startZ(const Approach& a) { return a.angleDeg > 45 ? -12 : a.angleDeg > 0 ? -2 : 8.5; }

McrlCarState addApproaching(McrlWorld* w, int32_t id, const Approach& a, double gap = -1, double riser = RISER,
                            double ground = FLOOR_TOP, double z = std::numeric_limits<double>::quiet_NaN()) {
    const Placement p = place(a);
    if (gap < 0) gap = std::max(3 * UU, 3 * a.v0 / 120);
    const double th = a.angleDeg * std::numbers::pi / 180;
    McrlCarState c = carAt(riser - gap - p.lead, ground + CAR_REST, std::isnan(z) ? startZ(a) : z, p.yaw);
    c.body.vx = a.v0 * std::cos(th);
    c.body.vz = a.v0 * std::sin(th);
    REQUIRE(mcrl_car_add(w, id, 0, 0, &c) == MCRL_OK);
    setControls(w, id, a.reverse ? -1.f : 1.f, 0, !a.reverse && a.v0 >= 46);
    return c;
}

struct Run {
    int ticks = 0;
    int minWheels = 4, groundDrops = 0, riserTicks = 0, snaps = 0, floorTicks = 0, firstSnap = -1;
    double maxVy = -1e9, sumDz = 0;
    double vAfter = 0;
    bool done = false, reachedTop = false;
    bool jumpPressed = false, jumpFirst = false, floorAtJump = false;
    std::vector<double> vh;
    McrlCarState end{};
};

Run drive(McrlWorld* w, int32_t id, const Approach& a, const CourseInfo& ci, bool jumpAfterSnap = false,
          int maxTicks = 720) {
    const Placement p = place(a);
    Run r;
    McrlCarState prev = getCar(w, id);
    int after = -1;
    for (int t = 1; t <= maxTicks; t++) {
        if (jumpAfterSnap && r.firstSnap > 0 && !r.jumpPressed) {
            r.floorAtJump = floorOf(w, id).active;
            setControls(w, id, a.reverse ? -1.f : 1.f, 0, !a.reverse && a.v0 >= 46, true);
            r.jumpPressed = true;
            after = 3;
            step(w, 1);
            const McrlCarState o = getCar(w, id);
            r.jumpFirst = (o.flags & MCRL_CAR_JUMPED) && !(o.flags & MCRL_CAR_DOUBLE_JUMPED) &&
                          !(o.flags & MCRL_CAR_FLIPPED);
            r.ticks = t;
            prev = o;
            continue;
        }
        step(w, 1);
        r.ticks = t;
        const McrlCarState o = getCar(w, id);
        r.vh.push_back(hspeed(o.body));
        if (!r.jumpPressed) {
            r.minWheels = std::min(r.minWheels, wheels(o));
            if (!(o.flags & MCRL_CAR_ON_GROUND)) r.groundDrops++;
            r.maxVy = std::max(r.maxVy, o.body.vy);
            if (riserContacts(w, id) > 0) r.riserTicks++;
        }
        if (floorOf(w, id).active) r.floorTicks++;
        if (o.stepDz > 0) {
            if (r.firstSnap < 0) r.firstSnap = t;
            r.snaps++;
            r.sumDz += o.stepDz;
        }
        prev = o;
        if (after < 0 && o.body.px + p.trail >= ci.lastRiser + 0.5) after = 12;
        if (after >= 0 && after-- == 0) {
            r.done = true;
            break;
        }
    }
    r.end = getCar(w, id);
    r.vAfter = hspeed(r.end.body);
    r.reachedTop = r.done && std::abs(r.end.body.py - ci.top - CAR_REST) < 0.05 &&
                   (r.end.flags & MCRL_CAR_ON_GROUND) && r.end.body.px + p.trail > ci.lastRiser;
    return r;
}

std::vector<double> twinSpeeds(McrlWorld* flat, int32_t id, const Approach& a, int ticks) {
    addApproaching(flat, id, a);
    std::vector<double> v;
    for (int t = 0; t < ticks; t++) {
        step(flat, 1);
        v.push_back(hspeed(getCar(flat, id).body));
    }
    REQUIRE(mcrl_car_remove(flat, id) == MCRL_OK);
    return v;
}

void climbCase(World& course, World& flat, Course c, const Approach& a, bool fullStep) {
    const CourseInfo ci = info(c);
    CAPTURE(ci.name);
    CAPTURE(a.v0);
    CAPTURE(a.reverse);
    CAPTURE(a.angleDeg);
    CAPTURE(fullStep);
    REQUIRE(mcrl_world_set_step_up(course.w, fullStep ? 1 : 0) == MCRL_OK);
    const int32_t id = course.nextId++;
    addApproaching(course.w, id, a);
    const Run r = drive(course.w, id, a, ci);
    REQUIRE(mcrl_car_remove(course.w, id) == MCRL_OK);
    const std::vector<double> twin = twinSpeeds(flat.w, flat.nextId++, a, r.ticks);
    const double vt = twin.back();
    const double lossPct = 100 * (vt - r.vAfter) / vt;
    double worstPct = 0, worstAbs = 0;
    REQUIRE(r.vh.size() == twin.size());
    for (size_t t = 0; t < twin.size(); t++) {
        const double diff = std::abs(twin[t] - r.vh[t]);
        worstAbs = std::max(worstAbs, diff);
        worstPct = std::max(worstPct, 100 * diff / twin[t]);
    }

    const int32_t jid = course.nextId++;
    addApproaching(course.w, jid, a);
    const Run j = drive(course.w, jid, a, ci, true);
    REQUIRE(mcrl_car_remove(course.w, jid) == MCRL_OK);

    INFO("end (", r.end.body.px, ", ", r.end.body.py, ", ", r.end.body.pz, ") flags ", r.end.flags);
    CHECK(r.reachedTop);
    CHECK(r.minWheels >= 3);
    CHECK(r.groundDrops == 0);
    if (a.v0 < 2) {
        CHECK(std::abs(vt - r.vAfter) <= 0.05);
        CHECK(worstAbs <= 0.05);
    } else {
        CHECK(std::abs(lossPct) <= 1);
        CHECK(worstPct <= 1);
    }
    CHECK(r.maxVy <= 0.01);
    CHECK(r.riserTicks == 0);
    CHECK(r.sumDz == doctest::Approx(ci.top - FLOOR_TOP).epsilon(1e-4));
    CHECK(j.jumpPressed);
    CHECK(j.floorAtJump);
    CHECK(j.jumpFirst);
}

const double SPEEDS[] = {1, 6, 20, 46};

void climbAll(const McrlShapes* s, World& flat, std::initializer_list<Course> courses,
              std::initializer_list<double> angles, bool fullStep) {
    for (Course c : courses) {
        World course(s, courseScene(c));
        for (double angle : angles)
            for (bool rev : {false, true})
                for (double v : SPEEDS) climbCase(course, flat, c, {v, rev, angle}, fullStep);
    }
}

struct Refusal {
    int snaps = 0, floorTicks = 0, ticks = 0, riserTicks = 0;
    int outcomes[(int)Outcome::COUNT] = {};
    McrlCarState end{};
};

Refusal watch(McrlWorld* w, int32_t id, int ticks) {
    Refusal r;
    for (int t = 0; t < ticks; t++) {
        step(w, 1);
        const McrlCarState o = getCar(w, id);
        if (o.stepDz != 0) r.snaps++;
        if (floorOf(w, id).active) r.floorTicks++;
        if (riserContacts(w, id) > 0) r.riserTicks++;
        r.outcomes[(int)eventsOf(w, id).preOutcome]++;
        r.ticks++;
    }
    r.end = getCar(w, id);
    return r;
}
}

TEST_CASE("step: wall and top slab stop the car at 6, 20, 46 blocks/s") {
    McrlShapes* s = makeStepShapes();
    for (const uint16_t id : {(uint16_t)CUBE, (uint16_t)TOP_SLAB})
        for (const double v0 : {6.0, 20.0, 46.0}) {
            CAPTURE(id);
            CAPTURE(v0);
            World wd(s, Scene().fill(0, 63, Z0, 47, 63, Z1, CUBE).fill(RISER, 64, Z0, RISER, 64, Z1, id));
            const Approach a{v0, false, 0};
            addApproaching(wd.w, 1, a, 0.5);
            const Refusal r = watch(wd.w, 1, 360);
            CHECK(r.snaps == 0);
            CHECK(r.floorTicks == 0);
            CHECK(r.outcomes[(int)Outcome::RISE] > 0);
            CHECK(r.end.body.px + hbFront() < RISER + 0.05);
            CHECK(r.end.body.py < 64.6);
        }
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("step: half-step climbs a bottom slab and 0.5/0.5 stairs") {
    McrlShapes* s = makeStepShapes();
    World flat(s, courseScene(Course::FLAT));
    climbAll(s, flat, {Course::SLAB, Course::STAIRS}, {0}, false);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("step: full step climbs a block, a slab, stairs, 1/1 terraces, a short-tread start, a 1-block ledge at 30 "
          "and 60 degrees") {
    McrlShapes* s = makeStepShapes();
    World flat(s, courseScene(Course::FLAT));
    climbAll(s, flat, {Course::BLOCK, Course::SLAB, Course::STAIRS, Course::TERRACES, Course::SHORT_TREAD}, {0},
             true);
    climbAll(s, flat, {Course::BLOCK}, {30, 60}, true);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("step: refusals with the full step on") {
    McrlShapes* s = makeStepShapes();
    const Scene floor = Scene().fill(0, 63, Z0, 47, 63, Z1, CUBE);
    struct Case {
        const char* name;
        Scene scene;
        Approach a;
        double gap;
        Outcome why;
    };
    const Case cases[] = {
        {"2-block wall, 6", Scene(floor).fill(RISER, 64, Z0, 47, 65, Z1, CUBE), {6, false, 0}, 0.5, Outcome::RISE},
        {"2-block wall, 20", Scene(floor).fill(RISER, 64, Z0, 47, 65, Z1, CUBE), {20, false, 0}, 0.5, Outcome::RISE},
        {"2-block wall, 46", Scene(floor).fill(RISER, 64, Z0, 47, 65, Z1, CUBE), {46, false, 0}, 1.2, Outcome::RISE},
        {"step under a 2-block ceiling, 6",
         Scene(floor).fill(RISER, 64, Z0, 47, 64, Z1, CUBE).fill(10, 66, Z0, 47, 66, Z1, CUBE), {6, false, 0}, 0.5,
         Outcome::HEADROOM},
        {"step under a 2-block ceiling, 20",
         Scene(floor).fill(RISER, 64, Z0, 47, 64, Z1, CUBE).fill(10, 66, Z0, 47, 66, Z1, CUBE), {20, false, 0}, 0.5,
         Outcome::HEADROOM},
        {"pane row, 6", Scene(floor).fill(RISER, 64, Z0, RISER, 64, Z1, PANE_Z), {6, false, 0}, 0.5, Outcome::THIN},
        {"pane row, 20", Scene(floor).fill(RISER, 64, Z0, RISER, 64, Z1, PANE_Z), {20, false, 0}, 0.5, Outcome::THIN},
        {"fence row, 6", Scene(floor).fill(RISER, 64, Z0, RISER, 64, Z1, FENCE_Z), {6, false, 0}, 0.5, Outcome::THIN},
        {"fence row, 20", Scene(floor).fill(RISER, 64, Z0, RISER, 64, Z1, FENCE_Z), {20, false, 0}, 0.5, Outcome::THIN},
        {"upright rod row, 6", Scene(floor).fill(RISER, 64, Z0, RISER, 64, Z1, ROD_Y), {6, false, 0}, 0.5, Outcome::THIN},
        {"upright rod row, 20", Scene(floor).fill(RISER, 64, Z0, RISER, 64, Z1, ROD_Y), {20, false, 0}, 0.5,
         Outcome::THIN},
    };
    for (const Case& c : cases) {
        CAPTURE(c.name);
        World wd(s, c.scene);
        REQUIRE(mcrl_world_set_step_up(wd.w, 1) == MCRL_OK);
        addApproaching(wd.w, 1, c.a, c.gap);
        const Refusal r = watch(wd.w, 1, 240);
        CHECK(r.snaps == 0);
        CHECK(r.floorTicks == 0);
        CHECK(r.outcomes[(int)c.why] > 0);
        CHECK(r.end.body.py < 64.6);
        if (std::string(c.name).rfind("2-block wall", 0) == 0) CHECK(r.riserTicks > 0);
    }

    SUBCASE("grazing a ledge at 85 degrees") {
        World wd(s, Scene(floor).fill(RISER, 64, Z0, 47, 64, Z1, CUBE));
        REQUIRE(mcrl_world_set_step_up(wd.w, 1) == MCRL_OK);
        const Approach a{20, false, 85};
        addApproaching(wd.w, 1, a, 2 * UU);
        const Refusal r = watch(wd.w, 1, 50);
        CHECK(r.snaps == 0);
        CHECK(r.floorTicks == 0);
        CHECK(r.outcomes[(int)Outcome::FACE] > 0);
        CHECK(r.end.body.py < 64.6);
    }

    SUBCASE("airborne over a ledge") {
        World wd(s, Scene(floor).fill(RISER, 64, Z0, 47, 64, Z1, CUBE));
        REQUIRE(mcrl_world_set_step_up(wd.w, 1) == MCRL_OK);
        McrlCarState c = carAt(RISER - 1 - hbFront(), FLOOR_TOP + 1.25, 8.5);
        c.body.vx = 20;
        REQUIRE(mcrl_car_add(wd.w, 1, 0, 0, &c) == MCRL_OK);
        setControls(wd.w, 1, 1);
        const Refusal r = watch(wd.w, 1, 60);
        CHECK(r.snaps == 0);
        CHECK(r.floorTicks == 0);
        CHECK(r.outcomes[(int)Outcome::WHEELS] > 0);
        CHECK(r.end.body.py > 65);
    }

    SUBCASE("wall-riding past a ledge") {
        World wd(s, Scene(floor).fill(RISER, 64, Z0, RISER + 1, 71, Z1, CUBE).fill(RISER - 1, 64, 14, RISER - 1, 71, 14,
                                                                                  CUBE));
        REQUIRE(mcrl_world_set_step_up(wd.w, 1) == MCRL_OK);
        McrlCarState c{};
        c.body.px = RISER - CAR_REST - 0.02;
        c.body.py = 68.5;
        c.body.pz = 4;
        c.body.qw = 0.5f;
        c.body.qx = 0.5f;
        c.body.qy = -0.5f;
        c.body.qz = 0.5f;
        c.body.vz = 20;
        REQUIRE(mcrl_car_add(wd.w, 1, 0, 0, &c) == MCRL_OK);
        setControls(wd.w, 1, 1);
        int onWall = 0;
        Refusal r;
        for (int t = 0; t < 70; t++) {
            const Refusal one = watch(wd.w, 1, 1);
            r.snaps += one.snaps;
            r.floorTicks += one.floorTicks;
            for (int i = 0; i < (int)Outcome::COUNT; i++) r.outcomes[i] += one.outcomes[i];
            r.end = one.end;
            if (wheels(one.end) >= 3) onWall++;
        }
        CHECK(onWall > 5);
        CHECK(r.snaps == 0);
        CHECK(r.floorTicks == 0);
    }
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("step: two worlds climbing the same stairs are byte-identical, and snaps count no hit or bounce") {
    McrlShapes* s = makeStepShapes();
    const Scene sc = courseScene(Course::STAIRS);
    World a(s, sc), b(s, sc);
    for (World* x : {&a, &b}) {
        setBall(x->w, 10, 66, -6);
        step(x->w, 240);
    }
    const McrlBallState rest = getBall(a.w);
    REQUIRE(speed(rest.body) < 0.05);
    const Approach ap{20, false, 0};
    for (World* x : {&a, &b}) addApproaching(x->w, 1, ap);
    int mismatches = 0, snaps = 0;
    uint32_t maxHits = 0;
    for (int t = 0; t < 120; t++) {
        for (World* x : {&a, &b}) step(x->w, 1);
        const McrlCarState ca = getCar(a.w, 1), cb = getCar(b.w, 1);
        const McrlBallState ba = getBall(a.w), bb = getBall(b.w);
        if (std::memcmp(&ca, &cb, sizeof ca) != 0 || std::memcmp(&ba, &bb, sizeof ba) != 0) mismatches++;
        if (ca.stepDz > 0) snaps++;
        maxHits = std::max(maxHits, ca.ballHits);
    }
    const McrlCarState e = getCar(a.w, 1);
    const McrlBallState be = getBall(a.w);
    CHECK(mismatches == 0);
    CHECK(snaps == 12);
    CHECK(maxHits == 0);
    CHECK(be.bounces == rest.bounces);
    CHECK(std::hypot(be.body.px - rest.body.px, be.body.pz - rest.body.pz) < 0.01);
    CHECK(e.body.py == doctest::Approx(70 + CAR_REST).epsilon(0.05 / 70));
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

namespace {
constexpr int DEEP_R2 = 24, DEEP_R3 = 32;
Scene deepSteps() {
    return Scene()
        .fill(0, 63, Z0, 47, 63, Z1, CUBE)
        .fill(20, 64, Z0, 47, 64, Z1, CUBE)
        .fill(DEEP_R2, 65, Z0, 47, 65, Z1, CUBE)
        .fill(DEEP_R3, 66, Z0, 47, 66, Z1, CUBE);
}

int wheelsOnRealGround(McrlWorld* w, int32_t id) {
    RocketSim::Car* car = carOf(w, id);
    const void* dummy = &w->stepRays.at(id)->dummy;
    int n = 0;
    for (int i = 0; i < car->_bulletVehicle.getNumWheels(); i++) {
        const auto& r = car->_bulletVehicle.m_wheelInfo[i].m_raycastInfo;
        if (r.m_isInContact && r.m_groundObject != dummy) n++;
    }
    return n;
}

double frontWheelX(const McrlCarState& c) { return c.body.px + octane().frontWheels.connectionPointOffset.x * UU; }

const double FALL_1UU = (std::sqrt(2 * 650.0 * 1) / 120 + 0.5 * 650.0 / (120.0 * 120)) * UU;
}

TEST_CASE("step: the floor never holds the car over air") {
    McrlShapes* s = makeStepShapes();

    SUBCASE("flag off pressed against the third full step, then reverse") {
        World wd(s, deepSteps());
        REQUIRE(mcrl_world_set_step_up(wd.w, 1) == MCRL_OK);
        addApproaching(wd.w, 1, {2, false, 0});
        setControls(wd.w, 1, 0.15f);
        int snaps = 0, offTick = -1, pressTick = -1, backTick = -1, t = 0, minWheels = 4, minReal = 4, floorTicks = 0,
            snapsAfter = 0;
        double maxFall = 0;
        McrlCarState prev = getCar(wd.w, 1);
        for (t = 1; t <= 1200; t++) {
            if (offTick > 0 && pressTick < 0)
                setControls(wd.w, 1, prev.body.vx > 1.3 ? -1.f : prev.body.vx > 1 ? 0.f : 0.1f);
            step(wd.w, 1);
            const McrlCarState o = getCar(wd.w, 1);
            if (o.stepDz > 0) {
                if (offTick < 0) snaps++;
                else snapsAfter++;
            }
            if (offTick < 0 && snaps == 2 && !floorOf(wd.w, 1).active && o.body.px - hbBack() > DEEP_R2) {
                REQUIRE(mcrl_world_set_step_up(wd.w, 0) == MCRL_OK);
                offTick = t;
            }
            if (offTick > 0) {
                minWheels = std::min(minWheels, wheels(o));
                minReal = std::min(minReal, wheelsOnRealGround(wd.w, 1));
                maxFall = std::max(maxFall, prev.body.py - o.body.py);
                if (floorOf(wd.w, 1).active) floorTicks++;
                const double gap = DEEP_R3 - (o.body.px + hbFront());
                if (pressTick < 0 && gap < 0.02) {
                    pressTick = t;
                    setControls(wd.w, 1, 0.1f);
                }
                if (pressTick > 0 && t == pressTick + 60) setControls(wd.w, 1, -0.2f);
                if (pressTick > 0 && t > pressTick + 60 && backTick < 0 && gap > 0.75) {
                    backTick = t;
                    setControls(wd.w, 1, 0);
                }
                if (backTick > 0 && t >= backTick + 120) break;
            }
            prev = o;
        }
        const McrlCarState e = getCar(wd.w, 1);
        CHECK(snaps == 2);
        CHECK(offTick > 0);
        CHECK(pressTick > 0);
        CHECK(backTick > 0);
        CHECK(minWheels >= 3);
        CHECK(minReal >= 3);
        CHECK(floorTicks == 0);
        CHECK(snapsAfter == 0);
        CHECK(maxFall <= FALL_1UU);
        CHECK(e.body.px - hbBack() > DEEP_R2);
        CHECK(std::abs(e.body.py - 66 - CAR_REST) < 0.05);
    }

    SUBCASE("snap from rest, then reverse at once") {
        World wd(s, courseScene(Course::BLOCK));
        REQUIRE(mcrl_world_set_step_up(wd.w, 1) == MCRL_OK);
        addApproaching(wd.w, 1, {0, false, 0}, 1.0);
        setControls(wd.w, 1, 0);
        step(wd.w, 240);
        McrlCarState c = getCar(wd.w, 1);
        c.body.px = RISER - 0.2 * UU - hbFront();
        c.body.vx = c.body.vy = c.body.vz = c.body.wx = c.body.wy = c.body.wz = 0;
        REQUIRE(mcrl_car_set_state(wd.w, 1, &c) == MCRL_OK);
        step(wd.w, 2);
        setControls(wd.w, 1, 1);
        step(wd.w, 1);
        REQUIRE(getCar(wd.w, 1).stepDz > 0);
        REQUIRE(floorOf(wd.w, 1).active);
        setControls(wd.w, 1, -1);
        int heldNoIntent = 0, released = -1;
        for (int t = 1; t <= 120; t++) {
            step(wd.w, 1);
            const McrlCarState o = getCar(wd.w, 1);
            const bool overAir = frontWheelX(o) < RISER;
            const bool noIntent = o.body.vx < 10 * UU;
            if (floorOf(wd.w, 1).active && overAir && noIntent) heldNoIntent++;
            if (released < 0 && !floorOf(wd.w, 1).active) released = t;
        }
        const McrlCarState e = getCar(wd.w, 1);
        CHECK(released > 0);
        CHECK(heldNoIntent <= 2);
        CHECK(std::abs(e.body.py - FLOOR_TOP - CAR_REST) < 0.05);
        CHECK(e.wheelContact == 0xFu);
    }
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
