package dev.mcrl.client.link;

import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.mcrl.net.InputHistory;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.net.NetProtocol;
import dev.mcrl.net.Snapshot;
import dev.mcrl.sim.InputFrame;

final class InputPacer {
	private LeadControl lead = new LeadControl();
	private TickClock clock = new TickClock();
	private InputHistory history = new InputHistory();
	private final TickInputs tickInputs = new TickInputs();
	private int car = -1;

	public void onSnapshot(Snapshot s, long recvNanos, int rttMs) {
		lead.onSnapshot(s, recvNanos, rttMs);
	}

	public List<InputC2S> frame(long nowNanos, @Nullable InputFrame f, int carId) {
		if (!lead.hasSnapshot()) {
			return List.of();
		}
		if (carId != car) {
			car = carId;
			tickInputs.reset();
			if (carId >= 0) {
				lead.requestAnchor();
			}
		}
		int n = clock.advance(nowNanos, lead.clockRate(nowNanos, clock.position()));
		if (lead.needsAnchor(nowNanos, clock.position())) {
			clock.anchor(lead.anchorPosition(nowNanos));
			n = 1;
		}
		if (carId < 0 || f == null) {
			return List.of();
		}
		tickInputs.see(f);
		long newest = clock.tick();
		for (int i = n - 1; i >= 0; i--) {
			history.put(newest - i, tickInputs.next(f));
		}
		if (n > 0) {
			lead.onPayload(n);
		}
		return history.takeUnsent(NetProtocol.MAX_INPUT_TICKS, lead.epoch());
	}

	public int leadTicks(long nowNanos) {
		return lead.hasSnapshot() ? (int) Math.round(clock.position() - lead.serverTickEstimate(nowNanos)) : 0;
	}

	public LeadControl lead() {
		return lead;
	}

	public TickClock clock() {
		return clock;
	}

	public InputHistory history() {
		return history;
	}

	public void reset() {
		lead = new LeadControl();
		clock = new TickClock();
		history = new InputHistory();
		tickInputs.reset();
		car = -1;
	}
}
