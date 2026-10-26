package dev.mcrl.client.render;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.Team;
import dev.mcrl.client.config.McrlConfig.Visuals;
import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.link.ClientPoses;
import dev.mcrl.client.match.ClientTeams;
import dev.mcrl.client.render.look.CarLooks;
import dev.mcrl.client.render.look.CarShadow;
import dev.mcrl.client.render.look.LookDraws;
import dev.mcrl.client.render.look.MeshBounds;
import dev.mcrl.client.render.look.RlLook;
import dev.mcrl.client.render.look.WheelSpin;
import dev.mcrl.client.rl.BodyLook;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.sim.SimFrame;

public class CarRenderer extends EntityRenderer<CarEntity, CarRenderer.CarRenderState> {
	private static final double MAX_DT = 0.25;

	private static final List<Simple> SIMPLE = HitboxPreset.ALL.stream().map(Simple::of).toList();

	private final Map<CarEntity, WheelSpin> spins = new WeakHashMap<>();
	private final Map<CarEntity, BoostFlame.FlameState> flames = new WeakHashMap<>();
	private final Map<CarEntity, RlWheelPose> rlWheels = new WeakHashMap<>();
	private final Map<CarEntity, Lamps> lamps = new WeakHashMap<>();
	private final Map<BodyLook, AABB> rlBoxes = new IdentityHashMap<>();
	private static final int RL_BOXES = 64;
	private @Nullable RlCache rlBoxCache;

	public CarRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public CarRenderState createRenderState() {
		return new CarRenderState();
	}

	public static boolean hidden(SimFrame.@Nullable CarSnap snap) {
		return snap != null && snap.demoed();
	}

	static float[] restWheels(HitboxPreset h) {
		float[] w = new float[12];
		for (int i = 0; i < 4; i++) {
			HitboxPreset.Axle a = i < 2 ? h.front() : h.back();
			w[3 * i] = (float) a.connForward();
			w[3 * i + 1] = (float) (a.connUp() - (a.suspensionRest() - HitboxPreset.MAX_SUSPENSION_TRAVEL));
			w[3 * i + 2] = (float) (i % 2 == 0 ? a.connRight() : -a.connRight());
		}
		return w;
	}

	@Override
	public void extractRenderState(CarEntity car, CarRenderState s, float partialTick) {
		super.extractRenderState(car, s, partialTick);
		Visuals vis = RlConversion.effectiveVisuals();
		Pose p = vis == Visuals.HITBOX ? ClientPoses.carRaw(car.getId()) : ClientPoses.car(car.getId());
		SimFrame.CarSnap snap = ClientPoses.carSnap(car.getId());
		s.hidden = hidden(snap);
		s.preset = car.hitboxPreset();
		if (p == null) {
			p = Pose.upright(s.x, s.y, s.z, car.getYRot());
		}
		s.pose = p;
		s.x = p.x();
		s.y = p.y();
		s.z = p.z();
		boolean boosting = false;
		if (snap != null) {
			System.arraycopy(snap.wheelLocal(), 0, s.wheelLocal, 0, 12);
			boosting = (snap.carFlags() & CarState.FLAG_BOOSTING) != 0;
			s.steer = snap.steerAngle();
			s.wheelContact = snap.wheelContact();
		} else {
			System.arraycopy(SIMPLE.get(s.preset.index()).restWheels(), 0, s.wheelLocal, 0, 12);
			s.steer = 0f;
			s.wheelContact = 0b1111;
		}
		double now = ClientPoses.simSeconds();
		CarSignals signals = FrameSignals.current().car(car.carId());
		WheelSpin spin = spins.computeIfAbsent(car, c -> new WheelSpin());
		spin.advance(now, MAX_DT, signals, s.preset);
		for (int i = 0; i < 4; i++) {
			s.wheelSpin[i] = spin.angle(i);
		}
		Lamps l = lamps.computeIfAbsent(car, c -> new Lamps());
		l.advance(now, signals);
		s.boostGlow = l.glow;
		s.brake = l.brake;
		s.visuals = vis;
		RlCache loaded = RlCache.loaded();
		boolean flame = BoostFlame.shows(loaded, McrlConfig.get().effects);
		s.boost = flames.computeIfAbsent(car, c -> new BoostFlame.FlameState()).update(boosting && flame, now);
		s.boostPulse = BoostFlame.pulse(now);
		s.rl = s.visuals == Visuals.RL ? loaded : null;
		s.look = s.rl != null ? CarLooks.of(s.rl, car) : null;
		s.boostSocket = BoostFlame.socket(s.look != null ? s.look : loaded == null ? null : loaded.bodies().octane());
		if (s.rl != null && s.look != null) {
			RlWheelPose wp = rlWheels.computeIfAbsent(car, c -> new RlWheelPose());
			float upY = 1 - 2 * (p.qx() * p.qx() + p.qz() * p.qz());
			wp.advance(s.look.wheels(), now, MAX_DT, s.steer, s.wheelLocal, s.wheelContact, upY);
			s.rlSteer = wp.steer;
			System.arraycopy(wp.offset, 0, s.rlWheelOffset, 0, 4);
			LookDraws.carLight(s.rl, s.look, car.level(), p, s.probes, s.ground);
		}
		McrlConfig cfg = McrlConfig.get();
		s.team = ClientTeams.of(car);
		s.carShadow.clear();
		s.carShadowFootprint = null;
		if (cfg.carShadow && !s.isInvisible && Minecraft.getInstance().options.entityShadows().get()) {
			AABB box = localBox(s.rl, s.look, s.preset);
			s.carShadowFootprint = CarShadow.footprint(p, box);
			CarShadow.pieces(car.level(), p, box, s.distanceToCameraSq, s.carShadow);
		}
	}

	private AABB localBox(@Nullable RlCache rl, @Nullable BodyLook look, HitboxPreset h) {
		if (rl == null || look == null) {
			return SIMPLE.get(h.index()).box();
		}
		if (rl != rlBoxCache || rlBoxes.size() > RL_BOXES) {
			rlBoxes.clear();
			rlBoxCache = rl;
		}
		return rlBoxes.computeIfAbsent(look, MeshBounds::carLocal);
	}

	@Override
	protected AABB getBoundingBoxForCulling(CarEntity car, float partialTick) {
		Visuals vis = RlConversion.effectiveVisuals();
		Pose p = vis == Visuals.HITBOX ? ClientPoses.carRaw(car.getId()) : ClientPoses.car(car.getId());
		if (p == null) {
			Vec3 at = car.getPosition(partialTick);
			p = Pose.upright(at.x, at.y, at.z, car.getYRot());
		}
		RlCache loaded = RlCache.loaded(), rl = vis == Visuals.RL ? loaded : null;
		BodyLook look = rl != null ? CarLooks.of(rl, car) : null;
		AABB box = localBox(rl, look, car.hitboxPreset());
		if (BoostFlame.shows(loaded, McrlConfig.get().effects)) {
			float[] socket = BoostFlame.socket(look != null ? look : loaded == null ? null : loaded.bodies().octane());
			double flameEnd = socket[0] - BoostFlame.OUTER_LENGTH * (1 + BoostFlame.PULSE_AMPLITUDE);
			double r = BoostFlame.OUTER_RADIUS;
			box = box.minmax(new AABB(flameEnd, socket[1] - r, socket[2] - r, socket[0], socket[1] + r, socket[2] + r));
		}
		return MeshBounds.worldBox(p, box);
	}

	@Override
	public void submit(CarRenderState s, PoseStack ps, SubmitNodeCollector collector, CameraRenderState camera) {
		if (s.hidden) {
			return;
		}
		CarShadow.Footprint shadow = s.carShadowFootprint;
		if (shadow != null) {
			CarShadow.submit(s.carShadow, shadow, s.x, s.y, s.z, ps, collector);
		}
		Pose p = s.pose;
		ps.pushPose();
		ps.rotate(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()));
		RlCache rl = s.rl;
		BodyLook look = s.look;
		boolean rlDrawn = s.visuals == Visuals.RL && rl != null && look != null
				&& LookDraws.submitCar(rl, look, s, ps, collector);
		if (s.visuals == Visuals.HITBOX) {
			HitboxRenderer.submitCar(s, ps, collector);
		} else if (!rlDrawn) {
			submitSimple(s, ps, collector);
		}
		BoostFlame.submit(s, ps, collector);
		ps.popPose();
		super.submit(s, ps, collector, camera);
	}

	private static void submitSimple(CarRenderState s, PoseStack ps, SubmitNodeCollector collector) {
		RenderType type = MeshEmitter.renderType();
		int light = s.lightCoords;
		Simple simple = SIMPLE.get(s.preset.index());
		collector.submitCustomGeometry(ps, type, (pose, vc) -> MeshEmitter.emitTriangles(pose, vc, simple.body(), light));
		for (int i = 0; i < 4; i++) {
			ps.pushPose();
			ps.translate(s.wheelLocal[3 * i], s.wheelLocal[3 * i + 1], s.wheelLocal[3 * i + 2]);
			ps.rotate(wheelRotation(i, s));
			float[] wheel = i < 2 ? simple.frontWheel() : simple.backWheel();
			collector.submitCustomGeometry(ps, type, (pose, vc) -> MeshEmitter.emitTriangles(pose, vc, wheel, light));
			ps.popPose();
		}
	}

	static Quaternionf wheelRotation(int i, CarRenderState s) {
		return new Quaternionf().rotationY(i < 2 ? s.steer : 0f).rotateZ(-s.wheelSpin[i]);
	}

	@Override
	protected boolean affectedByCulling(CarEntity car) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null || mc.player.getVehicle() != car;
	}

	private record Simple(float[] body, float[] frontWheel, float[] backWheel, AABB box, float[] restWheels) {
		static Simple of(HitboxPreset h) {
			return new Simple(SimpleModels.carBody(h), SimpleModels.wheel((float) h.front().wheelRadius()),
					SimpleModels.wheel((float) h.back().wheelRadius()), MeshBounds.simpleLocal(h), CarRenderer.restWheels(h));
		}
	}

	public static class CarRenderState extends EntityRenderState {
		public boolean hidden;
		public HitboxPreset preset = HitboxPreset.OCTANE;
		public Pose pose = new Pose(0, 0, 0, 1f, 0f, 0f, 0f);
		public final float[] wheelLocal = new float[12];
		public float steer;
		public int wheelContact;
		public final float[] wheelSpin = new float[4];
		public float rlSteer;
		public final float[] rlWheelOffset = new float[4];
		public Visuals visuals = Visuals.SIMPLE;
		public @Nullable RlCache rl;
		public @Nullable BodyLook look;
		public Team team = Team.BLUE;
		public float boost;
		public float boostPulse = 1f;
		public float[] boostSocket = BoostFlame.DEFAULT_SOCKET;
		public float boostGlow;
		public float brake;
		public final float[] probes = new float[16];
		public final float[] ground = new float[3];
		public final List<CarShadow.Piece> carShadow = new ArrayList<>();
		public CarShadow.@Nullable Footprint carShadowFootprint;
	}

	private static final class Lamps {
		float glow;
		float brake;
		double simTime = Double.NaN;

		void advance(double now, @Nullable CarSignals s) {
			double dt = Double.isNaN(simTime) || !(now > simTime) ? 0 : Math.min(now - simTime, MAX_DT);
			simTime = now;
			if (s == null) {
				return;
			}
			double forward = s.forwardSpeed();
			double target = s.onGround() || s.boosting() ? forward / RlLook.MAX_LINEAR_SPEED_UU : 0;
			glow += (float) ((target - glow) * Math.min(RlLook.BOOST_GLOW_RATE * dt, 1));
			double t = s.boosting() ? 1 : s.throttle();
			boolean braking = Math.abs(t) >= RlLook.THROTTLE_DEADZONE && Math.abs(forward) > RlLook.STOPPING_FORWARD_VEL_UU
					&& Math.signum(t) != Math.signum(forward);
			brake = braking ? 1 : 0;
		}
	}
}
