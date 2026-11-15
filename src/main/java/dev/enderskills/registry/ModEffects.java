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
	/** Where the particles of a status effect are drawn. */
	public enum Look { NONE, HEAD_RING, BODY_SPARKS, FEET_RING, DRIP, FLAMES, CLOUD }

	public static class EsEffect extends MobEffect {
		private final Look look;
		private final net.minecraft.core.particles.ParticleOptions particle;

		public EsEffect(MobEffectCategory category, int color) {
			this(category, color, Look.NONE, null);
		}

		public EsEffect(MobEffectCategory category, int color, Look look, net.minecraft.core.particles.ParticleOptions particle) {
			super(category, color);
			this.look = look;
			this.particle = particle;
		}

		@Override
		public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
			return look != Look.NONE && duration % 3 == 0;
		}

		@Override
		public boolean applyEffectTick(ServerLevel level, net.minecraft.world.entity.LivingEntity e, int amplifier) {
			double t = e.tickCount * 0.35;
			double w = e.getBbWidth() * 0.7 + 0.2;
			double h = e.getBbHeight();
			switch (look) {
				case HEAD_RING -> {
					for (int i = 0; i < 3; i++) {
						double a = t + i * Math.PI * 2 / 3;
						level.sendParticles(particle, e.getX() + Math.cos(a) * w, e.getY() + h + 0.25, e.getZ() + Math.sin(a) * w, 1, 0, 0, 0, 0);
					}
				}
				case BODY_SPARKS -> level.sendParticles(particle, e.getX(), e.getY() + h / 2, e.getZ(), 4, w / 2, h / 3, w / 2, 0.05);
				case FEET_RING -> {
					for (int i = 0; i < 6; i++) {
						double a = t * 0.3 + i * Math.PI / 3;
						level.sendParticles(particle, e.getX() + Math.cos(a) * w, e.getY() + 0.1 + (i % 2) * 0.3, e.getZ() + Math.sin(a) * w, 1, 0, 0.05, 0, 0);
					}
				}
				case DRIP -> level.sendParticles(particle, e.getX(), e.getY() + h * 0.6, e.getZ(), 2, w / 3, h / 4, w / 3, 0);
				case FLAMES -> level.sendParticles(particle, e.getX(), e.getY() + h / 3, e.getZ(), 3, w / 3, h / 3, w / 3, 0.01);
				case CLOUD -> level.sendParticles(particle, e.getX(), e.getY() + h * 0.8, e.getZ(), 3, w / 2, 0.2, w / 2, 0.01);
				default -> {
				}
			}
			return true;
		}
	}

	// ---- status effects of the skills
	public static final Holder<MobEffect> STUNNED = effect("stunned", new EsEffect(MobEffectCategory.HARMFUL, 0xC8B464, Look.HEAD_RING, net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT)
			.addAttributeModifier(Attributes.MOVEMENT_SPEED, EnderSkills.id("stunned"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(Attributes.JUMP_STRENGTH, EnderSkills.id("stunned"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(Attributes.ATTACK_DAMAGE, EnderSkills.id("stunned"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	public static final Holder<MobEffect> ELECTRIFIED = effect("electrified", new EsEffect(MobEffectCategory.HARMFUL, 0x7FD4FF, Look.BODY_SPARKS, net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK)
			.addAttributeModifier(Attributes.MOVEMENT_SPEED, EnderSkills.id("electrified"), -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	public static final Holder<MobEffect> ROOTED = effect("rooted", new EsEffect(MobEffectCategory.HARMFUL, 0x5A8C3A, Look.FEET_RING, net.minecraft.core.particles.ParticleTypes.SPORE_BLOSSOM_AIR)
			.addAttributeModifier(Attributes.MOVEMENT_SPEED, EnderSkills.id("rooted"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.addAttributeModifier(Attributes.JUMP_STRENGTH, EnderSkills.id("rooted"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	public static final Holder<MobEffect> BLEEDING = effect("bleeding", new EsEffect(MobEffectCategory.HARMFUL, 0x8B0000, Look.DRIP, new net.minecraft.core.particles.DustParticleOptions(0x8B0000, 1.0F)));
	public static final Holder<MobEffect> GASSED = effect("gassed", new EsEffect(MobEffectCategory.HARMFUL, 0x6B8E23, Look.CLOUD, new net.minecraft.core.particles.DustParticleOptions(0x6B8E23, 1.5F)));
	public static final Holder<MobEffect> BLACK_FLAME = effect("black_flame", new EsEffect(MobEffectCategory.HARMFUL, 0x5020A0, Look.FLAMES, net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME));

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
		String id = skill;
		if (dev.enderskills.data.EsSettings.get().soundFor(skill) == dev.enderskills.data.EsSettings.SoundStyle.MINECRAFT) {
			return true; // this ability uses the Minecraft sounds
		}
		SoundEvent sound = SOUNDS.get(soundName(skill));
		if (sound == null) {
			return false;
		}
		dev.enderskills.data.EsSettings cfg = dev.enderskills.data.EsSettings.get();
		float volume = (float) (cfg.soundVolume * cfg.skillVolume.getOrDefault(id, 1.0));
		if (volume <= 0) {
			return true;
		}
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, 1.0F);
		return true;
	}

	/** The ability has an original Ender Skills sound. */
	public static boolean hasSound(String skill) {
		return SOUNDS.containsKey(soundName(skill));
	}

	/** Stops the (long, looping) original sound of an ability for the players around, when it ends or is switched off. */
	public static void stop(ServerLevel level, Vec3 at, String skill) {
		SoundEvent sound = SOUNDS.get(soundName(skill));
		if (sound == null) {
			return;
		}
		net.minecraft.network.protocol.game.ClientboundStopSoundPacket packet =
				new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(sound.location(), SoundSource.PLAYERS);
		for (net.minecraft.server.level.ServerPlayer p : level.players()) {
			if (p.distanceToSqr(at) < 96 * 96) {
				p.connection.send(packet);
			}
		}
	}

	private static String soundName(String skill) {
		return switch (skill) {
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
	}
}
