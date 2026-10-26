package dev.mcrl.sim;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Objects;

import dev.mcrl.natives.Layouts;

public record CarFx(WheelContact[] wheels, float handbrake, int jumps, int doubleJumps, int dodges, int flipResets,
		int worldHits, float worldHitSpeed, boolean bodyContact, float bnx, float bny, float bnz, float slideSpeed,
		double bpx, double bpy, double bpz, Hit worldHit, Hit ballHit, int activeStates, int bumps, int bumpedBy,
		float bumpSpeed, Wet wet) {
	public static final int JUMPING = 1, DOUBLE_JUMPING = 2, FLIPPING = 4;

	public static final CarFx EMPTY = new CarFx(new WheelContact[] {WheelContact.NONE, WheelContact.NONE,
			WheelContact.NONE, WheelContact.NONE}, 0f, 0, 0, 0, 0, 0, 0f, false, 0f, 0f, 0f, 0f, 0, 0, 0, Hit.NONE,
			Hit.NONE, 0, 0, -1, 0f, Wet.DRY);

	public CarFx {
		if (wheels.length != 4) {
			throw new IllegalArgumentException("wheels must have 4 entries");
		}
		wheels = wheels.clone();
	}

	@Override
	public WheelContact[] wheels() {
		return wheels.clone();
	}

	public WheelContact wheel(int i) {
		return wheels[i];
	}

	public boolean jumping() {
		return (activeStates & JUMPING) != 0;
	}

	public boolean doubleJumping() {
		return (activeStates & DOUBLE_JUMPING) != 0;
	}

	public boolean flipping() {
		return (activeStates & FLIPPING) != 0;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof CarFx f && Arrays.equals(wheels, f.wheels)
				&& Float.compare(handbrake, f.handbrake) == 0 && jumps == f.jumps && doubleJumps == f.doubleJumps
				&& dodges == f.dodges && flipResets == f.flipResets && worldHits == f.worldHits
				&& Float.compare(worldHitSpeed, f.worldHitSpeed) == 0 && bodyContact == f.bodyContact
				&& Float.compare(bnx, f.bnx) == 0 && Float.compare(bny, f.bny) == 0 && Float.compare(bnz, f.bnz) == 0
				&& Float.compare(slideSpeed, f.slideSpeed) == 0 && Double.compare(bpx, f.bpx) == 0
				&& Double.compare(bpy, f.bpy) == 0 && Double.compare(bpz, f.bpz) == 0
				&& worldHit.equals(f.worldHit) && ballHit.equals(f.ballHit) && activeStates == f.activeStates
				&& bumps == f.bumps && bumpedBy == f.bumpedBy && Float.compare(bumpSpeed, f.bumpSpeed) == 0
				&& wet.equals(f.wet);
	}

	@Override
	public int hashCode() {
		return 31 * Arrays.hashCode(wheels) + Objects.hash(handbrake, jumps, doubleJumps, dodges, flipResets, worldHits,
				worldHitSpeed, bodyContact, bnx, bny, bnz, slideSpeed, bpx, bpy, bpz, worldHit, ballHit, activeStates,
				bumps, bumpedBy, bumpSpeed, wet);
	}

	@Override
	public String toString() {
		return "CarFx[wheels=" + Arrays.toString(wheels) + ", handbrake=" + handbrake + ", jumps=" + jumps
				+ ", doubleJumps=" + doubleJumps + ", dodges=" + dodges + ", flipResets=" + flipResets + ", worldHits="
				+ worldHits + ", worldHitSpeed=" + worldHitSpeed + ", bodyContact=" + bodyContact + ", bn=(" + bnx + ", "
				+ bny + ", " + bnz + "), slideSpeed=" + slideSpeed + ", bp=(" + bpx + ", " + bpy + ", " + bpz
				+ "), worldHit=" + worldHit + ", ballHit=" + ballHit + ", activeStates=" + activeStates + ", bumps="
				+ bumps + ", bumpedBy=" + bumpedBy + ", bumpSpeed=" + bumpSpeed + ", wet=" + wet + "]";
	}

	public static CarFx readFrom(MemorySegment s) {
		WheelContact[] w = new WheelContact[4];
		long size = Layouts.WHEEL_FX.byteSize();
		for (int i = 0; i < 4; i++) {
			w[i] = WheelContact.readFrom(s, Layouts.CAR_FX_WHEELS + size * i);
		}
		long p = Layouts.CAR_FX_BODY_POINT, n = Layouts.CAR_FX_BODY_NORMAL;
		return new CarFx(w, s.get(JAVA_FLOAT, Layouts.CAR_FX_HANDBRAKE),
				s.get(JAVA_INT, Layouts.CAR_FX_JUMPS), s.get(JAVA_INT, Layouts.CAR_FX_DOUBLE_JUMPS),
				s.get(JAVA_INT, Layouts.CAR_FX_DODGES), s.get(JAVA_INT, Layouts.CAR_FX_FLIP_RESETS),
				s.get(JAVA_INT, Layouts.CAR_FX_WORLD_HITS), s.get(JAVA_FLOAT, Layouts.CAR_FX_WORLD_HIT_SPEED),
				s.get(JAVA_INT, Layouts.CAR_FX_BODY_CONTACT) != 0,
				s.get(JAVA_FLOAT, n), s.get(JAVA_FLOAT, n + 4), s.get(JAVA_FLOAT, n + 8),
				s.get(JAVA_FLOAT, Layouts.CAR_FX_SLIDE_SPEED),
				s.get(JAVA_DOUBLE, p), s.get(JAVA_DOUBLE, p + 8), s.get(JAVA_DOUBLE, p + 16),
				Hit.readFrom(s, Layouts.CAR_FX_WORLD_HIT), Hit.readFrom(s, Layouts.CAR_FX_BALL_HIT),
				s.get(JAVA_INT, Layouts.CAR_FX_ACTIVE_STATES), s.get(JAVA_INT, Layouts.CAR_FX_BUMPS),
				s.get(JAVA_INT, Layouts.CAR_FX_BUMPED_BY), s.get(JAVA_FLOAT, Layouts.CAR_FX_BUMP_SPEED),
				Wet.readFrom(s, Layouts.CAR_FX_WET));
	}
}
