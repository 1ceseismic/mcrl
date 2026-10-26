package dev.mcrl.client.fx.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;
import dev.mcrl.client.fx.particle.FxEmitterDef.Blend;
import dev.mcrl.client.render.look.ArenaLight;

public final class FxPipelines {
	public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Normal", GpuFormat.RGB32_FLOAT)
			.addAttribute("UV0", GpuFormat.RGBA32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA32_FLOAT)
			.addAttribute("Dyn", GpuFormat.RGBA32_FLOAT)
			.addAttribute("Extra", GpuFormat.RGBA32_FLOAT)
			.build();
	public static final String BLOCK = "FxDraw";
	public static final int BLOCK_SIZE = 128;
	public static final float CUTOUT = 0.3333f;
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "core/fx_rl");
	private static final DepthStencilState TEST_ONLY = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);
	private static final DepthStencilState NO_DEPTH = new DepthStencilState(CompareOp.ALWAYS_PASS, false);
	private static final RenderPipeline.Snippet MATRICES_FOG = RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.buildSnippet();

	public record Key(FxRecipe recipe, Blend blend, boolean skin) {
		private static final Key[] KEYS = new Key[FxRecipe.values().length * Blend.values().length * 2];
		private static final List<?>[] SAMPLERS = new List<?>[FxRecipe.values().length * 2];

		public static Key of(FxRecipe recipe, Blend blend, boolean skin) {
			int i = (recipe.ordinal() * Blend.values().length + blend.ordinal()) * 2 + (skin ? 1 : 0);
			Key k = KEYS[i];
			if (k == null) {
				k = new Key(recipe, blend, skin);
				KEYS[i] = k;
			}
			return k;
		}

		String name() {
			return "fx_rl_" + recipe.name().toLowerCase(Locale.ROOT) + "_" + blend.name().toLowerCase(Locale.ROOT)
					+ (skin ? "_skin" : "");
		}

		@SuppressWarnings("unchecked")
		public List<String> samplers() {
			int i = recipe.ordinal() * 2 + (skin ? 1 : 0);
			List<String> s = (List<String>) SAMPLERS[i];
			if (s == null) {
				List<String> out = new ArrayList<>();
				for (int t = 0; t < recipe.textures.length; t++) {
					out.add("T" + t);
				}
				if (skin) {
					out.add("Skin");
				}
				s = List.copyOf(out);
				SAMPLERS[i] = s;
			}
			return s;
		}

		public boolean lit() {
			return FxGeometry.lit(blend);
		}

		boolean noDepth() {
			return recipe == FxRecipe.RETICLE || recipe == FxRecipe.DUSTY_FLARE;
		}
	}

	private static final Map<Key, RenderPipeline> PIPELINES = new HashMap<>();
	private static final Map<Key, OitPipelineSet> OIT = new HashMap<>();

	private FxPipelines() {
	}

	public static RenderPipeline pipeline(Key k) {
		return PIPELINES.computeIfAbsent(k, key -> {
			RenderPipeline.Builder b = RenderPipeline.builder(MATRICES_FOG)
					.withLocation(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "pipeline/" + key.name()));
			common(b, key);
			switch (key.blend()) {
				case ADDITIVE -> b.withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
						.withDepthStencilState(depth(key));
				case TRANSLUCENT -> b.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
						.withDepthStencilState(depth(key));
				default -> b.withColorTargetState(ColorTargetState.DEFAULT)
						.withDepthStencilState(DepthStencilState.DEFAULT);
			}
			return b.build();
		});
	}

	public static OitPipelineSet oit(Key k) {
		return OIT.computeIfAbsent(k, key -> {
			RenderPipeline.Builder b = RenderPipeline.builder()
					.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
					.withBindGroupLayout(BindGroupLayouts.FOG)
					.withDepthStencilState(depth(key));
			common(b, key);
			if (key.blend() == Blend.ADDITIVE) {
				b.withShaderDefine("OIT_ADDITIVE");
			}
			OitPipelineSet.Builder set = OitPipelineSet.builder(Mcrl.MOD_ID + "_" + key.name(), b);
			if (key.noDepth()) {
				set.withoutDepthTest();
			}
			return set.build();
		});
	}

	public static int precompile() {
		int failed = 0;
		for (RenderPipeline p : PIPELINES.values()) {
			failed += RenderSystem.getCompiledPipelineNullable(p) == null ? 1 : 0;
		}
		for (OitPipelineSet set : OIT.values()) {
			for (OitStage stage : OitStage.values()) {
				failed += RenderSystem.getCompiledPipelineNullable(set.getPipeline(stage)) == null ? 1 : 0;
			}
		}
		return failed;
	}

	private static DepthStencilState depth(Key k) {
		return k.noDepth() ? NO_DEPTH : TEST_ONLY;
	}

	private static void common(RenderPipeline.Builder b, Key k) {
		BindGroupLayout.Builder layout = BindGroupLayout.builder().withUniform(BLOCK, UniformType.UNIFORM_BUFFER);
		for (String s : k.samplers()) {
			layout.withUniform(s, UniformType.COMBINED_IMAGE_SAMPLER);
		}
		if (k.lit()) {
			layout.withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER);
		}
		b.withVertexShader(SHADER)
				.withFragmentShader(SHADER)
				.withBindGroupLayout(layout.build())
				.withVertexBinding(0, VERTEX_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withCull(false)
				.withShaderDefine(k.recipe().define())
				.withShaderDefine("FX_" + k.blend().name())
				.withShaderDefine("FX_EXPOSURE", ArenaLight.EXPOSURE);
		for (int i = 0; i < k.recipe().textures.length; i++) {
			b.withShaderDefine("FX_T" + i);
		}
		if (k.skin()) {
			b.withShaderDefine("FX_SKIN");
		}
		if (k.lit()) {
			b.withShaderDefine("FX_LIT");
		}
		if (k.blend() == Blend.MASKED) {
			b.withShaderDefine("FX_CUTOUT", CUTOUT);
		}
	}
}
