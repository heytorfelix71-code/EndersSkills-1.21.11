package dev.enderskills.logic;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import dev.enderskills.EnderSkills;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;

/**
 * The class attributes bought at the altar. Stats use vanilla attribute modifiers; resistances and the
 * offensive bonuses change the damage in {@link #modifyDamage}.
 */
public final class AttributeEffects {
	private AttributeEffects() {
	}

	/** Attribute value ("MODIFIER" of its config) for a player, 0 if not owned. */
	public static double modifier(Player player, String attribute) {
		SkillData data = Progression.data(player);
		int level = data.level(attribute);
		return level < 0 ? 0.0 : SkillConfigs.value(attribute, "MODIFIER", level);
	}

	public static void apply(ServerPlayer player) {
		set(player, Attributes.MAX_HEALTH, "heart_boost", modifier(player, "heart_boost"), AttributeModifier.Operation.ADD_VALUE);
		set(player, Attributes.KNOCKBACK_RESISTANCE, "knockback_resistance", modifier(player, "knockback_resistance"),
				AttributeModifier.Operation.ADD_VALUE);
		set(player, Attributes.MOVEMENT_SPEED, "speed", modifier(player, "speed"), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		set(player, Attributes.JUMP_STRENGTH, "jump_height", modifier(player, "jump_height"), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		set(player, Attributes.WATER_MOVEMENT_EFFICIENCY, "swim_speed", modifier(player, "swim_speed"), AttributeModifier.Operation.ADD_VALUE);
		set(player, Attributes.ATTACK_DAMAGE, "damage", modifier(player, "damage"), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		set(player, Attributes.ATTACK_SPEED, "attack_speed", modifier(player, "attack_speed"), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		set(player, Attributes.ATTACK_KNOCKBACK, "knockback", modifier(player, "knockback"), AttributeModifier.Operation.ADD_VALUE);
		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
	}

	static void set(ServerPlayer player, Holder<Attribute> attribute, String name, double value,
			AttributeModifier.Operation operation) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		Identifier id = EnderSkills.id(name);
		instance.removeModifier(id);
		if (value != 0.0) {
			instance.addTransientModifier(new AttributeModifier(id, value, operation));
		}
	}

	/** Damage taken by a player (resistances) and dealt by a player in melee (stealth, crits, armor penetration). */
	public static float modifyDamage(LivingEntity victim, DamageSource source, float amount) {
		if (amount <= 0 || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return amount;
		}
		if (!AbilityManager.skillDamage && source.getEntity() instanceof Player attacker && source.getDirectEntity() == attacker
				&& attacker != victim) {
			double stealth = modifier(attacker, "stealth_damage");
			if (stealth > 0 && !inSight(victim, attacker)) {
				amount *= (float) (1.0 + stealth);
			}
			double crit = modifier(attacker, "critical_chance");
			if (crit > 0 && attacker.getRandom().nextDouble() < crit) {
				amount *= 1.5F;
				if (attacker.level() instanceof net.minecraft.server.level.ServerLevel level) {
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, victim.getX(), victim.getY(0.5), victim.getZ(),
							8, 0.3, 0.3, 0.3, 0.2);
				}
			}
			double pierce = modifier(attacker, "armor_penetration");
			if (pierce > 0) {
				amount *= (float) (1.0 + pierce * Math.min(20, victim.getArmorValue()) / 20.0);
			}
		}
		if (victim instanceof Player player) {
			amount *= (float) (1.0 - clamp(modifier(player, "damage_resistance")));
			if (source.is(DamageTypeTags.IS_EXPLOSION)) {
				amount *= (float) (1.0 - clamp(modifier(player, "explosion_resistance")));
			}
			if (source.is(DamageTypeTags.IS_FIRE)) {
				amount *= (float) (1.0 - clamp(modifier(player, "fire_resistance")));
			}
			if (source.is(DamageTypeTags.WITCH_RESISTANT_TO)) {
				amount *= (float) (1.0 - clamp(modifier(player, "magic_resistance")));
			}
			if (source.is(DamageTypeTags.IS_FALL)) {
				amount *= (float) (1.0 - clamp(modifier(player, "fall_resistance")));
			}
		}
		return amount;
	}

	private static double clamp(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	/** The attacker is inside the victim's field of view (about 120 degrees). */
	private static boolean inSight(LivingEntity victim, LivingEntity attacker) {
		Vec3 look = victim.getViewVector(1.0F).normalize();
		Vec3 to = attacker.getEyePosition().subtract(victim.getEyePosition()).normalize();
		return look.dot(to) > 0.5;
	}
}
