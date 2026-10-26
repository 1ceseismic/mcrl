#pragma once
#include <memory>
#include <vector>
#include "BulletCollision/CollisionShapes/btBvhTriangleMeshShape.h"
#include "BulletCollision/CollisionShapes/btTriangleIndexVertexArray.h"
#include "BulletCollision/CollisionShapes/btTriangleInfoMap.h"
#include "mcrl.h"
#include "shapes.h"

struct McrlWorld;

struct McrlBuilt {
    int32_t sx = 0, sy = 0, sz = 0;
    uint32_t version = 0;
    uint32_t flags = 0;
    int32_t tris = 0;
    std::vector<uint16_t> cells;
    std::vector<float> verts;
    std::vector<int32_t> idx;
    std::vector<uint8_t> triMat;
    McrlWorld* world = nullptr;
    std::vector<uint8_t> fluid;
    // Destroyed in reverse: the shape before its data.
    std::unique_ptr<btTriangleIndexVertexArray> iface;
    std::unique_ptr<btTriangleInfoMap> info;
    std::unique_ptr<btBvhTriangleMeshShape> shape;
};

namespace mcrl {
constexpr int SECTION_CELLS = 16 * 16 * 16;
constexpr int core_index(int x, int y, int z) { return (y * 16 + z) * 16 + x; }

// Test knobs; the C ABI uses the defaults.
struct MesherOptions {
    bool fullCubeFastPath = true;
    bool bvhEdgeInfo = false;
    bool keepSkirt = false;
};

std::unique_ptr<McrlBuilt> section_build(const McrlShapes& shapes, const McrlSectionInput& in, McrlBuildStats* out,
                                         const MesherOptions& opt = {});
}
