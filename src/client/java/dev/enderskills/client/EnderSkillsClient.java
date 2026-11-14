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
		BlockRenderLayerMap.putBlock(ModContent.CRYSTAL, ChunkSectionLayer.TRANSLUCENT);
		BlockRenderLayerMap.putBlocks(ChunkSectionLayer.CUTOUT, ModContent.OAK_ALTAR, ModContent.SPRUCE_ALTAR, ModContent.BIRCH_ALTAR,
				ModContent.JUNGLE_ALTAR, ModContent.ACACIA_ALTAR, ModContent.DARK_OAK_ALTAR, ModContent.ULTIMATE_ALTAR);
	}
}
