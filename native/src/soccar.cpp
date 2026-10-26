#include "soccar.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>

#include "BulletCollision/CollisionDispatch/btInternalEdgeUtility.h"
#include "BulletCollision/CollisionShapes/btBvhTriangleMeshShape.h"
#include "BulletCollision/CollisionShapes/btTriangleMesh.h"
#include "coords.h"
#include "world.h"

using namespace RocketSim;

namespace mcrl::soccar {
namespace {

std::nullptr_t no_meshes(const std::string& what) {
    fail(MCRL_ERR_ARG, "mcrl_arena_meshes_load", what);
    return nullptr;
}

constexpr int32_t MAX_COUNT = 1000 * 1000;

// Checked first: RocketSim's reader overruns short data.
std::string parse(const uint8_t* bytes, int32_t size, CollisionMeshFile& out) {
    if (!bytes) return "data is NULL";
    if (size < 8) return "shorter than the 8-byte header";
    int32_t tris = 0, verts = 0;
    std::memcpy(&tris, bytes, 4);
    std::memcpy(&verts, bytes + 4, 4);
    if (tris < 1 || verts < 1 || tris > MAX_COUNT || verts > MAX_COUNT)
        return "bad triangle or vertex count (" + std::to_string(tris) + ", " + std::to_string(verts) + ")";
    const int64_t need = 8 + 12 * (int64_t)tris + 12 * (int64_t)verts;
    if (size < need)
        return std::to_string(size) + " bytes, " + std::to_string(tris) + " triangles and " + std::to_string(verts) +
               " vertices need " + std::to_string(need);
    for (int64_t i = 0; i < 3 * (int64_t)tris; i++) {
        int32_t v = 0;
        std::memcpy(&v, bytes + 8 + 4 * i, 4);
        if (v < 0 || v >= verts) return "triangle " + std::to_string(i / 3) + " has a bad vertex index";
    }
    DataStreamIn in;
    in.data.assign(bytes, bytes + size);
    try {
        out.ReadFromStream(in, true);
    } catch (const std::exception& e) {
        return e.what();
    }
    return "";
}

btBvhTriangleMeshShape* build(CollisionMeshFile& f) {
    std::unique_ptr<btTriangleMesh> mesh(f.MakeBulletMesh());
    auto shape = std::make_unique<btBvhTriangleMeshShape>(mesh.get(), true);
    auto info = std::make_unique<btTriangleInfoMap>();
    btGenerateInternalEdgeInfo(shape.get(), info.get());
    shape->setTriangleInfoMap(info.release());
    mesh.release();
    return shape.release();
}

void free_shape(btBvhTriangleMeshShape* shape) {
    btStridingMeshInterface* mesh = shape->getMeshInterface();
    btTriangleInfoMap* info = shape->getTriangleInfoMap();
    delete shape;
    delete mesh;
    delete info;
}

// Sets live until exit; this keeps them reachable.
std::vector<McrlArenaMeshes*>& all_sets() {
    static auto* const sets = new std::vector<McrlArenaMeshes*>();
    return *sets;
}

// Arena::Create copies RocketSim's global SOCCAR mesh list.
class MeshesInPlace {
public:
    explicit MeshesInPlace(const McrlArenaMeshes& meshes)
        : global(RocketSim::GetArenaCollisionShapes(GameMode::SOCCAR)), saved(meshes.shapes) {
        global.swap(saved);
    }
    ~MeshesInPlace() { global.swap(saved); }
    MeshesInPlace(const MeshesInPlace&) = delete;
    MeshesInPlace& operator=(const MeshesInPlace&) = delete;

private:
    std::vector<btBvhTriangleMeshShape*>& global;
    std::vector<btBvhTriangleMeshShape*> saved;
};

void goal_push(McrlWorld& w, const btVector3& ballBt) {
    for (Car* car : w.arena->_cars) {
        if (car->_internalState.isDemoed) continue;
        btRigidBody& rb = car->_rigidBody;
        const btVector3 r = (rb.getWorldTransform().getOrigin() - ballBt) * BT_TO_UU;
        const double d = r.length();
        if (!(d < GOAL_PUSH_RADIUS_UU)) continue;
        const btVector3 dir = d > 0 ? r / (btScalar)d : btVector3(0, 0, 1);
        const double dv = GOAL_PUSH_DV_UU * std::pow(1 - d / GOAL_PUSH_RADIUS_UU, (double)GOAL_PUSH_FALLOFF);
        rb.setLinearVelocity(rb.getLinearVelocity() + dir * (btScalar)(dv * UU_TO_BT));
    }
}

}

McrlArenaMeshes* meshes_load(const uint8_t* const* files, const int32_t* sizes, int32_t n, uint32_t* outHashes) {
    if (n < 1) return no_meshes("n < 1");
    if (!files || !sizes) return no_meshes("files or sizes is NULL");
    std::lock_guard<std::mutex> lock(mcrl_internal::init_mutex());
    std::vector<CollisionMeshFile> parsed(n);
    for (int32_t i = 0; i < n; i++) {
        const std::string why = parse(files[i], sizes[i], parsed[i]);
        if (!why.empty()) return no_meshes("file " + std::to_string(i) + ": " + why);
    }
    auto set = std::make_unique<McrlArenaMeshes>();
    set->shapes.reserve(n);
    all_sets().reserve(all_sets().size() + 1);
    try {
        for (CollisionMeshFile& f : parsed) set->shapes.push_back(build(f));
    } catch (...) {
        for (btBvhTriangleMeshShape* s : set->shapes) free_shape(s);
        throw;
    }
    if (outHashes)
        for (int32_t i = 0; i < n; i++) outHashes[i] = parsed[i].hash;
    all_sets().push_back(set.get());
    return set.release();
}

std::unique_ptr<Arena> create_arena(const McrlArenaMeshes& meshes) {
    mcrl_internal::ensure_init();
    ArenaConfig cfg;
    cfg.useCustomBroadphase = false;
    cfg.noBallRot = false;
    std::lock_guard<std::mutex> lock(mcrl_internal::init_mutex());
    MeshesInPlace inPlace(meshes);
    return std::unique_ptr<Arena>(Arena::Create(GameMode::SOCCAR, cfg, 120));
}

int32_t kickoff(McrlWorld& w, int32_t seed, int32_t lockTicks) {
    static const char* fn = "mcrl_soccar_kickoff";
    if (!w.soccar) return fail(MCRL_ERR_ARG, fn, "not a SOCCAR world");
    if (lockTicks < 0) return fail(MCRL_ERR_ARG, fn, "lockTicks < 0");
    // More would index past RocketSim's spawn arrays.
    int perTeam[2] = {0, 0};
    for (const Car* car : w.arena->_cars) perTeam[car->team == Team::BLUE ? 0 : 1]++;
    if (std::max(perTeam[0], perTeam[1]) > MAX_KICKOFF_CARS_PER_TEAM)
        return fail(MCRL_ERR_ARG, fn, "at most 9 cars per team");
    put_ball_in(w);
    w.arena->ResetToRandomKickoff(seed);
    for (const auto& [id, car] : w.cars) car_was_set(w, id);
    ball_was_set(w);
    ball_clear_touch(w);
    Soccar& s = *w.soccar;
    s.kickoffs++;
    s.lockTicks = lockTicks;
    return MCRL_OK;
}

int32_t get_state(McrlWorld& w, McrlSoccarState* out) {
    static const char* fn = "mcrl_soccar_get_state";
    if (!w.soccar) return fail(MCRL_ERR_ARG, fn, "not a SOCCAR world");
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    const Soccar& s = *w.soccar;
    McrlSoccarState o{};
    o.goalX = s.goalPos[0];
    o.goalY = s.goalPos[1];
    o.goalZ = s.goalPos[2];
    o.goalVx = s.goalVel[0];
    o.goalVy = s.goalVel[1];
    o.goalVz = s.goalVel[2];
    o.goals = s.goals;
    o.goalTeam = s.goalTeam;
    o.goalToucher = s.goalToucher;
    o.ballOutOfPlay = s.ballOutOfPlay ? 1 : 0;
    o.kickoffs = s.kickoffs;
    o.lockTicks = (uint32_t)s.lockTicks;
    const std::vector<BoostPad*>& pads = w.arena->GetBoostPads();
    for (int i = 0; i < PAD_COUNT; i++) {
        const BoostPadState& p = pads[i]->_internalState;
        o.padActive[i] = p.isActive ? 1 : 0;
        o.padCooldown[i] = RS_MAX(p.cooldown - w.arena->tickTime, 0);
        o.padPickups[i] = s.padPickups[i];
        o.padCar[i] = s.padCar[i];
    }
    *out = o;
    return MCRL_OK;
}

int32_t respawn_state(McrlWorld& w, int32_t team, int32_t index, McrlCarState* out) {
    static const char* fn = "mcrl_soccar_respawn_state";
    if (!w.soccar) return fail(MCRL_ERR_ARG, fn, "not a SOCCAR world");
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    if (team != 0 && team != 1) return fail(MCRL_ERR_ARG, fn, "team must be 0 (BLUE) or 1 (ORANGE)");
    if (index < 0 || index >= RLConst::CAR_RESPAWN_LOCATION_AMOUNT) return fail(MCRL_ERR_ARG, fn, "index must be 0..3");
    const RLConst::CarSpawnPos& spot = RLConst::CAR_RESPAWN_LOCATIONS_SOCCAR[index];
    const bool blue = team == 0;
    CarState cs;
    cs.pos = Vec(spot.x, spot.y * (blue ? 1 : -1), RLConst::CAR_RESPAWN_Z);
    cs.rotMat = Angle(spot.yawAng + (blue ? 0 : M_PI), 0.f, 0.f).ToRotMat();
    cs.boost = w.arena->GetMutatorConfig().carSpawnBoostAmount;
    McrlCarState o{};
    rs_to_body(w, cs, o.body);
    o.boost = cs.boost;
    *out = o;
    return MCRL_OK;
}

int32_t pad_layout(McrlWorld& w, float* outXyz102, uint8_t* outBig34) {
    static const char* fn = "mcrl_soccar_pad_layout";
    if (!w.soccar) return fail(MCRL_ERR_ARG, fn, "not a SOCCAR world");
    if (!outXyz102 || !outBig34) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    const std::vector<BoostPad*>& pads = w.arena->GetBoostPads();
    for (int i = 0; i < PAD_COUNT; i++) {
        double p[3];
        coords::rsToMcPos(pads[i]->config.pos, w.ox, w.oy, w.oz, p);
        for (int a = 0; a < 3; a++) outXyz102[3 * i + a] = (float)p[a];
        outBig34[i] = pads[i]->config.isBig ? 1 : 0;
    }
    return MCRL_OK;
}

int32_t park_ball(McrlWorld& w) {
    if (!w.soccar) return fail(MCRL_ERR_ARG, "mcrl_soccar_park_ball", "not a SOCCAR world");
    take_ball_out(w);
    return MCRL_OK;
}

void take_ball_out(McrlWorld& w) {
    Soccar& s = *w.soccar;
    Ball* ball = w.arena->ball;
    if (!s.ballOutOfPlay) w.arena->_bulletWorld.removeRigidBody(&ball->_rigidBody);
    s.ballOutOfPlay = true;
    ball->SetState(BallState());
    w.ballParked = false;
    w.ballFxStale = true;
}

void put_ball_in(McrlWorld& w) {
    Soccar& s = *w.soccar;
    if (!s.ballOutOfPlay) return;
    // RocketSim Ball::_BulletSetup's collision filter.
    w.arena->_bulletWorld.addRigidBody(&w.arena->ball->_rigidBody,
                                       btBroadphaseProxy::DefaultFilter | CollisionMasks::HOOPS_NET |
                                           CollisionMasks::DROPSHOT_TILE,
                                       btBroadphaseProxy::AllFilter);
    s.ballOutOfPlay = false;
}

bool ball_out(const McrlWorld& w) { return w.soccar && w.soccar->ballOutOfPlay; }

void before_step(McrlWorld& w) {
    if (!w.soccar) return;
    Soccar& s = *w.soccar;
    s.locked = s.lockTicks > 0;
    if (s.locked) {
        s.lockTicks--;
        s.ownControls.clear();
        for (Car* car : w.arena->_cars) {
            s.ownControls.push_back(car->controls);
            car->controls = CarControls();
        }
    }
    const std::vector<BoostPad*>& pads = w.arena->GetBoostPads();
    for (int i = 0; i < PAD_COUNT; i++) s.cooldownBefore[i] = pads[i]->_internalState.cooldown;
}

void after_step(McrlWorld& w) {
    if (!w.soccar) return;
    Soccar& s = *w.soccar;
    if (s.locked) {
        size_t i = 0;
        for (Car* car : w.arena->_cars) car->controls = s.ownControls[i++];
    }
    const std::vector<BoostPad*>& pads = w.arena->GetBoostPads();
    for (int i = 0; i < PAD_COUNT; i++) {
        const BoostPadState& p = pads[i]->_internalState;
        if (!(p.cooldown > s.cooldownBefore[i])) continue;
        s.padPickups[i]++;
        const auto id = w.rsIds.find(p.prevLockedCarID);
        s.padCar[i] = id == w.rsIds.end() ? -1 : id->second;
    }
}

void after_tick(McrlWorld& w) {
    if (!w.soccar || w.soccar->ballOutOfPlay || !w.arena->IsBallScored()) return;
    Soccar& s = *w.soccar;
    Ball* ball = w.arena->ball;
    const btVector3 at = ball->_rigidBody.getWorldTransform().getOrigin();
    McrlBody b{};
    rs_to_body(w, ball->GetState(), b);
    s.goals++;
    s.goalTeam = at.y() > 0 ? 0 : 1;
    s.goalPos = {b.px, b.py, b.pz};
    s.goalVel = {(float)b.vx, (float)b.vy, (float)b.vz};
    s.goalToucher = w.lastToucher;
    goal_push(w, at);
    take_ball_out(w);
}

bool active(const McrlWorld& w) { return w.soccar != nullptr; }

const std::vector<BoostPad*>& pads(McrlWorld& w) { return w.arena->GetBoostPads(); }

int32_t lock_ticks(const McrlWorld& w) { return w.soccar ? w.soccar->lockTicks : 0; }

uint32_t kickoffs(const McrlWorld& w) { return w.soccar ? w.soccar->kickoffs : 0; }

}
