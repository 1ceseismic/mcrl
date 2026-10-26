package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import dev.mcrl.net.McrlPayloads.CarChoiceC2S;
import dev.mcrl.net.NetPayloads.HelloAckC2S;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.sim.Controls;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NetFuzzTest {
	private static final int CASES = 1000;

	private static RegistryFriendlyByteBuf buf() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	private static <T> byte[] encode(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf b = buf();
		codec.encode(b, value);
		byte[] out = new byte[b.readableBytes()];
		b.readBytes(out);
		return out;
	}

	private static byte[] input(Random r, List<byte[]> valid) {
		if (r.nextBoolean()) {
			byte[] b = new byte[r.nextInt(300)];
			r.nextBytes(b);
			return b;
		}
		byte[] b = valid.get(r.nextInt(valid.size())).clone();
		switch (r.nextInt(3)) {
			case 0 -> {
				for (int i = 0, n = 1 + r.nextInt(4); i < n && b.length > 0; i++) {
					b[r.nextInt(b.length)] = (byte) r.nextInt(256);
				}
			}
			case 1 -> b = Arrays.copyOf(b, r.nextInt(b.length + 1));
			default -> {
				byte[] tail = new byte[1 + r.nextInt(64)];
				r.nextBytes(tail);
				byte[] longer = Arrays.copyOf(b, b.length + tail.length);
				System.arraycopy(tail, 0, longer, b.length, tail.length);
				b = longer;
			}
		}
		return b;
	}

	private static <T> void fuzz(String name, StreamCodec<RegistryFriendlyByteBuf, T> codec, List<T> samples,
			Consumer<T> check) {
		List<byte[]> valid = samples.stream().map(v -> encode(codec, v)).toList();
		Random r = new Random(name.hashCode());
		int decoded = 0;
		for (int i = 0; i < CASES; i++) {
			byte[] in = input(r, valid);
			T value;
			try {
				value = codec.decode(new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(in), RegistryAccess.EMPTY));
			} catch (RuntimeException e) {
				if (!malformed(e)) {
					fail(name + " case " + i + " (" + Arrays.toString(in) + "): " + e);
				}
				continue;
			}
			check.accept(value);
			decoded++;
		}
		assertTrue(decoded > 0 && decoded < CASES, name + ": " + decoded + " of " + CASES + " decoded");
	}

	private static boolean malformed(RuntimeException e) {
		return e instanceof DecoderException || e instanceof IndexOutOfBoundsException
				|| e.getClass() == RuntimeException.class && String.valueOf(e.getMessage()).endsWith(" too big");
	}

	private static void inRange(Controls c) {
		for (float a : new float[] {c.throttle(), c.steer(), c.pitch(), c.yaw(), c.roll()}) {
			assertTrue(a >= -1 && a <= 1, "axis " + a);
		}
	}

	@Test
	void randomC2SBytesOnlyFailToDecode() {
		Controls full = new Controls(1, -1, 0.5f, -0.5f, 0.25f, true, true, true);
		fuzz("input", InputC2S.CODEC, List.of(new InputC2S(7, 3, new Controls[] {Controls.NEUTRAL}),
				new InputC2S(1L << 40, 255, new Controls[] {full, Controls.NEUTRAL, full})), in -> {
					assertTrue(in.count() >= 1 && in.count() <= NetProtocol.MAX_INPUT_TICKS, "count " + in.count());
					assertTrue(in.epoch() >= 0 && in.epoch() <= 255, "epoch " + in.epoch());
					for (Controls c : in.ticks()) {
						inRange(c);
					}
				});
		fuzz("car choice", CarChoiceC2S.CODEC, List.of(new CarChoiceC2S("Body_Octane", 0, 0),
				new CarChoiceC2S("a".repeat(64), 6, 1)), c -> assertTrue(c.bodyId().length() <= 64, c.bodyId()));
		fuzz("hello ack", HelloAckC2S.CODEC, List.of(new HelloAckC2S(NetProtocol.VERSION, 6, 42, NetMode.PREDICT, 9),
				new HelloAckC2S(1, 1, -1, NetMode.NONE, 0)), h -> assertTrue(h.mode() != null, "mode"));
	}
}
