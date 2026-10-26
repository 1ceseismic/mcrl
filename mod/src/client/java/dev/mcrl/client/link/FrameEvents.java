package dev.mcrl.client.link;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

import dev.mcrl.sim.SimFrame;

public final class FrameEvents {
	public interface Listener {
		void frame(Minecraft mc, SimFrame frame, long frameNanos);
	}

	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	private FrameEvents() {
	}

	public static void register(Listener l) {
		LISTENERS.add(Objects.requireNonNull(l));
	}

	public static void dispatch(Minecraft mc) {
		SimFrame frame = ClientPoses.frame();
		long frameNanos = LocalSimLink.frameNanos();
		for (Listener l : LISTENERS) {
			try {
				l.frame(mc, frame, frameNanos);
			} catch (RuntimeException e) {
				LISTENERS.remove(l);
				LOG.error("mcrl: a per-frame listener ({}) failed; it is removed until Minecraft restarts",
						l.getClass().getName(), e);
			}
		}
	}
}
