package dev.enderskills.registry;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistryBuilder;

import dev.enderskills.EnderSkills;

/** Status effects, potions and the original sounds. */
public final class ModEffects {
	public static class EsEffect extends MobEffect {
		public EsEffect(MobEffectCategory category, int color) {
			super(category, color);
		}
	}

	// ---- status effects of the skills
	public static final Holder<MobEffect> STUNNED = effect("stunned", new EsEffect(MobEffectCategory.HARMFUL, 0xC8B464)
			.addAttributeModifier(Attributes.MOVEMENT_SPEED, EnderSkills.id("stunned"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(Attributes.JUMP_STRENGTH, EnderSkills.id("stunned"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(Attributes.ATTACK_DAMAGE, EnderSkills.id("stunned"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	public static final Holder<MobEffect> ELECTRIFIED = effect("electrified", new EsEffect(MobEffectCategory.HARMFUL, 0x7FD4FF)
			.addAttributeModifier(Attributes.MOVEMENT_SPEED, EnderSkills.id("electrified"), -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	public static final Holder<MobEffect> ROOTED = effect("rooted", new EsEffect(MobEffectCategory.HARMFUL, 0x3C2A50)
			.addAttributeModifier(Attributes.MOVEMENT_SPEED, EnderSkills.id("rooted"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(Attributes.JUMP_STRENGTH, EnderSkills.id("rooted"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	public static final Holder<MobEffect> BLEEDING = effect("bleeding", new EsEffect(MobEffectCategory.HARMFUL, 0x8B0000));
	public static final Holder<MobEffect> GASSED = effect("gassed", new EsEffect(MobEffectCategory.HARMFUL, 0x6B8E23));
	public static final Holder<MobEffect> BLACK_FLAME = effect("black_flame", new EsEffect(MobEffectCategory.HARMFUL, 0x1A0A2A));

	// ---- potion effects
	/** +25% ability power per level. */
	public static final Holder<MobEffect> ABILITY_POWER = effect("ability_power", new EsEffect(MobEffectCategory.BENEFICIAL, 0xB04BE0));
	/** +10 max endurance per level. */
	public static final Holder<MobEffect> ENDURANCE = effect("endurance", new EsEffect(MobEffectCategory.BENEFICIAL, 0x3C8CFF));
	/** Endurance comes back faster. */
	public static final Holder<MobEffect> ENDURANCE_REGEN = effect("endurance_regen", new EsEffect(MobEffectCategory.BENEFICIAL, 0x64B4FF));
	/** -15% ability cooldowns per level. */
	public static final Holder<MobEffect> COOLDOWN = effect("cooldown", new EsEffect(MobEffectCategory.BENEFICIAL, 0xE0E060));

	public static final Holder<Potion> STRANGE = potion("strange", new Potion("strange"));
	public static final Holder<Potion> ABILITY_POWER_P = potion("ability_power", new Potion("ability_power", new MobEffectInstance(ABILITY_POWER, 3600)));
	public static final Holder<Potion> LONG_ABILITY_POWER = potion("long_ability_power", new Potion("ability_power", new MobEffectInstance(ABILITY_POWER, 9600)));
	public static final Holder<Potion> STRONG_ABILITY_POWER = potion("strong_ability_power", new Potion("ability_power", new MobEffectInstance(ABILITY_POWER, 1800, 1)));
	public static final Holder<Potion> ENDURANCE_P = potion("endurance", new Potion("endurance", new MobEffectInstance(ENDURANCE, 3600)));
	public static final Holder<Potion> STRONG_ENDURANCE = potion("strong_endurance", new Potion("endurance", new MobEffectInstance(ENDURANCE, 1800, 1)));
	public static final Holder<Potion> ENDURANCE_REGEN_P = potion("endurance_regen", new Potion("endurance_regen", new MobEffectInstance(ENDURANCE_REGEN, 900)));
	public static final Holder<Potion> LONG_ENDURANCE_REGEN = potion("long_endurance_regen", new Potion("endurance_regen", new MobEffectInstance(ENDURANCE_REGEN, 1800)));
	public static final Holder<Potion> STRONG_ENDURANCE_REGEN = potion("strong_endurance_regen", new Potion("endurance_regen", new MobEffectInstance(ENDURANCE_REGEN, 450, 1)));
	public static final Holder<Potion> COOLDOWN_P = potion("cooldown", new Potion("cooldown", new MobEffectInstance(COOLDOWN, 3600)));
	public static final Holder<Potion> LONG_COOLDOWN = potion("long_cooldown", new Potion("cooldown", new MobEffectInstance(COOLDOWN, 9600)));
	public static final Holder<Potion> STRONG_COOLDOWN = potion("strong_cooldown", new Potion("cooldown", new MobEffectInstance(COOLDOWN, 1800, 1)));

	private static final Map<String, SoundEvent> SOUNDS = new HashMap<>();

	private ModEffects() {
	}

	private static Holder<MobEffect> effect(String name, MobEffect effect) {
		return Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, EnderSkills.id(name), effect);
	}

	private static Holder<Potion> potion(String name, Potion potion) {
		return Registry.registerForHolder(BuiltInRegistries.POTION, EnderSkills.id(name), potion);
	}

	public static void init() {
		try (InputStream in = ModEffects.class.getResourceAsStream("/assets/enderskills/sounds.json")) {
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (String name : json.keySet()) {
				SOUNDS.put(name, Registry.register(BuiltInRegistries.SOUND_EVENT, EnderSkills.id(name),
						SoundEvent.createVariableRangeEvent(EnderSkills.id(name))));
			}
		} catch (Exception e) {
			EnderSkills.LOGGER.error("Could not register the sounds: {}", e.toString());
		}
		FabricBrewingRecipeRegistryBuilder.BUILD.register(b -> {
			b.registerPotionRecipe(Potions.AWKWARD, Ingredient.of(ModContent.ENDER_DUST), STRANGE);
			b.registerPotionRecipe(STRANGE, Ingredient.of(Items.BLAZE_POWDER), ABILITY_POWER_P);
			b.registerPotionRecipe(ABILITY_POWER_P, Ingredient.of(Items.REDSTONE), LONG_ABILITY_POWER);
			b.registerPotionRecipe(ABILITY_POWER_P, Ingredient.of(Items.GLOWSTONE_DUST), STRONG_ABILITY_POWER);
			b.registerPotionRecipe(STRANGE, Ingredient.of(Items.GOLDEN_CARROT), ENDURANCE_P);
			b.registerPotionRecipe(ENDURANCE_P, Ingredient.of(Items.GLOWSTONE_DUST), STRONG_ENDURANCE);
			b.registerPotionRecipe(STRANGE, Ingredient.of(Items.SUGAR), ENDURANCE_REGEN_P);
			b.registerPotionRecipe(ENDURANCE_REGEN_P, Ingredient.of(Items.REDSTONE), LONG_ENDURANCE_REGEN);
			b.registerPotionRecipe(ENDURANCE_REGEN_P, Ingredient.of(Items.GLOWSTONE_DUST), STRONG_ENDURANCE_REGEN);
			b.registerPotionRecipe(STRANGE, Ingredient.of(Items.CLOCK), COOLDOWN_P);
			b.registerPotionRecipe(COOLDOWN_P, Ingredient.of(Items.REDSTONE), LONG_COOLDOWN);
			b.registerPotionRecipe(COOLDOWN_P, Ingredient.of(Items.GLOWSTONE_DUST), STRONG_COOLDOWN);
		});
	}

	/** Original sound of the mod for a skill name (with the original file names), if there is one. */
	public static boolean play(ServerLevel level, Vec3 at, String skill) {
		String name = switch (skill) {
			case "animated_stone_golem" -> "animated_stone";
			case "barrage_wisps" -> "barrage_whisps_cast";
			case "black_hole" -> "blackhole";
			case "blood_pool" -> "bloodpool";
			case "final_flash" -> "final_flash_cast";
			case "gleam_flash" -> "gleam_bang_cast";
			case "lumen_wave" -> "lumin_wave_cast";
			case "radiant_ray" -> "radiant_ray_cast";
			case "solar_lance" -> "solar_lance_cast";
			case "shadow_jab" -> "shadowjab";
			case "speed_boost" -> "speed";
			case "extra_jump" -> "jump";
			case "smash" -> "smash_start";
			case "portal" -> "portal_place";
			case "voltaic_sentinel" -> "voltaic_sentinel_summon";
			case "wall" -> "wall_up";
			case "dome" -> "dome_up";
			case "thorny" -> "thorns";
			case "home_star" -> "home_star_boom";
			case "blazing_aura" -> "blazing_aura_active";
			case "flares" -> "flares_active";
			case "overheat", "black_blessing_flame" -> "fire_spirit_active";
			case "life_steal" -> "life_steal_attack";
			case "shadow" -> "shadow_attack";
			case "black_flame_ball", "black_raging_flame_ball", "black_volley_flame", "black_scouring_flame", "black_flare_flame" -> "fireball";
			default -> skill;
		};
		SoundEvent sound = SOUNDS.get(name);
		if (sound == null) {
			return false;
		}
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, 1.0F, 1.0F);
		return true;
	}
}
