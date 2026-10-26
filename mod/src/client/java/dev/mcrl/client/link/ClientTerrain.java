package dev.mcrl.client.link;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.multiplayer.ClientLevel;

import dev.mcrl.sim.SimHost;
import dev.mcrl.terrain.Grid;
import dev.mcrl.terrain.GridFiller;
import dev.mcrl.terrain.MeshWorkers;
import dev.mcrl.terrain.ShapeRegistry;
import dev.mcrl.terrain.TerrainEdits;
import dev.mcrl.terrain.TerrainFeeder;
import dev.mcrl.terrain.TerrainSource;

final class ClientTerrain {
	private static @Nullable SimHost host;
	private static @Nullable LevelSource source;
	private static boolean registered;

	private record LevelSource(ClientLevel level, ShapeRegistry registry) implements TerrainSource {
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

	private ClientTerrain() {
	}

	static void attach(SimHost h, ClientLevel l) {
		register();
		TerrainEdits.drainClientDirty();
		TerrainEdits.trackClient(true);
		host = h;
		source = new LevelSource(l, MeshWorkers.registry());
	}

	static void detach() {
		SimHost h = host;
		if (h != null) {
			TerrainFeeder.forget(h);
		}
		host = null;
		source = null;
		TerrainEdits.trackClient(false);
	}

	private static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ClientTickEvents.END_LEVEL_TICK.register(ClientTerrain::onEndLevelTick);
		ClientChunkEvents.CHUNK_LOAD.register((l, chunk) -> {
			SimHost h = host;
			LevelSource src = source;
			if (h != null && src != null && l == src.level()) {
				int cx = chunk.getPos().x(), cz = chunk.getPos().z();
				TerrainFeeder.chunkLoaded(h, cx, cz);
				TerrainEdits.markClientChunk(cx, cz, l.getMinSectionY(), l.getMaxSectionY());
			}
		});
	}

	private static void onEndLevelTick(ClientLevel l) {
		SimHost h = host;
		LevelSource src = source;
		if (h == null || src == null || l != src.level()) {
			return;
		}
		if (h.isClosed()) {
			detach();
			return;
		}
		TerrainFeeder.feed(h, src, TerrainEdits.drainClientDirty(), TerrainFeeder.BUDGET_NANOS);
	}
}
