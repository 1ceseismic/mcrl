package dev.mcrl.client.screen;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import dev.mcrl.SafeBoot;
import dev.mcrl.client.screen.tabs.PhysicsTab;
import dev.mcrl.natives.Breadcrumb;

public final class CrashNoticeScreen extends Screen {
	public static final String TURN_OFF_KEY = "mcrl.crash.turn_off";
	private static final int BUTTON_W = 150, GAP = 10;
	private static boolean registered;
	private static boolean checked;

	private final @Nullable Screen parent;
	private final Breadcrumb.Previous crash;

	public CrashNoticeScreen(@Nullable Screen parent, Breadcrumb.Previous crash) {
		super(Component.translatableWithFallback("mcrl.crash.title", "mcrl: the last session crashed"));
		this.parent = parent;
		this.crash = crash;
	}

	public static boolean pending() {
		return registered && !checked;
	}

	public static void register() {
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (checked || mc.gui.overlay() != null || !(mc.gui.screen() instanceof TitleScreen title)) {
				return;
			}
			checked = true;
			Breadcrumb.Previous crash = SafeBoot.lastCrash();
			String property = System.getProperty("mcrl.crashNotice");
			if (crash != null && (property == null || !property.trim().equalsIgnoreCase("off"))) {
				mc.gui.setScreen(new CrashNoticeScreen(title, crash));
			}
		});
	}

	@Override
	protected void init() {
		int w = Math.min(width - 40, 2 * BUTTON_W + GAP);
		int y = 40;
		Component busy = Component.translatableWithFallback("mcrl.crash.body",
				"Minecraft stopped while mcrl's physics was busy (%s at section %s %s %s).",
				crash.op().name().toLowerCase(Locale.ROOT), crash.x(), crash.y(), crash.z());
		Component advice = Component.translatableWithFallback("mcrl.crash.advice",
				"If this keeps happening, turn physics off: cars stay in your worlds but do not move until you turn it "
						+ "back on in mcrl settings (M) > Physics.");
		for (Component c : new Component[] {busy, advice}) {
			MultiLineTextWidget t = new MultiLineTextWidget(c, font).setMaxWidth(w).setCentered(true);
			t.setPosition((width - t.getWidth()) / 2, y);
			addRenderableWidget(t);
			y += t.getHeight() + GAP;
		}
		y = Math.min(y + GAP, height - 20 - GAP);
		int left = width / 2 - BUTTON_W - GAP / 2;
		addRenderableWidget(Button.builder(Component.translatableWithFallback(TURN_OFF_KEY, "Turn physics off"), b -> {
			PhysicsTab.switchPhysics(false);
			onClose();
		}).bounds(left, y, BUTTON_W, 20).build());
		addRenderableWidget(Button.builder(Component.translatableWithFallback("mcrl.crash.keep_on", "Keep physics on"),
				b -> onClose()).bounds(left + BUTTON_W + GAP, y, BUTTON_W, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
