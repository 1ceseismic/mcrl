package dev.mcrl.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;
import dev.mcrl.client.match.MatchClientState;
import dev.mcrl.client.render.look.RlLook;
import dev.mcrl.match.Match;
import dev.mcrl.match.MatchBlock;
import dev.mcrl.match.MatchRules;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.net.MatchPayloads.MatchEventS2C;

public final class MatchHud {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "match");
	private static final double KMH_PER_UU = 0.036;
	private static final int SIM_TICKS_PER_TICK = 6;
	private static final int BANNER_TICKS = 60;
	private static final int WHITE = 0xFFFFFFFF, CLOCK_BACK = 0xC0101018, PANEL_ALPHA = 0xD0;
	private static final int TOP = 6, PANEL_W = 48, PANEL_H = 20, CLOCK_W = 56;
	static final int BOTTOM = TOP + PANEL_H;
	private static final float COUNTDOWN_SCALE = 4f, BANNER_SCALE = 2f;
	private static final double BANNER_TOP = 0.28;
	private static long ticks;
	private static long lockEnd = -1;
	private static int lastCountdown;
	private static String countdown = "", clock = "";
	private static @Nullable List<Component> banner;
	private static long bannerUntil;
	private static int endWinner = -1;
	private static boolean endHeard;

	private MatchHud() {
	}

	public static boolean showing(Minecraft mc) {
		return MatchClientState.active() && mc.level != null && mc.level.dimension().equals(StadiumConst.KEY);
	}

	public static void register() {
		HudElementRegistry.addLast(ID, MatchHud::extract);
		ClientTickEvents.END_CLIENT_TICK.register(MatchHud::tick);
		MatchClientState.addListener(MatchHud::event);
	}

	private static void tick(Minecraft mc) {
		ticks++;
		MatchBlock b = MatchClientState.block();
		if (b == null || !showing(mc)) {
			lockEnd = -1;
			lastCountdown = 0;
			countdown = "";
			clock = "";
			banner = null;
			endWinner = -1;
			endHeard = false;
			return;
		}
		MatchBlock.Header h = b.header();
		long now = ticks * SIM_TICKS_PER_TICK;
		if (lastCountdown > 0 && h.countdown() == 0 && h.phaseOrNull() == Match.Phase.PLAYING) {
			lockEnd = now;
		}
		lastCountdown = h.countdown();
		countdown = countdownText(h.countdown(), now, lockEnd).getString();
		clock = clockText(h.clockSeconds(), h.overtime());
		if (banner != null && ticks >= bannerUntil) {
			banner = null;
		}
	}

	private static void event(MatchEventS2C e) {
		if (e.kind() == MatchEventS2C.Kind.GOAL) {
			banner = goalLines(e);
			bannerUntil = ticks + BANNER_TICKS;
		} else if (e.kind() == MatchEventS2C.Kind.END) {
			endWinner = e.team();
			endHeard = true;
		}
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		MatchBlock b = MatchClientState.block();
		if (b == null || !showing(mc) || mc.gui.hud.isHidden()) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		MatchBlock.Header hd = b.header();
		int y = TOP;
		panel(g, font, blueX(w), y, PANEL_W, argb(RlLook.TEAM_BLUE_SRGB),
				Integer.toString(hd.blue()));
		panel(g, font, clockX(w), y, CLOCK_W, CLOCK_BACK, clock);
		panel(g, font, orangeX(w), y, PANEL_W, argb(RlLook.TEAM_ORANGE_SRGB),
				Integer.toString(hd.orange()));
		if (!countdown.isEmpty()) {
			centred(g, font, Component.literal(countdown), w / 2f, h / 2f - font.lineHeight * COUNTDOWN_SCALE,
					COUNTDOWN_SCALE);
		}
		List<Component> lines = hd.phaseOrNull() == Match.Phase.ENDED
				? endLines(winner(hd), hd.blue(), hd.orange()) : banner;
		if (lines != null) {
			float s = BANNER_SCALE, top = (float) (h * BANNER_TOP);
			for (int i = 0; i < lines.size(); i++) {
				centred(g, font, lines.get(i), w / 2f, top + i * (font.lineHeight + 2) * s, i == 0 ? s : s * 0.75f);
			}
		}
	}

	private static int winner(MatchBlock.Header h) {
		if (endHeard) {
			return endWinner;
		}
		return h.blue() > h.orange() ? 0 : h.orange() > h.blue() ? 1 : -1;
	}

	private static void panel(GuiGraphicsExtractor g, Font font, int x, int y, int pw, int back, String text) {
		g.fill(x, y, x + pw, y + PANEL_H, back);
		g.text(font, text, x + (pw - font.width(text)) / 2, y + (PANEL_H - font.lineHeight) / 2 + 1, WHITE,
				true);
	}

	private static void centred(GuiGraphicsExtractor g, Font font, Component text, float cx, float top, float scale) {
		g.pose().pushMatrix();
		g.pose().translate(cx, top);
		g.pose().scale(scale);
		g.text(font, text, -font.width(text) / 2, 0, WHITE, true);
		g.pose().popMatrix();
	}

	private static int argb(float[] srgb) {
		int r = Math.round(srgb[0] * 255), gr = Math.round(srgb[1] * 255), b = Math.round(srgb[2] * 255);
		return PANEL_ALPHA << 24 | r << 16 | gr << 8 | b;
	}

	private static String clockText(int seconds, boolean overtime) {
		int s = Math.max(0, seconds);
		String t = String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
		return overtime ? "+" + t : t;
	}

	private static String speedText(float uu) {
		return Math.round(uu * KMH_PER_UU) + " KM/H";
	}

	private static Component countdownText(int countdown, long tick, long lockEndTick) {
		if (countdown > 0) {
			return Component.literal(Integer.toString(countdown));
		}
		if (lockEndTick >= 0 && tick >= lockEndTick && tick - lockEndTick < MatchRules.GO_SHOW_TICKS) {
			return Component.translatableWithFallback("mcrl.match.go", "GO!");
		}
		return Component.empty();
	}

	private static List<Component> goalLines(MatchEventS2C e) {
		List<Component> out = new ArrayList<>(4);
		out.add(Component.translatableWithFallback("mcrl.match.goal_banner", "GOAL!"));
		if (!e.scorer().isEmpty()) {
			out.add(Component.literal(e.scorer()));
		}
		if (!e.assist().isEmpty()) {
			out.add(Component.translatableWithFallback("mcrl.match.assist", "Assist: %s", e.assist()));
		}
		out.add(Component.literal(speedText(e.speedUu())));
		return out;
	}

	private static List<Component> endLines(int winner, int blue, int orange) {
		Component title = switch (winner) {
			case 0 -> Component.translatableWithFallback("mcrl.match.blue_wins", "BLUE WINS");
			case 1 -> Component.translatableWithFallback("mcrl.match.orange_wins", "ORANGE WINS");
			default -> Component.translatableWithFallback("mcrl.match.ended", "MATCH ENDED");
		};
		return List.of(title, Component.literal(blue + " - " + orange));
	}

	private static int blueX(int w) {
		return w / 2 - CLOCK_W / 2 - PANEL_W;
	}

	private static int clockX(int w) {
		return w / 2 - CLOCK_W / 2;
	}

	private static int orangeX(int w) {
		return w / 2 - CLOCK_W / 2 + CLOCK_W;
	}
}
