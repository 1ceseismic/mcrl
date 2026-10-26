package dev.mcrl.terrain;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;

public final class ServerLevelTerrainSource implements TerrainSource {
	private final ServerLevel level;
	private final ShapeRegistry registry;

	public ServerLevelTerrainSource(ServerLevel level, ShapeRegistry registry) {
		this.level = level;
		this.registry = registry;
	}

	// Server thread only.
	@Override
	public @Nullable Grid fillGrid(int sx, int sy, int sz) {
		return GridFiller.fill(level, level.getChunkSource()::getChunkNow, sx, sy, sz, registry);
	}

	@Override
	public int minSectionY() {
		return level.getMinSectionY();
	}

	@Override
	public int maxSectionY() {
		return level.getMaxSectionY();
	}
}
