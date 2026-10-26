package dev.mcrl.net;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import dev.mcrl.Mcrl;
import dev.mcrl.match.MatchBlock;
import dev.mcrl.match.MatchSize;

public final class MatchPayloads {
	private MatchPayloads() {
	}

	public record MatchS2C(byte[] block) implements CustomPacketPayload {
		public static final Type<MatchS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "match"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MatchS2C> CODEC = StreamCodec.of(
				(buf, m) -> buf.writeByteArray(m.block), buf -> new MatchS2C(buf.readByteArray(MatchBlock.BYTES)));

		public MatchS2C {
			block = block.clone();
		}

		@Override
		public byte[] block() {
			return block.clone();
		}

		@Override
		public Type<MatchS2C> type() {
			return TYPE;
		}
	}

	public record SlotInfo(int index, int team, int carEntityId, String name, boolean bot) {
	}

	public record MatchSetupS2C(@Nullable MatchSize size, int ox, int oy, int oz, float[] padXyz, long padBigBits,
			List<SlotInfo> slots) implements CustomPacketPayload {
		public static final Type<MatchSetupS2C> TYPE = new Type<>(
				Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "match_setup"));
		private static final int MAX_SLOTS = 6, MAX_PAD_FLOATS = 3 * MatchBlock.PADS;
		public static final StreamCodec<RegistryFriendlyByteBuf, MatchSetupS2C> CODEC = StreamCodec.of((buf, s) -> {
			buf.writeVarInt(s.size == null ? -1 : s.size.ordinal());
			buf.writeVarInt(s.ox);
			buf.writeVarInt(s.oy);
			buf.writeVarInt(s.oz);
			buf.writeVarInt(s.padXyz.length);
			for (float f : s.padXyz) {
				buf.writeFloat(f);
			}
			buf.writeLong(s.padBigBits);
			buf.writeVarInt(s.slots.size());
			for (SlotInfo i : s.slots) {
				buf.writeVarInt(i.index());
				buf.writeVarInt(i.team());
				buf.writeVarInt(i.carEntityId());
				buf.writeUtf(i.name(), 64);
				buf.writeBoolean(i.bot());
			}
		}, buf -> {
			int size = buf.readVarInt();
			MatchSize[] all = MatchSize.values();
			int ox = buf.readVarInt(), oy = buf.readVarInt(), oz = buf.readVarInt();
			int n = buf.readVarInt();
			if (n < 0 || n > MAX_PAD_FLOATS) {
				throw new IllegalArgumentException("match setup: " + n + " pad coordinates");
			}
			float[] xyz = new float[n];
			for (int i = 0; i < n; i++) {
				xyz[i] = buf.readFloat();
			}
			long big = buf.readLong();
			int m = buf.readVarInt();
			if (m < 0 || m > MAX_SLOTS) {
				throw new IllegalArgumentException("match setup: " + m + " slots");
			}
			List<SlotInfo> slots = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				slots.add(new SlotInfo(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64),
						buf.readBoolean()));
			}
			return new MatchSetupS2C(size >= 0 && size < all.length ? all[size] : null, ox, oy, oz, xyz, big, slots);
		});

		public MatchSetupS2C {
			padXyz = padXyz.clone();
			slots = List.copyOf(slots);
		}

		@Override
		public float[] padXyz() {
			return padXyz.clone();
		}

		@Override
		public Type<MatchSetupS2C> type() {
			return TYPE;
		}
	}

	public record MatchEventS2C(@Nullable Kind kind, int team, int entityId, int pad, float speedUu, String scorer,
			String assist, double x, double y, double z) implements CustomPacketPayload {
		public enum Kind { GOAL, PICKUP, END }

		static @Nullable Kind kind(int i) {
			Kind[] all = Kind.values();
			return i >= 0 && i < all.length ? all[i] : null;
		}

		public static final Type<MatchEventS2C> TYPE = new Type<>(
				Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "match_event"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MatchEventS2C> CODEC = StreamCodec.of((buf, e) -> {
			buf.writeVarInt(e.kind.ordinal());
			buf.writeVarInt(e.team);
			buf.writeVarInt(e.entityId);
			buf.writeVarInt(e.pad);
			buf.writeFloat(e.speedUu);
			buf.writeUtf(e.scorer, 64);
			buf.writeUtf(e.assist, 64);
			buf.writeDouble(e.x);
			buf.writeDouble(e.y);
			buf.writeDouble(e.z);
		}, buf -> new MatchEventS2C(kind(buf.readVarInt()), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readFloat(), buf.readUtf(64), buf.readUtf(64), buf.readDouble(), buf.readDouble(), buf.readDouble()));

		public boolean known() {
			return kind != null;
		}

		public static MatchEventS2C goal(int team, int carEntityId, float speedUu, String scorer, String assist, double x,
				double y, double z) {
			return new MatchEventS2C(Kind.GOAL, team, carEntityId, -1, speedUu, scorer, assist, x, y, z);
		}

		public static MatchEventS2C pickup(int pad, int carEntityId) {
			return new MatchEventS2C(Kind.PICKUP, -1, carEntityId, pad, 0f, "", "", 0, 0, 0);
		}

		public static MatchEventS2C end(int winner) {
			return new MatchEventS2C(Kind.END, winner, -1, -1, 0f, "", "", 0, 0, 0);
		}

		@Override
		public Type<MatchEventS2C> type() {
			return TYPE;
		}
	}

	public static void send(ServerPlayer p, CustomPacketPayload payload) {
		if (ServerPlayNetworking.canSend(p, payload.type())) {
			ServerPlayNetworking.send(p, payload);
		}
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(MatchS2C.TYPE, MatchS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MatchSetupS2C.TYPE, MatchSetupS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MatchEventS2C.TYPE, MatchEventS2C.CODEC);
	}
}
