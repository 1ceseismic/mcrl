package dev.mcrl.client.input;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class McrlKeys {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mcrl", "driving"));

	public static final KeyMapping THROTTLE = key("key.mcrl.throttle", InputConstants.KEY_W);
	public static final KeyMapping REVERSE = key("key.mcrl.reverse", InputConstants.KEY_S);
	public static final KeyMapping STEER_LEFT = key("key.mcrl.steer_left", InputConstants.KEY_A);
	public static final KeyMapping STEER_RIGHT = key("key.mcrl.steer_right", InputConstants.KEY_D);
	public static final KeyMapping JUMP = mouse("key.mcrl.jump", InputConstants.MOUSE_BUTTON_RIGHT);
	public static final KeyMapping BOOST = mouse("key.mcrl.boost", InputConstants.MOUSE_BUTTON_LEFT);
	public static final KeyMapping POWERSLIDE = key("key.mcrl.powerslide", InputConstants.KEY_LSHIFT);
	public static final KeyMapping AIR_ROLL_LEFT = key("key.mcrl.air_roll_left", InputConstants.KEY_Q);
	public static final KeyMapping AIR_ROLL_RIGHT = key("key.mcrl.air_roll_right", InputConstants.KEY_E);
	public static final KeyMapping BALL_CAM = key("key.mcrl.ball_cam", InputConstants.KEY_SPACE);
	public static final KeyMapping EXIT_CAR = key("key.mcrl.exit_car", InputConstants.KEY_G);
	public static final KeyMapping RESET_CAR = key("key.mcrl.reset_car", InputConstants.KEY_R);
	public static final KeyMapping SETTINGS = key("key.mcrl.settings", InputConstants.KEY_M);

	private McrlKeys() {
	}

	public static void register() {
		for (KeyMapping k : new KeyMapping[] {THROTTLE, REVERSE, STEER_LEFT, STEER_RIGHT, JUMP, BOOST, POWERSLIDE,
				AIR_ROLL_LEFT, AIR_ROLL_RIGHT, BALL_CAM, EXIT_CAR, RESET_CAR, SETTINGS}) {
			KeyMappingHelper.registerKeyMapping(k);
		}
	}

	private static KeyMapping key(String name, int scancode) {
		return new KeyMapping(name, InputConstants.Type.KEYBOARD, scancode, CATEGORY);
	}

	private static KeyMapping mouse(String name, int button) {
		return new KeyMapping(name, InputConstants.Type.MOUSE, button, CATEGORY);
	}
}
