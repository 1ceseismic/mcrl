#version 330
#extension GL_ARB_separate_shader_objects : require

#include <mcrl:rl_mesh_frag.glsl>
#include <mcrl:rl_chassis.glsl>

void main() {
    RlFrag r = rl_frag();
    vec3 lit = rl_chassis_base(texCoord0, r.twr, r.vts, r.sts, Ground.rgb)
        + rl_chassis_light(texCoord0, r.twr, r.vts, r.lts) * SunColor.rgb;
    rl_frag_out(lit, rl_chassis_emissive(texCoord0, r.twr, r.vts, Dyn.x, Dyn.y));
}
