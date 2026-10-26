package dev.mcrl.client.input;

import dev.mcrl.sim.InputFrame;

public final class InputMapper {
	// RocketSim: pitch +1 is nose up
	private static final int W_PITCH_SIGN = -1;

	public record MapperSettings(float stickDeadzone, float triggerDeadzone, float sensitivity) {
		public static final MapperSettings DEFAULT = new MapperSettings(0.10f, 0.05f, 1.0f);

		public MapperSettings {
			if (!(stickDeadzone >= 0f && stickDeadzone < 1f) || !(triggerDeadzone >= 0f && triggerDeadzone < 1f)
					|| !(sensitivity > 0f && Float.isFinite(sensitivity))) {
				throw new IllegalArgumentException("bad mapper settings: deadzones must be in [0, 1), sensitivity > 0: "
						+ stickDeadzone + ", " + triggerDeadzone + ", " + sensitivity);
			}
		}
	}

	private int prevButtons;
	private int masked;
	private boolean afterNeutral;

	public InputFrame map(RawInput r, int seq, MapperSettings s) {
		if (r.screenOpen() || !r.driving()) {
			prevButtons = 0;
			afterNeutral = true;
			return new InputFrame(seq, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, 0, 0);
		}
		float keyThrottle = r.throttleKey() - r.reverseKey();
		float triggers = deadzone(r.rt(), s.triggerDeadzone()) - deadzone(r.lt(), s.triggerDeadzone());
		float throttle = maxMagnitude(keyThrottle, triggers);

		float[] stick = radial(r.lsX(), r.lsY(), s.stickDeadzone(), s.sensitivity());
		float steerAxis = maxMagnitude(r.steerRight() - r.steerLeft(), stick[0]);
		float noseDown = maxMagnitude(keyThrottle, -stick[1]);
		float pitch = W_PITCH_SIGN * noseDown;

		int held = (r.jump() ? InputFrame.JUMP : 0) | (r.boost() ? InputFrame.BOOST : 0)
				| (r.powerslide() ? InputFrame.HANDBRAKE : 0);
		if (afterNeutral) {
			masked = held;
			afterNeutral = false;
		}
		masked &= held;
		int buttons = held & ~masked;
		int edges = buttons & ~prevButtons;
		prevButtons = buttons;

		float yaw, roll;
		if ((buttons & InputFrame.HANDBRAKE) != 0) {
			yaw = 0f;
			roll = steerAxis;
		} else {
			yaw = steerAxis;
			roll = (r.rollRight() ? 1f : 0f) - (r.rollLeft() ? 1f : 0f);
		}
		return InputFrame.of(seq, throttle, steerAxis, pitch, yaw, roll, buttons, edges);
	}

	private static float maxMagnitude(float a, float b) {
		return Math.abs(b) > Math.abs(a) ? b : a;
	}

	private static float deadzone(float v, float dz) {
		float a = Math.max(0f, Math.min(1f, v));
		return a < dz ? 0f : (a - dz) / (1f - dz);
	}

	private static float[] radial(float x, float y, float dz, float sensitivity) {
		double m = Math.hypot(x, y);
		if (!(m >= dz) || m == 0) {
			return new float[] {0f, 0f};
		}
		double scaled = (Math.min(m, 1.0) - dz) / (1.0 - dz);
		double k = scaled / m * sensitivity;
		return new float[] {clamp((float) (x * k)), clamp((float) (y * k))};
	}

	private static float clamp(float v) {
		return Math.max(-1f, Math.min(1f, v));
	}
}
