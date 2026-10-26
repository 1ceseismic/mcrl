package dev.mcrl.client.camera.shake;

public record Osc(double amp, double freq, boolean randomOffset) {
	public static final Osc NONE = new Osc(0, 0, true);
}
