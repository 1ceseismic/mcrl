#ifndef MCRL_RL_WHEEL_GLSL
#define MCRL_RL_WHEEL_GLSL

#include <mcrl:rl_scene.glsl>

layout(std140) uniform RlWheel {
    vec4 CustomColor;
    vec4 RimColor;
    vec4 Spec;
    vec4 Powers;
    vec4 Srgb0;
};

uniform sampler2D RimDiffuse;
uniform sampler2D RimNormal;
uniform sampler2D RimRGB;
uniform sampler2D TireDiffuse;
uniform sampler2D TireNormal;
uniform sampler2D RimDetailNormal;
uniform sampler2D TireDetailNormal;
uniform sampler2D Swirls;

struct RlWheelSurface {
    vec3 v;
    vec3 n;
    vec3 diff;
    vec3 spec;
    float power;
};

RlWheelSurface rl_wheel_surface(vec2 uv, vec3 Vts, vec3 l, float t) {
    RlWheelSurface w;
    vec3 rimDetail = rl_nmap_agb(texture(RimDetailNormal, vec2(10.0 * uv.y, uv.x)));
    vec3 rimN = rl_nmap_agb(texture(RimNormal, uv)) + rimDetail * vec3(0.1, 0.1, 0.0) * Powers.x;
    vec3 tyreDetail = rl_nmap_rgb(texture(TireDetailNormal, uv * 2.0));
    vec3 tyreN = rl_nmap_rgb(texture(TireNormal, uv)) * vec3(0.625, 0.625, 1.0)
        + tyreDetail * vec3(0.375, 0.375, 0.0) * Powers.y;
    vec3 nm = mix(rimN, tyreN, t);
    w.n = normalize(nm);
    w.v = normalize(Vts);
    vec3 r = reflect(-w.v, w.n);
    vec3 rd = rl_tex(RimDiffuse, uv, Srgb0.x).rgb;
    vec3 td = rl_tex(TireDiffuse, uv, Srgb0.z).rgb;
    float a = rl_sat(dot(tyreN, l));
    float b = dot(tyreN, w.v);
    w.spec = mix(rl_sat(rd * Spec.y), 4.0 * td * a * b, t);
    float f = 1.0 - max(dot(nm, w.v), 0.0);
    float z = min(min(rl_pow(f, 5.0), 1.0) + 0.3, 1.0);
    float x = min(max((max(1.0 - rl_pow(f, 0.15), 0.0) - 0.15) * 3.0, 0.0), 1.5);
    float k = mix(x + z, 1.0, CustomColor.a);
    float sw = texture(Swirls, 0.05 * r.xy).r + Spec.z;
    vec4 m = rl_tex(RimRGB, uv, Srgb0.y);
    vec3 painted = (rd * sw + k * CustomColor.rgb) * k * CustomColor.rgb * m.g * sw;
    vec3 rimDiff = mix(painted, sw * rd, m.r);
    float q = (rl_pow(abs(b), 6.0) + 0.015625) * 0.5;
    float wg = clamp(2.0 * b, 0.35, 1.0) * rl_sat((0.625 - b) * 1.666);
    vec3 tyreDiff = rl_sat(mix(16.0 * q * td, a * RimColor.rgb, wg * wg));
    w.diff = mix(rimDiff, tyreDiff, t);
    w.power = mix(Spec.x, 6.0, t);
    return w;
}

vec3 rl_wheel_light(vec2 uv, vec3 Vts, vec3 Lts, float t) {
    vec3 l = normalize(Lts);
    RlWheelSurface w = rl_wheel_surface(uv, Vts, l, t);
    float nh = rl_sat(dot(w.n, normalize(l + w.v)));
    return nh < 1e-6 ? vec3(0.0) : w.diff * nh + w.spec * rl_pow(nh, w.power);
}

vec3 rl_wheel_base(vec2 uv, vec3 Vts, vec3 Sts, float t, vec3 ground) {
    RlWheelSurface w = rl_wheel_surface(uv, Vts, vec3(0.0, 0.0, 1.0), t);
    return rl_sky(w.diff, dot(normalize(Sts), w.n), ground);
}

#endif
