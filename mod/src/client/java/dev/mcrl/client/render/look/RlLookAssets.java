package dev.mcrl.client.render.look;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.phys.AABB;

import dev.mcrl.Mcrl;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.Team;
import dev.mcrl.client.config.McrlConfig.TextureQuality;
import dev.mcrl.client.rl.BodyLook;
import dev.mcrl.client.rl.MipChain;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.rl.RlTextures;
import dev.mcrl.rlassets.bodies.BodyIds;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;

public final class RlLookAssets {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final int WHEELS = 4;
	private static final int MAX_LIVE_BODIES = 8;
	private static final long IDLE_NANOS = 10_000_000_000L;

	public enum State {
		LOADING, READY, FAILED
	}

	public record Bind(String sampler, Identifier texture) {
	}

	public record Part(RlGpuMesh mesh, RenderPipeline pipeline, String block, GpuBuffer materialBlock,
			List<Bind> samplers) {
	}

	public enum Neutral {
		MASK(0, 0, 0, 0),
		NORMAL_RGB(0.5f, 0.5f, 1, 1),
		NORMAL_SWIZZLED(0, 0.5f, 1, 0.5f),
		COLOR(1, 1, 1, 1);

		final float[] rgba;

		Neutral(float r, float g, float b, float a) {
			rgba = new float[] {r, g, b, a};
		}

		public Identifier id() {
			return Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "look/neutral_" + name().toLowerCase(Locale.ROOT));
		}
	}

	record Slot(String sampler, @Nullable String param, @Nullable String fixed, boolean required, Neutral neutral) {
		static Slot param(String sampler, String param, boolean required, Neutral neutral) {
			return new Slot(sampler, param, null, required, neutral);
		}

		static Slot fixed(String sampler, String texture, boolean required, Neutral neutral) {
			return new Slot(sampler, null, texture, required, neutral);
		}
	}

	enum Material {
		BODY(RlLook.BODY, RlPipelines.BODY, "RlBody", List.of(
				Slot.param("BodyDiffuse", "Diffuse", true, Neutral.COLOR),
				Slot.param("BodySkin", "Skin", true, Neutral.COLOR),
				Slot.param("BodyCurvature", "CurvaturePack", false, Neutral.MASK),
				Slot.param("BodyMasks", "BodyMasks", false, Neutral.MASK),
				Slot.param("BodyDetail1", "F1DetailNormal", false, Neutral.NORMAL_RGB),
				Slot.param("BodyDetail2", "F2DetailNormal", false, Neutral.NORMAL_RGB),
				Slot.param("BodyTertiaryNormal", "TertiaryMaterial_Normal", false, Neutral.NORMAL_RGB),
				Slot.fixed("LightFalloff", RlLook.LIGHT_FALLOFF, true, Neutral.MASK),
				Slot.fixed("EnvPack", RlLook.ENV_PACK, true, Neutral.COLOR))),
		CHASSIS(RlLook.CHASSIS, RlPipelines.CHASSIS, "RlChassis", List.of(
				Slot.param("ChassisDiffuse", "Diffuse", true, Neutral.COLOR),
				Slot.param("ChassisMasks", "Masks", true, Neutral.MASK),
				Slot.param("ChassisNormal", "Normal", true, Neutral.NORMAL_SWIZZLED),
				Slot.fixed("BrushedMetal", RlLook.BRUSHED_METAL, false, Neutral.NORMAL_SWIZZLED),
				Slot.fixed("Swirls", RlLook.SWIRLS, true, Neutral.MASK))),
		WHEEL(RlLook.WHEEL, RlPipelines.WHEEL, "RlWheel", List.of(
				Slot.param("RimDiffuse", "RimDiffuse", true, Neutral.COLOR),
				Slot.param("RimNormal", "RimNormal", false, Neutral.NORMAL_SWIZZLED),
				Slot.param("RimRGB", "RimRGB", true, Neutral.MASK),
				Slot.param("TireDiffuse", "TireDiffuse", true, Neutral.COLOR),
				Slot.param("TireNormal", "TireNormal", false, Neutral.NORMAL_RGB),
				Slot.param("RimDetailNormal", "Rim_AdditionalNormal", false, Neutral.NORMAL_SWIZZLED),
				Slot.param("TireDetailNormal", "Tire_AdditionalNormal", false, Neutral.NORMAL_RGB),
				Slot.fixed("Swirls", RlLook.SWIRLS, true, Neutral.MASK))),
		BALL(RlLook.BALL, RlPipelines.BALL, "RlBall", List.of(
				Slot.param("BallDiffuse", "Diffuse", true, Neutral.COLOR),
				Slot.param("BallNormal", "Normal", true, Neutral.NORMAL_RGB),
				Slot.param("BallMask", "Mask", true, Neutral.MASK),
				Slot.fixed("BallDetailNormal", RlLook.MATTE_N, false, Neutral.NORMAL_RGB)));

		final String material;
		final RenderPipeline pipeline;
		final String block;
		final List<Slot> slots;

		Material(String material, RenderPipeline pipeline, String block, List<Slot> slots) {
			this.material = material;
			this.pipeline = pipeline;
			this.block = block;
			this.slots = slots;
		}
	}

	private static final Set<Neutral> NEUTRALS = EnumSet.noneOf(Neutral.class);

	private record Key(RlCache cache, String body) {
		boolean octane() {
			return body.equals(BodyIds.OCTANE);
		}
	}

	private static final LinkedHashMap<Key, RlLookAssets> live = new LinkedHashMap<>();
	private static final Map<Key, RlLookAssets> standIns = new HashMap<>();
	private static final List<RlLookAssets> retired = new ArrayList<>();
	private static long frame;

	private final Key key;
	private final BodyLook look;
	private long requestedIn, requestedAt;
	private final boolean withBall;
	private final List<Material> used;
	private final TextureQuality quality;
	private State state = State.LOADING;
	private @Nullable String failure;
	private final AABB probeBox;
	private final Map<Material, RlMaterial> materials = new LinkedHashMap<>();
	private final Map<Material, Map<Slot, @Nullable TextureInfo>> planned = new LinkedHashMap<>();
	private final Map<String, CompletableFuture<Identifier>> textures = new LinkedHashMap<>();
	private final @Nullable CompletableFuture<List<RlGpuMesh.Data>> meshData;
	private @Nullable List<Boolean> compiles;
	private final List<RlGpuMesh> meshes = new ArrayList<>();
	private final List<GpuBuffer> blocks = new ArrayList<>();
	private final Part[] bodies = new Part[Team.values().length];
	private final Part[] rims = new Part[WHEELS];
	private final Part[] tyres = new Part[WHEELS];
	private @Nullable Part chassis;
	private @Nullable Part ball;
	private boolean closed;

	public static RlLookAssets forBody(RlCache rl, BodyLook look) {
		TextureQuality quality = McrlConfig.get().textureQuality;
		Key key = new Key(rl, BodyIds.isDefault(look.id()) ? BodyIds.OCTANE : look.id());
		retireOtherCaches(rl);
		RlLookAssets a = live.remove(key);
		if (a != null && a.quality == quality && a.look == look) {
			live.put(key, a);
			a.requestedIn = frame;
			a.requestedAt = System.nanoTime();
			return a;
		}
		RlLookAssets next = new RlLookAssets(key, rl, look, quality);
		next.requestedIn = frame;
		next.requestedAt = System.nanoTime();
		if (a != null && a.look == look && a.state == State.READY && next.state == State.LOADING) {
			retire(standIns.put(key, a));
		} else {
			retire(a);
			if (a != null && a.look != look) {
				retire(standIns.remove(key));
			}
		}
		live.put(key, next);
		return next;
	}

	private static void retireIdle(long now) {
		for (var it = live.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Key, RlLookAssets> e = it.next();
			RlLookAssets a = e.getValue();
			if (!e.getKey().octane() && a.requestedIn != frame && now - a.requestedAt >= IDLE_NANOS) {
				it.remove();
				retire(a);
				retire(standIns.remove(e.getKey()));
			}
		}
	}

	public static void retireBodies() {
		for (var it = live.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Key, RlLookAssets> e = it.next();
			if (!e.getKey().octane()) {
				it.remove();
				retire(e.getValue());
				retire(standIns.remove(e.getKey()));
			}
		}
	}

	private static void retireBeyondCap() {
		int bodies = 0;
		for (Key k : live.keySet()) {
			bodies += k.octane() ? 0 : 1;
		}
		for (var it = live.entrySet().iterator(); it.hasNext() && bodies > MAX_LIVE_BODIES;) {
			Map.Entry<Key, RlLookAssets> e = it.next();
			if (!e.getKey().octane() && e.getValue().requestedIn != frame) {
				it.remove();
				retire(e.getValue());
				retire(standIns.remove(e.getKey()));
				bodies--;
			}
		}
	}

	private static void retireOtherCaches(RlCache cache) {
		for (var it = live.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Key, RlLookAssets> e = it.next();
			if (e.getKey().cache() != cache) {
				it.remove();
				retire(e.getValue());
				retire(standIns.remove(e.getKey()));
			}
		}
		standIns.entrySet().removeIf(e -> {
			if (e.getKey().cache() != cache) {
				retire(e.getValue());
				return true;
			}
			return false;
		});
	}

	public static @Nullable RlLookAssets drawable(RlLookAssets a) {
		State s = a.state();
		if (s == State.READY) {
			return a;
		}
		return s == State.LOADING && live.get(a.key) == a ? standIns.get(a.key) : null;
	}

	public static void resourcesReloaded() {
		for (var it = live.values().iterator(); it.hasNext();) {
			RlLookAssets a = it.next();
			if (a.state == State.FAILED) {
				it.remove();
				retire(a);
			}
		}
	}

	private static void retire(@Nullable RlLookAssets a) {
		if (a != null) {
			retired.add(a);
		}
	}

	public static void endFrame() {
		retireBeyondCap();
		retireIdle(System.nanoTime());
		for (RlLookAssets a : retired) {
			a.close();
		}
		retired.clear();
		frame++;
	}

	public static void failLive(String reason) {
		for (RlLookAssets a : List.copyOf(live.values())) {
			a.fail(reason);
		}
	}

	private RlLookAssets(Key key, RlCache rl, BodyLook look, TextureQuality quality) {
		this.key = key;
		this.look = look;
		this.withBall = key.octane();
		this.used = withBall ? List.of(Material.values())
				: List.of(Material.BODY, Material.CHASSIS, Material.WHEEL);
		this.quality = quality;
		AABB box = null;
		CompletableFuture<List<RlGpuMesh.Data>> data = null;
		try {
			for (String name : new String[] {"body_shell", "chassis"}) {
				RlCache.Mesh m = mesh(rl, look, name);
				if (m != null) {
					AABB b = MeshBounds.of(m.vertices());
					box = box == null ? b : box.minmax(b);
				}
			}
			if (plan(rl, look)) {
				data = CompletableFuture.supplyAsync(() -> pack(rl, look), Util.backgroundExecutor());
			}
		} catch (RuntimeException e) {
			fail("the RL look could not be set up (" + e + ")");
		}
		probeBox = box != null ? box : MeshBounds.simpleLocal(HitboxPreset.OCTANE);
		meshData = data;
	}

	private static RlCache.@Nullable Mesh mesh(RlCache rl, BodyLook look, String name) {
		RlCache.Mesh m = look.mesh(name);
		return m != null ? m : rl.mesh(name);
	}

	private boolean plan(RlCache rl, BodyLook look) {
		for (String name : meshNames(withBall)) {
			if (mesh(rl, look, name) == null) {
				return fail("the RL cache has no mesh " + name);
			}
		}
		Materials all = rl.allMaterials();
		for (Material m : used) {
			String materialName = switch (m) {
				case BODY -> look.shellMaterial();
				case CHASSIS -> look.chassisMaterial();
				default -> m.material;
			};
			RlMaterial mat;
			try {
				mat = RlMaterials.resolve(all, materialName);
			} catch (IllegalArgumentException e) {
				return fail(String.valueOf(e.getMessage()));
			}
			materials.put(m, m == Material.BODY ? RlMaterials.withFinish(mat) : mat);
			Map<Slot, @Nullable TextureInfo> slots = new LinkedHashMap<>();
			for (Slot s : m.slots) {
				String name = s.param() != null ? mat.texture(s.param()) : s.fixed();
				TextureInfo info = name == null ? null : all.textures().get(name);
				if (info == null && s.required()) {
					return fail("the RL cache has no texture " + (name != null ? name : s.param()) + " (" + materialName
							+ ", " + s.sampler() + ")");
				}
				if (info != null) {
					textures.computeIfAbsent(info.name(),
							n -> key.octane() ? RlTextures.hold(n, this) : RlTextures.holdBody(n, this));
				}
				slots.put(s, info);
			}
			planned.put(m, slots);
		}
		return true;
	}

	private static List<String> meshNames(boolean ball) {
		List<String> names = new ArrayList<>(List.of("body_shell", "chassis"));
		for (String w : RlCache.WHEEL_SUFFIXES) {
			names.add("wheel_rim_" + w);
		}
		for (String w : RlCache.WHEEL_SUFFIXES) {
			names.add("wheel_tyre_" + w);
		}
		if (ball) {
			names.add("ball");
		}
		return names;
	}

	private List<RlGpuMesh.Data> pack(RlCache rl, BodyLook look) {
		List<RlGpuMesh.Data> out = new ArrayList<>();
		for (String name : meshNames(withBall)) {
			out.add(RlGpuMesh.pack(name, Objects.requireNonNull(mesh(rl, look, name))));
		}
		return out;
	}

	public State state() {
		poll();
		return state;
	}

	public @Nullable String failure() {
		return failure;
	}

	public AABB probeBox() {
		return probeBox;
	}

	public Part body(Team team) {
		ready();
		return bodies[team.ordinal()];
	}

	public Part chassis() {
		ready();
		return Objects.requireNonNull(chassis);
	}

	public Part rim(int i) {
		ready();
		return rims[i];
	}

	public Part tyre(int i) {
		ready();
		return tyres[i];
	}

	public Part ball() {
		ready();
		Part b = ball;
		if (b == null) {
			throw new IllegalStateException("the ball is the Octane's look's, not body " + key.body() + "'s");
		}
		return b;
	}

	public String bodyId() {
		return key.body();
	}

	private void ready() {
		if (state != State.READY) {
			throw new IllegalStateException("the RL look is " + state);
		}
	}

	private void poll() {
		if (state != State.LOADING || closed) {
			return;
		}
		boolean pending = false;
		for (Map.Entry<Material, Map<Slot, @Nullable TextureInfo>> m : planned.entrySet()) {
			for (Map.Entry<Slot, @Nullable TextureInfo> e : m.getValue().entrySet()) {
				TextureInfo info = e.getValue();
				if (info == null) {
					continue;
				}
				CompletableFuture<Identifier> f = textures.get(info.name());
				if (!f.isDone() || f.isCancelled()) {
					pending = true;
				} else if (f.isCompletedExceptionally() && e.getKey().required()) {
					fail("the RL texture " + info.name() + " did not load (" + cause(f) + ")");
					return;
				}
			}
		}
		if (compiles == null) {
			compiles = new ArrayList<>();
			for (Material m : used) {
				compiles.add(compile(m.pipeline));
			}
		}
		for (int i = 0; i < compiles.size(); i++) {
			if (!compiles.get(i)) {
				fail("the pipeline " + used.get(i).pipeline.getLocation() + " did not compile (see the log)");
				return;
			}
		}
		CompletableFuture<List<RlGpuMesh.Data>> d = Objects.requireNonNull(meshData);
		if (!d.isDone()) {
			pending = true;
		} else if (d.isCompletedExceptionally()) {
			fail("the RL meshes were not prepared (" + cause(d) + ")");
			return;
		}
		if (!pending) {
			build(d.join());
		}
	}

	private void build(List<RlGpuMesh.Data> data) {
		Map<String, Identifier> ids = new LinkedHashMap<>();
		textures.forEach((name, f) -> {
			if (f.isCompletedExceptionally()) {
				LOG.warn("mcrl: the optional RL texture {} did not load ({}); a neutral one stands in", name, cause(f));
			} else {
				ids.put(name, f.join());
			}
		});
		try {
			Map<String, RlGpuMesh> byName = new LinkedHashMap<>();
			for (RlGpuMesh.Data d : data) {
				RlGpuMesh m = RlGpuMesh.upload(d);
				meshes.add(m);
				byName.put(d.name(), m);
			}
			GpuBuffer chassisBlock = block("chassis", RlUbos.CHASSIS_SIZE,
					b -> RlUbos.chassis(b, materials.get(Material.CHASSIS), srgb(Material.CHASSIS, ids, "ChassisDiffuse",
							"ChassisMasks")));
			GpuBuffer wheelBlock = block("wheel", RlUbos.WHEEL_SIZE, b -> RlUbos.wheel(b, materials.get(Material.WHEEL),
					srgb(Material.WHEEL, ids, "RimDiffuse", "RimRGB", "TireDiffuse")));
			GpuBuffer ballBlock = withBall ? block("ball", RlUbos.BALL_SIZE, b -> RlUbos.ball(b,
					materials.get(Material.BALL), srgb(Material.BALL, ids, "BallDiffuse", "BallMask"))) : null;
			float[] bodySrgb = srgb(Material.BODY, ids, "BodyDiffuse", "BodySkin", "BodyCurvature", "BodyMasks",
					"EnvPack");
			for (Team t : Team.values()) {
				GpuBuffer b = block("body " + t.name().toLowerCase(Locale.ROOT), RlUbos.BODY_SIZE,
						w -> RlUbos.body(w, materials.get(Material.BODY), RlLook.teamColor(t), bodySrgb));
				bodies[t.ordinal()] = part(byName.get("body_shell"), Material.BODY, b, ids);
			}
			chassis = part(byName.get("chassis"), Material.CHASSIS, chassisBlock, ids);
			for (int i = 0; i < WHEELS; i++) {
				String w = RlCache.WHEEL_SUFFIXES.get(i);
				rims[i] = part(byName.get("wheel_rim_" + w), Material.WHEEL, wheelBlock, ids);
				tyres[i] = part(byName.get("wheel_tyre_" + w), Material.WHEEL, wheelBlock, ids);
			}
			if (ballBlock != null) {
				ball = part(byName.get("ball"), Material.BALL, ballBlock, ids);
			}
		} catch (RuntimeException e) {
			freeBuffers();
			fail("the RL meshes did not go up (" + e + ")");
			return;
		}
		state = State.READY;
		LOG.info("mcrl: RL look of {} ready ({} pipelines)", key.body(), used.size());
		dropStandIn();
	}

	private Part part(RlGpuMesh mesh, Material m, GpuBuffer block, Map<String, Identifier> ids) {
		List<Bind> binds = new ArrayList<>();
		for (Map.Entry<Slot, @Nullable TextureInfo> e : planned.get(m).entrySet()) {
			TextureInfo info = e.getValue();
			Identifier id = info == null ? null : ids.get(info.name());
			binds.add(new Bind(e.getKey().sampler(), id != null ? id : neutral(e.getKey().neutral())));
		}
		return new Part(mesh, m.pipeline, m.block, block, List.copyOf(binds));
	}

	private float[] srgb(Material m, Map<String, Identifier> ids, String... samplers) {
		float[] out = new float[samplers.length];
		for (int i = 0; i < samplers.length; i++) {
			for (Map.Entry<Slot, @Nullable TextureInfo> e : planned.get(m).entrySet()) {
				TextureInfo info = e.getValue();
				if (e.getKey().sampler().equals(samplers[i]) && info != null && ids.containsKey(info.name())
						&& RlTextures.kindOf(info) == MipChain.Kind.COLOR_SRGB) {
					out[i] = 1;
				}
			}
		}
		return out;
	}

	private GpuBuffer block(String what, int size, Consumer<Std140Builder> writer) {
		ByteBuffer buf = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
		Std140Builder b = Std140Builder.intoBuffer(buf);
		writer.accept(b);
		GpuBuffer out = RenderSystem.getDevice().createBuffer(() -> "mcrl RL " + what + " block", GpuBuffer.USAGE_UNIFORM,
				b.get());
		blocks.add(out);
		return out;
	}

	private boolean fail(String reason) {
		if (state != State.FAILED) {
			state = State.FAILED;
			failure = reason;
			LOG.warn("mcrl: the RL look of {} failed: {}", key.body(), reason);
			dropStandIn();
		}
		return false;
	}

	private void dropStandIn() {
		if (live.get(key) == this) {
			RlLookAssets s = standIns.remove(key);
			if (s != null) {
				retired.add(s);
			}
		}
	}

	private void freeBuffers() {
		for (RlGpuMesh m : meshes) {
			m.close();
		}
		meshes.clear();
		for (GpuBuffer b : blocks) {
			b.close();
		}
		blocks.clear();
	}

	private void close() {
		if (closed) {
			return;
		}
		closed = true;
		freeBuffers();
		for (String name : textures.keySet()) {
			RlTextures.release(name, this);
		}
	}

	private static Identifier neutral(Neutral n) {
		if (NEUTRALS.add(n)) {
			Minecraft.getInstance().getTextureManager().register(n.id(), new NeutralTexture(n));
		}
		return n.id();
	}

	public static boolean compile(RenderPipeline p) {
		try {
			return RenderSystem.getCompiledPipelineNullable(p) != null;
		} catch (RuntimeException e) {
			LOG.warn("mcrl: pipeline {} did not compile: {}", p.getLocation(), e.toString());
			return false;
		}
	}

	private static String cause(CompletableFuture<?> f) {
		try {
			f.join();
			return "no failure";
		} catch (CompletionException | CancellationException e) {
			Throwable c = e.getCause() != null ? e.getCause() : e;
			return c.toString();
		}
	}

	private static final class NeutralTexture extends AbstractTexture {
		NeutralTexture(Neutral n) {
			GpuDevice device = RenderSystem.getDevice();
			texture = device.createTexture("mcrl neutral " + n, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
					GpuFormat.RGBA32_FLOAT, 1, 1, 1, 1);
			textureView = device.createTextureView(texture);
			sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
			ByteBuffer px = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
			for (float c : n.rgba) {
				px.putFloat(c);
			}
			device.createCommandEncoder().writeToTexture(texture, px.flip(), 0, 0, 0, 0, 1, 1);
		}
	}
}
