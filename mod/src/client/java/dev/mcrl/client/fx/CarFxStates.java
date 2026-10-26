package dev.mcrl.client.fx;

import java.util.List;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.sim.CarFx;

public final class CarFxStates {
	private CarFxStates() {
	}

	public static void apply(FxActor car, CarSignals s, McrlConfig.Team team, double now) {
		CarFx fx = s.fx();
		List<FxEvent> events = s.events();
		car.setState(FxState.SPAWNED, true, now);
		car.setState(FxState.IN_AIR, !s.onGround(), now);
		car.setState(FxState.SUPERSONIC, s.supersonic(), now);
		car.setState(FxState.BOOST, s.boosting(), now);
		car.setState(FxState.THROTTLING, s.throttle() > 0, now);
		car.setState(FxState.JUMP, fx.jumping() || has(events, FxEvent.Jumped.class), now);
		car.setState(FxState.DOUBLE_JUMP, fx.doubleJumping() || has(events, FxEvent.DoubleJumped.class), now);
		car.setState(FxState.DODGE, fx.flipping() || has(events, FxEvent.Dodged.class), now);
		car.setState(FxState.TEAM0, team == McrlConfig.Team.BLUE, now);
		car.setState(FxState.TEAM1, team == McrlConfig.Team.ORANGE, now);
	}

	private static boolean has(List<FxEvent> events, Class<? extends FxEvent> kind) {
		for (FxEvent e : events) {
			if (kind.isInstance(e)) {
				return true;
			}
		}
		return false;
	}
}
