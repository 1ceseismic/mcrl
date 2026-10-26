package dev.mcrl.natives;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;

import dev.mcrl.math.Pose;

public final class Layouts {
	public static final MemoryLayout BODY = MemoryLayout.structLayout(
			JAVA_DOUBLE.withName("px"), JAVA_DOUBLE.withName("py"), JAVA_DOUBLE.withName("pz"),
			JAVA_FLOAT.withName("qw"), JAVA_FLOAT.withName("qx"), JAVA_FLOAT.withName("qy"), JAVA_FLOAT.withName("qz"),
			JAVA_DOUBLE.withName("vx"), JAVA_DOUBLE.withName("vy"), JAVA_DOUBLE.withName("vz"),
			JAVA_DOUBLE.withName("wx"), JAVA_DOUBLE.withName("wy"), JAVA_DOUBLE.withName("wz"));

	public static final MemoryLayout CAR_STATE = MemoryLayout.structLayout(
			BODY.withName("body"),
			JAVA_FLOAT.withName("boost"), JAVA_INT.withName("flags"),
			MemoryLayout.sequenceLayout(12, JAVA_FLOAT).withName("wheelLocal"),
			JAVA_FLOAT.withName("steerAngle"), JAVA_INT.withName("wheelContact"),
			JAVA_INT.withName("ballHits"), JAVA_FLOAT.withName("ballHitSpeed"),
			JAVA_FLOAT.withName("stepDz"), JAVA_INT.withName("reserved"));

	public static final MemoryLayout BALL_STATE = MemoryLayout.structLayout(
			BODY.withName("body"), JAVA_INT.withName("bounces"), JAVA_FLOAT.withName("bounceSpeed"));

	public static final MemoryLayout CONTROLS = MemoryLayout.structLayout(
			JAVA_FLOAT.withName("throttle"), JAVA_FLOAT.withName("steer"), JAVA_FLOAT.withName("pitch"),
			JAVA_FLOAT.withName("yaw"), JAVA_FLOAT.withName("roll"),
			JAVA_BYTE.withName("jump"), JAVA_BYTE.withName("boost"), JAVA_BYTE.withName("handbrake"),
			JAVA_BYTE.withName("pad"));

	public static final MemoryLayout SECTION_INPUT = MemoryLayout.structLayout(
			JAVA_INT.withName("sx"), JAVA_INT.withName("sy"), JAVA_INT.withName("sz"),
			JAVA_INT.withName("version"), JAVA_INT.withName("border"), JAVA_INT.withName("flags"),
			ADDRESS.withName("cells"));

	public static final MemoryLayout BUILD_STATS = MemoryLayout.structLayout(
			JAVA_INT.withName("tris"), JAVA_FLOAT.withName("buildMs"), JAVA_INT.withName("flags"));

	public static final MemoryLayout WHEEL_FX = MemoryLayout.structLayout(
			JAVA_DOUBLE.withName("px"), JAVA_DOUBLE.withName("py"), JAVA_DOUBLE.withName("pz"),
			JAVA_FLOAT.withName("nx"), JAVA_FLOAT.withName("ny"), JAVA_FLOAT.withName("nz"),
			JAVA_INT.withName("contact"), JAVA_INT.withName("lands"), JAVA_FLOAT.withName("landSpeed"));

	public static final MemoryLayout HIT_FX = MemoryLayout.structLayout(
			JAVA_DOUBLE.withName("px"), JAVA_DOUBLE.withName("py"), JAVA_DOUBLE.withName("pz"),
			JAVA_FLOAT.withName("nx"), JAVA_FLOAT.withName("ny"), JAVA_FLOAT.withName("nz"), JAVA_INT.withName("pad"));

	public static final MemoryLayout WET_FX = MemoryLayout.structLayout(
			JAVA_FLOAT.withName("immersion"), JAVA_INT.withName("fluid"), JAVA_INT.withName("splashes"),
			JAVA_FLOAT.withName("splashSpeed"),
			JAVA_DOUBLE.withName("sx"), JAVA_DOUBLE.withName("sy"), JAVA_DOUBLE.withName("sz"));

	public static final MemoryLayout CAR_FX = MemoryLayout.structLayout(
			MemoryLayout.sequenceLayout(4, WHEEL_FX).withName("wheels"),
			JAVA_FLOAT.withName("handbrake"),
			JAVA_INT.withName("jumps"), JAVA_INT.withName("doubleJumps"), JAVA_INT.withName("dodges"),
			JAVA_INT.withName("flipResets"), JAVA_INT.withName("worldHits"),
			JAVA_FLOAT.withName("worldHitSpeed"),
			JAVA_INT.withName("bodyContact"), JAVA_INT.withName("activeStates"), JAVA_INT.withName("bumps"),
			JAVA_DOUBLE.withName("bpx"), JAVA_DOUBLE.withName("bpy"), JAVA_DOUBLE.withName("bpz"),
			JAVA_FLOAT.withName("bnx"), JAVA_FLOAT.withName("bny"), JAVA_FLOAT.withName("bnz"),
			JAVA_FLOAT.withName("slideSpeed"),
			HIT_FX.withName("worldHit"), HIT_FX.withName("ballHit"),
			JAVA_INT.withName("bumpedBy"), JAVA_FLOAT.withName("bumpSpeed"), WET_FX.withName("wet"));

	public static final MemoryLayout BALL_FX = MemoryLayout.structLayout(
			JAVA_INT.withName("worldContact"), JAVA_INT.withName("lastTouchTeam"), HIT_FX.withName("bounce"),
			WET_FX.withName("wet"));

	public static final MemoryLayout CAR_CONFIG = MemoryLayout.structLayout(
			MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("hitboxSize"),
			MemoryLayout.sequenceLayout(3, JAVA_FLOAT).withName("hitboxOffset"),
			MemoryLayout.sequenceLayout(2, JAVA_FLOAT).withName("wheelRadius"),
			MemoryLayout.sequenceLayout(2, JAVA_FLOAT).withName("suspensionRest"),
			MemoryLayout.sequenceLayout(6, JAVA_FLOAT).withName("connection"),
			JAVA_INT.withName("threeWheels"), JAVA_INT.withName("preset"), JAVA_INT.withName("team"),
			JAVA_INT.withName("pad"));

	public static final int SOCCAR_PADS = 34;
	public static final MemoryLayout SOCCAR_STATE = MemoryLayout.structLayout(
			JAVA_DOUBLE.withName("goalX"), JAVA_DOUBLE.withName("goalY"), JAVA_DOUBLE.withName("goalZ"),
			JAVA_FLOAT.withName("goalVx"), JAVA_FLOAT.withName("goalVy"), JAVA_FLOAT.withName("goalVz"),
			JAVA_INT.withName("goals"), JAVA_INT.withName("goalTeam"), JAVA_INT.withName("goalToucher"),
			JAVA_INT.withName("ballOutOfPlay"), JAVA_INT.withName("kickoffs"), JAVA_INT.withName("lockTicks"),
			MemoryLayout.sequenceLayout(SOCCAR_PADS, JAVA_BYTE).withName("padActive"),
			MemoryLayout.sequenceLayout(2, JAVA_BYTE).withName("reserved"),
			MemoryLayout.sequenceLayout(SOCCAR_PADS, JAVA_FLOAT).withName("padCooldown"),
			MemoryLayout.sequenceLayout(SOCCAR_PADS, JAVA_INT).withName("padPickups"),
			MemoryLayout.sequenceLayout(SOCCAR_PADS, JAVA_INT).withName("padCar"));
	public static final long SOCCAR_GOAL_VEL = 24;
	public static final long SOCCAR_GOALS = 36;
	public static final long SOCCAR_GOAL_TEAM = 40;
	public static final long SOCCAR_GOAL_TOUCHER = 44;
	public static final long SOCCAR_BALL_OUT = 48;
	public static final long SOCCAR_KICKOFFS = 52;
	public static final long SOCCAR_LOCK_TICKS = 56;
	public static final long SOCCAR_PAD_ACTIVE = 60;
	public static final long SOCCAR_PAD_COOLDOWN = 96;
	public static final long SOCCAR_PAD_PICKUPS = 232;
	public static final long SOCCAR_PAD_CAR = 368;
	public static final int SOCCAR_STATE_INDEX = 12;
	public static final MemoryLayout MATERIAL = MemoryLayout.structLayout(
			JAVA_FLOAT.withName("wheelLat"), JAVA_FLOAT.withName("wheelLong"), JAVA_FLOAT.withName("wheelDrag"),
			JAVA_FLOAT.withName("chassisFriction"), JAVA_FLOAT.withName("chassisRestitution"),
			JAVA_FLOAT.withName("ballFriction"), JAVA_FLOAT.withName("ballRestitution"), JAVA_FLOAT.withName("ballDrag"),
			JAVA_FLOAT.withName("padRestitution"), JAVA_INT.withName("pad"));

	public static final MemoryLayout BOX_Q = MemoryLayout.structLayout(
			MemoryLayout.sequenceLayout(3, JAVA_SHORT).withName("mn"),
			MemoryLayout.sequenceLayout(3, JAVA_SHORT).withName("mx"));

	public static final long BODY_VEL = 40;
	public static final long BODY_ANGVEL = 64;
	public static final long CAR_BOOST = 88;
	public static final long CAR_FLAGS = 92;
	public static final long CAR_WHEELS = 96;
	public static final long CAR_STEER = 144;
	public static final long CAR_WHEEL_CONTACT = 148;
	public static final long CAR_BALL_HITS = 152;
	public static final long CAR_BALL_HIT_SPEED = 156;
	public static final long CAR_STEP_DZ = 160;
	public static final long BALL_BOUNCES = 88;
	public static final long BALL_BOUNCE_SPEED = 92;
	public static final long SECTION_CELLS = 24;
	public static final long BUILD_STATS_FLAGS = 8;
	public static final long WHEEL_FX_NORMAL = 24;
	public static final long WHEEL_FX_CONTACT = 36;
	public static final long WHEEL_FX_LANDS = 40;
	public static final long WHEEL_FX_LAND_SPEED = 44;
	public static final long HIT_FX_NORMAL = 24;
	public static final long CAR_FX_WHEELS = 0;
	public static final long CAR_FX_HANDBRAKE = 192;
	public static final long CAR_FX_JUMPS = 196;
	public static final long CAR_FX_DOUBLE_JUMPS = 200;
	public static final long CAR_FX_DODGES = 204;
	public static final long CAR_FX_FLIP_RESETS = 208;
	public static final long CAR_FX_WORLD_HITS = 212;
	public static final long CAR_FX_WORLD_HIT_SPEED = 216;
	public static final long CAR_FX_BODY_CONTACT = 220;
	public static final long CAR_FX_ACTIVE_STATES = 224;
	public static final long CAR_FX_BODY_POINT = 232;
	public static final long CAR_FX_BODY_NORMAL = 256;
	public static final long CAR_FX_SLIDE_SPEED = 268;
	public static final long CAR_FX_WORLD_HIT = 272;
	public static final long CAR_FX_BALL_HIT = 312;
	public static final long CAR_FX_BUMPS = 228;
	public static final long CAR_FX_BUMPED_BY = 352;
	public static final long CAR_FX_BUMP_SPEED = 356;
	public static final long CAR_FX_WET = 360;
	public static final long BALL_FX_WET = 48;
	public static final long WET_FX_IMMERSION = 0;
	public static final long WET_FX_FLUID = 4;
	public static final long WET_FX_SPLASHES = 8;
	public static final long WET_FX_SPLASH_SPEED = 12;
	public static final long WET_FX_SPLASH_POINT = 16;
	public static final long CAR_CONFIG_HITBOX_SIZE = 0;
	public static final long CAR_CONFIG_HITBOX_OFFSET = 12;
	public static final long CAR_CONFIG_WHEEL_RADIUS = 24;
	public static final long CAR_CONFIG_SUSPENSION_REST = 32;
	public static final long CAR_CONFIG_CONNECTION = 40;
	public static final long CAR_CONFIG_THREE_WHEELS = 64;
	public static final long CAR_CONFIG_PRESET = 68;
	public static final long CAR_CONFIG_TEAM = 72;
	public static final int CAR_BLOB_SIZE = 320;
	public static final int BALL_BLOB_SIZE = 104;
	public static final int SOCCAR_BLOB_SIZE = 616;
	public static final int BLOB_VERSION = 1;
	public static final long BALL_FX_WORLD_CONTACT = 0;
	public static final long BALL_FX_LAST_TOUCH_TEAM = 4;
	public static final long BALL_FX_BOUNCE = 8;

	static {
		MemoryLayout[] all = {BODY, CAR_STATE, BALL_STATE, CONTROLS, SECTION_INPUT, BUILD_STATS, CAR_FX, BALL_FX,
				CAR_CONFIG, MATERIAL, WET_FX};
		String[] names = {"BODY", "CAR_STATE", "BALL_STATE", "CONTROLS", "SECTION_INPUT", "BUILD_STATS", "CAR_FX",
				"BALL_FX", "CAR_CONFIG", "MATERIAL", "WET_FX"};
		for (int i = 0; i < all.length; i++) {
			long java = all[i].byteSize();
			int nat = McrlNative.sizeOf(i);
			if (java != nat) {
				throw new IllegalStateException("layout mismatch: " + names[i] + " java=" + java + " native=" + nat);
			}
		}
		int soccar = McrlNative.sizeOf(SOCCAR_STATE_INDEX);
		if (SOCCAR_STATE.byteSize() != soccar) {
			throw new IllegalStateException("layout mismatch: SOCCAR_STATE java=" + SOCCAR_STATE.byteSize() + " native="
					+ soccar);
		}
		int[][] blobs = {{16, CAR_BLOB_SIZE}, {17, BALL_BLOB_SIZE}, {18, SOCCAR_BLOB_SIZE}};
		for (int[] b : blobs) {
			int nat = McrlNative.sizeOf(b[0]);
			if (nat != b[1]) {
				throw new IllegalStateException("layout mismatch: blob " + b[0] + " java=" + b[1] + " native=" + nat);
			}
		}
	}

	private Layouts() {}

	public static void verify() {}

	public static void writePose(MemorySegment s, Pose p) {
		s.set(JAVA_DOUBLE, 0, p.x());
		s.set(JAVA_DOUBLE, 8, p.y());
		s.set(JAVA_DOUBLE, 16, p.z());
		s.set(JAVA_FLOAT, 24, p.qw());
		s.set(JAVA_FLOAT, 28, p.qx());
		s.set(JAVA_FLOAT, 32, p.qy());
		s.set(JAVA_FLOAT, 36, p.qz());
	}

	public static Pose readPose(MemorySegment s) {
		return new Pose(s.get(JAVA_DOUBLE, 0), s.get(JAVA_DOUBLE, 8), s.get(JAVA_DOUBLE, 16),
				s.get(JAVA_FLOAT, 24), s.get(JAVA_FLOAT, 28), s.get(JAVA_FLOAT, 32), s.get(JAVA_FLOAT, 36));
	}

	public static void writeVec(MemorySegment s, long off, double x, double y, double z) {
		s.set(JAVA_DOUBLE, off, x);
		s.set(JAVA_DOUBLE, off + 8, y);
		s.set(JAVA_DOUBLE, off + 16, z);
	}
}
