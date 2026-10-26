package dev.mcrl.client.input;

import net.minecraft.client.KeyMapping;

public final class KeyboardSource {
	public record State(boolean throttle, boolean reverse, boolean steerLeft, boolean steerRight, boolean jump,
			boolean boost, boolean powerslide, boolean rollLeft, boolean rollRight, boolean exitPressed,
			boolean resetPressed, boolean ballCamPressed, boolean ballCamHeld) {
	}

	// MC turns key repeat into clicks: an edge needs the held state
	private static final class PressEdge {
		private boolean wasDown;

		boolean update(boolean clicked, boolean down) {
			boolean edge = (clicked || down) && !wasDown;
			wasDown = down;
			return edge;
		}
	}

	private static final PressEdge EXIT = new PressEdge();
	private static final PressEdge RESET = new PressEdge();
	private static final PressEdge BALL_CAM = new PressEdge();

	private KeyboardSource() {
	}

	public static State poll() {
		boolean ballCamClicked = drain(McrlKeys.BALL_CAM), ballCamDown = McrlKeys.BALL_CAM.isDown();
		return new State(held(McrlKeys.THROTTLE), held(McrlKeys.REVERSE), held(McrlKeys.STEER_LEFT),
				held(McrlKeys.STEER_RIGHT), held(McrlKeys.JUMP), held(McrlKeys.BOOST), held(McrlKeys.POWERSLIDE),
				held(McrlKeys.AIR_ROLL_LEFT), held(McrlKeys.AIR_ROLL_RIGHT), edge(EXIT, McrlKeys.EXIT_CAR),
				edge(RESET, McrlKeys.RESET_CAR), BALL_CAM.update(ballCamClicked, ballCamDown),
				ballCamClicked || ballCamDown);
	}

	private static boolean held(KeyMapping k) {
		return drain(k) | k.isDown();
	}

	private static boolean edge(PressEdge e, KeyMapping k) {
		return e.update(drain(k), k.isDown());
	}

	private static boolean drain(KeyMapping k) {
		boolean any = false;
		while (k.consumeClick()) {
			any = true;
		}
		return any;
	}
}
