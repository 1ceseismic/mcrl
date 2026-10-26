package dev.mcrl.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;

import dev.mcrl.McrlGameRules;
import dev.mcrl.SafeBoot;
import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.CarChoice;
import dev.mcrl.entity.CarChoices;
import dev.mcrl.entity.CarOwners;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.entity.SpawnPlacer;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.sim.BallState;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.terrain.TerrainFeeder;

public final class McrlCommands {
	private static final double BALL_AHEAD = 6.0;
	private static final double BALL_UP = 4.0;
	private static final List<String> VISUALS_MODES = List.of("rl", "simple", "hitbox");

	private McrlCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
				Commands.literal("mcrl")
						.then(Commands.literal("car").executes(c -> car(c.getSource())))
						.then(Commands.literal("ball").executes(c -> ball(c.getSource())))
						.then(Commands.literal("physics")
								.then(Commands.literal("status").executes(c -> physicsStatus(c.getSource())))
								.then(Commands.literal("restart")
										.requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions())
												|| isSingleplayerOwner(src))
										.executes(c -> physicsRestart(c.getSource()))))
						.then(Commands.literal("stepup")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(c -> stepUp(c.getSource(), BoolArgumentType.getBool(c, "enabled")))))
						.then(visualsSuggestions())
						.then(onlineSuggestions())));
	}

	static boolean isSingleplayerOwner(CommandSourceStack src) {
		ServerPlayer p = src.getPlayer();
		return p != null && src.getServer().isSingleplayerOwner(p.nameAndId());
	}

	// Completion only: Fabric hides client children under server roots.
	private static <S> LiteralArgumentBuilder<S> visualsSuggestions() {
		LiteralArgumentBuilder<S> visuals = LiteralArgumentBuilder.literal("visuals");
		for (String mode : VISUALS_MODES) {
			visuals.then(LiteralArgumentBuilder.<S>literal(mode));
		}
		return visuals;
	}

	private static <S> LiteralArgumentBuilder<S> onlineSuggestions() {
		return LiteralArgumentBuilder.<S>literal("online").then(LiteralArgumentBuilder.<S>literal("prediction")
				.then(LiteralArgumentBuilder.<S>literal("on")).then(LiteralArgumentBuilder.<S>literal("off")));
	}

	static int physicsStatus(CommandSourceStack src) {
		Breadcrumb.Previous crash = SafeBoot.lastCrash();
		if (crash != null) {
			Component line = crashLine(crash);
			src.sendSuccess(() -> line, false);
		}
		if (!SafeBoot.physicsOn()) {
			src.sendSuccess(McrlCommands::physicsOff, false);
			return 0;
		}
		List<Map.Entry<ResourceKey<Level>, SimHost>> hosts = new ArrayList<>(SimHosts.entries());
		if (hosts.isEmpty()) {
			src.sendSuccess(McrlCommands::noPhysics, false);
			return 0;
		}
		hosts.sort(Comparator.comparing(e -> e.getKey().identifier().toString()));
		long now = System.nanoTime();
		for (Map.Entry<ResourceKey<Level>, SimHost> e : hosts) {
			Component line = physicsStatus(e.getKey().identifier().toString(), e.getValue(), now);
			src.sendSuccess(() -> line, false);
		}
		return hosts.size();
	}

	static int physicsRestart(CommandSourceStack src) {
		if (refusedWhileOff(src)) {
			return 0;
		}
		Map<ResourceKey<Level>, SimHosts.Restarted> done = SimHosts.restartAll(src.getServer());
		if (done.isEmpty()) {
			src.sendSuccess(McrlCommands::noPhysics, false);
			return 0;
		}
		for (Map.Entry<ResourceKey<Level>, SimHosts.Restarted> e : done.entrySet()) {
			SimHosts.Restarted r = e.getValue();
			src.sendSuccess(() -> Component.translatableWithFallback("mcrl.physics.restarted",
					"Physics restarted in %s: %s car(s), ball %s", e.getKey().identifier().toString(), r.cars(),
					yesNo(r.ball())), false);
		}
		return done.size();
	}

	public static Component crashLine(Breadcrumb.Previous p) {
		return Component.translatableWithFallback("mcrl.physics.last_crash",
				"Last session crashed in mcrl physics: %s at section %s %s %s", p.op().name().toLowerCase(Locale.ROOT),
				p.x(), p.y(), p.z());
	}

	public static Component physicsOff() {
		return Component.translatableWithFallback("mcrl.physics.off", "Physics is off (mcrl settings (M) > Physics)");
	}

	public static Component physicsOffRefusal() {
		return Component.translatableWithFallback("mcrl.physics.off_refused",
				"Physics is off: turn it on in mcrl settings (M) > Physics");
	}

	private static Component noFreePlay() {
		return Component.translatableWithFallback("mcrl.match.no_free_play",
				"The stadium is for matches: use /mcrl match");
	}

	private static boolean refusedInStadium(CommandSourceStack src, ServerLevel level) {
		if (!level.dimension().equals(StadiumConst.KEY)) {
			return false;
		}
		src.sendFailure(noFreePlay());
		return true;
	}

	private static boolean refusedWhileOff(CommandSourceStack src) {
		if (SafeBoot.physicsOn()) {
			return false;
		}
		src.sendFailure(physicsOffRefusal());
		return true;
	}

	public static Component noPhysics() {
		return Component.translatableWithFallback("mcrl.physics.none", "No physics running");
	}

	public static Component physicsStatus(String dimension, SimHost host, long nowNanos) {
		SimFrame f = host.latest();
		MutableComponent line = Component.translatableWithFallback("mcrl.physics.status",
				"%s: %s, tick %s, %s car(s), ball %s, %s sections", dimension, stateWords(host.stalledNanos(nowNanos), f.flags(), f.anyCarHeld()), f.tick(),
				f.cars().size(), yesNo(f.ball() != null), host.sectionCount());
		SimHost.Fault fault = host.fault();
		if (fault.count() > 0) {
			line.append(Component.translatableWithFallback("mcrl.physics.faults", ", %s fault(s), last: %s",
					fault.count(), String.valueOf(fault.last())));
		}
		int quarantined = TerrainFeeder.quarantined(host);
		if (quarantined > 0) {
			line.append(Component.translatableWithFallback("mcrl.physics.quarantined", ", %s quarantined", quarantined));
		}
		return line;
	}

	private static Component stateWords(long stalledNanos, int flags, boolean carHeld) {
		if (stalledNanos == Long.MAX_VALUE) {
			return Component.translatableWithFallback("mcrl.physics.state.ended", "stalled (sim thread ended)");
		}
		if (stalledNanos > 0) {
			return Component.translatableWithFallback("mcrl.physics.state.stalled", "stalled %s ms",
					stalledNanos / 1_000_000);
		}
		if ((flags & SimFrame.PAUSED) != 0) {
			return Component.translatableWithFallback("mcrl.physics.state.paused", "paused");
		}
		if ((flags & SimFrame.FROZEN) != 0) {
			return carHeld ? Component.translatableWithFallback("mcrl.physics.state.car_waits", "a car waits for terrain")
					: Component.translatableWithFallback("mcrl.physics.state.frozen", "frozen (loading terrain)");
		}
		return Component.translatableWithFallback("mcrl.physics.state.running", "running");
	}

	private static Component yesNo(boolean b) {
		return b ? Component.translatableWithFallback("mcrl.physics.yes", "yes")
				: Component.translatableWithFallback("mcrl.physics.no", "no");
	}

	static int stepUp(CommandSourceStack src, boolean on) {
		MinecraftServer server = src.getServer();
		server.getGameRules().set(McrlGameRules.STEP_UP, on, server);
		src.sendSuccess(() -> Component.translatableWithFallback("mcrl.command.stepup",
				"Full-block step-up is now %s", on), true);
		return on ? 1 : 0;
	}

	static int car(CommandSourceStack src) throws CommandSyntaxException {
		if (refusedWhileOff(src)) {
			return 0;
		}
		ServerPlayer player = src.getPlayerOrException();
		ServerLevel level = player.level();
		if (refusedInStadium(src, level)) {
			return 0;
		}
		CarChoice choice = CarChoices.get(player);
		Optional<Pose> pose = SpawnPlacer.place(level, player, HitboxPreset.of(choice.preset()));
		if (pose.isEmpty()) {
			src.sendFailure(Component.translatableWithFallback("mcrl.command.no_room", "No room for a car here"));
			return 0;
		}
		CarEntity car = McrlEntities.CAR.create(level, EntitySpawnReason.COMMAND);
		if (car == null) {
			return 0;
		}
		car.applyChoice(choice);
		car.setSimState(CarState.of(pose.get(), 0, 0, 0, 0, 0, 0));
		if (!level.addFreshEntity(car)) {
			src.sendFailure(Component.translatableWithFallback("mcrl.command.car_failed", "Could not spawn the car"));
			return 0;
		}
		CarOwners.claim(player, car);
		McrlPayloads.sendFxEvent(car, McrlPayloads.FxEventS2C.Kind.CAR_SPAWNED, pose.get(), player);
		src.sendSuccess(() -> Component.translatableWithFallback("mcrl.command.car_spawned", "Spawned a car"), false);
		return 1;
	}

	static int ball(CommandSourceStack src) throws CommandSyntaxException {
		if (refusedWhileOff(src)) {
			return 0;
		}
		ServerPlayer player = src.getPlayerOrException();
		ServerLevel level = player.level();
		if (refusedInStadium(src, level)) {
			return 0;
		}
		double rad = Math.toRadians(player.getYRot());
		double fx = -Math.sin(rad), fz = Math.cos(rad);
		BallState s = BallEntity.restingAt(player.getX() + fx * BALL_AHEAD, player.getY() + BALL_UP,
				player.getZ() + fz * BALL_AHEAD);
		BallEntity ball = BallEntity.active(level);
		if (ball != null) {
			ball.relocate(s);
			McrlPayloads.sendFxEvent(ball, McrlPayloads.FxEventS2C.Kind.BALL_SPAWNED, ball.pose(), player);
			src.sendSuccess(() -> Component.translatableWithFallback("mcrl.command.ball_moved", "Moved the ball"), false);
			return 1;
		}
		ball = McrlEntities.BALL.create(level, EntitySpawnReason.COMMAND);
		if (ball == null) {
			return 0;
		}
		ball.setSimState(s);
		if (!level.addFreshEntity(ball)) {
			src.sendFailure(Component.translatableWithFallback("mcrl.command.ball_failed", "Could not spawn the ball"));
			return 0;
		}
		McrlPayloads.sendFxEvent(ball, McrlPayloads.FxEventS2C.Kind.BALL_SPAWNED, ball.pose(), player);
		src.sendSuccess(() -> Component.translatableWithFallback("mcrl.command.ball_spawned", "Spawned the ball"), false);
		return 1;
	}
}
