package dev.mcrl.client.fx.effects;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.BallSignals;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxActor;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxActors;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.fx.particle.SystemInstance;
import dev.mcrl.client.link.FrameEvents;

public final class BallTrail {
	private static final float[] WHITE = {1, 1, 1};

	private double opacity;
	private double lastSim = Double.NaN;
	private @Nullable FxActor ball;

	public static void register() {
		BallTrail t = new BallTrail();
		FrameEvents.register((mc, frame, frameNanos) -> {
			FxActors actors = FxActors.game();
			FxActor ball = actors == null ? null : actors.ball();
			FxActorRef r = ball == null ? null : actors.ref(ball);
			FrameSignals f = FrameSignals.current();
			if (r != null && f.ball() != null) {
				t.frame(ball, r.params(), f.ball(), f.simSeconds());
			}
		});
	}

	public static boolean fast(Vec3 v) {
		double s = FxConstants.BALL_FAST_UU;
		return v.y > s || v.lengthSqr() > s * s;
	}

	public static float[] color(int team) {
		if (team < 0 || team >= FxConstants.TEAM_FX_COLOR.length) {
			return WHITE.clone();
		}
		float[] c = FxConstants.TEAM_FX_COLOR[team];
		float max = Math.max(c[0], Math.max(c[1], c[2]));
		return max <= 0 ? WHITE.clone() : new float[] {c[0] / max, c[1] / max, c[2] / max};
	}

	public double step(Vec3 v, double dt) {
		double k = Math.clamp(dt * FxConstants.TRAIL_OPACITY_RATE, 0, 1);
		opacity += ((fast(v) ? 1 : 0) - opacity) * k;
		return opacity;
	}

	public double opacity() {
		return opacity;
	}

	public void frame(FxActor actor, FxParams p, BallSignals b, double simSeconds) {
		if (actor != ball) {
			ball = actor;
			opacity = 0;
			lastSim = Double.NaN;
		}
		double dt = Double.isNaN(lastSim) || simSeconds < lastSim ? 0
				: Math.min(simSeconds - lastSim, SystemInstance.MAX_STEP);
		lastSim = simSeconds;
		step(b.velocity(), dt);
		float[] c = color(b.lastTouchTeam());
		p.setScalar(FxConstants.TRAIL_OPACITY_PARAM, (float) opacity);
		p.setScalar(FxConstants.SPEED_PARAM, (float) b.velocity().length());
		p.setScalar(FxConstants.SPAWN_RATE_PARAM, 1);
		p.setVector(FxConstants.COLOR_PARAM, c[0], c[1], c[2]);
	}
}
