#include <doctest/doctest.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <functional>
#include <iterator>
#include <map>
#include <memory>
#include <random>
#include <set>
#include <string>
#include <tuple>
#include <vector>
#include "mcrl.h"
#include "mesher.h"

namespace {
constexpr int N = 18;
constexpr uint16_t UNKNOWN = 0xFFFF;
constexpr double EPS = 1e-3;

enum : uint16_t { CUBE = 1, SLAB = 2, HALF_X = 3, POST = 4, STAIRS_N = 5, FENCE = 6, TOP_SLAB = 7, FENCE_X = 8 };
constexpr uint16_t LAST_SHAPE = FENCE_X;

struct Box { double mn[3], mx[3]; };

const std::vector<McrlBoxQ>& boxesOf(uint16_t id) {
    static const std::vector<McrlBoxQ> table[] = {
        {},
        {{{0, 0, 0}, {4096, 4096, 4096}}},
        {{{0, 0, 0}, {4096, 2048, 4096}}},
        {{{0, 0, 0}, {2048, 4096, 4096}}},
        {{{0, 0, 0}, {1024, 6144, 1024}}},
        {{{0, 0, 0}, {4096, 2048, 4096}}, {{0, 2048, 0}, {4096, 4096, 2048}}},
        {{{1536, 0, 1536}, {2560, 6144, 2560}}},
        {{{0, 2048, 0}, {4096, 4096, 4096}}},
        {{{1536, 0, 1536}, {2560, 6144, 2560}}, {{0, 0, 1792}, {4096, 6144, 2304}}},
    };
    return table[id];
}

McrlShapes* makeShapes() {
    McrlShapes* s = mcrl_shapes_create();
    REQUIRE(s != nullptr);
    for (uint16_t id = CUBE; id <= LAST_SHAPE; id++) {
        const auto& b = boxesOf(id);
        REQUIRE(mcrl_shapes_register(s, b.data(), (int32_t)b.size(), 0) == id);
    }
    return s;
}

struct Scene {
    std::vector<uint16_t> cells = std::vector<uint16_t>(N * N * N, 0);
    std::vector<Box> solids;
    void raw(int x, int y, int z, uint16_t id) { cells[((y + 1) * N + (z + 1)) * N + (x + 1)] = id; }
    void put(int x, int y, int z, uint16_t id) {
        raw(x, y, z, id);
        if (id == UNKNOWN) return;
        for (const McrlBoxQ& q : boxesOf(id))
            solids.push_back({{x + q.mn[0] / 4096.0, y + q.mn[1] / 4096.0, z + q.mn[2] / 4096.0},
                              {x + q.mx[0] / 4096.0, y + q.mx[1] / 4096.0, z + q.mx[2] / 4096.0}});
    }
    bool solidAt(const double p[3]) const {
        for (const Box& b : solids)
            if (p[0] > b.mn[0] && p[0] < b.mx[0] && p[1] > b.mn[1] && p[1] < b.mx[1] && p[2] > b.mn[2] && p[2] < b.mx[2])
                return true;
        return false;
    }
};

struct Built {
    McrlBuilt* b = nullptr;
    McrlBuildStats st{};
    ~Built() { if (b) mcrl_built_free(b); }
};

void build(McrlShapes* s, const Scene& sc, Built& out, int32_t sx = 0, int32_t sy = 0, int32_t sz = 0) {
    McrlSectionInput in{sx, sy, sz, 1, 1, 0, sc.cells.data()};
    out.st.tris = -1;
    out.b = mcrl_section_build(s, &in, &out.st);
    REQUIRE_MESSAGE(out.b != nullptr, mcrl_last_error());
}

struct Window {
    int border, n;
    std::vector<uint16_t> cells;
    explicit Window(int b) : border(b), n(16 + 2 * b), cells((size_t)n * n * n, 0) {}
    uint16_t& at(int x, int y, int z) { return cells[((y + border) * n + (z + border)) * n + (x + border)]; }
};

struct Tri {
    double v[3][3];
    double n[3];
    double c[3];
};

std::vector<Tri> trisOf(const McrlBuilt* b) {
    std::vector<Tri> out;
    REQUIRE(b->idx.size() == 3 * (size_t)b->tris);
    for (int t = 0; t < b->tris; t++) {
        Tri tr{};
        for (int k = 0; k < 3; k++) {
            const int32_t i = b->idx[3 * t + k];
            REQUIRE(i >= 0);
            REQUIRE(3 * (size_t)i + 2 < b->verts.size());
            for (int a = 0; a < 3; a++) tr.v[k][a] = b->verts[3 * i + a];
        }
        double e1[3], e2[3];
        for (int a = 0; a < 3; a++) { e1[a] = tr.v[1][a] - tr.v[0][a]; e2[a] = tr.v[2][a] - tr.v[0][a]; }
        tr.n[0] = e1[1] * e2[2] - e1[2] * e2[1];
        tr.n[1] = e1[2] * e2[0] - e1[0] * e2[2];
        tr.n[2] = e1[0] * e2[1] - e1[1] * e2[0];
        for (int a = 0; a < 3; a++) tr.c[a] = (tr.v[0][a] + tr.v[1][a] + tr.v[2][a]) / 3;
        out.push_back(tr);
    }
    return out;
}

double len(const double v[3]) { return std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }

void btToMc(const double bt[3], double mc[3]) { mc[0] = bt[0]; mc[1] = bt[2]; mc[2] = bt[1]; }

void checkOutward(const McrlBuilt* b, const Scene& sc) {
    for (const Tri& t : trisOf(b)) {
        const double l = len(t.n);
        REQUIRE(l > 1e-9);
        int axes = 0;
        for (int a = 0; a < 3; a++) axes += std::abs(t.n[a]) > 1e-9;
        CHECK(axes == 1);
        double front[3], back[3], frontMc[3], backMc[3];
        for (int a = 0; a < 3; a++) {
            front[a] = t.c[a] + t.n[a] / l * EPS;
            back[a] = t.c[a] - t.n[a] / l * EPS;
        }
        btToMc(front, frontMc);
        btToMc(back, backMc);
        CHECK_MESSAGE(!sc.solidAt(frontMc), "air expected in front of triangle at MC (", frontMc[0], ", ",
                      frontMc[1], ", ", frontMc[2], ")");
        CHECK_MESSAGE(sc.solidAt(backMc), "solid expected behind triangle at MC (", backMc[0], ", ", backMc[1],
                      ", ", backMc[2], ")");
    }
}

struct FaceSet {
    int count = 0;
    double area = 0;
    double lo[3] = {1e9, 1e9, 1e9}, hi[3] = {-1e9, -1e9, -1e9};
};

FaceSet facesAt(const McrlBuilt* b, int axis, int sign, double c) {
    FaceSet fs;
    for (const Tri& t : trisOf(b)) {
        double nMc[3], v0[3];
        btToMc(t.n, nMc);
        btToMc(t.v[0], v0);
        if (!(nMc[axis] * sign > 1e-9) || std::abs(v0[axis] - c) > 1e-9) continue;
        fs.count++;
        fs.area += len(t.n) / 2;
        for (int k = 0; k < 3; k++) {
            double m[3];
            btToMc(t.v[k], m);
            for (int a = 0; a < 3; a++) { fs.lo[a] = std::min(fs.lo[a], m[a]); fs.hi[a] = std::max(fs.hi[a], m[a]); }
        }
    }
    return fs;
}
}

TEST_CASE("mesher: single cube -> 12 outward triangles") {
    McrlShapes* s = makeShapes();
    Scene sc;
    sc.put(2, 5, 11, CUBE);
    Built bb;
    build(s, sc, bb);
    CHECK(bb.st.tris == 12);
    CHECK(bb.b->tris == 12);
    CHECK(bb.st.flags == 0);
    CHECK(std::isfinite(bb.st.buildMs));
    CHECK(bb.st.buildMs >= 0);
    CHECK(bb.b->verts.size() == 8 * 3);
    for (size_t i = 0; i < bb.b->verts.size(); i += 3) {
        const float x = bb.b->verts[i], y = bb.b->verts[i + 1], z = bb.b->verts[i + 2];
        CHECK((x == 2 || x == 3));
        CHECK((y == 11 || y == 12));
        CHECK((z == 5 || z == 6));
    }
    const double centre[3] = {2.5, 11.5, 5.5};
    int perDir[6] = {};
    for (const Tri& t : trisOf(bb.b)) {
        double d = 0;
        for (int a = 0; a < 3; a++) d += t.n[a] * (t.c[a] - centre[a]);
        CHECK(d > 0);
        for (int a = 0; a < 3; a++)
            if (std::abs(t.n[a]) > 1e-9) perDir[2 * a + (t.n[a] > 0)]++;
    }
    for (int i = 0; i < 6; i++) CHECK(perDir[i] == 2);
    checkOutward(bb.b, sc);
    REQUIRE(bb.b->shape != nullptr);
    CHECK(bb.b->shape->usesQuantizedAabbCompression());
    CHECK(bb.b->shape->getOptimizedBvh() != nullptr);
    REQUIRE(bb.b->shape->getTriangleInfoMap() != nullptr);
    CHECK(bb.b->shape->getTriangleInfoMap()->size() > 0);
    CHECK(bb.b->sx == 0);
    CHECK(bb.b->version == 1);
    mcrl_shapes_destroy(s);
}

TEST_CASE("mesher: faces are hidden only where covered") {
    McrlShapes* s = makeShapes();
    SUBCASE("bottom slab under a cube keeps its top: half a block of air between them") {
        Scene sc;
        sc.put(8, 8, 8, SLAB);
        sc.put(8, 9, 8, CUBE);
        Built bb;
        build(s, sc, bb);
        CHECK(bb.st.tris == 24);
        CHECK(facesAt(bb.b, 1, +1, 8.5).area == doctest::Approx(1));
        CHECK(facesAt(bb.b, 1, -1, 9).area == doctest::Approx(1));
        checkOutward(bb.b, sc);
    }
    SUBCASE("slab on a cube: cube top and slab bottom hidden entirely") {
        Scene sc;
        sc.put(8, 8, 8, CUBE);
        sc.put(8, 9, 8, SLAB);
        Built bb;
        build(s, sc, bb);
        CHECK(bb.st.tris == 20);
        CHECK(facesAt(bb.b, 1, +1, 9).count == 0);
        CHECK(facesAt(bb.b, 1, -1, 9).count == 0);
        checkOutward(bb.b, sc);
    }
    SUBCASE("slab beside a cube: slab side hidden, cube side hidden only below the slab top") {
        Scene sc;
        sc.put(8, 8, 8, SLAB);
        sc.put(9, 8, 8, CUBE);
        Built bb;
        build(s, sc, bb);
        CHECK(bb.st.tris == 22);
        CHECK(facesAt(bb.b, 0, +1, 9).count == 0);
        const FaceSet f = facesAt(bb.b, 0, -1, 9);
        CHECK(f.count == 2);
        CHECK(f.area == doctest::Approx(0.5));
        CHECK(f.lo[1] == doctest::Approx(8.5));
        CHECK(f.hi[1] == doctest::Approx(9));
        checkOutward(bb.b, sc);
    }
    SUBCASE("half-width box on a cube: cube top hidden only under it") {
        Scene sc;
        sc.put(8, 8, 8, CUBE);
        sc.put(8, 9, 8, HALF_X);
        Built bb;
        build(s, sc, bb);
        CHECK(bb.st.tris == 22);
        const FaceSet f = facesAt(bb.b, 1, +1, 9);
        CHECK(f.area == doctest::Approx(0.5));
        CHECK(f.lo[0] == doctest::Approx(8.5));
        CHECK(f.hi[0] == doctest::Approx(9));
        CHECK(facesAt(bb.b, 1, -1, 9).count == 0);
        checkOutward(bb.b, sc);
    }
    SUBCASE("1.5-block post under a cube") {
        Scene sc;
        sc.put(8, 8, 8, POST);
        sc.put(8, 9, 8, CUBE);
        Built bb;
        build(s, sc, bb);
        CHECK(bb.st.tris == 14 + 16);
        CHECK(facesAt(bb.b, 1, +1, 9.5).count == 0);
        CHECK(facesAt(bb.b, 1, -1, 9).area == doctest::Approx(1 - 0.0625));
        checkOutward(bb.b, sc);
    }
    mcrl_shapes_destroy(s);
}

TEST_CASE("mesher: unknown cells are air and mark the build provisional") {
    McrlShapes* s = makeShapes();
    {
        Scene sc;
        sc.put(0, 8, 8, CUBE);
        sc.put(-1, 8, 8, UNKNOWN);
        Built bb;
        build(s, sc, bb);
        CHECK(bb.st.flags == MCRL_BUILT_PROVISIONAL);
        CHECK(bb.b->flags == MCRL_BUILT_PROVISIONAL);
        CHECK(bb.st.tris == 12);
        CHECK(facesAt(bb.b, 0, -1, 0).count == 2);
    }
    {
        Scene sc;
        sc.put(0, 8, 8, CUBE);
        sc.put(-1, 8, 8, CUBE);
        Built bb;
        build(s, sc, bb);
        CHECK(bb.st.flags == 0);
        CHECK(bb.st.tris == 10);
    }
    {
        Scene sc;
        sc.put(16, 16, 16, UNKNOWN);
        Built bb;
        build(s, sc, bb);
        CHECK(bb.st.flags == MCRL_BUILT_PROVISIONAL);
        CHECK(bb.st.tris == 0);
    }
    mcrl_shapes_destroy(s);
}

TEST_CASE("mesher: rejects bad input") {
    McrlShapes* s = makeShapes();
    Scene sc;
    McrlBuildStats st{};
    McrlSectionInput in{0, 0, 0, 1, 1, 0, sc.cells.data()};
    sc.raw(4, 4, 4, LAST_SHAPE + 1);
    CHECK(mcrl_section_build(s, &in, &st) == nullptr);
    CHECK(std::string(mcrl_last_error()).find("shape id") != std::string::npos);
    sc.raw(-1, -1, -1, 0xFFFE);
    sc.raw(4, 4, 4, CUBE);
    CHECK(mcrl_section_build(s, &in, &st) == nullptr);
    sc.raw(-1, -1, -1, 0);
    McrlSectionInput nc = in;
    nc.cells = nullptr;
    CHECK(mcrl_section_build(s, &nc, &st) == nullptr);
    CHECK(mcrl_section_build(nullptr, &in, &st) == nullptr);
    CHECK(mcrl_section_build(s, nullptr, &st) == nullptr);
    McrlBuilt* b = mcrl_section_build(s, &in, nullptr);
    REQUIRE(b != nullptr);
    CHECK(std::string(mcrl_last_error()) == "");
    CHECK(b->tris == 12);
    CHECK(mcrl_built_free(b) == MCRL_OK);
    CHECK(mcrl_built_free(nullptr) == MCRL_ERR_ARG);
    mcrl_shapes_destroy(s);
}

namespace {
using IVec = std::array<int64_t, 3>;

}

TEST_CASE("mesher: full-cube fast path matches the general path") {
    McrlShapes* s = makeShapes();
    mcrl::MesherOptions general;
    general.fullCubeFastPath = false;
    for (uint32_t seed = 1; seed <= 20; seed++) {
        CAPTURE(seed);
        std::mt19937 rng(seed);
        Window w(2);
        for (uint16_t& c : w.cells) {
            const uint32_t r = rng() % 100;
            c = r < 30 ? CUBE : r < 40 ? SLAB : r < 45 ? FENCE : 0;
        }
        const McrlSectionInput in{0, 0, 0, 1, 2, 0, w.cells.data()};
        const std::unique_ptr<McrlBuilt> fast = mcrl::section_build(*s, in, nullptr);
        const std::unique_ptr<McrlBuilt> slow = mcrl::section_build(*s, in, nullptr, general);
        REQUIRE(fast != nullptr);
        REQUIRE(slow != nullptr);
        REQUIRE(fast->tris > 0);
        CHECK(fast->tris == slow->tris);
        CHECK(fast->verts == slow->verts);
        CHECK(fast->idx == slow->idx);
    }
    mcrl_shapes_destroy(s);
}

TEST_CASE("mesher: edge info matches Bullet's generator") {
    McrlShapes* s = makeShapes();
    mcrl::MesherOptions mine, reference;
    mine.keepSkirt = true;
    reference.keepSkirt = true;
    reference.bvhEdgeInfo = true;
    enum { FLAT, CONVEX, CONCAVE };
    int kinds[2][3] = {};
    for (uint32_t seed = 1; seed <= 10; seed++) {
        CAPTURE(seed);
        std::mt19937 rng(seed);
        Window w(2);
        const uint16_t ids[] = {CUBE, CUBE, CUBE, SLAB, TOP_SLAB, STAIRS_N, FENCE, FENCE_X, HALF_X};
        for (uint16_t& c : w.cells) c = rng() % 100 < 35 ? ids[rng() % std::size(ids)] : 0;
        const McrlSectionInput in{0, 0, 0, 1, 2, 0, w.cells.data()};
        const std::unique_ptr<McrlBuilt> fast = mcrl::section_build(*s, in, nullptr, mine);
        const std::unique_ptr<McrlBuilt> ref = mcrl::section_build(*s, in, nullptr, reference);
        REQUIRE(fast != nullptr);
        REQUIRE(ref != nullptr);
        REQUIRE(fast->tris > 0);
        REQUIRE(fast->tris == ref->tris);
        REQUIRE(fast->idx.size() > 3 * (size_t)fast->tris);
        REQUIRE(fast->verts == ref->verts);
        REQUIRE(fast->idx == ref->idx);
        std::map<std::pair<int32_t, int32_t>, int> perEdge;
        for (size_t t = 0; t < fast->idx.size() / 3; t++)
            for (int e = 0; e < 3; e++) {
                const int32_t i = fast->idx[3 * t + e], j = fast->idx[3 * t + (e + 1) % 3];
                perEdge[{std::min(i, j), std::max(i, j)}]++;
            }
        auto onBorder = [&](int32_t i, int32_t j) {
            for (int a = 0; a < 3; a++) {
                const float p = fast->verts[3 * i + a], q = fast->verts[3 * j + a];
                if (p == q && (p == 0 || p == 16)) return true;
            }
            return false;
        };
        for (int t = 0; t < fast->tris; t++) {
            const btHashInt key((0 << (31 - MAX_NUM_PARTS_IN_BITS)) | t);
            const btTriangleInfo* a = fast->info->find(key);
            const btTriangleInfo* b = ref->info->find(key);
            for (int e = 0; e < 3; e++) {
                const int32_t i = fast->idx[3 * t + e], j = fast->idx[3 * t + (e + 1) % 3];
                if (perEdge[{std::min(i, j), std::max(i, j)}] != 2) continue;
                INFO("triangle ", t, " edge ", e);
                REQUIRE(a != nullptr);
                REQUIRE(b != nullptr);
                const float angA[3] = {a->m_edgeV0V1Angle, a->m_edgeV1V2Angle, a->m_edgeV2V0Angle};
                const float angB[3] = {b->m_edgeV0V1Angle, b->m_edgeV1V2Angle, b->m_edgeV2V0Angle};
                const int mask = (1 << e) | (8 << e);
                CHECK(std::memcmp(&angA[e], &angB[e], sizeof(float)) == 0);
                CHECK((a->m_flags & mask) == (b->m_flags & mask));
                kinds[onBorder(i, j)][std::abs(angA[e]) < 1e-3 ? FLAT : (a->m_flags & (1 << e)) ? CONVEX : CONCAVE]++;
            }
        }
    }
    for (int border = 0; border < 2; border++)
        for (int kind = FLAT; kind <= CONCAVE; kind++) {
            CAPTURE(border);
            CAPTURE(kind);
            CHECK(kinds[border][kind] > 0);
        }
    mcrl_shapes_destroy(s);
}
