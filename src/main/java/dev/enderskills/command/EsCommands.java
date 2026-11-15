package dev.enderskills.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import dev.enderskills.data.EsSettings;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;
import dev.enderskills.logic.AbilityManager;
import dev.enderskills.logic.Progression;
import dev.enderskills.skill.Skills;

/**
 * /enderskills ability|attribute add|remove|level ...  (abilities = active/passive skills of the trees,
 * attributes = permanent stats like speed and resistances), plus enderlevel, reset, refill, list, reload.
 */
public final class EsCommands {
	private static final SimpleCommandExceptionType UNKNOWN = new SimpleCommandExceptionType(Component.translatable("enderskills.command.unknown"));
	private static final SimpleCommandExceptionType BAD_LEVEL = new SimpleCommandExceptionType(Component.translatable("enderskills.command.bad_level"));

	private EsCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("enderskills")
				.requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
				.then(kind("ability", Skills.Kind.ABILITY))
				.then(kind("attribute", Skills.Kind.ATTRIBUTE))
				.then(Commands.literal("enderlevel").then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("level", IntegerArgumentType.integer(0, 1000)).executes(c -> {
							int level = IntegerArgumentType.getInteger(c, "level");
							for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) {
								Progression.set(p, Progression.data(p).withLevel(level, 0.0));
							}
							return ok(c, "enderskills.command.level", level);
						}))))
				.then(Commands.literal("reset").then(Commands.argument("players", EntityArgument.players()).executes(c -> {
					for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) {
						SkillData data = Progression.data(p);
						Progression.set(p, new SkillData(Map.of(), List.of(), data.enderLevel(), data.progress(),
								data.storedXp() + data.xpSpent(), 0, 0));
					}
					return ok(c, "enderskills.command.reset");
				})))
				.then(Commands.literal("refill").then(Commands.argument("players", EntityArgument.players()).executes(c -> {
					for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) {
						AbilityManager.refill(p);
					}
					return ok(c, "enderskills.command.refill");
				})))
				.then(Commands.literal("list").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
					ServerPlayer p = EntityArgument.getPlayer(c, "player");
					SkillData data = Progression.data(p);
					StringBuilder b = new StringBuilder();
					data.skills().forEach((k, v) -> b.append(k).append(' ').append(v).append(", "));
					c.getSource().sendSuccess(() -> Component.translatable("enderskills.command.list", p.getDisplayName(),
							data.enderLevel(), b.isEmpty() ? "-" : b.substring(0, b.length() - 2)), false);
					return 1;
				})))
				.then(Commands.literal("reload").executes(c -> {
					EsSettings.load();
					SkillConfigs.load();
					return ok(c, "enderskills.command.reloaded");
				})));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> kind(String name, Skills.Kind kind) {
		return Commands.literal(name)
				.then(Commands.literal("add").then(Commands.argument("players", EntityArgument.players())
						.then(skillArg(kind)
								.executes(c -> set(c, kind, "0", false))
								.then(Commands.literal("level").then(Commands.argument("level", StringArgumentType.word())
										.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("max", "0", "10", "25", "50"), b))
										.executes(c -> set(c, kind, StringArgumentType.getString(c, "level"), false)))))))
				.then(Commands.literal("level").then(Commands.argument("players", EntityArgument.players())
						.then(skillArg(kind).then(Commands.argument("level", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("max", "0", "10", "25", "50"), b))
								.executes(c -> set(c, kind, StringArgumentType.getString(c, "level"), false))))))
				.then(Commands.literal("remove").then(Commands.argument("players", EntityArgument.players())
						.then(skillArg(kind).executes(c -> set(c, kind, "0", true)))));
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> skillArg(Skills.Kind kind) {
		return Commands.argument("skill", StringArgumentType.word()).suggests((c, b) -> {
			List<String> names = new ArrayList<>();
			names.add("all");
			Skills.all().stream().filter(s -> s.kind() == kind).forEach(s -> names.add(s.id()));
			return SharedSuggestionProvider.suggest(names, b);
		});
	}

	private static int set(CommandContext<CommandSourceStack> c, Skills.Kind kind, String levelText, boolean remove)
			throws CommandSyntaxException {
		String name = StringArgumentType.getString(c, "skill");
		List<String> targets = new ArrayList<>();
		if (name.equals("all")) {
			Skills.all().stream().filter(s -> s.kind() == kind).forEach(s -> targets.add(s.id()));
		} else {
			Skills.Skill s = Skills.get(name);
			if (s == null || s.kind() != kind) {
				throw UNKNOWN.create();
			}
			targets.add(name);
		}
		boolean max = levelText.equalsIgnoreCase("max");
		int level;
		try {
			level = max ? 0 : Integer.parseInt(levelText);
		} catch (NumberFormatException e) {
			throw BAD_LEVEL.create();
		}
		Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
		for (ServerPlayer p : players) {
			SkillData data = Progression.data(p);
			for (String id : targets) {
				if (remove) {
					data = data.withoutSkill(id);
				} else {
					int top = Math.min(SkillConfigs.maxLevel(id), 100);
					data = data.withSkill(id, Math.max(0, Math.min(max ? top : level, top)), kind == Skills.Kind.ABILITY);
				}
			}
			Progression.set(p, data);
		}
		return ok(c, remove ? "enderskills.command.removed" : "enderskills.command.set", targets.size(), max ? "max" : levelText);
	}

	private static int ok(CommandContext<CommandSourceStack> c, String key, Object... args) {
		c.getSource().sendSuccess(() -> Component.translatable(key, args), true);
		return 1;
	}
}
