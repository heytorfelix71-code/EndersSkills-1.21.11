package dev.enderskills.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap;

import dev.enderskills.network.EsNetwork;
import dev.enderskills.registry.ModContent;

public final class EnderSkillsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(EsNetwork.OpenAltar.TYPE, (payload, context) ->
				Minecraft.getInstance().setScreen(new AltarScreen(payload)));
		ClientAbilities.init();
		ClientPlayNetworking.registerGlobalReceiver(EsNetwork.PortalList.TYPE, (payload, context) -> PortalScreen.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(EsNetwork.VfxShape.TYPE, (payload, context) -> ShapeRenderer.add(payload));
		ClientPlayNetworking.registerGlobalReceiver(EsNetwork.SkillFx.TYPE, (payload, context) -> dev.enderskills.client.fx.OriginalFx.add(payload));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(dev.enderskills.client.fx.OriginalFx::tick);
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(dev.enderskills.client.fx.OriginalFx::render);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> dev.enderskills.client.fx.OriginalFx.reset());
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(ShapeRenderer::tick);
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(ShapeRenderer::render);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ShapeRenderer.reset());
		ClientPlayNetworking.registerGlobalReceiver(EsNetwork.AbilitySync.TYPE, (payload, context) -> ClientAbilities.apply(payload));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientAbilities.reset());
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(ClientAbilities::tick);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT, dev.enderskills.EnderSkills.id("abilities"),
				ClientAbilities::render);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.MISC_OVERLAYS, dev.enderskills.EnderSkills.id("status_overlay"),
				ClientAbilities::overlay);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT, dev.enderskills.EnderSkills.id("group_label"),
				ClientAbilities::groupLabel);
		BlockRenderLayerMap.putBlock(ModContent.CRYSTAL, ChunkSectionLayer.TRANSLUCENT);
		BlockRenderLayerMap.putBlock(ModContent.BLACK_FIRE, ChunkSectionLayer.CUTOUT);
		BlockRenderLayerMap.putBlock(ModContent.BLOOD, ChunkSectionLayer.TRANSLUCENT);
		BlockRenderLayerMap.putBlocks(ChunkSectionLayer.CUTOUT, ModContent.OAK_ALTAR, ModContent.SPRUCE_ALTAR, ModContent.BIRCH_ALTAR,
				ModContent.JUNGLE_ALTAR, ModContent.ACACIA_ALTAR, ModContent.DARK_OAK_ALTAR, ModContent.ULTIMATE_ALTAR);
	}
}
