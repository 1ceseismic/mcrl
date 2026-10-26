package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

import java.lang.foreign.MemorySegment;

public record Controls(float throttle, float steer, float pitch, float yaw, float roll,
		boolean jump, boolean boost, boolean handbrake) {
	public static final Controls NEUTRAL = new Controls(0, 0, 0, 0, 0, false, false, false);

	public void writeTo(MemorySegment s) {
		s.set(JAVA_FLOAT, 0, throttle);
		s.set(JAVA_FLOAT, 4, steer);
		s.set(JAVA_FLOAT, 8, pitch);
		s.set(JAVA_FLOAT, 12, yaw);
		s.set(JAVA_FLOAT, 16, roll);
		s.set(JAVA_BYTE, 20, (byte) (jump ? 1 : 0));
		s.set(JAVA_BYTE, 21, (byte) (boost ? 1 : 0));
		s.set(JAVA_BYTE, 22, (byte) (handbrake ? 1 : 0));
		s.set(JAVA_BYTE, 23, (byte) 0);
	}

	public static Controls readFrom(MemorySegment s) {
		return new Controls(s.get(JAVA_FLOAT, 0), s.get(JAVA_FLOAT, 4), s.get(JAVA_FLOAT, 8), s.get(JAVA_FLOAT, 12),
				s.get(JAVA_FLOAT, 16), s.get(JAVA_BYTE, 20) != 0, s.get(JAVA_BYTE, 21) != 0,
				s.get(JAVA_BYTE, 22) != 0);
	}
}
