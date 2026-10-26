#ifndef MCRL_RL_BODY_GLSL
#define MCRL_RL_BODY_GLSL

#include <mcrl:rl_scene.glsl>

layout(std140) uniform RlBody {
    vec4 TeamColor;
    vec4 CustomColor;
    vec4 PaintColor;
    vec4 TrimColor;
    vec4 F1ControlA;
    vec4 F2ControlA;
    vec4 F1ControlB;
    vec4 F2ControlB;
    vec4 Rows;
    vec4 TertControlA;
    vec4 TertControlB;
    vec4 TertColor;
    vec4 Srgb0;
    vec4 Srgb1;
};

uniform sampler2D BodyDiffuse;
uniform sampler2D BodySkin;
uniform sampler2D BodyCurvature;
uniform sampler2D BodyMasks;
uniform sampler2D BodyDetail1;
uniform sampler2D BodyDetail2;
uniform sampler2D BodyTertiaryNormal;
uniform sampler2D LightFalloff;
uniform sampler2D EnvPack;

const float RL_BODY_ROW = 0.003908;
const float RL_BODY_VIEW_ROW = 0.015626;
const float RL_BODY_UNPAINTED = 0.25;
const float RL_BODY_GLASS = 0.5;

vec4 rl_body_lf(float x, float row) {
    return texture(LightFalloff, vec2(x, row));
}

vec4 rl_body_tertiary_texel(vec2 uv) {
    return texture(BodyTertiaryNormal, uv * Rows.w * 0.25);
}

vec3 rl_body_sky_normal(vec2 uv) {
    vec3 tn = rl_nmap_rgb(rl_body_tertiary_texel(uv));
    vec3 tTn = vec3(TertControlA.w * tn.xy, tn.z);
    return normalize(mix(vec3(0.0, 0.0, 1.0), tTn, rl_tex(BodyMasks, uv, Srgb0.w).a));
}

struct RlBodySurface {
    vec3 v;
    vec3 rw;
    vec4 d;
    vec4 skin;
    float mR;
    float mA;
    float mT;
    vec4 tnS;
    vec3 nd;
    vec3 ns;
    float row1;
    float row2;
    float rowG;
    vec3 diffCol;
    vec3 specTint;
    float specInt;
    vec3 refl;
    float occl;
};

RlBodySurface rl_body_surface(vec2 uv, mat3 twr, vec3 Vts, float depthUu, vec3 ground) {
    RlBodySurface s;
    vec3 e = vec3(0.0, 0.0, 1.0);
    s.v = normalize(Vts);
    vec3 r = reflect(-s.v, rl_body_sky_normal(uv));
    s.mT = rl_tex(BodyMasks, uv, Srgb0.w).a;
    s.d = rl_tex(BodyDiffuse, uv, Srgb0.x);
    s.skin = rl_tex(BodySkin, uv, Srgb0.y);
    s.mR = rl_sat((s.skin.r - 0.5) * 4.0);
    float mB = rl_sat((s.skin.b - 0.5) * 4.0);
    s.mA = rl_sat((s.skin.a - 0.5) * 4.0);
    vec4 dn1 = texture(BodyDetail1, uv * 4.0);
    vec4 dn2 = texture(BodyDetail2, uv * 4.0);
    vec3 dd = mix(rl_nmap_rgb(dn1), rl_nmap_rgb(dn2), s.mA) - e;
    vec4 ca = mix(F1ControlA, F2ControlA, s.mA) * s.mR;
    vec4 cb = mix(F1ControlB, F2ControlB, s.mA);
    s.nd = e + ca.z * dd;
    vec3 nf = e + (ca.y * 0.5 * ca.z) * dd;
    s.ns = e + ca.w * dd;
    float nvF = dot(nf, s.v);
    s.row1 = Rows.x + RL_BODY_GLASS * mB;
    s.row2 = Rows.y + RL_BODY_GLASS * mB;
    s.rowG = RL_BODY_UNPAINTED + RL_BODY_ROW + RL_BODY_GLASS * mB;
    vec4 lfv = mix(rl_body_lf(nvF, s.row1 + RL_BODY_VIEW_ROW), rl_body_lf(nvF, s.row2 + RL_BODY_VIEW_ROW), s.mA);
    float blend = s.mR * cb.w * lfv.b * (1.0 - 2.0 * s.mA) + s.mA;
    vec3 c = mix(mix(TeamColor.rgb, CustomColor.rgb, blend), PaintColor.rgb, s.skin.g);
    vec3 base = mix(s.d.rgb, (1.0 - mB) * c, s.mR);
    float vz = s.v.z;
    float f5 = min(rl_pow(1.0 - max(vz, 0.0), 5.0), 1.0);
    float gA = min(min(rl_pow(abs(vz), 15.0), 1.0) * 2.0, 1.5);
    float hB = min(rl_pow(abs(vz), 4.0), 1.0) * 0.25;
    float trimF = mix(min(f5 + 0.1, 1.0) + gA, hB + f5 + 0.5, TrimColor.a);
    float curv = rl_tex(BodyCurvature, uv, Srgb0.z).g;
    base = mix(base, trimF * TrimColor.rgb, curv);
    vec3 baseK = base * mix(rl_sat((lfv.r - 0.5) * 8.0 + 0.75), 1.0, curv);
    s.diffCol = baseK + lfv.g * (ca.y * (baseK - 0.5) + 0.5);
    s.specTint = (1.0 - mB) * ca.x * (baseK - 0.5) + 0.5;
    s.specInt = mix(mix(0.125, cb.x, s.mR), 8.0, mB);
    float envScale = mix(mix(0.015625, cb.y, s.mR), 0.125, mB);
    s.rw = twr * r;
    vec4 env = rl_tex(EnvPack, (s.rw.xy + 1.0) * 0.5 + s.ns.xy, Srgb1.x);
    float envX = mix(env.r, env.g, lfv.a);
    float envPainted = mix(rl_body_lf(envX, s.row1).b, rl_body_lf(envX, s.row2).b, s.mA);
    float envLF = mix(rl_body_lf(envX, s.rowG).b, envPainted, s.mR) * envScale;
    float rim = (1.25 - nvF * nvF) * rl_sat((nvF - 0.09375) * 12.0) + 3.0 * envLF;
    float rk = (rl_sat(2.0 * (lfv.r - 0.5)) + lfv.b) * 6.0 + 0.3;
    s.refl = rl_cube(s.rw, ground) * (mB * rim * rk) + envLF + rl_sat(1.0 - s.ns.z) * env.a * s.mR * cb.z;
    float ao = 1.0 - (1.0 - mix(dn1.a, dn2.a, s.mA)) * ca.z;
    float occlCurve = mix(rl_body_lf(ao * s.d.a, s.row1).a, rl_body_lf(ao * s.d.a, s.row2).a, s.mA);
    s.occl = rl_sat(depthUu * 0.000244 + occlCurve);
    s.tnS = rl_body_tertiary_texel(uv);
    return s;
}

vec3 rl_body_tertiary(RlBodySurface s, vec3 l) {
    vec4 ca = TertControlA;
    vec4 tb = TertControlB;
    float row = Rows.z / 32.0 + RL_BODY_ROW;
    vec3 tn = rl_nmap_rgb(s.tnS);
    vec3 tN = vec3(0.0, 0.0, 1.0) + (s.tnS.rgb * 2.0 - vec3(1.0, 1.0, 2.0)) * (ca.y * 0.5);
    vec4 tLF = rl_body_lf(dot(tN, s.v), row + RL_BODY_VIEW_ROW);
    vec3 twC = TertColor.rgb * rl_sat((tLF.r - 0.5) * 8.0 + 0.75);
    vec3 tdiff = twC + s.d.a * tLF.g * (ca.y * (twC - 0.5) + 0.5);
    float tdl = rl_body_lf((dot(tn, l) + 1.0) * 0.5, row).r;
    vec3 tTint = ca.x * (twC - 0.5) + 0.5;
    vec3 tTn = vec3(ca.w * tn.xy, tn.z);
    float th = dot(tTn, normalize(l + s.v));
    float tspec = rl_body_lf(th * th, row).g;
    vec4 tenv = rl_tex(EnvPack, (s.rw.xy + 1.0) * 0.5 + tTn.xy, Srgb1.x);
    float tenvLF = rl_body_lf(mix(tenv.r, tenv.g, tLF.a), row).b;
    float tsum = tb.x * tspec + tb.y * tenvLF + tenv.a * rl_sat(1.0 - tn.z) * tb.z;
    vec3 tcol = tdl * tdiff + tsum * tTint;
    float tocc = rl_sat(rl_body_lf((1.0 - (1.0 - s.tnS.a) * ca.z) * s.d.a, row).a);
    return rl_sat(tcol * tocc);
}

vec3 rl_body_light(vec2 uv, mat3 twr, vec3 Vts, vec3 Lts, float depthUu, vec3 ground) {
    RlBodySurface s = rl_body_surface(uv, twr, Vts, depthUu, ground);
    vec3 l = normalize(Lts);
    vec3 h = normalize(l + s.v);
    float x = (dot(s.nd, l) + 1.0) * 0.5;
    float dl = mix(rl_body_lf(x, s.row1).r, rl_body_lf(x, s.row2).r, s.mA);
    float nh = dot(s.ns, h);
    float nh2 = nh * nh;
    float lobePainted = mix(rl_body_lf(nh2, s.row1).g, rl_body_lf(nh2, s.row2).g, s.mA);
    float lobe = mix(rl_body_lf(nh2, s.rowG).g, lobePainted, s.mR);
    vec3 col = (s.refl + lobe * s.specInt) * s.specTint + dl * s.diffCol;
    col = rl_sat(col * (s.occl * 2.0 * abs(s.skin.a - 0.5)));
    return mix(col, rl_body_tertiary(s, l), s.mT);
}

vec3 rl_body_sky(vec2 uv, mat3 twr, vec3 Vts, float depthUu, vec3 ground) {
    RlBodySurface s = rl_body_surface(uv, twr, Vts, depthUu, ground);
    vec3 col = (s.diffCol + s.refl * s.specTint) * s.occl;
    return mix(col, rl_body_tertiary(s, vec3(0.0, 0.0, 1.0)), s.mT);
}

#endif
