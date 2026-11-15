package dev.enderskills.client.config;

import java.awt.Color;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.enderskills.data.EsSettings;
import dev.enderskills.skill.Skills;

/** Ender Skills settings screen (YACL), grouped like the altar: power by class and tree, portal, HUD, rules. */
public final class EsConfigScreen {
	private EsConfigScreen() {
	}

	private static Component t(String key) {
		return Component.translatable("enderskills.config." + key);
	}

	private static OptionDescription d(String key) {
		return OptionDescription.of(Component.translatable("enderskills.config." + key + ".desc"));
	}

	public static Screen create(Screen parent) {
		EsSettings c = EsSettings.get();
		EsSettings def = new EsSettings();

		ConfigCategory.Builder power = ConfigCategory.createBuilder().name(t("category.power"))
				.group(OptionGroup.createBuilder().name(t("group.global"))
						.option(num("ability_damage_multiplier", def.abilityDamageMultiplier, 0, 1_000_000, () -> c.abilityDamageMultiplier, v -> c.abilityDamageMultiplier = v))
						.option(num("max_level_overdrive", def.maxLevelOverdrive, 0, 1_000_000, () -> c.maxLevelOverdrive, v -> c.maxLevelOverdrive = v))
						.option(num("overdrive_curve", def.overdriveCurve, 0.1, 10, () -> c.overdriveCurve, v -> c.overdriveCurve = v))
						.option(num("player_damage_multiplier", def.playerDamageMultiplier, 0, 100, () -> c.playerDamageMultiplier, v -> c.playerDamageMultiplier = v))
						.option(num("boss_damage_multiplier", def.bossDamageMultiplier, 0, 100, () -> c.bossDamageMultiplier, v -> c.bossDamageMultiplier = v))
						.option(num("cooldown_multiplier", def.cooldownMultiplier, 0, 1000, () -> c.cooldownMultiplier, v -> c.cooldownMultiplier = v))
						.option(num("endurance_multiplier", def.enduranceCostMultiplier, 0, 1000, () -> c.enduranceCostMultiplier, v -> c.enduranceCostMultiplier = v))
						.option(num("duration_multiplier", def.durationMultiplier, 0, 1000, () -> c.durationMultiplier, v -> c.durationMultiplier = v))
						.option(num("range_multiplier", def.rangeMultiplier, 0, 1000, () -> c.rangeMultiplier, v -> c.rangeMultiplier = v))
						.build());
		for (Skills.Tab tab : Skills.Tab.values()) {
			OptionGroup.Builder group = OptionGroup.createBuilder()
					.name(Component.translatable("enderskills.tab." + tab.id).withColor(tab.color));
			for (Skills.Page page : Skills.pages(tab)) {
				String key = tab.id + "." + page.element();
				group.option(Option.<Double>createBuilder()
						.name(Component.translatable("enderskills.page." + page.element()))
						.description(d("tree_power"))
						.binding(1.0, () -> c.groupPower.getOrDefault(key, 1.0), v -> c.groupPower.put(key, v))
						.controller(o -> DoubleFieldControllerBuilder.create(o).range(0.0, 1_000_000.0))
						.build());
			}
			power.group(group.build());
		}

		ConfigCategory hud = ConfigCategory.createBuilder().name(t("category.hud"))
				.option(Option.<EsSettings.HudStyle>createBuilder().name(t("hud_style")).description(d("hud_style"))
						.binding(def.hudStyle, () -> c.hudStyle, v -> c.hudStyle = v)
						.controller(o -> dev.isxander.yacl3.api.controller.EnumControllerBuilder.create(o).enumClass(EsSettings.HudStyle.class)
								.formatValue(v -> t("hud_style." + v.name().toLowerCase())))
						.build())
				.option(slider("hud_scale", def.hudScale, 0.25, 4, () -> c.hudScale, v -> c.hudScale = v))
				.option(slider("hud_panel", def.hudPanelOpacity, 0, 1, () -> c.hudPanelOpacity, v -> c.hudPanelOpacity = v))
				.option(bool("hud_group_label", def.hudGroupLabel, () -> c.hudGroupLabel, v -> c.hudGroupLabel = v))
				.option(bool("hud_special", def.hudShowSpecial, () -> c.hudShowSpecial, v -> c.hudShowSpecial = v))
				.option(Option.<EsSettings.ScreenBackground>createBuilder().name(t("altar_background")).description(d("altar_background"))
						.binding(def.altarBackground, () -> c.altarBackground, v -> c.altarBackground = v)
						.controller(o -> dev.isxander.yacl3.api.controller.EnumControllerBuilder.create(o).enumClass(EsSettings.ScreenBackground.class)
								.formatValue(v -> t("altar_background." + v.name().toLowerCase())))
						.build())
				.option(bool("invis_name", def.invisibilityHidesName, () -> c.invisibilityHidesName, v -> c.invisibilityHidesName = v))
				.option(bool("invis_dust", def.invisibilityNoSprintDust, () -> c.invisibilityNoSprintDust, v -> c.invisibilityNoSprintDust = v))
				.option(bool("invis_mobs_forget", def.invisibilityMobsForget, () -> c.invisibilityMobsForget, v -> c.invisibilityMobsForget = v))
				.option(bool("invis_particles", def.invisibilityHidesParticles, () -> c.invisibilityHidesParticles, v -> c.invisibilityHidesParticles = v))
				.option(integer("invis_unbreakable", def.invisibilityUnbreakableLevel, 0, 1000, () -> c.invisibilityUnbreakableLevel, v -> c.invisibilityUnbreakableLevel = v))
				.option(bool("damage_passive", def.damagePassiveMobs, () -> c.damagePassiveMobs, v -> c.damagePassiveMobs = v))
				.option(bool("status_overlay", def.statusOverlay, () -> c.statusOverlay, v -> c.statusOverlay = v))
				.option(bool("hud_visible", def.hudVisible, () -> c.hudVisible, v -> c.hudVisible = v))
				.option(integer("hud_x", def.hudX, 0, 4000, () -> c.hudX, v -> c.hudX = v))
				.option(integer("hud_y", def.hudY, 0, 4000, () -> c.hudY, v -> c.hudY = v))
				.option(color("hud_bar", def.hudBarColor, () -> c.hudBarColor, v -> c.hudBarColor = v))
				.option(color("hud_text", def.hudTextColor, () -> c.hudTextColor, v -> c.hudTextColor = v))
				.option(color("hud_ready", def.hudReadyColor, () -> c.hudReadyColor, v -> c.hudReadyColor = v))
				.option(color("hud_cooldown", def.hudCooldownColor, () -> c.hudCooldownColor, v -> c.hudCooldownColor = v))
				.option(color("hud_active", def.hudActiveColor, () -> c.hudActiveColor, v -> c.hudActiveColor = v))
				.build();

		ConfigCategory rules = ConfigCategory.createBuilder().name(t("category.rules"))
				.group(OptionGroup.createBuilder().name(t("group.costs"))
						.option(num("cost_multiplier", def.globalCostMultiplier, 0, 1000, () -> c.globalCostMultiplier, v -> c.globalCostMultiplier = v))
						.option(num("cost_increment", def.costIncrement, 0, 100, () -> c.costIncrement, v -> c.costIncrement = v))
						.option(bool("creative_free", def.creativeFree, () -> c.creativeFree, v -> c.creativeFree = v))
						.option(bool("one_tree", def.oneTreePerClass, () -> c.oneTreePerClass, v -> c.oneTreePerClass = v))
						.option(integer("max_resets", def.maxResetUnlocks, 0, 1000, () -> c.maxResetUnlocks, v -> c.maxResetUnlocks = v))
						.option(num("retry_return", def.retryXpReturn, 0, 1, () -> c.retryXpReturn, v -> c.retryXpReturn = v))
						.option(num("store_tariff", def.xpStoreTariff, 0, 1, () -> c.xpStoreTariff, v -> c.xpStoreTariff = v))
						.option(num("take_tariff", def.xpTakeTariff, 0, 1, () -> c.xpTakeTariff, v -> c.xpTakeTariff = v))
						.build())
				.group(OptionGroup.createBuilder().name(t("group.endurance"))
						.option(num("default_endurance", def.defaultEndurance, 1, 1.0E18, () -> c.defaultEndurance, v -> c.defaultEndurance = v))
						.option(bool("infinite_endurance", def.infiniteEndurance, () -> c.infiniteEndurance, v -> c.infiniteEndurance = v))
						.option(num("passive_speed_power", def.speedPassivePower, 0, 100, () -> c.speedPassivePower, v -> c.speedPassivePower = v))
						.option(num("passive_swim_power", def.swimSpeedPower, 0, 20, () -> c.swimSpeedPower, v -> c.swimSpeedPower = v))
						.option(num("swim_efficiency", def.swimEfficiencyScale, 0, 1, () -> c.swimEfficiencyScale, v -> c.swimEfficiencyScale = v))
						.option(num("swim_breath", def.swimBreathBonus, 0, 1000, () -> c.swimBreathBonus, v -> c.swimBreathBonus = v))
						.option(integer("swim_vision_level", def.swimVisionLevel, 0, 1000, () -> c.swimVisionLevel, v -> c.swimVisionLevel = v))
						.option(integer("swim_air_level", def.swimInfiniteAirLevel, 0, 1000, () -> c.swimInfiniteAirLevel, v -> c.swimInfiniteAirLevel = v))
						.option(num("passive_attack_power", def.attackSpeedPower, 0, 100, () -> c.attackSpeedPower, v -> c.attackSpeedPower = v))
						.option(num("crit_multiplier", def.critMultiplier, 1, 100, () -> c.critMultiplier, v -> c.critMultiplier = v))
						.option(bool("passive_report", def.passiveReport, () -> c.passiveReport, v -> c.passiveReport = v))
						.option(integer("default_level", def.defaultEnderLevel, 0, 1000, () -> c.defaultEnderLevel, v -> c.defaultEnderLevel = v))
						.option(bool("token_drops", def.enderTokenDrops, () -> c.enderTokenDrops, v -> c.enderTokenDrops = v))
						.option(num("token_rate", def.enderTokenDropRate, 0, 1, () -> c.enderTokenDropRate, v -> c.enderTokenDropRate = v))
						.build())
				.group(OptionGroup.createBuilder().name(t("group.hurt_timer"))
						.option(bool("hurt_timer", def.hurtTimer, () -> c.hurtTimer, v -> c.hurtTimer = v))
						.option(num("hurt_timer_diff", def.hurtTimerDamageDifference, 0, 1000, () -> c.hurtTimerDamageDifference, v -> c.hurtTimerDamageDifference = v))
						.build())
				.build();

		ConfigCategory.Builder sounds = ConfigCategory.createBuilder().name(t("category.sounds"))
				.group(OptionGroup.createBuilder().name(t("group.global"))
						.option(slider("sound_volume", def.soundVolume, 0, 2, () -> c.soundVolume, v -> c.soundVolume = v))
						.option(bool("extra_sounds", def.extraSounds, () -> c.extraSounds, v -> c.extraSounds = v))
						.option(Option.<EsSettings.EffectStyle>createBuilder().name(t("effect_style")).description(d("effect_style"))
								.binding(def.effectStyle, () -> c.effectStyle, v -> c.effectStyle = v)
								.controller(o -> dev.isxander.yacl3.api.controller.EnumControllerBuilder.create(o).enumClass(EsSettings.EffectStyle.class)
										.formatValue(v -> t("effect_style." + v.name().toLowerCase())))
								.build())
						.option(slider("vfx_density", def.vfxDensity, 0.1, 4, () -> c.vfxDensity, v -> c.vfxDensity = v))
						.option(integer("max_particles", def.maxParticlesPerEffect, 1, 2000, () -> c.maxParticlesPerEffect, v -> c.maxParticlesPerEffect = v))
						.option(Option.<EsSettings.PassiveMode>createBuilder().name(t("passive_mode")).description(d("passive_mode"))
								.binding(def.passiveMode, () -> c.passiveMode, v -> c.passiveMode = v)
								.controller(o -> dev.isxander.yacl3.api.controller.EnumControllerBuilder.create(o).enumClass(EsSettings.PassiveMode.class)
										.formatValue(v -> t("passive_mode." + v.name().toLowerCase())))
								.build())
						.option(slider("area_duration", def.areaDurationMultiplier, 0.25, 5, () -> c.areaDurationMultiplier, v -> c.areaDurationMultiplier = v))
						.option(bool("modify_environment", def.modifyEnvironment, () -> c.modifyEnvironment, v -> c.modifyEnvironment = v))
						.option(bool("destroy_environment", def.destroyEnvironment, () -> c.destroyEnvironment, v -> c.destroyEnvironment = v))
						.option(integer("environment_seconds", def.environmentSeconds, 1, 600, () -> c.environmentSeconds, v -> c.environmentSeconds = v))
						.option(Option.<EsSettings.VfxStyle>createBuilder().name(t("vfx_style")).description(d("vfx_style"))
								.binding(def.vfxStyle, () -> c.vfxStyle, v -> c.vfxStyle = v)
								.controller(o -> dev.isxander.yacl3.api.controller.EnumControllerBuilder.create(o).enumClass(EsSettings.VfxStyle.class)
										.formatValue(v -> t("vfx_style." + v.name().toLowerCase())))
								.build())
						.option(Option.<EsSettings.SoundStyle>createBuilder().name(t("sound_style")).description(d("sound_style"))
								.binding(def.soundStyle, () -> c.soundStyle, v -> c.soundStyle = v)
								.controller(o -> dev.isxander.yacl3.api.controller.EnumControllerBuilder.create(o).enumClass(EsSettings.SoundStyle.class)
										.formatValue(v -> t("sound_style." + v.name().toLowerCase())))
								.build())
						.option(bool("vfx_shapes", def.vfxShapes, () -> c.vfxShapes, v -> c.vfxShapes = v))
						.option(bool("vfx_trails", def.vfxTrails, () -> c.vfxTrails, v -> c.vfxTrails = v))
						.option(bool("vfx_vibrate", def.vfxVibrate, () -> c.vfxVibrate, v -> c.vfxVibrate = v))
						.build());
		for (Skills.Page page : Skills.PAGES) {
			OptionGroup.Builder group = OptionGroup.createBuilder().collapsed(true)
					.name(Component.translatable("enderskills.tab." + page.tab().id).withColor(page.tab().color)
							.append(" · ").append(Component.translatable("enderskills.page." + page.element())));
			for (String id : page.abilities()) {
				group.option(Option.<Double>createBuilder().name(Component.translatable("skill.enderskills." + id))
						.description(d("skill_volume"))
						.binding(1.0, () -> c.skillVolume.getOrDefault(id, 1.0), v -> c.skillVolume.put(id, v))
						.controller(o -> dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder.create(o).range(0.0, 2.0).step(0.05))
						.build());
			}
			sounds.group(group.build());
		}

		YetAnotherConfigLib.Builder builder = YetAnotherConfigLib.createBuilder().title(t("title"))
				.category(power.build()).category(sounds.build()).category(hud).category(rules)
				.save(EsSettings::save);
		// Defense / Mobility / Offense: one button per element (Earth, Wind, Void...) opening its abilities;
		// Passives: the attributes (damage, swim speed, critical chance...) of every class
		for (Skills.Tab tab : Skills.Tab.values()) {
			builder.category(tabCategory(c, tab));
		}
		builder.category(passivesCategory(c));
		return builder.build().generateScreen(parent);
	}

	private static ConfigCategory tabCategory(EsSettings c, Skills.Tab tab) {
		ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Component.translatable("enderskills.tab." + tab.id));
		for (Skills.Page page : Skills.pages(tab)) {
			String key = "enderskills.page." + page.element();
			net.minecraft.network.chat.MutableComponent list = Component.empty();
			for (String id : page.abilities()) {
				if (!list.getSiblings().isEmpty()) {
					list.append("\n");
				}
				list.append(Component.literal("• ").append(Component.translatable("skill.enderskills." + id)));
			}
			cat.option(dev.isxander.yacl3.api.ButtonOption.createBuilder()
					.name(Component.translatable(key).withColor(tab.color))
					.description(OptionDescription.of(list))
					.text(t("open"))
					.action((screen, option) -> net.minecraft.client.Minecraft.getInstance().setScreen(elementScreen(screen, c, tab, key)))
					.build());
		}
		return cat.build();
	}

	/** The abilities of one element, each in its own folding group. */
	private static Screen elementScreen(Screen parent, EsSettings c, Skills.Tab tab, String key) {
		YetAnotherConfigLib.Builder b = YetAnotherConfigLib.createBuilder()
				.title(Component.translatable("enderskills.tab." + tab.id).append(" · ").append(Component.translatable(key)))
				.save(EsSettings::save);
		skillCategories(c, tab, key).forEach(b::category);
		return b.build().generateScreen(parent);
	}

	private static ConfigCategory passivesCategory(EsSettings c) {
		ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(t("category.passives"));
		for (Skills.Tab tab : Skills.Tab.values()) {
			for (ConfigCategory built : skillCategories(c, tab, "enderskills.altar.attributes")) {
				for (OptionGroup group : built.groups()) {
					if (!group.isRoot()) {
						cat.group(group);
					}
				}
			}
		}
		return cat.build();
	}

	/** Every skill of a class: on/off, max level and each property at level 0 and at the max level. */
	/** One tab per element of the class (e.g. "Mobility · Wind"), each with one folding group per ability. */
	private static java.util.List<ConfigCategory> skillCategories(EsSettings c, Skills.Tab tab, String only) {
		java.util.List<ConfigCategory> out = new java.util.ArrayList<>();
		ConfigCategory.Builder cat = null;
		String current = null;
		java.util.List<String[]> entries = new java.util.ArrayList<>();
		for (Skills.Page page : Skills.pages(tab)) {
			for (String id : page.abilities()) {
				entries.add(new String[] {id, "enderskills.page." + page.element()});
			}
		}
		for (String id : Skills.ATTRIBUTES.get(tab)) {
			entries.add(new String[] {id, "enderskills.altar.attributes"});
		}
		if (only != null) {
			entries.removeIf(e -> !e[1].equals(only));
		}
		for (String[] e : entries) {
			String id = e[0];
			if (!e[1].equals(current)) {
				if (cat != null) {
					out.add(cat.build());
				}
				current = e[1];
				cat = ConfigCategory.createBuilder().name(Component.translatable("enderskills.tab." + tab.id).append(" · ")
						.append(Component.translatable(e[1])));
			}
			OptionGroup.Builder g = OptionGroup.createBuilder().collapsed(true)
					.name(Component.translatable("skill.enderskills." + id).withColor(tab.color))
					.description(OptionDescription.of(Component.translatable("skill.enderskills." + id + ".desc")));
			g.option(Option.<Boolean>createBuilder().name(t("skill_enabled")).description(d("skill_enabled"))
					.binding(true, () -> !c.disabledSkills.contains(id), v -> {
						c.disabledSkills.remove(id);
						if (!v) {
							c.disabledSkills.add(id);
						}
					}).controller(TickBoxControllerBuilder::create).build());
			int fileMax = dev.enderskills.dsl.SkillConfigs.fileMaxLevel(id);
			g.option(Option.<Integer>createBuilder().name(t("skill_max_level")).description(d("skill_max_level"))
					.binding(fileMax, () -> c.skillMaxLevel.getOrDefault(id, fileMax), v -> {
						if (v == fileMax) {
							c.skillMaxLevel.remove(id);
						} else {
							c.skillMaxLevel.put(id, v);
						}
					}).controller(o -> IntegerFieldControllerBuilder.create(o).range(0, 1000)).build());
			EsSettings def0 = new EsSettings();
			extras(g, c, id);
			g.option(Option.<String>createBuilder().name(t("skill_sound_style")).description(d("skill_sound_style"))
					.binding(def0.skillSoundStyle.getOrDefault(id, "DEFAULT"), () -> c.skillSoundStyle.getOrDefault(id, "DEFAULT"), v -> {
						if (v.equals("DEFAULT")) {
							c.skillSoundStyle.remove(id);
						} else {
							c.skillSoundStyle.put(id, v);
						}
					})
					.controller(o -> dev.isxander.yacl3.api.controller.CyclingListControllerBuilder.create(o)
							.values("DEFAULT", "ORIGINAL", "MINECRAFT", "BOTH")
							.formatValue(v -> t("sound_style." + v.toLowerCase())))
					.build());
			g.option(Option.<Double>createBuilder().name(t("skill_particles")).description(d("skill_particles"))
					.binding(1.0, () -> c.skillParticles.getOrDefault(id, 1.0), v -> {
						if (Math.abs(v - 1.0) < 1.0E-6) {
							c.skillParticles.remove(id);
						} else {
							c.skillParticles.put(id, v);
						}
					}).controller(o -> dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder.create(o).range(0.0, 3.0).step(0.05)).build());
			g.option(Option.<Boolean>createBuilder().name(t("skill_custom_color")).description(d("skill_custom_color"))
					.binding(false, () -> c.skillColors.containsKey(id), v -> {
						if (!v) {
							c.skillColors.remove(id);
						} else {
							c.skillColors.putIfAbsent(id, tab.color);
						}
					}).controller(TickBoxControllerBuilder::create).build());
			g.option(Option.<Color>createBuilder().name(t("skill_color")).description(d("skill_color"))
					.binding(new Color(tab.color), () -> new Color(c.skillColors.getOrDefault(id, tab.color)), v -> {
						if (c.skillColors.containsKey(id)) {
							c.skillColors.put(id, v.getRGB() & 0xFFFFFF);
						}
					}).controller(ColorControllerBuilder::create).build());
			for (String prop : dev.enderskills.dsl.SkillConfigs.get(id).map.keySet()) {
				g.option(propOption(c, id, prop, 0));
				g.option(propOption(c, id, prop, 1));
			}
			cat.group(g.build());
		}
		if (cat != null) {
			out.add(cat.build());
		}
		return out;
	}

	/** Special settings of Portal, Teleport, Wall and Dome, inside their own group. */
	private static void extras(OptionGroup.Builder g, EsSettings c, String id) {
		EsSettings def = new EsSettings();
		switch (id) {
			case "unstable_portal" -> {
				g.option(bool("unstable_far", def.unstableFar, () -> c.unstableFar, v -> c.unstableFar = v));
				g.option(num("unstable_min", def.unstableMinDistance, 0, 30_000_000, () -> c.unstableMinDistance, v -> c.unstableMinDistance = v));
				g.option(num("unstable_max", def.unstableMaxDistance, 1, 30_000_000, () -> c.unstableMaxDistance, v -> c.unstableMaxDistance = v));
				g.option(bool("unstable_dimension", def.unstableRandomDimension, () -> c.unstableRandomDimension, v -> c.unstableRandomDimension = v));
				g.option(slider("unstable_dimension_chance", def.unstableDimensionChance, 0, 1, () -> c.unstableDimensionChance, v -> c.unstableDimensionChance = v));
				g.option(num("unstable_return_distance", def.unstableReturnDistance, 0, 30_000_000, () -> c.unstableReturnDistance, v -> c.unstableReturnDistance = v));
				g.option(integer("unstable_return_seconds", def.unstableReturnSeconds, 5, 86400, () -> c.unstableReturnSeconds, v -> c.unstableReturnSeconds = v));
				g.option(num("unstable_max_radius", def.unstableMaxRadius, 0.5, 20, () -> c.unstableMaxRadius, v -> c.unstableMaxRadius = v));
				g.option(num("unstable_reach", def.unstableReach, 0.1, 1, () -> c.unstableReach, v -> c.unstableReach = v));
			}
			case "portal" -> {
				g.option(bool("portal_always_open", def.portalAlwaysOpen, () -> c.portalAlwaysOpen, v -> c.portalAlwaysOpen = v));
				g.option(integer("portal_close_start", def.portalAutoCloseStart, 1, 100000, () -> c.portalAutoCloseStart, v -> c.portalAutoCloseStart = v));
				g.option(integer("portal_close_end", def.portalAutoCloseEnd, 1, 100000, () -> c.portalAutoCloseEnd, v -> c.portalAutoCloseEnd = v));
				g.option(num("portal_range_start", def.portalRangeStart, 1, 30_000_000, () -> c.portalRangeStart, v -> c.portalRangeStart = v));
				g.option(num("portal_range_max", def.portalRangeMax, 1, 30_000_000, () -> c.portalRangeMax, v -> c.portalRangeMax = v));
				g.option(num("portal_aim_range", def.portalAimRange, 8, 1024, () -> c.portalAimRange, v -> c.portalAimRange = v));
				g.option(integer("portal_max_pairs", def.portalMaxPairs, 1, 100, () -> c.portalMaxPairs, v -> c.portalMaxPairs = v));
				g.option(num("portal_reach", def.portalReach, 0.1, 1, () -> c.portalReach, v -> c.portalReach = v));
				g.option(bool("coordinates", def.coordinateTargeting, () -> c.coordinateTargeting, v -> c.coordinateTargeting = v));
			}
			case "speed_boost" -> {
				g.option(num("speed_boost_power", def.speedBoostPower, 0, 5, () -> c.speedBoostPower, v -> c.speedBoostPower = v));
				g.option(num("speed_boost_step", def.speedBoostStep, 0, 4, () -> c.speedBoostStep, v -> c.speedBoostStep = v));
			}
			case "shadow" -> {
				g.option(integer("shadow_minion_level", def.shadowMinionLevel, 0, 1000, () -> c.shadowMinionLevel, v -> c.shadowMinionLevel = v));
				g.option(integer("shadow_minion_max", def.shadowMinionMax, 0, 100, () -> c.shadowMinionMax, v -> c.shadowMinionMax = v));
				g.option(num("shadow_minion_damage", def.shadowMinionDamage, 0, 1000, () -> c.shadowMinionDamage, v -> c.shadowMinionDamage = v));
				g.option(num("shadow_minion_range", def.shadowMinionRange, 4, 64, () -> c.shadowMinionRange, v -> c.shadowMinionRange = v));
				g.option(num("shadow_minion_drain", def.shadowMinionDrain, 0, 100, () -> c.shadowMinionDrain, v -> c.shadowMinionDrain = v));
				g.option(num("shadow_minion_top_factor", def.shadowMinionTopFactor, 0, 1, () -> c.shadowMinionTopFactor, v -> c.shadowMinionTopFactor = v));
			}
			case "fire_spirit", "black_blessing_flame" -> g.option(integer("second_rune_ring_level", def.secondRuneRingLevel, 1, 1000,
					() -> c.secondRuneRingLevel, v -> c.secondRuneRingLevel = v));
			case "hasten" -> {
				g.option(integer("hasten_refill_level", def.hastenRefillLevel, 0, 1000, () -> c.hastenRefillLevel, v -> c.hastenRefillLevel = v));
				g.option(num("hasten_cooldown_factor", def.hastenCooldownFactor, 0.1, 2, () -> c.hastenCooldownFactor, v -> c.hastenCooldownFactor = v));
				g.option(integer("hasten_phase_level", def.hastenPhaseLevel, 0, 1000, () -> c.hastenPhaseLevel, v -> c.hastenPhaseLevel = v));
			}
			case "gas_cloud" -> {
				g.option(slider("gas_cloud_size", def.gasCloudSize, 0.5, 3, () -> c.gasCloudSize, v -> c.gasCloudSize = v));
				g.option(num("gas_cloud_hold", def.gasCloudHoldSeconds, 0, 30, () -> c.gasCloudHoldSeconds, v -> c.gasCloudHoldSeconds = v));
			}
			case "black_hole" -> {
				g.option(slider("black_hole_size", def.blackHoleSize, 0.5, 4, () -> c.blackHoleSize, v -> c.blackHoleSize = v));
				g.option(slider("black_hole_duration", def.blackHoleDuration, 0.5, 8, () -> c.blackHoleDuration, v -> c.blackHoleDuration = v));
				g.option(slider("black_hole_pull_range", def.blackHolePullRange, 1, 4, () -> c.blackHolePullRange, v -> c.blackHolePullRange = v));
				g.option(bool("black_hole_cooldown_after", def.blackHoleCooldownAfter, () -> c.blackHoleCooldownAfter, v -> c.blackHoleCooldownAfter = v));
				g.option(integer("black_hole_blocks", def.blackHoleBlocksPerTick, 0, 30, () -> c.blackHoleBlocksPerTick, v -> c.blackHoleBlocksPerTick = v));
				g.option(num("black_hole_top_bonus", def.blackHoleTopBonus, 0, 1000, () -> c.blackHoleTopBonus, v -> c.blackHoleTopBonus = v));
				g.option(num("black_hole_block_radius", def.blackHoleMaxBlockRadius, 2, 1000, () -> c.blackHoleMaxBlockRadius, v -> c.blackHoleMaxBlockRadius = v));
				g.option(num("black_hole_damage_second", def.blackHoleDamagePerSecond, 0, 100, () -> c.blackHoleDamagePerSecond, v -> c.blackHoleDamagePerSecond = v));
			}
			case "blood_pool" -> g.option(slider("blood_pool_size", def.bloodPoolSize, 0.5, 3, () -> c.bloodPoolSize, v -> c.bloodPoolSize = v));
			case "gloom" -> {
				g.option(integer("gloom_volley_level", def.gloomVolleyLevel, 0, 1000, () -> c.gloomVolleyLevel, v -> c.gloomVolleyLevel = v));
				g.option(integer("gloom_max_shots", def.gloomMaxShots, 2, 20, () -> c.gloomMaxShots, v -> c.gloomMaxShots = v));
			}
			case "fog" -> {
				g.option(num("fog_radius", def.fogRadius, 1, 64, () -> c.fogRadius, v -> c.fogRadius = v));
				g.option(integer("fog_toggle_level", def.fogToggleLevel, 0, 1000, () -> c.fogToggleLevel, v -> c.fogToggleLevel = v));
				g.option(integer("fog_max_seconds", def.fogMaxSeconds, 1, 3600, () -> c.fogMaxSeconds, v -> c.fogMaxSeconds = v));
			}
			case "teleport" -> {
				g.option(num("teleport_range_start", def.teleportRangeStart, 1, 30_000_000, () -> c.teleportRangeStart, v -> c.teleportRangeStart = v));
				g.option(num("teleport_range_max", def.teleportRangeMax, 1, 30_000_000, () -> c.teleportRangeMax, v -> c.teleportRangeMax = v));
				g.option(integer("teleport_cooldown_level", def.teleportCooldownStartLevel, 0, 1000, () -> c.teleportCooldownStartLevel, v -> c.teleportCooldownStartLevel = v));
				g.option(num("teleport_cooldown_top", def.teleportCooldownTopSeconds, 1, 100000, () -> c.teleportCooldownTopSeconds, v -> c.teleportCooldownTopSeconds = v));
				g.option(bool("teleport_no_sky", def.teleportNoSky, () -> c.teleportNoSky, v -> c.teleportNoSky = v));
				g.option(bool("teleport_ground", def.teleportGroundOnly, () -> c.teleportGroundOnly, v -> c.teleportGroundOnly = v));
			}
			case "wall", "dome" -> {
				g.option(integer("earth_toggle_level", def.earthToggleLevel, 0, 1000, () -> c.earthToggleLevel, v -> c.earthToggleLevel = v));
				g.option(integer("earth_toggle_max", def.earthToggleMaxSeconds, 0, 86400, () -> c.earthToggleMaxSeconds, v -> c.earthToggleMaxSeconds = v));
				if (id.equals("wall")) {
					g.option(slider("wall_scale", def.wallSizeScale, 0.1, 2, () -> c.wallSizeScale, v -> c.wallSizeScale = v));
					g.option(integer("wall_max_width", def.wallMaxWidth, 0, 64, () -> c.wallMaxWidth, v -> c.wallMaxWidth = v));
					g.option(integer("wall_max_height", def.wallMaxHeight, 0, 64, () -> c.wallMaxHeight, v -> c.wallMaxHeight = v));
					g.option(integer("wall_width", def.wallWidth, 0, 64, () -> c.wallWidth, v -> c.wallWidth = v));
					g.option(integer("wall_height", def.wallHeight, 0, 64, () -> c.wallHeight, v -> c.wallHeight = v));
					g.option(integer("wall_thickness", def.wallThickness, 1, 8, () -> c.wallThickness, v -> c.wallThickness = v));
					g.option(integer("wall_wings", def.wallWings, 0, 16, () -> c.wallWings, v -> c.wallWings = v));
					g.option(num("wall_distance", def.wallDistance, 0, 32, () -> c.wallDistance, v -> c.wallDistance = v));
				} else {
					g.option(slider("dome_scale", def.domeSizeScale, 0.1, 2, () -> c.domeSizeScale, v -> c.domeSizeScale = v));
					g.option(integer("dome_max_radius", def.domeMaxRadius, 0, 32, () -> c.domeMaxRadius, v -> c.domeMaxRadius = v));
					g.option(integer("dome_max_height", def.domeMaxHeight, 0, 64, () -> c.domeMaxHeight, v -> c.domeMaxHeight = v));
					g.option(integer("dome_radius", def.domeRadius, 0, 32, () -> c.domeRadius, v -> c.domeRadius = v));
					g.option(integer("dome_height", def.domeHeight, 0, 64, () -> c.domeHeight, v -> c.domeHeight = v));
					g.option(bool("dome_roof", def.domeRoof, () -> c.domeRoof, v -> c.domeRoof = v));
				}
				g.option(bool("earth_copy_ground", def.earthCopyGround, () -> c.earthCopyGround, v -> c.earthCopyGround = v));
				g.option(bool("earth_replace_small", def.earthReplaceSmall, () -> c.earthReplaceSmall, v -> c.earthReplaceSmall = v));
				g.option(Option.<String>createBuilder().name(t("earth_fallback")).description(d("earth_fallback"))
						.binding(def.earthFallbackBlock, () -> c.earthFallbackBlock, v -> c.earthFallbackBlock = v)
						.controller(dev.isxander.yacl3.api.controller.StringControllerBuilder::create).build());
			}
			default -> {
			}
		}
	}

	/** Property value at level 0 (end 0) or at the max level (end 1), in display units. */
	private static Option<Double> propOption(EsSettings c, String id, String prop, int end) {
		dev.enderskills.dsl.Units u = dev.enderskills.dsl.Units.of(id, prop);
		int cap = Math.max(1, dev.enderskills.dsl.SkillConfigs.fileMaxLevel(id));
		double fileValue = round(u.toDisplay(dev.enderskills.dsl.SkillConfigs.raw(id, prop, end == 0 ? 0 : cap)));
		Component unit = Component.literal(u.suffix.isEmpty() ? "" : " (" + u.suffix + ")");
		return Option.<Double>createBuilder()
				.name(Component.translatable(end == 0 ? "enderskills.config.at_level0" : "enderskills.config.at_max",
						Component.translatable("enderskills.stat." + prop.toLowerCase())).append(unit))
				.description(OptionDescription.of(Component.translatable("enderskills.config.prop.desc", fileValue)))
				.binding(fileValue, () -> {
					java.util.Map<String, double[]> m = c.skillTuning.get(id);
					double[] r = m == null ? null : m.get(prop);
					return r == null ? fileValue : r[end];
				}, v -> {
					java.util.Map<String, double[]> m = c.skillTuning.computeIfAbsent(id, k -> new java.util.LinkedHashMap<>());
					double[] r = m.get(prop);
					if (r == null) {
						r = new double[] {round(u.toDisplay(dev.enderskills.dsl.SkillConfigs.raw(id, prop, 0))),
								round(u.toDisplay(dev.enderskills.dsl.SkillConfigs.raw(id, prop, cap)))};
					}
					r[end] = v;
					double a = round(u.toDisplay(dev.enderskills.dsl.SkillConfigs.raw(id, prop, 0)));
					double b = round(u.toDisplay(dev.enderskills.dsl.SkillConfigs.raw(id, prop, cap)));
					if (Math.abs(r[0] - a) < 1.0E-6 && Math.abs(r[1] - b) < 1.0E-6) {
						m.remove(prop); // unchanged: keep the original curve
						if (m.isEmpty()) {
							c.skillTuning.remove(id);
						}
					} else {
						m.put(prop, r);
					}
				})
				.controller(o -> DoubleFieldControllerBuilder.create(o).range(-1.0E9, 1.0E9))
				.build();
	}

	private static double round(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	private static Option<Double> num(String key, double def, double min, double max, Supplier<Double> get, Consumer<Double> set) {
		return Option.<Double>createBuilder().name(t(key)).description(d(key)).binding(def, get, set)
				.controller(o -> DoubleFieldControllerBuilder.create(o).range(min, max)).build();
	}

	private static Option<Double> slider(String key, double def, double min, double max, Supplier<Double> get, Consumer<Double> set) {
		return Option.<Double>createBuilder().name(t(key)).description(d(key)).binding(def, get, set)
				.controller(o -> dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder.create(o).range(min, max).step(0.05)).build();
	}

	private static Option<Integer> integer(String key, int def, int min, int max, Supplier<Integer> get, Consumer<Integer> set) {
		return Option.<Integer>createBuilder().name(t(key)).description(d(key)).binding(def, get, set)
				.controller(o -> IntegerFieldControllerBuilder.create(o).range(min, max)).build();
	}

	private static Option<Boolean> bool(String key, boolean def, Supplier<Boolean> get, Consumer<Boolean> set) {
		return Option.<Boolean>createBuilder().name(t(key)).description(d(key)).binding(def, get, set)
				.controller(TickBoxControllerBuilder::create).build();
	}

	private static Option<Color> color(String key, int def, Supplier<Integer> get, Consumer<Integer> set) {
		return Option.<Color>createBuilder().name(t(key)).description(d(key))
				.binding(new Color(def), () -> new Color(get.get()), v -> set.accept(v.getRGB() & 0xFFFFFF))
				.controller(ColorControllerBuilder::create).build();
	}
}
