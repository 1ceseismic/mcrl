package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import dev.mcrl.match.MatchBlock;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarFx;
import dev.mcrl.sim.Controls;
import dev.mcrl.sim.Hit;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SoccarState;

class SnapshotCodecTest {
	private static final int[] O = {256, 64, -1008};

	private static float[] wheels(float base) {
		float[] w = new float[12];
		for (int i = 0; i < 12; i++) {
			w[i] = base + (i - 6) * 0.3137f;
		}
		return w;
	}

	private static SimFrame.CarSnap car(int id, double x, double y, double z, int team, int preset, Controls applied) {
		Pose cur = new Pose(x, y, z, 0.9238795f, 0.1f, -0.3826834f, 0.05f);
		Pose prev = new Pose(x - 0.2, y, z, 1, 0, 0, 0);
		return new SimFrame.CarSnap(id, prev, cur, 23.456789, -1.25, 7.1, 0.5, -2.75, 3.3333, 66.6f,
				0x1C5, wheels(0.1f), 0.37f, 0b1011, 9, 1234.5f, 0.25f, CarFx.EMPTY, team, preset, applied);
	}

	private static SimFrame.BallSnap ball() {
		Pose cur = new Pose(270.125, 66.5, -1003.0625, 0.5f, 0.5f, 0.5f, 0.5f);
		return new SimFrame.BallSnap(cur, cur, -40.5, 12.25, 3.0, 1.5, 0, -6.25, 17, 812.5f, 1, true, Hit.NONE);
	}

	private static Snapshot decode(byte[] data) {
		ByteBuf b = Unpooled.wrappedBuffer(data);
		Snapshot s = SnapshotCodec.decode(b);
		assertEquals(0, b.readableBytes(), "the reader ends at the payload end");
		return s;
	}

	@Test
	void roundTrip() {
		Controls c1 = new Controls(1f, -0.5f, 0.3f, -1f, 0.77f, true, false, true);
		Controls c2 = new Controls(-0.25f, 0f, 0f, 0f, 0f, false, true, false);
		SimFrame.CarSnap a = car(42, 256.5, 70.25, -1000.75, 0, 0, c1);
		SimFrame.CarSnap b = car(300_000, 256.5, 70.25, -1000.75, 1, 3, c2);
		SimFrame.BallSnap ball = ball();
		SnapshotCodec.Header h = new SnapshotCodec.Header(123_456_789L, O, SimFrame.FROZEN | SimFrame.STEPUP, 300, 2.5f)
				.withInput(201, 123_456_700L, -3);
		byte[] data = SnapshotCodec.assemble(h,
				List.of(SnapshotCodec.carRecord(a, O, null), SnapshotCodec.carRecord(b, O, null)),
				SnapshotCodec.ballRecord(ball, O, null), null, ArenaBlock.TERRAIN);
		Snapshot s = decode(data);
		assertEquals(123_456_789L, s.tick());
		assertArrayEquals(O, s.origin());
		assertEquals(SimFrame.FROZEN | SimFrame.STEPUP, s.frameFlags());
		assertEquals(300, s.hostId());
		assertEquals(2.5f, s.rate());
		assertEquals(201, s.inputEpoch());
		assertEquals(123_456_700L, s.ackTick());
		assertEquals(-3, s.slack());
		assertEquals(ArenaBlock.TERRAIN, s.arena());
		assertNull(s.arenaBlob());
		assertEquals(2, s.cars().size());
		for (SimFrame.CarSnap want : List.of(a, b)) {
			Snapshot.Car got = s.car(want.carId());
			assertNotNull(got, "car " + want.carId());
			assertNull(got.blob());
			assertEquals(want.team(), got.team());
			assertEquals(want.preset(), got.preset());
			Controls w = want.applied();
			assertEquals(new Controls(q(w.throttle()), q(w.steer()), q(w.pitch()), q(w.yaw()), q(w.roll()), w.jump(),
					w.boost(), w.handbrake()), got.applied());
			Pose p = got.head().pose(s.origin());
			assertEquals(want.cur().x(), p.x(), 1e-5);
			assertEquals(want.cur().y(), p.y(), 1e-5);
			assertEquals(want.cur().z(), p.z(), 1e-5);
			assertEquals(want.cur().qw(), p.qw());
			assertEquals(want.cur().qx(), p.qx());
			assertEquals(want.cur().qy(), p.qy());
			assertEquals(want.cur().qz(), p.qz());
			Snapshot.CarHead hd = got.head();
			assertEquals((float) want.vx(), hd.vx());
			assertEquals((float) want.vy(), hd.vy());
			assertEquals((float) want.vz(), hd.vz());
			assertEquals((float) want.wx(), hd.wx());
			assertEquals((float) want.wy(), hd.wy());
			assertEquals((float) want.wz(), hd.wz());
			assertEquals(67, hd.boost(), "66.6 rounds to 67");
			assertEquals(want.carFlags(), hd.flags());
			assertEquals(want.wheelContact(), hd.wheelContact());
			assertEquals(want.steerAngle(), hd.steer(), 1f / 1024);
			float[] ww = want.wheelLocal(), gw = hd.wheelLocal();
			for (int i = 0; i < 12; i++) {
				assertEquals(ww[i], gw[i], 1f / 1024, "wheel float " + i);
			}
			SimFrame.CarSnap back = got.toCarSnap(want.prev(), p);
			assertEquals(want.carId(), back.carId());
			assertEquals(CarFx.EMPTY, back.fx());
			assertEquals(want.applied().jump(), back.applied().jump());
			assertEquals(hd.vx(), back.vx());
		}
		Snapshot.Ball gb = s.ball();
		assertNotNull(gb);
		assertNull(gb.blob());
		Snapshot.BallHead bh = gb.head();
		Pose bp = bh.pose(s.origin());
		assertEquals(ball.cur().x(), bp.x(), 1e-5);
		assertEquals(ball.cur().y(), bp.y(), 1e-5);
		assertEquals(ball.cur().z(), bp.z(), 1e-5);
		assertEquals(ball.cur().qw(), bp.qw());
		assertEquals((float) ball.vx(), bh.vx());
		assertEquals((float) ball.wz(), bh.wz());
		assertEquals(17, bh.bounces());
		assertEquals(812.5f, bh.bounceSpeed());
		assertEquals(1, bh.lastTouchTeam());
		assertEquals(true, bh.worldContact());
	}

	private static float q(float v) {
		return InputFrame.axis(InputFrame.quantise(v));
	}

	@Test
	void blobsRoundTrip() {
		Random r = new Random(7);
		byte[] carBlob1 = new byte[300], carBlob2 = new byte[300], ballBlob = new byte[100], arenaBlob = new byte[64];
		r.nextBytes(carBlob1);
		r.nextBytes(carBlob2);
		r.nextBytes(ballBlob);
		r.nextBytes(arenaBlob);
		SnapshotCodec.Header h = new SnapshotCodec.Header(99, O, 0, 3, 1f).withInput(0, 98, 2).withBlobs(300, 100, 64);
		byte[] data = SnapshotCodec.assemble(h,
				List.of(SnapshotCodec.carRecord(car(1, 260, 65, -1000, 0, 0, Controls.NEUTRAL), O, carBlob1),
						SnapshotCodec.carRecord(car(2, 250, 65, -1000, 1, 0, Controls.NEUTRAL), O, carBlob2)),
				SnapshotCodec.ballRecord(ball(), O, ballBlob), arenaBlob, ArenaBlock.TERRAIN);
		Snapshot s = decode(data);
		assertArrayEquals(carBlob1, s.car(1).blob());
		assertArrayEquals(carBlob2, s.car(2).blob());
		assertArrayEquals(ballBlob, s.ball().blob());
		assertArrayEquals(arenaBlob, s.arenaBlob());
		assertEquals(260, s.car(1).head().pose(s.origin()).x(), 1e-5, "the head still reads after a blob");
		assertEquals(ArenaBlock.TERRAIN, s.arena());

		assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.assemble(h, List.of(), null, new byte[3],
				ArenaBlock.TERRAIN), "an arena blob of the wrong size");
	}

	@Test
	void truncatedSnapshotThrows() {
		byte[] data = SnapshotCodec.assemble(new SnapshotCodec.Header(5, O, 0, 1, 1f),
				List.of(SnapshotCodec.carRecord(car(1, 260, 65, -1000, 0, 0, Controls.NEUTRAL), O, null)), null, null,
				ArenaBlock.TERRAIN);
		byte[] cut = Arrays.copyOf(data, data.length - 10);
		assertThrows(DecoderException.class,
				() -> SnapshotCodec.decode(Unpooled.wrappedBuffer(cut)));
	}

	@Test
	void soccarBlock() {
		boolean[] active = new boolean[MatchBlock.PADS];
		float[] cooldown = new float[MatchBlock.PADS];
		int[] pickups = new int[MatchBlock.PADS], car = new int[MatchBlock.PADS];
		Arrays.fill(active, true);
		Arrays.fill(car, -1);
		active[0] = false;
		cooldown[0] = 9.5f;
		active[6] = false;
		cooldown[6] = 3.25f;
		SoccarState soccar = new SoccarState(2, 1, 7, 0, 65, 0, 0, 0, 0, 900, true, 3, 600, 40, active, cooldown, pickups,
				car);
		SimFrame f = new SimFrame(1000, 0f, 0, List.of(), null, 0, 0, 1f, soccar);
		ArenaBlock arena = SnapshotSender.arenaBlock(f);
		assertEquals(ArenaBlock.KIND_SOCCAR, arena.kind());
		assertEquals(MatchBlock.BYTES, arena.body().length);
		assertEquals(110, MatchBlock.BYTES);
		byte[] data = SnapshotCodec.assemble(new SnapshotCodec.Header(1000, O, 0, 1, 1f), List.of(), null, null, arena);
		Snapshot back = SnapshotCodec.decode(Unpooled.wrappedBuffer(data));
		assertEquals(arena, back.arena());
		MatchBlock want = MatchBlock.of(soccar, null), got = MatchBlock.decode(back.arena().body());
		assertEquals(want, got);
		assertFalse(got.padActive()[6]);
		assertEquals(3.25f, got.padCooldown()[6]);
		assertTrue(got.header().ballOutOfPlay());
		assertEquals(ArenaBlock.TERRAIN, SnapshotSender.arenaBlock(new SimFrame(1, 0f, 0, List.of(), null, 0)));
	}

	@Test
	void negativeCarIdThrows() {
		byte[] data = SnapshotCodec.assemble(new SnapshotCodec.Header(5, O, 0, 1, 1f),
				List.of(SnapshotCodec.carRecord(car(-1, 260, 65, -1000, 0, 0, Controls.NEUTRAL), O, null)), null, null,
				ArenaBlock.TERRAIN);
		assertThrows(DecoderException.class, () -> SnapshotCodec.decode(Unpooled.wrappedBuffer(data)));
	}
}
