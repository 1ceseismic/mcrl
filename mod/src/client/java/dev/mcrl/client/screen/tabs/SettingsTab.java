package dev.mcrl.client.screen.tabs;

import java.util.Locale;

import net.minecraft.network.chat.Component;

import dev.mcrl.client.screen.McrlSettingsScreen;

public interface SettingsTab {
	enum Id {
		CAMERA, VISUALS, EFFECTS, HUD, SOUND, CONTROLS, COLLISIONS, PHYSICS, MATCH, ONLINE, CAR, SETUP
	}

	Id id();

	default Component title() {
		return Component.translatable("mcrl.settings.tab." + id().name().toLowerCase(Locale.ROOT));
	}

	void build(McrlSettingsScreen screen);

	default void tick() {
	}

	default void closed() {
	}
}
