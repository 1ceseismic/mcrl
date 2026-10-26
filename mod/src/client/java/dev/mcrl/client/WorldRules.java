package dev.mcrl.client;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.net.McrlPayloads.RulesS2C;

public final class WorldRules {
	private static volatile @Nullable RulesS2C latest;

	private WorldRules() {
	}

	public static @Nullable RulesS2C get() {
		return latest;
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RulesS2C.TYPE, (payload, ctx) -> {
			latest = payload;
			if (ctx.client().gui.screen() instanceof McrlSettingsScreen s) {
				s.onRulesChanged();
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> latest = null);
	}
}
