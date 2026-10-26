package dev.mcrl.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.natives.NativeBoot;
import dev.mcrl.net.NetPayloads.HelloAckC2S;
import dev.mcrl.net.NetPayloads.HelloS2C;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;

public final class NetPeers {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Map<UUID, NetMode> MODES = new ConcurrentHashMap<>();

	private NetPeers() {
	}

	public static void put(UUID player, NetMode mode) {
		MODES.put(player, mode);
	}

	public static @Nullable NetMode mode(UUID player) {
		return MODES.get(player);
	}

	public static void remove(UUID player) {
		MODES.remove(player);
	}

	static NetMode granted(HelloAckC2S ack, int abi, long buildHash) {
		if (ack.protocol() != NetProtocol.VERSION) {
			return NetMode.NONE;
		}
		if (ack.mode() == NetMode.PREDICT && (ack.abi() != abi || ack.buildHash() != buildHash)) {
			return NetMode.INTERPOLATE;
		}
		return ack.mode();
	}

	static HelloS2C hello(ServerPlayer player) {
		SimHost h = SimHosts.get(player.level().dimension());
		return new HelloS2C(NetProtocol.VERSION, McrlNative.EXPECTED_ABI_VERSION, NativeBoot.buildHash(),
				h == null ? 0 : h.latest().tick(), SimClock.TICK_RATE, NetProtocol.SNAP_INTERVAL_TICKS,
				ArenaMeshes.identity());
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.player;
			if (!server.isSingleplayerOwner(p.nameAndId()) && ServerPlayNetworking.canSend(p, HelloS2C.TYPE)) {
				ServerPlayNetworking.send(p, hello(p));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(HelloAckC2S.TYPE, (ack, ctx) -> ctx.server().execute(() -> {
			NetMode m = granted(ack, McrlNative.EXPECTED_ABI_VERSION, NativeBoot.buildHash());
			if (m == NetMode.PREDICT && ack.arenaMeshes() != ArenaMeshes.identity()) {
				LOG.info("mcrl: {} has other stadium meshes: it follows the stadium's snapshots",
						ctx.player().getName().getString());
			}
			put(ctx.player().getUUID(), m);
		}));
		// Advertises the channel; the Netty mixin takes inputs first.
		ServerPlayNetworking.registerGlobalReceiver(InputC2S.TYPE, NetPeers::lateInput);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			remove(handler.player.getUUID());
			InputMailbox.SERVER.unbind(handler.player.getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(NetPeers::clear);
	}

	private static void lateInput(InputC2S in, ServerPlayNetworking.Context ctx) {
		InputMailbox.SERVER.receive(ctx.player().getUUID(), in);
	}

	private static void clear(MinecraftServer server) {
		MODES.clear();
		InputMailbox.SERVER.clear();
	}
}
