package dev.mcrl.entity;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;

import dev.mcrl.Mcrl;

public final class CarTicket {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "car");
	public static final long TIMEOUT_TICKS = 40;
	public static final TicketType TYPE = new TicketType(TIMEOUT_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);

	private CarTicket() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.TICKET_TYPE, ID, TYPE);
	}

	public static BallTicket.Chunks of(ServerLevel level) {
		return BallTicket.chunks(level, TYPE);
	}

	public static boolean follow(BallTicket.Chunks c, int fromX, int fromZ, int cx, int cz) {
		if ((cx == fromX && cz == fromZ) || c.entitiesAccessible(cx, cz)) {
			return true;
		}
		c.addTicket(cx, cz);
		return false;
	}
}
