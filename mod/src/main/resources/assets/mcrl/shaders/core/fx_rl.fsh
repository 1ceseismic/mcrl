#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:oit.glsl>
#include <mcrl:rl_common.glsl>

layout(std140) uniform FxDraw {
    vec4 P0;
    vec4 P1;
    vec4 P2;
    vec4 P3;
    vec4 SkinColor;
    vec4 Srgb;
    vec4 Time;
    vec4 Sky;
};

#ifdef FX_T0
uniform sampler2D T0;
vec4 t0(vec2 uv) { return rl_tex(T0, uv, Srgb.x); }
#endif
#ifdef FX_T1
uniform sampler2D T1;
vec4 t1(vec2 uv) { return rl_tex(T1, uv, Srgb.y); }
#endif
#ifdef FX_T2
uniform sampler2D T2;
vec4 t2(vec2 uv) { return rl_tex(T2, uv, Srgb.z); }
#endif
#ifdef FX_T3
uniform sampler2D T3;
#endif
#ifdef FX_SKIN
uniform sampler2D Skin;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 texCoord;
layout(location = 3) in vec4 particleColor;
layout(location = 4) in vec4 particleDyn;
layout(location = 5) in vec4 particleExtra;
layout(location = 6) in vec3 worldNormal;
layout(location = 7) in vec3 viewPos;
layout(location = 8) in float depthUu;
layout(location = 9) in vec3 lightMapColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

float fx_frac(float x) {
    return x - floor(x);
}

float fx_pw(float x, float e) {
    return abs(x) < 1e-6 ? 0.0 : pow(abs(x), e);
}

void main() {
    vec4 c = particleColor;
    vec4 d = particleDyn;
    vec4 uv4 = texCoord;
    vec2 uv = texCoord.xy;
    float t = Time.x;
    float vz = dot(normalize(worldNormal), normalize(-viewPos));
    float depth = depthUu;
    float height = viewPos.y * 50.0 - Time.y;
    vec3 rgb = vec3(0.0);
    float alpha = 1.0;
#if defined(FX_R_UNTEXTURED)
    rgb = c.rgb;
    alpha = c.a;
    #ifdef FX_ADDITIVE
    rgb *= c.a;
    #endif
#elif defined(FX_R_SMOKE_PUFF)
    float s1 = t0(uv * 3.0 + vec2(fx_frac(t * 0.038), fx_frac(t * 0.05))).r;
    float s2 = t0(uv * 1.53 + vec2(fx_frac(t * -0.00175), fx_frac(t * -0.05))).r;
    float g = t1(uv * 4.0).r;
    float rad = t2(uv * 4.0).r;
    float a = rl_sat(rad * s1 * s2 * 48.0 - 0.25);
    float y = (s1 + s2) * g;
    rgb = (3.0 - a) * mix(vec3(y / 2.0), y * c.rgb, d.x);
    alpha = a * c.a;
#elif defined(FX_R_FLAME03)
    float th = t * 0.5 * -0.8;
    vec2 p = uv * 0.25 - 0.1;
    vec4 n = t0(vec2(cos(th) * p.x - sin(th) * p.y, sin(th) * p.x + cos(th) * p.y) + 0.1);
    vec2 nn = (n.rg * 2.0 - 1.0) * 0.05;
    vec3 m = t1(uv * 4.0 + nn).rgb;
    vec3 w = t2(uv * 2.5 + nn + vec2(fx_frac(t * 0.1), fx_frac(t * 0.5))).rgb;
    vec3 k = rl_sat(w * 4.0 - 0.05);
    vec3 r1 = rl_sat((k * m + 0.35 * m - 0.1) * 3.0) * c.rgb;
    rgb = 5.0 * w * r1 * d.y;
    alpha = rl_sat(rl_sat((k.r * m.r + 0.35 * m.r - 0.1) * 3.0) * c.a);
#elif defined(FX_R_BOOST_CONE)
    float tt = t * (1.0 + 0.5 * P0.x);
    vec2 q = abs(uv * 2.0 + 1.0);
    float n = t0((1.0 - q) * 0.25 + tt * vec2(-0.2616, 0.48504)).g;
    vec4 w = t1(n * P0.y + q * 0.5 + tt * vec2(0.4, 2.0));
    float s = w.r + w.g;
    float v3 = fx_pw(uv.y, 3.0);
    float pulse = rl_sat(sin(t * 4.053668));
    float a = rl_sat((s * v3 * (1.0 - uv.y) + 0.05) * pulse * 10.0);
    vec3 e = 4.0 * (s * v3 - 0.005) * P1.rgb;
    rgb = a * e * e;
#elif defined(FX_R_BOOST_SHEET)
    vz = abs(vz);
    float inner = rl_sat((uv4.w - 0.5) * 100.0);
    float g3 = rl_sat(uv.y - 0.5) * 3.0;
    float tt = mix(P0.x, P0.y, inner) * t;
    vec2 dd = t0(vec2(uv.x - 0.05 * tt, 0.25 * uv.y + tt)).rg + t0(vec2(uv.x + 0.02 * tt, 0.5 * uv.y + tt)).rg;
    vec2 tile = vec2(uv.x * P1.x, uv.y * P1.y * 0.2);
    float s1 = t1(tile + vec2(0.0, tt) + 0.1 * dd).r;
    float s2 = t1(tile + vec2(0.0, 2.0 * tt) + 0.1 * dd).r;
    float sp = rl_sat(s1 * s2 - mix(P0.z, P0.w, inner));
    float fe = P1.w + (abs(uv.y) < 1e-6 ? 0.0 : min(4.0 * fx_pw(uv.y, 4.0), 1.0) * (P1.z - P1.w));
    float gv = uv.y + 0.5 * dd.y;
    float fr = abs(vz) >= 1e-6 ? min(fx_pw(vz, fe), 1.0) : 0.0;
    float g = rl_sat(fx_pw(gv, P2.x) * P2.y);
    float fg = abs(gv) < 1e-6 || abs(vz) < 1e-6 ? 0.0 : fr * g;
    float q = rl_sat(1.0 - gv);
    float tip = q < 1e-6 ? 0.0 : min(64.0 * q * q * q * q, 1.0);
    float core = fg * rl_sat(vz) * tip;
    float a = rl_sat((64.0 * sp * core + fg) * P2.z);
    vec3 C = P3.rgb * P2.w;
    vec3 col = mix(mix(min(sqrt(C), 1.0), C, g3), rl_sat(C + 0.1), inner) + 64.0 * sp * core * C;
    rgb = a * col;
#elif defined(FX_R_DUSTY_FLARE)
    float fl = t0(vec2(fx_frac(t * 0.4765), fx_frac(t * 0.4536))).r + 0.25;
    rgb = fl * (t1(uv).b * P0.x * P0.x * c.rgb * c.a);
#elif defined(FX_R_STANDARD_FLARE)
    rgb = c.a * (P0.x * P0.x * t0(uv).rgb * c.rgb);
#elif defined(FX_R_STREAKS)
    vec4 r = t0(uv);
    rgb = vec3((r.r + r.b) * 0.5);
#elif defined(FX_R_WHEEL_TRAIL)
    float e = 1.0 - abs(2.0 * uv4.w - 1.0);
    vec2 B = vec2(uv4.w, 0.1875 * e + uv4.z);
    float a0 = e * c.a;
    float n2 = t0(B * vec2(0.5, 1.0) + vec2(fx_frac(t * -0.0109), fx_frac(t * -0.030315))).g;
    float n1 = t0(B * vec2(1.0, 2.0) + vec2(fx_frac(t * 0.033333), fx_frac(t * -0.1075)) + 0.125 * n2).g;
    float f = rl_sat(1.25 * a0 + n2 + n1 - 1.0);
    float core = rl_sat((a0 * a0 - 0.875) * 4.0);
    float a = min(core + f, 1.0) * (1.0 - uv4.x) * rl_sat(uv4.z * 18.0);
    rgb = a * (core + c.rgb);
#elif defined(FX_R_GLOW) || defined(FX_R_FLIPRESET_CIRCLE)
    rgb = t0(uv).r * c.a * c.rgb;
#elif defined(FX_R_DISTORTION_SPHERE)
    rgb = t0(uv).g * c.a * c.rgb;
#elif defined(FX_R_FLIPRESET_GRADIENT)
    float g = max(1.0 - length(uv * 2.0 - 1.0), 0.0);
    g = g * g * g * g;
    rgb = g * (c.a * c.rgb);
#elif defined(FX_R_FLIPRESET_MESH)
    rgb = c.rgb;
    alpha = abs(1.0 - uv.y) >= 1e-6 ? fx_pw(1.0 - uv.y, 5.0) * c.a : 0.0;
#elif defined(FX_R_SPARKS_ASH)
    rgb = c.a * (c.rgb * t0(uv).rgb);
#elif defined(FX_R_SPARK)
    vec2 q = 1.0 - abs(uv * 2.0 - 1.0);
    rgb = c.rgb;
    alpha = rl_sat(9.0 * q.x * q.y) * c.a;
#elif defined(FX_R_GLOW_TRANSLUCENT)
    rgb = c.rgb;
    alpha = t0(uv).r * c.a;
#elif defined(FX_R_SMOKE_PUFF01)
    float n = t0(uv + vec2(fx_frac(t * -0.093802), fx_frac(t * 0.1125))).r;
    alpha = t0(uv + 0.1 * n).b * c.a;
    float x = (n - 0.125) * 0.5 + 2.0 * uv.y;
    float y = 1.0 - (x - trunc(x));
    rgb = y * y * c.rgb;
#elif defined(FX_R_GLOW01_MIC)
    float e = t0(uv).r;
    rgb = e * c.rgb;
    alpha = e * c.a;
#elif defined(FX_R_DODGE_RIBBON)
    float across = 1.0 - 2.0 * uv.y;
    float x = 1.0 - min(2.0 * abs(across), 1.0);
    x = x * x + 0.25;
    float e = rl_sat(4.0 * (1.0 - abs(1.0 - 2.0 * uv.x)));
    float m = max(1.0 - ((1.0 - e) * (1.0 - e) + across * across), 0.0);
    m = m * m * x;
    rgb = m * c.a * (m * (c.rgb - 1.0) + 1.0);
#elif defined(FX_R_DIRT_PUFF)
    float tt = t * P0.y;
    float n = t0(uv * P0.x * 2.0 + vec2(fx_frac(tt * -0.2475), fx_frac(tt * -0.42))).r;
    vec4 s = t1(uv + 0.0625 * n);
    float h = rl_sat(height * 0.125);
    rgb = s.g * c.rgb;
    alpha = rl_sat(h * s.b * c.a - n);
#elif defined(FX_R_CLOUDS02)
    vec4 s = t0(uv);
    float g = s.b * 0.14 + 0.06;
    rgb = (s.r * (10.0 - g) + g) * c.rgb;
    alpha = s.a * c.a;
#elif defined(FX_R_SPRAY_DIRT)
    float n = t0(uv * 3.0 + vec2(fx_frac(t * 0.1), fx_frac(t * 2.0))).r;
    float y = 1.0 - min(abs(height) * 0.0625, 1.0);
    float v = rl_sat(t1(uv).b - (y * y + n - c.a + 1.0));
    rgb = v * c.rgb;
    alpha = v;
#elif defined(FX_R_GRASS_BLADES)
    vec4 g = t0(uv);
    rgb = rl_sat(2.0 * g.rgb * c.rgb);
    alpha = rl_sat(g.a * c.a);
#elif defined(FX_R_SAND_LIT)
    vec4 s = t0(uv);
    float n = rl_sat(t1(uv * 5.0).b * t1(uv * 10.0).b);
    rgb = (n * 1024.0 + s.g + 0.5) * c.rgb;
    alpha = s.a * c.a;
#elif defined(FX_R_SNOW_SMOKE)
    vec4 cl = t0(uv * 0.5 + fx_frac(t * 0.05));
    vec3 f = rl_sat(t1(uv + 0.05 * cl.rg).rgb);
    float y = rl_sat((f.r + d.z) * d.w);
    float z = rl_sat((f.r - 0.01) * 4.0);
    rgb = 2.0 * min(f + 0.35, 1.0) * c.rgb * d.y;
    alpha = rl_sat(z * y * c.a);
#elif defined(FX_R_WATER_SPLASH)
    vec2 q = uv + 0.15 * t0(uv + vec2(fx_frac(t * 0.25), fx_frac(t * 0.1))).rg;
    rgb = Sky.rgb * c.rgb + c.rgb;
    alpha = rl_sat(t1(q).r - (1.0 - c.a));
#elif defined(FX_R_SPLASH)
    rgb = Sky.rgb * c.rgb;
    alpha = rl_sat(t0(uv).r - (1.0 - c.a));
#elif defined(FX_R_WATER_SPLASH01)
    rgb = c.rgb;
    alpha = t0(uv).g * c.a;
#elif defined(FX_R_TEAM_GLOW)
    rgb = vec3(t0(uv).r * c.a);
#elif defined(FX_R_SPEED_TRAIL)
    float across = abs(2.0 * uv.y - 1.0);
    float x = 1.0 - across;
    float y = max((0.25 - across) * 4.0, 0.0) + 0.125;
    rgb = mix(c.rgb, vec3(1.0), d.x);
    alpha = x * y * rl_sat((1.0 - uv.x) * 4.0) * c.a * rl_sat(depth * 0.00125);
#elif defined(FX_R_LOCATION_BEAM)
    float k = min(max((sin(uv.y * 201.061935) - 0.75) * 8.0, 0.0), 0.75) + 0.25;
    vec2 s = gl_FragCoord.xy / ScreenSize - 0.5;
    rgb = c.rgb;
    alpha = rl_sat((dot(s, s) - 0.0625) * 4.0) * k * c.a;
#elif defined(FX_R_RETICLE)
    vec2 p = uv * 2.0 - 1.0;
    float ring = rl_sat((1.0 - abs((0.875 - dot(p, p)) * 12.0)) * 4.0);
    float far = max((rl_sat(depth / 4096.0) - 0.5) * 2.0, 0.0);
    rgb = c.rgb;
    alpha = far * ring;
#elif defined(FX_R_CLARITY_SPHERE)
    float a = rl_sat(vz) * rl_sat(depth * 0.000488);
    rgb = vec3(0.0);
    alpha = a * a;
#elif defined(FX_R_EXPLOSION)
    vec4 tt = mix(t0(uv4.xy), t0(uv4.zw), particleExtra.w);
    float lum = dot(tt.rgb, vec3(0.3, 0.59, 0.11));
    vec3 x = mix(tt.rgb, vec3(lum), d.z);
    vec3 m = max(abs(x), 1e-6);
    rgb = c.rgb * x + m * m * m * d.x * c.rgb;
    alpha = tt.a * c.a;
#elif defined(FX_R_FLASH_GRADIENT)
    float g = 1.0 - length(uv - 0.5);
    g = abs(g) < 1e-6 ? 0.0 : min(fx_pw(g, 12.0), 1.0);
    rgb = g * c.a * (g * c.rgb);
#endif
#ifdef FX_SKIN
    rgb = rl_srgb_decode(texture(Skin, texCoord.zw).rgb) * SkinColor.rgb;
#endif
#ifdef FX_LIT
    rgb *= rl_srgb_decode(lightMapColor);
#endif
    vec3 display = rl_srgb_encode(clamp(rgb * FX_EXPOSURE, 0.0, 1.0));
#if defined(FX_ADDITIVE)
    vec4 color = vec4(display, 1.0) * ColorModulator;
    vec4 fogColor = vec4(0.0, 0.0, 0.0, FogColor.a);
#elif defined(FX_MASKED)
    if (alpha < FX_CUTOUT) {
        discard;
    }
    vec4 color = vec4(display, 1.0) * ColorModulator;
    vec4 fogColor = FogColor;
#else
    if (alpha < 0.002) {
        discard;
    }
    vec4 color = vec4(display, clamp(alpha, 0.0, 1.0)) * ColorModulator;
    vec4 fogColor = FogColor;
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #ifndef FX_ADDITIVE
    fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #endif
    #endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
}
