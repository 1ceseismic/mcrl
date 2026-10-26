package dev.mcrl.client.fx;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import dev.mcrl.client.link.ClientPoses;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.link.LocalSimLink;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.sim.SimFrame;

public final class FrameSignals {
	public interface NormalizedRpm {
		NormalizedRpm HOLD = carId -> Double.NaN;

		double of(int carId);
	}

	public static final FrameSignals EMPTY = new FrameSignals(Map.of(), null, 0);

	private static final SignalTracker TRACKER = new SignalTracker();
	private static FrameSignals current = EMPTY;
	private static NormalizedRpm rpmSource = NormalizedRpm.HOLD;
	private static @Nullable ClientLevel trackedLevel;
	private static long trackedEpoch;

	private final Map<Integer, CarSignals> cars;
	private final @Nullable BallSignals ball;
	private final double simSeconds;

	FrameSignals(Map<Integer, CarSignals> cars, @Nullable BallSignals ball, double simSeconds) {
		this.cars = Collections.unmodifiableMap(cars);
		this.ball = ball;
		this.simSeconds = simSeconds;
	}

	public static FrameSignals current() {
		return current;
	}

	public static void setRpmSource(NormalizedRpm rpm) {
		rpmSource = Objects.requireNonNull(rpm);
	}

	public static void register() {
		FrameEvents.register(FrameSignals::frame);
	}

	private static void frame(Minecraft mc, SimFrame f, long frameNanos) {
		try {
			update(mc, f);
		} catch (RuntimeException e) {
			current = EMPTY;
			throw e;
		}
	}

	private static void update(Minecraft mc, SimFrame f) {
		ClientLevel level = mc.level;
		if (level != trackedLevel || ClientPoses.sourceEpoch() != trackedEpoch) {
			TRACKER.reset();
			trackedLevel = level;
			trackedEpoch = ClientPoses.sourceEpoch();
		}
		if (level == null) {
			current = EMPTY;
			return;
		}
		CarEntity driven = LocalSimLink.drivenCar(mc.player);
		int local = driven != null ? driven.carId() : -1;
		float throttle = LocalSimLink.throttle();
		current = TRACKER.update(f, ClientPoses::car, ClientPoses.ball(), level,
				rpmSource, id -> throttleOf(f, id, local, throttle), local, ClientPoses.simSeconds());
	}

	private static float throttleOf(SimFrame f, int carId, int localCar, float localThrottle) {
		if (carId == localCar) {
			return localThrottle;
		}
		SimFrame.CarSnap c = f.car(carId);
		return c == null ? 0f : c.applied().throttle();
	}

	public Map<Integer, CarSignals> cars() {
		return cars;
	}

	public @Nullable CarSignals car(int carId) {
		return cars.get(carId);
	}

	public @Nullable BallSignals ball() {
		return ball;
	}

	public double simSeconds() {
		return simSeconds;
	}
}
