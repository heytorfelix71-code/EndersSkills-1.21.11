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

		ConfigCategory portal = ConfigCategory.createBuilder().name(t("category.portal"))
				.option(bool("portal_always_open", def.portalAlwaysOpen, () -> c.portalAlwaysOpen, v -> c.portalAlwaysOpen = v))
				.option(integer("portal_close_start", def.portalAutoCloseStart, 1, 100000, () -> c.portalAutoCloseStart, v -> c.portalAutoCloseStart = v))
				.option(integer("portal_close_end", def.portalAutoCloseEnd, 1, 100000, () -> c.portalAutoCloseEnd, v -> c.portalAutoCloseEnd = v))
				.option(bool("coordinates", def.coordinateTargeting, () -> c.coordinateTargeting, v -> c.coordinateTargeting = v))
				.option(bool("teleport_no_sky", def.teleportNoSky, () -> c.teleportNoSky, v -> c.teleportNoSky = v))
				.option(bool("teleport_ground", def.teleportGroundOnly, () -> c.teleportGroundOnly, v -> c.teleportGroundOnly = v))
				.option(num("portal_range_start", def.portalRangeStart, 1, 30_000_000, () -> c.portalRangeStart, v -> c.portalRangeStart = v))
				.option(num("portal_range_max", def.portalRangeMax, 1, 30_000_000, () -> c.portalRangeMax, v -> c.portalRangeMax = v))
				.option(num("portal_aim_range", def.portalAimRange, 8, 1024, () -> c.portalAimRange, v -> c.portalAimRange = v))
				.option(num("teleport_range_start", def.teleportRangeStart, 1, 30_000_000, () -> c.teleportRangeStart, v -> c.teleportRangeStart = v))
				.option(num("teleport_range_max", def.teleportRangeMax, 1, 30_000_000, () -> c.teleportRangeMax, v -> c.teleportRangeMax = v))
				.build();

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
						.option(num("default_endurance", def.defaultEndurance, 1, 1_000_000, () -> c.defaultEndurance, v -> c.defaultEndurance = v))
						.option(integer("default_level", def.defaultEnderLevel, 0, 1000, () -> c.defaultEnderLevel, v -> c.defaultEnderLevel = v))
						.option(bool("token_drops", def.enderTokenDrops, () -> c.enderTokenDrops, v -> c.enderTokenDrops = v))
						.option(num("token_rate", def.enderTokenDropRate, 0, 1, () -> c.enderTokenDropRate, v -> c.enderTokenDropRate = v))
						.build())
				.group(OptionGroup.createBuilder().name(t("group.hurt_timer"))
						.option(bool("hurt_timer", def.hurtTimer, () -> c.hurtTimer, v -> c.hurtTimer = v))
						.option(num("hurt_timer_diff", def.hurtTimerDamageDifference, 0, 1000, () -> c.hurtTimerDamageDifference, v -> c.hurtTimerDamageDifference = v))
						.build())
				.build();

		ConfigCategory earth = ConfigCategory.createBuilder().name(t("category.earth"))
				.option(integer("earth_toggle_level", def.earthToggleLevel, 0, 1000, () -> c.earthToggleLevel, v -> c.earthToggleLevel = v))
				.option(integer("wall_width", def.wallWidth, 0, 64, () -> c.wallWidth, v -> c.wallWidth = v))
				.option(integer("wall_height", def.wallHeight, 0, 64, () -> c.wallHeight, v -> c.wallHeight = v))
				.option(integer("wall_thickness", def.wallThickness, 1, 8, () -> c.wallThickness, v -> c.wallThickness = v))
				.option(integer("wall_wings", def.wallWings, 0, 16, () -> c.wallWings, v -> c.wallWings = v))
				.option(num("wall_distance", def.wallDistance, 0, 32, () -> c.wallDistance, v -> c.wallDistance = v))
				.option(integer("dome_radius", def.domeRadius, 0, 32, () -> c.domeRadius, v -> c.domeRadius = v))
				.option(integer("dome_height", def.domeHeight, 0, 64, () -> c.domeHeight, v -> c.domeHeight = v))
				.option(bool("dome_roof", def.domeRoof, () -> c.domeRoof, v -> c.domeRoof = v))
				.option(bool("earth_copy_ground", def.earthCopyGround, () -> c.earthCopyGround, v -> c.earthCopyGround = v))
				.option(bool("earth_replace_small", def.earthReplaceSmall, () -> c.earthReplaceSmall, v -> c.earthReplaceSmall = v))
				.option(Option.<String>createBuilder().name(t("earth_fallback")).description(d("earth_fallback"))
						.binding(def.earthFallbackBlock, () -> c.earthFallbackBlock, v -> c.earthFallbackBlock = v)
						.controller(dev.isxander.yacl3.api.controller.StringControllerBuilder::create).build())
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

		return YetAnotherConfigLib.createBuilder().title(t("title"))
				.category(power.build()).category(portal).category(earth).category(sounds.build()).category(hud).category(rules)
				.save(EsSettings::save)
				.build().generateScreen(parent);
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
