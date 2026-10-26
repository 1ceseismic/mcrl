package dev.mcrl.terrain;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.mcrl.sim.SimHost.SectionKey;
import dev.mcrl.sim.SimHosts;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class TerrainEdits {
	private static final Map<ResourceKey<Level>, Set<SectionKey>> DIRTY = new ConcurrentHashMap<>();
	private static final Set<SectionKey> CLIENT_DIRTY = ConcurrentHashMap.newKeySet();
	private static volatile boolean trackClient;

	private TerrainEdits() {
	}

	public static Set<SectionKey> affectedSections(BlockPos pos) {
		int[] xs = axis(pos.getX()), ys = axis(pos.getY()), zs = axis(pos.getZ());
		Set<SectionKey> s = new HashSet<>(16);
		for (int x : xs) {
			for (int y : ys) {
				for (int z : zs) {
					s.add(new SectionKey(x, y, z));
				}
			}
		}
		return s;
	}

	private static int[] axis(int v) {
		int sec = v >> 4, local = v & 15;
		if (local < GridFiller.BORDER) {
			return new int[] {sec - 1, sec};
		}
		if (local >= 16 - GridFiller.BORDER) {
			return new int[] {sec, sec + 1};
		}
		return new int[] {sec};
	}

	public static void onBlockChanged(ServerLevel level, BlockPos pos) {
		if (SimHosts.get(level.dimension()) != null) {
			markChanged(level.dimension(), pos);
		}
	}

	static void markChanged(ResourceKey<Level> dim, BlockPos pos) {
		DIRTY.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).addAll(affectedSections(pos));
	}

	public static void onClientBlockChanged(BlockPos pos) {
		if (trackClient) {
			CLIENT_DIRTY.addAll(affectedSections(pos));
		}
	}

	public static void markClientChunk(int cx, int cz, int minSectionY, int maxSectionY) {
		if (!trackClient) {
			return;
		}
		for (int x = cx - 1; x <= cx + 1; x++) {
			for (int z = cz - 1; z <= cz + 1; z++) {
				for (int y = minSectionY; y <= maxSectionY; y++) {
					CLIENT_DIRTY.add(new SectionKey(x, y, z));
				}
			}
		}
	}

	public static void trackClient(boolean on) {
		trackClient = on;
		if (!on) {
			CLIENT_DIRTY.clear();
		}
	}

	public static Set<SectionKey> drainClientDirty() {
		return drain(CLIENT_DIRTY);
	}

	public static Set<SectionKey> drainDirty(ServerLevel level) {
		return drainDirty(level.dimension());
	}

	static Set<SectionKey> drainDirty(ResourceKey<Level> dim) {
		Set<SectionKey> s = DIRTY.get(dim);
		return s == null ? Set.of() : drain(s);
	}

	private static Set<SectionKey> drain(Set<SectionKey> s) {
		if (s.isEmpty()) {
			return Set.of();
		}
		Set<SectionKey> out = new LinkedHashSet<>();
		for (Iterator<SectionKey> it = s.iterator(); it.hasNext();) {
			out.add(it.next());
			it.remove();
		}
		return out;
	}

	public static void clear() {
		DIRTY.clear();
	}
}
