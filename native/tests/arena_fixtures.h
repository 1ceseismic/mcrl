#pragma once
#include <doctest/doctest.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <utility>
#include <vector>
#include "mcrl.h"

namespace afx {

constexpr float BT = 1.f / 50;
constexpr float SIDE_X = 4096 * BT;
constexpr float CEILING_Z = 2048 * BT;
constexpr float END_Y = 5120 * BT;
constexpr float GOAL_HALF_W = 892.755f * BT;
constexpr float GOAL_H = 642.775f * BT;
constexpr float GOAL_DEPTH = 880 * BT;
constexpr int FILES = 16;

constexpr int32_t OX = 0, OY = 64, OZ = 0;

struct V3 {
    float x, y, z;
};

inline V3 sub(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline float dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }

class Cmf {
public:
    Cmf& quad(V3 a, V3 b, V3 c, V3 d, V3 n) {
        if (dot(cross(sub(b, a), sub(c, a)), n) < 0) std::swap(b, d);
        const int32_t i = (int32_t)verts.size();
        for (V3 v : {a, b, c, d}) verts.push_back(v);
        tris.insert(tris.end(), {i, i + 1, i + 2, i, i + 2, i + 3});
        return *this;
    }

    std::vector<uint8_t> bytes() const {
        std::vector<uint8_t> out;
        auto put = [&out](const void* p, size_t n) {
            const uint8_t* b = static_cast<const uint8_t*>(p);
            out.insert(out.end(), b, b + n);
        };
        const int32_t nTris = (int32_t)tris.size() / 3, nVerts = (int32_t)verts.size();
        put(&nTris, 4);
        put(&nVerts, 4);
        put(tris.data(), tris.size() * 4);
        for (const V3& v : verts) put(&v, 12);
        return out;
    }

private:
    std::vector<int32_t> tris;
    std::vector<V3> verts;
};

inline std::vector<std::vector<uint8_t>> syntheticFiles() {
    std::vector<std::vector<uint8_t>> files;
    for (int q = 0; q < 4; q++) {
        const float sx = q % 2 ? 1.f : -1.f, sy = q < 2 ? -1.f : 1.f;
        const float y0 = sy * END_Y, y1 = sy * (END_Y + GOAL_DEPTH), xm = sx * GOAL_HALF_W, xs = sx * SIDE_X;
        const V3 toField{0, -sy, 0};
        files.push_back(Cmf()
                            .quad({xm, y0, 0}, {xs, y0, 0}, {xs, y0, CEILING_Z}, {xm, y0, CEILING_Z}, toField)
                            .quad({0, y0, GOAL_H}, {xm, y0, GOAL_H}, {xm, y0, CEILING_Z}, {0, y0, CEILING_Z}, toField)
                            .bytes());
        files.push_back(Cmf().quad({0, y1, 0}, {xm, y1, 0}, {xm, y1, GOAL_H}, {0, y1, GOAL_H}, toField).bytes());
        files.push_back(
            Cmf().quad({xm, y0, 0}, {xm, y1, 0}, {xm, y1, GOAL_H}, {xm, y0, GOAL_H}, {-sx, 0, 0}).bytes());
        files.push_back(
            Cmf().quad({0, y0, GOAL_H}, {xm, y0, GOAL_H}, {xm, y1, GOAL_H}, {0, y1, GOAL_H}, {0, 0, -1}).bytes());
    }
    return files;
}

inline McrlArenaMeshes* load(const std::vector<std::vector<uint8_t>>& files, uint32_t* outHashes = nullptr) {
    std::vector<const uint8_t*> ptrs;
    std::vector<int32_t> sizes;
    for (const auto& f : files) {
        ptrs.push_back(f.data());
        sizes.push_back((int32_t)f.size());
    }
    std::vector<uint32_t> hashes(files.size());
    McrlArenaMeshes* m = mcrl_arena_meshes_load(ptrs.data(), sizes.data(), (int32_t)files.size(), hashes.data());
    if (m && outHashes) std::memcpy(outHashes, hashes.data(), hashes.size() * 4);
    return m;
}

inline McrlArenaMeshes* syntheticMeshes() {
    static McrlArenaMeshes* const m = [] {
        McrlArenaMeshes* r = load(syntheticFiles());
        REQUIRE_MESSAGE(r != nullptr, std::string(mcrl_last_error()));
        return r;
    }();
    return m;
}

inline McrlWorld* makeStadium() {
    McrlWorld* w = mcrl_world_create_soccar(syntheticMeshes(), OX, OY, OZ);
    REQUIRE_MESSAGE(w != nullptr, std::string(mcrl_last_error()));
    return w;
}

inline McrlSoccarState getSoccar(McrlWorld* w) {
    McrlSoccarState s{};
    REQUIRE(mcrl_soccar_get_state(w, &s) == MCRL_OK);
    return s;
}

}
