#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec4 UV0;
layout(location = 3) in vec4 Color;
layout(location = 4) in vec4 Dyn;
layout(location = 5) in vec4 Extra;

#ifdef FX_LIT
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 texCoord;
layout(location = 3) out vec4 particleColor;
layout(location = 4) out vec4 particleDyn;
layout(location = 5) out vec4 particleExtra;
layout(location = 6) out vec3 worldNormal;
layout(location = 7) out vec3 viewPos;
layout(location = 8) out float depthUu;
layout(location = 9) out vec3 lightMapColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord = UV0;
    particleColor = Color;
    particleDyn = Dyn;
    particleExtra = Extra;
    worldNormal = Normal;
    viewPos = Position;
    depthUu = -(ModelViewMat * vec4(Position, 1.0)).z * 50.0;
    #ifdef FX_LIT
    lightMapColor = texture(Sampler2, clamp((Extra.xy + 0.5) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
    #else
    lightMapColor = vec3(1.0);
    #endif
}
