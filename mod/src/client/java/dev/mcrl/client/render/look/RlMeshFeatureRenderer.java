package dev.mcrl.client.render.look;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.world.attribute.EnvironmentAttributes;

import dev.mcrl.client.link.ClientPoses;

public final class RlMeshFeatureRenderer implements FeatureRenderer<RlMeshSubmit> {
	public static final FeatureRendererType<RlMeshSubmit> TYPE = FeatureRendererType.create("mcrl:rl_mesh");
	private static final int INITIAL_DRAWS = 64;
	private static final int RETIRE_FRAMES = 4;
	private @Nullable MappableRingBuffer ring;
	private int ringSize;
	private @Nullable MappableRingBuffer retiredRing;
	private int retiredFrames;
	private int wanted;
	private @Nullable SceneValues sceneValues;
	private @Nullable GpuBufferSlice scene;
	private @Nullable GpuBufferSlice transforms;
	private int cursor;
	private final List<int @Nullable []> groups = new ArrayList<>();
	private final List<int[]> offsetArrays = new ArrayList<>();

	@Override
	public void beginPrepare(FeatureFrameContext ctx) {
		groups.clear();
		sceneValues = null;
		scene = null;
		transforms = null;
		cursor = 0;
	}

	@Override
	public void prepareGroup(FeatureFrameContext ctx, List<RlMeshSubmit> submits, boolean strictlyOrdered) {
		int align = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		int stride = roundUp(RlUbos.DRAW_SIZE, align);
		int sceneBytes = scene == null ? roundUp(RlUbos.SCENE_SIZE, align) : 0;
		int start = cursor;
		int end = start + sceneBytes + submits.size() * stride;
		GpuBuffer buffer = buffer(start, end, align);
		if (buffer == null) {
			groups.add(null);
			return;
		}
		int[] offsets = offsets(groups.size(), submits.size());
		try (GpuBufferSlice.MappedView view = buffer.map(start, end - start, false, true)) {
			if (scene == null) {
				SceneValues v = sceneValues();
				RlUbos.scene(Std140Builder.intoBuffer(view.data()), v);
				sceneValues = v;
				scene = buffer.slice(start, RlUbos.SCENE_SIZE);
			}
			Vector3f sky = sceneValues.cubeSky();
			float[] skyGround = {sky.x, sky.y, sky.z};
			int at = start + sceneBytes;
			for (int i = 0; i < submits.size(); i++) {
				RlMeshSubmit s = submits.get(i);
				float[] ground = Float.isNaN(s.ground()[0]) ? skyGround : s.ground();
				Std140Builder b = Std140Builder.intoBuffer(view.data().position(at - start));
				RlUbos.draw(b, s.model(), s.probeMat(), s.probes(), s.dyn(), ground);
				offsets[i] = at;
				at += stride;
			}
		}
		cursor = end;
		groups.add(offsets);
	}

	@Override
	public void finishPrepare(FeatureFrameContext ctx) {
		if (!groups.isEmpty()) {
			transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
		}
	}

	@Override
	public void executeGroup(FeatureFrameContext ctx, @Nullable OitStage stage, RenderPass pass, int groupIndex,
			List<RlMeshSubmit> submits, boolean strictlyOrdered) {
		MappableRingBuffer r = ring;
		if (stage != null || r == null || transforms == null || scene == null) {
			return;
		}
		int[] offsets = groups.get(groupIndex);
		if (offsets == null) {
			return;
		}
		GpuBuffer buffer = r.currentBuffer();
		pass.pushDebugGroup(() -> "mcrl RL meshes");
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("RlScene", scene);
		pass.setUniform("Sampler2", ctx.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		CompiledRenderPipeline bound = null;
		for (int i = 0; i < submits.size(); i++) {
			RlLookAssets.Part p = submits.get(i).part();
			CompiledRenderPipeline pipeline = RenderSystem.getCompiledPipelineNullable(p.pipeline());
			if (pipeline == null) {
				RlLookAssets.failLive("the pipeline " + p.pipeline().getLocation() + " no longer compiles");
				continue;
			}
			if (pipeline != bound) {
				pass.setPipeline(pipeline);
				bound = pipeline;
			}
			pass.setUniform("RlDraw", buffer.slice(offsets[i], RlUbos.DRAW_SIZE));
			pass.setUniform(p.block(), p.materialBlock());
			for (RlLookAssets.Bind bind : p.samplers()) {
				AbstractTexture t = ctx.textureManager().getTexture(bind.texture());
				pass.setUniform(bind.sampler(), t.getTextureView(), t.getSampler());
			}
			RlGpuMesh mesh = p.mesh();
			pass.setVertexBuffer(0, mesh.vertexBuffer().slice());
			pass.setIndexBuffer(mesh.indexBuffer(), IndexType.INT);
			pass.drawIndexed(mesh.indexCount(), 1, 0, 0, 0);
		}
		pass.popDebugGroup();
	}

	@Override
	public void finishExecute(FeatureFrameContext ctx) {
		groups.clear();
	}

	@Override
	public void close() {
		if (ring != null) {
			ring.close();
			ring = null;
		}
		if (retiredRing != null) {
			retiredRing.close();
			retiredRing = null;
		}
	}

	private @Nullable GpuBuffer buffer(int start, int end, int align) {
		if (start == 0) {
			if (retiredRing != null && --retiredFrames <= 0) {
				retiredRing.close();
				retiredRing = null;
			}
			int need = Math.max(end, wanted);
			wanted = 0;
			if (ring == null || need > ringSize) {
				int stride = roundUp(RlUbos.DRAW_SIZE, align);
				int draws = Math.max(INITIAL_DRAWS, Integer.highestOneBit(Math.max(1, need / stride)) * 2);
				int size = roundUp(RlUbos.SCENE_SIZE, align) + draws * stride;
				if (ring != null) {
					if (retiredRing != null) {
						retiredRing.close();
					}
					retiredRing = ring;
					retiredFrames = RETIRE_FRAMES;
				}
				ring = new MappableRingBuffer(() -> "mcrl RL draws", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
						size);
				ringSize = size;
			} else {
				ring.rotate();
			}
		} else if (ring == null || end > ringSize) {
			wanted = Math.max(wanted, end);
			return null;
		}
		return ring.currentBuffer();
	}

	private static SceneValues sceneValues() {
		Minecraft mc = Minecraft.getInstance();
		LevelRenderState level = mc.gameRenderer.gameRenderState().levelRenderState;
		Vector3fc fog = mc.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR,
				level.worldPartialTicks);
		return SceneLight.values(level.skyRenderState, fog, ClientPoses.simSeconds());
	}

	private int[] offsets(int group, int n) {
		while (offsetArrays.size() <= group) {
			offsetArrays.add(new int[0]);
		}
		int[] a = offsetArrays.get(group);
		if (a.length < n) {
			a = new int[Math.max(n, 2 * a.length)];
			offsetArrays.set(group, a);
		}
		return a;
	}

	private static int roundUp(int n, int align) {
		return (n + align - 1) / align * align;
	}
}
