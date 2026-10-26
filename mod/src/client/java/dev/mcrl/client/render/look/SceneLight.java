package dev.mcrl.client.render.look;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.MapColor;

public final class SceneLight {
	static final int GROUND_SCAN = 16;

	private SceneLight() {
	}

	public static Vector3f sunDirection(float angle) {
		return new Vector3f((float) -Math.sin(angle), (float) Math.cos(angle), 0);
	}

	public static Vector3f lightDirection(float sunAngle, float moonAngle, boolean overworldSky) {
		if (!overworldSky) {
			return new Vector3f(0, 1, 0);
		}
		Vector3f sun = sunDirection(sunAngle);
		return sun.y > 0 ? sun : sunDirection(moonAngle);
	}

	public static SceneValues values(SkyRenderState sky, Vector3fc fogColor, double simSeconds) {
		boolean overworldSky = sky.skybox == DimensionType.Skybox.OVERWORLD;
		Vector3fc c = overworldSky && sky.skyColor != null ? sky.skyColor : fogColor;
		Vector3f cubeSky = new Vector3f((float) RlDisplay.srgbDecode(c.x()), (float) RlDisplay.srgbDecode(c.y()),
				(float) RlDisplay.srgbDecode(c.z()));
		double t8 = 8 * simSeconds;
		return new SceneValues(lightDirection(sky.sunAngle, sky.moonAngle, overworldSky), vec(ArenaLight.SUN_COLOR),
				vec(ArenaLight.SKY_UPPER), vec(ArenaLight.SKY_LOWER), ArenaLight.LOWER_IS_GROUND,
				vec(ArenaLight.AMBIENT), ArenaLight.SKY_FACTOR, cubeSky, (float) (simSeconds - Math.floor(simSeconds)),
				(float) (t8 - Math.floor(t8)));
	}

	public static @Nullable Vector3f groundColor(BlockGetter level, double x, double y, double z) {
		BlockPos.MutableBlockPos p = BlockPos.containing(x, y, z).mutable();
		for (int i = 0; i < GROUND_SCAN; i++, p.move(Direction.DOWN)) {
			BlockState state = level.getBlockState(p);
			if (state.isAir()) {
				continue;
			}
			MapColor map = state.getMapColor(level, p);
			if (map != MapColor.NONE) {
				int col = map.col;
				return new Vector3f(decodeByte(col >> 16), decodeByte(col >> 8), decodeByte(col));
			}
		}
		return null;
	}

	private static float decodeByte(int c) {
		return (float) RlDisplay.srgbDecode((c & 0xFF) / 255.0);
	}

	private static Vector3f vec(float[] v) {
		return new Vector3f(v[0], v[1], v[2]);
	}
}
