package dev.mcrl.natives;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarState;

class NativeWorldTest {
	private static final Pose UPRIGHT = new Pose(8.5, 70, 8.5, 1, 0, 0, 0);

	private static <T> T on(ExecutorService ex, Callable<T> c) throws Exception {
		try {
			return ex.submit(c).get();
		} catch (ExecutionException e) {
			if (e.getCause() instanceof Exception x) {
				throw x;
			}
			throw e;
		}
	}

	private static long offset(MemoryLayout layout, String field) {
		return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
	}

	@Test
	void abiLayouts() {
		Layouts.verify();
		assertEquals(7, McrlNative.abiVersion());
		assertEquals(7, McrlNative.EXPECTED_ABI_VERSION);
		assertEquals(400, Layouts.CAR_FX.byteSize());
		assertEquals(400, McrlNative.sizeOf(6));
		assertEquals(80, Layouts.CAR_CONFIG.byteSize());
		assertEquals(80, McrlNative.sizeOf(8));
		assertEquals(Layouts.CAR_FX_BUMPS, offset(Layouts.CAR_FX, "bumps"));
		assertEquals(228, Layouts.CAR_FX_BUMPS);
		assertEquals(Layouts.CAR_FX_BUMPED_BY, offset(Layouts.CAR_FX, "bumpedBy"));
		assertEquals(352, Layouts.CAR_FX_BUMPED_BY);
		assertEquals(Layouts.CAR_FX_BUMP_SPEED, offset(Layouts.CAR_FX, "bumpSpeed"));
		assertEquals(356, Layouts.CAR_FX_BUMP_SPEED);
		assertEquals(Layouts.CAR_CONFIG_HITBOX_SIZE, offset(Layouts.CAR_CONFIG, "hitboxSize"));
		assertEquals(Layouts.CAR_CONFIG_HITBOX_OFFSET, offset(Layouts.CAR_CONFIG, "hitboxOffset"));
		assertEquals(Layouts.CAR_CONFIG_WHEEL_RADIUS, offset(Layouts.CAR_CONFIG, "wheelRadius"));
		assertEquals(Layouts.CAR_CONFIG_SUSPENSION_REST, offset(Layouts.CAR_CONFIG, "suspensionRest"));
		assertEquals(Layouts.CAR_CONFIG_CONNECTION, offset(Layouts.CAR_CONFIG, "connection"));
		assertEquals(Layouts.CAR_CONFIG_THREE_WHEELS, offset(Layouts.CAR_CONFIG, "threeWheels"));
		assertEquals(Layouts.CAR_CONFIG_PRESET, offset(Layouts.CAR_CONFIG, "preset"));
		assertEquals(Layouts.CAR_CONFIG_TEAM, offset(Layouts.CAR_CONFIG, "team"));
		assertEquals(64, Layouts.CAR_CONFIG_THREE_WHEELS);
	}

	@Test
	void blobRoundTrip() throws Exception {
		assertEquals(Layouts.CAR_BLOB_SIZE, McrlNative.sizeOf(16));
		assertEquals(Layouts.BALL_BLOB_SIZE, McrlNative.sizeOf(17));
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			on(ex, () -> {
				try (Arena a = Arena.ofConfined()) {
					MemorySegment w = McrlNative.worldCreate(MemorySegment.NULL, 0, 0, 0);
					try {
						MemorySegment st = a.allocate(Layouts.CAR_STATE);
						CarState.of(UPRIGHT, 3, 0, 1, 0.5, 0, 0).writeTo(st);
						McrlNative.carAdd(w, 1, 0, 0, st);
						MemorySegment ball = a.allocate(Layouts.BALL_STATE);
						new dev.mcrl.sim.BallState(new Pose(20.5, 70, 8.5, 1, 0, 0, 0), 2, 1, 0, 0, 0, 3).writeTo(ball);
						McrlNative.ballSetState(w, ball);
						assertEquals(30, McrlNative.step(w, 30, MemorySegment.NULL));
						McrlNative.carGetState(w, 1, st);
						McrlNative.ballGetState(w, ball);
						byte[] carWas = st.toArray(JAVA_BYTE), ballWas = ball.toArray(JAVA_BYTE);
						MemorySegment carBlob = a.allocate(Layouts.CAR_BLOB_SIZE);
						MemorySegment ballBlob = a.allocate(Layouts.BALL_BLOB_SIZE);
						McrlNative.carSave(w, 1, carBlob);
						McrlNative.ballSave(w, ballBlob);
						assertEquals(Layouts.BLOB_VERSION, carBlob.get(JAVA_BYTE, 0));
						assertEquals(Layouts.BLOB_VERSION, ballBlob.get(JAVA_BYTE, 0));

						assertEquals(10, McrlNative.step(w, 10, MemorySegment.NULL));
						CarState.of(new Pose(-40, 90, 3, 1, 0, 0, 0), 0, 0, 0, 0, 0, 0).writeTo(st);
						McrlNative.carSetState(w, 1, st);
						McrlNative.worldSetTick(w, 30);
						McrlNative.carLoad(w, 1, carBlob);
						McrlNative.ballLoad(w, ballBlob);
						McrlNative.carGetState(w, 1, st);
						McrlNative.ballGetState(w, ball);
						assertArrayEquals(carWas, st.toArray(JAVA_BYTE), "the car's state, bit for bit");
						assertArrayEquals(ballWas, ball.toArray(JAVA_BYTE), "the ball's state, bit for bit");

						assertTrue(McrlNative.worldRewindEvents(w, 30));
						assertFalse(McrlNative.worldRewindEvents(w, 31), "after the world's tick");
						McrlNative.worldSetTick(w, 1000);
						assertFalse(McrlNative.worldRewindEvents(w, 1000), "never recorded");
						carBlob.set(JAVA_BYTE, 0, (byte) 2);
						McrlNativeException e = assertThrows(McrlNativeException.class,
								() -> McrlNative.carLoad(w, 1, carBlob));
						assertEquals(McrlNative.ERR_ARG, e.code());

						MemorySegment shortCar = carBlob.asSlice(0, Layouts.CAR_BLOB_SIZE - 1);
						MemorySegment shortBall = ballBlob.asSlice(0, Layouts.BALL_BLOB_SIZE - 1);
						assertThrows(IllegalArgumentException.class, () -> McrlNative.carLoad(w, 1, shortCar));
						assertThrows(IllegalArgumentException.class, () -> McrlNative.carSave(w, 1, shortCar));
						assertThrows(IllegalArgumentException.class, () -> McrlNative.ballLoad(w, shortBall));
						assertThrows(IllegalArgumentException.class, () -> McrlNative.ballSave(w, shortBall));
						assertThrows(IllegalArgumentException.class,
								() -> McrlNative.carLoad(w, 1, MemorySegment.NULL));
						return null;
					} finally {
						McrlNative.worldDestroy(w);
					}
				}
			});
		} finally {
			ex.shutdownNow();
		}
	}

	@Test
	void stepOffOwnerThreadThrows() throws Exception {
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			MemorySegment w = on(ex, () -> McrlNative.worldCreate(MemorySegment.NULL, 0, 0, 0));
			try {
				McrlNativeException e = assertThrows(McrlNativeException.class,
						() -> McrlNative.step(w, 1, MemorySegment.NULL));
				assertEquals(-2, e.code());
			} finally {
				on(ex, () -> { McrlNative.worldDestroy(w); return null; });
			}
		} finally {
			ex.shutdownNow();
		}
	}
}
