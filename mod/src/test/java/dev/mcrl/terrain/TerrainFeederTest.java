package dev.mcrl.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.SimClock.Mode;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHost.SectionKey;
import dev.mcrl.sim.SimTestHooks;
import dev.mcrl.sim.SyntheticTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;

@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TerrainFeederTest {
	private static final long WAIT = TimeUnit.SECONDS.toNanos(30);
	private static final int MIN_Y = -64;

	@BeforeAll
	static void boot() {
		SyntheticTerrain.boot();
	}

	private static CarState carAt(double x, double y, double z) {
		return CarState.of(new Pose(x, y, z, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0);
	}

	private static void settle(SimHost h) {
		assertTrue(MeshWorkers.awaitIdle(WAIT), "mesh workers idle");
		h.flush();
	}

	private static boolean frozen(SimHost h) {
		return (h.latest().flags() & SimFrame.FROZEN) != 0;
	}

	private static final class EditedTerrain implements TerrainSource {
		final SyntheticTerrain inner = new SyntheticTerrain();
		final Set<BlockPos> stone = ConcurrentHashMap.newKeySet();
		final Map<SectionKey, byte[]> fluid = new ConcurrentHashMap<>();
		final short stoneId = (short) MeshWorkers.registry().idFor(Blocks.STONE.defaultBlockState(),
				EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

		@Override
		public Grid fillGrid(int sx, int sy, int sz) {
			Grid g = inner.fillGrid(sx, sy, sz);
			if (g == null) {
				return null;
			}
			for (BlockPos p : stone) {
				int x = p.getX() - sx * 16 + GridFiller.BORDER, y = p.getY() - sy * 16 + GridFiller.BORDER;
				int z = p.getZ() - sz * 16 + GridFiller.BORDER;
				if (x >= 0 && x < GridFiller.N && y >= 0 && y < GridFiller.N && z >= 0 && z < GridFiller.N) {
					g.cells()[GridFiller.gridIndex(x, y, z)] = stoneId;
				}
			}
			byte[] f = fluid.get(new SectionKey(sx, sy, sz));
			return new Grid(g.cells(), g.polled(), f == null ? null : f.clone());
		}

		@Override
		public int minSectionY() {
			return inner.minSectionY();
		}

		@Override
		public int maxSectionY() {
			return inner.maxSectionY();
		}
	}

	@Test
	void feeds_needed_sections_and_opens_the_gate() {
		try (SimHost h = SimHost.start("f-gate", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			h.addCar(1, carAt(8.5, 65, 8.5), 0, 0);
			h.flush();
			SyntheticTerrain src = new SyntheticTerrain();
			assertEquals(27, TerrainFeeder.feed(h, src, Long.MAX_VALUE), "a car at rest needs the ring around it");
			settle(h);
			h.stepLockstep(6);
			assertFalse(frozen(h));
			assertEquals(6, h.latest().tick());
			assertEquals(0, TerrainFeeder.feed(h, src, Long.MAX_VALUE), "everything needed is already submitted");
		}
	}

	@Test
	void removed_section_is_refed_and_the_car_unfreezes() {
		try (SimHost h = SimHost.start("f-refeed", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			SectionKey k = new SectionKey(0, 4, 0);
			h.addCar(1, carAt(8.5, 65, 8.5), 0, 0);
			h.flush();
			SyntheticTerrain src = new SyntheticTerrain();
			long t0 = 1_000_000_000L;
			assertEquals(27, TerrainFeeder.feed(h, src, Long.MAX_VALUE, t0));
			settle(h);
			h.stepLockstep(6);
			assertFalse(frozen(h));

			h.removeSection(k.x(), k.y(), k.z());
			h.stepLockstep(1);
			assertTrue(frozen(h));
			assertFalse(h.holdsSection(k));
			assertEquals(0, TerrainFeeder.feed(h, src, Long.MAX_VALUE, t0 + 500_000_000L), "within the grace period");
			assertEquals(1, TerrainFeeder.feed(h, src, Long.MAX_VALUE, t0 + 1_500_000_000L),
					"only the section the host lacks is fed again");
			assertEquals(2, TerrainFeeder.submittedVersion(h, k), "with a newer version");
			settle(h);
			assertTrue(h.holdsSection(k));
			h.stepLockstep(6);
			assertFalse(frozen(h), "the car unfreezes");
		}
	}

	private static final class BadSection implements TerrainSource {
		final SyntheticTerrain inner = new SyntheticTerrain();
		final SectionKey k;
		volatile boolean fixed;
		final java.util.concurrent.atomic.AtomicInteger fills = new java.util.concurrent.atomic.AtomicInteger();

		BadSection(SectionKey k) {
			this.k = k;
		}

		@Override
		public Grid fillGrid(int sx, int sy, int sz) {
			Grid g = inner.fillGrid(sx, sy, sz);
			if (g != null && new SectionKey(sx, sy, sz).equals(k)) {
				fills.incrementAndGet();
				if (!fixed) {
					g.cells()[0] = 0x7FFE;
				}
			}
			return g;
		}

		@Override
		public int minSectionY() {
			return inner.minSectionY();
		}

		@Override
		public int maxSectionY() {
			return inner.maxSectionY();
		}
	}

	private static final long SEC = 1_000_000_000L;

	private static long quarantine(SimHost h, BadSection src) {
		long t = SEC;
		assertEquals(27, TerrainFeeder.feed(h, src, Long.MAX_VALUE, t));
		settle(h);
		t += 3 * SEC / 2;
		assertEquals(1, TerrainFeeder.feed(h, src, Long.MAX_VALUE, t), "the first failure is retried");
		settle(h);
		assertEquals(0, TerrainFeeder.quarantined(h));
		t += 3 * SEC / 2;
		assertEquals(1, TerrainFeeder.feed(h, src, Long.MAX_VALUE, t), "the second failure submits the empty grid");
		settle(h);
		return t;
	}

	@Test
	void failed_build_twice_is_quarantined_as_empty() {
		try (SimHost h = SimHost.start("f-quarantine", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			SectionKey k = new SectionKey(0, 4, 0);
			h.addCar(1, carAt(8.5, 65, 8.5), 0, 0);
			h.flush();
			BadSection src = new BadSection(k);
			long t = quarantine(h, src);
			assertEquals(1, TerrainFeeder.quarantined(h));
			assertEquals(3, TerrainFeeder.lastVersion(h, k), "v1 and v2 failed, v3 is the empty grid");
			assertTrue(h.holdsSection(k), "held, so no car freezes there");
			assertEquals(3, h.committedVersion(k));
			h.stepLockstep(6);
			assertFalse(frozen(h));
			int fills = src.fills.get();
			for (int i = 1; i <= 4; i++) {
				assertEquals(0, TerrainFeeder.feed(h, src, Long.MAX_VALUE, t + i * 2 * SEC), "no third submission");
			}
			assertEquals(3, TerrainFeeder.lastVersion(h, k));
			assertEquals(fills, src.fills.get(), "a held quarantined section is not even refilled");
			assertEquals(0, TerrainFeeder.feed(h, src, Set.of(k), Long.MAX_VALUE, t + 10 * SEC));
			assertEquals(3, TerrainFeeder.lastVersion(h, k));
			assertEquals(1, TerrainFeeder.quarantined(h));
		}
	}

	private static long count(List<SectionKey> calls, SectionKey k) {
		return calls.stream().filter(k::equals).count();
	}

	@Test
	void dirty_mark_kept_while_a_build_is_in_flight() throws Exception {
		try (SimHost h = SimHost.start("f-inflight", 0, 0, 0, MIN_Y, Mode.LOCKSTEP)) {
			SectionKey k = new SectionKey(-1, 4, 0);
			h.addCar(1, carAt(8.5, 65, 8.5), 0, 0);
			h.flush();
			EditedTerrain src = new EditedTerrain();
			long t0 = 1_000_000_000L;
			CountDownLatch first = SimTestHooks.block(h);
			h.setCar(1, carAt(8.5 + 2 * 16, 65, 8.5));
			CountDownLatch second = SimTestHooks.block(h);
			assertEquals(27, TerrainFeeder.feed(h, src, Set.of(), Long.MAX_VALUE, t0), "the needed set before the move");
			assertTrue(MeshWorkers.awaitIdle(WAIT), "built; the commits wait behind the second block");
			first.countDown();
			long deadline = System.nanoTime() + WAIT;
			while (h.neededSections().contains(k)) {
				assertTrue(System.nanoTime() < deadline, "the car never moved");
				Thread.sleep(1);
			}
			assertFalse(h.holdsSection(k), "k's pre-edit build is still in flight");

			src.stone.add(new BlockPos(-8, 70, 5));
			src.inner.calls.clear();
			assertEquals(18, TerrainFeeder.feed(h, src, Set.of(k), Long.MAX_VALUE, t0), "x 2..3 are new");
			assertEquals(0, count(src.inner.calls, k), "k is neither needed nor held: not filled yet");
			second.countDown();
			settle(h);
			assertEquals(1, h.committedVersion(k), "the pre-edit build landed (within 2 sections of the need)");
			assertEquals(1, TerrainFeeder.feed(h, src, Set.of(), Long.MAX_VALUE, t0), "the kept mark rebuilds k");
			settle(h);
			assertEquals(2, h.committedVersion(k));
			assertEquals(0, TerrainFeeder.feed(h, src, Set.of(), Long.MAX_VALUE, t0), "and the mark is gone");
		}
	}
}
