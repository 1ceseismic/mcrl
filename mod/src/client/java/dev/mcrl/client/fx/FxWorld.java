package dev.mcrl.client.fx;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.effects.BoostSheets;
import dev.mcrl.client.fx.effects.FlareOcclusion;
import dev.mcrl.client.fx.effects.FxDrawList;
import dev.mcrl.client.fx.effects.FxEffect;
import dev.mcrl.client.fx.effects.FxFrame;
import dev.mcrl.client.fx.effects.GroundReticle;
import dev.mcrl.client.fx.effects.LensFlare;
import dev.mcrl.client.fx.particle.BlockCollider;
import dev.mcrl.client.fx.particle.EmitterFrame;
import dev.mcrl.client.fx.particle.EmitterInstance;
import dev.mcrl.client.fx.particle.FxCollider;
import dev.mcrl.client.fx.particle.FxEmitterDef.Blend;
import dev.mcrl.client.fx.particle.FxEmitterDef.Kind;
import dev.mcrl.client.fx.particle.SystemInstance;
import dev.mcrl.client.fx.render.FxColor;
import dev.mcrl.client.fx.render.FxFrameDraws;
import dev.mcrl.client.fx.render.FxGeometry;
import dev.mcrl.client.fx.render.FxMaterial;
import dev.mcrl.client.fx.render.FxRecipe;
import dev.mcrl.client.fx.render.FxRenderTypes;
import dev.mcrl.client.fx.render.FxSkin;
import dev.mcrl.client.fx.render.FxVertices;
import dev.mcrl.client.fx.render.FxView;
import dev.mcrl.client.link.ClientPoses;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.render.look.CarLooks;
import dev.mcrl.client.render.look.SceneLight;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.SimFrame;

public final class FxWorld {
	private static final FxWorld GAME = new FxWorld();
	private static final String LENS_FLARE = "LensFlareComponent", DECAL = "DecalComponent";
	private static final double FLARE_CLEARANCE = 0.05;

	private final List<FxEffect> effects = new ArrayList<>();
	private final FxDrawList draws = new FxDrawList();
	private final FxGeometry geometry = new FxGeometry();
	private final FxView view = new FxView();
	private final Vertices vertices = new Vertices();
	private final FxFrameDraws frameDraws = new FxFrameDraws();
	private final Vector3d origin = new Vector3d(), flareSource = new Vector3d();
	private @Nullable ClientLevel lightLevel;
	private final FxGeometry.Light light = (x, y, z) -> {
		ClientLevel l = lightLevel;
		return l == null ? FxGeometry.FULL_BRIGHT : LightCoordsUtil.getLightCoords(l, BlockPos.containing(x, y, z));
	};
	private final FlareOcclusion flares = new FlareOcclusion();
	private final FlareOcclusion.Blocked blocked = (x0, y0, z0, x1, y1, z1) -> {
		ClientLevel l = lightLevel;
		if (l == null) {
			return false;
		}
		double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double k = len > FLARE_CLEARANCE ? 1 - FLARE_CLEARANCE / len : 0;
		return l.clip(new ClipContext(new Vec3(x0, y0, z0), new Vec3(x0 + dx * k, y0 + dy * k, z0 + dz * k),
				ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
				== HitResult.Type.BLOCK;
	};
	private double frameDt;
	private double lastSim = Double.NaN;
	private @Nullable ClientLevel colliderLevel;
	private FxCollider collider = FxCollider.NONE;

	public static FxWorld get() {
		return GAME;
	}

	public static void register() {
		FrameEvents.register(GAME::frame);
		LevelExtractionEvents.END_EXTRACTION.register(GAME::camera);
		LevelRenderEvents.COLLECT_SUBMITS.register(GAME::submit);
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> GAME.clear());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(GAME::clear));
	}

	public <T extends FxEffect> T add(T effect) {
		effects.add(effect);
		return effect;
	}

	public void clear() {
		for (FxEffect e : effects) {
			e.removed();
		}
		effects.clear();
		draws.clear();
		frameDraws.clear();
		lastSim = Double.NaN;
		lightLevel = null;
		colliderLevel = null;
		collider = FxCollider.NONE;
	}

	public FxView view() {
		return view;
	}

	double clock(double now) {
		if (Double.isNaN(lastSim) || now < lastSim || !Double.isFinite(now)) {
			lastSim = now;
			return 0;
		}
		double dt = Math.min(now - lastSim, SystemInstance.MAX_STEP);
		lastSim = now;
		return dt;
	}

	public void update(FxFrame f) {
		for (int i = 0; i < effects.size(); i++) {
			effects.get(i).update(f);
		}
		effects.removeIf(FxWorld::drop);
	}

	private static boolean drop(FxEffect e) {
		if (e.done()) {
			e.removed();
			return true;
		}
		return false;
	}

	private void frame(Minecraft mc, SimFrame f, long frameNanos) {
		double dt = clock(FrameSignals.current().simSeconds());
		frameDt = dt;
		EffectsSettings settings = McrlConfig.get().effects;
		if (settings.enabled()) {
			RlConversion.game().start();
		}
		if (effects.isEmpty()) {
			return;
		}
		ClientLevel level = mc.level;
		if (level != colliderLevel) {
			colliderLevel = level;
			collider = level == null ? FxCollider.NONE : new BlockCollider(level);
		}
		update(new FxFrame(new EmitterFrame(dt, FxBudget.spawnFactor(), view.position(), collider), settings));
	}

	private void camera(LevelExtractionContext ctx) {
		view.set(ctx.camera());
	}

	private void submit(LevelRenderContext ctx) {
		if (effects.isEmpty()) {
			return;
		}
		draws.clear();
		for (FxEffect e : effects) {
			e.collect(draws);
		}
		RlCache rl = RlCache.loaded();
		lightLevel = Minecraft.getInstance().level;
		PoseStack ps = ctx.poseStack();
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = view.position();
		LevelRenderState level = mc.gameRenderer.gameRenderState().levelRenderState;
		Vector3fc fog = mc.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR,
				level.worldPartialTicks);
		Vector3f sky = SceneLight.values(level.skyRenderState, fog, ClientPoses.simSeconds()).cubeSky();
		frameDraws.begin(FrameSignals.current().simSeconds(), sky.x, sky.y, sky.z);
		for (int i = 0; i < draws.size(); i++) {
			EmitterInstance e = draws.get(i);
			RlCache.Mesh mesh = null;
			if (e.def().typeData().kind() == Kind.MESH) {
				String name = e.def().typeData().mesh();
				mesh = rl == null || name == null ? null : rl.mesh(name);
				if (mesh == null) {
					continue;
				}
			}
			Blend blend = FxMaterial.blend(rl, e);
			RlCache.Mesh drawn = mesh;
			if (blend == Blend.OPAQUE) {
				ctx.submitNodeCollector().submitCustomGeometry(ps, FxRenderTypes.opaqueMesh(),
						(pose, vc) -> {
							vertices.start(pose, vc, true);
							geometry.emitter(e, view, drawn, light, vertices);
						});
				continue;
			}
			FxMaterial.Bound bound = FxMaterial.bind(rl, FxMaterial.materialOf(rl, e));
			if (bound.recipe() == FxRecipe.HIDDEN) {
				continue;
			}
			FxSkin skin = e.def().typeData().kind() == Kind.SPRITE ? draws.skin(i) : null;
			int first = frameDraws.vertices().count();
			geometry.emitter(e, view, drawn, skin, light, frameDraws.vertices());
			e.emitterToWorldRl(0, 0, 0, origin);
			frameDraws.add(bound, blend, skin, first, (float) (origin.z - cam.y * 50));
		}
		for (FxDrawList.Static st : draws.statics()) {
			submitStatic(ctx, ps, rl, st);
		}
		flares.endFrame();
		frameDraws.submit(ctx.submitNodeCollector());
	}

	private void submitStatic(LevelRenderContext ctx, PoseStack ps, @Nullable RlCache rl, FxDrawList.Static st) {
		if (st.def().componentClass().startsWith(DECAL)) {
			submitReticle(ctx, ps, rl, st);
			return;
		}
		FxVertices out = frameDraws.vertices();
		int first = out.count();
		if (st.def().componentClass().startsWith(LENS_FLARE)) {
			FxMaterial.Bound bound = FxMaterial.bind(rl, FxConstants.LENS_FLARE_MATERIAL);
			if (bound.recipe() == FxRecipe.UNTEXTURED) {
				return;
			}
			float alpha = LensFlare.alpha(st, view);
			if (!(alpha > 0)) {
				return;
			}
			Vector3d s = LensFlare.source(st, flareSource);
			Vec3 cam = view.position();
			float visibility = flares.update(LensFlare.key(st), !blocked.between(cam.x, cam.y, cam.z, s.x, s.y, s.z),
					frameDt);
			LensFlare.quad(st, view, alpha * visibility, geometry, out);
			frameDraws.add(bound, Blend.ADDITIVE, null, first, 0);
			return;
		}
		BoostSheets.Draw d = rl == null ? null : BoostSheets.resolve(rl, CarLooks.ofCar(rl, st.ref().carId()), st);
		if (d == null) {
			return;
		}
		Matrix4f m = d.cameraRelative(view.position(), new Matrix4f());
		float[] c = d.color();
		out.particle(c[0], c[1], c[2], 1, 0, 0, 0, 0, 0);
		geometry.staticMesh(d.mesh(), m, FxColor.additive(c[0], c[1], c[2], 1), FxGeometry.FULL_BRIGHT, out);
		frameDraws.add(FxMaterial.bind(rl, d.material()), Blend.ADDITIVE, null, first, 0);
	}

	private void submitReticle(LevelRenderContext ctx, PoseStack ps, @Nullable RlCache rl, FxDrawList.Static st) {
		FxMaterial.Texture tex = FxMaterial.texture(rl, FxConstants.RETICLE_MATERIAL);
		ClientLevel level = lightLevel;
		if (tex == null || level == null) {
			return;
		}
		Pose p = st.pose();
		List<GroundReticle.Quad> quads = GroundReticle.place(p.x(), p.y(), p.z(), level);
		float altitude = GroundReticle.altitude(p.x(), p.z(), quads);
		if (quads.isEmpty()) {
			return;
		}
		Vec3 cam = view.position();
		ctx.submitNodeCollector().submitCustomGeometry(ps, FxRenderTypes.reticle(tex.id(), tex.colorSrgb()),
				(pose, vc) -> {
					vertices.start(pose, vc, false);
					GroundReticle.vertices(quads, altitude, cam.x, cam.y, cam.z, vertices);
				});
	}

	private static final class Vertices implements FxGeometry.Sink {
		private PoseStack.@Nullable Pose pose;
		private @Nullable VertexConsumer vc;
		private boolean entity;

		void start(PoseStack.Pose pose, VertexConsumer vc, boolean entity) {
			this.pose = pose;
			this.vc = vc;
			this.entity = entity;
		}

		@Override
		public void vertex(float x, float y, float z, float u, float v, int argb, int light, float nx, float ny,
				float nz) {
			VertexConsumer c = vc.addVertex(pose, x, y, z).setUv(u, v).setColor(argb).setLight(light);
			if (entity) {
				c.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, nx, ny, nz);
			}
		}
	}
}
