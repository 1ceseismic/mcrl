#version 330
#extension GL_ARB_separate_shader_objects : require

#include <mcrl:rl_mesh_frag.glsl>

layout(std140) uniform RlDissolve {
    vec4 DissolveColor;
    vec4 Srgb0;
    vec4 Srgb1;
};

uniform sampler2D DissolveNormal;
uniform sampler2D DissolveDetailNormal;
uniform sampler2D DissolveMask;
uniform sampler2D DissolveDiffuse;
uniform sampler2D DissolveNoise;
uniform sampler2D DissolveClouds;
uniform sampler2D DissolveEnergy;

void main() {
    RlFrag r = rl_frag();
    vec2 uv = texCoord0;
    float d = Dyn.x;
    vec3 n0 = rl_nmap_rgb(texture(DissolveNormal, uv))
        + rl_nmap_rgb(texture(DissolveDetailNormal, uv * 48.0)) * vec3(0.5, 0.5, 0.0);
    float ndv = dot(n0 * vec3(0.5, 0.5, 1.0), normalize(r.vts));
    vec3 diffuse = rl_tex(DissolveDiffuse, uv, Srgb0.x).rgb;
    vec3 base = ndv * diffuse * (rl_sat(depthUu * 0.000244) + 1.0);
    float f = 1.0 - rl_sat(2.0 * (ndv - 0.0625));
    vec3 col = base + f * f * (4.0 * base + 0.25 - base / 2.0);
    float c = rl_tex(DissolveClouds, uv, Srgb0.z).r;
    float en = rl_tex(DissolveEnergy, uv, Srgb0.w).r;
    float m = rl_tex(DissolveMask, uv, Srgb0.y).r;
    float k = 1.4 * d;
    float a = rl_pow((m + en + c) * c * 4.0 * k + c * (1.0 - k) + k, 100.0);
    float edge = rl_sat((a - 0.25) * 32.0);
    float alpha = min(a + edge, 1.0);
    float noise = rl_tex(DissolveNoise, Dyn.yz, Srgb1.x).r;
    vec3 emissive = (noise + 0.125) * 32.0 * m * base + (1.0 - edge) * DissolveColor.rgb * DissolveColor.a;
    vec3 lit = rl_sky(col, dot(normalize(r.sts), normalize(n0)), Ground.rgb);
    vec4 color = vec4(rl_compose(lit, emissive, lightMapColor), alpha);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
        FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
