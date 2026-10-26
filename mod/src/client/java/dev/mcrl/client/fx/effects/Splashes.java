package dev.mcrl.client.fx.effects;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxEvent;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.sim.Wet;

public final class Splashes {
	private static final float BALL_WIDTH = 3.65f;
	private static final float DRIVEN = 0.9f, UNDRIVEN = 0.2f;
	private static final float HIGH_SPEED_VOLUME = 0.25f;
	private static final double TICKS_PER_SECOND = 20;
	private static final RandomSource RANDOM = RandomSource.create();

	private Splashes() {
	}

	public static void register() {
		FrameEvents.register((mc, frame, frameNanos) -> {
			if (mc.level != null) {
				frame(mc.level, FrameSignals.current());
			}
		});
	}

	private static void frame(ClientLevel level, FrameSignals f) {
		if (!McrlConfig.get().effects.on(Group.WHEELS)) {
			return;
		}
		for (CarSignals c : f.cars().values()) {
			for (FxEvent e : c.events()) {
				if (e instanceof FxEvent.Splash s) {
					Entity car = level.getEntity(c.carId());
					play(level, s, McrlEntities.CAR.getWidth(), car != null && car.getControllingPassenger() != null);
				}
			}
		}
		if (f.ball() != null) {
			for (FxEvent e : f.ball().events()) {
				if (e instanceof FxEvent.Splash s) {
					play(level, s, BALL_WIDTH, false);
				}
			}
		}
	}

	// Vanilla's splash particle count.
	private static int count(float width) {
		int n = 0;
		for (int i = 0; (float) i < 1.0f + width * 20.0f; i++) {
			n++;
		}
		return n;
	}

	private static void play(ClientLevel level, FxEvent.Splash e, float width, boolean driven) {
		if (e.fluid() != Wet.WATER && e.fluid() != Wet.LAVA) {
			return;
		}
		RandomSource r = RANDOM;
		float volume = Math.min(1.0f, (float) (e.speed() / UU_PER_BLOCK / TICKS_PER_SECOND) * (driven ? DRIVEN : UNDRIVEN));
		float pitch = 1.0f + (r.nextFloat() - r.nextFloat()) * 0.4f;
		Vec3 p = e.point();
		int n = count(width);
		if (e.fluid() == Wet.LAVA) {
			level.playLocalSound(p.x, p.y, p.z, SoundEvents.LAVA_POP, SoundSource.NEUTRAL, volume, pitch, false);
			for (int i = Math.max(1, n / 4); i > 0; i--) {
				level.addParticle(ParticleTypes.LAVA, spread(p.x, width, r), p.y, spread(p.z, width, r), 0, 0, 0);
			}
			return;
		}
		level.playLocalSound(p.x, p.y, p.z, volume < HIGH_SPEED_VOLUME ? SoundEvents.GENERIC_SPLASH
				: SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.NEUTRAL, volume, pitch, false);
		for (int i = 0; i < n; i++) {
			level.addParticle(ParticleTypes.BUBBLE, spread(p.x, width, r), p.y, spread(p.z, width, r), 0,
					-r.nextDouble() * 0.2, 0);
		}
		for (int i = 0; i < n; i++) {
			level.addParticle(ParticleTypes.SPLASH, spread(p.x, width, r), p.y, spread(p.z, width, r), 0, 0, 0);
		}
	}

	private static double spread(double c, float width, RandomSource r) {
		return c + (r.nextDouble() * 2 - 1) * width / 2;
	}
}
