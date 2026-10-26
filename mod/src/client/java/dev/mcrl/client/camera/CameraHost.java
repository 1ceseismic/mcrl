package dev.mcrl.client.camera;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.camera.CamSpace.View;
import dev.mcrl.client.camera.CameraSettings.BallCamMode;
import dev.mcrl.client.camera.shake.RumbleOut;
import dev.mcrl.client.camera.shake.ShakeTriggers;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.effects.Demolition;
import dev.mcrl.client.input.PadState;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.link.LocalSimLink;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.SimFrame;

public final class CameraHost {
	private static final RlCamera CAMERA = new RlCamera();
	private static final ShakeTriggers SHAKES = new ShakeTriggers();
	private static final RumbleOut RUMBLE = new RumbleOut();
	private static final int[] STILL = { 0, 0 };
	private static final FrameClock CLOCK = new FrameClock();
	private static boolean riding, padNorthWas, ballCamWanted;
	private static @Nullable View view;
	private static double frameDt;
	private static @Nullable View zoomed;
	private static double zoomDistance = Double.NaN;
	private static @Nullable Vec3 lastLocation;

	private CameraHost() {
	}

	public static void register() {
		FrameEvents.register(CameraHost::frame);
		Demolition.LISTENERS.add(pose -> explosion(new Vec3(pose.x(), pose.y(), pose.z())));
	}

	public static void reset() {
		CAMERA.reset();
		SHAKES.reset();
		ballCamWanted = false;
		zoomDistance = Double.NaN;
		riding = false;
		padNorthWas = false;
		view = null;
		zoomed = null;
		lastLocation = null;
	}

	private static @Nullable CarEntity ridden(Minecraft mc) {
		LocalPlayer p = mc.player;
		return p != null && p.getVehicle() instanceof CarEntity car ? car : null;
	}

	public static boolean chasing(Minecraft mc) {
		return view(mc) != null;
	}

	public static @Nullable View view(Minecraft mc) {
		return ridden(mc) != null ? view : null;
	}

	public static float verticalFov() {
		View v = view;
		return v != null ? v.vFov() : CamSpace.verticalFov(McrlConfig.get().camera.fov());
	}

	public static void controls(PadState pad, boolean ballCamPressed, boolean ballCamHeld, float deadzone,
			boolean active) {
		float x = Math.abs(pad.rsX()) < deadzone ? 0 : pad.rsX();
		float y = Math.abs(pad.rsY()) < deadzone ? 0 : pad.rsY();
		CAMERA.stick(active ? x : 0, active ? -y : 0);
		boolean north = pad.north();
		boolean pressed = active && (ballCamPressed || north && !padNorthWas);
		boolean held = active && (ballCamHeld || north);
		padNorthWas = north;
		ballCamWanted = McrlConfig.get().camera.ballCamMode() == BallCamMode.HOLD ? held : pressed != ballCamWanted;
		CAMERA.ballCam(ballCamWanted);
	}

	public static double zoom(View v, double clipped) {
		if (v != zoomed) {
			zoomed = v;
			double ease = 1 - Math.exp(-RlCamConstants.ZOOM_OUT_RATE * frameDt);
			zoomDistance = Double.isNaN(zoomDistance) ? clipped
					: Math.min(clipped, zoomDistance + (v.distance() - zoomDistance) * ease);
		}
		return Math.min(zoomDistance, clipped);
	}

	public static void mouse(double dx, double dy) {
		CAMERA.mouse(dx, dy);
	}

	public static boolean ballCamOn() {
		return CAMERA.ballCamOn();
	}

	private static void explosion(Vec3 mcPos) {
		Vec3 cam = lastLocation;
		if (cam != null) {
			ShakeTriggers.explosion(CamSpace.rlPos(mcPos.x, mcPos.y, mcPos.z).distanceTo(cam), CAMERA.shakes(),
					CLOCK.now());
		}
	}

	private static void frame(Minecraft mc, SimFrame f, long frameNanos) {
		try {
			runFrame(mc, frameNanos);
		} catch (RuntimeException e) {
			view = null;
			lastLocation = null;
			RUMBLE.update(STILL, false, CLOCK.now());
			throw e;
		}
	}

	private static void runFrame(Minecraft mc, long frameNanos) {
		CarEntity car = ridden(mc);
		FrameSignals signals = FrameSignals.current();
		CarSignals s = car == null ? null : signals.car(car.carId());
		double dt = CLOCK.advance(frameNanos), now = CLOCK.now();
		if (s == null) {
			if (riding) {
				reset();
			}
			view = null;
			RUMBLE.update(STILL, false, now);
			return;
		}
		if (!riding) {
			CAMERA.reset();
			ballCamWanted = false;
			SHAKES.reset();
			riding = true;
		}
		McrlConfig cfg = McrlConfig.get();
		CAMERA.mouseLook(cfg.cameraMouseLook);
		CAMERA.shake(cfg.cameraShake);
		SHAKES.frame(s, CAMERA.shakes(), now);
		RlCamera.Frame fr = CAMERA.update(CamSpace.input(s, signals.ball()), cfg.camera, now, dt);
		frameDt = dt;
		if (fr != null && fr.snapped()) {
			zoomDistance = Double.NaN;
		}
		Pose p = s.pose();
		view = fr == null ? null : CamSpace.view(fr, new Vec3(p.x(), p.y(), p.z()));
		lastLocation = fr == null ? null : fr.location();
		boolean allowed = cfg.rumble && s.local() && !LocalSimLink.pausing(mc) && mc.isWindowActive();
		RUMBLE.update(CAMERA.shakes().motors(now), allowed, now);
	}
}
