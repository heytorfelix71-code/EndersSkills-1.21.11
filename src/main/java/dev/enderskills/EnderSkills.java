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
		Progression.init();
		EsNetwork.init();

		ServerPlayerEvents.JOIN.register(AttributeEffects::apply);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> AttributeEffects.apply(newPlayer));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Progression.forget(handler.getPlayer()));

		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.UNDERGROUND_ORES,
				ResourceKey.create(Registries.PLACED_FEATURE, id("ender_ore")));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("enderskills")
						.requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.literal("level")
								.then(Commands.argument("level", IntegerArgumentType.integer(0, 1000))
										.executes(c -> setLevel(c, c.getSource().getPlayerOrException()))
										.then(Commands.argument("player", EntityArgument.player())
												.executes(c -> setLevel(c, EntityArgument.getPlayer(c, "player"))))))
						.then(Commands.literal("reset")
								.executes(c -> reset(c, c.getSource().getPlayerOrException()))
								.then(Commands.argument("player", EntityArgument.player())
										.executes(c -> reset(c, EntityArgument.getPlayer(c, "player")))))
						.then(Commands.literal("reload").executes(c -> {
							EsSettings.load();
							SkillConfigs.load();
							c.getSource().sendSuccess(() -> Component.translatable("enderskills.command.reloaded"), true);
							return 1;
						}))));
		LOGGER.info("Ender Skills loaded");
	}

	private static int setLevel(CommandContext<CommandSourceStack> c, ServerPlayer player) {
		int level = IntegerArgumentType.getInteger(c, "level");
		Progression.set(player, Progression.data(player).withLevel(level, 0.0));
		c.getSource().sendSuccess(() -> Component.translatable("enderskills.command.level", player.getDisplayName(), level), true);
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> c, ServerPlayer player) throws CommandSyntaxException {
		SkillData data = Progression.data(player);
		Progression.set(player, new SkillData(java.util.Map.of(), java.util.List.of(), data.enderLevel(), data.progress(),
				data.storedXp() + data.xpSpent(), 0, 0));
		c.getSource().sendSuccess(() -> Component.translatable("enderskills.command.reset", player.getDisplayName()), true);
		return 1;
	}
}
