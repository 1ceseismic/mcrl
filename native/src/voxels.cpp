#include "voxels.h"

#include <algorithm>
#include <cmath>
#include <shared_mutex>

namespace mcrl::voxels {
namespace {
constexpr double Q = 4096;
constexpr double LIMIT = 1e9;
constexpr double MAX_SPAN = 512;

bool sane(const double mn[3], const double mx[3]) {
    for (int a = 0; a < 3; a++)
        if (!(std::abs(mn[a]) < LIMIT && std::abs(mx[a]) < LIMIT && mn[a] <= mx[a] && mx[a] - mn[a] <= MAX_SPAN))
            return false;
    return true;
}

int32_t sectionOf(int64_t cell) { return (int32_t)(cell >> 4); }

bool cellRange(const double mn[3], const double mx[3], const int32_t below[3], const int32_t above[3], int64_t c0[3],
               int64_t c1[3]) {
    for (int a = 0; a < 3; a++) {
        c0[a] = (int64_t)std::floor(mn[a]) - below[a];
        c1[a] = (int64_t)std::ceil(mx[a]) - 1 + above[a];
        if (c0[a] > c1[a]) return false;
    }
    return true;
}

double dot(const double a[3], const double b[3]) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

bool queryCells(const McrlShapes& s, const double mn[3], const double mx[3], int64_t c0[3], int64_t c1[3]) {
    return cellRange(mn, mx, s.reachHi, s.reachLo, c0, c1);
}

bool cellsReady(const TerrainStore& t, const int64_t c0[3], const int64_t c1[3]) {
    for (int32_t sy = sectionOf(c0[1]); sy <= sectionOf(c1[1]); sy++)
        for (int32_t sz = sectionOf(c0[2]); sz <= sectionOf(c1[2]); sz++)
            for (int32_t sx = sectionOf(c0[0]); sx <= sectionOf(c1[0]); sx++)
                if (!t.IsReady({sx, sy, sz})) return false;
    return true;
}

template <class F>
bool forEachBox(const TerrainStore& t, const McrlShapes& s, const double mn[3], const double mx[3], F&& fn) {
    int64_t c0[3], c1[3];
    if (!queryCells(s, mn, mx, c0, c1)) return false;
    const size_t nrecs = s.recs.size();
    for (int32_t sy = sectionOf(c0[1]); sy <= sectionOf(c1[1]); sy++)
        for (int32_t sz = sectionOf(c0[2]); sz <= sectionOf(c1[2]); sz++)
            for (int32_t sx = sectionOf(c0[0]); sx <= sectionOf(c1[0]); sx++) {
                const uint16_t* g = t.cells({sx, sy, sz});
                if (!g) continue;
                const int64_t base[3] = {16 * (int64_t)sx, 16 * (int64_t)sy, 16 * (int64_t)sz};
                int lo[3], hi[3];
                for (int a = 0; a < 3; a++) {
                    lo[a] = (int)(std::max(c0[a], base[a]) - base[a]);
                    hi[a] = (int)(std::min(c1[a], base[a] + 15) - base[a]);
                }
                for (int y = lo[1]; y <= hi[1]; y++)
                    for (int z = lo[2]; z <= hi[2]; z++)
                        for (int x = lo[0]; x <= hi[0]; x++) {
                            const uint16_t id = g[core_index(x, y, z)];
                            if (id == 0 || id >= nrecs) continue;
                            const ShapeRec& r = s.recs[id];
                            const double o[3] = {(double)(base[0] + x), (double)(base[1] + y), (double)(base[2] + z)};
                            for (const McrlBoxQ& q : r.boxes) {
                                WBox w;
                                bool hit = true;
                                for (int a = 0; a < 3; a++) {
                                    w.mn[a] = o[a] + q.mn[a] / Q;
                                    w.mx[a] = o[a] + q.mx[a] / Q;
                                    hit = hit && w.mn[a] < mx[a] && w.mx[a] > mn[a];
                                }
                                w.flags = r.flags;
                                if (hit && fn(w)) return true;
                            }
                        }
            }
    return false;
}

bool validObb(const Obb& o) {
    for (int i = 0; i < 3; i++) {
        if (!std::isfinite(o.c[i]) || !std::isfinite(o.half[i]) || o.half[i] < 0) return false;
        for (int k = 0; k < 3; k++)
            if (!std::isfinite(o.axis[i][k])) return false;
    }
    return true;
}
}

bool ready(const TerrainStore& t, const McrlShapes& s, const double mn[3], const double mx[3]) {
    if (!sane(mn, mx)) return false;
    std::shared_lock lock(s.mu);
    int64_t c0[3], c1[3];
    if (!queryCells(s, mn, mx, c0, c1)) return true;
    return cellsReady(t, c0, c1);
}

void boxesIn(const TerrainStore& t, const McrlShapes& s, const double mn[3], const double mx[3],
             std::vector<WBox>& out) {
    out.clear();
    if (!sane(mn, mx)) return;
    std::shared_lock lock(s.mu);
    forEachBox(t, s, mn, mx, [&out](const WBox& w) {
        out.push_back(w);
        return false;
    });
}

bool obbOverlapsAny(const TerrainStore& t, const McrlShapes& s, const Obb& box) {
    if (!validObb(box)) return true;
    double mn[3], mx[3];
    aabbOf(box, mn, mx);
    if (!sane(mn, mx)) return true;
    std::shared_lock lock(s.mu);
    return forEachBox(t, s, mn, mx,
                      [&](const WBox& w) { return overlaps(box, w); });
}

bool overlaps(const Obb& o, const WBox& b) {
    double bh[3], d[3];
    for (int k = 0; k < 3; k++) {
        bh[k] = 0.5 * (b.mx[k] - b.mn[k]);
        d[k] = o.c[k] - 0.5 * (b.mn[k] + b.mx[k]);
    }
    auto separated = [&](const double L[3]) {
        double rA = 0, rB = 0;
        for (int k = 0; k < 3; k++) rA += bh[k] * std::abs(L[k]);
        for (int j = 0; j < 3; j++) rB += o.half[j] * std::abs(dot(o.axis[j], L));
        return std::abs(dot(d, L)) >= rA + rB;
    };
    for (int k = 0; k < 3; k++) {
        double L[3] = {0, 0, 0};
        L[k] = 1;
        if (separated(L)) return false;
    }
    for (int j = 0; j < 3; j++)
        if (separated(o.axis[j])) return false;
    for (int k = 0; k < 3; k++)
        for (int j = 0; j < 3; j++) {
            const double* a = o.axis[j];
            double L[3];
            L[k] = 0;
            L[(k + 1) % 3] = -a[(k + 2) % 3];
            L[(k + 2) % 3] = a[(k + 1) % 3];
            if (dot(L, L) < 1e-18) continue;
            if (separated(L)) return false;
        }
    return true;
}

void aabbOf(const Obb& o, double mn[3], double mx[3]) {
    for (int k = 0; k < 3; k++) {
        double e = 0;
        for (int j = 0; j < 3; j++) e += o.half[j] * std::abs(o.axis[j][k]);
        mn[k] = o.c[k] - e;
        mx[k] = o.c[k] + e;
    }
}
}
