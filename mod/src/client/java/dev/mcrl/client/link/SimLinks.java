package dev.mcrl.client.link;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

public final class SimLinks {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final LinkGuard GUARD = new LinkGuard(
			e -> LOG.error("mcrl: the sim link failed; it stays idle (no pacing, no input) until a world is joined", e),
			() -> {
				LocalSimLink.abandon();
				NetSimLink.abandon();
			});

	private SimLinks() {
	}

	public static void onFrame(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		Object session = server != null ? server : mc.getConnection();
		GUARD.run(session, () -> frame(mc, server), () -> idleFrame(mc));
	}

	private static void frame(Minecraft mc, @Nullable IntegratedServer server) {
		LinkInput in = LinkInput.sample();
		if (server == null && mc.getConnection() != null) {
			LocalSimLink.release();
			NetSimLink.frame(mc, in);
		} else {
			NetSimLink.release();
			LocalSimLink.frame(mc, in);
		}
	}

	public static boolean failed() {
		return GUARD.idle();
	}

	private static void idleFrame(Minecraft mc) {
		LocalSimLink.setFrameNanos(System.nanoTime());
		LocalSimLink.sendExitIfPressed(mc, LinkInput.sample().exit());
	}
}
