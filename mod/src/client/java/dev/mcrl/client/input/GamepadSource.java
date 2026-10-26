package dev.mcrl.client.input;

import java.nio.IntBuffer;

import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLGamepad;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLStdinc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class GamepadSource {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final int HOTPLUG_TICKS = 20;
	private static final float AXIS_MAX = 32767f;

	private static boolean enabled;
	private static @Nullable String decision;
	private static long pad;
	private static int ticks;

	private GamepadSource() {
	}

	public static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> start());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (enabled && ++ticks >= HOTPLUG_TICKS) {
				ticks = 0;
				hotplug();
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stop());
	}

	static @Nullable String offReason(@Nullable String gamepadProperty, @Nullable String sdlVideoDriver,
			boolean clientGametest) {
		if ("off".equalsIgnoreCase(gamepadProperty)) {
			return "-Dmcrl.gamepad=off";
		}
		if ("offscreen".equalsIgnoreCase(sdlVideoDriver)) {
			return "SDL_VIDEO_DRIVER=offscreen";
		}
		if (clientGametest) {
			return "Fabric client gametest (-Dfabric.client.gametest)";
		}
		return null;
	}

	private static void start() {
		if (decision != null) {
			return;
		}
		String off = offReason(System.getProperty("mcrl.gamepad"), System.getenv("SDL_VIDEO_DRIVER"),
				System.getProperty("fabric.client.gametest") != null);
		if (off != null) {
			decision = "OFF (" + off + ")";
			LOG.info("mcrl gamepad: {}: the SDL gamepad subsystem is not initialised", decision);
			return;
		}
		// MC initialises only SDL video and swallows gamepad events
		try {
			if ((SDLInit.SDL_WasInit(SDLInit.SDL_INIT_GAMEPAD) & SDLInit.SDL_INIT_GAMEPAD) != 0) {
				decision = "OFF (someone else initialised the SDL gamepad subsystem)";
			} else if (SDLInit.SDL_InitSubSystem(SDLInit.SDL_INIT_GAMEPAD)) {
				enabled = true;
				decision = "ON (SDL gamepad subsystem initialised)";
			} else {
				decision = "OFF (SDL_InitSubSystem(GAMEPAD) failed: " + SDLError.SDL_GetError() + ")";
			}
		} catch (Throwable t) {
			decision = "OFF (" + t + ")";
		}
		LOG.info("mcrl gamepad: {}", decision);
		if (enabled) {
			hotplug();
		}
	}

	private static void stop() {
		if (!enabled) {
			return;
		}
		close();
		SDLInit.SDL_QuitSubSystem(SDLInit.SDL_INIT_GAMEPAD);
		enabled = false;
	}

	private static void hotplug() {
		if (pad != 0 && !SDLGamepad.SDL_GamepadConnected(pad)) {
			LOG.info("mcrl gamepad: controller disconnected");
			close();
		}
		if (pad != 0) {
			return;
		}
		IntBuffer ids = SDLGamepad.SDL_GetGamepads();
		if (ids == null) {
			return;
		}
		try {
			if (ids.remaining() > 0) {
				long p = SDLGamepad.SDL_OpenGamepad(ids.get(ids.position()));
				if (p != 0) {
					pad = p;
					LOG.info("mcrl gamepad: opened '{}'", SDLGamepad.SDL_GetGamepadName(p));
				}
			}
		} finally {
			SDLStdinc.SDL_free(ids);
		}
	}

	private static void close() {
		if (pad != 0) {
			SDLGamepad.SDL_CloseGamepad(pad);
			pad = 0;
		}
	}

	public static PadState poll() {
		if (!enabled || pad == 0) {
			return PadState.NONE;
		}
		if (!SDLGamepad.SDL_GamepadConnected(pad)) {
			close();
			return PadState.NONE;
		}
		return new PadState(stick(SDLGamepad.SDL_GAMEPAD_AXIS_LEFTX), stick(SDLGamepad.SDL_GAMEPAD_AXIS_LEFTY),
				stick(SDLGamepad.SDL_GAMEPAD_AXIS_RIGHTX), stick(SDLGamepad.SDL_GAMEPAD_AXIS_RIGHTY),
				trigger(SDLGamepad.SDL_GAMEPAD_AXIS_LEFT_TRIGGER), trigger(SDLGamepad.SDL_GAMEPAD_AXIS_RIGHT_TRIGGER),
				button(SDLGamepad.SDL_GAMEPAD_BUTTON_SOUTH), button(SDLGamepad.SDL_GAMEPAD_BUTTON_EAST),
				button(SDLGamepad.SDL_GAMEPAD_BUTTON_WEST), button(SDLGamepad.SDL_GAMEPAD_BUTTON_NORTH),
				button(SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_UP), button(SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_DOWN));
	}

	public static void rumble(int low, int high, int millis) {
		if (enabled && pad != 0) {
			SDLGamepad.SDL_RumbleGamepad(pad, (short) low, (short) high, millis);
		}
	}

	private static float stick(int axis) {
		return Math.max(-1f, Math.min(1f, SDLGamepad.SDL_GetGamepadAxis(pad, axis) / AXIS_MAX));
	}

	private static float trigger(int axis) {
		return Math.max(0f, Math.min(1f, SDLGamepad.SDL_GetGamepadAxis(pad, axis) / AXIS_MAX));
	}

	private static boolean button(int b) {
		return SDLGamepad.SDL_GetGamepadButton(pad, b);
	}
}
