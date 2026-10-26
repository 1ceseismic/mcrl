#version 330
#extension GL_ARB_separate_shader_objects : require

#include <mcrl:rl_mesh_frag.glsl>
#include <mcrl:rl_ball.glsl>

void main() {
    RlFrag r = rl_frag();
    vec3 g = Ground.rgb;
    vec3 lit = rl_ball_base(texCoord0, r.twr, r.vts, r.sts, pwRl, depthUu, g)
        + rl_ball_light(texCoord0, r.twr, r.vts, r.lts, pwRl, depthUu, g) * SunColor.rgb;
    rl_frag_out(lit, rl_ball_emissive(texCoord0, r.twr, r.vts, pwRl, depthUu, Time.x, Time.y));
}
