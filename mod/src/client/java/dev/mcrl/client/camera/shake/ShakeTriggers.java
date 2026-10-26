package dev.mcrl.client.camera.shake;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.FxEvent;

public final class ShakeTriggers {
	private final Edge jump = new Edge(RlShakes.JUMP, RlShakes.JUMP_FF),
			doubleJump = new Edge(RlShakes.JUMP, RlShakes.JUMP_FF), dodge = new Edge(RlShakes.DODGE, RlShakes.DODGE_FF),
			boost = new Edge(RlShakes.NONE, RlShakes.BOOST_ACTIVATED_FF);

	public void frame(CarSignals car, ShakePlayer p, double now) {
		if (!car.local()) {
			return;
		}
		boolean jumped = false, doubleJumped = false, dodged = false, boosted = false;
		for (FxEvent e : car.events()) {
			switch (e) {
				case FxEvent.Jumped _ -> jumped = true;
				case FxEvent.DoubleJumped _ -> doubleJumped = true;
				case FxEvent.Dodged _ -> dodged = true;
				case FxEvent.BoostStart _ -> boosted = true;
				case FxEvent.WorldHit h -> impact(h.speed(), p, now);
				case FxEvent.BallHit h -> impact(h.speed(), p, now);
				case FxEvent.Landed l -> {
					double scale = RlShakes.LAND_SCALE.at(l.normalSpeed());
					if (scale > 0) {
						p.play(RlShakes.LAND, RlShakes.LAND_FF, scale, now);
					}
				}
				case FxEvent.FlipReset _ -> p.play(RlShakes.NONE, RlShakes.FLIP_RESET_FF, 1, now);
				default -> {
				}
			}
		}
		jump.update(car.fx().jumping() || jumped, p, now);
		doubleJump.update(car.fx().doubleJumping() || doubleJumped, p, now);
		dodge.update(car.fx().flipping() || dodged, p, now);
		boost.update(car.boosting() || boosted, p, now);
	}

	public void reset() {
		jump.reset();
		doubleJump.reset();
		dodge.reset();
		boost.reset();
	}

	public static void explosion(double distanceUu, ShakePlayer p, double now) {
		double scale = RlShakes.explosionScale(distanceUu);
		if (scale > 0) {
			p.play(RlShakes.EXPLOSION_MEDIUM, RlShakes.EXPLOSION_MEDIUM_FF, scale, now);
		}
	}

	private static void impact(double speed, ShakePlayer p, double now) {
		double scale = RlShakes.IMPACT_SCALE.at(speed);
		if (scale > 0) {
			p.play(RlShakes.IMPACT_MEDIUM, scale, now);
		}
	}

	private static final class Edge {
		private final ShakeDef def;
		private final FfWaveform ff;
		private boolean on;
		private int id = -1;

		Edge(ShakeDef def, FfWaveform ff) {
			this.def = def;
			this.ff = ff;
		}

		void update(boolean now, ShakePlayer p, double time) {
			if (now && !on) {
				id = p.play(def, ff, 1, time);
			} else if (!now && on) {
				p.stop(id, time);
			}
			on = now;
		}

		void reset() {
			on = false;
			id = -1;
		}
	}
}
