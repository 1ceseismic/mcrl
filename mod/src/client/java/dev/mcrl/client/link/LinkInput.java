package dev.mcrl.client.link;

import dev.mcrl.client.input.GamepadSource;
import dev.mcrl.client.input.KeyboardSource;
import dev.mcrl.client.input.PadState;
import dev.mcrl.client.input.RawInput;

public record LinkInput(KeyboardSource.State k, PadState pad, boolean exit, boolean reset) {
	private static boolean padExitWas, padResetWas;

	static LinkInput sample() {
		KeyboardSource.State k = KeyboardSource.poll();
		PadState pad = GamepadSource.poll();
		boolean exit = k.exitPressed() || pad.dpadUp() && !padExitWas;
		boolean reset = k.resetPressed() || pad.dpadDown() && !padResetWas;
		padExitWas = pad.dpadUp();
		padResetWas = pad.dpadDown();
		return new LinkInput(k, pad, exit, reset);
	}

	RawInput raw(boolean screenOpen, boolean driving) {
		return new RawInput(k.throttle() ? 1f : 0f, k.reverse() ? 1f : 0f, k.steerLeft() ? 1f : 0f,
				k.steerRight() ? 1f : 0f, pad.lsX(), pad.lsY(), pad.rsX(), pad.rsY(), pad.lt(), pad.rt(),
				k.jump() || pad.south(), k.boost() || pad.east(), k.powerslide() || pad.west(), k.rollLeft(),
				k.rollRight(), screenOpen, driving);
	}
}
