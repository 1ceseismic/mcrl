package dev.mcrl.client.fx.effects;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.link.FrameEvents;

public final class BallDissolve {
	public static final float FADE_IN_SPEED = 1;
	public static final double MAX_STEP = 1 / 30.0;

	private static final BallDissolve GAME = new BallDissolve();

	private float opacity = 1;
	private float speed;
	private double lastSim = Double.NaN;

	public static BallDissolve game() {
		return GAME;
	}

	public static void register() {
		FrameEvents.register((mc, frame, frameNanos) -> GAME.frame(FrameSignals.current().simSeconds()));
	}

	public void start() {
		if (!McrlConfig.get().effects.on(Group.BALL)) {
			return;
		}
		startFade();
	}

	public void startFade() {
		opacity = 0;
		speed = FADE_IN_SPEED;
	}

	public void frame(double simSeconds) {
		double dt = Double.isNaN(lastSim) || simSeconds < lastSim ? 0 : simSeconds - lastSim;
		lastSim = simSeconds;
		update(dt);
	}

	public void update(double dt) {
		if (speed == 0 || !(dt > 0)) {
			return;
		}
		opacity = (float) Math.clamp(opacity + Math.min(dt, MAX_STEP) * speed, 0, 1);
		if (opacity >= 1 || opacity <= 0) {
			speed = 0;
		}
	}

	public float opacity() {
		return opacity;
	}

	public boolean dissolving() {
		return opacity < 1;
	}
}
