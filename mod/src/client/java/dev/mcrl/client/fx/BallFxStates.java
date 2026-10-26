package dev.mcrl.client.fx;

public final class BallFxStates {
	private BallFxStates() {
	}

	public static void apply(FxActor ball, BallSignals s, double now) {
		ball.setState(FxState.SPAWNED, true, now);
		ball.setState(FxState.IN_AIR, !s.worldContact(), now);
	}
}
