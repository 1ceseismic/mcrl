package dev.mcrl.sim;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.mcrl.sim.SimHost.SectionKey;
import dev.mcrl.terrain.Grid;
import dev.mcrl.terrain.GridFiller;
import dev.mcrl.terrain.MeshWorkers;
import dev.mcrl.terrain.TerrainSource;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;

public final class SyntheticTerrain implements TerrainSource {
	public static final int FLOOR_Y = 63;
	private static final int N = GridFiller.N;
	private static final int B = GridFiller.BORDER;

	private final int minSectionY, maxSectionY;
	private final short stone;
	public final List<SectionKey> calls = new CopyOnWriteArrayList<>();

	public SyntheticTerrain() {
		this(-4, 19);
	}

	public SyntheticTerrain(int minSectionY, int maxSectionY) {
		boot();
		this.minSectionY = minSectionY;
		this.maxSectionY = maxSectionY;
		this.stone = (short) MeshWorkers.registry().idFor(Blocks.STONE.defaultBlockState(), EmptyBlockGetter.INSTANCE,
				BlockPos.ZERO);
	}

	public static synchronized void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Override
	public Grid fillGrid(int sx, int sy, int sz) {
		calls.add(new SectionKey(sx, sy, sz));
		short[] g = new short[N * N * N];
		for (int y = 0; y < N; y++) {
			if (sy * 16 + y - B != FLOOR_Y) {
				continue;
			}
			for (int z = 0; z < N; z++) {
				for (int x = 0; x < N; x++) {
					g[((y * N) + z) * N + x] = stone;
				}
			}
		}
		return new Grid(g, false);
	}

	@Override
	public int minSectionY() {
		return minSectionY;
	}

	@Override
	public int maxSectionY() {
		return maxSectionY;
	}
}
