package dev.mcrl.client.sound;

public final class SlideRule {
	public static final double DELAY = 0.15;
	public static final double MOMENTUM_MIN = 200;

	public enum Change { NONE, START, STOP }

	private double time;
	private boolean playing;

	public static boolean sliding(boolean bodyContact, double slideSpeed) {
		return bodyContact && slideSpeed > MOMENTUM_MIN;
	}

	public Change step(double dt, boolean sliding) {
		if (sliding) {
			if (playing) {
				time = DELAY;
				return Change.NONE;
			}
			time += dt;
			if (time >= DELAY - 1e-9) {
				time = DELAY;
				playing = true;
				return Change.START;
			}
			return Change.NONE;
		}
		if (playing) {
			time -= dt;
			if (time <= 1e-9) {
				time = 0;
				playing = false;
				return Change.STOP;
			}
		}
		return Change.NONE;
	}

	public boolean playing() {
		return playing;
	}
}
