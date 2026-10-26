#ifndef MCRL_RL_SCENE_GLSL
#define MCRL_RL_SCENE_GLSL

#include <mcrl:rl_common.glsl>

layout(std140) uniform RlScene {
    vec4 SunDir;
    vec4 SunColor;
    vec4 SkyUpper;
    vec4 SkyLower;
    vec4 Ambient;
    vec4 CubeSky;
    vec4 Time;
    vec4 Display;
};

vec3 rl_sky(vec3 diff, float nDotS, vec3 ground) {
    vec3 lower = SkyLower.w >= 0.5 ? ground : SkyLower.rgb;
    float up = nDotS * 0.5 + 0.5;
    float down = 0.5 - nDotS * 0.5;
    return diff * Ambient.rgb + Ambient.w * diff * (SkyUpper.rgb * (up * up) + lower * (down * down));
}

vec3 rl_cube(vec3 dirRl, vec3 ground) {
    float z = normalize(dirRl).z;
    float up = z * 0.5 + 0.5;
    float down = 0.5 - z * 0.5;
    return CubeSky.rgb * (up * up) + ground * (down * down);
}

vec3 rl_display(vec3 hdr) {
    return rl_srgb_encode(clamp(hdr * Display.x, 0.0, 1.0));
}

vec3 rl_compose(vec3 lit, vec3 emissive, vec3 lightmapDisplay) {
    return rl_display(lit * rl_srgb_decode(lightmapDisplay) + emissive);
}

#endif
