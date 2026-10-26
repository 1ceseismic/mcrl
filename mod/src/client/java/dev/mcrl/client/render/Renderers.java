package dev.mcrl.client.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import dev.mcrl.Mcrl;
import dev.mcrl.client.fx.render.FxFeatureRenderer;
import dev.mcrl.client.fx.render.FxPipelines;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.render.look.LookDraws;
import dev.mcrl.client.render.look.RlLookAssets;
import dev.mcrl.client.render.look.RlMeshFeatureRenderer;
import dev.mcrl.client.render.stadium.StadiumRenderer;
import dev.mcrl.client.rl.RlTextures;
import dev.mcrl.entity.McrlEntities;

public final class Renderers {
	private static final Identifier LOOK_RELOAD = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "rl_look");
	private static final Identifier FX_RELOAD = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "rl_fx");

	private Renderers() {
	}

	public static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(MeshEmitter::registerTextures);
		EntityRendererRegistry.register(McrlEntities.CAR, CarRenderer::new);
		EntityRendererRegistry.register(McrlEntities.BALL, BallRenderer::new);
		FeatureRendererRegistry.register(RlMeshFeatureRenderer.TYPE, RlMeshFeatureRenderer::new);
		FeatureRendererRegistry.register(FxFeatureRenderer.TYPE, FxFeatureRenderer::new);
		FeatureRendererRegistry.register(StadiumRenderer.TYPE, StadiumRenderer::new);
		StadiumRenderer.register();
		FrameEvents.register((mc, frame, frameNanos) -> {
			RlLookAssets.endFrame();
			LookDraws.frame();
			RlTextures.frame();
		});
		ResourceLoader client = ResourceLoader.get(PackType.CLIENT_RESOURCES);
		client.registerReloadListener(LOOK_RELOAD, (ResourceManagerReloadListener) rm -> RlLookAssets.resourcesReloaded());
		client.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, LOOK_RELOAD);
		client.registerReloadListener(FX_RELOAD, (ResourceManagerReloadListener) rm -> FxPipelines.precompile());
		client.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, FX_RELOAD);
	}
}
