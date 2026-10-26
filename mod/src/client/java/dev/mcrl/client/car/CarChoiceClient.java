package dev.mcrl.client.car;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.Team;
import dev.mcrl.entity.CarChoice;
import dev.mcrl.net.McrlPayloads.CarChoiceC2S;

public final class CarChoiceClient {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");

	private CarChoiceClient() {
	}

	private static CarChoice current() {
		McrlConfig c = McrlConfig.get();
		return new CarChoice(c.car.body(), c.car.preset(), c.team == Team.ORANGE ? 1 : 0);
	}

	public static void send() {
		if (Minecraft.getInstance().getConnection() == null) {
			return;
		}
		if (ClientPlayNetworking.canSend(CarChoiceC2S.TYPE)) {
			ClientPlayNetworking.send(new CarChoiceC2S(current()));
		} else {
			LOG.info("mcrl: car choice not sent: the server does not take {}", CarChoiceC2S.TYPE.id());
		}
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (ClientPlayNetworking.canSend(CarChoiceC2S.TYPE)) {
				sender.sendPacket(new CarChoiceC2S(current()));
			}
		});
	}
}
