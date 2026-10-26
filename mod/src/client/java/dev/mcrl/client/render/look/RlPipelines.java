package dev.mcrl.client.render.look;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;

public final class RlPipelines {
	public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Normal", GpuFormat.RGB32_FLOAT)
			.addAttribute("Tangent", GpuFormat.RGBA32_FLOAT)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.build();

	// Vanilla's MATRICES_FOG snippet is private.
	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.withVertexShader(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "core/rl_mesh"))
			.withVertexBinding(0, VERTEX_FORMAT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(true)
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.buildSnippet();

	public static final RenderPipeline BODY = pipeline("body", "RlBody", "BodyDiffuse", "BodySkin", "BodyCurvature",
			"BodyMasks", "BodyDetail1", "BodyDetail2", "BodyTertiaryNormal", "LightFalloff", "EnvPack");
	public static final RenderPipeline CHASSIS = pipeline("chassis", "RlChassis", "ChassisDiffuse", "ChassisMasks",
			"ChassisNormal", "BrushedMetal", "Swirls");
	public static final RenderPipeline WHEEL = pipeline("wheel", "RlWheel", "RimDiffuse", "RimNormal", "RimRGB",
			"TireDiffuse", "TireNormal", "RimDetailNormal", "TireDetailNormal", "Swirls");
	public static final RenderPipeline BALL = pipeline("ball", "RlBall", "BallDiffuse", "BallNormal", "BallMask",
			"BallDetailNormal");

	private RlPipelines() {
	}

	private static RenderPipeline pipeline(String name, String block, String... samplers) {
		BindGroupLayout.Builder layout = BindGroupLayout.builder()
				.withUniform("RlScene", UniformType.UNIFORM_BUFFER)
				.withUniform("RlDraw", UniformType.UNIFORM_BUFFER)
				.withUniform(block, UniformType.UNIFORM_BUFFER);
		for (String s : samplers) {
			layout.withUniform(s, UniformType.COMBINED_IMAGE_SAMPLER);
		}
		layout.withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER);
		return RenderPipeline.builder(SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "pipeline/rl_" + name))
				.withFragmentShader(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "core/rl_" + name))
				.withBindGroupLayout(layout.build())
				.build();
	}
}
