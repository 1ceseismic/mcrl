#version 330
#extension GL_ARB_separate_shader_objects : require

#include <mcrl:rl_mesh_frag.glsl>
#include <mcrl:rl_wheel.glsl>

void main() {
    RlFrag r = rl_frag();
    vec3 lit = rl_wheel_base(texCoord0, r.vts, r.sts, Dyn.z, Ground.rgb)
        + rl_wheel_light(texCoord0, r.vts, r.lts, Dyn.z) * SunColor.rgb;
    rl_frag_out(lit, vec3(0.0));
}
