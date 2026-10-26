package dev.mcrl.client.fx.effects;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.RlSurface;
import dev.mcrl.client.fx.WheelSignal;
import dev.mcrl.client.fx.particle.SystemInstance;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.rl.RlCache;

public final class VanillaWheelFx {
	private static final double EPS = 1e-6;

	private final RandomSource random = RandomSource.create();
	private final Int2ObjectOpenHashMap<Wheel[]> cars = new Int2ObjectOpenHashMap<>();
	private double lastSim = Double.NaN;

	private static final class Wheel {
		@Nullable Vec3 last;
		double lava, splash, dust;
	}

	private VanillaWheelFx() {
	}

	public static void register() {
		VanillaWheelFx fx = new VanillaWheelFx();
		FrameEvents.register((mc, frame, frameNanos) -> fx.frame(mc.level, FrameSignals.current()));
	}

	private void frame(@Nullable ClientLevel level, FrameSignals f) {
		double now = f.simSeconds();
		double dt = Double.isNaN(lastSim) || now < lastSim ? 0 : Math.min(now - lastSim, SystemInstance.MAX_STEP);
		lastSim = now;
		cars.keySet().removeIf(id -> f.car(id) == null);
		boolean on = McrlConfig.get().effects.on(Group.WHEELS), rl = RlCache.loaded() != null;
		for (CarSignals s : f.cars().values()) {
			Wheel[] ws = cars.computeIfAbsent(s.carId(), id -> new Wheel[] {new Wheel(), new Wheel(), new Wheel(),
					new Wheel()});
			int mask = WheelFxRules.emitting(s);
			for (int i = 0; i < 4; i++) {
				WheelSignal w = s.wheel(i);
				Wheel st = ws[i];
				if (!on || !w.contact()) {
					st.last = null;
					st.lava = st.splash = st.dust = 0;
					continue;
				}
				Vec3 p = w.point();
				Vec3 move = st.last == null ? Vec3.ZERO : p.subtract(st.last);
				double uu = move.length() * 50;
				if (uu > SystemInstance.TELEPORT_UU) {
					uu = 0;
					move = Vec3.ZERO;
				}
				st.last = p;
				lava(level, st, p, uu);
				boolean emits = !rl && (mask >> i & 1) != 0 && w.surface() != null
						&& !(s.supersonic() && WheelFxRules.rear(i));
				if (!emits) {
					st.splash = st.dust = 0;
				} else if (w.surface() == RlSurface.WATER) {
					st.splash += uu;
					for (; st.splash >= FxConstants.WHEEL_PARTICLE_UU - EPS; st.splash -= FxConstants.WHEEL_PARTICLE_UU) {
						add(level, ParticleTypes.SPLASH, p.x, p.y, p.z, 0, 0, 0);
					}
				} else if (w.surface() != RlSurface.BALL) {
					dust(level, st, w, dt > 0 ? move.scale(1 / dt) : Vec3.ZERO, dt);
				}
			}
		}
	}

	private static void lava(@Nullable ClientLevel level, Wheel st, Vec3 p, double uu) {
		BlockPos at = BlockPos.containing(p);
		if (!lava(level, at) && !lava(level, at.below())) {
			st.lava = 0;
			return;
		}
		st.lava += uu;
		for (; st.lava >= FxConstants.WHEEL_PARTICLE_UU - EPS; st.lava -= FxConstants.WHEEL_PARTICLE_UU) {
			add(level, ParticleTypes.LAVA, p.x, p.y, p.z, 0, 0, 0);
			add(level, ParticleTypes.SMOKE, p.x, p.y, p.z, 0, 0, 0);
		}
	}

	private static boolean lava(@Nullable ClientLevel level, BlockPos pos) {
		return Fluids.LAVA.isSame(block(level, pos).getFluidState().getType());
	}

	private static BlockState block(@Nullable ClientLevel level, BlockPos pos) {
		return level == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
	}

	private static void add(@Nullable ClientLevel level, ParticleOptions p, double x, double y, double z, double vx,
			double vy, double vz) {
		if (level != null) {
			level.addParticle(p, x, y, z, vx, vy, vz);
		}
	}

	private void dust(@Nullable ClientLevel level, Wheel st, WheelSignal w, Vec3 velocity, double dt) {
		BlockState state = block(level, BlockSprite.under(w.point(), w.normal()));
		if (state.isAir() || state.liquid()) {
			st.dust = 0;
			return;
		}
		double scale = Math.clamp((Math.abs(w.sideSpeed()) - FxConstants.DIRT_SIDE_SPEED_MIN)
				/ FxConstants.DIRT_SIDE_SPEED_RANGE, 0, 1);
		st.dust += FxConstants.DIRT_RATE * scale * dt;
		BlockParticleOption block = new BlockParticleOption(ParticleTypes.BLOCK, state);
		double[] lo = FxConstants.DIRT_VELOCITY_MIN, hi = FxConstants.DIRT_VELOCITY_MAX;
		for (; st.dust >= 1 - EPS; st.dust -= 1) {
			double rx = lo[0] + (hi[0] - lo[0]) * random.nextDouble();
			double ry = lo[1] + (hi[1] - lo[1]) * random.nextDouble();
			double rz = lo[2] + (hi[2] - lo[2]) * random.nextDouble();
			double k = FxConstants.DIRT_INHERIT;
			add(level, block, w.point().x, w.point().y, w.point().z, (rx / 50 + k * velocity.x) / 20,
					(rz / 50 + k * velocity.y) / 20, (ry / 50 + k * velocity.z) / 20);
		}
	}
}
