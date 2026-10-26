package dev.mcrl.sim;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import dev.mcrl.McrlGameRules;
import dev.mcrl.SafeBoot;
import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.entity.MobHits;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.math.Pose;
import dev.mcrl.natives.NativeBoot;
import dev.mcrl.net.SnapshotSender;

public final class SimHosts {
	public record Restarted(int cars, boolean ball) {
	}

	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Map<ResourceKey<Level>, SimHost> HOSTS = new ConcurrentHashMap<>();
	private static @Nullable String warnedMissing;

	private SimHosts() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> closeAll());
		ServerLifecycleEvents.SERVER_STOPPING.register(SimHosts::closeAll);
	}

	public static @Nullable SimHost getOrCreateIfOn(ServerLevel level, double x, double z) {
		if (!SafeBoot.physicsOn()) {
			return null;
		}
		SimHost existing = HOSTS.get(level.dimension());
		if (existing != null && !existing.isClosed()) {
			return existing;
		}
		NativeBoot.ensureStarted();
		ArenaMeshes.Set meshes = null;
		if (level.dimension().equals(StadiumConst.KEY)) {
			try {
				meshes = ArenaMeshes.load();
			} catch (ArenaMeshes.Missing e) {
				if (!e.getMessage().equals(warnedMissing)) {
					warnedMissing = e.getMessage();
					LOG.warn("mcrl: no stadium physics: {}", e.getMessage());
				}
				return null;
			}
			warnedMissing = null;
		}
		ArenaMeshes.Set stadium = meshes;
		return HOSTS.compute(level.dimension(), (k, h) -> {
			if (h != null && !h.isClosed()) {
				return h;
			}
			SimClock.Mode mode = SimClock.selectedMode(level.getServer());
			SimHost started;
			if (stadium != null) {
				started = SimHost.startSoccar(k.identifier().toString(), stadium, mode, SimHost.Role.SERVER);
			} else {
				started = SimHost.start(k.identifier().toString(), originOf(x), 0, originOf(z), level.getMinY(), mode);
				if (level.getGameRules().get(McrlGameRules.STEP_UP)) {
					started.setStepUp(true);
				}
				if (!level.getGameRules().get(McrlGameRules.CAR_DEMOS)) {
					McrlGameRules.pushCarDemos(started, false);
				}
				started.setExtras(McrlGameRules.extras(level.getGameRules()));
			}
			if (level.tickRateManager().isFrozen()) {
				started.setPaused(PauseSource.FREEZE, true);
			}
			if (stadium == null) {
				started.setPerCarGate(true);
			}
			started.setTicksListener(MobHits.trigger(level));
			started.setSnapshotListener(new SnapshotSender(started));
			return started;
		});
	}

	static int originOf(double v) {
		return (int) Math.floor(v / 16.0) * 16;
	}

	public static void setClockAll(SimClock.Mode mode) {
		for (SimHost h : HOSTS.values()) {
			h.setClock(mode);
		}
	}

	public static void reconcileClocks(MinecraftServer server) {
		setClockAll(SimClock.selectedMode(server));
	}

	public static @Nullable SimHost get(ResourceKey<Level> dimension) {
		return HOSTS.get(dimension);
	}

	public static List<Map.Entry<ResourceKey<Level>, SimHost>> entries() {
		return HOSTS.entrySet().stream().map(e -> Map.entry(e.getKey(), e.getValue())).toList();
	}

	public static Collection<SimHost> all() {
		return Collections.unmodifiableCollection(HOSTS.values());
	}

	public static void closeAll(MinecraftServer server) {
		closeAll();
		for (ServerLevel level : server.getAllLevels()) {
			for (CarEntity car : List.copyOf(level.getEntities(McrlEntities.CAR, c -> !c.isRemoved()))) {
				car.leftSim();
			}
		}
	}

	public static void closeAll() {
		for (ResourceKey<Level> k : HOSTS.keySet()) {
			SimHost h = HOSTS.remove(k);
			if (h != null) {
				retire(h);
			}
		}
	}

	public static void close(ResourceKey<Level> dimension) {
		SimHost h = HOSTS.remove(dimension);
		if (h != null) {
			retire(h);
		}
	}

	private static void retire(SimHost h) {
		if (h.stalledNanos(System.nanoTime()) > 0) {
			h.abandon();
		} else {
			h.close();
		}
	}

	public static Restarted restart(ServerLevel level) {
		SimHost old = HOSTS.remove(level.dimension());
		if (old != null) {
			retire(old);
		}
		List<? extends CarEntity> cars = List.copyOf(level.getEntities(McrlEntities.CAR, c -> !c.isRemoved()));
		for (CarEntity car : cars) {
			car.leftSim();
			CarState s = car.keptState();
			CarState in = reentryState(s, car.getX(), car.getY(), car.getZ(), car.getYRot());
			if (in != s) {
				car.setSimState(in);
			}
			car.onLoad(level);
		}
		BallEntity ball = levelBall(level);
		if (ball != null) {
			BallState s = ball.pendingState() != null ? ball.pendingState() : ball.simState();
			if (!s.isFinite() && Double.isFinite(ball.getX()) && Double.isFinite(ball.getY())
					&& Double.isFinite(ball.getZ())) {
				ball.setSimState(BallEntity.restingAt(ball.getX(), ball.getY(), ball.getZ()));
			}
			ball.onLoad(level);
			for (BallEntity other : List.copyOf(level.getEntities(McrlEntities.BALL, b -> !b.isRemoved()))) {
				if (other != ball) {
					other.allowLoad(level);
				}
			}
		}
		return new Restarted(cars.size(), ball != null);
	}

	public static Map<ResourceKey<Level>, Restarted> restartAll(MinecraftServer server) {
		Map<ResourceKey<Level>, Restarted> out = new LinkedHashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			if (HOSTS.containsKey(level.dimension()) || levelBall(level) != null
					|| !level.getEntities(McrlEntities.CAR, c -> !c.isRemoved()).isEmpty()) {
				out.put(level.dimension(), restart(level));
			}
		}
		return out;
	}

	private static @Nullable BallEntity levelBall(ServerLevel level) {
		BallEntity active = BallEntity.active(level);
		if (active != null) {
			return active;
		}
		List<? extends BallEntity> loaded = level.getEntities(McrlEntities.BALL, b -> !b.isRemoved());
		return loaded.isEmpty() ? null : loaded.get(0);
	}

	static CarState reentryState(CarState s, double x, double y, double z, float yawDeg) {
		if (s.isFinite() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			return s;
		}
		return CarState.of(Pose.upright(x, y, z, Float.isFinite(yawDeg) ? yawDeg : 0f), 0, 0, 0, 0, 0, 0);
	}
}
