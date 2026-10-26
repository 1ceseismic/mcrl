#include "mesher.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_map>

#include "BulletCollision/CollisionDispatch/btInternalEdgeUtility.h"
#include "edgeinfo.h"
#include "error.h"

namespace mcrl {
namespace {
constexpr int32_t Q = 4096;
constexpr bool QUANTIZED_AABB = true;

struct IBox {
    int32_t mn[3], mx[3];
};

struct Quad {
    int32_t a, s, c, u0, u1, v0, v1;
    uint8_t mat;
};

std::nullptr_t no_build(const std::string& what) {
    fail(MCRL_ERR_ARG, "mcrl_section_build", what);
    return nullptr;
}

inline int32_t floorDiv(int32_t a, int32_t b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

bool isFullCube(const ShapeRec& r) {
    if (r.boxes.size() != 1) return false;
    const McrlBoxQ& b = r.boxes[0];
    return b.mn[0] == 0 && b.mn[1] == 0 && b.mn[2] == 0 && b.mx[0] == Q && b.mx[1] == Q && b.mx[2] == Q;
}

constexpr int32_t KOFF = 1 << 16;
constexpr uint64_t KMASK = (1u << 18) - 1;
inline uint64_t planeKey(int32_t a, int32_t s, int32_t c) {
    return (uint64_t)a << 19 | (uint64_t)(s > 0) << 18 | (uint64_t)(c + KOFF);
}
inline uint64_t cornerKey(uint64_t plane, int32_t w, int32_t t) {
    return plane << 36 | (uint64_t)(w + KOFF) << 18 | (uint64_t)(t + KOFF);
}

class Mesher {
public:
    Mesher(int border, const MesherOptions& opt)
        : B(border), N(16 + 2 * border), m2(border == 2), fast(opt.fullCubeFastPath) {}

    const int B;
    const int N;
    const bool m2;
    const bool fast;
    std::vector<IBox> boxes;
    std::vector<uint8_t> boxMat;
    std::vector<int32_t> cellStart;
    std::vector<uint8_t> fullCube;
    std::vector<Quad> quads;
    std::vector<float> verts;
    std::vector<int32_t> idx;
    std::vector<uint8_t> triMat;

    int cellIndex(int x, int y, int z) const { return ((y + B) * N + (z + B)) * N + (x + B); }

    void meshCore() {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) cell(x, y, z);
    }

    void meshSkirt() {
        for (int y = -1; y <= 16; y++)
            for (int z = -1; z <= 16; z++)
                for (int x = -1; x <= 16; x++)
                    if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) cell(x, y, z);
    }

    void indexCorners() {
        byV.clear();
        byU.clear();
        byV.reserve(4 * quads.size());
        byU.reserve(4 * quads.size());
        for (const Quad& f : quads) {
            const uint64_t p = planeKey(f.a, f.s, f.c);
            for (const int32_t u : {f.u0, f.u1})
                for (const int32_t v : {f.v0, f.v1}) {
                    byV.push_back(cornerKey(p, v, u));
                    byU.push_back(cornerKey(p, u, v));
                }
        }
        for (std::vector<uint64_t>* k : {&byV, &byU}) {
            std::sort(k->begin(), k->end());
            k->erase(std::unique(k->begin(), k->end()), k->end());
        }
    }

    void emit(size_t q0, size_t q1) {
        for (size_t q = q0; q < q1; q++) emitQuad(quads[q]);
    }

private:
    std::unordered_map<uint64_t, int32_t> weld;
    std::vector<IBox> occ;
    std::vector<int32_t> hit;
    std::vector<int32_t> us, vs;
    std::vector<uint64_t> byV, byU;
    std::vector<int32_t> side;
    std::vector<int32_t> pu, pv;
    std::vector<int32_t> ids;

    void cell(int x, int y, int z) {
        const int c = cellIndex(x, y, z);
        if (fast && fullCube[c]) {
            for (int a = 0; a < 3; a++)
                for (int s = -1; s <= 1; s += 2) {
                    int n[3] = {x, y, z};
                    n[a] += s;
                    if (!fullCube[cellIndex(n[0], n[1], n[2])]) face(cellStart[c], a, s, x, y, z);
                }
            return;
        }
        for (int32_t i = cellStart[c]; i < cellStart[c + 1]; i++)
            for (int a = 0; a < 3; a++) {
                face(i, a, -1, x, y, z);
                face(i, a, +1, x, y, z);
            }
    }

    void face(int32_t bi, int a, int s, int cx, int cy, int cz) {
        const IBox& b = boxes[bi];
        const int u = (a + 1) % 3, v = (a + 2) % 3;
        const int32_t c = s > 0 ? b.mx[a] : b.mn[a];
        occ.clear();
        for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    const int n = cellIndex(cx + dx, cy + dy, cz + dz);
                    for (int32_t i = cellStart[n]; i < cellStart[n + 1]; i++) {
                        const IBox& o = boxes[i];
                        const bool slab = s > 0 ? (o.mn[a] <= c && c < o.mx[a]) : (o.mn[a] < c && c <= o.mx[a]);
                        const bool tie = m2 && i < bi && (s > 0 ? o.mx[a] : o.mn[a]) == c;
                        if (!slab && !tie) continue;
                        if (o.mn[u] >= b.mx[u] || o.mx[u] <= b.mn[u] || o.mn[v] >= b.mx[v] || o.mx[v] <= b.mn[v])
                            continue;
                        occ.push_back(o);
                    }
                }
        for (int32_t u0 = b.mn[u]; u0 < b.mx[u];) {
            const int32_t u1 = std::min(b.mx[u], (floorDiv(u0, Q) + 1) * Q);
            for (int32_t v0 = b.mn[v]; v0 < b.mx[v];) {
                const int32_t v1 = std::min(b.mx[v], (floorDiv(v0, Q) + 1) * Q);
                piece(a, s, c, u0, u1, v0, v1, boxMat[bi]);
                v0 = v1;
            }
            u0 = u1;
        }
    }

    void piece(int a, int s, int32_t c, int32_t u0, int32_t u1, int32_t v0, int32_t v1, uint8_t mat) {
        const int u = (a + 1) % 3, v = (a + 2) % 3;
        hit.clear();
        for (int32_t i = 0; i < (int32_t)occ.size(); i++) {
            const IBox& o = occ[i];
            if (o.mn[u] >= u1 || o.mx[u] <= u0 || o.mn[v] >= v1 || o.mx[v] <= v0) continue;
            if (o.mn[u] <= u0 && o.mx[u] >= u1 && o.mn[v] <= v0 && o.mx[v] >= v1) return;
            hit.push_back(i);
        }
        if (hit.empty()) {
            quads.push_back({a, s, c, u0, u1, v0, v1, mat});
            return;
        }
        us.assign({u0, u1});
        vs.assign({v0, v1});
        for (int32_t i : hit) {
            const IBox& o = occ[i];
            if (o.mn[u] > u0) us.push_back(o.mn[u]);
            if (o.mx[u] < u1) us.push_back(o.mx[u]);
            if (o.mn[v] > v0) vs.push_back(o.mn[v]);
            if (o.mx[v] < v1) vs.push_back(o.mx[v]);
        }
        std::sort(us.begin(), us.end());
        us.erase(std::unique(us.begin(), us.end()), us.end());
        std::sort(vs.begin(), vs.end());
        vs.erase(std::unique(vs.begin(), vs.end()), vs.end());
        for (size_t i = 0; i + 1 < us.size(); i++)
            for (size_t j = 0; j + 1 < vs.size(); j++) {
                bool covered = false;
                for (int32_t k : hit) {
                    const IBox& o = occ[k];
                    if (o.mn[u] <= us[i] && o.mx[u] >= us[i + 1] && o.mn[v] <= vs[j] && o.mx[v] >= vs[j + 1]) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) quads.push_back({a, s, c, us[i], us[i + 1], vs[j], vs[j + 1], mat});
            }
    }

    size_t between(const std::vector<uint64_t>& sorted, uint64_t p, int32_t w, int32_t lo, int32_t hi) {
        side.clear();
        const uint64_t end = cornerKey(p, w, hi);
        for (auto it = std::upper_bound(sorted.begin(), sorted.end(), cornerKey(p, w, lo));
             it != sorted.end() && *it < end; ++it)
            side.push_back((int32_t)(*it & KMASK) - KOFF);
        return side.size();
    }

    void emitQuad(const Quad& f) {
        const int32_t cu[4] = {f.u0, f.u1, f.u1, f.u0}, cv[4] = {f.v0, f.v0, f.v1, f.v1};
        const uint64_t p = planeKey(f.a, f.s, f.c);
        size_t cnt[4] = {0, 0, 0, 0};
        pu.clear();
        pv.clear();
        for (int k = 0; k < 4; k++) {
            pu.push_back(cu[k]);
            pv.push_back(cv[k]);
            if (!m2) continue;
            const int32_t nu = cu[(k + 1) % 4], nv = cv[(k + 1) % 4];
            const bool alongU = nv == cv[k];
            const int32_t from = alongU ? cu[k] : cv[k], to = alongU ? nu : nv;
            cnt[k] = alongU ? between(byV, p, nv, std::min(from, to), std::max(from, to))
                            : between(byU, p, nu, std::min(from, to), std::max(from, to));
            if (to < from) std::reverse(side.begin(), side.end());
            for (const int32_t t : side) {
                pu.push_back(alongU ? t : nu);
                pv.push_back(alongU ? nv : t);
            }
        }
        const size_t n = pu.size();

        const int u = (f.a + 1) % 3, v = (f.a + 2) % 3;
        ids.resize(n);
        for (size_t i = 0; i < n; i++) {
            const size_t src = f.s > 0 ? i : (n - i) % n;
            int32_t q[3];
            q[f.a] = 2 * f.c;
            q[u] = 2 * pu[src];
            q[v] = 2 * pv[src];
            ids[i] = vertex(q);
        }
        size_t ins[4], pos[4] = {0, 0, 0, 0};
        for (int k = 0; k < 4; k++) ins[k] = f.s > 0 ? cnt[k] : cnt[3 - k];
        for (int k = 0; k < 3; k++) pos[k + 1] = pos[k] + 1 + ins[k];

        // The y/z swap to Bullet axes flips the winding.
        auto tri = [this, &f](int32_t i0, int32_t i1, int32_t i2) {
            const int32_t t[3] = {i0, i2, i1};
            idx.insert(idx.end(), t, t + 3);
            triMat.push_back(f.mat);
        };
        for (int k = 0; k < 4; k++) {
            if (ins[k] != 0 || ins[(k + 3) % 4] != 0) continue;
            const size_t apex = pos[k];
            for (size_t j = 1; j + 1 < n; j++) tri(ids[apex], ids[(apex + j) % n], ids[(apex + j + 1) % n]);
            return;
        }
        int32_t m[3];
        m[f.a] = 2 * f.c;
        m[u] = f.u0 + f.u1;
        m[v] = f.v0 + f.v1;
        const int32_t centre = vertex(m);
        for (size_t j = 0; j < n; j++) tri(centre, ids[j], ids[(j + 1) % n]);
    }

    int32_t vertex(const int32_t p[3]) {
        constexpr int64_t OFF = 1 << 20;
        const uint64_t key = (uint64_t)(p[0] + OFF) | (uint64_t)(p[1] + OFF) << 21 | (uint64_t)(p[2] + OFF) << 42;
        auto [it, inserted] = weld.try_emplace(key, (int32_t)(verts.size() / 3));
        if (inserted) {
            verts.push_back((float)p[0] / (2 * Q));
            verts.push_back((float)p[2] / (2 * Q));
            verts.push_back((float)p[1] / (2 * Q));
        }
        return it->second;
    }
};
}

std::unique_ptr<McrlBuilt> section_build(const McrlShapes& shapes, const McrlSectionInput& in, McrlBuildStats* out,
                                         const MesherOptions& opt) {
    const auto t0 = std::chrono::steady_clock::now();
    if (in.border != 1 && in.border != 2) return no_build("border must be 1 or 2");
    if (!in.cells) return no_build("cells is NULL");

    Mesher m(in.border, opt);
    const int B = m.B, N = m.N;
    uint32_t flags = 0;
    m.cellStart.resize(N * N * N + 1);
    m.fullCube.assign(N * N * N, 0);
    {
        std::shared_lock lock(shapes.mu);
        const int32_t count = (int32_t)shapes.recs.size();
        for (int y = -B; y < 16 + B; y++)
            for (int z = -B; z < 16 + B; z++)
                for (int x = -B; x < 16 + B; x++) {
                    const int c = m.cellIndex(x, y, z);
                    m.cellStart[c] = (int32_t)m.boxes.size();
                    const uint16_t id = in.cells[c];
                    if (id == 0) continue;
                    if (id == UNKNOWN_SHAPE) {
                        flags |= MCRL_BUILT_PROVISIONAL;
                        continue;
                    }
                    if (id >= count) {
                        char buf[96];
                        std::snprintf(buf, sizeof buf, "unknown shape id %u at local cell (%d, %d, %d)", id, x, y, z);
                        return no_build(buf);
                    }
                    m.fullCube[c] = isFullCube(shapes.recs[id]);
                    const uint8_t mat = material_of(shapes.recs[id].flags);
                    for (const McrlBoxQ& q : shapes.recs[id].boxes) {
                        const int32_t o[3] = {x * Q, y * Q, z * Q};
                        m.boxes.push_back({{o[0] + q.mn[0], o[1] + q.mn[1], o[2] + q.mn[2]},
                                           {o[0] + q.mx[0], o[1] + q.mx[1], o[2] + q.mx[2]}});
                        m.boxMat.push_back(mat);
                    }
                }
        m.cellStart[N * N * N] = (int32_t)m.boxes.size();
    }

    m.meshCore();
    const size_t coreQuads = m.quads.size();
    if (m.m2 && coreQuads > 0) {
        m.meshSkirt();
        m.indexCorners();
    }
    m.emit(0, coreQuads);
    const int32_t coreTris = (int32_t)(m.idx.size() / 3);
    const size_t coreVerts = m.verts.size() / 3;
    m.emit(coreQuads, m.quads.size());
    const int32_t allTris = (int32_t)(m.idx.size() / 3);

    auto b = std::make_unique<McrlBuilt>();
    b->sx = in.sx;
    b->sy = in.sy;
    b->sz = in.sz;
    b->version = in.version;
    b->flags = flags;
    b->tris = coreTris;
    m.triMat.resize((size_t)coreTris);
    b->triMat = std::move(m.triMat);
    b->cells.resize(SECTION_CELLS);
    for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                const uint16_t id = in.cells[m.cellIndex(x, y, z)];
                b->cells[core_index(x, y, z)] = id == UNKNOWN_SHAPE ? 0 : id;
            }
    if (b->tris > 0) {
        b->info = std::make_unique<btTriangleInfoMap>();
        if (m.m2 && !opt.bvhEdgeInfo) {
            edge_info(m.verts, m.idx, coreTris, *b->info);
        } else if (m.m2) {
            btTriangleIndexVertexArray all(allTris, m.idx.data(), 3 * (int)sizeof(int32_t),
                                           (int)(m.verts.size() / 3), m.verts.data(), 3 * (int)sizeof(float));
            btBvhTriangleMeshShape tmp(&all, QUANTIZED_AABB);
            btGenerateInternalEdgeInfo(&tmp, b->info.get());
        }
        if (!opt.keepSkirt) {
            m.idx.resize(3 * (size_t)coreTris);
            m.verts.resize(3 * coreVerts);
        }
        b->verts = std::move(m.verts);
        b->idx = std::move(m.idx);
        b->iface = std::make_unique<btTriangleIndexVertexArray>(b->tris, b->idx.data(), 3 * (int)sizeof(int32_t),
                                                                (int)(b->verts.size() / 3), b->verts.data(),
                                                                3 * (int)sizeof(float));
        b->shape = std::make_unique<btBvhTriangleMeshShape>(b->iface.get(), QUANTIZED_AABB);
        if (m.m2) b->shape->setTriangleInfoMap(b->info.get());
        else btGenerateInternalEdgeInfo(b->shape.get(), b->info.get());
    }

    if (out) {
        out->tris = b->tris;
        out->buildMs = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t0).count();
        out->flags = b->flags;
    }
    return b;
}
}
