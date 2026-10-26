package dev.mcrl.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.mcrl.sim.SimHost.SectionKey;
import dev.mcrl.sim.SyntheticTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class TerrainEditsTest {
	@BeforeAll
	static void boot() {
		SyntheticTerrain.boot();
	}

	@Test
	void affected_sections_are_exactly_the_windows_holding_the_block() {
		for (int v = -40; v <= 40; v++) {
			BlockPos p = new BlockPos(v, 7, 7);
			Set<SectionKey> want = new HashSet<>();
			for (int sx = -4; sx <= 4; sx++) {
				int local = v - sx * 16;
				if (local >= -GridFiller.BORDER && local <= 15 + GridFiller.BORDER) {
					want.add(new SectionKey(sx, 0, 0));
				}
			}
			assertEquals(want, TerrainEdits.affectedSections(p), "x = " + v);
		}
	}

	@Test
	void clientMapIsSeparate() {
		TerrainEdits.drainDirty(Level.OVERWORLD);
		TerrainEdits.onClientBlockChanged(new BlockPos(5, 5, 5));
		assertEquals(Set.of(), TerrainEdits.drainClientDirty(), "nothing recorded without a prediction host");
		TerrainEdits.trackClient(true);
		TerrainEdits.onClientBlockChanged(new BlockPos(5, 5, 5));
		assertEquals(Set.of(), TerrainEdits.drainDirty(Level.OVERWORLD), "the server's map has no client edit");
		TerrainEdits.markChanged(Level.OVERWORLD, new BlockPos(40, 5, 5));
		assertEquals(Set.of(new SectionKey(0, 0, 0)), TerrainEdits.drainClientDirty(), "the client's own edit only");
		assertEquals(Set.of(new SectionKey(2, 0, 0)), TerrainEdits.drainDirty(Level.OVERWORLD));
		assertTrue(TerrainEdits.drainClientDirty().isEmpty(), "drained");
		TerrainEdits.trackClient(false);
	}
}
