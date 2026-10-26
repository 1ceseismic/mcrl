#ifndef MCRL_RL_BALL_GLSL
#define MCRL_RL_BALL_GLSL

#include <mcrl:rl_scene.glsl>

layout(std140) uniform RlBall {
    vec4 GoalPlaneLocation;
    vec4 GoalPlaneNormal;
    vec4 Tint;
    vec4 Srgb0;
};

uniform sampler2D BallDiffuse;
uniform sampler2D BallNormal;
uniform sampler2D BallMask;
uniform sampler2D BallDetailNormal;

struct RlBallSurface {
    vec3 n0;
    vec3 n;
    vec3 v;
    float g;
    vec3 d;
    vec3 glow;
    vec4 mask;
};

RlBallSurface rl_ball_surface(vec2 uv, vec3 Vts, vec3 pwRl) {
    RlBallSurface s;
    vec3 detail = rl_nmap_rgb(texture(BallDetailNormal, uv * 8.0));
    s.n0 = rl_nmap_rgb(texture(BallNormal, uv)) + detail * vec3(0.5, 0.5, 0.0);
    s.n = normalize(s.n0);
    s.v = normalize(Vts);
    s.g = rl_sat(dot(GoalPlaneLocation.xyz - pwRl, GoalPlaneNormal.xyz));
    s.d = rl_tex(BallDiffuse, uv, Srgb0.x).rgb;
    s.mask = rl_tex(BallMask, uv, Srgb0.y);
    s.glow = 64.0 * Tint.rgb * s.mask.r;
    return s;
}

vec3 rl_ball_color(RlBallSurface s, mat3 twr, vec3 pwRl, float depthUu, vec3 ground) {
    vec3 rw = twr * reflect(-s.v, s.n);
    vec3 rd = vec3(abs(rw.x) < 1e-8 ? 1e-8 : rw.x, abs(rw.y) < 1e-8 ? 1e-8 : rw.y, abs(rw.z) < 1e-8 ? 1e-8 : rw.z);
    vec3 box = vec3(5000.0, 7500.0, 5000.0);
    vec3 tFar = max((box - pwRl) / rd, (-box - pwRl) / rd);
    float tHit = min(min(tFar.x, tFar.y), tFar.z);
    vec3 env = rl_cube(pwRl + rw * tHit, ground) * (Tint.rgb + 1.0);
    vec3 dp = s.d * (1.0 - s.g) * (rl_sat(depthUu * 0.000244) + 0.5);
    float e = 2.0 + 0.75 * rl_sat((s.mask.g - 0.125) * 8.0);
    vec3 reflW = mix(e * dp, vec3(1.0), rl_sat(s.glow));
    float ndv = dot(s.n0, s.v);
    vec3 base = ndv * dp;
    float f = 1.0 - rl_sat(2.0 * (ndv - 0.0625));
    float fres = f * f * 0.25;
    return env * reflW + base + fres * (16.0 * (base + 0.0625) - base);
}

vec3 rl_ball_light(vec2 uv, mat3 twr, vec3 Vts, vec3 Lts, vec3 pwRl, float depthUu, vec3 ground) {
    RlBallSurface s = rl_ball_surface(uv, Vts, pwRl);
    float nh = rl_sat(dot(s.n, normalize(normalize(Lts) + s.v)));
    return nh < 1e-6 ? vec3(0.0) : rl_ball_color(s, twr, pwRl, depthUu, ground) * nh;
}

vec3 rl_ball_base(vec2 uv, mat3 twr, vec3 Vts, vec3 Sts, vec3 pwRl, float depthUu, vec3 ground) {
    RlBallSurface s = rl_ball_surface(uv, Vts, pwRl);
    return rl_sky(rl_ball_color(s, twr, pwRl, depthUu, ground), dot(normalize(Sts), s.n), ground);
}

vec3 rl_ball_emissive(vec2 uv, mat3 twr, vec3 Vts, vec3 pwRl, float depthUu, float timeFrac, float timeFrac8) {
    RlBallSurface s = rl_ball_surface(uv, Vts, pwRl);
    float ndv = dot(s.n0, s.v);
    float a = rl_sat(ndv - 0.5);
    float k = mix(a * a, (1.0 - ndv) * (1.0 - ndv), s.g) * 8.0 * timeFrac8 * GoalPlaneLocation.w
        + 96.0 * (1.0 - abs(2.0 * s.g - 1.0));
    float pulse = rl_sat((timeFrac - 0.875) * 8.0);
    return s.d * k + s.glow * (0.0625 + 4.0 * pulse);
}

#endif
