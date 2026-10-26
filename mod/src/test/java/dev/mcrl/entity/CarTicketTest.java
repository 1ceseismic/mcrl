package dev.mcrl.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.mcrl.sim.SyntheticTerrain;

class CarTicketTest {
	@BeforeAll
	static void boot() {
		SyntheticTerrain.boot();
	}

	private static final class FakeChunks implements BallTicket.Chunks {
		final Set<String> accessible = new HashSet<>();
		final List<String> log = new ArrayList<>();

		@Override
		public void addTicket(int cx, int cz) {
			log.add("add " + cx + "," + cz);
		}

		@Override
		public void removeTicket(int cx, int cz) {
			log.add("remove " + cx + "," + cz);
		}

		@Override
		public boolean entitiesAccessible(int cx, int cz) {
			return accessible.contains(cx + "," + cz);
		}
	}

	@Test
	void a_far_respawn_waits_for_its_chunk_with_a_renewed_ticket() {
		FakeChunks c = new FakeChunks();
		assertFalse(CarTicket.follow(c, 0, 0, 40, -3), "the entity stays: there it would unload");
		assertFalse(CarTicket.follow(c, 0, 0, 40, -3));
		assertEquals(List.of("add 40,-3", "add 40,-3"), c.log, "renewed every tick it waits (a timed ticket)");

		c.accessible.add("40,-3");
		assertTrue(CarTicket.follow(c, 0, 0, 40, -3), "now it moves");
		assertEquals(2, c.log.size(), "no removal: the ticket times out by itself");
	}
}
