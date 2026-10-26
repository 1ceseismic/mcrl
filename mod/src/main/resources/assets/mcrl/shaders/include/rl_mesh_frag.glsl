#ifndef MCRL_RL_MESH_FRAG_GLSL
#define MCRL_RL_MESH_FRAG_GLSL

#include <minecraft:fog.glsl>
#include <mcrl:rl_common.glsl>
#include <mcrl:rl_scene.glsl>
#include <mcrl:rl_draw.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec3 worldPos;
layout(location = 3) in vec3 worldNormal;
layout(location = 4) in vec4 worldTangent;
layout(location = 5) in vec2 texCoord0;
layout(location = 6) in vec3 lightMapColor;
layout(location = 7) in vec3 pwRl;
layout(location = 8) in float depthUu;

layout(location = 0) out vec4 fragColor;

struct RlFrag {
    mat3 twr;
    vec3 vts;
    vec3 lts;
    vec3 sts;
};

RlFrag rl_frag() {
    vec3 n = normalize(worldNormal);
    vec3 t = worldTangent.xyz - n * dot(n, worldTangent.xyz);
    if (dot(t, t) < 1e-12) {
        t = abs(n.x) < 0.9 ? cross(n, vec3(1.0, 0.0, 0.0)) : cross(n, vec3(0.0, 1.0, 0.0));
    }
    mat3 f = rl_frame(t, n, worldTangent.w < 0.0 ? -1.0 : 1.0);
    RlFrag r;
    r.twr = rl_twr(f);
    r.vts = rl_to_tangent(f, normalize(-worldPos));
    r.lts = rl_to_tangent(f, SunDir.xyz);
    r.sts = rl_to_tangent(f, vec3(0.0, 1.0, 0.0));
    return r;
}

void rl_frag_out(vec3 lit, vec3 emissive) {
    vec4 color = vec4(rl_compose(lit, emissive, lightMapColor), 1.0);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

#endif
