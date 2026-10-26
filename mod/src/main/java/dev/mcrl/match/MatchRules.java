package dev.mcrl.match;

import java.util.Map;

import dev.mcrl.entity.BallEntity;
import dev.mcrl.sim.SimFrame;

public final class MatchRules {
	// RL's game length and countdown, at 120 ticks/s.
	public static final int GAME_TICKS = 36000;
	public static final int COUNTDOWN_TICKS = 360;
	public static final int GOAL_TICKS = 360;
	public static final int PODIUM_TICKS = 600;
	public static final double FLOOR_SLACK = 0.1;
	public static final int GO_SHOW_TICKS = 120;
	public static final int ASSIST_TICKS = 600;

	private MatchRules() {
	}

	public static int assist(Map<Integer, Long> lastHit, int scorer, long goalTick) {
		int best = -1;
		long bestTick = Long.MIN_VALUE;
		for (Map.Entry<Integer, Long> e : lastHit.entrySet()) {
			long t = e.getValue();
			if (e.getKey() != scorer && t <= goalTick && goalTick - t <= ASSIST_TICKS && t > bestTick) {
				best = e.getKey();
				bestTick = t;
			}
		}
		return best;
	}

	public static boolean floorTouch(int prevBounces, SimFrame.BallSnap b, int oy) {
		if (b.worldContact() && b.cur().y() <= oy + BallEntity.RADIUS + FLOOR_SLACK) {
			return true;
		}
		return b.bounces() != prevBounces && b.bounce().ny() >= 0.7;
	}
}
