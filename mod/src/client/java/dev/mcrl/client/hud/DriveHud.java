package dev.mcrl.client.hud;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.mcrl.Mcrl;
import dev.mcrl.SafeBoot;
import dev.mcrl.client.camera.CameraHost;
import dev.mcrl.client.camera.FrameClock;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.link.ClientPoses;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.link.LocalSimLink;
import dev.mcrl.client.match.ClientTeams;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.sim.SimFrame;

public final class DriveHud {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "drive");
	private static final int MARGIN = 8;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int SPEED_GAP = 4;
	private static final int BADGE_BACK = 0xB0000000;
	private static final StepUpBadge STEP_UP = new StepUpBadge();
	private static final BallCamIndicator BALL_CAM = new BallCamIndicator();
	private static final FrameClock CLOCK = new FrameClock();
	private static boolean riding, stepUpBaseline;

	private DriveHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(ID, DriveHud::extract);
		FrameEvents.register((mc, frame, frameNanos) -> {
			CLOCK.advance(frameNanos);
			double now = CLOCK.now();
			BoostMeterHud.textures().refresh();
			CarEntity car = LocalSimLink.ridingCar(mc) && mc.player.getVehicle() instanceof CarEntity c ? c : null;
			if (car != null && !riding) {
				BoostMeterHud.newRide();
				stepUpBaseline = true;
			}
			if (car != null) {
				stepUpBaseline = stepUpFrame(STEP_UP, stepUpBaseline, frame.car(car.carId()) != null, frame.flags(), now);
			}
			BALL_CAM.update(car != null && CameraHost.ballCamOn() && McrlConfig.get().hud.ballCamIndicator(), now);
			riding = car != null;
		});
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!LocalSimLink.ridingCar(mc) || mc.gui.hud.isHidden() || !(mc.player.getVehicle() instanceof CarEntity car)) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		SimFrame.CarSnap snap = ClientPoses.carSnap(car.getId());
		if (snap != null) {
			HudSettings hud = McrlConfig.get().hud;
			String speed = speedText(snap.vx(), snap.vy(), snap.vz());
			if (hud.boostMeter()) {
				double top = BoostMeterHud.extract(g, mc, snap.boost(), ClientTeams.meter(ClientTeams.of(car)),
						CLOCK.now());
				if (hud.speed()) {
					int sw = mc.getWindow().getWidth(), sh = mc.getWindow().getHeight();
					double cx = HudLayout.meterCentre(sw, sh)[0] / mc.getWindow().getGuiScale();
					int x = (int) Math.round(cx - font.width(speed) / 2.0);
					g.text(font, speed, x, (int) Math.floor(top) - SPEED_GAP - font.lineHeight, WHITE, true);
				}
			} else if (hud.speed()) {
				g.text(font, speed, w - MARGIN - font.width(speed), h - MARGIN - font.lineHeight, WHITE, true);
			}
		}
		if (McrlConfig.get().hud.ballCamIndicator()) {
			BALL_CAM.extract(g, mc, BoostMeterHud.textures(), CLOCK.now());
		}
		int y = MatchHud.showing(mc) ? MatchHud.BOTTOM + MARGIN : MARGIN;
		boolean off = !SafeBoot.physicsOn() && mc.getSingleplayerServer() != null;
		for (String badge : badges(badgeFlags(ClientPoses.frame(), car.carId()), off)) {
			int tw = font.width(badge), x = (w - tw) / 2;
			g.fill(x - 4, y - 3, x + tw + 4, y + font.lineHeight + 2, BADGE_BACK);
			g.text(font, badge, x, y, WHITE, false);
			y += font.lineHeight + 8;
		}
		if (STEP_UP.visible()) {
			stepUpBadge(g, font, w, y, STEP_UP.alpha(), STEP_UP.on());
		}
	}

	private static void stepUpBadge(GuiGraphicsExtractor g, Font font, int w, int y, float alpha, boolean on) {
		Component text = Component.translatable(on ? "mcrl.hud.step_up_on" : "mcrl.hud.step_up_off");
		int icon = MeterStyle.STEP_UP_ICON, gap = 4;
		int tw = icon + gap + font.width(text), x = (w - tw) / 2;
		int a = Math.round(alpha * 255), fg = a << 24 | 0xFFFFFF, back = Math.round(alpha * (BADGE_BACK >>> 24)) << 24;
		int top = y + font.lineHeight / 2 - icon / 2;
		g.fill(x - 4, top - 2, x + tw + 4, top + icon + 2, back);
		int step = icon / 3;
		for (int i = 0; i < 3; i++) {
			g.fill(x + i * step, top + icon - (i + 1) * step, x + icon, top + icon - i * step, fg);
		}
		g.text(font, text, x + icon + gap, y, fg, false);
	}

	static boolean stepUpFrame(StepUpBadge badge, boolean baselinePending, boolean hasCar, int flags, double now) {
		if (!hasCar) {
			return baselinePending;
		}
		badge.update((flags & SimFrame.STEPUP) != 0, baselinePending, now);
		return false;
	}

	static String speedText(double vx, double vy, double vz) {
		long uu = Math.round(Math.sqrt(vx * vx + vy * vy + vz * vz) * UU_PER_BLOCK);
		return String.format(Locale.ROOT, "%4d uu/s", uu);
	}

	static int badgeFlags(SimFrame f, int carId) {
		if ((f.flags() & SimFrame.FROZEN) == 0 || !f.anyCarHeld()) {
			return f.flags();
		}
		SimFrame.CarSnap mine = f.car(carId);
		return mine != null && mine.held() ? f.flags() : f.flags() & ~SimFrame.FROZEN;
	}

	static List<String> badges(int flags, boolean physicsOff) {
		List<String> out = new ArrayList<>(3);
		if (physicsOff) {
			out.add("PHYSICS OFF");
		}
		if ((flags & SimFrame.FROZEN) != 0) {
			out.add("Loading terrain");
		}
		if ((flags & SimFrame.PAUSED) != 0) {
			out.add("PAUSED");
		}
		if ((flags & SimFrame.STALLED) != 0) {
			out.add("PHYSICS STALLED - /mcrl physics restart");
		}
		if ((flags & SimFrame.FAULT) != 0) {
			out.add("PHYSICS FAULT - /mcrl physics restart");
		}
		return out;
	}
}
