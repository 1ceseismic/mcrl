package dev.mcrl.net;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;
import dev.mcrl.sim.Controls;
import dev.mcrl.sim.InputFrame;

public final class NetPayloads {
	// Retired hello field; version 1 peers still decode it.
	private static final long RETIRED = 0;

	private NetPayloads() {
	}

	public record HelloS2C(int protocol, int abi, long buildHash, long serverTick, int tickRate, int snapInterval,
			long arenaMeshes) implements CustomPacketPayload {
		public static final Type<HelloS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "hello"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HelloS2C> CODEC = StreamCodec.of((buf, h) -> {
			buf.writeVarInt(h.protocol);
			buf.writeVarInt(h.abi);
			buf.writeLong(h.buildHash);
			buf.writeVarLong(h.serverTick);
			buf.writeVarInt(h.tickRate);
			buf.writeVarInt(h.snapInterval);
			buf.writeLong(RETIRED);
			buf.writeLong(h.arenaMeshes);
		}, buf -> new HelloS2C(buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readVarLong(), buf.readVarInt(),
				buf.readVarInt(), buf.skipBytes(Long.BYTES).readLong()));

		@Override
		public Type<HelloS2C> type() {
			return TYPE;
		}
	}

	public record HelloAckC2S(int protocol, int abi, long buildHash, NetMode mode, long arenaMeshes)
			implements CustomPacketPayload {
		public static final Type<HelloAckC2S> TYPE = new Type<>(
				Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "hello_ack"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HelloAckC2S> CODEC = StreamCodec.of((buf, h) -> {
			buf.writeVarInt(h.protocol);
			buf.writeVarInt(h.abi);
			buf.writeLong(h.buildHash);
			buf.writeByte(h.mode.ordinal());
			buf.writeLong(RETIRED);
			buf.writeLong(h.arenaMeshes);
		}, buf -> new HelloAckC2S(buf.readVarInt(), buf.readVarInt(), buf.readLong(),
				NetMode.of(buf.readUnsignedByte()), buf.skipBytes(Long.BYTES).readLong()));

		@Override
		public Type<HelloAckC2S> type() {
			return TYPE;
		}
	}

	public record InputC2S(long firstTick, int epoch, Controls[] ticks) implements CustomPacketPayload {
		public static final Type<InputC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "input"));
		public static final StreamCodec<RegistryFriendlyByteBuf, InputC2S> CODEC = StreamCodec.of((buf, in) -> {
			buf.writeVarLong(in.firstTick);
			buf.writeByte(in.epoch);
			buf.writeByte(in.ticks.length);
			for (Controls c : in.ticks) {
				writeControls(buf, c);
			}
		}, buf -> {
			long first = buf.readVarLong();
			int epoch = buf.readUnsignedByte();
			int n = buf.readUnsignedByte();
			if (n < 1 || n > NetProtocol.MAX_INPUT_TICKS) {
				throw new DecoderException("mcrl:input with " + n + " ticks (1.." + NetProtocol.MAX_INPUT_TICKS + ")");
			}
			Controls[] ticks = new Controls[n];
			for (int i = 0; i < n; i++) {
				ticks[i] = readControls(buf);
			}
			return new InputC2S(first, epoch, ticks);
		});

		public InputC2S {
			if (ticks.length < 1 || ticks.length > NetProtocol.MAX_INPUT_TICKS) {
				throw new IllegalArgumentException("an input carries 1.." + NetProtocol.MAX_INPUT_TICKS + " ticks, not "
						+ ticks.length);
			}
			if (epoch < 0 || epoch > 255) {
				throw new IllegalArgumentException("an input epoch is 0..255, not " + epoch);
			}
			ticks = ticks.clone();
		}

		@Override
		public Controls[] ticks() {
			return ticks.clone();
		}

		public int count() {
			return ticks.length;
		}

		public Controls tick(int i) {
			return ticks[i];
		}

		@Override
		public Type<InputC2S> type() {
			return TYPE;
		}
	}

	public record SnapshotS2C(byte[] data, long recvNanos) implements CustomPacketPayload {
		public static final Type<SnapshotS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "snap"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotS2C> CODEC = StreamCodec.of(
				(buf, s) -> buf.writeByteArray(s.data), buf -> new SnapshotS2C(buf.readByteArray(), System.nanoTime()));

		public SnapshotS2C(byte[] data) {
			this(data, 0);
		}

		@Override
		public Type<SnapshotS2C> type() {
			return TYPE;
		}
	}

	public static void writeControls(ByteBuf buf, Controls c) {
		buf.writeByte(InputFrame.quantise(c.throttle()));
		buf.writeByte(InputFrame.quantise(c.steer()));
		buf.writeByte(InputFrame.quantise(c.pitch()));
		buf.writeByte(InputFrame.quantise(c.yaw()));
		buf.writeByte(InputFrame.quantise(c.roll()));
		buf.writeByte((c.jump() ? InputFrame.JUMP : 0) | (c.boost() ? InputFrame.BOOST : 0)
				| (c.handbrake() ? InputFrame.HANDBRAKE : 0));
	}

	public static Controls readControls(ByteBuf buf) {
		float throttle = InputFrame.axis(buf.readByte()), steer = InputFrame.axis(buf.readByte());
		float pitch = InputFrame.axis(buf.readByte()), yaw = InputFrame.axis(buf.readByte());
		float roll = InputFrame.axis(buf.readByte());
		int b = buf.readUnsignedByte();
		return new Controls(throttle, steer, pitch, yaw, roll, (b & InputFrame.JUMP) != 0, (b & InputFrame.BOOST) != 0,
				(b & InputFrame.HANDBRAKE) != 0);
	}

	static void registerTypes() {
		PayloadTypeRegistry.clientboundPlay().register(HelloS2C.TYPE, HelloS2C.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HelloAckC2S.TYPE, HelloAckC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(InputC2S.TYPE, InputC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SnapshotS2C.TYPE, SnapshotS2C.CODEC);
	}
}
