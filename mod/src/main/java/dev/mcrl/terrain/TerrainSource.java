package dev.mcrl.terrain;

import org.jspecify.annotations.Nullable;

public interface TerrainSource {
	@Nullable Grid fillGrid(int sx, int sy, int sz);

	int minSectionY();

	int maxSectionY();
}
