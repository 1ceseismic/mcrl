#pragma once
#include <atomic>
#include <shared_mutex>
#include <vector>
#include "mcrl.h"

namespace mcrl {
struct ShapeRec {
    std::vector<McrlBoxQ> boxes;
    uint32_t flags = 0;
};
constexpr uint16_t UNKNOWN_SHAPE = 0xFFFF;
constexpr int32_t MAX_SHAPE_ID = UNKNOWN_SHAPE - 1;
constexpr int MATERIAL_COUNT = 256;
constexpr McrlMaterial DEFAULT_MATERIAL{.wheelLat = 1, .wheelLong = 1, .chassisFriction = 1, .chassisRestitution = -1,
                                        .ballFriction = 1, .ballRestitution = -1};
inline uint8_t material_of(uint32_t flags) { return (uint8_t)(flags >> MCRL_SHAPE_MATERIAL_SHIFT); }
}

struct McrlShapes {
    mutable std::shared_mutex mu;
    std::vector<mcrl::ShapeRec> recs;
    int32_t reachLo[3] = {0, 0, 0};
    int32_t reachHi[3] = {0, 0, 0};
    McrlMaterial materials[mcrl::MATERIAL_COUNT];
    std::atomic<uint32_t> materialsVersion{0};
};

namespace mcrl {
McrlShapes* shapes_create();
int32_t shapes_register(McrlShapes& s, const McrlBoxQ* boxes, int32_t n, uint32_t flags);
int32_t shapes_count(const McrlShapes& s);
int32_t shapes_set_material(McrlShapes& s, int32_t id, const McrlMaterial* m);
}
