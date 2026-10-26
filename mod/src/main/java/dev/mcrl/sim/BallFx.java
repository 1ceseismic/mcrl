package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;

import dev.mcrl.natives.Layouts;

public record BallFx(boolean worldContact, int lastTouchTeam, Hit bounce, Wet wet) {
	public static final BallFx EMPTY = new BallFx(false, -1, Hit.NONE, Wet.DRY);

	public static BallFx readFrom(MemorySegment s) {
		return new BallFx(s.get(JAVA_INT, Layouts.BALL_FX_WORLD_CONTACT) != 0,
				s.get(JAVA_INT, Layouts.BALL_FX_LAST_TOUCH_TEAM), Hit.readFrom(s, Layouts.BALL_FX_BOUNCE),
				Wet.readFrom(s, Layouts.BALL_FX_WET));
	}
}
