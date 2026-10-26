package dev.mcrl.client.fx.render;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;
import dev.mcrl.client.render.MeshEmitter;
import dev.mcrl.client.mixin.RenderTypeInvoker;

public final class FxRenderTypes {
	private static final Identifier RETICLE_SHADER = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "core/fx_reticle");
	private static final RenderPipeline.Snippet MATRICES_FOG = RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.buildSnippet();
	private static final DepthStencilState TEST_ONLY = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

	private record Key(Identifier texture, boolean decodeTexel, boolean reticle) {
	}

	private static final Map<Key, RenderType> TYPES = new HashMap<>();
	private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();
	private static final Map<String, OitPipelineSet> OIT = new HashMap<>();

	private FxRenderTypes() {
	}

	public static RenderType opaqueMesh() {
		return TYPES.computeIfAbsent(new Key(MeshEmitter.WHITE, false, false), k -> RenderTypes.entityCutout(k.texture()));
	}

	public static RenderType reticle(Identifier texture, boolean decodeTexel) {
		return TYPES.computeIfAbsent(new Key(texture, decodeTexel, true), FxRenderTypes::reticle);
	}

	private static RenderType reticle(Key k) {
		String name = "fx_reticle" + (k.decodeTexel() ? "_decoded" : "");
		RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(PIPELINES.computeIfAbsent(name, n -> pipeline(n, k)))
				.withTexture("Sampler0", k.texture())
				.setOitPipelines(OIT.computeIfAbsent(name, n -> oit(n, k)));
		return RenderTypeInvoker.mcrl$create("mcrl_" + name, setup.createRenderSetup());
	}

	private static RenderPipeline pipeline(String name, Key k) {
		RenderPipeline.Builder b = RenderPipeline.builder(MATRICES_FOG)
				.withLocation(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "pipeline/" + name));
		shaderAndFormat(b, k.decodeTexel());
		return b.withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE)).withDepthStencilState(TEST_ONLY)
				.build();
	}

	private static OitPipelineSet oit(String name, Key k) {
		RenderPipeline.Builder b = RenderPipeline.builder()
				.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
				.withBindGroupLayout(BindGroupLayouts.FOG)
				.withDepthStencilState(TEST_ONLY);
		shaderAndFormat(b, k.decodeTexel());
		b.withShaderDefine("OIT_ADDITIVE");
		return OitPipelineSet.builder(Mcrl.MOD_ID + "_" + name, b).build();
	}

	private static void shaderAndFormat(RenderPipeline.Builder b, boolean decodeTexel) {
		b.withVertexShader(RETICLE_SHADER)
				.withFragmentShader(RETICLE_SHADER)
				.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
				.withVertexBinding(0, DefaultVertexFormat.PARTICLE)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withCull(false)
				.withShaderDefine("FX_ADDITIVE");
		if (decodeTexel) {
			b.withShaderDefine("FX_DECODE_TEXEL");
		}
	}
}
