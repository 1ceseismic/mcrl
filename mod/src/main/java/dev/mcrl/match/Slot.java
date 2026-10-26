package dev.mcrl.match;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record Slot(int index, int team, @Nullable UUID player, int carEntityId, boolean bot) {
	public static Slot empty(int index, int team) {
		return new Slot(index, team, null, -1, false);
	}

	public boolean open() {
		return player == null;
	}

	Slot withPlayer(UUID p, int carId) {
		return new Slot(index, team, p, carId, false);
	}

	Slot withBot(int carId) {
		return new Slot(index, team, null, carId, true);
	}

	Slot cleared() {
		return empty(index, team);
	}
}
