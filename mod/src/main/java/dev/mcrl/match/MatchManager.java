package dev.mcrl.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.SafeBoot;
import dev.mcrl.bot.BotFiller;
import dev.mcrl.command.McrlCommands;
import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarChoice;
import dev.mcrl.entity.CarChoices;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.match.Match.Action;
import dev.mcrl.net.MatchPayloads;
import dev.mcrl.net.MatchPayloads.MatchEventS2C;
import dev.mcrl.net.MatchPayloads.MatchS2C;
import dev.mcrl.net.MatchPayloads.MatchSetupS2C;
import dev.mcrl.net.MatchPayloads.SlotInfo;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.net.McrlPayloads.FxEventS2C;
import dev.mcrl.net.NetMode;
import dev.mcrl.net.NetPeers;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.sim.SoccarState;

public final class MatchManager {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");

	public enum JoinAs { AUTO, BLUE, ORANGE, SPECTATE }

	public record Result(boolean ok, Component message) {
		static Result ok(Component m) {
			return new Result(true, m);
		}

		static Result fail(Component m) {
			return new Result(false, m);
		}
	}

	// RocketSim's ball rest height, 93.15 UU.
	public static final double BALL_REST_Y = StadiumConst.ORIGIN.getY() + 93.15 / 50;

	public static final String BOT_NAME = "Bot";

	private static @Nullable ActiveMatch current;
	public static final int REMOUNT_TICKS = 40;
	private static final RandomGenerator RNG = new Random();
	private static final List<UUID> JOINED = new ArrayList<>();

	private record Remount(UUID player, UUID car, ResourceKey<Level> dimension, int until) {
	}

	private static final List<Remount> REMOUNTS = new ArrayList<>();
	private static volatile MatchBlock.@Nullable Header header;
	private static int viewDistanceSeen = -1;

	private MatchManager() {
	}

	public static void register() {
		ReturnPoint.register();
		ServerTickEvents.END_SERVER_TICK.register(MatchManager::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(MatchManager::reset);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			JOINED.clear();
			REMOUNTS.clear();
			viewDistanceSeen = -1;
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.getPlayer()));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(MatchManager::onDimensionChange);
		ServerPlayerEvents.AFTER_RESPAWN.register((before, after, alive) -> onRespawn(before, after));
	}

	public static MatchBlock.@Nullable Header header() {
		return header;
	}

	public static @Nullable ActiveMatch current() {
		return current;
	}

	public static MatchConfig config() {
		return MatchConfig.read(MatchConfig.file());
	}

	private static Component msg(String key, String fallback, Object... args) {
		return Component.translatableWithFallback(key, fallback, args);
	}

	private static Component teamName(int team) {
		return team == 0 ? msg("mcrl.match.team.blue", "Blue") : msg("mcrl.match.team.orange", "Orange");
	}

	public static Result create(ServerPlayer p, @Nullable MatchSize size) {
		if (current != null) {
			return Result.fail(msg("mcrl.match.exists", "A match is on already: /mcrl match join"));
		}
		if (!SafeBoot.physicsOn()) {
			return Result.fail(McrlCommands.physicsOffRefusal());
		}
		MinecraftServer server = p.level().getServer();
		ServerLevel stadium = server.getLevel(StadiumConst.KEY);
		if (stadium == null) {
			return Result.fail(msg("mcrl.match.no_stadium", "The stadium dimension is missing"));
		}
		try {
			ArenaMeshes.load();
		} catch (ArenaMeshes.Missing e) {
			return Result.fail(msg("mcrl.match.no_meshes", "No arena meshes: %s", e.getMessage()));
		}
		MatchSize sz = size != null ? size : config().defaultSize();
		StadiumTicket.loadField(stadium);
		clearStadium(stadium);
		SimHost host = SimHosts.getOrCreateIfOn(stadium, 0, 0);
		if (host == null) {
			return Result.fail(msg("mcrl.match.no_host", "The stadium's physics did not start (see the log)"));
		}
		ActiveMatch m = new ActiveMatch(new Match(sz, RNG), p.getUUID(), stadium);
		m.observedHost = host;
		current = m;
		BallEntity ball = McrlEntities.BALL.create(stadium, EntitySpawnReason.EVENT);
		if (ball != null) {
			ball.setMatchOwned(true);
			ball.setSimState(BallEntity.restingAt(0, BALL_REST_Y, 0));
			if (stadium.addFreshEntity(ball)) {
				m.ballEntityId = ball.getId();
				m.bodies.put(ball.getId(), ball);
			}
		}
		Result joined = join(p, JoinAs.AUTO);
		if (!joined.ok()) {
			reset(server);
			return joined;
		}
		LOG.info("mcrl: {} created a {} match", p.getName().getString(), sz.label());
		return Result.ok(msg("mcrl.match.created", "Created a %s match: /mcrl match join, then /mcrl match start",
				sz.label()));
	}

	public static Result join(ServerPlayer p, JoinAs as) {
		ActiveMatch m = current;
		if (m == null) {
			return Result.fail(msg("mcrl.match.none", "No match: /mcrl match create"));
		}
		UUID id = p.getUUID();
		if (m.slotOf(id) != null || (as == JoinAs.SPECTATE && m.spectatorSet().contains(id))) {
			return Result.fail(msg("mcrl.match.already_in", "You are in the match already"));
		}
		ServerLevel stadium = p.level().getServer().getLevel(StadiumConst.KEY);
		if (stadium == null) {
			return Result.fail(msg("mcrl.match.no_stadium", "The stadium dimension is missing"));
		}
		if (as == JoinAs.SPECTATE) {
			keepReturnPoint(p);
			p.stopRiding();
			Vec3 at = StadiumConst.SPECTATOR_POS;
			p.teleportTo(stadium, at.x, at.y, at.z, Set.of(), StadiumConst.SPECTATOR_YAW, StadiumConst.SPECTATOR_PITCH,
					true);
			p.setGameMode(GameType.SPECTATOR);
			m.spectatorSet().add(id);
			return Result.ok(msg("mcrl.match.spectating", "You are watching the match"));
		}
		int team = as == JoinAs.BLUE ? 0 : as == JoinAs.ORANGE ? 1 : autoTeam(m);
		Slot slot = openSlot(m, team);
		if (slot == null) {
			return Result.fail(openSlot(m, 1 - team) == null ? msg("mcrl.match.full", "Both teams are full")
					: msg("mcrl.match.team_full", "%s is full", teamName(team)));
		}
		SimHost host = m.host();
		if (host == null || host.soccarSpawns(team).isEmpty()) {
			return Result.fail(msg("mcrl.match.no_host", "The stadium's physics did not start (see the log)"));
		}
		CarChoice own = CarChoices.get(p);
		CarChoice choice = new CarChoice(own.bodyId(), own.preset(), team);
		CarEntity car = slot.bot() ? liveCar(m, slot.carEntityId()) : null;
		boolean fresh = car == null;
		if (car == null) {
			int n = m.size().perTeam();
			car = spawnCar(stadium, choice, host.soccarSpawns(team).get((slot.index() - team * n) % 4));
			if (car == null) {
				return Result.fail(msg("mcrl.match.car_failed", "Could not spawn your car"));
			}
			m.bodies.put(car.getId(), car);
		} else {
			car.applyChoice(choice);
		}
		if (slot.bot()) {
			BotFiller.onRelease(m, slot);
			if (car.isBot()) {
				car.setBot(null);
			}
		}
		keepReturnPoint(p);
		p.stopRiding();
		m.spectatorSet().remove(id);
		if (p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
			p.setGameMode(GameType.ADVENTURE);
		}
		p.teleportTo(stadium, car.getX(), car.getY(), car.getZ(), Set.of(), car.getYRot(), 0f, true);
		p.startRiding(car);
		m.set(slot.withPlayer(id, car.getId()));
		if (fresh) {
			McrlPayloads.sendFxEvent(car, FxEventS2C.Kind.CAR_SPAWNED, car.pose(), p);
		}
		return Result.ok(msg("mcrl.match.joined", "You joined %s", teamName(team)));
	}

	public static Result start(CommandSourceStack src) {
		ActiveMatch m = current;
		if (m == null) {
			return Result.fail(msg("mcrl.match.none", "No match: /mcrl match create"));
		}
		if (!allowed(src, m)) {
			return Result.fail(msg("mcrl.match.not_allowed", "Only the match's creator or an operator can do that"));
		}
		if (m.match().phase() != Match.Phase.LOBBY) {
			return Result.fail(msg("mcrl.match.started_already", "The match has started"));
		}
		SimHost host = m.host();
		if (host == null) {
			return Result.fail(msg("mcrl.match.no_host", "The stadium's physics did not start (see the log)"));
		}
		for (Slot s : m.slots()) {
			if (s.open() && !s.bot()) {
				BotFiller.onEmpty(m, s);
			}
		}
		run(m, m.match().start(host.latest().tick()), src.getServer());
		m.setupSent.clear();
		return Result.ok(msg("mcrl.match.started", "The match starts"));
	}

	public static Result leave(ServerPlayer p) {
		ActiveMatch m = current;
		if (m == null) {
			return Result.fail(msg("mcrl.match.none", "No match: /mcrl match create"));
		}
		UUID id = p.getUUID();
		Slot s = m.slotOf(id);
		if (s == null && !m.spectatorSet().remove(id)) {
			return Result.fail(msg("mcrl.match.not_in", "You are not in the match"));
		}
		p.stopRiding();
		if (s != null) {
			freeSlot(m, s, p.level().getServer());
		}
		returnPlayer(p);
		return Result.ok(msg("mcrl.match.left", "You left the match"));
	}

	public static Result end(CommandSourceStack src) {
		ActiveMatch m = current;
		if (m == null) {
			return Result.fail(msg("mcrl.match.none", "No match: /mcrl match create"));
		}
		if (!allowed(src, m)) {
			return Result.fail(msg("mcrl.match.not_allowed", "Only the match's creator or an operator can do that"));
		}
		SimHost host = m.host();
		if (m.match().phase() == Match.Phase.ENDED || host == null || host.isClosed()) {
			finish(src.getServer(), m);
			return Result.ok(msg("mcrl.match.end_done", "The match was ended"));
		}
		run(m, m.match().forfeit(host.latest().tick()), src.getServer());
		return Result.ok(msg("mcrl.match.end_done", "The match was ended"));
	}

	private static boolean allowed(CommandSourceStack src, ActiveMatch m) {
		ServerPlayer p = src.getPlayer();
		return (p != null && p.getUUID().equals(m.creator())) || Commands.LEVEL_GAMEMASTERS.check(src.permissions());
	}

	private static int autoTeam(ActiveMatch m) {
		int[] players = new int[2];
		for (Slot s : m.slots()) {
			if (!s.open()) {
				players[s.team()]++;
			}
		}
		int team = players[1] < players[0] ? 1 : 0;
		return openSlot(m, team) != null ? team : 1 - team;
	}

	private static @Nullable Slot openSlot(ActiveMatch m, int team) {
		for (Slot s : m.slots()) {
			if (s.team() == team && s.open()) {
				return s;
			}
		}
		return null;
	}

	private static @Nullable CarEntity liveCar(ActiveMatch m, int id) {
		return m.bodies.get(id) instanceof CarEntity c && !c.isRemoved() ? c : null;
	}

	private static @Nullable CarEntity spawnCar(ServerLevel stadium, CarChoice choice, CarState spawn) {
		CarEntity car = McrlEntities.CAR.create(stadium, EntitySpawnReason.EVENT);
		if (car == null) {
			return null;
		}
		car.setMatchOwned(true);
		car.applyChoice(choice);
		car.setSimState(spawn);
		return stadium.addFreshEntity(car) ? car : null;
	}

	private static void refill(ActiveMatch m) {
		Match.Phase ph = m.match().phase();
		if (ph == Match.Phase.LOBBY || ph == Match.Phase.ENDED) {
			return;
		}
		for (Slot s : m.slots()) {
			if (s.open() && !s.bot()) {
				BotFiller.onEmpty(m, s);
			}
		}
	}

	private static void freeSlot(ActiveMatch m, Slot s, MinecraftServer server) {
		CarEntity car = liveCar(m, s.carEntityId());
		if (car != null) {
			car.ejectPassengers();
			car.discard();
		}
		Slot empty = s.cleared();
		m.set(empty);
		Match.Phase ph = m.match().phase();
		if (ph != Match.Phase.LOBBY && ph != Match.Phase.ENDED) {
			BotFiller.onEmpty(m, empty);
		}
	}

	private static void keepReturnPoint(ServerPlayer p) {
		if (p.hasAttached(ReturnPoint.ATTACHMENT)) {
			return;
		}
		if (p.level().dimension().equals(StadiumConst.KEY)) {
			MinecraftServer server = p.level().getServer();
			LevelData.RespawnData spawn = server.getRespawnData();
			p.setAttached(ReturnPoint.ATTACHMENT, new ReturnPoint(spawn.dimension(), new Vec3(spawn.pos().getX() + 0.5,
					spawn.pos().getY(), spawn.pos().getZ() + 0.5), spawn.yaw(), spawn.pitch(), server.getDefaultGameType(),
					Optional.empty()));
			return;
		}
		Optional<UUID> car = p.getVehicle() instanceof CarEntity c && !c.matchOwned() ? Optional.of(c.getUUID())
				: Optional.empty();
		p.setAttached(ReturnPoint.ATTACHMENT, new ReturnPoint(p.level().dimension(), p.position(), p.getYRot(),
				p.getXRot(), p.gameMode.getGameModeForPlayer(), car));
	}

	public static void returnPlayer(ServerPlayer p) {
		ReturnPoint rp = p.removeAttached(ReturnPoint.ATTACHMENT);
		p.stopRiding();
		MinecraftServer server = p.level().getServer();
		ServerLevel to = rp != null ? server.getLevel(rp.dimension()) : null;
		if (rp != null && to != null) {
			p.teleportTo(to, rp.pos().x, rp.pos().y, rp.pos().z, Set.of(), rp.yRot(), rp.xRot(), true);
			p.setGameMode(rp.gameMode());
			rp.car().ifPresent(car -> REMOUNTS.add(new Remount(p.getUUID(), car, rp.dimension(),
					server.getTickCount() + REMOUNT_TICKS)));
			return;
		}
		LevelData.RespawnData spawn = server.getRespawnData();
		ServerLevel level = server.getLevel(spawn.dimension());
		if (level == null) {
			level = server.overworld();
		}
		p.teleportTo(level, spawn.pos().getX() + 0.5, spawn.pos().getY(), spawn.pos().getZ() + 0.5, Set.of(),
				spawn.yaw(), spawn.pitch(), true);
		p.setGameMode(server.getDefaultGameType());
	}

	public static void onJoin(ServerPlayer p) {
		if (p.level().dimension().equals(StadiumConst.KEY)) {
			sendViewRadius(p);
			JOINED.add(p.getUUID());
		}
	}

	public static void onDisconnect(ServerPlayer p) {
		ActiveMatch m = current;
		if (m == null) {
			return;
		}
		Slot s = m.slotOf(p.getUUID());
		if (s != null) {
			p.stopRiding();
			freeSlot(m, s, p.level().getServer());
		}
		m.spectatorSet().remove(p.getUUID());
	}

	public static void onRespawn(ServerPlayer before, ServerPlayer after) {
		ServerLevel from = before.level(), to = after.level();
		if (from.dimension().equals(StadiumConst.KEY) || to.dimension().equals(StadiumConst.KEY)) {
			onDimensionChange(after, from, to);
		}
	}

	public static void onDimensionChange(ServerPlayer p, ServerLevel from, ServerLevel to) {
		if (from.dimension().equals(StadiumConst.KEY) != to.dimension().equals(StadiumConst.KEY)) {
			sendViewRadius(p);
		}
		ActiveMatch m = current;
		if (m == null || !from.dimension().equals(StadiumConst.KEY) || to.dimension().equals(StadiumConst.KEY)) {
			return;
		}
		Slot s = m.slotOf(p.getUUID());
		boolean spectator = m.spectatorSet().remove(p.getUUID());
		if (s == null && !spectator) {
			return;
		}
		if (s != null) {
			freeSlot(m, s, p.level().getServer());
		}
		ReturnPoint rp = p.removeAttached(ReturnPoint.ATTACHMENT);
		if (rp != null) {
			p.setGameMode(rp.gameMode());
		}
	}

	public static void sendViewRadius(ServerPlayer p) {
		int server = p.level().getServer().getPlayerList().getViewDistance();
		int r = p.level().dimension().equals(StadiumConst.KEY) ? Math.max(server, StadiumConst.VIEW_CHUNKS) : server;
		p.connection.send(new ClientboundSetChunkCacheRadiusPacket(r));
	}

	private static void viewDistanceChanged(MinecraftServer server) {
		int vd = server.getPlayerList().getViewDistance();
		if (vd == viewDistanceSeen) {
			return;
		}
		ServerLevel stadium = viewDistanceSeen < 0 ? null : server.getLevel(StadiumConst.KEY);
		viewDistanceSeen = vd;
		if (stadium != null) {
			for (ServerPlayer p : stadium.players()) {
				sendViewRadius(p);
			}
		}
	}

	private static void handleJoins(MinecraftServer server) {
		for (UUID id : List.copyOf(JOINED)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || !p.level().dimension().equals(StadiumConst.KEY)) {
				continue;
			}
			ActiveMatch m = current;
			if (m != null && (m.slotOf(id) != null || m.spectatorSet().contains(id))) {
				continue;
			}
			if (p.getVehicle() instanceof CarEntity car) {
				p.stopRiding();
				car.discard();
			}
			if (m != null && m.match().phase() != Match.Phase.ENDED && join(p, JoinAs.SPECTATE).ok()) {
				continue;
			}
			returnPlayer(p);
		}
		JOINED.clear();
	}

	private static void remount(MinecraftServer server) {
		int now = server.getTickCount();
		REMOUNTS.removeIf(r -> {
			ServerPlayer p = server.getPlayerList().getPlayer(r.player());
			ServerLevel level = server.getLevel(r.dimension());
			if (p == null || level == null || now > r.until() || p.level() != level || p.isPassenger()) {
				return true;
			}
			if (level.getEntity(r.car()) instanceof CarEntity car && !car.isRemoved() && !car.isVehicle()) {
				p.startRiding(car);
				return true;
			}
			return false;
		});
	}

	public static void tick(MinecraftServer server) {
		viewDistanceChanged(server);
		if (!JOINED.isEmpty()) {
			handleJoins(server);
		}
		if (!REMOUNTS.isEmpty()) {
			remount(server);
		}
		ActiveMatch m = current;
		if (m == null) {
			return;
		}
		ServerLevel stadium = server.getLevel(StadiumConst.KEY);
		if (stadium == null) {
			reset(server);
			return;
		}
		StadiumTicket.renew(stadium);
		keepPlayersSafe(m, stadium);
		SimHost host = m.host();
		if (host == null) {
			return;
		}
		SimFrame f = host.latest();
		SoccarState s = f.soccar();
		if (s == null) {
			return;
		}
		List<Action> actions = new ArrayList<>();
		if (host != m.observedHost) {
			m.observedHost = host;
			m.kickoffsSeen = -1;
			m.bouncesSeen = f.ball() != null ? f.ball().bounces() : 0;
			m.padPickupsSeen = null;
			m.hitsSeen.clear();
			m.lastHit.clear();
			m.setupSent.clear();
			actions.addAll(m.match().hostRestarted(m.lastTick, f.tick()));
			refill(m);
		}
		noteHits(m, f);
		m.lastState = s;
		boolean floor = f.ball() != null && MatchRules.floorTouch(m.bouncesSeen, f.ball(), StadiumConst.ORIGIN.getY());
		if (f.ball() != null) {
			m.bouncesSeen = f.ball().bounces();
		}
		if (m.kickoffsSeen >= 0 && s.kickoffs() > m.kickoffsSeen) {
			kickoffFx(m, stadium, f);
		}
		m.kickoffsSeen = s.kickoffs();
		actions.addAll(m.match().observe(f.tick(), s, floor));
		m.lastTick = f.tick();
		if (abandoned(m, server)) {
			actions.addAll(m.match().forfeit(f.tick()));
		}
		run(m, actions, server);
		if (current == m) {
			broadcast(m, stadium, host, f, s);
		}
	}

	private static void keepPlayersSafe(ActiveMatch m, ServerLevel stadium) {
		MinecraftServer server = stadium.getServer();
		for (Slot s : m.slots()) {
			ServerPlayer p = s.player() == null ? null : server.getPlayerList().getPlayer(s.player());
			CarEntity car = liveCar(m, s.carEntityId());
			if (p != null && car != null && p.isAlive() && p.level() == stadium && p.getVehicle() != car) {
				p.stopRiding();
				p.teleportTo(stadium, car.getX(), car.getY(), car.getZ(), Set.of(), car.getYRot(), 0f, true);
				p.startRiding(car);
			}
		}
		Vec3 at = StadiumConst.SPECTATOR_POS;
		for (ServerPlayer p : List.copyOf(stadium.players())) {
			if (p.isAlive() && !p.isPassenger() && p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR
					&& p.getY() < StadiumConst.ORIGIN.getY() - 8) {
				p.teleportTo(stadium, at.x, at.y, at.z, Set.of(), StadiumConst.SPECTATOR_YAW, StadiumConst.SPECTATOR_PITCH,
						true);
				p.resetFallDistance();
			}
		}
	}

	private static void noteHits(ActiveMatch m, SimFrame f) {
		for (Slot sl : m.slots()) {
			SimFrame.CarSnap c = f.car(sl.carEntityId());
			if (c == null) {
				continue;
			}
			Integer seen = m.hitsSeen.put(sl.carEntityId(), c.ballHits());
			if (seen != null && seen.intValue() != c.ballHits()) {
				m.lastHit.put(sl.carEntityId(), f.tick());
			}
		}
	}

	private static void broadcast(ActiveMatch m, ServerLevel stadium, SimHost host, SimFrame f, SoccarState s) {
		Match match = m.match();
		long tick = f.tick();
		MatchBlock.Header h = new MatchBlock.Header(match.phase().ordinal(), match.overtime(), match.clockSeconds(tick),
				match.blue(), match.orange(), match.countdown(tick), s.ballOutOfPlay());
		header = h;
		MatchS2C block = new MatchS2C(MatchBlock.of(s, h).encode());
		List<MatchEventS2C> pickups = new ArrayList<>();
		int[] now = s.padPickups(), car = s.padCar(), seen = m.padPickupsSeen;
		m.padPickupsSeen = now;
		if (seen != null && seen.length == now.length) {
			for (int i = 0; i < now.length; i++) {
				if (now[i] != seen[i]) {
					pickups.add(MatchEventS2C.pickup(i, car[i]));
				}
			}
		}
		MatchSetupS2C setup = null;
		Set<UUID> here = new HashSet<>();
		for (ServerPlayer p : stadium.players()) {
			here.add(p.getUUID());
		}
		m.setupSent.retainAll(here);
		for (ServerPlayer p : stadium.players()) {
			if (m.setupSent.add(p.getUUID())) {
				if (setup == null) {
					setup = setup(m, host, stadium.getServer());
				}
				MatchPayloads.send(p, setup);
			}
			NetMode link = NetPeers.mode(p.getUUID());
			if (link == null || link == NetMode.NONE) {
				MatchPayloads.send(p, block);
			}
			for (MatchEventS2C e : pickups) {
				MatchPayloads.send(p, e);
			}
		}
	}

	private static MatchSetupS2C setup(ActiveMatch m, SimHost host, MinecraftServer server) {
		SimHost.PadLayout pads = host.padLayout();
		long big = 0;
		if (pads != null) {
			boolean[] b = pads.big();
			for (int i = 0; i < b.length && i < 64; i++) {
				big |= b[i] ? 1L << i : 0;
			}
		}
		List<SlotInfo> slots = new ArrayList<>();
		for (Slot s : m.slots()) {
			slots.add(new SlotInfo(s.index(), s.team(), s.carEntityId(), playerName(server, s), s.bot()));
		}
		BlockPos o = StadiumConst.ORIGIN;
		return new MatchSetupS2C(m.size(), o.getX(), o.getY(), o.getZ(), pads != null ? pads.xyz() : new float[0], big,
				slots);
	}

	private static String playerName(MinecraftServer server, Slot s) {
		if (s.bot()) {
			return BOT_NAME;
		}
		ServerPlayer p = s.player() == null ? null : server.getPlayerList().getPlayer(s.player());
		return p == null ? "" : p.getName().getString();
	}

	private static void toStadium(MinecraftServer server, CustomPacketPayload payload) {
		ServerLevel stadium = server.getLevel(StadiumConst.KEY);
		if (stadium != null) {
			for (ServerPlayer p : stadium.players()) {
				MatchPayloads.send(p, payload);
			}
		}
	}

	private static MatchEventS2C goalEvent(ActiveMatch m, MinecraftServer server, Match.Goal g) {
		int scorerCar = -1;
		String scorer = "";
		Map<Integer, Long> team = new HashMap<>();
		for (Slot s : m.slots()) {
			if (s.team() != g.team() || s.carEntityId() < 0) {
				continue;
			}
			if (s.carEntityId() == g.toucher()) {
				scorerCar = g.toucher();
				scorer = playerName(server, s);
			}
			Long t = m.lastHit.get(s.carEntityId());
			if (t != null) {
				team.put(s.carEntityId(), t);
			}
		}
		long goalTick = m.lastState != null ? m.lastState.goalTick() : -1;
		int assistCar = goalTick < 0 ? -1 : MatchRules.assist(team, scorerCar, goalTick);
		String assist = "";
		for (Slot s : m.slots()) {
			if (assistCar >= 0 && s.carEntityId() == assistCar) {
				assist = playerName(server, s);
			}
		}
		return MatchEventS2C.goal(g.team(), scorerCar, (float) g.speedUu(), scorer, assist, g.x(), g.y(), g.z());
	}

	private static boolean abandoned(ActiveMatch m, MinecraftServer server) {
		Match.Phase ph = m.match().phase();
		if (ph == Match.Phase.ENDED) {
			return false;
		}
		for (Slot s : m.slots()) {
			if (s.player() != null && server.getPlayerList().getPlayer(s.player()) != null) {
				return false;
			}
		}
		if (ph == Match.Phase.LOBBY) {
			for (UUID id : m.spectators()) {
				if (server.getPlayerList().getPlayer(id) != null) {
					return false;
				}
			}
		}
		return true;
	}

	private static void kickoffFx(ActiveMatch m, ServerLevel stadium, SimFrame f) {
		if (m.bodies.get(m.ballEntityId) instanceof BallEntity ball && f.ball() != null) {
			McrlPayloads.sendFxEvent(ball, FxEventS2C.Kind.BALL_SPAWNED, f.ball().cur(), null);
		}
		for (Slot s : m.slots()) {
			SimFrame.CarSnap c = f.car(s.carEntityId());
			if (c != null && liveCar(m, s.carEntityId()) instanceof CarEntity car) {
				McrlPayloads.sendFxEvent(car, FxEventS2C.Kind.CAR_SPAWNED, c.cur(), null);
			}
		}
	}

	private static void run(ActiveMatch m, List<Action> actions, MinecraftServer server) {
		for (Action a : actions) {
			if (current != m) {
				return;
			}
			switch (a) {
				case Match.Kickoff k -> {
					SimHost host = m.host();
					if (host != null) {
						host.kickoff(k.seed(), MatchRules.COUNTDOWN_TICKS);
					}
				}
				case Match.ResetBall r -> {
					if (m.bodies.get(m.ballEntityId) instanceof BallEntity ball && !ball.isRemoved()) {
						ball.relocate(BallEntity.restingAt(0, BALL_REST_Y, 0));
					}
				}
				case Match.Goal g -> {
					announce(m, server, goalMessage(m, server, g));
					toStadium(server, goalEvent(m, server, g));
				}
				case Match.End e -> {
					announce(m, server, e.winner() < 0
							? msg("mcrl.match.over", "The match is over (%s-%s)", m.match().blue(), m.match().orange())
							: msg("mcrl.match.end", "%s wins %s-%s!", teamName(e.winner()), m.match().blue(),
									m.match().orange()));
					toStadium(server, MatchEventS2C.end(e.winner()));
				}
				case Match.Return r -> finish(server, m);
			}
		}
	}

	private static Component goalMessage(ActiveMatch m, MinecraftServer server, Match.Goal g) {
		for (Slot s : m.slots()) {
			if (s.carEntityId() == g.toucher() && s.team() == g.team() && s.bot()) {
				return msg("mcrl.match.goal", "%s scores for %s!", BOT_NAME, teamName(g.team()));
			}
			if (s.carEntityId() == g.toucher() && s.team() == g.team() && s.player() != null) {
				ServerPlayer p = server.getPlayerList().getPlayer(s.player());
				if (p != null) {
					return msg("mcrl.match.goal", "%s scores for %s!", p.getName(), teamName(g.team()));
				}
			}
		}
		return msg("mcrl.match.goal_team", "%s scores!", teamName(g.team()));
	}

	private static void announce(ActiveMatch m, MinecraftServer server, Component c) {
		for (UUID id : m.everyone()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				p.sendSystemMessage(c);
			}
		}
	}

	private static void finish(MinecraftServer server, ActiveMatch m) {
		current = null;
		header = null;
		for (Slot s : m.slots()) {
			if (s.bot()) {
				BotFiller.onRelease(m, s);
			}
		}
		for (UUID id : m.everyone()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				returnPlayer(p);
			}
		}
		for (Entity e : m.bodies.values()) {
			if (!e.isRemoved()) {
				e.ejectPassengers();
				e.discard();
			}
		}
		ServerLevel stadium = server.getLevel(StadiumConst.KEY);
		if (stadium != null) {
			clearStadium(stadium);
		}
		SimHosts.close(StadiumConst.KEY);
	}

	public static void reset(MinecraftServer server) {
		ActiveMatch m = current;
		if (m != null) {
			finish(server, m);
		}
	}

	private static void clearStadium(ServerLevel stadium) {
		for (Entity e : List.copyOf(stadium.getEntities(McrlEntities.CAR, c -> !c.isRemoved()))) {
			e.ejectPassengers();
			e.discard();
		}
		for (Entity e : List.copyOf(stadium.getEntities(McrlEntities.BALL, b -> !b.isRemoved()))) {
			e.discard();
		}
	}
}
