#pragma once
#include <cstdint>
#include <vector>
#include "BulletCollision/CollisionDispatch/btCollisionObjectWrapper.h"
#include "BulletCollision/NarrowPhaseCollision/btManifoldPoint.h"
#include "RocketSim.h"
#include "fxfeed.h"
#include "mcrl.h"

struct McrlWorld;

namespace mcrl::materials {
// "mcrl": the user index of a section mesh shape.
constexpr int TERRAIN_TAG = 0x6D63726C;

const McrlMaterial& of(const McrlWorld& w, const btCollisionObject* obj, int tri);

void refresh(McrlWorld& w);

void after_tick(McrlWorld& w, std::vector<fxfeed::Before>& cars, const fxfeed::BallBefore& ball);

// Runs inside Bullet's narrowphase: must not allocate or throw.
bool contact_added(btManifoldPoint& cp, const btCollisionObjectWrapper* a, int partA, int indexA,
                   const btCollisionObjectWrapper* b, int partB, int indexB);

void install_callback() noexcept;

}
