package dev.enderskills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.GenerationStep;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import dev.enderskills.data.EsSettings;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;
import dev.enderskills.logic.AttributeEffects;
import dev.enderskills.logic.Progression;
import dev.enderskills.network.EsNetwork;
import dev.enderskills.registry.ModContent;

public final class EnderSkills implements ModInitializer {
	public static final String MOD_ID = "enderskills";
	public static final Logger LOGGER = LoggerFactory.getLogger("Ender Skills");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		EsSettings.load();
		SkillConfigs.load();
		ModContent.init();
		dev.enderskills.registry.ModEffects.init();
		Progression.init();
		EsNetwork.init();

		ServerPlayerEvents.JOIN.register(AttributeEffects::apply);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> AttributeEffects.apply(newPlayer));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Progression.forget(handler.getPlayer());
			dev.enderskills.logic.AbilityManager.forget(handler.getPlayer());
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(srv -> dev.enderskills.logic.Vfx.quietly(() -> dev.enderskills.logic.AbilityManager.tick(srv)));
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				dev.enderskills.logic.DefenseAbilities.allowDamage(entity, source) && dev.enderskills.logic.HurtTimer.allow(entity, source, amount));
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
			dev.enderskills.logic.HurtTimer.after(victim);
			dev.enderskills.logic.DefenseAbilities.afterDamage(victim, source, taken);
			if (source.getEntity() instanceof ServerPlayer attacker && source.getDirectEntity() == attacker && victim != attacker
					&& !dev.enderskills.logic.AbilityManager.skillDamage && taken > 0) {
				dev.enderskills.logic.Vfx.quietly(() -> dev.enderskills.logic.OffenseAbilities.onMelee(attacker, victim, taken));
			}
			if (victim instanceof ServerPlayer player && taken > 0) {
				dev.enderskills.logic.AbilityManager.onHurt(player);
			}
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
				dev.enderskills.logic.AbilityManager.onDeath(entity));

		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
				dev.enderskills.logic.EarthStructures.onBreak(world, pos));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				dev.enderskills.logic.EarthStructures.restoreAll());

		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.UNDERGROUND_ORES,
				ResourceKey.create(Registries.PLACED_FEATURE, id("ender_ore")));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dev.enderskills.command.EsCommands.register(dispatcher));
		LOGGER.info("Ender Skills loaded");
	}
}
