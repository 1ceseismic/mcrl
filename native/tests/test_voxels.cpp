#include <doctest/doctest.h>
#include <cmath>
#include <limits>
#include <vector>
#include "mcrl.h"
#include "shapes.h"
#include "terrain.h"
#include "terrain_fixtures.h"
#include "voxels.h"
#include "world.h"

namespace {
using mcrl::voxels::Obb;
using mcrl::voxels::WBox;

enum : uint16_t { STAIR = 4, FENCE = 5, PANE = 6 };
constexpr uint32_t THIN = MCRL_SHAPE_THIN, NONFLOOR = MCRL_SHAPE_NONFLOOR;

McrlShapes* makeShapes() {
    McrlShapes* s = fx::makeShapes();
    const McrlBoxQ stair[2] = {{{0, 0, 0}, {4096, 2048, 4096}}, {{0, 2048, 0}, {4096, 4096, 2048}}};
    REQUIRE(mcrl_shapes_register(s, stair, 2, 0) == STAIR);
    const McrlBoxQ fence{{1536, 0, 1536}, {2560, 6144, 2560}};
    REQUIRE(mcrl_shapes_register(s, &fence, 1, NONFLOOR) == FENCE);
    const McrlBoxQ pane{{1792, 0, 1792}, {2304, 4096, 2304}};
    REQUIRE(mcrl_shapes_register(s, &pane, 1, THIN) == PANE);
    return s;
}

fx::Scene scene() {
    return fx::floorScene()
        .fill(2, 64, 2, 2, 64, 2, fx::SLAB)
        .fill(4, 64, 2, 4, 64, 2, STAIR)
        .fill(6, 64, 2, 6, 64, 2, FENCE)
        .fill(8, 64, 2, 8, 64, 2, PANE)
        .fill(-3, 64, -5, -3, 64, -5, fx::SLAB)
        .fill(10, 66, 10, 10, 66, 10, fx::CUBE)
        .fill(20, 66, 20, 20, 66, 20, fx::CUBE);
}

std::vector<WBox> boxesIn(McrlWorld* w, const McrlShapes* s, double x0, double y0, double z0, double x1, double y1,
                          double z1) {
    const double mn[3] = {x0, y0, z0}, mx[3] = {x1, y1, z1};
    std::vector<WBox> out{{{-1, -1, -1}, {-1, -1, -1}, 99}};
    mcrl::voxels::boxesIn(*w->terrain, *s, mn, mx, out);
    return out;
}

bool ready(McrlWorld* w, const McrlShapes* s, double x0, double y0, double z0, double x1, double y1, double z1) {
    const double mn[3] = {x0, y0, z0}, mx[3] = {x1, y1, z1};
    return mcrl::voxels::ready(*w->terrain, *s, mn, mx);
}

void checkBox(const WBox& b, double x0, double y0, double z0, double x1, double y1, double z1, uint32_t flags) {
    CHECK(b.mn[0] == x0);
    CHECK(b.mn[1] == y0);
    CHECK(b.mn[2] == z0);
    CHECK(b.mx[0] == x1);
    CHECK(b.mx[1] == y1);
    CHECK(b.mx[2] == z1);
    CHECK(b.flags == flags);
}

Obb obb(const double c[3], const double a0[3], const double a1[3], const double a2[3], double h0, double h1,
        double h2) {
    Obb o{};
    for (int i = 0; i < 3; i++) {
        o.c[i] = c[i];
        o.axis[0][i] = a0[i];
        o.axis[1][i] = a1[i];
        o.axis[2][i] = a2[i];
    }
    o.half[0] = h0;
    o.half[1] = h1;
    o.half[2] = h2;
    return o;
}

Obb aabbObb(double x0, double y0, double z0, double x1, double y1, double z1) {
    const double c[3] = {(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2};
    const double ex[3] = {1, 0, 0}, ey[3] = {0, 1, 0}, ez[3] = {0, 0, 1};
    return obb(c, ex, ey, ez, (x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2);
}

bool overlapsAny(McrlWorld* w, const McrlShapes* s, const Obb& o) {
    return mcrl::voxels::obbOverlapsAny(*w->terrain, *s, o);
}
}

TEST_CASE("voxels: boxesIn returns exact world boxes and flags") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = fx::makeWorld(s);
    const fx::Scene sc = scene();
    fx::commitStandard(w, s, sc);

    std::vector<WBox> r = boxesIn(w, s, 2, 64, 2, 3, 65, 3);
    REQUIRE(r.size() == 1);
    checkBox(r[0], 2, 64, 2, 3, 64.5, 3, 0);

    r = boxesIn(w, s, 4, 64, 2, 5, 65, 3);
    REQUIRE(r.size() == 2);
    checkBox(r[0], 4, 64, 2, 5, 64.5, 3, 0);
    checkBox(r[1], 4, 64.5, 2, 5, 65, 2.5, 0);

    r = boxesIn(w, s, 6, 64, 2, 7, 65, 3);
    REQUIRE(r.size() == 1);
    checkBox(r[0], 6.375, 64, 2.375, 6.625, 65.5, 2.625, NONFLOOR);
    r = boxesIn(w, s, 6, 65, 2, 7, 66, 3);
    REQUIRE(r.size() == 1);
    checkBox(r[0], 6.375, 64, 2.375, 6.625, 65.5, 2.625, NONFLOOR);
    CHECK(boxesIn(w, s, 6, 65.5, 2, 7, 66, 3).empty());

    r = boxesIn(w, s, 8, 64, 2, 9, 65, 3);
    REQUIRE(r.size() == 1);
    checkBox(r[0], 8.4375, 64, 2.4375, 8.5625, 65, 2.5625, THIN);
    CHECK(boxesIn(w, s, 8, 64, 2, 8.4375, 65, 3).empty());

    r = boxesIn(w, s, -3, 64, -5, -2, 65, -4);
    REQUIRE(r.size() == 1);
    checkBox(r[0], -3, 64, -5, -2, 64.5, -4, 0);

    r = boxesIn(w, s, 1.5, 63.9, 1.5, 2.5, 64.1, 2.5);
    REQUIRE(r.size() == 5);
    int cubes = 0, slabs = 0;
    for (const WBox& b : r) {
        if (b.mx[1] == 64) {
            cubes++;
            CHECK(b.mn[1] == 63);
            CHECK(b.mx[0] - b.mn[0] == 1);
        } else {
            slabs++;
            checkBox(b, 2, 64, 2, 3, 64.5, 3, 0);
        }
    }
    CHECK(cubes == 4);
    CHECK(slabs == 1);

    CHECK(boxesIn(w, s, 12, 70, 12, 13, 71, 13).empty());
    CHECK(boxesIn(w, s, 2, 64, 2, std::numeric_limits<double>::quiet_NaN(), 65, 3).empty());
    CHECK(boxesIn(w, s, 2, 64, 2, std::numeric_limits<double>::infinity(), 65, 3).empty());
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("voxels: sanity bounds (MAX_SPAN 512 blocks, LIMIT 1e9 blocks)") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = fx::makeWorld(s);
    fx::commitStandard(w, s, scene());
    CHECK_FALSE(boxesIn(w, s, 2, 63.5, 2, 2 + 512, 64.5, 3).empty());
    CHECK(boxesIn(w, s, 2, 63.5, 2, 2 + 513, 64.5, 3).empty());
    CHECK_FALSE(ready(w, s, 2, 63.5, 2, 2 + 513, 64.5, 3));
    CHECK(overlapsAny(w, s, aabbObb(100, 200, 100, 100 + 513, 201, 101)));
    CHECK_FALSE(overlapsAny(w, s, aabbObb(100, 200, 100, 100 + 511, 201, 101)));
    CHECK(boxesIn(w, s, 1e9, 64, 2, 1e9 + 1, 65, 3).empty());
    CHECK_FALSE(ready(w, s, 1e9, 64, 2, 1e9 + 1, 65, 3));
    CHECK_FALSE(ready(w, s, -1e9 - 1, 64, 2, -1e9, 65, 3));
    CHECK(overlapsAny(w, s, aabbObb(2e9, 70, 2, 2e9 + 1, 71, 3)));
    CHECK(boxesIn(w, s, 3, 64, 2, 2, 65, 3).empty());
    CHECK_FALSE(ready(w, s, 3, 64, 2, 2, 65, 3));
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}

TEST_CASE("voxels: oriented box against block boxes") {
    McrlShapes* s = makeShapes();
    McrlWorld* w = fx::makeWorld(s);
    fx::commitStandard(w, s, scene());
    const double r2 = std::sqrt(2.0);

    SUBCASE("rotated 45 degrees about y, a face against the corner edge of the cube at (20, 66, 20)") {
        const double a0[3] = {1 / r2, 0, 1 / r2}, a1[3] = {0, 1, 0}, a2[3] = {-1 / r2, 0, 1 / r2};
        for (const double gap : {0.01, -0.01}) {
            const double d = (0.5 + gap) / r2;
            const double c[3] = {21 + d, 66.5, 21 + d};
            CHECK(overlapsAny(w, s, obb(c, a0, a1, a2, 0.5, 0.25, 0.5)) == (gap < 0));
        }
    }

    SUBCASE("a rod past the cube's edge: only an edge-edge axis separates") {
        const double r3 = std::sqrt(3.0), r6 = std::sqrt(6.0);
        const double n[3] = {1 / r2, 1 / r2, 0};
        const double d[3] = {1 / r3, -1 / r3, 1 / r3};
        const double wv[3] = {-1 / r6, 1 / r6, 2 / r6};
        double a1[3], a2[3];
        for (int i = 0; i < 3; i++) {
            a1[i] = (n[i] + wv[i]) / r2;
            a2[i] = (n[i] - wv[i]) / r2;
        }
        for (const double gap : {0.01, -0.01}) {
            const double off = 0.05 * r2 + gap;
            const double c[3] = {11 + off * n[0], 67 + off * n[1], 10.5};
            CHECK(overlapsAny(w, s, obb(c, d, a1, a2, 3, 0.05, 0.05)) == (gap < 0));
        }
    }

    SUBCASE("nearly parallel: turned 1e-6 rad") {
        const double e = 1e-6, k = 1 / std::sqrt(3.0);
        const double kx = k, ky = k, kz = k;
        const double cs = std::cos(e), sn = std::sin(e), t = 1 - cs;
        const double a0[3] = {cs + kx * kx * t, ky * kx * t + kz * sn, kz * kx * t - ky * sn};
        const double a1[3] = {kx * ky * t - kz * sn, cs + ky * ky * t, kz * ky * t + kx * sn};
        const double a2[3] = {kx * kz * t + ky * sn, ky * kz * t - kx * sn, cs + kz * kz * t};
        for (const double gap : {0.01, -0.01}) {
            const double c[3] = {20 - 0.5 - gap, 66.5, 20.5};
            CHECK(overlapsAny(w, s, obb(c, a0, a1, a2, 0.5, 0.4, 0.4)) == (gap < 0));
        }
    }

    SUBCASE("touching is not overlapping") {
        CHECK_FALSE(overlapsAny(w, s, aabbObb(12, 64, 4, 14, 65, 6)));
        CHECK(overlapsAny(w, s, aabbObb(12, 63.99, 4, 14, 65, 6)));
        CHECK_FALSE(overlapsAny(w, s, aabbObb(19, 66.2, 20.2, 20, 66.8, 20.8)));
        CHECK(overlapsAny(w, s, aabbObb(19, 66.2, 20.2, 20.01, 66.8, 20.8)));
    }

    SUBCASE("not finite counts as overlapping") {
        Obb o = aabbObb(12, 70, 12, 13, 71, 13);
        CHECK_FALSE(overlapsAny(w, s, o));
        o.c[1] = std::numeric_limits<double>::quiet_NaN();
        CHECK(overlapsAny(w, s, o));
    }
    CHECK(mcrl_world_destroy(w) == MCRL_OK);
    CHECK(mcrl_shapes_destroy(s) == MCRL_OK);
}
