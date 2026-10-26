package dev.mcrl.client.fx.particle;

import java.util.List;

import dev.mcrl.rlassets.model.Distribution;

public sealed interface FxModule {
	int POS_X = 1, NEG_X = 2, POS_Y = 4, NEG_Y = 8, POS_Z = 16, NEG_Z = 32, ALL_AXES = 63;

	String className();

	record Lifetime(Distribution lifeTime) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleLifetime";
		}
	}

	record Size(Distribution startSize) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleSize";
		}
	}

	record SizeMultiplyLife(Distribution multiplier, boolean x, boolean y, boolean z) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleSizeMultiplyLife";
		}
	}

	record SizeScale(Distribution scale, boolean x, boolean y, boolean z) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleSizeScale";
		}
	}

	record Color(Distribution color, Distribution alpha, boolean clampAlpha) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleColor";
		}
	}

	record ColorOverLife(Distribution color, Distribution alpha, boolean clampAlpha) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleColorOverLife";
		}
	}

	record ColorScaleOverLife(Distribution color, Distribution alpha, boolean emitterTime) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleColorScaleOverLife";
		}
	}

	record Velocity(Distribution start, Distribution radial, boolean world) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleVelocity";
		}
	}

	record VelocityOverLifetime(Distribution scale, boolean absolute, boolean world) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleVelocityOverLifetime";
		}
	}

	record VelocityInheritParent(Distribution scale, boolean world, float maxAdded) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleVelocityInheritParent";
		}
	}

	record Acceleration(Distribution acceleration, boolean world) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleAcceleration";
		}
	}

	record AccelerationOverLifetime(Distribution acceleration, boolean world) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleAccelerationOverLifetime";
		}
	}

	record Rotation(Distribution start) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleRotation";
		}
	}

	record RotationRate(Distribution start) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleRotationRate";
		}
	}

	record MeshRotation(Distribution start) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleMeshRotation";
		}
	}

	record MeshRotationRate(Distribution start) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleMeshRotationRate";
		}
	}

	record Location(Distribution start) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleLocation";
		}
	}

	record LocationWorldOffset(Distribution start) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleLocationWorldOffset";
		}
	}

	record LocationSphere(Distribution center, Distribution radius, boolean surfaceOnly, boolean velocity,
			Distribution velocityScale, int axes) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleLocationPrimitiveSphere";
		}
	}

	record LocationCylinder(Distribution center, Distribution radius, Distribution height, int heightAxis,
			boolean surfaceOnly, boolean velocity, boolean radialVelocity, Distribution velocityScale, int axes)
			implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleLocationPrimitiveCylinder";
		}
	}

	record LocationEmitter(String emitterName, boolean inheritVelocity, float velocityScale) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleLocationEmitter";
		}
	}

	record SourceMovement(Distribution scale) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleSourceMovement";
		}
	}

	record OrbitOptions(boolean spawn, boolean update, boolean emitterTime) {
	}

	record Orbit(Distribution offset, OrbitOptions offsetOptions, Distribution rotation, OrbitOptions rotationOptions,
			Distribution rate, OrbitOptions rateOptions) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleOrbit";
		}
	}

	record SubUV(Distribution index) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleSubUV";
		}
	}

	record DynParam(String name, Distribution value, boolean emitterTime, boolean spawnOnly) {
	}

	record ParameterDynamic(List<DynParam> params) implements FxModule {
		public ParameterDynamic {
			params = List.copyOf(params);
		}

		@Override
		public String className() {
			return "ParticleModuleParameterDynamic";
		}
	}

	record CameraOffset(Distribution offset) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleCameraOffset";
		}
	}

	enum Completion { KILL, FREEZE, HALT }

	record Collision(Distribution damping, Distribution maxCollisions, Completion completion, Distribution delay)
			implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleCollision";
		}
	}

	enum Lock { NONE, X, Y, Z, NEG_X, NEG_Y, NEG_Z, ROTATE_X, ROTATE_Y, ROTATE_Z }

	record AxisLock(Lock lock) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleOrientationAxisLock";
		}
	}

	record MaterialByParameter(List<String> defaultMaterials) implements FxModule {
		public MaterialByParameter {
			defaultMaterials = List.copyOf(defaultMaterials);
		}

		@Override
		public String className() {
			return "ParticleModuleMaterialByParameter";
		}
	}

	record TrailSource(float[] offsets) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleTrailSource";
		}

		public int count() {
			return offsets.length / 3;
		}
	}

	record BeamSource(Distribution source) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleBeamSource";
		}
	}

	record BeamTarget(Distribution target) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleBeamTarget";
		}
	}

	record BeamNoise(Distribution range, Distribution rangeScale, boolean rangeScaleEmitterTime, int frequency,
			float lockTime) implements FxModule {
		@Override
		public String className() {
			return "ParticleModuleBeamNoise";
		}
	}

	record Other(String className) implements FxModule {
	}
}
