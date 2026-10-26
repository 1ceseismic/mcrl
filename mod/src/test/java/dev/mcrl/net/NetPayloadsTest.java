package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarLong;

import dev.mcrl.net.NetPayloads.HelloAckC2S;
import dev.mcrl.net.NetPayloads.HelloS2C;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.sim.Controls;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.SimClock;

class NetPayloadsTest {
	private static RegistryFriendlyByteBuf buf() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	private static Controls tick(int i) {
		return new Controls((i % 9 - 4) / 4f, (i % 5 - 2) / 2f, (i % 3 - 1), -0.5f, 0.25f, i % 2 == 0, i % 3 == 0,
				i % 4 == 0);
	}

	private static float q(float v) {
		return InputFrame.axis(InputFrame.quantise(v));
	}

	@Test
	void inputCountBounds() {
		assertThrows(IllegalArgumentException.class, () -> new InputC2S(1, 0, new Controls[0]));
		assertThrows(IllegalArgumentException.class, () -> new InputC2S(1, 0, new Controls[33]));
		assertThrows(IllegalArgumentException.class, () -> new InputC2S(1, 256, new Controls[1]), "epoch 0..255");
		for (int n : new int[] {0, 33}) {
			RegistryFriendlyByteBuf b = buf();
			b.writeVarLong(1);
			b.writeByte(3);
			b.writeByte(n);
			for (int i = 0; i < n; i++) {
				NetPayloads.writeControls(b, Controls.NEUTRAL);
			}
			assertThrows(DecoderException.class, () -> InputC2S.CODEC.decode(b), "n = " + n);
		}

		Controls[] ticks = new Controls[32];
		for (int i = 0; i < 32; i++) {
			ticks[i] = tick(i);
		}
		RegistryFriendlyByteBuf b = buf();
		InputC2S.CODEC.encode(b, new InputC2S(1L << 40, 255, ticks));
		assertEquals(VarLong.getByteSize(1L << 40) + 1 + 1 + 32 * 6, b.readableBytes(),
				"varlong firstTick, u8 epoch, u8 n, n × 6 B");
		InputC2S in = InputC2S.CODEC.decode(b);
		assertEquals(0, b.readableBytes());
		assertEquals(1L << 40, in.firstTick());
		assertEquals(255, in.epoch());
		assertEquals(32, in.count());
		for (int i = 0; i < 32; i++) {
			Controls w = ticks[i];
			assertEquals(new Controls(q(w.throttle()), q(w.steer()), q(w.pitch()), q(w.yaw()), q(w.roll()), w.jump(),
					w.boost(), w.handbrake()), in.tick(i), "tick " + i);
		}
	}

	@Test
	void helloRoundTripsWithTheProtocolFirst() {
		HelloS2C h = new HelloS2C(NetProtocol.VERSION, 6, 0x0123_4567_89AB_CDEFL, 987_654L, SimClock.TICK_RATE,
				NetProtocol.SNAP_INTERVAL_TICKS, -0x1234_5678_9ABCL);
		RegistryFriendlyByteBuf b = buf();
		HelloS2C.CODEC.encode(b, h);
		assertEquals(NetProtocol.VERSION, b.getByte(b.readerIndex()), "the protocol is the first byte");
		assertEquals(h, HelloS2C.CODEC.decode(b));
		assertEquals(0, b.readableBytes());

		for (NetMode m : NetMode.values()) {
			HelloAckC2S a = new HelloAckC2S(NetProtocol.VERSION, 6, -5L, m, 77L);
			RegistryFriendlyByteBuf ab = buf();
			HelloAckC2S.CODEC.encode(ab, a);
			assertEquals(a, HelloAckC2S.CODEC.decode(ab));
			assertEquals(0, ab.readableBytes());
		}
		RegistryFriendlyByteBuf unknown = buf();
		unknown.writeVarInt(1).writeVarInt(6).writeLong(0).writeByte(9).writeLong(0).writeLong(0);
		assertEquals(NetMode.NONE, HelloAckC2S.CODEC.decode(unknown).mode(), "a mode this build does not know");
	}
}
