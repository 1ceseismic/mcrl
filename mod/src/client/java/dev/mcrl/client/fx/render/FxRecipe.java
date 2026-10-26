package dev.mcrl.client.fx.render;

import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.Materials;

public enum FxRecipe {
	UNTEXTURED(List.of()),
	HIDDEN(List.of("Explosion_ShockWave_Mat")),
	SMOKE_PUFF(List.of("SmokePuff_Mat"), "Smoke01_D", "Sphere_Gradient01_D", "Radial_Generic_Tiling_Pack"),
	FLAME03(List.of("Flame_03_Trans_MAT"), "Noise_Fire_01_N", "SmokeMask_02_T", "FX_Water_02.Tex.Water_01_Pack"),
	BOOST_CONE(List.of("BoostCone_Flame_Mat"), "Noise_Fire_02_Pack", "Water_01_Pack"),
	BOOST_SHEET(List.of("BoostMesh_Paintable_MAT"), "Water_02_N", "ParticleSheet_T"),
	DUSTY_FLARE(List.of("DustyFlare_Flicker_MIC"), "Noise_Weird_01_Pack", "Radial_Generic_Tiling_Pack"),
	STANDARD_FLARE(List.of("StandardFlare_Mat"), "param:Texture"),
	STREAKS(List.of("SupersonicStreaks_Mat"), "Radial_Generic_01_Pack"),
	WHEEL_TRAIL(List.of("Wheel_Trail_Mat"), "Noise_Fire_02_Pack"),
	GLOW(List.of("Car_FX.Materials.Glow_Mat", "Glow01_Mat"), "Radial_Generic_01_Pack"),
	DISTORTION_SPHERE(List.of("DistortionSphere_Mat"), "Radial_Generic_01_Pack"),
	FLIPRESET_CIRCLE(List.of("M_FlipReset_Circle"), "GradientRing_T"),
	FLIPRESET_GRADIENT(List.of("M_FlipReset_Gradient")),
	FLIPRESET_MESH(List.of("M_FlipReset_Mesh")),
	SPARKS_ASH(List.of("Boost_Tests.Materials.Sparks_MAT"), "Ash_T"),
	SPARK(List.of("Spark_Mat")),
	GLOW_TRANSLUCENT(List.of("Glow_Translucent_Mat"), "Radial_Generic_01_Pack"),
	SMOKE_PUFF01(List.of("Smoke_Puff_01_Mat"), "Noise_Smoke_02_Pack"),
	GLOW01_MIC(List.of("Glow01_MIC"), "param:Emmisive"),
	DODGE_RIBBON(List.of("DodgeRibbon_Mat")),
	DIRT_PUFF(List.of("Dirt_Puff_01_MIC"), "Noise_Smoke_03_Pack", "Noise_Smoke_01_Pack"),
	CLOUDS02(List.of("Clouds02_Mat"), "Clouds02_D"),
	SPRAY_DIRT(List.of("Spray_Dirt_01_Mat"), "Noise_Smoke_03_Pack", "Noise_Smoke_01_Pack"),
	GRASS_BLADES(List.of("GrassBlades_Mat"), "GrassBlades_T"),
	SAND_LIT(List.of("SandLit_test_MAT"), "Smoke_HQ", "Noise_Generic_01_Pack"),
	SNOW_SMOKE(List.of("SnowSmoke_MAT"), "Clouds_NoMips_T", "FlameCloud_01"),
	WATER_SPLASH(List.of("WaterSplash_MAT"), "PH_Water_01_N", "Splash01"),
	SPLASH(List.of("Splash_MAT"), "Splash01"),
	WATER_SPLASH01(List.of("Water_Splash_01_Mat"), "Waterfall_Stream_01"),
	TEAM_GLOW(List.of("TeamGlow_Mat"), "Radial_Generic_01_Pack"),
	SPEED_TRAIL(List.of("SpeedTrail_Mat")),
	LOCATION_BEAM(List.of("Ball_LocationBeam01_Mat")),
	RETICLE(List.of("Reticle_Mat")),
	CLARITY_SPHERE(List.of("BallClaritySphere_Mat")),
	EXPLOSION(List.of("Explosion_MAT"), "param:Texture"),
	FLASH_GRADIENT(List.of("FlashGradient_MAT"));

	final List<String> materials;
	final String[] textures;

	FxRecipe(List<String> materials, String... textures) {
		this.materials = materials;
		this.textures = textures;
	}

	public String define() {
		return "FX_R_" + name();
	}

	public String[] textures() {
		return textures.clone();
	}

	public static FxRecipe of(@Nullable Materials materials, @Nullable String path) {
		if (path == null || path.isEmpty()) {
			return UNTEXTURED;
		}
		List<MaterialDef> chain = materials == null ? List.of() : materials.chain(path);
		if (chain.isEmpty()) {
			return match(path.substring(path.indexOf(':') + 1));
		}
		for (MaterialDef d : chain) {
			FxRecipe r = match(d.path().substring(d.path().indexOf(':') + 1));
			if (r != UNTEXTURED) {
				return r;
			}
		}
		return UNTEXTURED;
	}

	private static FxRecipe match(String objectPath) {
		String last = objectPath.substring(objectPath.lastIndexOf('.') + 1);
		for (FxRecipe r : values()) {
			for (String m : r.materials) {
				if (m.indexOf('.') < 0 ? m.equals(last) : objectPath.equals(m) || objectPath.endsWith("." + m)) {
					return r;
				}
			}
		}
		return UNTEXTURED;
	}
}
