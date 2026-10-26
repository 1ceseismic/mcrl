package dev.mcrl.client.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.Controls;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.SimClock.Mode;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimTestHooks;
import dev.mcrl.sim.SyntheticTerrain;

class TickInputsTest {
	@BeforeAll
	static void boot() {
		SyntheticTerrain.boot();
	}

	@Test
	void matchesSimHostSlot() throws Exception {
		InputFrame jumpEdge = InputFrame.of(1, 0.5f, 0, 0, 0, 0, 0, InputFrame.JUMP);
		InputFrame boostHeld = InputFrame.of(2, 1f, -0.25f, 0, 0, 0, InputFrame.BOOST, InputFrame.BOOST);
		InputFrame plain = InputFrame.of(3, 1f, 0, 0, 0, 0, 0, 0);

		TickInputs ti = new TickInputs();
		List<Controls> client = new ArrayList<>();
		ti.see(jumpEdge);
		client.add(ti.next(boostHeld));
		client.add(ti.next(boostHeld));
		client.add(ti.next(plain));

		List<Controls> host = new CopyOnWriteArrayList<>();
		try (SimHost h = SimHost.start("t-tickinputs", 0, 0, 0, -64, Mode.LOCKSTEP)) {
			h.addCar(1, CarState.of(Pose.upright(8.5, 65, 8.5, 0), 0, 0, 0, 0, 0, 0), 0, 0);
			SimTestHooks.feedAll(h, new SyntheticTerrain());
			h.flush();
			h.submitInput(1, jumpEdge);
			h.submitInput(1, boostHeld);
			for (int i = 0; i < 2; i++) {
				h.stepLockstep(1);
				host.add(h.latest().car(1).applied());
			}
			h.submitInput(1, plain);
			h.stepLockstep(1);
			host.add(h.latest().car(1).applied());
		}
		assertEquals(host, client);
		assertEquals(List.of(true, false, false), client.stream().map(Controls::jump).toList(), "jump on one tick");
		assertEquals(List.of(true, true, false), client.stream().map(Controls::boost).toList());
	}
}
