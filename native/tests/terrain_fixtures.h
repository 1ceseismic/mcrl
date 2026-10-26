#pragma once
#include <doctest/doctest.h>
#include <cmath>
#include <cstdint>
#include <numbers>
#include <string>
#include <vector>
#include "mcrl.h"

namespace fx {

constexpr int BORDER = 2;
constexpr int N = 16 + 2 * BORDER;
constexpr int16_t Q = 4096;

inline int32_t sectionOf(double v) { return (int32_t)std::floor(v / 16); }

enum : uint16_t { AIR = 0, CUBE = 1, SLAB = 2, CARPET = 3 };

inline int32_t registerBox(McrlShapes* s, int16_t mnY, int16_t mxY) {
    const McrlBoxQ b{{0, mnY, 0}, {Q, mxY, Q}};
    return mcrl_shapes_register(s, &b, 1, 0);
}

inline int32_t registerLayer(McrlShapes* s, int16_t h) { return registerBox(s, 0, h); }

inline McrlShapes* makeShapes() {
    McrlShapes* s = mcrl_shapes_create();
    REQUIRE(s != nullptr);
    REQUIRE(registerLayer(s, Q) == CUBE);
    REQUIRE(registerLayer(s, Q / 2) == SLAB);
    REQUIRE(registerLayer(s, Q / 16) == CARPET);
    return s;
}

enum : uint16_t { ICE = CARPET + 1 };

inline int32_t registerCube(McrlShapes* s, int material) {
    const McrlBoxQ b{{0, 0, 0}, {Q, Q, Q}};
    return mcrl_shapes_register(s, &b, 1, (uint32_t)material << MCRL_SHAPE_MATERIAL_SHIFT);
}

inline void addIce(McrlShapes* s) { REQUIRE(registerCube(s, 1) == ICE); }

inline void registerMaterial(McrlShapes* s, int id, McrlMaterial m) {
    REQUIRE(mcrl_shapes_set_material(s, id, &m) == MCRL_OK);
}

struct Fill {
    int32_t x0, y0, z0, x1, y1, z1;
    uint16_t id;
};

class Scene {
public:
    Scene& fill(int32_t x0, int32_t y0, int32_t z0, int32_t x1, int32_t y1, int32_t z1, uint16_t id) {
        fills.push_back({x0, y0, z0, x1, y1, z1, id});
        return *this;
    }

    uint16_t at(int32_t x, int32_t y, int32_t z) const {
        for (auto it = fills.rbegin(); it != fills.rend(); ++it)
            if (x >= it->x0 && x <= it->x1 && y >= it->y0 && y <= it->y1 && z >= it->z0 && z <= it->z1)
                return it->id;
        return AIR;
    }

    Scene& fluid(int32_t x0, int32_t y0, int32_t z0, int32_t x1, int32_t y1, int32_t z1, uint8_t b) {
        fluids.push_back({x0, y0, z0, x1, y1, z1, b});
        return *this;
    }

    uint8_t fluidAt(int32_t x, int32_t y, int32_t z) const {
        for (auto it = fluids.rbegin(); it != fluids.rend(); ++it)
            if (x >= it->x0 && x <= it->x1 && y >= it->y0 && y <= it->y1 && z >= it->z0 && z <= it->z1)
                return (uint8_t)it->id;
        return 0;
    }

    std::vector<uint8_t> fluidPlane(int32_t sx, int32_t sy, int32_t sz) const {
        std::vector<uint8_t> c(16 * 16 * 16, 0);
        bool any = false;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    const uint8_t b = fluidAt(16 * sx + x, 16 * sy + y, 16 * sz + z);
                    c[(y * 16 + z) * 16 + x] = b;
                    any = any || b != 0;
                }
        if (!any) c.clear();
        return c;
    }

    std::vector<uint16_t> cells(int32_t sx, int32_t sy, int32_t sz) const {
        std::vector<uint16_t> c(N * N * N, AIR);
        for (int y = -BORDER; y < 16 + BORDER; y++)
            for (int z = -BORDER; z < 16 + BORDER; z++)
                for (int x = -BORDER; x < 16 + BORDER; x++)
                    c[((y + BORDER) * N + (z + BORDER)) * N + (x + BORDER)] =
                        at(16 * sx + x, 16 * sy + y, 16 * sz + z);
        return c;
    }

    McrlBuilt* build(const McrlShapes* shapes, int32_t sx, int32_t sy, int32_t sz, McrlBuildStats* st = nullptr,
                     uint32_t version = 1) const {
        const std::vector<uint16_t> c = cells(sx, sy, sz);
        const McrlSectionInput in{sx, sy, sz, version, BORDER, 0, c.data()};
        McrlBuilt* b = mcrl_section_build(shapes, &in, st);
        REQUIRE_MESSAGE(b != nullptr, std::string(mcrl_last_error()));
        return b;
    }

    int32_t commit(McrlWorld* w, const McrlShapes* shapes, int32_t sx, int32_t sy, int32_t sz) const {
        McrlBuildStats st{};
        McrlBuilt* b = build(shapes, sx, sy, sz, &st);
        const std::vector<uint8_t> plane = fluidPlane(sx, sy, sz);
        if (!plane.empty()) REQUIRE(mcrl_built_set_fluid(b, plane.data()) == MCRL_OK);
        REQUIRE(mcrl_section_commit(w, b) == MCRL_OK);
        return st.tris;
    }

    int64_t commitRange(McrlWorld* w, const McrlShapes* shapes, int32_t sx0, int32_t sy0, int32_t sz0, int32_t sx1,
                        int32_t sy1, int32_t sz1) const {
        int64_t tris = 0;
        for (int32_t sy = sy0; sy <= sy1; sy++)
            for (int32_t sz = sz0; sz <= sz1; sz++)
                for (int32_t sx = sx0; sx <= sx1; sx++) tris += commit(w, shapes, sx, sy, sz);
        return tris;
    }

private:
    std::vector<Fill> fills;
    std::vector<Fill> fluids;
};

inline Scene floorScene() { return Scene().fill(-16, 63, -16, 31, 63, 31, CUBE); }

inline int64_t commitStandard(McrlWorld* w, const McrlShapes* shapes, const Scene& sc) {
    return sc.commitRange(w, shapes, -1, 3, -1, 1, 5, 1);
}

inline McrlWorld* makeWorld(const McrlShapes* shapes, int32_t ox = 0, int32_t oy = 0, int32_t oz = 0) {
    REQUIRE(mcrl_init() == MCRL_OK);
    McrlWorld* w = mcrl_world_create(shapes, ox, oy, oz);
    REQUIRE_MESSAGE(w != nullptr, mcrl_last_error());
    return w;
}

inline McrlCarState carAt(double x, double y, double z, double yawDeg = 0) {
    McrlCarState c{};
    c.body.px = x;
    c.body.py = y;
    c.body.pz = z;
    const double h = yawDeg * std::numbers::pi / 360;
    c.body.qw = (float)std::cos(h);
    c.body.qy = (float)std::sin(h);
    return c;
}

inline McrlCarState getCar(McrlWorld* w, int32_t id) {
    McrlCarState s{};
    REQUIRE(mcrl_car_get_state(w, id, &s) == MCRL_OK);
    return s;
}

inline McrlBallState getBall(McrlWorld* w) {
    McrlBallState b{};
    REQUIRE(mcrl_ball_get_state(w, &b) == MCRL_OK);
    return b;
}

inline void setBall(McrlWorld* w, double x, double y, double z) {
    McrlBallState b{};
    b.body.px = x;
    b.body.py = y;
    b.body.pz = z;
    b.body.qw = 1;
    REQUIRE(mcrl_ball_set_state(w, &b) == MCRL_OK);
}

inline void setControls(McrlWorld* w, int32_t id, float throttle, float steer = 0, bool boost = false,
                        bool jump = false, bool handbrake = false) {
    McrlControls c{};
    c.throttle = throttle;
    c.steer = steer;
    c.boost = boost ? 1 : 0;
    c.jump = jump ? 1 : 0;
    c.handbrake = handbrake ? 1 : 0;
    REQUIRE(mcrl_car_set_controls(w, id, &c) == MCRL_OK);
}

inline void step(McrlWorld* w, int32_t ticks) { REQUIRE(mcrl_step(w, ticks, nullptr) == ticks); }

inline double speed(const McrlBody& b) { return std::sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz); }

}
