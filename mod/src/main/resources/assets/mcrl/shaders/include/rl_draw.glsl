#ifndef MCRL_RL_DRAW_GLSL
#define MCRL_RL_DRAW_GLSL

layout(std140) uniform RlDraw {
    mat4 ModelMat;
    mat4 ProbeMat;
    vec4 Probe[8];
    vec4 Dyn; // brake, boost glow, 0 rim or 1 tyre
    vec4 Ground;
};

#endif
