package dev.mcrl.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.mcrl.match.Match.Action;
import dev.mcrl.match.Match.Phase;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.Hit;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SoccarState;

class MatchTest {
	private static SoccarState state(int goals, int goalTeam, long goalTick, int kickoffs, long kickoffTick,
			int lockTicks) {
		return new SoccarState(goals, goalTeam, 7, 1, 65, goals == 0 ? 0 : 105, 0, 0, 40, goalTick, false, kickoffs,
				kickoffTick, lockTicks, new boolean[34], new float[34], new int[34], new int[34]);
	}

	private static final class World {
		int goals, goalTeam = -1, kickoffs;
		long goalTick = -1, kickoffTick = -1;

		void apply(List<Action> actions, long tick) {
			for (Action a : actions) {
				if (a instanceof Match.Kickoff) {
					kickoffs++;
					kickoffTick = tick;
				}
			}
		}

		void goal(int team, long tick) {
			goals++;
			goalTeam = team;
			goalTick = tick;
		}

		SoccarState at(long tick) {
			int lock = (int) Math.max(0, kickoffTick < 0 ? 0 : kickoffTick + MatchRules.COUNTDOWN_TICKS - tick);
			return state(goals, goalTeam, goalTick, kickoffs, kickoffTick, lock);
		}
	}

	private static List<Action> run(Match m, World w, long from, long to, boolean floor) {
		List<Action> all = new java.util.ArrayList<>();
		for (long t = from; t <= to; t++) {
			List<Action> a = m.observe(t, w.at(t), floor);
			w.apply(a, t);
			all.addAll(a);
		}
		return all;
	}

	private static Match started(World w) {
		Match m = new Match(MatchSize.DUEL, new Random(1));
		List<Action> a = m.start(1000);
		w.apply(a, 1000);
		return m;
	}

	@Test
	void kickoffThenPlaying() {
		World w = new World();
		Match m = new Match(MatchSize.DUEL, new Random(1));
		assertEquals(Phase.LOBBY, m.phase());
		List<Action> a = m.start(1000);
		assertEquals(1, a.size());
		assertInstanceOf(Match.Kickoff.class, a.get(0));
		w.apply(a, 1000);
		assertEquals(Phase.KICKOFF, m.phase());
		assertEquals(List.of(), run(m, w, 1000, 1359, false));
		assertEquals(Phase.KICKOFF, m.phase());
		assertEquals(1, m.countdown(1359));
		assertEquals(3, m.countdown(1000));
		assertEquals(300, m.clockSeconds(1359));
		run(m, w, 1360, 1360, false);
		assertEquals(Phase.PLAYING, m.phase());
		assertEquals(0, m.countdown(1360));
		assertEquals(300, m.clockSeconds(1360));
		assertEquals(300, m.clockSeconds(1361));
		assertEquals(299, m.clockSeconds(1480));
		assertFalse(m.overtime());
	}

	@Test
	void goalStopsClockAndResets() {
		World w = new World();
		Match m = started(w);
		run(m, w, 1000, 7359, false);
		w.goal(0, 7360);
		List<Action> a = m.observe(7360, w.at(7360), false);
		assertEquals(Phase.GOAL, m.phase());
		assertEquals(1, m.blue());
		assertEquals(0, m.orange());
		assertEquals(1, a.size());
		Match.Goal g = assertInstanceOf(Match.Goal.class, a.get(0));
		assertEquals(0, g.team());
		assertEquals(7, g.toucher());
		assertEquals(2000, g.speedUu(), 1e-6);
		assertEquals(250, m.clockSeconds(8000));
		assertEquals(List.of(), run(m, w, 7361, 7719, false));
		List<Action> k = run(m, w, 7720, 7720, false);
		assertEquals(1, k.size());
		assertInstanceOf(Match.Kickoff.class, k.get(0));
		assertEquals(Phase.KICKOFF, m.phase());
		run(m, w, 7721, 8080, false);
		assertEquals(Phase.PLAYING, m.phase());
		assertEquals(250, m.clockSeconds(8080));
	}

	@Test
	void timeUpWaitsForFloor() {
		World w = new World();
		Match m = started(w);
		run(m, w, 1000, 1999, false);
		w.goal(0, 2000);
		run(m, w, 2000, 2360, false);
		assertEquals(Phase.KICKOFF, m.phase());
		long zero = 2720 + MatchRules.GAME_TICKS - 640;
		run(m, w, 2361, zero + 50, false);
		assertEquals(Phase.PLAYING, m.phase());
		assertEquals(0, m.clockSeconds(zero));
		assertEquals(1, m.clockSeconds(zero - 1));
		List<Action> end = m.observe(zero + 51, w.at(zero + 51), true);
		assertEquals(1, end.size());
		assertEquals(0, assertInstanceOf(Match.End.class, end.get(0)).winner());
		assertEquals(Phase.ENDED, m.phase());
		assertEquals(List.of(), run(m, w, zero + 52, zero + 51 + MatchRules.PODIUM_TICKS - 1, false));
		List<Action> back = run(m, w, zero + 51 + MatchRules.PODIUM_TICKS, zero + 51 + MatchRules.PODIUM_TICKS + 10,
				false);
		assertEquals(1, back.size());
		assertInstanceOf(Match.Return.class, back.get(0));
	}

	@Test
	void tieGoesToOvertime() {
		World w = new World();
		Match m = started(w);
		long zero = 1360 + MatchRules.GAME_TICKS;
		run(m, w, 1000, zero, false);
		List<Action> ot = m.observe(zero + 1, w.at(zero + 1), true);
		assertEquals(1, ot.size());
		assertInstanceOf(Match.Kickoff.class, ot.get(0));
		w.apply(ot, zero + 1);
		assertTrue(m.overtime());
		assertEquals(Phase.KICKOFF, m.phase());
		long play = zero + 1 + MatchRules.COUNTDOWN_TICKS;
		run(m, w, zero + 2, play, false);
		assertEquals(Phase.PLAYING, m.phase());
		assertEquals(0, m.clockSeconds(play));
		assertEquals(1, m.clockSeconds(play + 120));
		run(m, w, play + 1, play + 500, true);
		assertEquals(Phase.PLAYING, m.phase());
		w.goal(1, play + 501);
		List<Action> g = m.observe(play + 501, w.at(play + 501), false);
		assertInstanceOf(Match.Goal.class, g.get(0));
		assertEquals(Phase.GOAL, m.phase());
		assertEquals(1, m.orange());
		List<Action> end = run(m, w, play + 502, play + 501 + MatchRules.GOAL_TICKS, false);
		assertEquals(1, end.size());
		assertEquals(1, assertInstanceOf(Match.End.class, end.get(0)).winner());
		assertEquals(Phase.ENDED, m.phase());
	}

	@Test
	void floorTouchRule() {
		int oy = 64;
		assertTrue(MatchRules.floorTouch(0, ball(65.9, true, 0, 0f), oy));
		assertFalse(MatchRules.floorTouch(0, ball(70, true, 0, 0f), oy));
		assertTrue(MatchRules.floorTouch(3, ball(70, false, 4, 0.9f), oy));
		assertFalse(MatchRules.floorTouch(3, ball(70, false, 4, 0.2f), oy));
		assertFalse(MatchRules.floorTouch(4, ball(70, false, 4, 0.9f), oy), "no new bounce");
	}

	private static SimFrame.BallSnap ball(double y, boolean contact, int bounces, float ny) {
		Pose p = new Pose(0, y, 0, 1, 0, 0, 0);
		return new SimFrame.BallSnap(p, p, 0, 0, 0, 0, 0, 0, bounces, 0f, -1, contact, new Hit(0, y, 0, 0f, ny, 0f));
	}

	@Test
	void hostRestartKeepsTimePlayed() {
		World w = new World();
		Match m = started(w);
		run(m, w, 1000, 1360 + 1200, false);
		assertEquals(290, m.clockSeconds(2560));
		World nw = new World();
		List<Action> a = m.hostRestarted(2560, 6);
		assertEquals(1, a.size());
		assertInstanceOf(Match.Kickoff.class, a.get(0));
		nw.apply(a, 6);
		assertEquals(Phase.KICKOFF, m.phase());
		assertEquals(290, m.clockSeconds(6), "the time played is kept");
		run(m, nw, 6, 6 + MatchRules.COUNTDOWN_TICKS, false);
		assertEquals(Phase.PLAYING, m.phase());
		assertEquals(290, m.clockSeconds(366));
		assertEquals(289, m.clockSeconds(366 + 120));
	}
}
