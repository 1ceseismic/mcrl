package dev.mcrl.client.fx.render;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.texture.AbstractTexture;

import dev.mcrl.client.fx.particle.FxEmitterDef.Blend;

public final class FxFeatureRenderer implements FeatureRenderer<FxSubmit> {
	public static final FeatureRendererType<FxSubmit> TYPE = FeatureRendererType.create("mcrl:fx");
	private static final int INITIAL_VERTICES = 8192, INITIAL_DRAWS = 64;
	private static final int RETIRE_FRAMES = 4;
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Set<RenderPipeline> FAILED = new HashSet<>();

	private final Ring vertices = new Ring("mcrl fx vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE);
	private final Ring blocks = new Ring("mcrl fx draws", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE);
	private final List<Boolean> groups = new ArrayList<>();
	private int vertexCursor, blockCursor, maxIndices;
	private @Nullable GpuBufferSlice transforms;

	private static final class Ring {
		final String label;
		final int usage;
		@Nullable MappableRingBuffer ring;
		int size;
		@Nullable MappableRingBuffer retired;
		int retiredFrames;
		int wanted;
		boolean rotated;

		Ring(String label, int usage) {
			this.label = label;
			this.usage = usage;
		}

		@Nullable GpuBuffer buffer(int end, int frame, int initial) {
			if (!rotated) {
				rotated = true;
				if (retired != null && --retiredFrames <= 0) {
					retired.close();
					retired = null;
				}
				int need = Math.max(Math.max(Math.max(end, frame), wanted), initial);
				wanted = 0;
				if (ring == null || need > size) {
					if (ring != null) {
						if (retired != null) {
							retired.close();
						}
						retired = ring;
						retiredFrames = RETIRE_FRAMES;
					}
					size = Integer.highestOneBit(need - 1) << 1;
					ring = new MappableRingBuffer(() -> label, usage, size);
				} else {
					ring.rotate();
				}
			} else if (ring == null || end > size) {
				wanted = Math.max(wanted, end);
				return null;
			}
			return ring.currentBuffer();
		}

		void close() {
			if (ring != null) {
				ring.close();
				ring = null;
			}
			if (retired != null) {
				retired.close();
				retired = null;
			}
		}
	}

	@Override
	public void beginPrepare(FeatureFrameContext ctx) {
		groups.clear();
		vertexCursor = 0;
		blockCursor = 0;
		maxIndices = 0;
		transforms = null;
		vertices.rotated = false;
		blocks.rotated = false;
	}

	@Override
	public void prepareGroup(FeatureFrameContext ctx, List<FxSubmit> submits, boolean strictlyOrdered) {
		int align = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		int stride = roundUp(FxPipelines.BLOCK_SIZE, align);
		int vertexBytes = 0, draws = 0;
		for (int i = 0; i < submits.size(); i++) {
			List<FxSubmit.Draw> ds = submits.get(i).draws();
			for (int j = 0; j < ds.size(); j++) {
				vertexBytes += ds.get(j).count() * FxVertices.FLOATS * 4;
				draws++;
			}
		}
		if (draws == 0) {
			groups.add(Boolean.FALSE);
			return;
		}
		int vStart = vertexCursor, vEnd = vStart + vertexBytes;
		int bStart = blockCursor, bEnd = bStart + draws * stride;
		FxSubmit first = submits.get(0);
		GpuBuffer vb = vertices.buffer(vEnd, first.vertices().count() * FxVertices.FLOATS * 4,
				INITIAL_VERTICES * FxVertices.FLOATS * 4);
		GpuBuffer bb = blocks.buffer(bEnd, first.frameDraws() * stride, INITIAL_DRAWS * stride);
		if (vb == null || bb == null) {
			groups.add(Boolean.FALSE);
			return;
		}
		try (GpuBufferSlice.MappedView vv = vb.map(vStart, Math.max(4, vertexBytes), false, true);
				GpuBufferSlice.MappedView bv = bb.map(bStart, bEnd - bStart, false, true)) {
			FloatBuffer vf = vv.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
			FloatBuffer bf = bv.data().order(ByteOrder.nativeOrder()).asFloatBuffer();
			int at = vStart, k = 0;
			for (int i = 0; i < submits.size(); i++) {
				FxSubmit s = submits.get(i);
				for (int j = 0; j < s.draws().size(); j++) {
					FxSubmit.Draw d = s.draws().get(j);
					s.vertices().copy(d.first(), d.count(), vf);
					bf.put(k * stride / 4, d.block(), 0, FxPipelines.BLOCK_SIZE / 4);
					d.vertexOffset = at;
					d.blockOffset = bStart + k * stride;
					at += d.count() * FxVertices.FLOATS * 4;
					maxIndices = Math.max(maxIndices, d.count() / 4 * 6);
					k++;
				}
			}
		}
		vertexCursor = vEnd;
		blockCursor = bEnd;
		groups.add(Boolean.TRUE);
	}

	@Override
	public void finishPrepare(FeatureFrameContext ctx) {
		if (!groups.isEmpty()) {
			transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
			if (maxIndices > 0) {
				RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(maxIndices);
			}
		}
	}

	@Override
	public void executeGroup(FeatureFrameContext ctx, @Nullable OitStage stage, RenderPass pass, int groupIndex,
			List<FxSubmit> submits, boolean strictlyOrdered) {
		boolean prepared = groupIndex < groups.size() && groups.get(groupIndex);
		MappableRingBuffer vr = vertices.ring, br = blocks.ring;
		if (!prepared || vr == null || br == null || transforms == null) {
			return;
		}
		RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		pass.pushDebugGroup(() -> "mcrl RL effects");
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		for (int i = 0; i < submits.size(); i++) {
			List<FxSubmit.Draw> ds = submits.get(i).draws();
			for (int j = 0; j < ds.size(); j++) {
				FxSubmit.Draw d = ds.get(j);
				FxPipelines.Key key = d.key();
				boolean blended = key.blend() == Blend.ADDITIVE || key.blend() == Blend.TRANSLUCENT;
				if (stage != null && !blended) {
					continue;
				}
				RenderPipeline p = stage == null ? FxPipelines.pipeline(key) : FxPipelines.oit(key).getPipeline(stage);
				CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(p);
				if (compiled == null) {
					if (FAILED.add(p)) {
						LOG.warn("mcrl: FX pipeline {} did not compile; its draws are skipped", p.getLocation());
					}
					continue;
				}
				pass.setPipeline(compiled);
				pass.setUniform(FxPipelines.BLOCK, br.currentBuffer().slice(d.blockOffset, FxPipelines.BLOCK_SIZE));
				List<String> samplers = key.samplers();
				for (int t = 0; t < samplers.size(); t++) {
					AbstractTexture tex = ctx.textureManager().getTexture(d.textures()[t]);
					pass.setUniform(samplers.get(t), tex.getTextureView(), tex.getSampler());
				}
				if (key.lit()) {
					pass.setUniform("Sampler2", ctx.lightmap(),
							RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				}
				pass.setVertexBuffer(0, vr.currentBuffer().slice(d.vertexOffset, d.count() * FxVertices.FLOATS * 4L));
				pass.setIndexBuffer(quads.getBuffer(), quads.type());
				pass.drawIndexed(d.count() / 4 * 6, 1, 0, 0, 0);
			}
		}
		pass.popDebugGroup();
	}

	@Override
	public void finishExecute(FeatureFrameContext ctx) {
		groups.clear();
	}

	@Override
	public void close() {
		vertices.close();
		blocks.close();
	}

	private static int roundUp(int n, int align) {
		return (n + align - 1) / align * align;
	}
}
