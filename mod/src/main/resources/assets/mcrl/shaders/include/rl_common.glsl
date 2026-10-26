#ifndef MCRL_RL_COMMON_GLSL
#define MCRL_RL_COMMON_GLSL

float rl_sat(float x) {
    return clamp(x, 0.0, 1.0);
}

vec3 rl_sat(vec3 x) {
    return clamp(x, 0.0, 1.0);
}

float rl_pow(float x, float e) {
    return x < 1e-6 ? 0.0 : pow(x, e);
}

// 26.3 has no sRGB texture formats, so shaders decode.
vec3 rl_srgb_decode(vec3 c) {
    vec3 curve = pow((max(c, vec3(0.0)) + 0.055) / 1.055, vec3(2.4));
    return mix(c / 12.92, curve, greaterThan(c, vec3(0.04045)));
}

vec3 rl_srgb_encode(vec3 c) {
    vec3 curve = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(c * 12.92, curve, greaterThan(c, vec3(0.0031308)));
}

vec4 rl_tex(sampler2D s, vec2 uv, float srgb) {
    vec4 t = texture(s, uv);
    return srgb >= 0.5 ? vec4(rl_srgb_decode(t.rgb), t.a) : t;
}

vec3 rl_nmap_agb(vec4 t) {
    return vec3(t.a, t.g, t.b) * 2.0 - 1.0;
}

vec3 rl_nmap_rgb(vec4 t) {
    return t.rgb * 2.0 - 1.0;
}

vec3 rl_axes(vec3 mc) {
    return vec3(mc.x, mc.z, mc.y);
}

mat3 rl_frame(vec3 T, vec3 N, float w) {
    vec3 t = normalize(T);
    vec3 n = normalize(N);
    return mat3(t, cross(n, t) * w, n);
}

vec3 rl_to_tangent(mat3 f, vec3 x) {
    return vec3(dot(f[0], x), dot(f[1], x), dot(f[2], x));
}

mat3 rl_twr(mat3 f) {
    return mat3(rl_axes(f[0]), rl_axes(f[1]), rl_axes(f[2]));
}

#endif
