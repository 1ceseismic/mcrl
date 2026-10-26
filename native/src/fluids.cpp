#include "fluids.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "mesher.h"
#include "world.h"

using namespace RocketSim;

namespace mcrl::fluids {
namespace {
constexpr double CAR_DENSITY = 1.5;
constexpr double BALL_DENSITY = 0.5;
constexpr double CAR_DRAG = 2.0;
constexpr double CAR_ANG_DRAG = 3.0;
constexpr double BALL_DRAG = 1.5;
constexpr double BALL_ANG_DRAG = 2.0;
constexpr double MEDIUM_DRAG = 6.0;
constexpr double MEDIUM_ANG_DRAG = 6.0;
constexpr double WET_ON = 0.10;
constexpr double WET_OFF = 0.05;
constexpr double SPLASH_MIN_VY = 150;
constexpr double LIMIT = 1e9;

constexpr uint8_t type_of(uint8_t b) { return b & 3; }
constexpr int height_of(uint8_t b) { return b >> 2; }

inline int64_t floorDiv16(int64_t a) { return a >= 0 ? a / 16 : -((-a + 15) / 16); }

uint8_t cell_at(const McrlWorld& w, double mx, double my, double mz) {
    if (!(std::abs(mx) < LIMIT && std::abs(my) < LIMIT && std::abs(mz) < LIMIT)) return 0;
    const int64_t c[3] = {(int64_t)std::floor(mx), (int64_t)std::floor(my), (int64_t)std::floor(mz)};
    const SecKey k{(int32_t)floorDiv16(c[0]), (int32_t)floorDiv16(c[1]), (int32_t)floorDiv16(c[2])};
    const uint8_t* plane = w.terrain->fluid(k);
    if (!plane) return 0;
    return plane[core_index((int)(c[0] - 16 * (int64_t)k.x), (int)(c[1] - 16 * (int64_t)k.y),
                            (int)(c[2] - 16 * (int64_t)k.z))];
}

bool in_fluid(uint8_t c, double my) { return type_of(c) != 0 && my - std::floor(my) < height_of(c) / 9.0; }

struct Immersion {
    double fw = 0, fm = 0;
    uint32_t fluid = 0;
    double surface = 0;
};

void write_wet(McrlWetFx& f, bool& wet, const Immersion& im, const btVector3& v, double cx, double cz, bool count) {
    f.immersion = (float)im.fw;
    f.fluid = im.fluid;
    const bool was = wet;
    if (!wet && im.fw >= WET_ON) wet = true;
    else if (wet && im.fw < WET_OFF) wet = false;
    if (was || !wet || !count) return;
    const double vx = v.x() * (double)BT_TO_UU, vy = v.z() * (double)BT_TO_UU, vz = v.y() * (double)BT_TO_UU;
    if (std::abs(vy) < SPLASH_MIN_VY) return;
    f.splashes++;
    f.splashSpeed = (float)std::sqrt(0.2 * vx * vx + vy * vy + 0.2 * vz * vz);
    f.sx = cx;
    f.sy = im.surface;
    f.sz = cz;
}

void edit(btRigidBody& rb, const Immersion& im, double density, double drag, double angDrag, double g, double dt) {
    if (im.fw == 0 && im.fm == 0) return;
    btVector3 v = rb.getLinearVelocity();
    v.setZ(v.z() + (btScalar)(g * im.fw / density * dt));
    v *= (btScalar)std::exp(-(drag * im.fw + MEDIUM_DRAG * im.fm) * dt);
    rb.setLinearVelocity(v);
    rb.setAngularVelocity(rb.getAngularVelocity() *
                          (btScalar)std::exp(-(angDrag * im.fw + MEDIUM_ANG_DRAG * im.fm) * dt));
}

Immersion car_immersion(const McrlWorld& w, const Car& car) {
    const CarConfig& cfg = car.config;
    const btTransform& t = car._rigidBody.getWorldTransform();
    const btVector3 half = btVector3(cfg.hitboxSize.x, cfg.hitboxSize.y, cfg.hitboxSize.z) * (UU_TO_BT / 2);
    const btVector3 centre(cfg.hitboxPosOffset.x * UU_TO_BT, cfg.hitboxPosOffset.y * UU_TO_BT,
                           cfg.hitboxPosOffset.z * UU_TO_BT);
    Immersion im;
    int wet = 0, medium = 0;
    bool water = false, lava = false;
    double top = -std::numeric_limits<double>::infinity();
    for (int i = 0; i < 9; i++) {
        btVector3 local = centre;
        if (i < 8)
            local += btVector3(i & 1 ? half.x() : -half.x(), i & 2 ? half.y() : -half.y(), i & 4 ? half.z() : -half.z());
        const btVector3 p = t(local);
        const double mx = w.ox + (double)p.x(), my = w.oy + (double)p.z(), mz = w.oz + (double)p.y();
        const uint8_t c = cell_at(w, mx, my, mz);
        if (!in_fluid(c, my)) continue;
        const uint8_t type = type_of(c);
        if (type == MCRL_FLUID_MEDIUM) {
            medium++;
            continue;
        }
        wet++;
        water = water || type == MCRL_FLUID_WATER;
        lava = lava || type == MCRL_FLUID_LAVA;
        if (my > top) {
            top = my;
            im.surface = std::floor(my) + height_of(c) / 9.0;
        }
    }
    im.fw = wet / 9.0;
    im.fm = medium / 9.0;
    im.fluid = lava ? MCRL_FLUID_LAVA : water ? MCRL_FLUID_WATER : medium > 0 ? MCRL_FLUID_MEDIUM : 0;
    return im;
}

struct Column {
    double surface = 0;
    uint8_t type = 0;
};
Column column(const McrlWorld& w, double x, double z, double cy, double r) {
    for (int64_t y = (int64_t)std::floor(cy + r), y0 = (int64_t)std::floor(cy - r); y >= y0; y--) {
        const uint8_t c = cell_at(w, x, (double)y + 0.5, z);
        const uint8_t type = type_of(c);
        if (type == MCRL_FLUID_WATER || type == MCRL_FLUID_LAVA) return {(double)y + height_of(c) / 9.0, type};
    }
    return {};
}

Immersion ball_immersion(const McrlWorld& w, double cx, double cy, double cz, double r) {
    Immersion im;
    if (!(std::abs(cx) < LIMIT && std::abs(cy) < LIMIT && std::abs(cz) < LIMIT)) return im;
    const double o = r / 2;
    const double xs[5] = {cx, cx + o, cx - o, cx, cx}, zs[5] = {cz, cz, cz, cz + o, cz - o};
    double sum = 0, top = -std::numeric_limits<double>::infinity();
    bool water = false, lava = false;
    for (int i = 0; i < 5; i++) {
        const Column col = column(w, xs[i], zs[i], cy, r);
        if (col.type == 0) continue;
        const double d = std::clamp(col.surface - (cy - r), 0.0, 2 * r);
        if (d == 0) continue;
        sum += d * d * (3 * r - d) / (4 * r * r * r);
        water = water || col.type == MCRL_FLUID_WATER;
        lava = lava || col.type == MCRL_FLUID_LAVA;
        top = std::max(top, col.surface);
    }
    im.fw = sum / 5;
    if (im.fw > 0) im.surface = top;
    im.fm = type_of(cell_at(w, cx, cy, cz)) == MCRL_FLUID_MEDIUM ? 1 : 0;
    im.fluid = lava ? MCRL_FLUID_LAVA : water ? MCRL_FLUID_WATER : im.fm > 0 ? MCRL_FLUID_MEDIUM : 0;
    return im;
}
}

bool valid_plane(const uint8_t* cells) {
    for (int i = 0; i < SECTION_CELLS; i++) {
        const int h = height_of(cells[i]);
        if (type_of(cells[i]) != 0 && (h < 1 || h > 9)) return false;
    }
    return true;
}

void before_tick(McrlWorld& w) {
    const double dt = w.arena->tickTime;
    const double g = std::abs((double)w.arena->GetMutatorConfig().gravity.z) * UU_TO_BT;
    for (auto& [id, car] : w.cars) {
        McrlCarEvents& ev = w.carEvents.at(id);
        if (car->_internalState.isDemoed) {
            ev.fx.wet.immersion = 0;
            ev.fx.wet.fluid = 0;
            ev.wet = false;
            continue;
        }
        if (ev.held) continue;
        btRigidBody& rb = car->_rigidBody;
        const Immersion im = car_immersion(w, *car);
        const btVector3& o = rb.getWorldTransform().getOrigin();
        write_wet(ev.fx.wet, ev.wet, im, rb.getLinearVelocity(), w.ox + (double)o.x(), w.oz + (double)o.y(),
                  !ev.fxStale);
        edit(rb, im, CAR_DENSITY, CAR_DRAG, CAR_ANG_DRAG, g, dt);
    }
    if (w.ballParked) return;
    Ball& ball = *w.arena->ball;
    btRigidBody& rb = ball._rigidBody;
    const btVector3& c = rb.getWorldTransform().getOrigin();
    const double cx = w.ox + (double)c.x(), cy = w.oy + (double)c.z(), cz = w.oz + (double)c.y();
    const Immersion im = ball_immersion(w, cx, cy, cz, ball.GetRadiusBullet());
    write_wet(w.ballFx.wet, w.ballWet, im, rb.getLinearVelocity(), cx, cz, !w.ballFxStale);
    edit(rb, im, BALL_DENSITY, BALL_DRAG, BALL_ANG_DRAG, g, dt);
}

}
