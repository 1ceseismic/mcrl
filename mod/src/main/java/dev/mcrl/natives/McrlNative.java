package dev.mcrl.natives;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

// Mirrors native/include/mcrl.h. World calls: its creating thread only.
public final class McrlNative {
	private static final class Handles {
		static final SymbolLookup LOOKUP = NativeLoader.load();
		static final Linker LINKER = Linker.nativeLinker();
		static final MethodHandle ABI_VERSION = bind("mcrl_abi_version", FunctionDescriptor.of(JAVA_INT));
		static final MethodHandle INIT = bind("mcrl_init", FunctionDescriptor.of(JAVA_INT));
		static final MethodHandle LAST_ERROR = bind("mcrl_last_error", FunctionDescriptor.of(ADDRESS));

		static final MethodHandle SIZEOF = bind("mcrl_sizeof", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
		static final MethodHandle WORLD_CREATE = bind("mcrl_world_create",
				FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
		static final MethodHandle WORLD_DESTROY = bind("mcrl_world_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		static final MethodHandle SPAWN_GATE = bind("mcrl_world_set_spawn_gate",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		static final MethodHandle STEP_UP = bind("mcrl_world_set_step_up",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		static final MethodHandle WORLD_SHIFT_ORIGIN = bind("mcrl_world_shift_origin",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
		static final MethodHandle CAR_ADD = bind("mcrl_car_add",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
		static final MethodHandle CAR_GET_CONFIG = bind("mcrl_car_get_config",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle CAR_DEMOLISH = bind("mcrl_car_demolish",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		static final MethodHandle CAR_RESPAWN = bind("mcrl_car_respawn",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle SET_DEMOS = bind("mcrl_world_set_demos",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
		static final MethodHandle GATE_MODE = bind("mcrl_world_set_gate_mode",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		static final MethodHandle SET_EXTRAS = bind("mcrl_world_set_extras",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		static final MethodHandle CAR_REMOVE = bind("mcrl_car_remove", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		static final MethodHandle CAR_SET_STATE = bind("mcrl_car_set_state",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle CAR_GET_STATE = bind("mcrl_car_get_state",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle CAR_SET_CONTROLS = bind("mcrl_car_set_controls",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle BALL_SET_STATE = bind("mcrl_ball_set_state",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle BALL_GET_STATE = bind("mcrl_ball_get_state",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle CAR_GET_FX = bind("mcrl_car_get_fx",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle BALL_GET_FX = bind("mcrl_ball_get_fx",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle BALL_CLEAR_TOUCH = bind("mcrl_ball_clear_touch",
				FunctionDescriptor.of(JAVA_INT, ADDRESS));
		static final MethodHandle CAR_ADD_VELOCITY = bind("mcrl_car_add_velocity",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
		static final MethodHandle BALL_ADD_VELOCITY = bind("mcrl_ball_add_velocity",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
		static final MethodHandle STEP = bind("mcrl_step", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle WORLD_SET_TICK = bind("mcrl_world_set_tick",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
		static final MethodHandle CAR_SAVE = bind("mcrl_car_save",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle CAR_LOAD = bind("mcrl_car_load",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle BALL_SAVE = bind("mcrl_ball_save", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle BALL_LOAD = bind("mcrl_ball_load", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle SOCCAR_SAVE = bind("mcrl_soccar_save", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle SOCCAR_LOAD = bind("mcrl_soccar_load", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle WORLD_REWIND_EVENTS = bind("mcrl_world_rewind_events",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
		static final MethodHandle SECTION_COMMIT = bind("mcrl_section_commit",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle SECTION_REMOVE = bind("mcrl_section_remove",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
		static final MethodHandle SHAPES_CREATE = bind("mcrl_shapes_create", FunctionDescriptor.of(ADDRESS));
		static final MethodHandle SHAPES_DESTROY = bind("mcrl_shapes_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		static final MethodHandle SHAPES_REGISTER = bind("mcrl_shapes_register",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
		static final MethodHandle SHAPES_COUNT = bind("mcrl_shapes_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		static final MethodHandle SHAPES_SET_MATERIAL = bind("mcrl_shapes_set_material",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle SECTION_BUILD = bind("mcrl_section_build",
				FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
		static final MethodHandle BUILT_FREE = bind("mcrl_built_free", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		static final MethodHandle ARENA_MESHES_LOAD = bind("mcrl_arena_meshes_load",
				FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle WORLD_CREATE_SOCCAR = bind("mcrl_world_create_soccar",
				FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
		static final MethodHandle SOCCAR_KICKOFF = bind("mcrl_soccar_kickoff",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
		static final MethodHandle SOCCAR_GET_STATE = bind("mcrl_soccar_get_state",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle SOCCAR_RESPAWN_STATE = bind("mcrl_soccar_respawn_state",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
		static final MethodHandle SOCCAR_PAD_LAYOUT = bind("mcrl_soccar_pad_layout",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		static final MethodHandle SOCCAR_PARK_BALL = bind("mcrl_soccar_park_ball", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		static final MethodHandle BUILT_SET_FLUID = bind("mcrl_built_set_fluid",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle SECTION_SET_FLUID = bind("mcrl_section_set_fluid",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
		static final MethodHandle BOT_OBSERVE = bind("mcrl_bot_observe",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
		static final MethodHandle BOT_POLICY_CREATE = bind("mcrl_bot_policy_create",
				FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
		static final MethodHandle BOT_POLICY_FREE = bind("mcrl_bot_policy_free", FunctionDescriptor.ofVoid(ADDRESS));
		static final MethodHandle BOT_POLICY_FORWARD = bind("mcrl_bot_policy_forward",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
		static final MethodHandle BOT_ADD = bind("mcrl_bot_add",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_LONG));
		static final MethodHandle BOT_REMOVE = bind("mcrl_bot_remove", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		static final MethodHandle CAR_GET_CONTROLS = bind("mcrl_car_get_controls",
				FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

		static MethodHandle bind(String name, FunctionDescriptor fd) {
			return LINKER.downcallHandle(LOOKUP.find(name).orElseThrow(), fd);
		}
	}

	private McrlNative() {}

	public static final int EXPECTED_ABI_VERSION = 7;

	public static int abiVersion() {
		try {
			return (int) Handles.ABI_VERSION.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static int init() {
		try {
			return (int) Handles.INIT.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static String lastError() {
		try {
			MemorySegment p = (MemorySegment) Handles.LAST_ERROR.invokeExact();
			if (p.equals(MemorySegment.NULL)) {
				return "";
			}
			return p.reinterpret(4096).getString(0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static final int ERR_EXCEPTION = -1;
	public static final int ERR_ARG = -3;
	private static final int ERR_NOT_FOUND = -4;
	public static final int STALE = 1;
	public static final int STEP_FROZEN = 1;
	public static final int STEP_BALL_PARKED = 2;
	public static final int STEP_RESTORED = 4;
	public static final int STEP_BALL_RESTORED = 8;
	public static final int CAR_ON_GROUND = 1;
	public static final int WHEEL_CONTACT = 1;
	public static final int WHEEL_WORLD = 2;
	public static final int BUILT_PROVISIONAL = 1;

	private static int check(int code) {
		if (code < 0) {
			throw new McrlNativeException(code, lastError());
		}
		return code;
	}

	private static int checkStale(int code) {
		if (code != 0 && code != STALE) {
			throw new McrlNativeException(code, lastError());
		}
		return code;
	}

	private static MemorySegment checkPtr(MemorySegment p, String what) {
		if (p.equals(MemorySegment.NULL)) {
			throw new McrlNativeException(ERR_EXCEPTION, what + ": " + lastError());
		}
		return p;
	}

	public static int sizeOf(int which) {
		try {
			return check((int) Handles.SIZEOF.invokeExact(which));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static MemorySegment worldCreate(MemorySegment shapes, int ox, int oy, int oz) {
		try {
			return checkPtr((MemorySegment) Handles.WORLD_CREATE.invokeExact(shapes, ox, oy, oz), "world_create");
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void worldDestroy(MemorySegment world) {
		try {
			check((int) Handles.WORLD_DESTROY.invokeExact(world));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void worldSetStepUp(MemorySegment world, boolean enabled) {
		try {
			check((int) Handles.STEP_UP.invokeExact(world, enabled ? 1 : 0));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void worldSetSpawnGate(MemorySegment world, boolean enabled) {
		try {
			check((int) Handles.SPAWN_GATE.invokeExact(world, enabled ? 1 : 0));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void worldSetGateMode(MemorySegment world, boolean perCar) {
		try {
			check((int) Handles.GATE_MODE.invokeExact(world, perCar ? 1 : 0));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static final int EXTRAS_SURFACES = 1, EXTRAS_FLUIDS = 2;

	public static void worldSetExtras(MemorySegment world, int flags) {
		try {
			check((int) Handles.SET_EXTRAS.invokeExact(world, flags));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void worldSetDemos(MemorySegment world, int mode, boolean teamDemos) {
		try {
			check((int) Handles.SET_DEMOS.invokeExact(world, mode, teamDemos ? 1 : 0));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void worldShiftOrigin(MemorySegment world, int ox, int oy, int oz) {
		try {
			check((int) Handles.WORLD_SHIFT_ORIGIN.invokeExact(world, ox, oy, oz));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carAdd(MemorySegment world, int id, int team, int preset, MemorySegment state) {
		try {
			check((int) Handles.CAR_ADD.invokeExact(world, id, team, preset, state));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carGetConfig(MemorySegment world, int id, MemorySegment out) {
		try {
			check((int) Handles.CAR_GET_CONFIG.invokeExact(world, id, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carDemolish(MemorySegment world, int id) {
		try {
			check((int) Handles.CAR_DEMOLISH.invokeExact(world, id));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carRespawn(MemorySegment world, int id, MemorySegment state) {
		try {
			check((int) Handles.CAR_RESPAWN.invokeExact(world, id, state));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carRemove(MemorySegment world, int id) {
		try {
			check((int) Handles.CAR_REMOVE.invokeExact(world, id));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carSetState(MemorySegment world, int id, MemorySegment state) {
		try {
			check((int) Handles.CAR_SET_STATE.invokeExact(world, id, state));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carGetState(MemorySegment world, int id, MemorySegment out) {
		try {
			check((int) Handles.CAR_GET_STATE.invokeExact(world, id, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carSetControls(MemorySegment world, int id, MemorySegment controls) {
		try {
			check((int) Handles.CAR_SET_CONTROLS.invokeExact(world, id, controls));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void ballSetState(MemorySegment world, MemorySegment state) {
		try {
			check((int) Handles.BALL_SET_STATE.invokeExact(world, state));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void ballGetState(MemorySegment world, MemorySegment out) {
		try {
			check((int) Handles.BALL_GET_STATE.invokeExact(world, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carGetFx(MemorySegment world, int id, MemorySegment out) {
		try {
			check((int) Handles.CAR_GET_FX.invokeExact(world, id, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void ballGetFx(MemorySegment world, MemorySegment out) {
		try {
			check((int) Handles.BALL_GET_FX.invokeExact(world, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void ballClearTouch(MemorySegment world) {
		try {
			check((int) Handles.BALL_CLEAR_TOUCH.invokeExact(world));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carAddVelocity(MemorySegment world, int id, double dvx, double dvy, double dvz) {
		try {
			check((int) Handles.CAR_ADD_VELOCITY.invokeExact(world, id, dvx, dvy, dvz));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void worldSetTick(MemorySegment world, long tick) {
		try {
			check((int) Handles.WORLD_SET_TICK.invokeExact(world, tick));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void ballAddVelocity(MemorySegment world, double dvx, double dvy, double dvz) {
		try {
			check((int) Handles.BALL_ADD_VELOCITY.invokeExact(world, dvx, dvy, dvz));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carSave(MemorySegment world, int id, MemorySegment out) {
		blobBytes(out, Layouts.CAR_BLOB_SIZE, "out");
		try {
			check((int) Handles.CAR_SAVE.invokeExact(world, id, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carLoad(MemorySegment world, int id, MemorySegment in) {
		blobBytes(in, Layouts.CAR_BLOB_SIZE, "in");
		try {
			check((int) Handles.CAR_LOAD.invokeExact(world, id, in));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void ballSave(MemorySegment world, MemorySegment out) {
		blobBytes(out, Layouts.BALL_BLOB_SIZE, "out");
		try {
			check((int) Handles.BALL_SAVE.invokeExact(world, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void ballLoad(MemorySegment world, MemorySegment in) {
		blobBytes(in, Layouts.BALL_BLOB_SIZE, "in");
		try {
			check((int) Handles.BALL_LOAD.invokeExact(world, in));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void soccarSave(MemorySegment world, MemorySegment out) {
		blobBytes(out, Layouts.SOCCAR_BLOB_SIZE, "out");
		try {
			check((int) Handles.SOCCAR_SAVE.invokeExact(world, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void soccarLoad(MemorySegment world, MemorySegment in) {
		blobBytes(in, Layouts.SOCCAR_BLOB_SIZE, "in");
		try {
			check((int) Handles.SOCCAR_LOAD.invokeExact(world, in));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static void blobBytes(MemorySegment s, int size, String what) {
		if (s.byteSize() < size) {
			throw new IllegalArgumentException("a blob's " + what + " of " + s.byteSize() + " bytes, not " + size);
		}
	}

	public static boolean worldRewindEvents(MemorySegment world, long tick) {
		try {
			int rc = (int) Handles.WORLD_REWIND_EVENTS.invokeExact(world, tick);
			if (rc == ERR_NOT_FOUND) {
				return false;
			}
			check(rc);
			return true;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static int step(MemorySegment world, int ticks, MemorySegment outFlags) {
		try {
			return check((int) Handles.STEP.invokeExact(world, ticks, outFlags));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static MemorySegment shapesCreate() {
		try {
			return checkPtr((MemorySegment) Handles.SHAPES_CREATE.invokeExact(), "shapes_create");
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void shapesDestroy(MemorySegment shapes) {
		try {
			check((int) Handles.SHAPES_DESTROY.invokeExact(shapes));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static int shapesRegister(MemorySegment shapes, MemorySegment boxes, int n, int flags) {
		try {
			return check((int) Handles.SHAPES_REGISTER.invokeExact(shapes, boxes, n, flags));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static int shapesCount(MemorySegment shapes) {
		try {
			return check((int) Handles.SHAPES_COUNT.invokeExact(shapes));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static int shapesSetMaterial(MemorySegment shapes, int id, MemorySegment material) {
		try {
			return check((int) Handles.SHAPES_SET_MATERIAL.invokeExact(shapes, id, material));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void builtSetFluid(MemorySegment built, MemorySegment cells) {
		try {
			check((int) Handles.BUILT_SET_FLUID.invokeExact(built, cells));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void sectionSetFluid(MemorySegment world, int sx, int sy, int sz, MemorySegment cells) {
		try {
			check((int) Handles.SECTION_SET_FLUID.invokeExact(world, sx, sy, sz, cells));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	// The caller owns the build until sectionCommit or builtFree.
	public static MemorySegment sectionBuild(MemorySegment shapes, MemorySegment input, MemorySegment stats) {
		try {
			return checkPtr((MemorySegment) Handles.SECTION_BUILD.invokeExact(shapes, input, stats), "section_build");
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void builtFree(MemorySegment built) {
		try {
			check((int) Handles.BUILT_FREE.invokeExact(built));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	// Always consumes the build.
	public static int sectionCommit(MemorySegment world, MemorySegment built) {
		try {
			return checkStale((int) Handles.SECTION_COMMIT.invokeExact(world, built));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void sectionRemove(MemorySegment world, int sx, int sy, int sz) {
		try {
			check((int) Handles.SECTION_REMOVE.invokeExact(world, sx, sy, sz));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	// Never freed: every SOCCAR world on it shares it.
	public static MemorySegment arenaMeshesLoad(MemorySegment files, MemorySegment sizes, int n, MemorySegment outHashes) {
		try {
			return checkPtr((MemorySegment) Handles.ARENA_MESHES_LOAD.invokeExact(files, sizes, n, outHashes),
					"arena_meshes_load");
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static MemorySegment worldCreateSoccar(MemorySegment meshes, int ox, int oy, int oz) {
		try {
			return checkPtr((MemorySegment) Handles.WORLD_CREATE_SOCCAR.invokeExact(meshes, ox, oy, oz),
					"world_create_soccar");
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void soccarKickoff(MemorySegment world, int seed, int lockTicks) {
		try {
			check((int) Handles.SOCCAR_KICKOFF.invokeExact(world, seed, lockTicks));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void soccarGetState(MemorySegment world, MemorySegment out) {
		try {
			check((int) Handles.SOCCAR_GET_STATE.invokeExact(world, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void soccarRespawnState(MemorySegment world, int team, int index, MemorySegment out) {
		try {
			check((int) Handles.SOCCAR_RESPAWN_STATE.invokeExact(world, team, index, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void soccarPadLayout(MemorySegment world, MemorySegment outXyz, MemorySegment outBig) {
		try {
			check((int) Handles.SOCCAR_PAD_LAYOUT.invokeExact(world, outXyz, outBig));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void soccarParkBall(MemorySegment world) {
		try {
			check((int) Handles.SOCCAR_PARK_BALL.invokeExact(world));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static MemorySegment botPolicyCreate(int[] dims, float[][] params) {
		if (dims.length < 2 || params.length != 2 * (dims.length - 1)) {
			throw new IllegalArgumentException("bot policy: " + params.length + " arrays for " + dims.length + " dims");
		}
		for (int l = 0; l + 1 < dims.length; l++) {
			long w = (long) dims[l + 1] * dims[l];
			if (dims[l] <= 0 || dims[l + 1] <= 0 || params[2 * l].length != w || params[2 * l + 1].length != dims[l + 1]) {
				throw new IllegalArgumentException("bot policy: layer " + l + " has " + params[2 * l].length + " weights and "
						+ params[2 * l + 1].length + " biases for " + dims[l] + " -> " + dims[l + 1]);
			}
		}
		try (Arena a = Arena.ofConfined()) {
			MemorySegment d = a.allocateFrom(JAVA_INT, dims);
			MemorySegment ptrs = a.allocate(ADDRESS, params.length);
			for (int i = 0; i < params.length; i++) {
				ptrs.setAtIndex(ADDRESS, i, a.allocateFrom(JAVA_FLOAT, params[i]));
			}
			return checkPtr((MemorySegment) Handles.BOT_POLICY_CREATE.invokeExact(d, dims.length - 1, ptrs),
					"bot_policy_create");
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void botPolicyFree(MemorySegment p) {
		try {
			Handles.BOT_POLICY_FREE.invokeExact(p);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void botPolicyForward(MemorySegment p, float[] obs, float[] logits) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment in = a.allocateFrom(JAVA_FLOAT, obs);
			MemorySegment out = a.allocate(JAVA_FLOAT, logits.length);
			check((int) Handles.BOT_POLICY_FORWARD.invokeExact(p, in, out));
			MemorySegment.copy(out, JAVA_FLOAT, 0, logits, 0, logits.length);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void botObserve(MemorySegment world, int carId, int prevAction, float[] out) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment o = a.allocate(JAVA_FLOAT, out.length);
			check((int) Handles.BOT_OBSERVE.invokeExact(world, carId, prevAction, o));
			MemorySegment.copy(o, JAVA_FLOAT, 0, out, 0, out.length);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void botAdd(MemorySegment world, int carId, MemorySegment p, float temperature, long seed) {
		try {
			check((int) Handles.BOT_ADD.invokeExact(world, carId, p, temperature, seed));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void botRemove(MemorySegment world, int carId) {
		try {
			check((int) Handles.BOT_REMOVE.invokeExact(world, carId));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void carGetControls(MemorySegment world, int carId, MemorySegment out) {
		try {
			check((int) Handles.CAR_GET_CONTROLS.invokeExact(world, carId, out));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) {
			return r;
		}
		if (t instanceof Error e) {
			throw e;
		}
		return new IllegalStateException(t);
	}
}
