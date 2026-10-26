#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(std140) uniform StadiumDraw {
    mat4 ModelMat;
    vec4 Color;
    vec4 TexMode;
    vec4 TexMask;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec4 Tangent;
layout(location = 3) in vec2 UV0;

layout(location = 0) out vec3 worldPos;
layout(location = 1) out vec3 worldNormal;
layout(location = 2) out vec2 texCoord;

void main() {
    vec4 world = ModelMat * vec4(Position, 1.0);
    gl_Position = ProjMat * ModelViewMat * world;
    worldPos = world.xyz;
    worldNormal = mat3(ModelMat) * Normal;
    texCoord = UV0;
}
