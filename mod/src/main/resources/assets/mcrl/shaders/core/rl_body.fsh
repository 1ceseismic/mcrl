#version 330
#extension GL_ARB_separate_shader_objects : require

#include <mcrl:rl_mesh_frag.glsl>
#include <mcrl:rl_body.glsl>

void main() {
    RlFrag r = rl_frag();
    vec3 g = Ground.rgb;
    vec3 sky = rl_body_sky(texCoord0, r.twr, r.vts, depthUu, g);
    vec3 lit = rl_sky(sky, dot(normalize(r.sts), rl_body_sky_normal(texCoord0)), g)
        + rl_body_light(texCoord0, r.twr, r.vts, r.lts, depthUu, g) * SunColor.rgb;
    rl_frag_out(lit, vec3(0.0));
}
