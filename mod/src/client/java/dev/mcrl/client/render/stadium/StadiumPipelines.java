package dev.mcrl.client.render.stadium;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;
import dev.mcrl.client.render.look.RlPipelines;
import dev.mcrl.client.render.stadium.StadiumScene.Pass;

public final class StadiumPipelines {
	public static final String LIGHTS = "StadiumLights", DRAW = "StadiumDraw", TEXTURE = "StadiumTex";
	public static final int LIGHTS_SIZE = 9 * 16;
	public static final int DRAW_SIZE = 64 + 3 * 16;
	private static final DepthStencilState TEST_ONLY = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "core/rl_stadium");
	private static final BindGroupLayout BLOCKS = BindGroupLayout.builder()
			.withUniform(LIGHTS, UniformType.UNIFORM_BUFFER)
			.withUniform(DRAW, UniformType.UNIFORM_BUFFER)
			.withUniform(TEXTURE, UniformType.COMBINED_IMAGE_SAMPLER)
			.build();
	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BLOCKS)
			.withVertexShader(SHADER)
			.withFragmentShader(SHADER)
			.withVertexBinding(0, RlPipelines.VERTEX_FORMAT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.buildSnippet();

	public static final RenderPipeline OPAQUE = pipeline(Pass.OPAQUE, ColorTargetState.DEFAULT, DepthStencilState.DEFAULT);
	public static final RenderPipeline MASKED = pipeline(Pass.MASKED, ColorTargetState.DEFAULT, DepthStencilState.DEFAULT);
	public static final RenderPipeline GLASS = pipeline(Pass.GLASS, new ColorTargetState(BlendFunction.TRANSLUCENT),
			TEST_ONLY);
	private static final BlendFunction SCREEN = new BlendFunction(BlendFactor.ONE_MINUS_DST_COLOR, BlendFactor.ONE);
	public static final RenderPipeline GLOW = pipeline(Pass.GLOW, new ColorTargetState(SCREEN), TEST_ONLY);

	private static final Map<Pass, OitPipelineSet> OIT = new EnumMap<>(Pass.class);

	private StadiumPipelines() {
	}

	public static OitPipelineSet oit(Pass p) {
		if (p == Pass.OPAQUE || p == Pass.MASKED) {
			throw new IllegalArgumentException(p + " is not blended");
		}
		return OIT.computeIfAbsent(p, k -> {
			RenderPipeline.Builder b = RenderPipeline.builder()
					.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
					.withBindGroupLayout(BLOCKS)
					.withVertexShader(SHADER)
					.withFragmentShader(SHADER)
					.withVertexBinding(0, RlPipelines.VERTEX_FORMAT)
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.withCull(false)
					.withShaderDefine("STADIUM_" + k.name())
					.withDepthStencilState(TEST_ONLY);
			if (k == Pass.GLOW) {
				b.withShaderDefine("OIT_ADDITIVE");
			}
			return OitPipelineSet.builder(Mcrl.MOD_ID + "_stadium_" + k.name().toLowerCase(Locale.ROOT), b).build();
		});
	}

	public static RenderPipeline of(Pass p) {
		return switch (p) {
			case OPAQUE -> OPAQUE;
			case MASKED -> MASKED;
			case GLASS -> GLASS;
			case GLOW -> GLOW;
		};
	}

	private static RenderPipeline pipeline(Pass p, ColorTargetState color, DepthStencilState depth) {
		String name = p.name().toLowerCase(Locale.ROOT);
		return RenderPipeline.builder(SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "pipeline/rl_stadium_" + name))
				.withShaderDefine("STADIUM_" + p.name())
				.withColorTargetState(color)
				.withDepthStencilState(depth)
				.build();
	}
}
