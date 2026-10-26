package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.math.Pose;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.natives.Breadcrumb.Op;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.sim.SimHost.Kind;
import dev.mcrl.sim.SimHost.SectionKey;
import dev.mcrl.sim.SimWorld.CarRec;

final class SimSections {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final double CAR_LOOKAHEAD_S = 0.75;
	private static final double BALL_LOOKAHEAD_S = 0.5;
	private static final double NEED_MARGIN_BLOCKS = 1.5;
	private static final int KEEP_DILATION = 2;
	private static final int KEEP_TICKS = 600;

	private final SimWorld w;
	private final Map<SectionKey, Integer> highestVersion = new HashMap<>();
	private final Map<SectionKey, Integer> outsideTicks = new HashMap<>();
	private final ConcurrentHashMap<SectionKey, Integer> committed = new ConcurrentHashMap<>();
	private volatile NeededSections need = NeededSections.NONE;

	SimSections(SimWorld w) {
		this.w = w;
	}

	Set<SectionKey> needed() {
		return need.sections();
	}

	boolean wants(SectionKey k) {
		return need.near(k, KEEP_DILATION);
	}

	boolean holds(SectionKey k) {
		return committed.containsKey(k);
	}

	@Nullable Integer version(SectionKey k) {
		return committed.get(k);
	}

	int count() {
		return committed.size();
	}

	void commit(SimHost.Commit c) {
		boolean handedToNative = false;
		try {
			SectionKey k = c.key();
			if (stale(k, c.version())) {
				return;
			}
			if (!wants(k)) {
				highestVersion.put(k, c.version());
				if (committed.containsKey(k)) {
					remove(k);
				}
				return;
			}
			handedToNative = true;
			int rc;
			Breadcrumb.enter(Op.COMMIT, k.x(), k.y(), k.z(), w.tick);
			try {
				rc = McrlNative.sectionCommit(w.handle, c.built());
			} finally {
				Breadcrumb.exit();
			}
			highestVersion.put(k, c.version());
			if (rc == McrlNative.STALE) {
				LOG.warn("mcrl: native kept a newer version of section {} than v{}", k, c.version());
			} else {
				committed.put(k, c.version());
				outsideTicks.remove(k);
			}
		} finally {
			if (!handedToNative) {
				freeBuilt(c.key(), c.built(), w.tick);
			}
		}
	}

	static void freeBuilt(SectionKey k, MemorySegment built, long tick) {
		Breadcrumb.enter(Op.COMMIT, k.x(), k.y(), k.z(), tick);
		try {
			McrlNative.builtFree(built);
		} finally {
			Breadcrumb.exit();
		}
	}

	void skipped(SimHost.Skipped c) {
		SectionKey k = c.key();
		if (stale(k, c.version())) {
			return;
		}
		highestVersion.put(k, c.version());
		if (committed.containsKey(k)) {
			remove(k);
		}
	}

	private boolean stale(SectionKey k, int version) {
		Integer hi = highestVersion.get(k);
		return hi != null && version < hi;
	}

	void remove(SectionKey k) {
		Breadcrumb.enter(Op.REMOVE, k.x(), k.y(), k.z(), w.tick);
		try {
			McrlNative.sectionRemove(w.handle, k.x(), k.y(), k.z());
		} finally {
			Breadcrumb.exit();
		}
		committed.remove(k);
		outsideTicks.remove(k);
	}

	void setFluid(SectionKey k, byte @Nullable [] cells) {
		if (!committed.containsKey(k)) {
			return;
		}
		Breadcrumb.enter(Op.COMMIT, k.x(), k.y(), k.z(), w.tick);
		try (Arena a = Arena.ofConfined()) {
			MemorySegment plane = MemorySegment.NULL;
			if (cells != null) {
				plane = a.allocate(cells.length);
				MemorySegment.copy(cells, 0, plane, JAVA_BYTE, 0, cells.length);
			}
			McrlNative.sectionSetFluid(w.handle, k.x(), k.y(), k.z(), plane);
		} finally {
			Breadcrumb.exit();
		}
	}

	void expire(int ticks) {
		for (Iterator<SectionKey> it = committed.keySet().iterator(); it.hasNext();) {
			SectionKey k = it.next();
			if (wants(k)) {
				outsideTicks.remove(k);
			} else if (outsideTicks.merge(k, ticks, Integer::sum) >= KEEP_TICKS) {
				Breadcrumb.enter(Op.REMOVE, k.x(), k.y(), k.z(), w.tick);
				try {
					McrlNative.sectionRemove(w.handle, k.x(), k.y(), k.z());
				} finally {
					Breadcrumb.exit();
				}
				it.remove();
				outsideTicks.remove(k);
			}
		}
	}

	void refresh() {
		if (w.kind != Kind.TERRAIN) {
			need = NeededSections.NONE;
			return;
		}
		List<NeededSections.Body> bodies = new ArrayList<>(w.cars.size() + 1);
		for (CarRec c : w.cars.values()) {
			Pose p = c.cur.pose();
			bodies.add(new NeededSections.Body(p.x(), p.y(), p.z(), c.cur.vx(), c.cur.vy(), c.cur.vz(), CAR_LOOKAHEAD_S));
		}
		if (w.ball != null) {
			Pose p = w.ball.cur.pose();
			bodies.add(new NeededSections.Body(p.x(), p.y(), p.z(), w.ball.cur.vx(), w.ball.cur.vy(), w.ball.cur.vz(),
					BALL_LOOKAHEAD_S));
		}
		need = NeededSections.of(bodies, NEED_MARGIN_BLOCKS);
	}

	void clear() {
		committed.clear();
		outsideTicks.clear();
	}
}
