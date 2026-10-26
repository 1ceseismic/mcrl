#ifndef MCRL_RL_CHASSIS_GLSL
#define MCRL_RL_CHASSIS_GLSL

#include <mcrl:rl_scene.glsl>

layout(std140) uniform RlChassis {
    vec4 HeadlightColor;
    vec4 TailLightColor;
    vec4 BoostGlowColor;
    vec4 Srgb0;
};

uniform sampler2D ChassisDiffuse;
uniform sampler2D ChassisMasks;
uniform sampler2D ChassisNormal;
uniform sampler2D BrushedMetal;
uniform sampler2D Swirls;

struct RlChassisSurface {
    vec4 d;
    vec3 v;
    vec3 n;
    float s;
    vec3 diff;
    vec3 spec;
};

RlChassisSurface rl_chassis_surface(vec2 uv, mat3 twr, vec3 Vts) {
    RlChassisSurface c;
    c.d = rl_tex(ChassisDiffuse, uv, Srgb0.x);
    vec3 n0 = rl_nmap_agb(texture(ChassisNormal, uv));
    vec3 bm = rl_nmap_agb(texture(BrushedMetal, vec2(10.0 * uv.y, uv.x))) * vec3(0.025, 0.025, 0.0);
    c.n = normalize(n0 + c.d.a * bm);
    c.v = normalize(Vts);
    vec3 rw = twr * reflect(-c.v, c.n);
    c.s = texture(Swirls, 0.1 * rw.xy).r;
    float rim = rl_pow(1.0 - max(dot(n0, c.v), 0.0), 7.0);
    c.diff = c.d.rgb * (1.0 + c.s) + rim * c.d.r;
    c.spec = rl_sat(16.0 * c.d.rgb - c.s);
    return c;
}

vec3 rl_chassis_light(vec2 uv, mat3 twr, vec3 Vts, vec3 Lts) {
    RlChassisSurface c = rl_chassis_surface(uv, twr, Vts);
    float nh = rl_sat(dot(c.n, normalize(normalize(Lts) + c.v)));
    return nh < 1e-6 ? vec3(0.0) : c.diff * nh + c.spec * rl_pow(nh, 96.0);
}

vec3 rl_chassis_base(vec2 uv, mat3 twr, vec3 Vts, vec3 Sts, vec3 ground) {
    RlChassisSurface c = rl_chassis_surface(uv, twr, Vts);
    return rl_sky(c.diff, dot(normalize(Sts), c.n), ground) + c.d.rgb * c.s;
}

vec3 rl_chassis_emissive(vec2 uv, mat3 twr, vec3 Vts, float brake, float glow) {
    RlChassisSurface c = rl_chassis_surface(uv, twr, Vts);
    vec4 m = rl_tex(ChassisMasks, uv, Srgb0.y);
    vec3 tail = TailLightColor.rgb * mix(TailLightColor.w, 20.0, rl_sat(brake));
    vec3 head = HeadlightColor.rgb * HeadlightColor.w;
    vec3 boost = BoostGlowColor.rgb * (BoostGlowColor.w * 5.0 * clamp(glow, 0.0, 2.0));
    vec3 lamps = c.d.rgb * m.r * tail + c.d.rgb * m.g * head + m.b * boost;
    return m.a * lamps;
}

#endif
