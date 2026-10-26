package dev.mcrl.match;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.Mcrl;

public final class StadiumConst {
	public static final ResourceKey<Level> KEY =
			ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "stadium"));
	public static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
	public static final int MIN_Y = 0;
	public static final int FIELD_CHUNK_MIN_X = -6, FIELD_CHUNK_MAX_X = 5, FIELD_CHUNK_MIN_Z = -8,
			FIELD_CHUNK_MAX_Z = 7;
	public static final int VIEW_CHUNKS = 20, VIEW_BLOCKS = 320;
	public static final Vec3 SPECTATOR_POS = new Vec3(0, 94, -135);
	public static final float SPECTATOR_YAW = 0f, SPECTATOR_PITCH = 15f;

	private StadiumConst() {
	}
}
