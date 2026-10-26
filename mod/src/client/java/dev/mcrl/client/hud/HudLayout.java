package dev.mcrl.client.hud;

import static dev.mcrl.client.hud.MeterStyle.BALL_CAM_X;
import static dev.mcrl.client.hud.MeterStyle.BALL_CAM_Y;
import static dev.mcrl.client.hud.MeterStyle.METER_INSET;
import static dev.mcrl.client.hud.MeterStyle.STAGE_HEIGHT;
import static dev.mcrl.client.hud.MeterStyle.STAGE_WIDTH;

final class HudLayout {
	private HudLayout() {
	}

	static double contentScale(int w, int h) {
		return (double) w / h >= STAGE_WIDTH / STAGE_HEIGHT ? h / STAGE_HEIGHT : w / STAGE_WIDTH;
	}

	static double[] meterCentre(int w, int h) {
		double f = contentScale(w, h);
		return new double[] { w - METER_INSET * f, h - METER_INSET * f };
	}

	static double[] ballCamIndicator(int w, int h) {
		double f = contentScale(w, h);
		return new double[] { BALL_CAM_X * f, h - (STAGE_HEIGHT - BALL_CAM_Y) * f };
	}
}
