package dev.mcrl.match;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import dev.mcrl.Mcrl;

public final class StadiumTicket {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "stadium");
	public static final long TIMEOUT_TICKS = 40;
	public static final int RADIUS = 10;
	public static final TicketType TYPE = new TicketType(TIMEOUT_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);

	private StadiumTicket() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.TICKET_TYPE, ID, TYPE);
	}

	public static void renew(ServerLevel stadium) {
		stadium.getChunkSource().addTicketWithRadius(TYPE, new ChunkPos(0, 0), RADIUS);
	}

	public static void loadField(ServerLevel stadium) {
		renew(stadium);
		for (int cx = StadiumConst.FIELD_CHUNK_MIN_X; cx <= StadiumConst.FIELD_CHUNK_MAX_X; cx++) {
			for (int cz = StadiumConst.FIELD_CHUNK_MIN_Z; cz <= StadiumConst.FIELD_CHUNK_MAX_Z; cz++) {
				stadium.getChunk(cx, cz);
			}
		}
	}
}
