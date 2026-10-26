package dev.mcrl.client.link;

import org.jspecify.annotations.Nullable;

import dev.mcrl.sim.Controls;
import dev.mcrl.sim.InputFrame;

final class TickInputs {
	private @Nullable InputFrame seen;
	private int pendingEdges;

	public void see(InputFrame f) {
		if (f != seen) {
			seen = f;
			pendingEdges |= f.edges();
		}
	}

	public Controls next(InputFrame latest) {
		see(latest);
		Controls c = latest.withEdges(pendingEdges).toControls(true);
		pendingEdges = 0;
		return c;
	}

	public void reset() {
		seen = null;
		pendingEdges = 0;
	}
}
