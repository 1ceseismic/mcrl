#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:oit.glsl>

layout(std140) uniform StadiumLights {
    vec4 KeyDir[3];
    vec4 KeyColor[3];
    vec4 Sky;
    vec4 FogColor;
    vec4 FogRange;
};

layout(std140) uniform StadiumDraw {
    mat4 ModelMat;
    vec4 Color;
    vec4 TexMode;
    vec4 TexMask;
};

uniform sampler2D StadiumTex;

layout(location = 0) in vec3 worldPos;
layout(location = 1) in vec3 worldNormal;
layout(location = 2) in vec2 texCoord;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec3 srgb_encode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

vec3 srgb_decode(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

void main() {
    vec3 base = Color.rgb;
    float alpha = Color.a;
    if (TexMode.x > 0.5) {
        vec4 t = texture(StadiumTex, texCoord);
        if (TexMode.z > 0.5 && t.a < 0.5) {
            discard;
        }
        if (TexMask.w > 0.5) {
            base *= vec3(dot(t.rgb, TexMask.rgb));
        } else {
            base *= TexMode.y > 0.5 ? srgb_decode(t.rgb) : t.rgb;
        }
#ifdef STADIUM_GLASS
        alpha *= t.a;
#endif
    }
#ifdef STADIUM_GLOW
    vec3 lit = base;
#else
    vec3 n = normalize(worldNormal);
    if (dot(n, -worldPos) < 0.0) {
        n = -n;
    }
    vec3 lit = base * Sky.rgb;
    for (int i = 0; i < 3; i++) {
        lit += base * KeyColor[i].rgb * max(dot(n, -KeyDir[i].xyz), 0.0);
    }
#endif
    if (FogColor.a > 0.5) {
        float f = clamp((length(worldPos) - FogRange.x) / max(FogRange.y - FogRange.x, 1e-3), 0.0, FogRange.z);
#ifdef STADIUM_GLOW
        lit *= 1.0 - f;
#else
        lit = mix(lit, FogColor.rgb, f);
#endif
    }
    vec4 color = vec4(srgb_encode(lit), alpha);
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    fragColor = color;
#endif
}
