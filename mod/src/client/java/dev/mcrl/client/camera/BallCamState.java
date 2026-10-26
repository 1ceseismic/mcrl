package dev.mcrl.client.camera;

import static dev.mcrl.client.camera.RlCamConstants.BALL_PITCH_HIGH;
import static dev.mcrl.client.camera.RlCamConstants.BALL_PITCH_LIFT;
import static dev.mcrl.client.camera.RlCamConstants.BALL_PITCH_LOW;
import static dev.mcrl.client.camera.RlCamConstants.BALL_PITCH_STRENGTH;
import static dev.mcrl.client.camera.RlCamConstants.BALL_ROT_RATE;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

public final class BallCamState {
	private boolean first = true;
	private Rot rot = Rot.ZERO;
	private double distance, fov;
	private @Nullable Pov last;

	public void reset() {
		first = true;
		last = null;
	}

	public Pov update(CamInput in, CameraSettings s, double dt) {
		if (!first && dt <= 0 && last != null) {
			return last;
		}
		Vec3 ball = in.ball();
		if (ball == null) {
			throw new IllegalArgumentException("ball cam without a ball");
		}
		int pitchOff = CarCamState.pitchOffset(s);
		Vec3 focus = in.carPos().add(0, 0, s.height());
		focus = focus.add(0, 0, Math.abs(Rot.of(ball.subtract(focus)).pitch()) * BALL_PITCH_LIFT);
		Vec3 d = ball.subtract(focus);
		Rot r = Rot.of(d);
		if (d.x * d.x + d.y * d.y < 1) {
			r = r.withYaw(first ? Rot.of(in.carFwd()).yaw() : rot.yaw());
		}
		double strength = UeMath.clamp((Math.abs(r.pitch()) - BALL_PITCH_LOW) / (BALL_PITCH_HIGH - BALL_PITCH_LOW), 0, 1);
		r = new Rot(UeMath.lerp(pitchOff, r.pitch(), BALL_PITCH_STRENGTH * strength), r.yaw(), 0);
		rot = first ? r : UeMath.rsmooth(rot, r, BALL_ROT_RATE, dt);
		distance = CarCamState.stepDistance(first, distance, in, s, rot, dt);
		fov = CarCamState.stepFov(first, fov, in, s, dt);
		first = false;
		last = new Pov(focus, rot, distance, fov);
		return last;
	}
}
