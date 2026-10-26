#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <mcrl:rl_common.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    float g = texture(Sampler0, texCoord0).g;
    #ifdef FX_DECODE_TEXEL
    g = rl_srgb_decode(vec3(g)).x;
    #endif
    vec2 p = texCoord0 * 2.0 - 1.0;
    float d2 = dot(p, p);
    float s = vertexColor.r;
    float inner = rl_sat(1.0 - abs((1.0 - d2 - s - 0.0625) * 16.0) - 4.0 * g);
    float outer = max(1.0 - abs((0.9375 - d2) * 16.0), 0.0);
    float intensity = min(inner + outer, 0.5) * vertexColor.g;
    vec4 color = vec4(rl_srgb_encode(vec3(intensity)), 1.0) * ColorModulator;
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, vec4(0.0, 0.0, 0.0, FogColor.a));
    #endif
}
