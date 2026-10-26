package dev.mcrl.client.link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.mcrl.natives.Layouts;
import dev.mcrl.net.ArenaBlock;
import dev.mcrl.net.InputHistory;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.net.NetProtocol;
import dev.mcrl.net.Snapshot;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.PauseSource;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;

// Render thread only.
public final class Prediction {
	private final SimHost host;
	private final LeadControl lead;
	private final InputHistory history;
	private @Nullable Snapshot last;
	private @Nullable Snapshot pending;
	private boolean pendingAnchor;
	private final Map<Integer, Long> pendingMentions = new HashMap<>();
	private long posted = Long.MIN_VALUE;
	private boolean anyPosted, ready;
	private int ownCar = -1;
	public static final long FRAME_WAIT_NANOS = 3_000_000L;
	private final long awaitNanos;
	private long corrections, anchors;
	private List<SimHost.Correction> taken = new ArrayList<>();

	public Prediction(SimHost host, LeadControl lead, InputHistory history, long awaitNanos) {
		if (host.role() != SimHost.Role.PREDICTION) {
			throw new IllegalArgumentException("a " + host.role() + " host does not predict");
		}
		this.host = host;
		this.lead = lead;
		this.history = history;
		this.awaitNanos = awaitNanos;
	}

	public SimHost host() {
		return host;
	}

	public LeadControl lead() {
		return lead;
	}

	public InputHistory history() {
		return history;
	}

	public void onSnapshot(Snapshot s, long recvNanos, int rttMs) {
		lead.onSnapshot(s, recvNanos, rttMs);
		if (!blobsUsable(s)) {
			return;
		}
		Snapshot old = pending;
		if (old != null) {
			for (Snapshot.Car c : old.cars()) {
				if (s.car(c.id()) == null) {
					pendingMentions.merge(c.id(), old.tick(), Math::max);
				}
			}
		}
		last = s;
		pending = s;
		long hostTick = hostTick();
		pendingAnchor |= s.tick() > hostTick || hostTick - s.tick() > LeadControl.REANCHOR_FALL;
	}

	public boolean ready() {
		return ready;
	}

	private long hostTick() {
		return posted != Long.MIN_VALUE ? posted : host.latest().tick();
	}

	private double position() {
		if (posted != Long.MIN_VALUE) {
			return posted;
		}
		SimFrame f = host.latest();
		return f.tick() + f.alpha();
	}

	static boolean blobsUsable(Snapshot s) {
		for (Snapshot.Car c : s.cars()) {
			if (c.blob() == null || c.blob().length != Layouts.CAR_BLOB_SIZE) {
				return false;
			}
		}
		if (s.arena().kind() == ArenaBlock.KIND_SOCCAR
				&& (s.arenaBlob() == null || s.arenaBlob().length != Layouts.SOCCAR_BLOB_SIZE)) {
			return false;
		}
		Snapshot.Ball b = s.ball();
		return b == null || b.blob() != null && b.blob().length == Layouts.BALL_BLOB_SIZE;
	}

	public SimFrame frame(long nowNanos, @Nullable InputFrame own, int ownCarId) {
		if (ownCarId != ownCar) {
			ownCar = ownCarId;
			host.setOwnCar(ownCarId, ownCarId >= 0 ? history : null);
			if (ownCarId >= 0) {
				lead.requestAnchor();
			}
		}
		if (ownCarId >= 0 && own != null) {
			host.submitInput(ownCarId, own);
		}
		host.setPaused(PauseSource.SERVER, lead.paused());
		double position = position();
		boolean anchor = last != null && (lead.needsAnchor(nowNanos, position) | pendingAnchor);
		Snapshot s = pending != null ? pending : anchor ? last : null;
		if (s != null) {
			long target = anchor ? lead.anchorTick(nowNanos) : SimHost.AT_TICK;
			host.reconcile(s, target, pendingMentions);
			if (anchor) {
				posted = target;
				position = target;
				anchors++;
			}
			anyPosted = true;
			pending = null;
			pendingAnchor = false;
			pendingMentions.clear();
		}
		float rate = lead.clockRate(nowNanos, position);
		if (rate > 0f) {
			host.setTickRateFactor(rate);
		}
		boolean reconciled = anyPosted;
		long seq = host.pace(nowNanos);
		host.awaitPace(seq, awaitNanos);
		SimHost.Published p = host.takeLatest();
		taken.addAll(p.corrections());
		SimFrame out = p.frame();
		if (out.paceSeq() >= seq) {
			posted = Long.MIN_VALUE;
			ready |= reconciled;
		}
		return out;
	}

	public List<InputC2S> takeOutgoing() {
		List<InputC2S> out = history.takeUnsent(NetProtocol.MAX_INPUT_TICKS, lead.epoch());
		int n = 0;
		for (InputC2S p : out) {
			n += p.count();
		}
		if (n > 0) {
			lead.onPayload(Math.min(n, TickClock.MAX_BURST));
		}
		return out;
	}

	public List<SimHost.Correction> drainCorrections() {
		List<SimHost.Correction> cs = taken;
		taken = new ArrayList<>();
		corrections += cs.size();
		return cs;
	}

	public long corrections() {
		return corrections;
	}

	public long anchors() {
		return anchors;
	}

	public int leadTicks(long nowNanos) {
		return lead.hasSnapshot() ? (int) Math.round(position() - lead.serverTickEstimate(nowNanos)) : 0;
	}
}
