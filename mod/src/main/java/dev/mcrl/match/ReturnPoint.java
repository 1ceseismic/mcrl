package dev.mcrl.match;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.Mcrl;

public record ReturnPoint(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot, GameType gameMode,
		Optional<UUID> car) {
	public static final Codec<ReturnPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
			ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ReturnPoint::dimension),
			Vec3.CODEC.fieldOf("pos").forGetter(ReturnPoint::pos),
			Codec.FLOAT.fieldOf("yRot").forGetter(ReturnPoint::yRot),
			Codec.FLOAT.fieldOf("xRot").forGetter(ReturnPoint::xRot),
			GameType.CODEC.fieldOf("gameMode").forGetter(ReturnPoint::gameMode),
			UUIDUtil.CODEC.optionalFieldOf("car").forGetter(ReturnPoint::car)).apply(i, ReturnPoint::new));

	public static final AttachmentType<ReturnPoint> ATTACHMENT = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "return_point"), b -> b.persistent(CODEC).copyOnDeath());

	public static void register() {
	}
}
