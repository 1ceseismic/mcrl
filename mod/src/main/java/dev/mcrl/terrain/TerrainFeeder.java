package dev.mcrl.terrain;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.natives.McrlNative;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHost.SectionKey;
import dev.mcrl.sim.SimHosts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class TerrainFeeder {
	public static final long BUDGET_NANOS = 2_000_000L;
	public static final long RESUBMIT_GRACE_NANOS = 1_000_000_000L;
	public static final int DYNAMIC_POLL_TICKS = 4;
	static final int QUARANTINE_AFTER = 2;
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final int CELLS = GridFiller.N * GridFiller.N * GridFiller.N;

	private static final Map<SimHost, State> STATES = new ConcurrentHashMap<>();

	private static final class Submission {
		final int version;
		final long atNanos;
		final CompletableFuture<Integer> build;
		final short[] cells;
		final boolean polled;
		byte @Nullable [] fluid;
		boolean neighbourLoaded;
		boolean failureCounted;

		Submission(int version, long atNanos, CompletableFuture<Integer> build, short[] cells, boolean polled,
				byte @Nullable [] fluid) {
			this.version = version;
			this.atNanos = atNanos;
			this.build = build;
			this.cells = cells;
			this.polled = polled;
			this.fluid = fluid;
		}

		boolean heldOrInFlight(SimHost host, SectionKey k) {
			return !build.isDone() || held(host, k);
		}

		boolean held(SimHost host, SectionKey k) {
			return build.isDone() && !build.isCompletedExceptionally()
					&& Integer.valueOf(version).equals(host.committedVersion(k));
		}

		boolean provisional() {
			return build.isDone() && !build.isCompletedExceptionally()
					&& (build.join() & McrlNative.BUILT_PROVISIONAL) != 0;
		}
	}

	private record Failures(short[] cells, int count) {
	}

	private static final class State {
		final Map<SectionKey, Submission> submitted = new HashMap<>();
		final Map<SectionKey, Submission> latest = new HashMap<>();
		// Native forgets versions on remove: keep them rising.
		final Map<SectionKey, Integer> lastVersion = new HashMap<>();
		final Set<SectionKey> dirty = new LinkedHashSet<>();
		final Set<SectionKey> polls = new LinkedHashSet<>();
		final Map<SectionKey, Failures> failures = new HashMap<>();
		final Map<SectionKey, short[]> quarantine = new HashMap<>();
		long ticks;
		int fluidUpdates;
	}

	private TerrainFeeder() {
	}

	public static void onEndLevelTick(ServerLevel level) {
		STATES.keySet().removeIf(SimHost::isClosed);
		Set<SectionKey> dirty = TerrainEdits.drainDirty(level);
		SimHost host = SimHosts.get(level.dimension());
		if (host != null) {
			feed(host, new ServerLevelTerrainSource(level, MeshWorkers.registry()), dirty, BUDGET_NANOS);
		}
	}

	public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		SimHost host = SimHosts.get(level.dimension());
		if (host != null) {
			ChunkPos p = chunk.getPos();
			chunkLoaded(host, p.x(), p.z());
		}
	}

	public static void chunkLoaded(SimHost host, int cx, int cz) {
		State st = STATES.get(host);
		if (st == null) {
			return;
		}
		synchronized (st) {
			for (Map.Entry<SectionKey, Submission> e : st.latest.entrySet()) {
				SectionKey k = e.getKey();
				if (Math.abs(k.x() - cx) <= 1 && Math.abs(k.z() - cz) <= 1) {
					e.getValue().neighbourLoaded = true;
				}
			}
		}
	}

	public static int feed(SimHost host, TerrainSource src, long budgetNanos) {
		return feed(host, src, Set.of(), budgetNanos, System.nanoTime());
	}

	public static int feed(SimHost host, TerrainSource src, Set<SectionKey> dirty, long budgetNanos) {
		return feed(host, src, dirty, budgetNanos, System.nanoTime());
	}

	static int feed(SimHost host, TerrainSource src, long budgetNanos, long nowNanos) {
		return feed(host, src, Set.of(), budgetNanos, nowNanos);
	}

	static int feed(SimHost host, TerrainSource src, Set<SectionKey> dirty, long budgetNanos, long nowNanos) {
		if (host.isClosed()) {
			STATES.remove(host);
			return 0;
		}
		State st = STATES.computeIfAbsent(host, h -> new State());
		synchronized (st) {
			Set<SectionKey> needed = host.neededSections();
			st.submitted.keySet().retainAll(needed);
			st.latest.entrySet().removeIf(e -> !needed.contains(e.getKey()) && e.getValue().build.isDone()
					&& !host.holdsSection(e.getKey()));
			st.dirty.addAll(dirty);
			for (Map.Entry<SectionKey, Submission> e : st.latest.entrySet()) {
				Submission sub = e.getValue();
				if (sub.neighbourLoaded && sub.build.isDone()) {
					sub.neighbourLoaded = false;
					if (sub.provisional()) {
						st.dirty.add(e.getKey());
					}
				}
			}
			if (++st.ticks % DYNAMIC_POLL_TICKS == 0) {
				for (Map.Entry<SectionKey, Submission> e : st.latest.entrySet()) {
					if (e.getValue().polled && host.holdsSection(e.getKey())) {
						st.polls.add(e.getKey());
					}
				}
			}
			return new Tick(st, host, src, needed, budgetNanos, nowNanos).run();
		}
	}

	private static final class Tick {
		final State st;
		final SimHost host;
		final TerrainSource src;
		final Set<SectionKey> needed;
		final long budgetNanos, nowNanos, start = System.nanoTime();
		int submittedCount;

		Tick(State st, SimHost host, TerrainSource src, Set<SectionKey> needed, long budgetNanos, long nowNanos) {
			this.st = st;
			this.host = host;
			this.src = src;
			this.needed = needed;
			this.budgetNanos = budgetNanos;
			this.nowNanos = nowNanos;
		}

		int run() {
			reapFailures();
			if (rebuildDirty() && submitNeeded()) {
				pollDynamic();
			}
			return submittedCount;
		}

		boolean spent() {
			return System.nanoTime() - start >= budgetNanos;
		}

		void reapFailures() {
			for (Map.Entry<SectionKey, Submission> e : List.copyOf(st.latest.entrySet())) {
				Submission sub = e.getValue();
				if (sub.failureCounted || !sub.build.isCompletedExceptionally()) {
					continue;
				}
				sub.failureCounted = true;
				SectionKey k = e.getKey();
				if (st.quarantine.containsKey(k)) {
					continue;
				}
				Failures f = st.failures.get(k);
				int n = f != null && Arrays.equals(f.cells(), sub.cells) ? f.count() + 1 : 1;
				st.failures.put(k, new Failures(sub.cells, n));
				if (n >= QUARANTINE_AFTER) {
					Throwable t = sub.build.exceptionNow();
					LOG.warn("mcrl: section {} {} {} quarantined after {} failed builds: {}", k.x(), k.y(), k.z(), n, t);
					st.quarantine.put(k, sub.cells);
					submitCells(k, new short[CELLS], false, null);
				}
			}
		}

		boolean rebuildDirty() {
			for (Iterator<SectionKey> it = st.dirty.iterator(); it.hasNext();) {
				SectionKey k = it.next();
				Submission last = st.latest.get(k);
				// In flight, then held: commits precede future completion.
				boolean inFlight = last != null && !last.build.isDone();
				if (!needed.contains(k) && !host.holdsSection(k)) {
					if (!inFlight) {
						it.remove();
					}
					continue;
				}
				Grid g = fill(k);
				if (g != null) {
					it.remove();
					boolean sameCells = last != null && Arrays.equals(g.cells(), last.cells);
					boolean sameFluid = last != null && Arrays.equals(g.fluid(), last.fluid);
					if (sameCells && !sameFluid && last.held(host, k)) {
						host.setFluid(k, g.fluid());
						last.fluid = g.fluid();
						st.fluidUpdates++;
					} else if (!sameCells || !sameFluid || !last.heldOrInFlight(host, k)) {
						submit(k, g);
					}
				}
				if (spent()) {
					return false;
				}
			}
			return true;
		}

		boolean submitNeeded() {
			for (SectionKey k : needed) {
				Submission sub = st.submitted.get(k);
				if (sub == null) {
					Submission last = st.latest.get(k);
					if (last != null && (!last.build.isDone() || host.holdsSection(k))) {
						st.submitted.put(k, last);
						continue;
					}
				} else if (!overdue(sub, k)) {
					continue;
				}
				Grid g = fill(k);
				if (g != null) {
					submit(k, g);
				}
				if (spent()) {
					return false;
				}
			}
			return true;
		}

		void pollDynamic() {
			for (Iterator<SectionKey> it = st.polls.iterator(); it.hasNext();) {
				SectionKey k = it.next();
				it.remove();
				Submission last = st.latest.get(k);
				if (last == null || !last.polled || !host.holdsSection(k)) {
					continue;
				}
				Grid g = fill(k);
				if (g != null && !Arrays.equals(g.cells(), last.cells)) {
					submit(k, g);
				}
				if (spent()) {
					return;
				}
			}
		}

		boolean overdue(Submission sub, SectionKey k) {
			return sub.build.isDone() && nowNanos - sub.atNanos >= RESUBMIT_GRACE_NANOS && !host.holdsSection(k);
		}

		@Nullable Grid fill(SectionKey k) {
			return k.y() < src.minSectionY() || k.y() > src.maxSectionY() ? new Grid(new short[CELLS], false)
					: src.fillGrid(k.x(), k.y(), k.z());
		}

		void submit(SectionKey k, Grid g) {
			short[] bad = st.quarantine.get(k);
			if (bad != null) {
				if (!Arrays.equals(bad, g.cells())) {
					st.quarantine.remove(k);
					st.failures.remove(k);
				} else {
					Submission last = st.latest.get(k);
					if (last == null || !last.heldOrInFlight(host, k)) {
						submitCells(k, new short[CELLS], false, null);
					}
					return;
				}
			}
			submitCells(k, g.cells(), g.polled(), g.fluid());
		}

		void submitCells(SectionKey k, short[] cells, boolean polled, byte @Nullable [] fluid) {
			int v = st.lastVersion.merge(k, 1, Integer::sum);
			Submission sub = new Submission(v, nowNanos, MeshWorkers.submit(k, cells, fluid, v, host), cells, polled,
					fluid);
			if (needed.contains(k)) {
				st.submitted.put(k, sub);
			}
			st.latest.put(k, sub);
			submittedCount++;
		}
	}

	public static void forget(SimHost host) {
		STATES.remove(host);
	}

	static boolean tracks(SimHost host) {
		return STATES.containsKey(host);
	}

	public static int quarantined(SimHost host) {
		State st = STATES.get(host);
		if (st == null) {
			return 0;
		}
		synchronized (st) {
			return st.quarantine.size();
		}
	}

	public static int fluidUpdates(SimHost host) {
		State st = STATES.get(host);
		if (st == null) {
			return 0;
		}
		synchronized (st) {
			return st.fluidUpdates;
		}
	}

	static @Nullable Integer submittedVersion(SimHost host, SectionKey k) {
		State st = STATES.get(host);
		if (st == null) {
			return null;
		}
		synchronized (st) {
			Submission sub = st.submitted.get(k);
			return sub == null ? null : sub.version;
		}
	}

	static int lastVersion(SimHost host, SectionKey k) {
		State st = STATES.get(host);
		if (st == null) {
			return 0;
		}
		synchronized (st) {
			return st.lastVersion.getOrDefault(k, 0);
		}
	}
}
