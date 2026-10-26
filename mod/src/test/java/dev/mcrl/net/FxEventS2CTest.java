package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import dev.mcrl.net.McrlPayloads.FxEventS2C;

class FxEventS2CTest {
	private static RegistryFriendlyByteBuf buf() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	@Test
	void roundTripsAndSkipsUnknownKinds() {
		RegistryFriendlyByteBuf b = buf();
		FxEventS2C e = new FxEventS2C(7, FxEventS2C.Kind.CAR_LEFT, 1.5, 64, -3, 1, 0, 0, 0);
		FxEventS2C.CODEC.encode(b, e);
		assertEquals(e, FxEventS2C.CODEC.decode(b));
		assertTrue(e.playable());

		RegistryFriendlyByteBuf unknown = buf();
		unknown.writeVarInt(7);
		unknown.writeVarInt(FxEventS2C.Kind.values().length);
		unknown.writeDouble(0).writeDouble(0).writeDouble(0);
		unknown.writeFloat(1).writeFloat(0).writeFloat(0).writeFloat(0);
		unknown.writeVarInt(-1);
		FxEventS2C d = FxEventS2C.CODEC.decode(unknown);
		assertNull(d.kind(), "decoded, not thrown");
		assertFalse(d.playable(), "and skipped");
		assertEquals(0, unknown.readableBytes(), "the whole event read");
	}

	@Test
	void nonFinitePosesDoNotPlay() {
		assertFalse(new FxEventS2C(1, FxEventS2C.Kind.CAR_SPAWNED, Double.NaN, 0, 0, 1, 0, 0, 0).playable());
		assertFalse(new FxEventS2C(1, FxEventS2C.Kind.CAR_SPAWNED, 0, Double.POSITIVE_INFINITY, 0, 1, 0, 0, 0)
				.playable());
		assertFalse(new FxEventS2C(1, FxEventS2C.Kind.BALL_SPAWNED, 0, 0, 0, Float.NaN, 0, 0, 0).playable());
	}
}
