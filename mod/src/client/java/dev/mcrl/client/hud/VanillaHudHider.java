package dev.mcrl.client.hud;

import java.util.Set;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.link.LocalSimLink;

public final class VanillaHudHider {
	private static final Set<Identifier> HIDDEN = Set.of(VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR,
			VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
			VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
			VanillaHudElements.HELD_ITEM_TOOLTIP);

	private VanillaHudHider() {
	}

	private static boolean hides(Identifier element, boolean riding, boolean setting) {
		return riding && setting && HIDDEN.contains(element);
	}

	public static void register() {
		for (Identifier id : HIDDEN) {
			HudElementRegistry.replaceElement(id, original -> (graphics, delta) -> {
				if (hides(id, LocalSimLink.ridingCar(Minecraft.getInstance()), McrlConfig.get().hud.hideVanilla())) {
					return;
				}
				original.extractRenderState(graphics, delta);
			});
		}
	}
}
