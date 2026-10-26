package dev.mcrl.client.screen.tabs;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import dev.mcrl.SafeBoot;
import dev.mcrl.client.WorldRules;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.command.McrlCommands;
import dev.mcrl.natives.Breadcrumb;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;

public final class PhysicsTab implements SettingsTab {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	public static final String PHYSICS_KEY = "mcrl.settings.physics";
	private static final String RESTART_COMMAND = "mcrl physics restart";
	private static final String NO_SERVER_KEY = "mcrl.settings.no_mcrl_server", OFF_KEY = "mcrl.physics.off_refused",
			NO_PERMISSION_KEY = "mcrl.settings.needs_permission";
	private static final long REFRESH_NANOS = 1_000_000_000L;
	private static final int STATUS_H = 40, CRASH_H = 20, GAP = 4;
	private static final int WHITE = 0xFFFFFFFF, CRASH_COLOUR = 0xFFFFC060;

	private @Nullable StatusText line;
	private @Nullable Button restart;
	private @Nullable String restartTooltip;
	private long shownAt;

	@Override
	public Id id() {
		return Id.PHYSICS;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		int x = screen.left();
		int y = screen.top();
		int w = screen.width();
		boolean remoteNow = remoteWorld();
		CycleButton<Boolean> toggle = screen.add(CycleButton.booleanBuilder(Component.translatable("mcrl.settings.on"),
				Component.translatable("mcrl.settings.off"), SafeBoot.physicsOn())
				.withTooltip(v -> Tooltip.create(Component.translatable(PHYSICS_KEY + (remoteNow ? ".remote" : ".tooltip"))))
				.create(x, y, w, 20, Component.translatable(PHYSICS_KEY), (b, on) -> {
					switchPhysics(on);
					updateRestart();
				}));
		toggle.active = !remoteNow;
		y += 20 + GAP;
		Breadcrumb.Previous crash = SafeBoot.lastCrash();
		if (crash != null) {
			screen.add(new StatusText(x, y, w, CRASH_H, McrlCommands.crashLine(crash), CRASH_COLOUR));
			y += CRASH_H + GAP;
		}
		line = screen.add(new StatusText(x, y, w, STATUS_H, liveStatus()));
		shownAt = System.nanoTime();
		y += STATUS_H + GAP;
		restart = screen.add(Button.builder(Component.translatable("mcrl.settings.physics.restart"),
				b -> CollisionsTab.sendToServer(RESTART_COMMAND)).bounds(x, y, w, 20).build());
		restartTooltip = null;
		updateRestart();
	}

	private void updateRestart() {
		Button b = restart;
		if (b == null) {
			return;
		}
		String why;
		if (WorldRules.get() == null) {
			why = NO_SERVER_KEY;
		} else if (!remoteWorld() && !SafeBoot.physicsOn()) {
			why = OFF_KEY;
		} else if (!restartCommandKnown()) {
			why = NO_PERMISSION_KEY;
		} else {
			why = null;
		}
		b.active = why == null;
		if (!Objects.equals(why, restartTooltip)) {
			b.setTooltip(why == null ? null : Tooltip.create(Component.translatable(why)));
			restartTooltip = why;
		}
	}

	@Override
	public void tick() {
		StatusText l = line;
		long now = System.nanoTime();
		if (l != null && now - shownAt >= REFRESH_NANOS) {
			l.setMessage(liveStatus());
			shownAt = now;
		}
	}

	@Override
	public void closed() {
		line = null;
		restart = null;
	}

	public static void switchPhysics(boolean on) {
		McrlConfig.edit(c -> c.nativeEnabled = on);
		SafeBoot.setPhysicsOn(on);
		LOG.info("mcrl: physics switched {}", on ? "on" : "off");
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			if (on) {
				server.execute(() -> SimHosts.restartAll(server));
			} else {
				server.execute(() -> SimHosts.closeAll(server));
			}
		}
	}

	// the server sends only nodes the player may use
	private static boolean restartCommandKnown() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) {
			return false;
		}
		CommandNode<?> node = mc.getConnection().getCommands().getRoot();
		for (String part : RESTART_COMMAND.split(" ")) {
			node = node.getChild(part);
			if (node == null) {
				return false;
			}
		}
		return true;
	}

	private static boolean remoteWorld() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.getSingleplayerServer() == null;
	}

	private static Component liveStatus() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return McrlCommands.noPhysics();
		}
		if (mc.getSingleplayerServer() == null) {
			return Component.translatable("mcrl.physics.remote");
		}
		if (!SafeBoot.physicsOn()) {
			return McrlCommands.physicsOff();
		}
		SimHost host = SimHosts.get(mc.level.dimension());
		if (host == null || host.isClosed()) {
			return McrlCommands.noPhysics();
		}
		return McrlCommands.physicsStatus(mc.level.dimension().identifier().toString(), host, System.nanoTime());
	}

	public static final class StatusText extends AbstractWidget {
		private final int colour;

		public StatusText(int x, int y, int w, int h, Component message) {
			this(x, y, w, h, message, WHITE);
		}

		StatusText(int x, int y, int w, int h, Component message, int colour) {
			super(x, y, w, h, message);
			this.colour = colour;
			active = false;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			Font font = Minecraft.getInstance().font;
			int y = getY();
			for (FormattedCharSequence l : font.split(getMessage(), getWidth())) {
				if (y + font.lineHeight > getY() + getHeight()) {
					break;
				}
				g.text(font, l, getX(), y, colour);
				y += font.lineHeight + 1;
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			output.add(NarratedElementType.TITLE, getMessage());
		}
	}
}
