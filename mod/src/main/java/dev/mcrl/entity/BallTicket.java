package dev.mcrl.entity;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import dev.mcrl.Mcrl;

public final class BallTicket {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "ball");
	public static final TicketType TYPE = new TicketType(TicketType.NO_TIMEOUT,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
	public static final int RADIUS = 2;

	public static void register() {
		Registry.register(BuiltInRegistries.TICKET_TYPE, ID, TYPE);
	}

	public interface Chunks {
		void addTicket(int cx, int cz);

		void removeTicket(int cx, int cz);

		boolean entitiesAccessible(int cx, int cz);
	}

	public static Chunks of(ServerLevel level) {
		return chunks(level, TYPE);
	}

	static Chunks chunks(ServerLevel level, TicketType type) {
		ServerChunkCache cache = level.getChunkSource();
		return new Chunks() {
			@Override
			public void addTicket(int cx, int cz) {
				cache.addTicketWithRadius(type, new ChunkPos(cx, cz), RADIUS);
			}

			@Override
			public void removeTicket(int cx, int cz) {
				cache.removeTicketWithRadius(type, new ChunkPos(cx, cz), RADIUS);
			}

			@Override
			public boolean entitiesAccessible(int cx, int cz) {
				return cache.getChunkNow(cx, cz) != null && level.areEntitiesLoaded(ChunkPos.pack(cx, cz));
			}
		};
	}

	private boolean holding, waiting;
	private int homeX, homeZ, aheadX, aheadZ;

	public boolean holding() {
		return holding;
	}

	public void start(Chunks c, int cx, int cz) {
		release(c);
		c.addTicket(cx, cz);
		holding = true;
		homeX = cx;
		homeZ = cz;
	}

	public boolean follow(Chunks c, int cx, int cz) {
		if (!holding) {
			return true;
		}
		if (cx == homeX && cz == homeZ) {
			dropAhead(c);
			return true;
		}
		boolean ahead = waiting && cx == aheadX && cz == aheadZ;
		if (c.entitiesAccessible(cx, cz)) {
			if (ahead) {
				waiting = false;
			} else {
				dropAhead(c);
				c.addTicket(cx, cz);
			}
			c.removeTicket(homeX, homeZ);
			homeX = cx;
			homeZ = cz;
			return true;
		}
		if (!ahead) {
			dropAhead(c);
			c.addTicket(cx, cz);
			waiting = true;
			aheadX = cx;
			aheadZ = cz;
		}
		return false;
	}

	public void release(Chunks c) {
		dropAhead(c);
		if (holding) {
			holding = false;
			c.removeTicket(homeX, homeZ);
		}
	}

	private void dropAhead(Chunks c) {
		if (waiting) {
			waiting = false;
			c.removeTicket(aheadX, aheadZ);
		}
	}
}
