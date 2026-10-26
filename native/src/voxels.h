#pragma once
#include <cstdint>
#include <vector>
#include "shapes.h"
#include "terrain.h"

namespace mcrl::voxels {
struct WBox {
    double mn[3], mx[3];
    uint32_t flags;
};

struct Obb {
    double c[3];
    double axis[3][3];
    double half[3];
};

bool ready(const TerrainStore& t, const McrlShapes& s, const double mn[3], const double mx[3]);

void boxesIn(const TerrainStore& t, const McrlShapes& s, const double mn[3], const double mx[3],
             std::vector<WBox>& out);

// True for a non-finite or absurd box (blocked).
bool obbOverlapsAny(const TerrainStore& t, const McrlShapes& s, const Obb& box);

// Strict, as Minecraft's AABB.intersects: touching boxes do not overlap.
bool overlaps(const Obb& box, const WBox& b);

void aabbOf(const Obb& box, double mn[3], double mx[3]);
}
