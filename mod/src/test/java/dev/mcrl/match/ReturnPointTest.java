package dev.mcrl.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.sim.SyntheticTerrain;

class ReturnPointTest {
	@BeforeAll
	static void boot() {
		SyntheticTerrain.boot();
	}

	private static ReturnPoint roundTrip(ReturnPoint p) {
		JsonElement json = ReturnPoint.CODEC.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
		return ReturnPoint.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
	}

	@Test
	void codecRoundTrip() {
		ReturnPoint a = new ReturnPoint(Level.NETHER, new Vec3(10.25, 70.5, -3.125), 91.5f, -12.25f, GameType.CREATIVE,
				Optional.of(new UUID(0x1234_5678_9abc_def0L, 0x0fed_cba9_8765_4321L)));
		assertEquals(a, roundTrip(a));
		ReturnPoint b = new ReturnPoint(Level.OVERWORLD, new Vec3(0, 64, 0), 0f, 0f, GameType.SURVIVAL, Optional.empty());
		assertEquals(b, roundTrip(b));
		ReturnPoint c = new ReturnPoint(StadiumConst.KEY, new Vec3(1, 2, 3), 4f, 5f, GameType.ADVENTURE, Optional.empty());
		assertEquals(c, roundTrip(c));
	}
}
