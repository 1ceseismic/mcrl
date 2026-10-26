package dev.mcrl.client.fx;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class SurfaceMap {
	private static final double PROBE = 0.01;
	private static final Map<Block, RlSurface> BLOCKS = new IdentityHashMap<>();
	private static final Map<SoundType, RlSurface> SOUNDS = new IdentityHashMap<>();

	static {
		blocks(RlSurface.GRASS, Blocks.GRASS_BLOCK);
		blocks(RlSurface.DIRT, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.FARMLAND,
				Blocks.PODZOL);
		blocks(RlSurface.SAND, Blocks.SAND, Blocks.SUSPICIOUS_SAND);
		blocks(RlSurface.SAND_02, Blocks.RED_SAND);
		blocks(RlSurface.SNOW, Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW);
		blocks(RlSurface.ICE, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.FROSTED_ICE);
		blocks(RlSurface.MUD, Blocks.MUD);
		blocks(RlSurface.FENCE, Blocks.IRON_BARS);
		blocks(RlSurface.FORCE_FIELD, Blocks.BARRIER);

		sounds(RlSurface.GRASS, SoundType.GRASS, SoundType.WET_GRASS, SoundType.MOSS, SoundType.MOSS_CARPET,
				SoundType.AZALEA, SoundType.NYLIUM, SoundType.LILY_PAD, SoundType.WOOL,
				SoundType.CROP, SoundType.HARD_CROP, SoundType.SWEET_BERRY_BUSH, SoundType.NETHER_WART, SoundType.STEM,
				SoundType.VINE, SoundType.CAVE_VINES, SoundType.WEEPING_VINES, SoundType.TWISTING_VINES,
				SoundType.GLOW_LICHEN, SoundType.FUNGUS, SoundType.ROOTS, SoundType.NETHER_SPROUTS,
				SoundType.HANGING_ROOTS, SoundType.SPORE_BLOSSOM, SoundType.FLOWERING_AZALEA, SoundType.AZALEA_LEAVES,
				SoundType.CHERRY_LEAVES, SoundType.POPLAR_LEAVES, SoundType.PINK_PETALS, SoundType.LEAF_LITTER,
				SoundType.BIG_DRIPLEAF, SoundType.SMALL_DRIPLEAF, SoundType.BAMBOO_SAPLING, SoundType.CHERRY_SAPLING,
				SoundType.CACTUS_FLOWER, SoundType.RED_SHRUB, SoundType.SHELF_MUSHROOM);
		sounds(RlSurface.DIRT, SoundType.GRAVEL, SoundType.ROOTED_DIRT, SoundType.SOUL_SAND, SoundType.SOUL_SOIL,
				SoundType.SUSPICIOUS_GRAVEL);
		sounds(RlSurface.SAND, SoundType.SAND, SoundType.SUSPICIOUS_SAND);
		sounds(RlSurface.WOOD, SoundType.WOOD, SoundType.CHERRY_WOOD, SoundType.NETHER_WOOD, SoundType.BAMBOO_WOOD,
				SoundType.LADDER, SoundType.SCAFFOLDING, SoundType.BAMBOO);
		sounds(RlSurface.WOOD, SoundType.HANGING_SIGN, SoundType.CHERRY_WOOD_HANGING_SIGN,
				SoundType.NETHER_WOOD_HANGING_SIGN, SoundType.BAMBOO_WOOD_HANGING_SIGN, SoundType.SHELF,
				SoundType.CHISELED_BOOKSHELF, SoundType.MANGROVE_ROOTS, SoundType.CREAKING_HEART);
		sounds(RlSurface.METAL, SoundType.METAL, SoundType.IRON, SoundType.ANVIL, SoundType.NETHERITE_BLOCK,
				SoundType.COPPER, SoundType.COPPER_BULB, SoundType.COPPER_GRATE, SoundType.COPPER_GOLEM_STATUE,
				SoundType.CHAIN, SoundType.LODESTONE, SoundType.HEAVY_CORE);
		sounds(RlSurface.GLASS, SoundType.GLASS, SoundType.AMETHYST, SoundType.AMETHYST_CLUSTER,
				SoundType.SMALL_AMETHYST_BUD, SoundType.MEDIUM_AMETHYST_BUD, SoundType.LARGE_AMETHYST_BUD);
		sounds(RlSurface.SNOW, SoundType.SNOW, SoundType.POWDER_SNOW);
		sounds(RlSurface.MUD, SoundType.MUD, SoundType.PACKED_MUD, SoundType.SLIME_BLOCK, SoundType.HONEY_BLOCK);
	}

	private SurfaceMap() {
	}

	private static void blocks(RlSurface s, Block... bs) {
		for (Block b : bs) {
			BLOCKS.put(b, s);
		}
	}

	private static void sounds(RlSurface s, SoundType... ts) {
		for (SoundType t : ts) {
			SOUNDS.put(t, s);
		}
	}

	public static RlSurface of(BlockState state) {
		RlSurface s = BLOCKS.get(state.getBlock());
		if (s == null) {
			s = SOUNDS.get(state.getSoundType());
		}
		return s != null ? s : RlSurface.ROCK;
	}

	public static RlSurface at(Level level, Vec3 point, Vec3 normal) {
		BlockPos ground = BlockPos.containing(point.x - PROBE * normal.x, point.y - PROBE * normal.y,
				point.z - PROBE * normal.z);
		BlockPos above = BlockPos.containing(point.x + PROBE * normal.x, point.y + PROBE * normal.y,
				point.z + PROBE * normal.z);
		if (level.getFluidState(above).is(FluidTags.WATER) || level.getFluidState(ground).is(FluidTags.WATER)) {
			return RlSurface.WATER;
		}
		RlSurface dry = of(level.getBlockState(ground));
		RlSurface wet = switch (dry) {
			case GRASS -> RlSurface.WET_GRASS;
			case METAL -> RlSurface.WET_METAL;
			case ROCK -> RlSurface.WET_PAVEMENT;
			default -> dry;
		};
		return wet != dry && level.isRainingAt(above) ? wet : dry;
	}
}
