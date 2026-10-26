package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import dev.mcrl.math.Pose;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.Controls;
import dev.mcrl.sim.SimClock.Mode;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimTestHooks;
import dev.mcrl.sim.SyntheticTerrain;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InputMailboxTest {
	private static final UUID P = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final Controls A = new Controls(1f, 0.5f, 0, 0, 0, false, true, false);
	private static final Controls B = new Controls(-1f, -0.5f, 0, 0, 0, true, false, false);
	private static final Controls C = new Controls(0.25f, 0, 1f, 0, 0, false, false, true);

	@BeforeAll
	static void boot() {
		SyntheticTerrain.boot();
	}

	private static final class Seen {
		final Map<Integer, List<Controls>> byCar = new ConcurrentHashMap<>();

		void step(SimHost h, int n) {
			for (int i = 0; i < n; i++) {
				h.stepLockstep(1);
				for (SimFrame.CarSnap c : h.latest().cars()) {
					byCar.computeIfAbsent(c.carId(), k -> new ArrayList<>()).add(c.applied());
				}
			}
		}

		List<Controls> take(int carId) {
			List<Controls> l = byCar.getOrDefault(carId, List.of());
			byCar.remove(carId);
			return l;
		}
	}

	private static SimHost host(String name) {
		SimHost h = SimHost.start(name, 0, 0, 0, -64, Mode.LOCKSTEP);
		h.addCar(7, CarState.of(Pose.upright(8.5, 65, 8.5, 0), 0, 0, 0, 0, 0, 0), 0, 0);
		h.addCar(9, CarState.of(Pose.upright(20.5, 65, 8.5, 0), 0, 0, 0, 0, 0, 0), 0, 0);
		try {
			SimTestHooks.feedAll(h, new SyntheticTerrain());
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		h.flush();
		return h;
	}

	private static InputC2S input(long firstTick, Controls c, int n) {
		Controls[] t = new Controls[n];
		java.util.Arrays.fill(t, c);
		return new InputC2S(firstTick, 0, t);
	}

	@Test
	void unboundPlayerIsDropped() {
		Seen seen = new Seen();
		ResourceKey<Level> dim = Level.OVERWORLD;
		try (SimHost h = host("t-mail-unbound")) {
			InputMailbox m = new InputMailbox(d -> d == dim ? h : null);
			m.receive(P, input(h.latest().tick() + 1, A, 4));
			seen.step(h, 4);
			assertEquals(List.of(Controls.NEUTRAL, Controls.NEUTRAL, Controls.NEUTRAL, Controls.NEUTRAL), seen.take(7));
			assertEquals(List.of(Controls.NEUTRAL, Controls.NEUTRAL, Controls.NEUTRAL, Controls.NEUTRAL), seen.take(9));
			assertEquals(SimHost.InputStats.NONE, h.inputStats(7));
			assertEquals(-1, m.boundCar(P));

			m.bind(P, Level.NETHER, 7);
			m.receive(P, input(h.latest().tick() + 1, A, 4));
			seen.step(h, 4);
			assertFalse(seen.take(7).contains(A), "no host for the bound dimension: dropped");
		}
	}

	@Test
	void rebindingTargetsNewCarOnly() {
		Seen seen = new Seen();
		ResourceKey<Level> dim = Level.OVERWORLD;
		try (SimHost h = host("t-mail-rebind")) {
			InputMailbox m = new InputMailbox(d -> d == dim ? h : null);
			m.bind(P, dim, 7);
			assertEquals(7, m.boundCar(P));
			m.receive(P, input(h.latest().tick() + 1, A, 4));
			seen.step(h, 4);
			assertEquals(List.of(A, A, A, A), seen.take(7), "car 7 fills");
			assertEquals(List.of(Controls.NEUTRAL, Controls.NEUTRAL, Controls.NEUTRAL, Controls.NEUTRAL), seen.take(9));
			assertEquals(h.latest().tick(), h.inputStats(7).ackTick());

			m.bind(P, dim, 9);
			m.receive(P, input(h.latest().tick() + 1, B, 4));
			seen.step(h, 4);
			assertEquals(List.of(B, B, B, B), seen.take(9), "car 9 only");
			List<Controls> seven = seen.take(7);
			assertFalse(seven.contains(B), "car 7 never gets the new car's input: " + seven);
			assertEquals(List.of(A, A, A, A), seven, "car 7 holds its own last input (no new ticks)");
			long ack7 = h.inputStats(7).ackTick();

			m.unbind(P);
			assertEquals(-1, m.boundCar(P));
			m.receive(P, input(h.latest().tick() + 1, C, 4));
			seen.step(h, 4);
			assertFalse(seen.take(9).contains(C), "unbound: nothing");
			assertFalse(seen.take(7).contains(C), "unbound: nothing");
			assertEquals(ack7, h.inputStats(7).ackTick());
			assertTrue(h.inputStats(9).ackTick() < h.latest().tick(), "car 9 got no new input tick");
		}
	}
}
