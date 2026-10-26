package dev.mcrl.client.hud;

import static dev.mcrl.client.hud.MeterStyle.STEP_UP_FADE;
import static dev.mcrl.client.hud.MeterStyle.STEP_UP_SHOW;

final class StepUpBadge {
	private boolean last, on;
	private double shownAt = Double.NEGATIVE_INFINITY, now;

	void update(boolean stepUp, boolean newRide, double now) {
		this.now = now;
		if (newRide) {
			shownAt = Double.NEGATIVE_INFINITY;
		} else if (stepUp != last) {
			on = stepUp;
			shownAt = now;
		}
		last = stepUp;
	}

	boolean visible() {
		return now - shownAt < STEP_UP_SHOW;
	}

	float alpha() {
		double left = STEP_UP_SHOW - (now - shownAt);
		return left <= 0 ? 0 : (float) Math.min(1, left / STEP_UP_FADE);
	}

	boolean on() {
		return on;
	}
}
