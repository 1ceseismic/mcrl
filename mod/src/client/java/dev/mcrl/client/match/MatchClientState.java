package dev.mcrl.client.match;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import dev.mcrl.match.MatchBlock;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.net.MatchPayloads.MatchEventS2C;
import dev.mcrl.net.MatchPayloads.MatchS2C;
import dev.mcrl.net.MatchPayloads.MatchSetupS2C;

public final class MatchClientState {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static @Nullable MatchBlock block;
	private static @Nullable Boolean predictedBallOut;
	private static @Nullable MatchSetupS2C setup;
	private static final List<Consumer<MatchEventS2C>> LISTENERS = new CopyOnWriteArrayList<>();

	private MatchClientState() {
	}

	public static boolean active() {
		return block != null;
	}

	public static @Nullable MatchBlock block() {
		return block;
	}

	public static @Nullable MatchSetupS2C setup() {
		return setup;
	}

	public static boolean ballOutOfPlay() {
		Boolean predicted = predictedBallOut;
		if (predicted != null) {
			return predicted;
		}
		MatchBlock b = block;
		return b != null && b.header().ballOutOfPlay();
	}

	public static void predictedBallOut(@Nullable Boolean out) {
		predictedBallOut = out;
	}

	public static void fromSnapshot(byte[] body) {
		try {
			block = MatchBlock.decode(body);
		} catch (IllegalArgumentException e) {
			LOG.warn("mcrl: match block skipped: {}", e.getMessage());
		}
	}

	public static void addListener(Consumer<MatchEventS2C> l) {
		LISTENERS.add(l);
	}

	private static void clear() {
		block = null;
		setup = null;
		predictedBallOut = null;
	}

	private static void received(MatchS2C m) {
		try {
			block = MatchBlock.decode(m.block());
		} catch (IllegalArgumentException e) {
			LOG.warn("mcrl: match block skipped: {}", e.getMessage());
		}
	}

	private static void received(MatchSetupS2C s) {
		if (s.size() != null) {
			setup = s;
		}
	}

	private static void received(MatchEventS2C e) {
		if (!e.known()) {
			return;
		}
		for (Consumer<MatchEventS2C> l : LISTENERS) {
			l.accept(e);
		}
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MatchS2C.TYPE, (p, ctx) -> received(p));
		ClientPlayNetworking.registerGlobalReceiver(MatchSetupS2C.TYPE, (p, ctx) -> received(p));
		ClientPlayNetworking.registerGlobalReceiver(MatchEventS2C.TYPE, (p, ctx) -> received(p));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(MatchClientState::clear));
		ClientTickEvents.END_CLIENT_TICK.register(MatchClientState::tick);
	}

	private static void tick(Minecraft mc) {
		if ((block != null || setup != null)
				&& (mc.level == null || !mc.level.dimension().equals(StadiumConst.KEY))) {
			clear();
		}
	}
}
