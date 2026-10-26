package dev.mcrl.client.camera;

public final class FrameClock {
	private boolean started;
	private long lastNanos;
	private double time;

	public double advance(long frameNanos) {
		double dt = started ? Math.max(0, Math.min(RlCamConstants.MAX_DT, (frameNanos - lastNanos) * 1e-9)) : 0;
		started = true;
		lastNanos = frameNanos;
		time += dt;
		return dt;
	}

	public double now() {
		return time;
	}
}
