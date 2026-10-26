#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <mcrl:rl_common.glsl>
#include <mcrl:rl_draw.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec4 Tangent;
layout(location = 3) in vec2 UV0;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec3 worldPos;
layout(location = 3) out vec3 worldNormal;
layout(location = 4) out vec4 worldTangent;
layout(location = 5) out vec2 texCoord0;
layout(location = 6) out vec3 lightMapColor;
layout(location = 7) out vec3 pwRl;
layout(location = 8) out float depthUu;

void main() {
    vec4 world = ModelMat * vec4(Position, 1.0);
    gl_Position = ProjMat * ModelViewMat * world;
    sphericalVertexDistance = fog_spherical_distance(world.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(world.xyz);

    mat3 m = mat3(ModelMat);
    worldPos = world.xyz;
    worldNormal = m * Normal;
    worldTangent = vec4(m * Tangent.xyz, Tangent.w);
    texCoord0 = UV0;

    vec3 f = clamp((ProbeMat * vec4(Position, 1.0)).xyz, 0.0, 1.0);
    vec2 levels = vec2(0.0);
    for (int i = 0; i < 8; i++) {
        float w = ((i & 1) != 0 ? f.x : 1.0 - f.x) * ((i & 2) != 0 ? f.y : 1.0 - f.y)
            * ((i & 4) != 0 ? f.z : 1.0 - f.z);
        levels += w * Probe[i].xy;
    }
    lightMapColor = texture(Sampler2, clamp((levels + 0.5) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;

    depthUu = -(ModelViewMat * world).z * 50.0;
    pwRl = rl_axes(m * Position) * 50.0;
}
