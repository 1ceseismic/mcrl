#include "shapes.h"

#include <algorithm>
#include <cmath>
#include <iterator>
#include <memory>
#include <mutex>

#include "error.h"

namespace mcrl {
namespace {
constexpr int32_t Q = 4096;
}

McrlShapes* shapes_create() {
    auto s = std::make_unique<McrlShapes>();
    s->recs.emplace_back();
    std::fill(std::begin(s->materials), std::end(s->materials), DEFAULT_MATERIAL);
    return s.release();
}

int32_t shapes_register(McrlShapes& s, const McrlBoxQ* boxes, int32_t n, uint32_t flags) {
    static const char* fn = "mcrl_shapes_register";
    if (n < 0) return fail(MCRL_ERR_ARG, fn, "n < 0");
    if (n > 0 && !boxes) return fail(MCRL_ERR_ARG, fn, "boxes is NULL");
    ShapeRec rec;
    rec.flags = flags;
    rec.boxes.reserve((size_t)n);
    for (int32_t i = 0; i < n; i++) {
        const McrlBoxQ& b = boxes[i];
        bool hasVolume = true;
        for (int a = 0; a < 3; a++) {
            if (b.mn[a] > b.mx[a]) return fail(MCRL_ERR_ARG, fn, "box with mn > mx");
            if (b.mn[a] == b.mx[a]) hasVolume = false;
        }
        if (hasVolume) rec.boxes.push_back(b);
    }
    int32_t lo[3] = {0, 0, 0}, hi[3] = {0, 0, 0};
    for (const McrlBoxQ& b : rec.boxes)
        for (int a = 0; a < 3; a++) {
            if (b.mn[a] < 0) lo[a] = std::max(lo[a], (-b.mn[a] + Q - 1) / Q);
            if (b.mx[a] > Q) hi[a] = std::max(hi[a], (b.mx[a] - 1) / Q);
        }
    std::unique_lock lock(s.mu);
    if ((int32_t)s.recs.size() > MAX_SHAPE_ID) return fail(MCRL_ERR_ARG, fn, "registry full (largest id is 0xFFFE)");
    s.recs.push_back(std::move(rec));
    for (int a = 0; a < 3; a++) {
        s.reachLo[a] = std::max(s.reachLo[a], lo[a]);
        s.reachHi[a] = std::max(s.reachHi[a], hi[a]);
    }
    return (int32_t)s.recs.size() - 1;
}

int32_t shapes_count(const McrlShapes& s) {
    std::shared_lock lock(s.mu);
    return (int32_t)s.recs.size();
}

int32_t shapes_set_material(McrlShapes& s, int32_t id, const McrlMaterial* m) {
    static const char* fn = "mcrl_shapes_set_material";
    if (!m) return fail(MCRL_ERR_ARG, fn, "material is NULL");
    if (id < 1 || id >= MATERIAL_COUNT) return fail(MCRL_ERR_ARG, fn, "id must be 1..255");
    const float v[] = {m->wheelLat,     m->wheelLong,       m->wheelDrag, m->chassisFriction, m->chassisRestitution,
                       m->ballFriction, m->ballRestitution, m->ballDrag,  m->padRestitution};
    for (const float x : v)
        if (!std::isfinite(x)) return fail(MCRL_ERR_ARG, fn, "non-finite value");
    if (m->wheelLat < 0 || m->wheelLong < 0 || m->chassisFriction < 0 || m->ballFriction < 0)
        return fail(MCRL_ERR_ARG, fn, "negative scale");
    if (m->wheelDrag < 0 || m->ballDrag < 0) return fail(MCRL_ERR_ARG, fn, "negative drag");
    if (m->chassisRestitution > 1 || m->ballRestitution > 1) return fail(MCRL_ERR_ARG, fn, "restitution above 1");
    if (m->padRestitution < 0 || m->padRestitution > 1) return fail(MCRL_ERR_ARG, fn, "padRestitution outside [0, 1]");
    McrlMaterial copy = *m;
    copy.pad = 0;
    std::unique_lock lock(s.mu);
    s.materials[id] = copy;
    s.materialsVersion.fetch_add(1, std::memory_order_release);
    return MCRL_OK;
}
}
