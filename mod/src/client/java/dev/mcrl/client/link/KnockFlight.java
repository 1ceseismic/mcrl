package dev.mcrl.client.link;

final class KnockFlight {
	public static final int MAX_TICKS = 10;
	public static final double HANDOVER_NEAR = 0.05;
	public static final double CORRECTION = 1.0;
	public static final int BLEND_TICKS = 2;
	// Vanilla LivingEntity's aiStep cutoff and travelInAir drags.
	static final double CUTOFF = 0.003;
	static final float AIR_FRICTION = 0.91F, VERTICAL_DRAG = 0.98F;

	public enum State {
		FLYING,
		RESTING,
		BLENDING,
		DONE
	}

	public enum Action {
		IGNORE,
		FOLLOW,
		HOLD,
		BLEND
	}

	private double x, y, z, vx, vy, vz;
	private boolean onGround;
	private final float groundFriction, airDragModifier;
	private final double gravity, floorY, dirX, dirZ;
	private final double[] path = new double[3 * (MAX_TICKS + 1)];
	private int flightTicks, restTicks, blendTick;
	private double blendFromX, blendFromY, blendFromZ, blendToX, blendToY, blendToZ;
	private State state = State.FLYING;

	public KnockFlight(double x, double y, double z, double vx, double vy, double vz, boolean onGround,
			float groundFriction, float airDragModifier, double gravity, double floorY) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.vx = vx;
		this.vy = vy;
		this.vz = vz;
		this.onGround = onGround;
		this.groundFriction = groundFriction;
		this.airDragModifier = airDragModifier;
		this.gravity = gravity;
		this.floorY = floorY;
		double h = Math.hypot(vx, vz);
		this.dirX = h > 1e-9 ? vx / h : 0.0;
		this.dirZ = h > 1e-9 ? vz / h : 0.0;
		path[0] = x;
		path[1] = y;
		path[2] = z;
	}

	// LivingEntity.computeModifiedFriction, in float.
	public static float modifiedFriction(float friction, float modifier) {
		float f = 1.0F - (1.0F - friction) * modifier;
		return f < 0.0F ? 0.0F : f > 1.0F ? 1.0F : f;
	}

	public boolean tick() {
		switch (state) {
			case FLYING -> {
				fly();
				return true;
			}
			case BLENDING -> {
				blendTick++;
				double k = (double) blendTick / BLEND_TICKS;
				x = blendFromX + (blendToX - blendFromX) * k;
				y = blendFromY + (blendToY - blendFromY) * k;
				z = blendFromZ + (blendToZ - blendFromZ) * k;
				if (blendTick >= BLEND_TICKS) {
					state = State.DONE;
				}
				return true;
			}
			case RESTING -> {
				if (++restTicks >= MAX_TICKS) {
					state = State.DONE;
				}
				return false;
			}
			default -> {
				return false;
			}
		}
	}

	// One airborne LivingEntity tick, in vanilla's order.
	private void fly() {
		if (Math.abs(vx) < CUTOFF) {
			vx = 0.0;
		}
		if (Math.abs(vz) < CUTOFF) {
			vz = 0.0;
		}
		if (Math.abs(vy) < CUTOFF) {
			vy = 0.0;
		}
		float blockFriction = onGround ? groundFriction : 1.0F;
		x += vx;
		y += vy;
		z += vz;
		float air = modifiedFriction(AIR_FRICTION, airDragModifier);
		float horizontal = blockFriction * air;
		float vertical = modifiedFriction(VERTICAL_DRAG, airDragModifier);
		vx = vx * (double) horizontal;
		vy = (vy - gravity) * (double) vertical;
		vz = vz * (double) horizontal;
		onGround = false;
		flightTicks++;
		if (y <= floorY) {
			y = floorY;
			state = State.RESTING;
		} else if (flightTicks >= MAX_TICKS) {
			state = State.RESTING;
		}
		path[3 * flightTicks] = x;
		path[3 * flightTicks + 1] = y;
		path[3 * flightTicks + 2] = z;
	}

	public void blocked() {
		if (flightTicks > 0) {
			flightTicks--;
			x = path[3 * flightTicks];
			y = path[3 * flightTicks + 1];
			z = path[3 * flightTicks + 2];
		}
		state = State.RESTING;
	}

	public Action onServerUpdate(double tx, double ty, double tz) {
		if (state != State.FLYING && state != State.RESTING) {
			state = State.DONE;
			return Action.FOLLOW;
		}
		double ex = tx - x, ey = ty - y, ez = tz - z;
		double dist = Math.sqrt(ex * ex + ey * ey + ez * ez);
		double along = ex * dirX + ez * dirZ;
		if (dist > CORRECTION && !onEarlierPath(tx, ty, tz)) {
			blendFromX = x;
			blendFromY = y;
			blendFromZ = z;
			blendToX = tx;
			blendToY = ty;
			blendToZ = tz;
			blendTick = 0;
			state = State.BLENDING;
			return Action.BLEND;
		}
		if (dist <= HANDOVER_NEAR) {
			state = State.DONE;
			return along < 0.0 ? Action.HOLD : Action.FOLLOW;
		}
		if (along >= 0.0) {
			state = State.DONE;
			return Action.FOLLOW;
		}
		return Action.IGNORE;
	}

	private boolean onEarlierPath(double tx, double ty, double tz) {
		for (int k = 0; k < flightTicks; k++) {
			double ex = tx - path[3 * k], ey = ty - path[3 * k + 1], ez = tz - path[3 * k + 2];
			if (ex * ex + ey * ey + ez * ez <= HANDOVER_NEAR * HANDOVER_NEAR) {
				return true;
			}
		}
		return false;
	}

	public State state() {
		return state;
	}

	public int ticks() {
		return flightTicks;
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public double z() {
		return z;
	}

	public double vx() {
		return state == State.FLYING ? vx : 0.0;
	}

	public double vy() {
		return state == State.FLYING ? vy : 0.0;
	}

	public double vz() {
		return state == State.FLYING ? vz : 0.0;
	}
}
