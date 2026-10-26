package dev.mcrl.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.network.chat.Component;

import dev.mcrl.client.camera.CameraHost;
import dev.mcrl.client.car.CarChoiceClient;
import dev.mcrl.client.command.McrlClientCommands;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.Visuals;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxActors;
import dev.mcrl.client.fx.effects.FxContext;
import dev.mcrl.client.hud.DriveHud;
import dev.mcrl.client.hud.MatchHud;
import dev.mcrl.client.hud.VanillaHudHider;
import dev.mcrl.client.input.GamepadSource;
import dev.mcrl.client.input.McrlKeys;
import dev.mcrl.client.link.ClientPoses;
import dev.mcrl.client.link.KnockReceiver;
import dev.mcrl.client.link.LocalSimLink;
import dev.mcrl.client.link.NetSimLink;
import dev.mcrl.client.match.MatchClientState;
import dev.mcrl.client.match.MatchCrowd;
import dev.mcrl.client.match.MatchFx;
import dev.mcrl.client.render.Renderers;
import dev.mcrl.client.render.look.CarLooks;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.client.screen.CrashNoticeScreen;
import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.client.screen.tabs.SettingsTab;
import dev.mcrl.client.sound.RlAudio;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.rlassets.ToolRunner;
import dev.mcrl.sim.SimFrame;

public class McrlClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			int killed = ToolRunner.shutdown();
			if (killed > 0) {
				LOG.info("mcrl: stopped {} RL conversion tool(s) on quit", killed);
			}
		});
		CrashNoticeScreen.register();

		McrlKeys.register();
		GamepadSource.register();
		LocalSimLink.register();
		NetSimLink.register();
		CarEntity.setClientDemoedSource(id -> {
			SimFrame.CarSnap s = ClientPoses.carSnap(id);
			return s != null && s.demoed();
		});
		CarChoiceClient.register();
		CarLooks.register();
		WorldRules.register();
		MatchClientState.register();
		MatchFx.register();
		MatchCrowd.register();
		KnockReceiver.register();
		// frame listeners run in order: signals before their readers
		FrameSignals.register();
		FxActors.register();
		CameraHost.register();
		FxContext.register();

		McrlConfig.get();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (McrlKeys.SETTINGS.consumeClick()) {
				if (mc.gui.screen() == null && mc.player != null) {
					mc.gui.setScreen(new McrlSettingsScreen(null, SettingsTab.Id.CAMERA));
				}
			}
		});

		Renderers.register();
		DriveHud.register();
		MatchHud.register();
		VanillaHudHider.register();
		McrlClientCommands.register();

		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			McrlConfig cfg = McrlConfig.get();
			if (cfg.visuals == Visuals.RL || cfg.soundVolume > 0f) {
				RlConversion.game().start();
			}
		});
		RlAudio.register();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null) {
				for (String line : RlConversion.game().takeMessages()) {
					mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[mcrl] " + line));
				}
			}
		});
	}
}
