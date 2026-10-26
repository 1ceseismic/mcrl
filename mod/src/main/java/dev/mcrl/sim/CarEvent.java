package dev.mcrl.sim;

import dev.mcrl.math.Pose;

public sealed interface CarEvent {
	int carId();

	Pose pose();

	long tick();

	record Demolished(int carId, int attackerId, Pose pose, long tick) implements CarEvent {
	}

	record Respawned(int carId, Pose pose, long tick) implements CarEvent {
	}
}
