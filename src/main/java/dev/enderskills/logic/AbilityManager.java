package dev.enderskills.logic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.enderskills.data.EsSettings;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;
import dev.enderskills.network.EsNetwork;
import dev.enderskills.registry.ModContent;
import dev.enderskills.skill.Abilities;

/** Server side of the abilities: endurance, cooldowns, timed effects and what each ability does. */
public final class AbilityManager {
	/** True while an ability is dealing damage (so melee attributes don't apply, and kills can drop Ender Tokens). */
	public static boolean skillDamage;

	private static final class State {
		double endurance = -1;
		int lastUse = -10000;
		final Map<String, Integer> cooldownUntil = new HashMap<>();
		final Map<String, Integer> activeUntil = new HashMap<>();
		int jumpsUsed;
		boolean smashing;
		double smashStartY;
		int smashStarted;
		Vec3 portalEntrance;
		boolean dirty = true;
		float sentEndurance = -1;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();
	private static final Map<Mob, Integer> FOGGED = new WeakHashMap<>();
	private static int now;

	private AbilityManager() {
	}

	private static State state(ServerPlayer player) {
		return STATES.computeIfAbsent(player.getUUID(), u -> new State());
	}

	public static void forget(ServerPlayer player) {
		STATES.remove(player.getUUID());
	}

	private static double maxEndurance(ServerPlayer player) {
		return EsSettings.get().defaultEndurance + AttributeEffects.modifier(player, "endurance");
	}

	private static boolean active(State s, String skill) {
		return s.activeUntil.getOrDefault(skill, 0) > now;
	}

	public static double value(ServerPlayer player, String skill, String property) {
		return SkillConfigs.value(skill, property, Math.max(0, Progression.data(player).level(skill)));
	}

	/** Toggle abilities: on until used again or out of endurance (their cost is drained every second). */
	private static final java.util.Set<String> TOGGLES = java.util.Set.of("shocking_aura", "blazing_aura", "flares");
	private static MinecraftServer server;

	public static ServerPlayer player(UUID id) {
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}

	/** Strength multiplier of abilities: Ability Power attribute + Power Boost. */
	public static double power(ServerPlayer player) {
		return 1.0 + AttributeEffects.modifier(player, "ability_power") + DefenseAbilities.boost(player)
				+ (isActive(player, "sacrifice") ? value(player, "sacrifice", "POWER") : 0.0);
	}

	/** Passive on-hit abilities: owned, off cooldown and enough endurance; pays and starts the cooldown. */
	public static boolean tryPassive(ServerPlayer player, String skill) {
		if (!Progression.data(player).owns(skill)) {
			return false;
		}
		State s = state(player);
		double cost = value(player, skill, "ENDURANCE");
		if (s.cooldownUntil.getOrDefault(skill, 0) > now || s.endurance < cost) {
			return false;
		}
		s.endurance -= cost;
		s.lastUse = now;
		s.cooldownUntil.put(skill, now + (int) value(player, skill, "COOLDOWN"));
		s.dirty = true;
		return true;
	}

	public static boolean isActive(ServerPlayer player, String skill) {
		return active(state(player), skill);
	}

	public static void setActive(ServerPlayer player, String skill, int ticks) {
		State s = state(player);
		s.activeUntil.put(skill, now + ticks);
		s.dirty = true;
	}

	public static double endurance(ServerPlayer player) {
		return Math.max(0, state(player).endurance);
	}

	public static void addEndurance(ServerPlayer player, double amount) {
		State s = state(player);
		s.endurance = Math.max(0, Math.min(maxEndurance(player), s.endurance + amount));
		s.dirty = true;
	}

	// ------------------------------------------------------------------ tick

	public static void tick(MinecraftServer srv) {
		server = srv;
		now = srv.getTickCount();
		Zones.tick(now);
		DefenseAbilities.tick(now);
		OffenseAbilities.tick(now);
		if (now % 5 == 0) {
			for (Iterator<Map.Entry<Mob, Integer>> it = FOGGED.entrySet().iterator(); it.hasNext();) {
				Map.Entry<Mob, Integer> e = it.next();
				if (e.getValue() <= now || !e.getKey().isAlive()) {
					it.remove();
				} else {
					e.getKey().setTarget(null);
				}
			}
		}
		for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
			State s = state(player);
			double max = maxEndurance(player);
			if (s.endurance < 0) {
				s.endurance = max;
			}
			int delay = (int) SkillConfigs.value("endurance", "DELAY", 0);
			int regen = Math.max(1, (int) SkillConfigs.value("endurance", "REGEN", 0));
			if (s.endurance < max && now - s.lastUse >= delay && now % regen == 0) {
				s.endurance = Math.min(max, s.endurance + 1);
			}
			if (s.endurance > max) {
				s.endurance = max;
			}
			if (player.onGround()) {
				s.jumpsUsed = 0;
			}
			for (Iterator<Map.Entry<String, Integer>> it = s.activeUntil.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Integer> e = it.next();
				if (e.getValue() <= now) {
					it.remove();
					end(player, e.getKey());
					s.dirty = true;
				}
			}
			for (String toggle : TOGGLES) {
				if (active(s, toggle)) {
					if (now % 20 == 0) {
						s.endurance -= value(player, toggle, "ENDURANCE");
						s.lastUse = now;
						if (s.endurance <= 0) {
							s.endurance = 0;
							switchOff(player, s, toggle);
							continue;
						}
					}
					DefenseAbilities.aura(player, toggle);
				}
			}
			if (active(s, "hover")) {
				player.resetFallDistance();
			}
			if (s.smashing) {
				player.resetFallDistance();
				if (player.onGround() || player.isInWater() || now - s.smashStarted > 200) {
					s.smashing = false;
					if (player.onGround()) {
						smashLand(player, s);
					}
				}
			}
			if (s.dirty || (now % 10 == 0 && (float) s.endurance != s.sentEndurance)) {
				sync(player, s, max);
			}
		}
	}

	private static void sync(ServerPlayer player, State s, double max) {
		Map<String, Integer> cds = new HashMap<>();
		s.cooldownUntil.forEach((k, v) -> {
			if (v > now) {
				cds.put(k, v - now);
			}
		});
		Map<String, Integer> act = new HashMap<>();
		s.activeUntil.forEach((k, v) -> {
			if (v > now) {
				act.put(k, v - now);
			}
		});
		s.dirty = false;
		s.sentEndurance = (float) s.endurance;
		if (ServerPlayNetworking.canSend(player, EsNetwork.AbilitySync.TYPE)) {
			ServerPlayNetworking.send(player, new EsNetwork.AbilitySync((float) s.endurance, (float) max, cds, act));
		}
	}

	private static void switchOff(ServerPlayer player, State s, String toggle) {
		s.activeUntil.remove(toggle);
		s.cooldownUntil.put(toggle, now + (int) value(player, toggle, "COOLDOWN"));
		s.dirty = true;
	}

	/** A timed ability ran out. */
	private static void end(ServerPlayer player, String skill) {
		if (skill.equals("speed_boost")) {
			AttributeEffects.set(player, Attributes.MOVEMENT_SPEED, "speed_boost", 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		}
	}

	// ------------------------------------------------------------------ using abilities

	public static void use(ServerPlayer player, String skill, float dx, float dz) {
		SkillData data = Progression.data(player);
		if (!data.owns(skill) || !Abilities.implemented(skill) || !player.isAlive() || player.isSpectator()) {
			return;
		}
		State s = state(player);
		ServerLevel level = (ServerLevel) player.level();
		double cost = value(player, skill, "ENDURANCE");
		boolean secondPortal = skill.equals("portal") && s.portalEntrance != null;
		if (TOGGLES.contains(skill) && active(s, skill)) {
			switchOff(player, s, skill);
			return;
		}
		if (s.cooldownUntil.getOrDefault(skill, 0) > now) {
			return;
		}
		if (skill.equals("extra_jump") && (player.onGround() || s.jumpsUsed >= (int) value(player, skill, "JUMPS"))) {
			return;
		}
		if (skill.equals("smash") && (player.onGround() || s.smashing)) {
			return;
		}
		if (!secondPortal && s.endurance < cost) {
			return;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		boolean done = true;
		switch (skill) {
			case "dash" -> effect(level, player, ParticleTypes.CLOUD, SoundEvents.BREEZE_JUMP);
			case "extra_jump" -> {
				s.jumpsUsed++;
				player.resetFallDistance();
				effect(level, player, ParticleTypes.CLOUD, SoundEvents.BREEZE_LAND);
			}
			case "hover" -> {
				s.activeUntil.put(skill, now + (int) value(player, skill, "DURATION"));
				player.resetFallDistance();
			}
			case "smash" -> {
				s.smashing = true;
				s.smashStartY = player.getY();
				s.smashStarted = now;
			}
			case "warp" -> {
				Vec3 dir = new Vec3(dx, 0, dz);
				if (dir.lengthSqr() < 1.0E-4) {
					dir = new Vec3(look.x, 0, look.z);
				}
				dir = dir.normalize();
				done = teleport(level, player, player.position().add(0, 0.5, 0), dir, value(player, skill, "RANGE"));
			}
			case "teleport" -> done = teleport(level, player, eye, look, value(player, skill, "RANGE"));
			case "speed_boost" -> {
				s.activeUntil.put(skill, now + (int) value(player, skill, "DURATION"));
				AttributeEffects.set(player, Attributes.MOVEMENT_SPEED, "speed_boost", value(player, skill, "SPEED"),
						AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
				effect(level, player, ParticleTypes.CLOUD, SoundEvents.BREEZE_WIND_CHARGE_BURST.value());
			}
			case "hasten" -> {
				s.activeUntil.put(skill, now + (int) value(player, skill, "DURATION"));
				effect(level, player, ParticleTypes.ENCHANT, SoundEvents.BEACON_ACTIVATE);
			}
			case "invisibility" -> {
				int duration = (int) value(player, skill, "DURATION");
				s.activeUntil.put(skill, now + duration);
				player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, duration, 0, false, false, true));
				effect(level, player, ParticleTypes.PORTAL, SoundEvents.ILLUSIONER_MIRROR_MOVE);
			}
			case "fog" -> {
				LivingEntity target = lookTarget(player, 24.0);
				if (target == null) {
					done = false;
				} else {
					int duration = (int) value(player, skill, "DURATION");
					target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, duration, 0, false, true, true));
					if (target instanceof Mob mob) {
						mob.setTarget(null);
						FOGGED.put(mob, now + duration);
					}
					level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, target.getX(), target.getY(0.6), target.getZ(), 30, 0.5, 0.6, 0.5, 0.01);
					level.playSound(null, target.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8F, 0.6F);
				}
			}
			case "unstable_portal" -> {
				Vec3 spot = aim(level, player, value(player, skill, "RANGE"));
				Zones.add(new Zones.Zone(level, spot, value(player, skill, "SIZE") / 2.0, now + (int) value(player, skill, "DURATION"),
						null, value(player, skill, "DISPLACEMENT")));
				level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.4F, 1.6F);
			}
			case "portal" -> {
				Vec3 spot = aim(level, player, 16.0);
				if (!secondPortal) {
					s.portalEntrance = spot;
					s.endurance -= cost;
					s.lastUse = now;
					s.dirty = true;
					Zones.add(new Zones.Zone(level, spot, 0.0, now + 600, null, 0));
					level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.PORTAL_AMBIENT, SoundSource.PLAYERS, 0.5F, 1.4F);
					return; // the exit is placed with the next use; the cooldown starts then
				}
				int until = now + (int) value(player, skill, "DURATION");
				Zones.add(new Zones.Zone(level, s.portalEntrance, 1.0, until, spot, 0));
				Zones.add(new Zones.Zone(level, spot, 0.0, until, null, 0));
				s.portalEntrance = null;
				cost = 0;
				level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.4F, 1.6F);
			}
			default -> {
				if (TOGGLES.contains(skill)) {
					s.activeUntil.put(skill, Integer.MAX_VALUE);
					s.endurance -= cost;
					s.lastUse = now;
					s.dirty = true;
					return; // the cooldown starts when switched off
				}
				done = DefenseAbilities.use(player, skill) || OffenseAbilities.use(player, skill);
			}
		}
		if (!done) {
			return;
		}
		s.endurance -= cost;
		s.lastUse = now;
		double cooldown = value(player, skill, "COOLDOWN");
		if (skill.equals("heal_other") && cooldown < 20) {
			cooldown = value(player, "heal_self", "COOLDOWN"); // the original config has percentages here
		}
		if (active(s, "hasten") && !skill.equals("hasten")) {
			cooldown *= 1.0 - Math.min(0.95, value(player, "hasten", "REDUCTION"));
		}
		if (cooldown >= 1) {
			s.cooldownUntil.put(skill, now + (int) cooldown);
		}
		s.dirty = true;
	}

	private static void effect(ServerLevel level, ServerPlayer player, net.minecraft.core.particles.SimpleParticleType particle, SoundEvent sound) {
		level.sendParticles(particle, player.getX(), player.getY(0.3), player.getZ(), 14, 0.3, 0.3, 0.3, 0.02);
		level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 0.7F, 1.2F);
	}

	/** Point the player aims at (a block, or the end of the range). */
	public static Vec3 aim(ServerLevel level, ServerPlayer player, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(range));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i()).scale(0.3));
	}

	/** Moves the player along a direction until something solid, to the nearest spot where they fit. */
	private static boolean teleport(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 dir, double range) {
		Vec3 end = from.add(dir.scale(range));
		BlockHitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double distance = hit.getType() == HitResult.Type.MISS ? range : from.distanceTo(hit.getLocation()) - 0.4;
		Vec3 base = player.position();
		Vec3 offset = from.subtract(base);
		for (double d = distance; d > 0.5; d -= 0.5) {
			Vec3 feet = from.add(dir.scale(d)).subtract(0, offset.y > 1 ? 0.2 : offset.y, 0);
			for (double up = 0; up <= 1.5; up += 0.5) {
				Vec3 spot = feet.add(0, up, 0);
				AABB box = player.getBoundingBox().move(spot.subtract(base));
				if (level.noCollision(player, box)) {
					level.sendParticles(ParticleTypes.PORTAL, base.x, base.y + 1, base.z, 30, 0.3, 0.6, 0.3, 0.3);
					level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.0F);
					if (player.isPassenger()) {
						player.stopRiding();
					}
					player.teleportTo(spot.x, spot.y, spot.z);
					player.resetFallDistance();
					level.sendParticles(ParticleTypes.PORTAL, spot.x, spot.y + 1, spot.z, 30, 0.3, 0.6, 0.3, 0.3);
					level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.0F);
					return true;
				}
			}
		}
		return false;
	}

	/** The living entity the player looks at. */
	public static LivingEntity lookTarget(ServerPlayer player, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(range));
		BlockHitResult wall = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double best = wall.getType() == HitResult.Type.MISS ? range * range : eye.distanceToSqr(wall.getLocation());
		LivingEntity found = null;
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(1.0),
				x -> x != player && x.isAlive() && !x.isSpectator())) {
			var hit = e.getBoundingBox().inflate(0.3).clip(eye, end);
			if (hit.isPresent() && eye.distanceToSqr(hit.get()) < best) {
				best = eye.distanceToSqr(hit.get());
				found = e;
			}
		}
		return found;
	}

	private static void smashLand(ServerPlayer player, State s) {
		ServerLevel level = (ServerLevel) player.level();
		double fallen = s.smashStartY - player.getY();
		float damage = (float) (Math.max(0, fallen - 3.0) * (1.0 + AttributeEffects.modifier(player, "ability_power")));
		double range = value(player, "smash", "RANGE");
		int stun = (int) value(player, "smash", "STUN");
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range, 1.5, range),
				x -> x != player && x.isAlive() && !x.isSpectator())) {
			damage(player, e, damage);
			stun(e, stun);
		}
		level.sendParticles(ParticleTypes.EXPLOSION, player.getX(), player.getY() + 0.2, player.getZ(), 6, range / 2, 0.1, range / 2, 0);
		level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.2, player.getZ(), 40, range / 2, 0.1, range / 2, 0.05);
		level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 0.8F);
	}

	/** Stun (until the status effects phase: can't move and hits for almost nothing). */
	public static void stun(LivingEntity target, int ticks) {
		target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 10, false, true, true));
		target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 10, false, false, true));
	}

	/** Damage dealt by an ability. */
	public static void damage(ServerPlayer player, LivingEntity target, float amount) {
		if (amount <= 0) {
			return;
		}
		skillDamage = true;
		try {
			target.hurtServer((ServerLevel) player.level(), player.damageSources().indirectMagic(player, player), amount);
		} finally {
			skillDamage = false;
		}
	}

	/** Called when something dies: monsters killed by an ability may drop an Ender Token. */
	public static void onDeath(LivingEntity entity) {
		EsSettings settings = EsSettings.get();
		if (skillDamage && settings.enderTokenDrops && entity instanceof Enemy && entity.level() instanceof ServerLevel level
				&& level.getRandom().nextDouble() < settings.enderTokenDropRate) {
			level.addFreshEntity(new ItemEntity(level, entity.getX(), entity.getY() + 0.5, entity.getZ(), new ItemStack(ModContent.TOKEN)));
		}
	}

	/** Called after a player took damage: Invisibility breaks. */
	public static void onHurt(ServerPlayer player) {
		State s = state(player);
		if (active(s, "invisibility")) {
			s.activeUntil.remove("invisibility");
			player.removeEffect(MobEffects.INVISIBILITY);
			s.dirty = true;
		}
	}
}
