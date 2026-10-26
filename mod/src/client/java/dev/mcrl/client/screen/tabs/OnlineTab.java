package dev.mcrl.client.screen.tabs;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.link.NetSimLink;
import dev.mcrl.client.link.NetStats;
import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.net.NetMode;
import dev.mcrl.net.NetProtocol;

public final class OnlineTab implements SettingsTab {
	private static final String PREDICTION_KEY = "mcrl.settings.online.prediction";
	private static final String STATUS_PREFIX = "mcrl.settings.online.status.";
	private static final long REFRESH_NANOS = 1_000_000_000L;
	private static final int LINE_H = 20, GAP = 4;

	private PhysicsTab.@Nullable StatusText statusLine, statsLine;
	private long shownAt;
	private long lastCorrections = -1, lastAt;

	@Override
	public Id id() {
		return Id.ONLINE;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		int x = screen.left();
		int y = screen.top();
		int w = screen.width();
		long now = System.nanoTime();
		lastCorrections = -1;
		statusLine = screen.add(new PhysicsTab.StatusText(x, y, w, LINE_H, liveStatus()));
		y += LINE_H + GAP;
		statsLine = screen.add(new PhysicsTab.StatusText(x, y, w, LINE_H, statsLine(now)));
		shownAt = now;
		y += LINE_H + GAP;
		screen.add(CycleButton.onOffBuilder(McrlConfig.get().onlinePrediction)
				.withTooltip(v -> Tooltip.create(Component.translatable(PREDICTION_KEY + ".tooltip")))
				.create(x, y, w, 20, Component.translatable(PREDICTION_KEY), (b, on) -> setPrediction(on)));
	}

	public static void setPrediction(boolean on) {
		McrlConfig.edit(c -> c.onlinePrediction = on);
	}

	@Override
	public void tick() {
		long now = System.nanoTime();
		if (now - shownAt < REFRESH_NANOS) {
			return;
		}
		shownAt = now;
		PhysicsTab.StatusText s = statusLine, n = statsLine;
		if (s != null) {
			s.setMessage(liveStatus());
		}
		if (n != null) {
			n.setMessage(statsLine(now));
		}
	}

	@Override
	public void closed() {
		statusLine = null;
		statsLine = null;
	}

	private Component statsLine(long now) {
		NetStats st = NetSimLink.stats();
		long perSecond = 0;
		if (lastCorrections >= 0 && now > lastAt) {
			long d = st.corrections() >= lastCorrections ? st.corrections() - lastCorrections : st.corrections();
			perSecond = Math.round(d * 1e9 / (now - lastAt));
		}
		lastCorrections = st.corrections();
		lastAt = now;
		if (st.mode() == NetMode.NONE) {
			return Component.empty();
		}
		return Component.translatable("mcrl.settings.online.stats", st.rttMs(), st.leadTicks(), st.slack(),
				Math.round(st.snapHz()), String.format(Locale.ROOT, "%.1f", st.bytesPerSec() / 1000), perSecond);
	}

	private static Component liveStatus() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null) {
			return Component.translatable(STATUS_PREFIX + "local");
		}
		if (mc.getConnection() == null) {
			return Component.translatable(STATUS_PREFIX + "offline");
		}
		NetMode mode = NetSimLink.mode();
		if (mode == NetMode.INTERPOLATE && NetSimLink.stadiumMeshesMissing()) {
			return Component.translatable(STATUS_PREFIX + "no_stadium_meshes");
		}
		return status(mode, NetSimLink.serverProtocol());
	}

	private static Component status(NetMode mode, int serverProtocol) {
		return switch (mode) {
			case PREDICT -> Component.translatable(STATUS_PREFIX + "predict");
			case INTERPOLATE -> Component.translatable(STATUS_PREFIX + "interpolate");
			case NONE -> serverProtocol < 0 ? Component.translatable(STATUS_PREFIX + "no_mcrl")
					: Component.translatable(STATUS_PREFIX + "none", serverProtocol, NetProtocol.VERSION);
		};
	}
}
