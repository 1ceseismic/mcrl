package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import dev.mcrl.match.MatchSize;
import dev.mcrl.net.MatchPayloads.MatchEventS2C;
import dev.mcrl.net.MatchPayloads.MatchSetupS2C;
import dev.mcrl.net.MatchPayloads.SlotInfo;

class MatchPayloadsTest {
	private static RegistryFriendlyByteBuf buf() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	@Test
	void setupRoundTrips() {
		float[] xyz = new float[102];
		for (int i = 0; i < xyz.length; i++) {
			xyz[i] = i * 1.25f - 40f;
		}
		List<SlotInfo> slots = List.of(new SlotInfo(0, 0, 41, "Alex", false), new SlotInfo(1, 0, -1, "", false),
				new SlotInfo(2, 1, 43, "", true), new SlotInfo(3, 1, 44, "Steve", false));
		MatchSetupS2C s = new MatchSetupS2C(MatchSize.DOUBLES, 0, 64, 0, xyz, 0b111111L, slots);
		RegistryFriendlyByteBuf b = buf();
		MatchSetupS2C.CODEC.encode(b, s);
		MatchSetupS2C d = MatchSetupS2C.CODEC.decode(b);
		assertEquals(MatchSize.DOUBLES, d.size());
		assertEquals(0, d.ox());
		assertEquals(64, d.oy());
		assertEquals(0, d.oz());
		assertArrayEquals(xyz, d.padXyz());
		assertEquals(0b111111L, d.padBigBits());
		assertEquals(slots, d.slots());
		assertEquals(0, b.readableBytes());
	}

	@Test
	void eventsRoundTrip() {
		for (MatchEventS2C e : List.of(MatchEventS2C.goal(0, 41, 2875.5f, "Alex", "Steve", 1.5, 65.2, 104.0),
				MatchEventS2C.pickup(33, 44), MatchEventS2C.end(-1), MatchEventS2C.end(1))) {
			RegistryFriendlyByteBuf b = buf();
			MatchEventS2C.CODEC.encode(b, e);
			assertEquals(e, MatchEventS2C.CODEC.decode(b));
			assertTrue(e.known());
			assertEquals(0, b.readableBytes());
		}
	}

	@Test
	void unknownKindIsNull() {
		RegistryFriendlyByteBuf b = buf();
		b.writeVarInt(9);
		b.writeVarInt(0);
		b.writeVarInt(41);
		b.writeVarInt(-1);
		b.writeFloat(0f);
		b.writeUtf("");
		b.writeUtf("");
		b.writeDouble(0).writeDouble(0).writeDouble(0);
		MatchEventS2C d = MatchEventS2C.CODEC.decode(b);
		assertNull(d.kind(), "decoded, not thrown");
		assertFalse(d.known(), "and skipped");
		assertEquals(41, d.entityId());
		assertEquals(0, b.readableBytes(), "the whole event read");
	}
}
