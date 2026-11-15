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
		int level = SkillConfigs.disabled(attribute) ? -1 : data.level(attribute);
		return level < 0 ? 0.0 : SkillConfigs.value(attribute, "MODIFIER", level);
	}

	public static void apply(ServerPlayer player) {
		set(player, Attributes.MAX_HEALTH, "heart_boost", modifier(player, "heart_boost"), AttributeModifier.Operation.ADD_VALUE);
		set(player, Attributes.KNOCKBACK_RESISTANCE, "knockback_resistance", modifier(player, "knockback_resistance"),
				AttributeModifier.Operation.ADD_VALUE);
		// Jump Height is in extra blocks (level 50 = +3.75): turned into jump strength (a 1.25 block jump needs the base strength)
		double extra = Math.max(0.0, modifier(player, "jump_height"));
		set(player, Attributes.JUMP_STRENGTH, "jump_height", extra <= 0 ? 0.0 : Math.sqrt((1.25 + extra) / 1.25) - 1.0,
				AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		set(player, Attributes.WATER_MOVEMENT_EFFICIENCY, "swim_speed", modifier(player, "swim_speed"), AttributeModifier.Operation.ADD_VALUE);
		set(player, Attributes.ATTACK_DAMAGE, "damage", modifier(player, "damage"), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		set(player, Attributes.ATTACK_SPEED, "attack_speed", modifier(player, "attack_speed") * Math.max(0.0, dev.enderskills.data.EsSettings.get().attackSpeedPower),
				AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
		set(player, Attributes.ATTACK_KNOCKBACK, "knockback", modifier(player, "knockback"), AttributeModifier.Operation.ADD_VALUE);
		// real movement speed (synced, works with other mods); the camera FOV ignores it
		set(player, Attributes.MOVEMENT_SPEED, "speed", modifier(player, "speed") * Math.max(0.0, dev.enderskills.data.EsSettings.get().speedPassivePower),
				AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
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
		AttributeModifier old = instance.getModifier(id);
		if (old != null ? old.amount() == value && old.operation() == operation : value == 0.0) {
			return; // unchanged: no churn (removing max health for a moment would cut the health)
		}
		instance.removeModifier(id);
		if (value != 0.0) {
			instance.addTransientModifier(new AttributeModifier(id, value, operation));
		}
	}

	/** A critical hit you can see, hear and read. */
	private static void critFeedback(Player attacker, LivingEntity victim) {
		if (attacker.level() instanceof net.minecraft.server.level.ServerLevel level) {
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT, victim.getX(), victim.getY(0.6), victim.getZ(),
					14, 0.35, 0.35, 0.35, 0.3);
			level.playSound(null, victim.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT,
					net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
		}
		if (attacker instanceof ServerPlayer sp) {
			sp.displayClientMessage(net.minecraft.network.chat.Component.translatable("enderskills.crit"), true);
		}
	}

	private static net.minecraft.network.chat.Component line(String id, String value) {
		return net.minecraft.network.chat.Component.literal("\n  ").append(net.minecraft.network.chat.Component.translatable("skill.enderskills." + id))
				.append(net.minecraft.network.chat.Component.literal(": " + value));
	}

	private static String pct(double v) {
		return String.format(java.util.Locale.ROOT, "%.0f%%", v * 100.0);
	}

	/** What the passives really do right now, read from the attributes the game uses (to see that they are applied). */
	public static void report(ServerPlayer p) {
		var msg = net.minecraft.network.chat.Component.translatable("enderskills.passives.title");
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		AttributeInstance jump = p.getAttribute(Attributes.JUMP_STRENGTH);
		AttributeInstance atk = p.getAttribute(Attributes.ATTACK_SPEED);
		if (modifier(p, "heart_boost") > 0) {
			msg.append(line("heart_boost", String.format(java.util.Locale.ROOT, "%.0f \u2764", p.getMaxHealth() / 2.0)));
		}
		if (speed != null && modifier(p, "speed") > 0) {
			msg.append(line("speed", "+" + pct(speed.getValue() / speed.getBaseValue() - 1.0)));
		}
		if (modifier(p, "swim_speed") > 0) {
			msg.append(line("swim_speed", pct(p.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY)) + " (+" + pct(Math.min(1.0, modifier(p, "swim_speed"))
					* dev.enderskills.data.EsSettings.get().swimSpeedPower) + ")"));
		}
		if (jump != null && modifier(p, "jump_height") > 0) {
			msg.append(line("jump_height", "+" + pct(jump.getValue() / jump.getBaseValue() - 1.0)));
		}
		if (atk != null && modifier(p, "attack_speed") > 0) {
			msg.append(line("attack_speed", String.format(java.util.Locale.ROOT, "%.1f/s", atk.getValue())));
		}
		String[][] plain = {{"damage", "damage"}, {"critical_chance", "critical_chance"}, {"stealth_damage", "stealth_damage"},
				{"armor_penetration", "armor_penetration"}, {"damage_resistance", "damage_resistance"}, {"explosion_resistance", "explosion_resistance"},
				{"fire_resistance", "fire_resistance"}, {"magic_resistance", "magic_resistance"}, {"fall_resistance", "fall_resistance"},
				{"knockback_resistance", "knockback_resistance"}, {"ability_power", "ability_power"}};
		for (String[] e : plain) {
			double v = modifier(p, e[0]);
			if (v > 0) {
				msg.append(line(e[1], e[0].equals("critical_chance") ? pct(v) + " (x" + dev.enderskills.data.EsSettings.get().critMultiplier + ")" : pct(v)));
			}
		}
		EnderSkills.LOGGER.info("Passives of {}: {}", p.getName().getString(), msg.getString().replace('\n', ' '));
		p.sendSystemMessage(msg);
	}

	/** Damage taken by a player (resistances) and dealt by a player in melee (stealth, crits, armor penetration). */
	public static float modifyDamage(LivingEntity victim, DamageSource source, float amount) {
		if (amount <= 0 || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return amount;
		}
		if (!AbilityManager.skillDamage && source.getEntity() instanceof Player attacker && source.getDirectEntity() == attacker
				&& attacker != victim) {
			if (attacker instanceof ServerPlayer sp && AbilityManager.isActive(sp, "sacrifice")) {
				amount *= (float) (1.0 + AbilityManager.value(sp, "sacrifice", "POWER"));
			}
			double stealth = modifier(attacker, "stealth_damage");
			if (stealth > 0 && !inSight(victim, attacker)) {
				amount *= (float) (1.0 + stealth);
			}
			double crit = modifier(attacker, "critical_chance");
			if (crit > 0 && attacker.getRandom().nextDouble() < crit) {
				amount *= (float) Math.max(1.0, dev.enderskills.data.EsSettings.get().critMultiplier);
					critFeedback(attacker, victim);
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
