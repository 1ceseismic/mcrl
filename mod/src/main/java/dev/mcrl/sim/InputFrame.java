package dev.mcrl.sim;

public record InputFrame(int seq, byte throttle, byte steer, byte pitch, byte yaw, byte roll, int buttons, int edges) {
	public static final int JUMP = 1;
	public static final int BOOST = 2;
	public static final int HANDBRAKE = 4;

	public static final InputFrame NEUTRAL = new InputFrame(0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, 0, 0);

	public static byte quantise(float v) {
		if (Float.isNaN(v)) {
			return 0;
		}
		return (byte) Math.round(Math.max(-1f, Math.min(1f, v)) * 127f);
	}

	public static float axis(byte b) {
		return Math.max(-1f, b / 127f);
	}

	public static InputFrame of(int seq, float throttle, float steer, float pitch, float yaw, float roll, int buttons,
			int edges) {
		return new InputFrame(seq, quantise(throttle), quantise(steer), quantise(pitch), quantise(yaw), quantise(roll),
				buttons, edges);
	}

	public InputFrame withEdges(int edges) {
		return new InputFrame(seq, throttle, steer, pitch, yaw, roll, buttons, edges);
	}

	public Controls toControls(boolean consumeEdges) {
		int b = consumeEdges ? (buttons | edges) : buttons;
		return new Controls(axis(throttle), axis(steer), axis(pitch), axis(yaw), axis(roll),
				(b & JUMP) != 0, (b & BOOST) != 0, (b & HANDBRAKE) != 0);
	}
}
