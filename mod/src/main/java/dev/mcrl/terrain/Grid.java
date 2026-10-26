package dev.mcrl.terrain;

import org.jspecify.annotations.Nullable;

public record Grid(short[] cells, boolean polled, byte @Nullable [] fluid) {
	public Grid {
		if (fluid != null && fluid.length != GridFiller.CORE_CELLS) {
			throw new IllegalArgumentException(
					"a fluid plane has " + GridFiller.CORE_CELLS + " cells, not " + fluid.length);
		}
	}

	public Grid(short[] cells, boolean polled) {
		this(cells, polled, null);
	}
}
