package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;

import org.jspecify.annotations.Nullable;

import dev.mcrl.natives.Layouts;

public record SoccarState(int goals, int goalTeam, int goalToucher, double gx, double gy, double gz, float gvx,
		float gvy, float gvz, long goalTick, boolean ballOutOfPlay, int kickoffs, long kickoffTick, int lockTicks,
		boolean[] padActive, float[] padCooldown, int[] padPickups, int[] padCar) {
	public SoccarState {
		padActive = padActive.clone();
		padCooldown = padCooldown.clone();
		padPickups = padPickups.clone();
		padCar = padCar.clone();
	}

	@Override
	public boolean[] padActive() {
		return padActive.clone();
	}

	@Override
	public float[] padCooldown() {
		return padCooldown.clone();
	}

	@Override
	public int[] padPickups() {
		return padPickups.clone();
	}

	@Override
	public int[] padCar() {
		return padCar.clone();
	}

	public static SoccarState readFrom(MemorySegment s, long tick, @Nullable SoccarState prev) {
		int goals = s.get(JAVA_INT, Layouts.SOCCAR_GOALS);
		int kickoffs = s.get(JAVA_INT, Layouts.SOCCAR_KICKOFFS);
		long goalTick = prev != null && prev.goals == goals ? prev.goalTick : goals == 0 ? -1 : tick;
		long kickoffTick = prev != null && prev.kickoffs == kickoffs ? prev.kickoffTick : kickoffs == 0 ? -1 : tick;
		int n = Layouts.SOCCAR_PADS;
		boolean[] active = new boolean[n];
		float[] cooldown = new float[n];
		int[] pickups = new int[n], car = new int[n];
		for (int i = 0; i < n; i++) {
			active[i] = s.get(JAVA_BYTE, Layouts.SOCCAR_PAD_ACTIVE + i) != 0;
			cooldown[i] = s.get(JAVA_FLOAT, Layouts.SOCCAR_PAD_COOLDOWN + 4L * i);
			pickups[i] = s.get(JAVA_INT, Layouts.SOCCAR_PAD_PICKUPS + 4L * i);
			car[i] = s.get(JAVA_INT, Layouts.SOCCAR_PAD_CAR + 4L * i);
		}
		long v = Layouts.SOCCAR_GOAL_VEL;
		return new SoccarState(goals, s.get(JAVA_INT, Layouts.SOCCAR_GOAL_TEAM), s.get(JAVA_INT, Layouts.SOCCAR_GOAL_TOUCHER),
				s.get(JAVA_DOUBLE, 0), s.get(JAVA_DOUBLE, 8), s.get(JAVA_DOUBLE, 16), s.get(JAVA_FLOAT, v),
				s.get(JAVA_FLOAT, v + 4), s.get(JAVA_FLOAT, v + 8), goalTick, s.get(JAVA_INT, Layouts.SOCCAR_BALL_OUT) != 0,
				kickoffs, kickoffTick, s.get(JAVA_INT, Layouts.SOCCAR_LOCK_TICKS), active, cooldown, pickups, car);
	}
}
