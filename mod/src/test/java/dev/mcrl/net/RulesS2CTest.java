package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import dev.mcrl.net.McrlPayloads.RulesS2C;

class RulesS2CTest {
	private static RegistryFriendlyByteBuf buf() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	@Test
	void roundtrip_keeps_every_rule() {
		Map<String, Boolean> values = new LinkedHashMap<>();
		values.put("mcrl:step_up", false);
		values.put("mcrl:car_damage", true);
		values.put("mcrl:car_demos", true);
		values.put("mcrl:explosion_demos", false);
		RulesS2C r = new RulesS2C(values, true);
		RegistryFriendlyByteBuf b = buf();
		RulesS2C.CODEC.encode(b, r);
		RulesS2C d = RulesS2C.CODEC.decode(b);
		assertEquals(r, d);
		assertEquals(0, b.readableBytes(), "the whole payload read");
		assertEquals(List.copyOf(values.keySet()), List.copyOf(d.values().keySet()), "in order");
		assertTrue(d.canEdit());
		assertEquals(Boolean.TRUE, d.get("mcrl:car_demos"));
		assertEquals(Boolean.FALSE, d.get("mcrl:explosion_demos"));
		assertNull(d.get("mcrl:nope"));
		assertFalse(d.stepUp());
		assertTrue(d.carDamage());
	}
}
