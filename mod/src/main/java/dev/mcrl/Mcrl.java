package dev.mcrl;

import java.util.Locale;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.TickRateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.command.McrlCommands;
import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.BallTicket;
import dev.mcrl.entity.CarChoices;
import dev.mcrl.entity.CarTicket;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.CarLoadGate;
import dev.mcrl.entity.CarOwners;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.entity.MobHits;
import dev.mcrl.match.MatchCommands;
import dev.mcrl.match.MatchManager;
import dev.mcrl.match.StadiumTicket;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.natives.NativeBoot;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.net.NetPeers;
import dev.mcrl.net.SnapshotSender;
import dev.mcrl.sim.CarEvent;
import dev.mcrl.sim.PauseSource;
import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.terrain.TerrainEdits;
import dev.mcrl.terrain.TerrainFeeder;

public class Mcrl implements ModInitializer {
	public static final String MOD_ID = "mcrl";
	public static final Logger LOGGER = LoggerFactory.getLogger("mcrl");
	public static final int LOCKSTEP_TICKS = 6;

	@Override
	public void onInitialize() {
		FabricLoader loader = FabricLoader.getInstance();
		// First: with physics off no native code may run.
		SafeBoot.init(loader.getConfigDir().resolve("mcrl.json"), loader.getGameDir());
		String version = loader.getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("?");
		Breadcrumb.Previous crash = SafeBoot.lastCrash();
		if (crash != null) {
			LOGGER.warn("mcrl: the last session crashed in mcrl physics: {} at section {} {} {} (sim tick {}, thread {})",
					crash.op().name().toLowerCase(Locale.ROOT), crash.x(), crash.y(), crash.z(), crash.tick(),
					crash.thread());
		}
		if (SafeBoot.physicsOn()) {
			NativeBoot.ensureStarted();
			LOGGER.info("mcrl {} abi {}", version, McrlNative.EXPECTED_ABI_VERSION);
		} else {
			LOGGER.info("mcrl {}", version);
			LOGGER.warn("mcrl: physics is off (safe boot)");
		}

		McrlGameRules.register();
		McrlEntities.register();
		BallTicket.register();
		CarTicket.register();
		McrlPayloads.register();
		NetPeers.register();
		McrlCommands.register();
		MatchCommands.register();
		StadiumTicket.register();
		SimHosts.register();
		CarOwners.register();
		ServerTickEvents.END_SERVER_TICK.register(CarOwners::tick);
		CarChoices.register();
		MatchManager.register();
		ServerLifecycleEvents.SERVER_STARTING.register(server -> CarLoadGate.clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BallEntity.clearActive();
			CarLoadGate.clear();
			TerrainEdits.clear();
			MobHits.clear();
		});

		ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, flag) ->
				!(entity instanceof BallEntity ball) || ball.allowLoad(level));
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			// Shutting down: the hosts are closed for good.
			if (!level.getServer().isRunning()) {
				return;
			}
			if (entity instanceof CarEntity car && CarOwners.superseded(car)) {
				CarOwners.discardLater(car);
				return;
			}
			if (entity instanceof CarEntity car) {
				CarChoices.onLoad(car);
			}
			if (!SafeBoot.physicsOn()) {
				return;
			}
			if (entity instanceof CarEntity car) {
				car.onLoad(level);
			} else if (entity instanceof BallEntity ball) {
				ball.onLoad(level);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof CarEntity car) {
				car.onUnload(level);
			} else if (entity instanceof BallEntity ball) {
				ball.onUnload(level);
			}
		});

		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			SimHost watched = SimHosts.get(level.dimension());
			if (watched != null) {
				watched.watchdog(System.nanoTime());
			}
			if (SafeBoot.physicsOn()) {
				CarLoadGate.onEndLevelTick(level);
			}
			SimHost host = SimHosts.get(level.dimension());
			if (host != null) {
				host.setTickRateFactor(level.getServer().tickRateManager().tickrate() / 20f);
				// This event fires on frozen ticks too.
				TickRateManager rate = level.tickRateManager();
				host.setPaused(PauseSource.FREEZE, rate.isFrozen());
				if (host.mode() == SimClock.Mode.LOCKSTEP) {
					if (rate.runsNormally()) {
						host.stepLockstep(LOCKSTEP_TICKS);
					}
				} else if (rate.isFrozen() && rate.runsNormally()) {
					// A /tick step: FREEZE pauses the other clocks.
					host.stepLockstep(LOCKSTEP_TICKS);
				}
				MobHits.tick(level);
				copyFrame(level, host.latest());
				sendCarEvents(level, host);
			}
			TerrainFeeder.onEndLevelTick(level);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			SnapshotSender.updateRecipients(server);
			SimHosts.reconcileClocks(server);
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) ->
				SnapshotSender.leftFor(p, to));
		ServerPlayerEvents.AFTER_RESPAWN.register((was, p, alive) -> SnapshotSender.leftFor(p, p.level()));
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> TerrainFeeder.onChunkLoad(level, chunk));
	}

	private static void sendCarEvents(ServerLevel level, SimHost host) {
		for (CarEvent e : host.drainCarEvents()) {
			if (!(level.getEntity(e.carId()) instanceof CarEntity car) || car.isRemoved()) {
				continue;
			}
			ServerPlayer driver = car.getControllingPassenger() instanceof ServerPlayer p ? p : null;
			switch (e) {
				case CarEvent.Demolished d -> McrlPayloads.sendFxEvent(car, McrlPayloads.FxEventS2C.Kind.CAR_DEMOLISHED,
						d.pose(), driver, d.attackerId());
				case CarEvent.Respawned r -> McrlPayloads.sendFxEvent(car, McrlPayloads.FxEventS2C.Kind.CAR_SPAWNED,
						r.pose(), driver);
			}
		}
	}

	private static void copyFrame(ServerLevel level, SimFrame f) {
		for (SimFrame.CarSnap s : f.cars()) {
			if (level.getEntity(s.carId()) instanceof CarEntity car && !car.isRemoved()) {
				car.applyFrame(s);
			}
		}
		SimFrame.BallSnap b = f.ball();
		BallEntity ball = BallEntity.active(level);
		if (b != null && ball != null) {
			ball.applyFrame(b);
		}
	}
}
