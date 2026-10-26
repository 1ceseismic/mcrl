package dev.mcrl.net;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRule;

import dev.mcrl.Mcrl;
import dev.mcrl.McrlGameRules;
import dev.mcrl.entity.CarChoice;
import dev.mcrl.entity.CarChoices;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.math.Pose;

public final class McrlPayloads {
	private McrlPayloads() {
	}

	public record ExitCarC2S() implements CustomPacketPayload {
		public static final ExitCarC2S INSTANCE = new ExitCarC2S();
		public static final Type<ExitCarC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "exit_car"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ExitCarC2S> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<ExitCarC2S> type() {
			return TYPE;
		}
	}

	public record ResetCarC2S() implements CustomPacketPayload {
		public static final ResetCarC2S INSTANCE = new ResetCarC2S();
		public static final Type<ResetCarC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "reset_car"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ResetCarC2S> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<ResetCarC2S> type() {
			return TYPE;
		}
	}

	public record CarChoiceC2S(String bodyId, int preset, int team) implements CustomPacketPayload {
		public static final Type<CarChoiceC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "car_choice"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CarChoiceC2S> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), CarChoiceC2S::bodyId, ByteBufCodecs.VAR_INT, CarChoiceC2S::preset,
				ByteBufCodecs.VAR_INT, CarChoiceC2S::team, CarChoiceC2S::new);

		public CarChoiceC2S(CarChoice c) {
			this(c.bodyId(), c.preset(), c.team());
		}

		public CarChoice choice() {
			return new CarChoice(bodyId, preset, team);
		}

		@Override
		public Type<CarChoiceC2S> type() {
			return TYPE;
		}
	}

	public record KnockS2C(int entityId, double x, double y, double z, double vx, double vy, double vz)
			implements CustomPacketPayload {
		public static final Type<KnockS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "knock"));
		public static final StreamCodec<RegistryFriendlyByteBuf, KnockS2C> CODEC = StreamCodec.of((buf, k) -> {
			buf.writeVarInt(k.entityId);
			buf.writeDouble(k.x);
			buf.writeDouble(k.y);
			buf.writeDouble(k.z);
			buf.writeDouble(k.vx);
			buf.writeDouble(k.vy);
			buf.writeDouble(k.vz);
		}, buf -> new KnockS2C(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
				buf.readDouble(), buf.readDouble()));

		@Override
		public Type<KnockS2C> type() {
			return TYPE;
		}
	}

	public record RulesS2C(Map<String, Boolean> values, boolean canEdit) implements CustomPacketPayload {
		public static final Type<RulesS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "rules"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RulesS2C> CODEC = StreamCodec.of((buf, r) -> {
			buf.writeVarInt(r.values.size());
			for (Map.Entry<String, Boolean> e : r.values.entrySet()) {
				buf.writeUtf(e.getKey());
				buf.writeBoolean(e.getValue());
			}
			buf.writeBoolean(r.canEdit);
		}, buf -> {
			int n = buf.readVarInt();
			Map<String, Boolean> values = new LinkedHashMap<>();
			for (int i = 0; i < n; i++) {
				values.put(buf.readUtf(), buf.readBoolean());
			}
			return new RulesS2C(values, buf.readBoolean());
		});
		private static final String STEP_UP = Mcrl.MOD_ID + ":step_up", CAR_DAMAGE = Mcrl.MOD_ID + ":car_damage";

		public RulesS2C {
			values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		}

		public @Nullable Boolean get(String ruleId) {
			return values.get(ruleId);
		}

		public boolean stepUp() {
			return Boolean.TRUE.equals(get(STEP_UP));
		}

		public boolean carDamage() {
			return Boolean.TRUE.equals(get(CAR_DAMAGE));
		}

		@Override
		public Type<RulesS2C> type() {
			return TYPE;
		}
	}

	public record FxEventS2C(int entityId, @Nullable Kind kind, double x, double y, double z, float qw, float qx, float qy,
			float qz, int attackerId) implements CustomPacketPayload {
		public enum Kind { CAR_SPAWNED, CAR_LEFT, BALL_SPAWNED, CAR_DEMOLISHED }

		static @Nullable Kind kind(int i) {
			Kind[] all = Kind.values();
			return i >= 0 && i < all.length ? all[i] : null;
		}

		public static final Type<FxEventS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "fx_event"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FxEventS2C> CODEC = StreamCodec.of((buf, e) -> {
			buf.writeVarInt(e.entityId);
			buf.writeVarInt(e.kind.ordinal());
			buf.writeDouble(e.x);
			buf.writeDouble(e.y);
			buf.writeDouble(e.z);
			buf.writeFloat(e.qw);
			buf.writeFloat(e.qx);
			buf.writeFloat(e.qy);
			buf.writeFloat(e.qz);
			buf.writeVarInt(e.attackerId);
		}, buf -> new FxEventS2C(buf.readVarInt(), kind(buf.readVarInt()), buf.readDouble(), buf.readDouble(),
				buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt()));

		public FxEventS2C(int entityId, @Nullable Kind kind, double x, double y, double z, float qw, float qx, float qy,
				float qz) {
			this(entityId, kind, x, y, z, qw, qx, qy, qz, -1);
		}

		public FxEventS2C(int entityId, Kind kind, Pose p) {
			this(entityId, kind, p, -1);
		}

		public FxEventS2C(int entityId, Kind kind, Pose p, int attackerId) {
			this(entityId, kind, p.x(), p.y(), p.z(), p.qw(), p.qx(), p.qy(), p.qz(), attackerId);
		}

		public Pose pose() {
			return new Pose(x, y, z, qw, qx, qy, qz);
		}

		public boolean playable() {
			return kind != null && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(qw)
					&& Float.isFinite(qx) && Float.isFinite(qy) && Float.isFinite(qz);
		}

		@Override
		public Type<FxEventS2C> type() {
			return TYPE;
		}
	}

	public static void sendFxEvent(Entity entity, FxEventS2C.Kind kind, Pose pose, @Nullable ServerPlayer actor) {
		sendFxEvent(entity, kind, pose, actor, -1);
	}

	public static void sendFxEvent(Entity entity, FxEventS2C.Kind kind, Pose pose, @Nullable ServerPlayer actor,
			int attackerId) {
		FxEventS2C e = new FxEventS2C(entity.getId(), kind, pose, attackerId);
		Set<ServerPlayer> to = new LinkedHashSet<>(PlayerLookup.tracking(entity));
		if (actor != null) {
			to.add(actor);
		}
		for (ServerPlayer p : to) {
			if (ServerPlayNetworking.canSend(p, FxEventS2C.TYPE)) {
				ServerPlayNetworking.send(p, e);
			}
		}
	}

	public static boolean canEditRules(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		CommandNode<CommandSourceStack> node = server == null ? null
				: server.getCommands().getDispatcher().getRoot().getChild("gamerule");
		if (node == null) {
			return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
		}
		return node.canUse(player.createCommandSourceStack());
	}

	public static void sendRules(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, RulesS2C.TYPE)) {
			ServerPlayNetworking.send(player, new RulesS2C(ruleValues(player.level().getServer(), null, false),
					canEditRules(player)));
		}
	}

	public static void broadcastRules(MinecraftServer server, String changedId, boolean newValue) {
		Map<String, Boolean> values = ruleValues(server, changedId, newValue);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, RulesS2C.TYPE)) {
				ServerPlayNetworking.send(p, new RulesS2C(values, canEditRules(p)));
			}
		}
	}

	private static Map<String, Boolean> ruleValues(MinecraftServer server, @Nullable String changedId, boolean newValue) {
		Map<String, Boolean> values = new LinkedHashMap<>();
		for (Map.Entry<String, GameRule<Boolean>> e : McrlGameRules.ALL.entrySet()) {
			values.put(e.getKey(), e.getKey().equals(changedId) ? newValue : server.getGameRules().get(e.getValue()));
		}
		return values;
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(ExitCarC2S.TYPE, ExitCarC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ResetCarC2S.TYPE, ResetCarC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CarChoiceC2S.TYPE, CarChoiceC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(KnockS2C.TYPE, KnockS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RulesS2C.TYPE, RulesS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(FxEventS2C.TYPE, FxEventS2C.CODEC);
		MatchPayloads.register();
		NetPayloads.registerTypes();
		ServerPlayNetworking.registerGlobalReceiver(ExitCarC2S.TYPE,
				(payload, ctx) -> ctx.server().execute(() -> handleExit(ctx.player())));
		ServerPlayNetworking.registerGlobalReceiver(ResetCarC2S.TYPE,
				(payload, ctx) -> ctx.server().execute(() -> handleReset(ctx.player())));
		ServerPlayNetworking.registerGlobalReceiver(CarChoiceC2S.TYPE,
				(payload, ctx) -> ctx.server().execute(() -> CarChoices.receive(ctx.player(), payload.choice())));
	}

	public static void handleExit(ServerPlayer player) {
		if (player.getVehicle() instanceof CarEntity car && !car.matchOwned()) {
			player.stopRiding();
		}
	}

	public static final int RESET_COOLDOWN_TICKS = 10;
	private static final Map<CarEntity, Long> LAST_RESET = new WeakHashMap<>();

	public static void handleReset(ServerPlayer player) {
		if (!(player.getVehicle() instanceof CarEntity car)) {
			return;
		}
		long now = car.level().getGameTime();
		Long last = LAST_RESET.get(car);
		if (last != null && now - last < RESET_COOLDOWN_TICKS && now >= last) {
			return;
		}
		if (car.resetUpright()) {
			LAST_RESET.put(car, now);
			sendFxEvent(car, FxEventS2C.Kind.CAR_SPAWNED, car.pose(), player);
		}
	}
}
