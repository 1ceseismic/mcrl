package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import dev.mcrl.entity.CarChoice;
import dev.mcrl.net.McrlPayloads.CarChoiceC2S;

class CarChoiceC2STest {
	private static RegistryFriendlyByteBuf buf() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	@Test
	void roundTrips() {
		RegistryFriendlyByteBuf b = buf();
		CarChoiceC2S c = new CarChoiceC2S("Body_Test", 3, 1);
		CarChoiceC2S.CODEC.encode(b, c);
		assertEquals(c, CarChoiceC2S.CODEC.decode(b));
		assertEquals(0, b.readableBytes());
		assertEquals(new CarChoice("Body_Test", 3, 1), c.choice());
	}

	@Test
	void longIdFailsToDecode() {
		RegistryFriendlyByteBuf b = buf();
		b.writeUtf("a".repeat(65));
		b.writeVarInt(0);
		b.writeVarInt(0);
		assertThrows(RuntimeException.class, () -> CarChoiceC2S.CODEC.decode(b));
	}

	@Test
	void validity() {
		assertTrue(CarChoice.DEFAULT.valid());
		assertTrue(new CarChoice("Body_Octane", 6, 1).valid());
		assertTrue(new CarChoice("a".repeat(64), 0, 0).valid());
		assertFalse(new CarChoice("a".repeat(65), 0, 0).valid());
		assertFalse(new CarChoice("../x", 0, 0).valid());
		assertFalse(new CarChoice("a/b", 0, 0).valid());
		assertFalse(new CarChoice("Body_A", 7, 0).valid());
		assertFalse(new CarChoice("Body_A", -1, 0).valid());
		assertFalse(new CarChoice("Body_A", 0, 2).valid());
		assertFalse(new CarChoice(null, 0, 0).valid());
	}
}
