package dev.mcrl.match;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SoccarState;

public final class Match {
	public enum Phase { LOBBY, KICKOFF, PLAYING, GOAL, ENDED }

	public sealed interface Action permits Kickoff, ResetBall, Goal, End, Return {
	}

	public record Kickoff(int seed) implements Action {
	}

	public record ResetBall() implements Action {
	}

	public record Goal(int team, int toucher, double x, double y, double z, double speedUu) implements Action {
	}

	public record End(int winner) implements Action {
	}

	public record Return() implements Action {
	}

	private final MatchSize size;
	private final RandomGenerator rng;
	private Phase phase = Phase.LOBBY;
	private int blue, orange;
	private boolean overtime;
	private int goalsSeen = -1;
	private long kickoffAsked = -1;
	private long playFrom = -1;
	private long played, otPlayed;
	private long goalAt = -1;
	private long endAt = -1;
	private int endWinner = -1;
	private boolean returned;

	public Match(MatchSize size, RandomGenerator rng) {
		this.size = size;
		this.rng = rng;
	}

	public MatchSize size() {
		return size;
	}

	public Phase phase() {
		return phase;
	}

	public int blue() {
		return blue;
	}

	public int orange() {
		return orange;
	}

	public boolean overtime() {
		return overtime;
	}

	public int winner() {
		return phase == Phase.ENDED ? endWinner : -1;
	}

	public List<Action> hostRestarted(long lastTick, long tick) {
		goalsSeen = -1;
		return switch (phase) {
			case KICKOFF, PLAYING -> {
				freeze(lastTick >= 0 ? lastTick : tick);
				goalAt = -1;
				yield List.of(kickoff(tick));
			}
			case GOAL -> {
				goalAt = tick;
				yield List.of();
			}
			case ENDED -> {
				endAt = tick;
				yield List.of();
			}
			case LOBBY -> {
				goalAt = -1;
				yield List.of();
			}
		};
	}

	public List<Action> start(long tick) {
		if (phase != Phase.LOBBY) {
			return List.of();
		}
		goalAt = -1;
		return List.of(kickoff(tick));
	}

	public List<Action> forfeit(long tick) {
		if (phase == Phase.ENDED) {
			return List.of();
		}
		freeze(tick);
		return List.of(end(tick, -1));
	}

	public List<Action> observe(long tick, SoccarState s, boolean floorTouch) {
		boolean newGoal = goalsSeen >= 0 && s.goals() != goalsSeen;
		goalsSeen = s.goals();
		List<Action> out = new ArrayList<>(1);
		switch (phase) {
			case LOBBY -> {
				if (newGoal) {
					goalAt = s.goalTick() >= 0 ? s.goalTick() : tick;
				}
				if (goalAt >= 0 && tick >= goalAt + MatchRules.GOAL_TICKS) {
					goalAt = -1;
					out.add(new ResetBall());
				}
			}
			case KICKOFF -> {
				if (kickoffAsked >= 0 && s.kickoffs() > 0 && s.kickoffTick() >= kickoffAsked) {
					playFrom = s.kickoffTick() + MatchRules.COUNTDOWN_TICKS;
					kickoffAsked = -1;
				}
				if (playFrom >= 0 && tick >= playFrom) {
					phase = Phase.PLAYING;
				}
			}
			case PLAYING -> {
				if (newGoal) {
					out.add(goal(s));
				} else if (floorTouch && !overtime && regulationPlayed(tick) >= MatchRules.GAME_TICKS) {
					freeze(tick);
					out.add(blue == orange ? overtimeKickoff(tick) : end(tick, leader()));
				}
			}
			case GOAL -> {
				if (tick >= goalAt + MatchRules.GOAL_TICKS) {
					if (overtime || (played >= MatchRules.GAME_TICKS && blue != orange)) {
						out.add(end(tick, leader()));
					} else if (played >= MatchRules.GAME_TICKS) {
						out.add(overtimeKickoff(tick));
					} else {
						out.add(kickoff(tick));
					}
				}
			}
			case ENDED -> {
				if (!returned && tick >= endAt + MatchRules.PODIUM_TICKS) {
					returned = true;
					out.add(new Return());
				}
			}
		}
		return out;
	}

	public int clockSeconds(long tick) {
		if (overtime) {
			return (int) (overtimePlayed(tick) / SimClock.TICK_RATE);
		}
		long left = Math.max(0, MatchRules.GAME_TICKS - regulationPlayed(tick));
		return (int) Math.ceilDiv(left, SimClock.TICK_RATE);
	}

	public int countdown(long tick) {
		if (phase != Phase.KICKOFF) {
			return 0;
		}
		long left = playFrom < 0 ? MatchRules.COUNTDOWN_TICKS : Math.max(0, playFrom - tick);
		return (int) Math.ceilDiv(left, SimClock.TICK_RATE);
	}

	private long running(long tick) {
		return phase == Phase.PLAYING && playFrom >= 0 ? Math.max(0, tick - playFrom) : 0;
	}

	private long regulationPlayed(long tick) {
		return overtime ? played : played + running(tick);
	}

	private long overtimePlayed(long tick) {
		return overtime ? otPlayed + running(tick) : 0;
	}

	private void freeze(long tick) {
		long r = running(tick);
		if (overtime) {
			otPlayed += r;
		} else {
			played += r;
		}
		playFrom = -1;
	}

	private Kickoff kickoff(long tick) {
		phase = Phase.KICKOFF;
		kickoffAsked = tick;
		playFrom = -1;
		return new Kickoff(rng.nextInt(Integer.MAX_VALUE));
	}

	private Kickoff overtimeKickoff(long tick) {
		overtime = true;
		return kickoff(tick);
	}

	private Goal goal(SoccarState s) {
		long at = s.goalTick();
		freeze(at);
		if (s.goalTeam() == 0) {
			blue++;
		} else if (s.goalTeam() == 1) {
			orange++;
		}
		phase = Phase.GOAL;
		goalAt = at;
		double speed = Math.sqrt((double) s.gvx() * s.gvx() + (double) s.gvy() * s.gvy() + (double) s.gvz() * s.gvz());
		return new Goal(s.goalTeam(), s.goalToucher(), s.gx(), s.gy(), s.gz(), speed * 50);
	}

	private int leader() {
		return blue > orange ? 0 : orange > blue ? 1 : -1;
	}

	private End end(long tick, int winner) {
		phase = Phase.ENDED;
		endAt = tick;
		endWinner = winner;
		return new End(winner);
	}
}
