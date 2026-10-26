package dev.mcrl.client.render.stadium;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.match.MatchClientState;
import dev.mcrl.client.render.look.RlGpuMesh;
import dev.mcrl.client.render.look.RlLook;
import dev.mcrl.client.render.stadium.StadiumScene.Instance;
import dev.mcrl.client.render.stadium.StadiumScene.Pass;
import dev.mcrl.client.rl.MipChain;
import dev.mcrl.client.rl.MipTexture;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.rl.RlTextures;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.match.MatchBlock;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.rlassets.Mcrm;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.model.TextureInfo;

public final class StadiumRenderer implements FeatureRenderer<StadiumSubmit> {
	public static final FeatureRendererType<StadiumSubmit> TYPE = FeatureRendererType.create("mcrl:stadium");
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final long ALL_PADS = -1L;
	private static final int INITIAL_DRAWS = 64, RETIRE_FRAMES = 4;
	private static final float[] PLAIN = {0.22f, 0.23f, 0.25f, 1f}, GLASS = {0.55f, 0.65f, 0.75f, 0.3f},
			GLOW = {1.2f, 1.15f, 1.05f, 1f};
	private static @Nullable AbstractTexture white;
	private static @Nullable StadiumScene requested;
	private static final long UPLOAD_BYTES_PER_FRAME = 4L << 20;
	private static final long FREE_AFTER_NANOS = 10_000_000_000L;
	private static long lastInStadium;

	private static @Nullable StadiumScene built;
	private static Map<String, RlGpuMesh> gpu = Map.of();
	private static @Nullable StadiumScene loadingScene;
	private static @Nullable CompletableFuture<Map<String, RlGpuMesh.Data>> loading;
	private static @Nullable StadiumScene uploadingScene;
	private static final ArrayDeque<Map.Entry<String, RlGpuMesh.Data>> toUpload = new ArrayDeque<>();
	private static Map<String, RlGpuMesh> uploaded = new HashMap<>();
	private static final List<RlGpuMesh> retired = new ArrayList<>();
	private static int retiredFrames;
	private static final long KEY_CHECK_NANOS = 2_000_000_000L;
	private static volatile @Nullable String fallbackKey;
	private static long keyCheckedAt;
	private static volatile @Nullable StadiumScene fallback;
	private static @Nullable CompletableFuture<Void> fallbackCheck;
	private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());

	private @Nullable MappableRingBuffer ring, retiredRing;
	private int ringSize, wanted, cursor, retiredRingFrames;
	private boolean rotated;
	private int lightsOffset = -1;
	private final List<int @Nullable []> groups = new ArrayList<>();
	private @Nullable GpuBufferSlice transforms;

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(StadiumRenderer::collect);
	}

	private static void collect(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		if (mc.level == null || !mc.level.dimension().equals(StadiumConst.KEY)) {
			if ((built != null || uploadingScene != null) && now - lastInStadium > FREE_AFTER_NANOS) {
				free();
			}
			return;
		}
		lastInStadium = now;
		StadiumScene scene = scene();
		if (scene == null) {
			return;
		}
		Map<String, RlGpuMesh> meshes = meshes(scene);
		white();
		MatchBlock b = MatchClientState.block();
		StadiumDrawList.Passes p = StadiumDrawList.build(scene, McrlConfig.get().match.farDetail(),
				b == null ? ALL_PADS : bits(b.padActive()));
		CameraRenderState cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Vec3 at = cam != null && cam.pos != null ? cam.pos : Vec3.ZERO;
		float[] lights = lights(scene);
		if (scene != requested && !meshes.isEmpty()) {
			requested = scene;
			request(scene);
		}
		List<StadiumSubmit.Draw> solid = new ArrayList<>(), after = new ArrayList<>();
		draws(scene, meshes, p.opaque(), at, solid);
		draws(scene, meshes, p.masked(), at, solid);
		draws(scene, meshes, p.glass(), at, after);
		draws(scene, meshes, p.glow(), at, after);
		if (!solid.isEmpty()) {
			ctx.submitNodeCollector().submitCustom(SubmitRenderPhases.SOLID, new StadiumSubmit(lights, solid));
		}
		if (!after.isEmpty()) {
			ctx.submitNodeCollector().submitCustom(SubmitRenderPhases.AFTER_TERRAIN, new StadiumSubmit(lights, after));
		}
	}

	static @Nullable StadiumScene scene() {
		RlCache rl = RlCache.loaded();
		if (rl != null) {
			Optional<StadiumScene> s = rl.stadiumOr(Util.ioPool());
			if (s == null) {
				return null;
			}
			if (s.isPresent()) {
				return s.get();
			}
		}
		long now = System.nanoTime();
		if ((fallback == null || now - keyCheckedAt > KEY_CHECK_NANOS) && (fallbackCheck == null || fallbackCheck.isDone())) {
			keyCheckedAt = now;
			String dir = McrlConfig.get().arenaMeshesDir;
			fallbackCheck = CompletableFuture.runAsync(() -> checkFallback(dir), Util.ioPool());
		}
		return fallback;
	}

	private static void checkFallback(String dir) {
		String key = ArenaMeshes.clientKey(dir);
		if (fallback != null && key.equals(fallbackKey)) {
			return;
		}
		StadiumScene f;
		try {
			f = StadiumScene.fallback(ArenaMeshes.clientBytes(dir));
		} catch (IllegalArgumentException e) {
			once("the arena meshes at " + key + " do not read for the stadium's look (" + e.getMessage() + ")");
			f = StadiumScene.fallback(List.of());
		}
		fallbackKey = key;
		fallback = f;
	}

	private static Map<String, RlGpuMesh> meshes(StadiumScene scene) {
		if (scene == built) {
			if (!retired.isEmpty() && --retiredFrames <= 0) {
				retired.forEach(RlGpuMesh::close);
				retired.clear();
			}
			return gpu;
		}
		if (scene != uploadingScene) {
			dropUpload();
			if (scene != loadingScene || loading == null) {
				loadingScene = scene;
				loading = CompletableFuture.supplyAsync(() -> read(scene), Util.ioPool());
			}
			if (!loading.isDone()) {
				return Map.of();
			}
			Map<String, RlGpuMesh.Data> packed;
			try {
				packed = loading.join();
			} catch (RuntimeException e) {
				once("the stadium meshes do not read (" + e + ")");
				packed = Map.of();
			}
			loading = null;
			loadingScene = null;
			uploadingScene = scene;
			toUpload.addAll(packed.entrySet());
		}
		long bytes = 0;
		while (!toUpload.isEmpty() && bytes < UPLOAD_BYTES_PER_FRAME) {
			Map.Entry<String, RlGpuMesh.Data> e = toUpload.poll();
			RlGpuMesh.Data d = e.getValue();
			uploaded.put(e.getKey(), RlGpuMesh.upload(d));
			bytes += d.vertices().capacity() + d.indices().capacity();
		}
		if (!toUpload.isEmpty()) {
			return Map.of();
		}
		retired.forEach(RlGpuMesh::close);
		retired.clear();
		retired.addAll(gpu.values());
		retiredFrames = RETIRE_FRAMES;
		built = scene;
		gpu = uploaded;
		uploaded = new HashMap<>();
		uploadingScene = null;
		return gpu;
	}

	private static void dropUpload() {
		uploaded.values().forEach(RlGpuMesh::close);
		uploaded = new HashMap<>();
		toUpload.clear();
		uploadingScene = null;
	}

	private static void free() {
		dropUpload();
		retired.forEach(RlGpuMesh::close);
		retired.clear();
		gpu.values().forEach(RlGpuMesh::close);
		gpu = Map.of();
		built = null;
		loading = null;
		loadingScene = null;
		LOG.info("mcrl: the stadium's meshes left the GPU");
	}

	private static Map<String, RlGpuMesh.Data> read(StadiumScene scene) {
		Map<String, RlGpuMesh.Data> out = new HashMap<>();
		for (Map.Entry<String, StadiumScene.Mesh> e : scene.meshes().entrySet()) {
			MeshData d = e.getValue().data();
			Path f = e.getValue().file();
			try {
				if (d == null && f != null) {
					d = Mcrm.read(f);
				}
			} catch (IOException ex) {
				once("the stadium mesh " + e.getKey() + " does not read (" + ex.getMessage() + ")");
			}
			if (d == null || d.indices().length == 0) {
				continue;
			}
			out.put(e.getKey(), RlGpuMesh.pack("stadium " + e.getKey(), new RlCache.Mesh(d.vertices(), d.indices(),
					d.uv1())));
		}
		return out;
	}

	private static long bits(boolean[] active) {
		long out = 0;
		for (int i = 0; i < active.length && i < 64; i++) {
			out |= active[i] ? 1L << i : 0;
		}
		return out;
	}

	static float[] lights(StadiumScene s) {
		float[] out = new float[StadiumPipelines.LIGHTS_SIZE / 4];
		List<StadiumLight.Key> keys = s.light().key();
		for (int i = 0; i < keys.size(); i++) {
			StadiumLight.Key k = keys.get(i);
			float[] d = k.dir(), c = k.color();
			System.arraycopy(d, 0, out, 4 * i, 3);
			for (int j = 0; j < 3; j++) {
				out[12 + 4 * i + j] = c[j] * k.brightness();
			}
			out[12 + 4 * i + 3] = 1;
		}
		float[] sky = s.light().skyColor();
		for (int j = 0; j < 3; j++) {
			out[24 + j] = sky[j] * s.light().skyBrightness();
		}
		StadiumScene.Fog fog = s.fog();
		if (fog != null) {
			System.arraycopy(fog.color(), 0, out, 28, 3);
			out[31] = 1;
			out[32] = fog.start();
			out[33] = fog.end();
			out[34] = fog.maxOpacity();
		}
		return out;
	}

	private static void draws(StadiumScene s, Map<String, RlGpuMesh> meshes, int[] which, Vec3 cam,
			List<StadiumSubmit.Draw> out) {
		List<Instance> in = s.instances();
		for (int i : which) {
			Instance x = in.get(i);
			RlGpuMesh mesh = meshes.get(x.mesh());
			if (mesh == null) {
				continue;
			}
			float[] m = x.m();
			float[] model = {m[0], m[4], m[8], 0, m[1], m[5], m[9], 0, m[2], m[6], m[10], 0,
					(float) (m[3] - cam.x), (float) (m[7] - cam.y), (float) (m[11] - cam.z), 1};
			float[] c = color(s, x);
			StadiumScene.Surface surface = s.surface(x);
			MipTexture t = surface == null ? null : RlTextures.get(StadiumScene.TEXTURE_PREFIX + surface.texture());
			if (t == null) {
				out.add(new StadiumSubmit.Draw(x.pass(), mesh, model, c));
				continue;
			}
			float[] tint = x.team() == 0 || x.team() == 1 ? c : surface.tint();
			TextureInfo info = s.textures().get(surface.texture());
			boolean srgb = info != null && RlTextures.kindOf(info) == MipChain.Kind.COLOR_SRGB;
			float[] w = surface.mask();
			out.add(new StadiumSubmit.Draw(x.pass(), mesh, model, new float[] {tint[0], tint[1], tint[2], c[3]}, t,
					new float[] {1, srgb ? 1 : 0, x.pass() == Pass.MASKED ? 1 : 0, 0},
					w == null ? new float[4] : new float[] {w[0], w[1], w[2], 1}));
		}
	}

	private static void request(StadiumScene scene) {
		Set<String> names = new HashSet<>();
		scene.surfaces().values().forEach(sf -> names.add(sf.texture()));
		for (String n : names) {
			RlTextures.request(StadiumScene.TEXTURE_PREFIX + n).exceptionally(e -> {
				once("the stadium texture " + n + " does not load (" + e + "); its materials draw their flat colour");
				return null;
			});
		}
	}

	static float[] color(StadiumScene s, Instance x) {
		float[] own = s.color(x);
		float[] plain = x.pass() == Pass.GLASS ? GLASS : x.pass() == Pass.GLOW ? GLOW : PLAIN;
		if (x.team() == 0 || x.team() == 1) {
			float[] c = RlLook.teamColor(x.team() == 0 ? McrlConfig.Team.BLUE : McrlConfig.Team.ORANGE);
			return new float[] {c[0], c[1], c[2], own != null ? own[3] : plain[3]};
		}
		return own != null ? own : plain;
	}

	private static AbstractTexture white() {
		AbstractTexture w = white;
		if (w == null) {
			w = new WhiteTexture();
			white = w;
		}
		return w;
	}

	private static final class WhiteTexture extends AbstractTexture {
		WhiteTexture() {
			GpuDevice device = RenderSystem.getDevice();
			texture = device.createTexture("mcrl stadium white", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
					GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			textureView = device.createTextureView(texture);
			sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
			ByteBuffer px = ByteBuffer.allocateDirect(4).put(new byte[] {-1, -1, -1, -1}).flip();
			device.createCommandEncoder().writeToTexture(texture, px, 0, 0, 0, 0, 1, 1);
		}
	}

	private static void once(String line) {
		if (LOGGED.add(line)) {
			LOG.warn("mcrl: {}", line);
		}
	}

	@Override
	public void beginPrepare(FeatureFrameContext ctx) {
		groups.clear();
		cursor = 0;
		lightsOffset = -1;
		transforms = null;
		rotated = false;
	}

	@Override
	public void prepareGroup(FeatureFrameContext ctx, List<StadiumSubmit> submits, boolean strictlyOrdered) {
		int align = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		int stride = roundUp(StadiumPipelines.DRAW_SIZE, align), lightBytes = roundUp(StadiumPipelines.LIGHTS_SIZE, align);
		int draws = 0;
		for (StadiumSubmit s : submits) {
			draws += s.draws().size();
		}
		int start = cursor, end = start + (lightsOffset < 0 ? lightBytes : 0) + draws * stride;
		GpuBuffer buffer = buffer(end, Math.max(INITIAL_DRAWS, 2 * draws) * stride + lightBytes);
		if (buffer == null || draws == 0) {
			groups.add(null);
			return;
		}
		int[] offsets = new int[draws];
		try (GpuBufferSlice.MappedView view = buffer.map(start, end - start, false, true)) {
			int at = start;
			if (lightsOffset < 0) {
				Std140Builder b = Std140Builder.intoBuffer(view.data());
				float[] l = submits.get(0).lights();
				for (int v = 0; v < l.length; v += 4) {
					b.putVec4(l[v], l[v + 1], l[v + 2], l[v + 3]);
				}
				lightsOffset = at;
				at += lightBytes;
			}
			int k = 0;
			Matrix4f model = new Matrix4f();
			for (StadiumSubmit s : submits) {
				for (StadiumSubmit.Draw d : s.draws()) {
					Std140Builder b = Std140Builder.intoBuffer(view.data().position(at - start));
					b.putMat4f(model.set(d.model()));
					float[] c = d.color();
					b.putVec4(c[0], c[1], c[2], c[3]);
					float[] mode = d.mode(), mask = d.mask();
					b.putVec4(mode[0], mode[1], mode[2], mode[3]);
					b.putVec4(mask[0], mask[1], mask[2], mask[3]);
					offsets[k++] = at;
					at += stride;
				}
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
			List<StadiumSubmit> submits, boolean strictlyOrdered) {
		int[] offsets = groupIndex < groups.size() ? groups.get(groupIndex) : null;
		MappableRingBuffer r = ring;
		if (offsets == null || r == null || transforms == null || lightsOffset < 0) {
			return;
		}
		GpuBuffer buffer = r.currentBuffer();
		pass.pushDebugGroup(() -> "mcrl stadium");
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform(StadiumPipelines.LIGHTS, buffer.slice(lightsOffset, StadiumPipelines.LIGHTS_SIZE));
		CompiledRenderPipeline bound = null, boundFor = null;
		AbstractTexture boundTexture = null;
		int k = 0;
		for (StadiumSubmit s : submits) {
			for (StadiumSubmit.Draw d : s.draws()) {
				int offset = offsets[k++];
				boolean blended = d.pass() == Pass.GLASS || d.pass() == Pass.GLOW;
				if (stage != null && !blended) {
					continue;
				}
				RenderPipeline p = stage == null ? StadiumPipelines.of(d.pass()) : StadiumPipelines.oit(d.pass())
						.getPipeline(stage);
				CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(p);
				if (compiled == null) {
					once("the stadium pipeline " + p.getLocation() + " did not compile; its draws are skipped");
					continue;
				}
				if (compiled != bound) {
					pass.setPipeline(compiled);
					bound = compiled;
				}
				pass.setUniform(StadiumPipelines.DRAW, buffer.slice(offset, StadiumPipelines.DRAW_SIZE));
				AbstractTexture tex = d.texture() != null ? d.texture() : white;
				if (tex == null) {
					continue;
				}
				if (tex != boundTexture || compiled != boundFor) {
					pass.setUniform(StadiumPipelines.TEXTURE, tex.getTextureView(), tex.getSampler());
					boundTexture = tex;
					boundFor = compiled;
				}
				RlGpuMesh mesh = d.mesh();
				pass.setVertexBuffer(0, mesh.vertexBuffer().slice());
				pass.setIndexBuffer(mesh.indexBuffer(), IndexType.INT);
				pass.drawIndexed(mesh.indexCount(), 1, 0, 0, 0);
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
		if (ring != null) {
			ring.close();
			ring = null;
		}
		if (retiredRing != null) {
			retiredRing.close();
			retiredRing = null;
		}
	}

	private @Nullable GpuBuffer buffer(int end, int frame) {
		if (!rotated) {
			rotated = true;
			if (retiredRing != null && --retiredRingFrames <= 0) {
				retiredRing.close();
				retiredRing = null;
			}
			int need = Math.max(Math.max(end, frame), wanted);
			wanted = 0;
			if (ring == null || need > ringSize) {
				if (ring != null) {
					if (retiredRing != null) {
						retiredRing.close();
					}
					retiredRing = ring;
					retiredRingFrames = RETIRE_FRAMES;
				}
				ringSize = Integer.highestOneBit(need - 1) << 1;
				ring = new MappableRingBuffer(() -> "mcrl stadium draws", GpuBuffer.USAGE_UNIFORM
						| GpuBuffer.USAGE_MAP_WRITE, ringSize);
			} else {
				ring.rotate();
			}
		} else if (ring == null || end > ringSize) {
			wanted = Math.max(wanted, end);
			return null;
		}
		return ring.currentBuffer();
	}

	private static int roundUp(int n, int align) {
		return (n + align - 1) / align * align;
	}
}
