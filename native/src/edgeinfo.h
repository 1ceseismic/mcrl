#pragma once
#include <cstdint>
#include <vector>
#include "BulletCollision/CollisionShapes/btTriangleInfoMap.h"

namespace mcrl {
// Needs an exactly welded mesh: neighbours match by index.
void edge_info(const std::vector<float>& verts, const std::vector<int32_t>& idx, int32_t tris, btTriangleInfoMap& map);
}
