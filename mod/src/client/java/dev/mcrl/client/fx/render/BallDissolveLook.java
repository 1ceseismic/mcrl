package dev.mcrl.client.fx.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;
import dev.mcrl.client.render.look.RlLookAssets;
import dev.mcrl.client.render.look.RlMaterial;
import dev.mcrl.client.render.look.RlMaterials;
import dev.mcrl.client.render.look.RlPipelines;
import dev.mcrl.client.rl.MipChain;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.rl.RlTextures;
import dev.mcrl.rlassets.model.TextureInfo;

public final class BallDissolveLook {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	public static final String MATERIAL = "BallDissolve_Soccar";
	static final String BLOCK = "RlDissolve";
	static final int BLOCK_SIZE = 48;
	private static final float[] DISSOLVE_COLOR = {0.15105805f, 0.6253447f, 0.40982574f, 20};
	private static final DepthStencilState TEST_ONLY = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

	static final Map<String, String> SLOTS = slots();

	public static final RenderPipeline PIPELINE = pipeline();

	private static @Nullable BallDissolveLook live;
	private static final List<GpuBuffer> RETIRED = new ArrayList<>();

	private final RlCache cache;
	private final Map<String, CompletableFuture<Identifier>> textures = new LinkedHashMap<>();
	private final Map<String, TextureInfo> infos = new LinkedHashMap<>();
	private float[] color = DISSOLVE_COLOR;
	private boolean failed;
	private @Nullable Boolean compiled;
	private @Nullable GpuBuffer block;
	private RlLookAssets.@Nullable Part part;
	private @Nullable Object partFor;

	private BallDissolveLook(RlCache cache) {
		this.cache = cache;
		RlMaterial m;
		try {
			m = RlMaterials.resolve(cache.materials(), MATERIAL);
		} catch (IllegalArgumentException e) {
			fail("the RL cache has no " + MATERIAL);
			return;
		}
		color = m.vector("DissolveColor", DISSOLVE_COLOR);
		for (Map.Entry<String, String> s : SLOTS.entrySet()) {
			String v = s.getValue();
			String name = v.startsWith("param:") ? m.texture(v.substring(6)) : v;
			TextureInfo info = name == null ? null : cache.textureInfo(name);
			if (info == null) {
				fail("the RL cache has no texture " + (name != null ? name : v) + " for " + s.getKey());
				return;
			}
			infos.put(s.getKey(), info);
			textures.put(s.getKey(), RlTextures.request(info.name()));
		}
	}

	private static Map<String, String> slots() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("DissolveNormal", "param:Normal");
		m.put("DissolveDetailNormal", "CarbonFiber_N");
		m.put("DissolveMask", "param:Masks");
		m.put("DissolveDiffuse", "param:Diffuse");
		m.put("DissolveNoise", "Noise_Shapes_01_Pack");
		m.put("DissolveClouds", "Clouds_RGB_T");
		m.put("DissolveEnergy", "Energy_T");
		return m;
	}

	public static RlLookAssets.@Nullable Part part(RlCache rl, RlLookAssets look) {
		BallDissolveLook l = live;
		if (l == null || l.cache != rl) {
			if (l != null) {
				l.retire();
			}
			l = new BallDissolveLook(rl);
			live = l;
		}
		return l.part(look);
	}

	private RlLookAssets.@Nullable Part part(RlLookAssets look) {
		if (failed) {
			return null;
		}
		for (CompletableFuture<Identifier> f : textures.values()) {
			if (!f.isDone()) {
				return null;
			}
			if (f.isCompletedExceptionally()) {
				fail("a dissolve texture did not load");
				return null;
			}
		}
		if (compiled == null) {
			compiled = RlLookAssets.compile(PIPELINE);
			if (!compiled) {
				fail("the pipeline " + PIPELINE.getLocation() + " did not compile");
				return null;
			}
		}
		if (block == null) {
			ByteBuffer buf = ByteBuffer.allocateDirect(BLOCK_SIZE).order(ByteOrder.nativeOrder());
			Std140Builder b = Std140Builder.intoBuffer(buf);
			b.putVec4(color[0], color[1], color[2], color.length > 3 ? color[3] : 1);
			b.putVec4(srgb("DissolveDiffuse"), srgb("DissolveMask"), srgb("DissolveClouds"), srgb("DissolveEnergy"));
			b.putVec4(srgb("DissolveNoise"), 0, 0, 0);
			block = RenderSystem.getDevice().createBuffer(() -> "mcrl RL dissolve block", GpuBuffer.USAGE_UNIFORM, b.get());
		}
		RlLookAssets.Part ball = look.ball();
		if (part == null || partFor != ball) {
			List<RlLookAssets.Bind> binds = new ArrayList<>();
			textures.forEach((sampler, f) -> binds.add(new RlLookAssets.Bind(sampler, f.join())));
			part = new RlLookAssets.Part(ball.mesh(), PIPELINE, BLOCK, block, List.copyOf(binds));
			partFor = ball;
		}
		return part;
	}

	private float srgb(String sampler) {
		TextureInfo info = infos.get(sampler);
		return info != null && RlTextures.kindOf(info) == MipChain.Kind.COLOR_SRGB ? 1 : 0;
	}

	private void fail(String reason) {
		if (!failed) {
			failed = true;
			LOG.warn("mcrl: no ball dissolve for this RL cache: {}", reason);
		}
	}

	private void retire() {
		for (GpuBuffer b : RETIRED) {
			b.close();
		}
		RETIRED.clear();
		if (block != null) {
			RETIRED.add(block);
		}
	}

	private static RenderPipeline pipeline() {
		BindGroupLayout.Builder layout = BindGroupLayout.builder()
				.withUniform("RlScene", UniformType.UNIFORM_BUFFER)
				.withUniform("RlDraw", UniformType.UNIFORM_BUFFER)
				.withUniform(BLOCK, UniformType.UNIFORM_BUFFER);
		for (String s : SLOTS.keySet()) {
			layout.withUniform(s, UniformType.COMBINED_IMAGE_SAMPLER);
		}
		layout.withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER);
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "pipeline/rl_ball_dissolve"))
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayouts.PROJECTION)
				.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
				.withBindGroupLayout(BindGroupLayouts.FOG)
				.withVertexShader(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "core/rl_mesh"))
				.withFragmentShader(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "core/rl_ball_dissolve"))
				.withVertexBinding(0, RlPipelines.VERTEX_FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(true)
				.withDepthStencilState(TEST_ONLY)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withBindGroupLayout(layout.build())
				.build();
	}
}
