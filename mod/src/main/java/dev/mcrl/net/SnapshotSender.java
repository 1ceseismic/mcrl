package dev.mcrl.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.match.MatchBlock;
import dev.mcrl.match.MatchManager;
import dev.mcrl.net.NetPayloads.SnapshotS2C;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.sim.SoccarState;

public final class SnapshotSender implements SimHost.SnapshotListener {
	public record Recipient(ServerPlayer player, double x, double y, double z, int ownCarId, NetMode mode) {
	}

	interface Sink {
		void send(Recipient to, SnapshotS2C snapshot);
	}

	private static final AtomicInteger HOST_IDS = new AtomicInteger();

	private final int hostId = HOST_IDS.incrementAndGet();
	private final SimHost.Kind kind;
	private final IntFunction<SimHost.InputStats> inputStats;
	private final Sink sink;
	private volatile List<Recipient> recipients = List.of();

	public SnapshotSender(SimHost host) {
		this(host.kind(), host::inputStats, (r, s) -> ServerPlayNetworking.send(r.player(), s));
	}

	SnapshotSender(SimHost.Kind kind, IntFunction<SimHost.InputStats> inputStats, Sink sink) {
		this.kind = kind;
		this.inputStats = inputStats;
		this.sink = sink;
	}

	int hostId() {
		return hostId;
	}

	public void setRecipients(List<Recipient> r) {
		recipients = List.copyOf(r);
	}

	void drop(UUID id) {
		List<Recipient> rs = recipients;
		if (rs.stream().anyMatch(r -> r.player().getUUID().equals(id))) {
			recipients = rs.stream().filter(r -> !r.player().getUUID().equals(id)).toList();
		}
	}

	public List<Recipient> recipients() {
		return recipients;
	}

	// Sim thread. The server thread sets the recipients.
	@Override
	public void onSnapshot(SimFrame f, int[] origin, SimHost.BlobReader blobs) {
		List<Recipient> rs = recipients;
		if (rs.isEmpty()) {
			return;
		}
		Map<Integer, SimFrame.CarSnap> byId = new HashMap<>();
		for (SimFrame.CarSnap c : f.cars()) {
			byId.put(c.carId(), c);
		}
		Records heads = new Records(f, origin, byId, null);
		Records withBlobs = null;
		ArenaBlock arena = arenaBlock(f);
		for (Recipient r : rs) {
			Interest.Selection sel = Interest.select(r, f, f.tick(), kind);
			Records recs = heads;
			if (r.mode() == NetMode.PREDICT) {
				if (withBlobs == null) {
					withBlobs = new Records(f, origin, byId, blobs);
				}
				if (withBlobs.complete(sel)) {
					recs = withBlobs;
				}
			}
			List<byte[]> cars = new ArrayList<>(sel.carIds().length);
			for (int id : sel.carIds()) {
				cars.add(recs.car(id));
			}
			SnapshotCodec.Header h = new SnapshotCodec.Header(f.tick(), origin, f.flags(), hostId, f.rate());
			SimHost.InputStats st = r.ownCarId() >= 0 ? inputStats.apply(r.ownCarId()) : SimHost.InputStats.NONE;
			if (st.epoch() >= 0) {
				h = h.withInput(st.epoch(), st.ackTick(), st.slack());
			}
			if (recs.blobs != null) {
				h = h.withBlobs(Math.max(0, recs.carBlobSize), recs.ballBlobSize, recs.arenaBlobSize());
			}
			byte[] data = SnapshotCodec.assemble(h, cars, sel.ball() ? recs.ball() : null, recs.arenaBlob(), arena);
			sink.send(r, new SnapshotS2C(data));
		}
	}

	static ArenaBlock arenaBlock(SimFrame f) {
		SoccarState s = f.soccar();
		return s == null ? ArenaBlock.TERRAIN
				: new ArenaBlock(ArenaBlock.KIND_SOCCAR, MatchBlock.of(s, MatchManager.header()).encode());
	}

	private static final class Records {
		final SimFrame f;
		final int[] origin;
		final Map<Integer, SimFrame.CarSnap> byId;
		final SimHost.@Nullable BlobReader blobs;
		final Map<Integer, byte[]> cars = new HashMap<>();
		final Map<Integer, byte @Nullable []> carBlobs = new HashMap<>();
		byte @Nullable [] ball, ballBlob, arenaBlob;
		boolean ballRead, arenaRead;
		int carBlobSize = -1, ballBlobSize;

		Records(SimFrame f, int[] origin, Map<Integer, SimFrame.CarSnap> byId, SimHost.@Nullable BlobReader blobs) {
			this.f = f;
			this.origin = origin;
			this.byId = byId;
			this.blobs = blobs;
		}

		boolean complete(Interest.Selection sel) {
			for (int id : sel.carIds()) {
				byte[] b = carBlob(id);
				if (b == null || carBlobSize >= 0 && b.length != carBlobSize) {
					return false;
				}
				carBlobSize = b.length;
			}
			return !sel.ball() || ballBlob() != null;
		}

		byte @Nullable [] carBlob(int id) {
			return carBlobs.computeIfAbsent(id, k -> blobs == null ? null : blobs.car(k));
		}

		byte @Nullable [] ballBlob() {
			if (!ballRead) {
				ballRead = true;
				ballBlob = blobs == null ? null : blobs.ball();
				ballBlobSize = ballBlob == null ? 0 : ballBlob.length;
			}
			return ballBlob;
		}

		byte @Nullable [] arenaBlob() {
			if (blobs == null) {
				return null;
			}
			if (!arenaRead) {
				arenaRead = true;
				arenaBlob = blobs.arena();
			}
			return arenaBlob;
		}

		int arenaBlobSize() {
			byte[] a = arenaBlob();
			return a == null ? 0 : a.length;
		}

		byte[] car(int id) {
			return cars.computeIfAbsent(id, k -> SnapshotCodec.carRecord(byId.get(k), origin,
					blobs == null ? null : carBlob(k)));
		}

		byte @Nullable [] ball() {
			SimFrame.BallSnap b = f.ball();
			if (ball == null && b != null) {
				ball = SnapshotCodec.ballRecord(b, origin, blobs == null ? null : ballBlob());
			}
			return ball;
		}
	}

	public static void leftFor(ServerPlayer player, ServerLevel to) {
		for (Map.Entry<ResourceKey<Level>, SimHost> e : SimHosts.entries()) {
			if (!e.getKey().equals(to.dimension()) && e.getValue().snapshotListener() instanceof SnapshotSender s) {
				s.drop(player.getUUID());
			}
		}
	}

	public static void updateRecipients(MinecraftServer server) {
		for (Map.Entry<ResourceKey<Level>, SimHost> e : SimHosts.entries()) {
			if (!(e.getValue().snapshotListener() instanceof SnapshotSender s)) {
				continue;
			}
			ServerLevel level = server.getLevel(e.getKey());
			List<Recipient> rs = new ArrayList<>();
			if (level != null) {
				for (ServerPlayer p : level.players()) {
					NetMode m = NetPeers.mode(p.getUUID());
					if (m == null || m == NetMode.NONE) {
						continue;
					}
					int own = p.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == p ? car.carId()
							: -1;
					rs.add(new Recipient(p, p.getX(), p.getY(), p.getZ(), own, m));
				}
			}
			if (!rs.isEmpty() || !s.recipients().isEmpty()) {
				s.setRecipients(rs);
			}
		}
	}
}
