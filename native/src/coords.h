#pragma once
#include <cmath>
#include "RocketSim.h"

namespace mcrl::coords {

using RocketSim::RotMat;
using RocketSim::Vec;

constexpr double UU_PER_BLOCK = 50.0;

inline Vec mcToRsPos(double x, double y, double z, int ox, int oy, int oz) {
    return Vec((float)((x - ox) * UU_PER_BLOCK), (float)((z - oz) * UU_PER_BLOCK), (float)((y - oy) * UU_PER_BLOCK));
}

inline void rsToMcPos(const Vec& p, int ox, int oy, int oz, double out[3]) {
    out[0] = ox + (double)p.x / UU_PER_BLOCK;
    out[1] = oy + (double)p.z / UU_PER_BLOCK;
    out[2] = oz + (double)p.y / UU_PER_BLOCK;
}

inline Vec mcToRsVel(double x, double y, double z) {
    return Vec((float)(x * UU_PER_BLOCK), (float)(z * UU_PER_BLOCK), (float)(y * UU_PER_BLOCK));
}

inline void rsToMcVel(const Vec& v, double out[3]) {
    out[0] = (double)v.x / UU_PER_BLOCK;
    out[1] = (double)v.z / UU_PER_BLOCK;
    out[2] = (double)v.y / UU_PER_BLOCK;
}

inline Vec mcToRsAngVel(double x, double y, double z) {
    return Vec((float)-x, (float)-z, (float)-y);
}

inline void rsToMcAngVel(const Vec& w, double out[3]) {
    out[0] = -(double)w.x;
    out[1] = -(double)w.z;
    out[2] = -(double)w.y;
}

inline RotMat mcQuatToRs(float qw, float qx, float qy, float qz) {
    double w = qw, x = -(double)qx, y = -(double)qz, z = -(double)qy;
    const double n = std::sqrt(w * w + x * x + y * y + z * z);
    w /= n; x /= n; y /= n; z /= n;
    const double r00 = 1 - 2 * (y * y + z * z), r01 = 2 * (x * y - w * z), r02 = 2 * (x * z + w * y);
    const double r10 = 2 * (x * y + w * z), r11 = 1 - 2 * (x * x + z * z), r12 = 2 * (y * z - w * x);
    const double r20 = 2 * (x * z - w * y), r21 = 2 * (y * z + w * x), r22 = 1 - 2 * (x * x + y * y);
    return RotMat(Vec((float)r00, (float)r10, (float)r20),
                  Vec((float)r01, (float)r11, (float)r21),
                  Vec((float)r02, (float)r12, (float)r22));
}

inline void rsRotToMcQuat(const RotMat& m, float out[4]) {
    const double r00 = m.forward.x, r10 = m.forward.y, r20 = m.forward.z;
    const double r01 = m.right.x, r11 = m.right.y, r21 = m.right.z;
    const double r02 = m.up.x, r12 = m.up.y, r22 = m.up.z;
    double w, x, y, z;
    const double tr = r00 + r11 + r22;
    if (tr > 0) {
        const double s = std::sqrt(tr + 1.0) * 2;
        w = s / 4; x = (r21 - r12) / s; y = (r02 - r20) / s; z = (r10 - r01) / s;
    } else if (r00 > r11 && r00 > r22) {
        const double s = std::sqrt(1.0 + r00 - r11 - r22) * 2;
        w = (r21 - r12) / s; x = s / 4; y = (r01 + r10) / s; z = (r02 + r20) / s;
    } else if (r11 > r22) {
        const double s = std::sqrt(1.0 + r11 - r00 - r22) * 2;
        w = (r02 - r20) / s; x = (r01 + r10) / s; y = s / 4; z = (r12 + r21) / s;
    } else {
        const double s = std::sqrt(1.0 + r22 - r00 - r11) * 2;
        w = (r10 - r01) / s; x = (r02 + r20) / s; y = (r12 + r21) / s; z = s / 4;
    }
    const double n = std::sqrt(w * w + x * x + y * y + z * z);
    const double sign = w < 0 ? -1.0 : 1.0;
    out[0] = (float)(sign * w / n);
    out[1] = (float)(sign * -x / n);
    out[2] = (float)(sign * -z / n);
    out[3] = (float)(sign * -y / n);
}

inline void rsLocalToMc(const btVector3& v, float out[3]) {
    out[0] = v.x();
    out[1] = v.z();
    out[2] = v.y();
}

}
