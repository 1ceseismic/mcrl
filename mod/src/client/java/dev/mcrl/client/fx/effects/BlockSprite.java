package dev.mcrl.client.fx.effects;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.render.FxSkin;

public record BlockSprite(TextureAtlasSprite sprite, int argb) {
	// TerrainParticle's colour before the tint.
	public static final float SHADE = 0.6f;
	private static final double INTO_SURFACE = 0.01;

	public static BlockSprite of(BlockState state, BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		TextureAtlasSprite sprite = mc.getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
		BlockAndTintGetter level = mc.level == null ? BlockAndTintGetter.EMPTY : mc.level;
		return new BlockSprite(sprite, color(mc.getBlockColors().getTintSource(state, 0), state, level, pos));
	}

	public static int color(@Nullable BlockTintSource tint, BlockState state, BlockAndTintGetter level, BlockPos pos) {
		float r = SHADE, g = SHADE, b = SHADE;
		if (tint != null) {
			int c = tint.colorAsTerrainParticle(state, level, pos);
			r *= (c >> 16 & 0xFF) / 255f;
			g *= (c >> 8 & 0xFF) / 255f;
			b *= (c & 0xFF) / 255f;
		}
		return ARGB.colorFromFloat(1, r, g, b);
	}

	public static BlockPos under(Vec3 point, Vec3 normal) {
		return BlockPos.containing(point.x - normal.x * INTO_SURFACE, point.y - normal.y * INTO_SURFACE,
				point.z - normal.z * INTO_SURFACE);
	}

	public FxSkin skin() {
		return new FxSkin(sprite.atlasLocation(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
				argb & 0xFFFFFF);
	}
}
