package dev.mcrl.terrain;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.natives.Layouts;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHost.SectionKey;

public final class MeshWorkers {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	public static final int THREADS = 2;
	private static final int CELLS = GridFiller.N * GridFiller.N * GridFiller.N;

	private static final MaterialRegistry MATERIALS = new MaterialRegistry();
	private static final ShapeRegistry REGISTRY = new ShapeRegistry(MATERIALS);
	private static volatile MemorySegment shapes;
	private static final AtomicInteger PENDING = new AtomicInteger();
	private static final Object IDLE = new Object();

	private static final class Pool {
		static final ExecutorService EXECUTOR = create();

		private static ExecutorService create() {
			AtomicInteger n = new AtomicInteger();
			return new ThreadPoolExecutor(THREADS, THREADS, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
				Thread t = new Thread(r, "mcrl-mesh-" + n.getAndIncrement());
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
		}
	}

	private MeshWorkers() {
	}

	// Mesh workers and sim threads only: may create natively.
	public static MemorySegment shapes() {
		MemorySegment s = shapes;
		if (s == null) {
			synchronized (MeshWorkers.class) {
				s = shapes;
				if (s == null) {
					shapes = s = McrlNative.shapesCreate();
				}
			}
		}
		return s;
	}

	public static ShapeRegistry registry() {
		return REGISTRY;
	}

	public static CompletableFuture<Integer> submit(SectionKey key, short[] grid, byte @Nullable [] fluid, int version,
			SimHost target) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(target);
		if (grid.length != CELLS) {
			throw new IllegalArgumentException("grid must have " + CELLS + " cells, got " + grid.length);
		}
		if (fluid != null && fluid.length != GridFiller.CORE_CELLS) {
			throw new IllegalArgumentException("fluid must have " + GridFiller.CORE_CELLS + " cells, got " + fluid.length);
		}
		CompletableFuture<Integer> f = new CompletableFuture<>();
		PENDING.incrementAndGet();
		try {
			Pool.EXECUTOR.execute(() -> {
				try {
					build(key, grid, fluid, version, target, f);
				} catch (Throwable t) {
					LOG.error("mcrl: section {} v{} failed to build", key, version, t);
					f.completeExceptionally(t);
				} finally {
					done();
				}
			});
		} catch (RejectedExecutionException e) {
			done();
			f.completeExceptionally(e);
		}
		return f;
	}

	public static boolean awaitIdle(long timeoutNanos) {
		long deadline = System.nanoTime() + timeoutNanos;
		synchronized (IDLE) {
			while (PENDING.get() > 0) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					return false;
				}
				try {
					TimeUnit.NANOSECONDS.timedWait(IDLE, left);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return true;
		}
	}

	private static void done() {
		if (PENDING.decrementAndGet() == 0) {
			synchronized (IDLE) {
				IDLE.notifyAll();
			}
		}
	}

	private static void build(SectionKey key, short[] grid, byte @Nullable [] fluid, int version, SimHost target,
			CompletableFuture<Integer> f) {
		if (target.isClosed()) {
			f.complete(0);
			return;
		}
		if (!target.wantsSection(key)) {
			target.skipped(key, version, () -> f.complete(0));
			return;
		}
		MemorySegment built;
		int flags;
		Breadcrumb.enter(Breadcrumb.Op.BUILD, key.x(), key.y(), key.z(), 0);
		try (Arena a = Arena.ofConfined()) {
			MemorySegment sh = shapes();
			MATERIALS.syncNative(sh);
			REGISTRY.syncNative(sh);
			MemorySegment cells = a.allocate(JAVA_SHORT, grid.length);
			MemorySegment.copy(grid, 0, cells, JAVA_SHORT, 0, grid.length);
			MemorySegment in = a.allocate(Layouts.SECTION_INPUT);
			in.set(JAVA_INT, 0, key.x());
			in.set(JAVA_INT, 4, key.y());
			in.set(JAVA_INT, 8, key.z());
			in.set(JAVA_INT, 12, version);
			in.set(JAVA_INT, 16, GridFiller.BORDER);
			in.set(JAVA_INT, 20, 0);
			in.set(ADDRESS, Layouts.SECTION_CELLS, cells);
			MemorySegment stats = a.allocate(Layouts.BUILD_STATS);
			built = McrlNative.sectionBuild(sh, in, stats);
			flags = stats.get(JAVA_INT, Layouts.BUILD_STATS_FLAGS);
			if (fluid != null) {
				MemorySegment plane = a.allocate(fluid.length);
				MemorySegment.copy(fluid, 0, plane, JAVA_BYTE, 0, fluid.length);
				try {
					McrlNative.builtSetFluid(built, plane);
				} catch (RuntimeException e) {
					McrlNative.builtFree(built);
					throw e;
				}
			}
		} finally {
			Breadcrumb.exit();
		}
		int handedFlags = flags;
		target.commit(key, version, built, () -> f.complete(handedFlags));
	}
}
