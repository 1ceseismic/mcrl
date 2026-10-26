package dev.mcrl.client.sound;

import static dev.mcrl.client.sound.EngineAudioProfile.*;

import java.util.function.DoubleSupplier;

import dev.mcrl.client.sound.ak.AkEngine;
import dev.mcrl.sim.SimClock;

public final class EngineRpmModel {
	private enum State { CLUTCHED, ON_GROUND, IN_AIR }

	// RL runs these rules once per physics tick.
	private static final double TICK = 1.0 / SimClock.TICK_RATE;

	private final DoubleSupplier uniform;
	private State state = State.CLUTCHED;
	private double rpm = GEARS[0][0];
	private int gear;
	private double shiftDown, shiftUp, shiftTimer, airTime, prevWheelSpeed;
	private boolean limiter, boosting;

	public EngineRpmModel(DoubleSupplier uniform) {
		this.uniform = uniform;
		pick(0);
	}

	private void pick(int g) {
		gear = Math.max(0, Math.min(GEARS.length - 1, g));
		double[] d = GEARS[gear];
		shiftDown = d[0] + uniform.getAsDouble() * d[1];
		shiftUp = d[2] + uniform.getAsDouble() * d[3];
	}

	public void step(double dt, boolean driving, boolean onGround, float throttle, boolean boosting, double wheelSpeed) {
		dt = Math.min(dt, AkEngine.MAX_DT);
		if (!(dt > 0)) {
			return;
		}
		int n = (int) Math.ceil(dt / TICK - 1e-9);
		double from = prevWheelSpeed;
		for (int i = 1; i <= n; i++) {
			tick(dt / n, driving, onGround, throttle, boosting, from + (wheelSpeed - from) * i / n);
		}
	}

	private void tick(double dt, boolean driving, boolean onGround, float throttle, boolean boosting, double wheelSpeed) {
		this.boosting = boosting;
		double ws = wheelSpeed;
		if (!driving) {
			state = State.CLUTCHED;
		} else if (onGround) {
			if (state != State.ON_GROUND) {
				pick((int) Math.round(ws / MAX_WHEEL_SPEED * 3));
				shiftTimer = 0;
			}
			state = State.ON_GROUND;
		} else {
			if (state != State.IN_AIR) {
				airTime = 0;
			}
			state = State.IN_AIR;
		}
		double dws = ws - prevWheelSpeed;
		prevWheelSpeed = ws;
		if (state != State.ON_GROUND) {
			if (state == State.IN_AIR) {
				airTime += dt;
			}
			double t = driving ? throttle : 0;
			if (limiter || state == State.IN_AIR && airTime > AIR_MAX_THROTTLE_TIME) {
				t = 0;
			}
			if (t > 0) {
				rpm += t * RPM_ACCEL_CLUTCHED * dt;
				if (rpm > RPM_MAX_CLUTCHED + LIMITER_MARGIN) {
					limiter = true;
				}
			} else if (limiter) {
				rpm -= LIMITER_DECEL * dt;
				if (rpm < RPM_MAX_CLUTCHED) {
					limiter = false;
				}
			} else {
				rpm -= DECEL_CLUTCHED * dt;
			}
			rpm = Math.max(rpm, GEARS[0][0]);
		} else {
			double s = ws / MAX_WHEEL_SPEED;
			if (shiftTimer > 0) {
				shiftTimer = Math.max(0, shiftTimer - dt);
				rpm += (1 - shiftTimer / SHIFT_TIME) * (shiftDown - rpm);
			} else if (throttle <= 0 && !boosting) {
				rpm -= (rpm - s * shiftUp) * COAST_RATE * dt;
				if (gear > 0 && rpm < shiftDown) {
					pick(0);
					shiftDown = 0;
				}
			} else {
				rpm += dt * (lerp(ACCEL_LOW, ACCEL_HIGH, s) + WHEEL_ACCEL * dws / dt);
				if (boosting) {
					rpm += dt * lerp(BOOST_LOW, BOOST_HIGH, s);
				}
				if (rpm > shiftPoint()) {
					pick(Math.min(gear + 1, GEARS.length - 1));
					shiftTimer = SHIFT_TIME;
				}
			}
			rpm = Math.max(shiftDown, Math.min(shiftPoint(), rpm));
		}
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	public double rpm() {
		return rpm;
	}

	private double shiftPoint() {
		return shiftUp + (boosting ? SHIFT_UP_BOOST : 0);
	}

	public double normalizedRpm() {
		return switch (state) {
			case IN_AIR -> (rpm - GEARS[0][0]) / (GEARS[0][2] - GEARS[0][0]);
			case ON_GROUND -> (rpm - GEARS[gear][0]) / (GEARS[gear][2] - GEARS[gear][0]);
			case CLUTCHED -> 0;
		};
	}
}
