package dev.mcrl.client.camera;

import static dev.mcrl.client.camera.RlCamConstants.MAX_DT;
import static dev.mcrl.client.camera.RlCamConstants.TELEPORT_UU;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.camera.shake.ShakePlayer;

public final class RlCamera {
	public record Frame(Pov pov, Vec3 location, Rot shakeRot, Vec3 shakeLoc, double fov, boolean snapped) {
	}

	private final ShakePlayer shakes = new ShakePlayer();
	private final CarCamState car = new CarCamState();
	private final BallCamState ball = new BallCamState();
	private final CamBlend blend = new CamBlend();
	private final Swivel swivel = new Swivel();
	private boolean ballCamOn, mouseLook = true, shakeOn = true;
	private boolean first = true, ballActive;
	private @Nullable Vec3 lastCarPos;
	private @Nullable Pov lastOutput;
	private @Nullable Frame last;

	public void reset() {
		snap();
		swivel.reset();
		shakes.clear();
		ballCamOn = false;
		lastCarPos = null;
		last = null;
	}

	public void ballCam(boolean on) {
		ballCamOn = on;
	}

	public boolean ballCamOn() {
		return ballCamOn;
	}

	public void stick(double x, double y) {
		swivel.stick(x, y);
	}

	public void mouse(double dx, double dy) {
		if (mouseLook) {
			swivel.mouse(dx, dy);
		}
	}

	public void mouseLook(boolean on) {
		mouseLook = on;
	}

	public ShakePlayer shakes() {
		return shakes;
	}

	public void shake(boolean on) {
		shakeOn = on;
	}

	public @Nullable Frame update(CamInput in, CameraSettings s, double now, double dt) {
		if (!in.finite()) {
			return last;
		}
		dt = dt > 0 ? Math.min(dt, MAX_DT) : 0;
		if (lastCarPos != null && in.carPos().distanceTo(lastCarPos) > TELEPORT_UU) {
			snap();
		}
		lastCarPos = in.carPos();
		boolean snapped = first;

		boolean wantBall = ballCamOn && in.ball() != null;
		boolean switched = first || wantBall != ballActive;
		if (switched) {
			if (wantBall) {
				ball.reset();
			} else {
				car.reset();
			}
			ballActive = wantBall;
		}
		Rot sw = swivel.update(in.velocity().length(), s, dt);
		Pov fresh = ballActive ? ball.update(in, s, dt) : car.update(in, s, swivel.freeLooking(), dt);
		if (switched && lastOutput != null) {
			blend.start(lastOutput, fresh, CamBlend.blendTime(s.transitionSpeed()));
		}
		Pov out = blend.apply(fresh, dt);
		lastOutput = out;
		Pov pov = new Pov(out.focus(), out.rot().plus(sw).normalized(), out.distance(), out.fov());
		ShakePlayer.Sample sh = shakes.sample(now);
		if (!shakeOn) {
			sh = ShakePlayer.Sample.ZERO;
		}
		first = false;
		last = new Frame(pov, pov.location(), sh.rot(), sh.loc(), pov.fov() + sh.fov(), snapped);
		return last;
	}

	private void snap() {
		car.reset();
		ball.reset();
		blend.reset();
		first = true;
		ballActive = false;
		lastOutput = null;
	}
}
