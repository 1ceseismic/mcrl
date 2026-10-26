package dev.mcrl.client.fx.particle;

import java.util.List;
import java.util.random.RandomGenerator;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.model.Distribution;

public record FxEmitterDef(int index, String name, Required required, Spawn spawn, @Nullable PerUnit perUnit,
		TypeData typeData, List<FxModule> modules) {
	public FxEmitterDef {
		modules = List.copyOf(modules);
	}

	public enum Blend { ADDITIVE, TRANSLUCENT, MASKED, OPAQUE }

	public enum SubUv { NONE, LINEAR, LINEAR_BLEND, RANDOM, RANDOM_BLEND }

	public enum Alignment { SQUARE, RECTANGLE, VELOCITY, TYPE_SPECIFIC }

	public enum Sort { NONE, VIEW_PROJ_DEPTH, DISTANCE_TO_VIEW, AGE_OLDEST_FIRST, AGE_NEWEST_FIRST }

	public enum Kind { SPRITE, RIBBON, MESH, BEAM }

	public record Required(String material, Blend blend, int subImagesH, int subImagesV, SubUv interpolation,
			Alignment alignment, boolean localSpace, float duration, int loops, float delay, boolean delayFirstLoopOnly,
			boolean killOnDeactivate, boolean killOnCompleted, int maxDrawCount, Sort sort) {
	}

	public record Burst(float time, int count, int countLow) {
	}

	public record Spawn(@Nullable Distribution rate, @Nullable Distribution rateScale, boolean processRate,
			List<Burst> bursts, boolean processBursts) {
		public static final Spawn NONE = new Spawn(null, null, true, List.of(), true);

		public Spawn {
			bursts = List.copyOf(bursts);
		}

		public float rate(float t, RandomGenerator rng, FxParams p) {
			if (rate == null) {
				return 0;
			}
			float r = FxDistributions.scalar(rate, t, rng, p);
			float s = rateScale == null ? 1 : Math.max(0, FxDistributions.scalar(rateScale, t, rng, p));
			return Math.max(0, r * s);
		}
	}

	public record PerUnit(float unitScalar, Distribution spawnPerUnit, float movementTolerance, float maxFrameDistance,
			boolean processSpawnRate, boolean ignoreRateWhenMoving) {
	}

	public record TypeData(Kind kind, @Nullable String mesh, boolean overrideMaterial, @Nullable Ribbon ribbon,
			@Nullable Beam beam) {
		public static final TypeData SPRITE = new TypeData(Kind.SPRITE, null, false, null, null);
	}

	public record Ribbon(int maxTrails, int maxPointsPerTrail, boolean worldUp, float tilingDistance,
			boolean clipSourceSegment) {
	}

	public record Beam(int maxBeams) {
	}
}
