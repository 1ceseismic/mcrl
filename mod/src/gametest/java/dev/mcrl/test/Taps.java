package dev.mcrl.test;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import dev.mcrl.net.McrlPayloads.FxEventS2C;

public final class Taps {
	public static volatile @Nullable Consumer<FxEventS2C> fxEvents;
	public static volatile @Nullable BiConsumer<ServerPlayer, CustomPacketPayload> matchPayloads;
	public static volatile int carScans;

	private Taps() {
	}
}
