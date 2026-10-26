package dev.mcrl.client.fx;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

public enum FxState {
	SPAWNED("Spawned"),
	IN_AIR("InAir"),
	BOOST("Boost"),
	ACTIVE("Active"),
	THROTTLING("Throttling"),
	BOOST_SHAKE("BoostShake"),
	SUPERSONIC("SuperSonic"),
	SUPERSONIC_END("SuperSonicEnd"),
	TEAM0("Team0"),
	TEAM1("Team1"),
	PAINTED("Painted"),
	HIDE_WORLD_UI("HideWorldUI"),
	MY_TEAM_EVENT("MyTeamEvent"),
	OPPOSING_TEAM_EVENT("OpposingTeamEvent"),
	JUMP("Jump"),
	DOUBLE_JUMP("DoubleJump"),
	DODGE("Dodge"),
	PICKED_UP("PickedUp");

	private static final Map<String, FxState> BY_RL_NAME = new HashMap<>();

	static {
		for (FxState s : values()) {
			BY_RL_NAME.put(s.rlName, s);
		}
	}

	private final String rlName;

	FxState(String rlName) {
		this.rlName = rlName;
	}

	public String rlName() {
		return rlName;
	}

	public static @Nullable FxState byRlName(String rlName) {
		return BY_RL_NAME.get(rlName);
	}
}
