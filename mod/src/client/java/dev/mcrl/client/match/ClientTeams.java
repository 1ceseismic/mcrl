package dev.mcrl.client.match;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.Level;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.hud.MeterTeam;
import dev.mcrl.entity.CarEntity;

public final class ClientTeams {
	private ClientTeams() {
	}

	public static McrlConfig.Team of(CarEntity car) {
		return car.team() == 1 ? McrlConfig.Team.ORANGE : McrlConfig.Team.BLUE;
	}

	public static McrlConfig.Team ofCar(@Nullable Level level, int id) {
		return level != null && level.getEntity(id) instanceof CarEntity c ? of(c) : McrlConfig.get().team;
	}

	public static MeterTeam meter(McrlConfig.Team team) {
		return team == McrlConfig.Team.ORANGE ? MeterTeam.ORANGE : MeterTeam.BLUE;
	}
}
