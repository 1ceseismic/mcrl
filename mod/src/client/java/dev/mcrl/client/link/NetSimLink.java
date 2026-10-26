package dev.mcrl.client.link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;

import dev.mcrl.McrlGameRules;
import dev.mcrl.SafeBoot;
import dev.mcrl.client.WorldRules;
import dev.mcrl.client.camera.CameraHost;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.effects.SpawnFx;
import dev.mcrl.client.match.MatchClientState;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.natives.NativeBoot;
import dev.mcrl.net.ArenaBlock;
import dev.mcrl.net.McrlPayloads.FxEventS2C;
import dev.mcrl.net.McrlPayloads.RulesS2C;
import dev.mcrl.net.NetMode;
import dev.mcrl.net.NetPayloads.HelloAckC2S;
import dev.mcrl.net.NetPayloads.HelloS2C;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.net.NetPayloads.SnapshotS2C;
import dev.mcrl.net.NetProtocol;
import dev.mcrl.net.NetTuning;
import dev.mcrl.net.Snapshot;
import dev.mcrl.net.SnapshotCodec;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SoccarState;

public final class NetSimLink {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final long RENDER_CATCH_UP_NANOS = 500_000_000L;
	private static final long SECOND = 1_000_000_000L;

	private static @Nullable HelloS2C hello;
	private static NetMode chosen = NetMode.NONE;
	private static SnapshotBuffer buffer = new SnapshotBuffer();
	private static final InputPacer PACER = new InputPacer();
	private static int hostId;
	private static boolean hasHost;
	private static boolean decodeFailed;
	private static double renderTick;
	private static long renderNanos;
	private static boolean rendering;
	private static boolean drawing;
	private static final ArrayDeque<long[]> ARRIVALS = new ArrayDeque<>();
	private static NetStats stats = NetStats.NONE;

	private static @Nullable Prediction prediction;
	private static boolean predictFailed;
	private static @Nullable ClientLevel stadiumMeshesMissing;
	private static @Nullable CompletableFuture<SimHost> stadiumOpening;
	private static @Nullable ClientLevel stadiumOpeningLevel;
	private static final ExecutorService STADIUM_LOADER = Executors.newSingleThreadExecutor(
			r -> Thread.ofPlatform().daemon().name("mcrl-stadium-load").unstarted(r));
	private static boolean predicting;
	private static final CorrectionOffsets CORRECTIONS = new CorrectionOffsets();
	private static int hostStepUp = -1;
	private static int hostExtras = -1;
	private static long predictionOpened;
	private static final long READY_WAIT_NANOS = SECOND;
	private static Set<Integer> fromSnapshots = new HashSet<>();
	private static Set<Integer> drawnBodies = new HashSet<>();
	private static @Nullable Snapshot newest;
	private static @Nullable ClientLevel linkLevel;
	private static @Nullable String linkDim;
	private static final HostDimensions HOST_DIMENSIONS = new HostDimensions();
	private static @Nullable Lag lag;

	private NetSimLink() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(HelloS2C.TYPE, (h, ctx) -> onHello(h));
		ClientPlayNetworking.registerGlobalReceiver(SnapshotS2C.TYPE, (s, ctx) -> onSnapshot(s, System.nanoTime()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(NetSimLink::reset));
		SpawnFx.RECEIVED.add(e -> {
			if (e.kind() == FxEventS2C.Kind.CAR_SPAWNED) {
				CORRECTIONS.clear(e.entityId());
			}
		});
	}

	public static NetMode mode() {
		return chosen == NetMode.NONE ? NetMode.NONE : predicting ? NetMode.PREDICT : NetMode.INTERPOLATE;
	}

	public interface Lag {
		void in(SnapshotS2C p, long atNanos);

		List<Arrival> due(long nowNanos);

		List<InputC2S> out(List<InputC2S> sent, long nowNanos);
	}

	public record Arrival(SnapshotS2C snapshot, long atNanos) {
	}

	public static void setLag(@Nullable Lag l) {
		Lag old = lag;
		lag = l;
		if (old == null) {
			return;
		}
		long now = System.nanoTime();
		for (Arrival a : old.due(Long.MAX_VALUE)) {
			deliver(a.snapshot(), Math.min(a.atNanos(), now), now);
		}
		List<InputC2S> held = old.out(List.of(), Long.MAX_VALUE);
		if (ClientPlayNetworking.canSend(InputC2S.TYPE)) {
			held.forEach(ClientPlayNetworking::send);
		}
	}

	private static void dropLagged() {
		Lag l = lag;
		if (l != null) {
			l.due(Long.MAX_VALUE);
			l.out(List.of(), Long.MAX_VALUE);
		}
	}

	public static NetStats stats() {
		return stats;
	}

	private static NetMode choose(HelloS2C h) {
		return NetMode.choose(h, NetProtocol.VERSION, McrlNative.EXPECTED_ABI_VERSION, NativeBoot.buildHash(),
				SafeBoot.physicsOn(), McrlConfig.get().onlinePrediction && !predictFailed && stadiumMeshesMissing == null);
	}

	public static boolean stadiumMeshesMissing() {
		return stadiumMeshesMissing != null;
	}

	public static int serverProtocol() {
		HelloS2C h = hello;
		return h == null ? -1 : h.protocol();
	}

	private static void onHello(HelloS2C h) {
		hello = h;
		NetMode m = choose(h);
		if (m == NetMode.NONE) {
			LOG.warn("mcrl: the server speaks mcrl protocol {}, this client {}: no car link (update one of them)",
					h.protocol(), NetProtocol.VERSION);
		} else {
			LOG.info("mcrl: online link {} (server abi {}, build {}; tick {})", m, h.abi(), Long.toHexString(h.buildHash()),
					h.serverTick());
		}
		sendAck(m);
	}

	private static void sendAck(NetMode m) {
		chosen = m;
		if (ClientPlayNetworking.canSend(HelloAckC2S.TYPE)) {
			ClientPlayNetworking.send(new HelloAckC2S(NetProtocol.VERSION, McrlNative.EXPECTED_ABI_VERSION,
					NativeBoot.buildHash(), m, ArenaMeshes.identity()));
		}
	}

	private static void onSnapshot(SnapshotS2C p, long nowNanos) {
		if (chosen == NetMode.NONE) {
			return;
		}
		long recvNanos = p.recvNanos() != 0 ? p.recvNanos() : nowNanos;
		Lag l = lag;
		if (l != null) {
			l.in(p, recvNanos);
			return;
		}
		deliver(p, recvNanos, nowNanos);
	}

	private static void pollLag(long nowNanos) {
		Lag l = lag;
		if (l != null) {
			for (Arrival a : l.due(nowNanos)) {
				deliver(a.snapshot(), a.atNanos(), nowNanos);
			}
		}
	}

	private static void deliver(SnapshotS2C p, long recvNanos, long nowNanos) {
		Minecraft mc = Minecraft.getInstance();
		syncLevel(mc.level);
		Snapshot s;
		try {
			s = SnapshotCodec.decode(Unpooled.wrappedBuffer(p.data()));
		} catch (RuntimeException e) {
			if (!decodeFailed) {
				decodeFailed = true;
				LOG.error("mcrl: a snapshot from the server does not decode; such snapshots are dropped", e);
			}
			return;
		}
		deliver(mc, s, p.data().length, recvNanos, nowNanos);
	}

	private static void deliver(Minecraft mc, Snapshot s, int bytes, long recvNanos, long nowNanos) {
		if (!HOST_DIMENSIONS.accepts(s.hostId(), linkDim)) {
			return;
		}
		if (hasHost && s.hostId() != hostId) {
			LOG.info("mcrl: snapshots now come from another sim host (tick {}): the link starts over", s.tick());
			startOver();
		}
		hostId = s.hostId();
		hasHost = true;
		newest = s;
		if (s.arena().kind() == ArenaBlock.KIND_SOCCAR) {
			MatchClientState.fromSnapshot(s.arena().body());
		}
		buffer.add(s);
		int rtt = rttMs(mc);
		PACER.onSnapshot(s, recvNanos, rtt);
		ClientLevel level = mc.level;
		if (chosen == NetMode.PREDICT && prediction == null && !predictFailed && stadiumMeshesMissing == null
				&& stadiumOpening == null && level != null) {
			openPrediction(s, level);
		}
		Prediction pr = prediction;
		if (pr != null) {
			pr.onSnapshot(s, recvNanos, rtt);
			int up = (s.frameFlags() & SimFrame.STEPUP) != 0 ? 1 : 0;
			if (up != hostStepUp) {
				pr.host().setStepUp(up == 1);
				hostStepUp = up;
			}
		}
		ARRIVALS.addLast(new long[] {recvNanos, bytes});
		trimArrivals(nowNanos);
	}

	private static void openPrediction(Snapshot s, ClientLevel level) {
		String dim = level.dimension().identifier().toString();
		if (s.arena().kind() == ArenaBlock.KIND_SOCCAR) {
			if (stadiumOpening == null) {
				long serverMeshes = hello != null ? hello.arenaMeshes() : 0;
				stadiumOpeningLevel = level;
				stadiumOpening = CompletableFuture.supplyAsync(() -> openStadium(dim, serverMeshes), STADIUM_LOADER);
			}
			return;
		}
		SimHost host = null;
		try {
			host = SimHost.start(dim, s.ox(), s.oy(), s.oz(), level.getMinY(), SimClock.Mode.CLIENT_PACED,
					SimHost.Kind.TERRAIN, SimHost.Role.PREDICTION);
			ClientTerrain.attach(host, level);
			installPrediction(host);
			LOG.info("mcrl: predicting {} from server tick {}", level.dimension().identifier(), s.tick());
		} catch (RuntimeException | LinkageError e) {
			prediction = null;
			if (host != null) {
				ClientTerrain.detach();
				host.close();
			}
			predictFailed = true;
			LOG.error("mcrl: the prediction host did not start: this connection interpolates", e);
		}
	}

	private static void installPrediction(SimHost host) {
		prediction = new Prediction(host, new LeadControl(), PACER.history(), Prediction.FRAME_WAIT_NANOS);
		predictionOpened = System.nanoTime();
	}

	private static final class StadiumUnpredictable extends RuntimeException {
		StadiumUnpredictable(String why) {
			super(why, null, false, false);
		}
	}

	private static SimHost openStadium(String dim, long serverMeshes) {
		ArenaMeshes.Set m;
		try {
			m = ArenaMeshes.load();
		} catch (ArenaMeshes.Missing e) {
			throw new StadiumUnpredictable("no stadium meshes: " + e.getMessage());
		}
		if (m.known() != ArenaMeshes.SOCCAR_MESHES && m.source() != ArenaMeshes.Source.PROPERTY) {
			throw new StadiumUnpredictable("the arena meshes hold " + m.known() + " of RocketSim's "
					+ ArenaMeshes.SOCCAR_MESHES);
		}
		long mine = ArenaMeshes.identity();
		if (mine != serverMeshes) {
			throw new StadiumUnpredictable("the arena meshes are not the server's (" + Long.toHexString(mine)
					+ ", the server's " + Long.toHexString(serverMeshes) + ")");
		}
		return SimHost.startSoccar(dim, m, SimClock.Mode.CLIENT_PACED, SimHost.Role.PREDICTION);
	}

	private static void takeStadium() {
		CompletableFuture<SimHost> f = stadiumOpening;
		if (f == null || !f.isDone()) {
			return;
		}
		stadiumOpening = null;
		ClientLevel level = stadiumOpeningLevel;
		stadiumOpeningLevel = null;
		SimHost host;
		try {
			host = f.join();
		} catch (CompletionException | CancellationException e) {
			Throwable why = e.getCause() != null ? e.getCause() : e;
			if (level == linkLevel) {
				stadiumMeshesMissing = level;
			}
			if (why instanceof StadiumUnpredictable) {
				LOG.warn("mcrl: the stadium is not predicted: {}", why.getMessage());
			} else {
				LOG.error("mcrl: the stadium's prediction host did not start: it is interpolated", why);
			}
			return;
		}
		if (level != linkLevel || prediction != null || chosen != NetMode.PREDICT) {
			host.close();
			return;
		}
		installPrediction(host);
		LOG.info("mcrl: predicting the stadium {}", host.dimName());
	}

	private static void closePrediction() {
		Prediction pr = prediction;
		predicting = false;
		if (pr == null) {
			return;
		}
		prediction = null;
		ClientTerrain.detach();
		MatchClientState.predictedBallOut(null);
		pr.host().close();
		CORRECTIONS.clearAll();
		fromSnapshots = new HashSet<>();
		drawnBodies = new HashSet<>();
		hostStepUp = -1;
		hostExtras = -1;
		PACER.lead().requestAnchor();
	}

	private static void startOver() {
		ClientPoses.newSource();
		buffer = new SnapshotBuffer();
		PACER.reset();
		rendering = false;
		newest = null;
		closePrediction();
	}

	private static void syncLevel(@Nullable ClientLevel level) {
		if (level == linkLevel) {
			return;
		}
		stadiumMeshesMissing = null;
		linkLevel = level;
		linkDim = level == null ? null : level.dimension().identifier().toString();
		startOver();
		hasHost = false;
		Lag l = lag;
		if (l != null) {
			l.due(Long.MAX_VALUE);
		}
		stopDrawing();
	}

	private static int extrasFromRules() {
		RulesS2C r = WorldRules.get();
		Boolean surfaces = r == null ? null : r.get("mcrl:surface_physics");
		Boolean fluids = r == null ? null : r.get("mcrl:fluid_physics");
		return McrlGameRules.extras(surfaces == null || surfaces, fluids == null || fluids);
	}

	private static LeadControl lead() {
		Prediction pr = prediction;
		return pr != null ? pr.lead() : PACER.lead();
	}

	private static void trimArrivals(long nowNanos) {
		while (!ARRIVALS.isEmpty() && ARRIVALS.getFirst()[0] < nowNanos - SECOND) {
			ARRIVALS.removeFirst();
		}
	}

	private static int rttMs(Minecraft mc) {
		ClientPacketListener c = mc.getConnection();
		LocalPlayer p = mc.player;
		PlayerInfo info = c == null || p == null ? null : c.getPlayerInfo(p.getUUID());
		return info == null ? 0 : info.getLatency();
	}

	static void frame(Minecraft mc, LinkInput in) {
		long now = System.nanoTime();
		LocalSimLink.setFrameNanos(now);
		LocalSimLink.sendExitIfPressed(mc, in.exit());
		HelloS2C h = hello;
		if (h != null) {
			NetMode m = choose(h);
			if (m != chosen) {
				sendAck(m);
			}
		}
		if (chosen != NetMode.PREDICT) {
			closePrediction();
		}
		syncLevel(mc.level);
		takeStadium();
		pollLag(now);
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		CarEntity car = player != null && level != null && chosen != NetMode.NONE ? LocalSimLink.drivenCar(player)
				: null;
		InputFrame f = LocalSimLink.driverInput(mc, in, car);
		LeadControl lead = lead();
		if (player == null || level == null || chosen == NetMode.NONE || !lead.hasSnapshot()) {
			predicting = false;
			stopDrawing();
			send(List.of(), now);
			stats = new NetStats(mode(), rttMs(mc), 0, 0, 0, 0, 0);
			return;
		}

		int carId = car == null ? -1 : car.carId();
		Prediction pr = prediction;
		SimFrame frame;
		if (pr != null) {
			int extras = extrasFromRules();
			if (extras != hostExtras && pr.host().kind() == SimHost.Kind.TERRAIN) {
				pr.host().setExtras(extras);
				hostExtras = extras;
			}
			boolean drives = pr.ready();
			SimFrame predicted = pr.frame(now, drives && car != null ? f : null, drives ? carId : -1);
			send(drives ? pr.takeOutgoing() : PACER.frame(now, car == null ? null : f, carId), now);
			List<SimHost.Correction> corrections = pr.drainCorrections();
			predicting = pr.ready();
			SoccarState soccar = predicted.soccar();
			MatchClientState.predictedBallOut(predicting && soccar != null ? soccar.ballOutOfPlay() : null);
			if (predicting) {
				Set<Integer> was = fromSnapshots;
				frame = predictedFrame(predicted, carId, lead, now);
				for (SimHost.Correction c : corrections) {
					if (!was.contains(c.bodyId()) && !fromSnapshots.contains(c.bodyId())) {
						CORRECTIONS.add(c, now);
					}
				}
				CORRECTIONS.sweep(now);
				ClientPoses.update(frame, pr.host(), now, CORRECTIONS);
			} else if (!drawing && now - predictionOpened < READY_WAIT_NANOS) {
				stats = stats(mc, now);
				return;
			} else {
				frame = interpolatedFrame(lead, now);
				ClientPoses.update(frame, null, now, null);
			}
		} else {
			predicting = false;
			send(PACER.frame(now, car == null ? null : f, carId), now);
			frame = interpolatedFrame(lead, now);
			ClientPoses.update(frame, null, now, null);
		}
		LocalSimLink.moveEntities(level, frame);
		drawing = true;
		stats = stats(mc, now);
	}

	private static void send(List<InputC2S> out, long nowNanos) {
		Lag l = lag;
		if (l != null) {
			out = l.out(out, nowNanos);
		}
		if (!out.isEmpty() && ClientPlayNetworking.canSend(InputC2S.TYPE)) {
			for (InputC2S p : out) {
				ClientPlayNetworking.send(p);
			}
		}
	}

	private static SimFrame interpolated(LeadControl lead, long now) {
		boolean paused = lead.paused();
		double target = paused ? buffer.newestTick() : lead.serverTickEstimate(now) - NetTuning.INTERP_DELAY_TICKS;
		return buffer.frameAt(renderTick(target, now, lead.serverRate(), paused));
	}

	private static SimFrame interpolatedFrame(LeadControl lead, long now) {
		SimFrame f = interpolated(lead, now);
		Set<Integer> bodies = new HashSet<>();
		for (SimFrame.CarSnap c : f.cars()) {
			bodies.add(c.carId());
		}
		if (f.ball() != null) {
			bodies.add(-1);
		}
		fromSnapshots = bodies;
		drawnBodies = bodies;
		return f;
	}

	private static SimFrame predictedFrame(SimFrame predicted, int ownCar, LeadControl lead, long now) {
		Snapshot s = newest;
		List<SimFrame.CarSnap> cars = new ArrayList<>(predicted.cars());
		SimFrame interp = null;
		Set<Integer> snapped = new HashSet<>(), drawn = new HashSet<>();
		for (int i = 0; i < cars.size(); i++) {
			SimFrame.CarSnap c = cars.get(i);
			int id = c.carId();
			boolean held = id != ownCar && (c.held() || heldOnServer(s, id));
			boolean was = fromSnapshots.contains(id);
			drawn.add(id);
			if (!held && !was) {
				continue;
			}
			if (interp == null) {
				interp = interpolated(lead, now);
			}
			SimFrame.CarSnap b = interp.car(id);
			if (b == null) {
				continue;
			}
			if (held) {
				cars.set(i, b);
				snapped.add(id);
			}
			if (held != was && drawnBodies.contains(id)) {
				CORRECTIONS.addSwitch(id, held ? c.cur() : b.cur(), held ? b.cur() : c.cur(), now);
			}
		}
		SimFrame.BallSnap ball = predicted.ball();
		if (ball != null) {
			drawn.add(-1);
			boolean held = (predicted.flags() & SimFrame.BALL_HELD) != 0;
			boolean was = fromSnapshots.contains(-1);
			if ((held || was) && interp == null) {
				interp = interpolated(lead, now);
			}
			SimFrame.BallSnap b = held || was ? interp.ball() : null;
			if (b != null) {
				if (held != was && drawnBodies.contains(-1)) {
					CORRECTIONS.addSwitch(-1, held ? ball.cur() : b.cur(), held ? b.cur() : ball.cur(), now);
				}
				if (held) {
					ball = b;
					snapped.add(-1);
				}
			}
		}
		fromSnapshots = snapped;
		drawnBodies = drawn;
		int flags = s == null ? predicted.flags() : s.frameFlags();
		return new SimFrame(predicted.tick(), predicted.alpha(), flags, cars, ball, predicted.paceSeq(),
				predicted.paceNanos(), predicted.rate());
	}

	private static boolean heldOnServer(@Nullable Snapshot s, int carId) {
		if (s != null) {
			for (Snapshot.Car c : s.cars()) {
				if (c.id() == carId) {
					return (c.head().flags() & CarState.FLAG_HELD) != 0;
				}
			}
		}
		return false;
	}

	private static double renderTick(double target, long now, float rate, boolean paused) {
		if (!rendering || Math.abs(target - renderTick) > NetTuning.INTERP_DELAY_TICKS) {
			rendering = true;
			renderTick = target;
		} else {
			long dt = Math.max(0, now - renderNanos);
			if (paused) {
				renderTick = Math.max(renderTick, Math.min(target, renderTick + dt / SimClock.DT_NANOS));
			} else {
				double free = renderTick + dt * (double) rate / SimClock.DT_NANOS;
				double next = free + (target - free) * Math.min(1.0, dt / (double) RENDER_CATCH_UP_NANOS);
				renderTick = Math.max(renderTick, next);
			}
		}
		renderNanos = now;
		return renderTick;
	}

	private static NetStats stats(Minecraft mc, long now) {
		trimArrivals(now);
		long bytes = 0;
		for (long[] a : ARRIVALS) {
			bytes += a[1];
		}
		Prediction pr = prediction;
		if (pr == null) {
			return new NetStats(mode(), rttMs(mc), PACER.leadTicks(now), PACER.lead().slack(), ARRIVALS.size(), bytes, 0);
		}
		return new NetStats(mode(), rttMs(mc), pr.leadTicks(now), pr.lead().slack(), ARRIVALS.size(), bytes,
				pr.corrections());
	}

	private static void stopDrawing() {
		fromSnapshots = new HashSet<>();
		drawnBodies = new HashSet<>();
		if (drawing) {
			drawing = false;
			ClientPoses.clear();
			CameraHost.reset();
		}
		rendering = false;
	}

	static void release() {
		closePrediction();
		drawing = false;
		rendering = false;
	}

	static void abandon() {
		closePrediction();
		stopDrawing();
	}

	static void reset() {
		hello = null;
		chosen = NetMode.NONE;
		startOver();
		hasHost = false;
		linkLevel = null;
		linkDim = null;
		HOST_DIMENSIONS.clear();
		predictFailed = false;
		ARRIVALS.clear();
		stats = NetStats.NONE;
		decodeFailed = false;
		dropLagged();
		stopDrawing();
	}
}
