package dev.mcrl.client.camera.shake;

import dev.mcrl.client.input.GamepadSource;

public final class RumbleOut {
	private static final int MIN_CHANGE = 655;
	private static final double REFRESH = 0.05;
	private static final int SEND_MILLIS = 100;

	private final int[] last = new int[2];
	private double lastTime = Double.NEGATIVE_INFINITY;

	public void update(int[] motors, boolean allowed, double now) {
		int low = allowed ? motors[0] : 0, high = allowed ? motors[1] : 0;
		if (low == 0 && high == 0) {
			if (last[0] != 0 || last[1] != 0) {
				send(0, 0, 0, now);
			}
			return;
		}
		if (Math.abs(low - last[0]) >= MIN_CHANGE || Math.abs(high - last[1]) >= MIN_CHANGE
				|| now - lastTime >= REFRESH) {
			send(low, high, SEND_MILLIS, now);
		}
	}

	private void send(int low, int high, int millis, double now) {
		GamepadSource.rumble(low, high, millis);
		last[0] = low;
		last[1] = high;
		lastTime = now;
	}
}
