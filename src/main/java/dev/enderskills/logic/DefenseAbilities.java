package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.enderskills.registry.ModContent;

/** The Defense class: Light, Earth, Electric and Fire trees. */
public final class DefenseAbilities {
	/** Damage over time: per second, until a tick, from whom. */
	private record Dot(ServerPlayer owner, float perSecond, int until) {
	}

	private record Timed(ServerPlayer owner, int until, double value) {
	}

	private record Ring(ServerLevel level, Vec3 center, double radius, int until, ServerPlayer owner, double dot, int dotTicks) {
	}

	private record Golem(IronGolem golem, ServerPlayer owner, int until, int stun, double mirror) {
	}

	private static final Map<LivingEntity, List<Dot>> DOTS = new WeakHashMap<>();
	private static final Map<Mob, Timed> CHARMED = new WeakHashMap<>();
	private static final Map<Mob, Timed> TAUNTED = new WeakHashMap<>();
	private static final Map<LivingEntity, Timed> INVINCIBLE = new WeakHashMap<>();
	private static final Map<LivingEntity, Timed> FIRE_IMMUNE = new WeakHashMap<>();
	private static final Map<LivingEntity, Timed> BOOSTED = new WeakHashMap<>();
	private static final Map<Mob, Timed> PULLED = new WeakHashMap<>();
	private static final Map<UUID, Timed> SENTINELS = new HashMap<>();
	private static final List<Ring> RINGS = new ArrayList<>();
	private static final List<Golem> GOLEMS = new ArrayList<>();
	private static final Map<UUID, Integer> FLARE_NEXT = new HashMap<>();
	private static final Map<UUID, Integer> STAR_NEXT = new HashMap<>();
	private static int now;

	private DefenseAbilities() {
	}

	// ------------------------------------------------------------------ helpers

	/** Players (and their tamed pets) are on the same side; everything else is an enemy. */
	public static boolean isAlly(ServerPlayer caster, Entity e) {
		return e == caster || e instanceof Player || (e instanceof OwnableEntity o && o.getOwner() == caster)
				|| (e instanceof IronGolem g && GOLEMS.stream().anyMatch(x -> x.golem() == g));
	}

	private static List<LivingEntity> around(ServerPlayer p, Vec3 center, double range, boolean allies) {
		return p.level().getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(range),
				e -> e.isAlive() && !e.isSpectator() && e.distanceToSqr(center) <= range * range && isAlly(p, e) == allies);
	}

	private static double v(ServerPlayer p, String skill, String property) {
		return AbilityManager.value(p, skill, property);
	}

	private static void fx(ServerLevel level, Vec3 at, SimpleParticleType particle, int count, double spread, SoundEvent sound, float pitch) {
		level.sendParticles(particle, at.x, at.y + 1.0, at.z, Vfx.n(count + count / 2), spread, 0.6, spread, 0.05);
		level.sendParticles(Vfx.halo(particle), at.x, at.y + 1.0, at.z, Vfx.n(count / 2 + 2), spread + 0.2, 0.7, spread + 0.2, 0.01);
		if (spread >= 1.0) {
			Vfx.shockwave(level, at, spread * 2, particle);
		}
		if (sound != null) {
			level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.8F, pitch);
		}
	}

	private static void heal(LivingEntity e, double percentOfMax) {
		e.heal((float) (e.getMaxHealth() * percentOfMax));
	}

	/** Pushes an entity away from a point (negative force pulls). */
	private static void push(LivingEntity e, Vec3 from, double force) {
		Vec3 d = e.position().subtract(from);
		d = new Vec3(d.x, 0, d.z);
		d = d.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 0) : d.normalize();
		e.setDeltaMovement(e.getDeltaMovement().add(d.x * force * 0.5, force > 0 ? 0.3 : 0.1, d.z * force * 0.5));
		e.hurtMarked = true;
	}

	/** Sets an entity on fire with a damage over time (Overheat makes it stronger). */
	public static void ignite(ServerPlayer owner, LivingEntity target, double totalDamage, int ticks) {
		if (AbilityManager.isActive(owner, "overheat")) {
			totalDamage *= 1.0 + v(owner, "overheat", "DAMAGE");
		}
		target.igniteForSeconds(Math.max(1, ticks / 20F));
		dot(owner, target, totalDamage * AbilityManager.power(owner), ticks);
	}

	public static void dot(ServerPlayer owner, LivingEntity target, double totalDamage, int ticks) {
		if (ticks <= 0 || totalDamage <= 0) {
			return;
		}
		DOTS.computeIfAbsent(target, t -> new ArrayList<>()).add(new Dot(owner, (float) (totalDamage * 20.0 / ticks), now + ticks));
	}

	/** Ability power boost from Power Boost (added to the Ability Power attribute). */
	public static double boost(LivingEntity e) {
		Timed t = BOOSTED.get(e);
		return t != null && t.until() > now ? t.value() : 0.0;
	}

	// ------------------------------------------------------------------ using

	/** Runs a Defense ability; false when nothing happened (no target...). */
	public static boolean use(ServerPlayer p, String skill) {
		ServerLevel level = (ServerLevel) p.level();
		double power = AbilityManager.power(p);
		Vec3 pos = p.position();
		switch (skill) {
			// ---------------- Light
			case "charm" -> {
				LivingEntity t = AbilityManager.lookTarget(p, v(p, skill, "RANGE"));
				if (!(t instanceof Mob mob) || isAlly(p, t)) {
					return false;
				}
				CHARMED.put(mob, new Timed(p, now + (int) v(p, skill, "DURATION"), 0));
				mob.setTarget(null);
				fx(level, mob.position(), ParticleTypes.HEART, 8, 0.4, SoundEvents.ALLAY_AMBIENT_WITH_ITEM, 1.2F);
			}
			case "heal_aura" -> {
				for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), true)) {
					heal(e, v(p, skill, "HEAL") * power);
					fx(level, e.position(), ParticleTypes.HAPPY_VILLAGER, 10, 0.4, null, 1);
				}
				fx(level, pos, ParticleTypes.END_ROD, 30, v(p, skill, "RANGE") / 2, SoundEvents.AMETHYST_BLOCK_CHIME, 1.4F);
			}
			case "power_boost" -> {
				LivingEntity t = AbilityManager.lookTarget(p, v(p, skill, "RANGE"));
				LivingEntity target = t != null && isAlly(p, t) ? t : p;
				BOOSTED.put(target, new Timed(p, now + (int) v(p, skill, "DURATION"), v(p, skill, "POWER")));
				fx(level, target.position(), ParticleTypes.ENCHANT, 30, 0.4, SoundEvents.BEACON_POWER_SELECT, 1.4F);
			}
			case "heal_other" -> {
				LivingEntity t = AbilityManager.lookTarget(p, v(p, skill, "RANGE"));
				if (t == null || t == p || !isAlly(p, t)) {
					return false;
				}
				heal(t, v(p, skill, "HEAL") * power);
				fx(level, t.position(), ParticleTypes.HEART, 8, 0.4, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F);
			}
			case "heal_self" -> {
				heal(p, v(p, skill, "HEAL") * power);
				fx(level, pos, ParticleTypes.HEART, 8, 0.4, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F);
			}
			case "nearby_invincibility" -> {
				int until = now + (int) v(p, skill, "DURATION");
				for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), true)) {
					INVINCIBLE.put(e, new Timed(p, until, 0));
					fx(level, e.position(), ParticleTypes.TOTEM_OF_UNDYING, 20, 0.4, null, 1);
				}
				level.playSound(null, p.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 1.0F, 1.3F);
			}
			// ---------------- Earth
			case "taunt" -> {
				int until = now + (int) v(p, skill, "DURATION");
				for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), false)) {
					if (e instanceof Mob mob) {
						TAUNTED.put(mob, new Timed(p, until, 0));
						mob.setTarget(p);
						fx(level, e.position(), ParticleTypes.ANGRY_VILLAGER, 3, 0.3, null, 1);
					}
				}
				level.playSound(null, p.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.8F, 1.2F);
			}
			case "wall", "dome" -> {
				if (!EarthStructures.build(p, skill)) {
					return false;
				}
				level.playSound(null, p.blockPosition(), SoundEvents.STONE_PLACE, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 1.0F, 0.6F);
			}
			case "thorny" -> {
				AbilityManager.setActive(p, skill, (int) v(p, skill, "DURATION"));
				fx(level, pos, ParticleTypes.CRIT, 20, 0.5, SoundEvents.SWEET_BERRY_BUSH_PLACE, 0.8F);
			}
			case "shockwave" -> {
				int stun = (int) v(p, skill, "STUN");
				double force = v(p, skill, "FORCE");
				for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), false)) {
					push(e, pos, force);
					AbilityManager.stun(e, stun);
				}
				fx(level, pos, ParticleTypes.EXPLOSION, 6, v(p, skill, "RANGE") / 2, SoundEvents.GENERIC_EXPLODE.value(), 1.2F);
			}
			case "animated_stone_golem" -> {
				IronGolem golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.MOB_SUMMONED);
				if (golem == null) {
					return false;
				}
				Vec3 at = pos.add(p.getViewVector(1.0F).multiply(2, 0, 2));
				golem.snapTo(at.x, pos.y, at.z, p.getYRot(), 0);
				golem.setPlayerCreated(true);
				double health = v(p, skill, "HEALTH") * power;
				golem.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
				golem.setHealth((float) health);
				golem.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(v(p, skill, "DAMAGE") * power);
				level.addFreshEntity(golem);
				GOLEMS.add(new Golem(golem, p, now + (int) v(p, skill, "DURATION"), (int) v(p, skill, "STUN"), v(p, skill, "DAMAGE_MIRROR")));
				fx(level, at, ParticleTypes.CLOUD, 20, 0.6, SoundEvents.IRON_GOLEM_REPAIR, 0.7F);
			}
			// ---------------- Electric
			case "electric_pulse" -> {
				int stun = (int) v(p, skill, "STUN");
				for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), false)) {
					push(e, pos, v(p, skill, "FORCE"));
					slow(e, stun);
					fx(level, e.position(), ParticleTypes.ELECTRIC_SPARK, 15, 0.3, null, 1);
				}
				fx(level, pos, ParticleTypes.ELECTRIC_SPARK, 40, v(p, skill, "RANGE") / 2, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.8F);
			}
			case "magnetic_pull" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				int until = now + (int) v(p, skill, "DURATION");
				int stun = (int) v(p, skill, "STUN");
				boolean any = false;
				for (LivingEntity e : around(p, at, v(p, skill, "SIZE"), false)) {
					if (e instanceof Mob mob) {
						PULLED.put(mob, new Timed(p, until, v(p, skill, "FORCE")));
					} else {
						push(e, pos, -v(p, skill, "FORCE") * 2);
					}
					slow(e, stun);
					any = true;
				}
				fx(level, at, ParticleTypes.ELECTRIC_SPARK, 30, v(p, skill, "SIZE") / 2, SoundEvents.BEACON_DEACTIVATE, 1.6F);
				return any;
			}
			case "power_drain" -> {
				int stun = (int) v(p, skill, "STUN");
				double gained = 0;
				for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), false)) {
					AbilityManager.stun(e, stun);
					gained += v(p, skill, "POWER");
					fx(level, e.position(), ParticleTypes.ELECTRIC_SPARK, 15, 0.3, null, 1);
				}
				if (gained <= 0) {
					return false;
				}
				AbilityManager.addEndurance(p, gained);
				level.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.8F, 0.6F);
			}
			case "energize" -> {
				LivingEntity t = AbilityManager.lookTarget(p, v(p, skill, "RANGE"));
				if (!(t instanceof ServerPlayer other)) {
					return false;
				}
				double amount = Math.min(v(p, skill, "POWER"), AbilityManager.endurance(p));
				AbilityManager.addEndurance(p, -amount);
				AbilityManager.addEndurance(other, amount);
				fx(level, other.position(), ParticleTypes.ELECTRIC_SPARK, 30, 0.4, SoundEvents.BEACON_POWER_SELECT, 1.6F);
			}
			case "voltaic_sentinel" -> {
				SENTINELS.put(p.getUUID(), new Timed(p, now + (int) v(p, skill, "DURATION"), 0));
				AbilityManager.setActive(p, skill, (int) v(p, skill, "DURATION"));
				fx(level, pos, ParticleTypes.ELECTRIC_SPARK, 40, 0.5, SoundEvents.BEACON_ACTIVATE, 1.8F);
			}
			// ---------------- Fire
			case "ring_of_fire" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				RINGS.add(new Ring(level, at, v(p, skill, "RING_RANGE"), now + (int) v(p, skill, "RING_DURATION"), p,
						v(p, skill, "DOT"), (int) v(p, skill, "DOT_DURATION")));
				level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 1.0F, 0.8F);
			}
			case "overheat" -> {
				AbilityManager.setActive(p, skill, (int) v(p, skill, "DURATION"));
				fx(level, pos, ParticleTypes.FLAME, 30, 0.5, SoundEvents.BLAZE_AMBIENT, 1.0F);
			}
			case "warm_heart" -> {
				int ticks = (int) v(p, skill, "HEAL_DURATION");
				double total = p.getMaxHealth() * v(p, skill, "HEAL") * power;
				// heal the total over the duration
				AbilityManager.setActive(p, skill, ticks);
				HEALS.put(p, new Timed(p, now + ticks, total * 20.0 / Math.max(1, ticks)));
				fx(level, pos, ParticleTypes.HEART, 6, 0.4, SoundEvents.BLAZE_SHOOT, 1.6F);
			}
			case "home_star" -> {
				int duration = (int) v(p, skill, "DURATION");
				AbilityManager.setActive(p, skill, duration);
				STAR_NEXT.put(p.getUUID(), now);
				for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), true)) {
					FIRE_IMMUNE.put(e, new Timed(p, now + duration, 0));
					AbilityManager.status(e, null, MobEffects.FIRE_RESISTANCE, duration, 0);
				}
				fx(level, pos, ParticleTypes.FLAME, 60, v(p, skill, "RANGE") / 2, SoundEvents.BLAZE_AMBIENT, 0.6F);
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private static final Map<LivingEntity, Timed> HEALS = new WeakHashMap<>();

	private static void slow(LivingEntity e, int ticks) {
		AbilityManager.status(e, dev.enderskills.registry.ModEffects.ELECTRIFIED, MobEffects.SLOWNESS, ticks, 2);
	}

	private static void raise(ServerLevel level, ServerPlayer p, BlockPos column, int height, int duration, double force) {
		for (int y = 0; y < height; y++) {
			place(level, column.above(y), duration);
		}
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(column).expandTowards(0, height, 0))) {
			if (!isAlly(p, e)) {
				e.setDeltaMovement(e.getDeltaMovement().add(0, force * 0.15, 0));
				e.hurtMarked = true;
			}
		}
	}

	private static void place(ServerLevel level, BlockPos pos, int duration) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.canBeReplaced()) {
			level.setBlockAndUpdate(pos, ModContent.EARTH.defaultBlockState());
			level.scheduleTick(pos, ModContent.EARTH, Math.max(20, duration));
		}
	}

	// ------------------------------------------------------------------ toggles (auras)

	/** Called every tick for each player with an aura switched on. */
	static void aura(ServerPlayer p, String skill) {
		Vfx.skill = skill;
		ServerLevel level = (ServerLevel) p.level();
		Vec3 pos = p.position();
		switch (skill) {
			case "shocking_aura" -> {
				if (now % 10 == 0) {
					for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), false)) {
						slow(e, 40);
						level.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY(0.5), e.getZ(), 3, 0.3, 0.3, 0.3, 0.05);
					}
					level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 1, pos.z, 4, 0.6, 0.6, 0.6, 0.05);
				}
			}
			case "blazing_aura" -> {
				if (now % 10 == 0) {
					for (LivingEntity e : around(p, pos, v(p, skill, "RANGE"), false)) {
						if (e.getRemainingFireTicks() <= 0) {
							ignite(p, e, v(p, skill, "DOT"), (int) v(p, skill, "DOT_DURATION"));
						}
					}
					level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 0.5, pos.z, 4, 0.6, 0.4, 0.6, 0.01);
				}
			}
			case "flares" -> {
				int next = FLARE_NEXT.getOrDefault(p.getUUID(), 0);
				if (now >= next) {
					FLARE_NEXT.put(p.getUUID(), now + (int) Math.max(5, v(p, skill, "INTERVAL")));
					List<LivingEntity> enemies = around(p, pos, v(p, skill, "RANGE"), false);
					if (!enemies.isEmpty()) {
						LivingEntity t = enemies.get(level.getRandom().nextInt(enemies.size()));
						line(level, p.getEyePosition(), t.getEyePosition(), ParticleTypes.FLAME);
						AbilityManager.damage(p, t, (float) (v(p, skill, "DAMAGE") * AbilityManager.power(p)));
						t.igniteForSeconds(2);
						level.playSound(null, t.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.5F, 1.4F);
					}
				}
			}
			default -> {
			}
		}
	}

	private static void line(ServerLevel level, Vec3 from, Vec3 to, SimpleParticleType particle) {
		Vfx.beam(level, from, to, particle, 0.3);
	}

	// ------------------------------------------------------------------ tick

	static void tick(int tick) {
		now = tick;
		// damage over time, once a second
		if (now % 20 == 0) {
			for (Iterator<Map.Entry<LivingEntity, List<Dot>>> it = DOTS.entrySet().iterator(); it.hasNext();) {
				Map.Entry<LivingEntity, List<Dot>> e = it.next();
				e.getValue().removeIf(d -> d.until() < now);
				LivingEntity target = e.getKey();
				if (e.getValue().isEmpty() || !target.isAlive()) {
					it.remove();
					continue;
				}
				for (Dot d : List.copyOf(e.getValue())) {
					AbilityManager.damage(d.owner(), target, d.perSecond());
				}
			}
			for (Iterator<Map.Entry<LivingEntity, Timed>> it = HEALS.entrySet().iterator(); it.hasNext();) {
				Map.Entry<LivingEntity, Timed> e = it.next();
				if (e.getValue().until() < now || !e.getKey().isAlive()) {
					it.remove();
				} else {
					e.getKey().heal((float) e.getValue().value());
				}
			}
		}
		CHARMED.entrySet().removeIf(e -> e.getValue().until() <= now || !e.getKey().isAlive());
		CHARMED.forEach((mob, t) -> {
			mob.setTarget(null);
			if (now % 10 == 0) {
				mob.getNavigation().moveTo(t.owner(), 1.0);
				((ServerLevel) mob.level()).sendParticles(ParticleTypes.HEART, mob.getX(), mob.getY(1.0) + 0.3, mob.getZ(), 1, 0.2, 0.1, 0.2, 0);
			}
		});
		TAUNTED.entrySet().removeIf(e -> e.getValue().until() <= now || !e.getKey().isAlive() || !e.getValue().owner().isAlive());
		TAUNTED.forEach((mob, t) -> {
			if (mob.getTarget() != t.owner()) {
				mob.setTarget(t.owner());
			}
		});
		PULLED.entrySet().removeIf(e -> e.getValue().until() <= now || !e.getKey().isAlive());
		PULLED.forEach((mob, t) -> {
			Vec3 d = t.owner().position().subtract(mob.position());
			if (d.lengthSqr() > 4) {
				Vec3 n = d.normalize().scale(t.value() * 0.12);
				mob.setDeltaMovement(mob.getDeltaMovement().add(n.x, 0.0, n.z));
			}
		});
		INVINCIBLE.entrySet().removeIf(e -> e.getValue().until() <= now);
		FIRE_IMMUNE.entrySet().removeIf(e -> e.getValue().until() <= now);
		BOOSTED.entrySet().removeIf(e -> e.getValue().until() <= now);

		// voltaic sentinel: zaps the nearest enemy every second
		Vfx.skill = "voltaic_sentinel";
		for (Iterator<Timed> it = SENTINELS.values().iterator(); it.hasNext();) {
			Timed t = it.next();
			ServerPlayer p = t.owner();
			if (t.until() <= now || !p.isAlive()) {
				it.remove();
				continue;
			}
			ServerLevel level = (ServerLevel) p.level();
			Vec3 orb = p.position().add(0, 2.6, 0);
			if (now % 5 == 0) {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, orb.x, orb.y, orb.z, 3, 0.15, 0.15, 0.15, 0.02);
			}
			if (now % 20 == 0) {
				LivingEntity best = null;
				for (LivingEntity e : around(p, p.position(), v(p, "voltaic_sentinel", "RANGE"), false)) {
					if (best == null || e.distanceToSqr(p) < best.distanceToSqr(p)) {
						best = e;
					}
				}
				if (best != null) {
					line(level, orb, best.getEyePosition(), ParticleTypes.ELECTRIC_SPARK);
					AbilityManager.damage(p, best, (float) (v(p, "voltaic_sentinel", "DAMAGE") * AbilityManager.power(p)));
					slow(best, 40);
					level.playSound(null, best.blockPosition(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.3F, 2.0F);
				}
			}
		}

		// ring of fire: enemies inside can't leave and burn
		Vfx.skill = "ring_of_fire";
		RINGS.removeIf(r -> r.until() <= now);
		for (Ring r : RINGS) {
			if (now % 4 == 0) {
				for (int i = 0; i < 16; i++) {
					double a = Math.PI * 2 * i / 16 + now * 0.05;
					r.level().sendParticles(ParticleTypes.FLAME, r.center().x + Math.cos(a) * r.radius(), r.center().y + 0.1,
							r.center().z + Math.sin(a) * r.radius(), 1, 0, 0.2, 0, 0.01);
				}
			}
			for (LivingEntity e : r.level().getEntitiesOfClass(LivingEntity.class, new AABB(r.center(), r.center()).inflate(r.radius() + 1.5, 3, r.radius() + 1.5),
					x -> x.isAlive() && !isAlly(r.owner(), x))) {
				Vec3 d = e.position().subtract(r.center());
				double dist = Math.sqrt(d.x * d.x + d.z * d.z);
				if (dist <= r.radius()) {
					if (e.getRemainingFireTicks() <= 0) {
						ignite(r.owner(), e, r.dot(), r.dotTicks());
					}
					if (dist > r.radius() - 0.8) { // keep them inside
						e.setDeltaMovement(-d.x / dist * 0.3, e.getDeltaMovement().y, -d.z / dist * 0.3);
						e.hurtMarked = true;
					}
				}
			}
		}

		// golems
		for (Iterator<Golem> it = GOLEMS.iterator(); it.hasNext();) {
			Golem g = it.next();
			if (g.until() <= now || !g.golem().isAlive()) {
				if (g.golem().isAlive()) {
					((ServerLevel) g.golem().level()).sendParticles(ParticleTypes.CLOUD, g.golem().getX(), g.golem().getY(1), g.golem().getZ(), 20, 0.5, 0.8, 0.5, 0.02);
					g.golem().discard();
				}
				it.remove();
			} else if (g.golem().getTarget() == null && now % 20 == 0 && g.owner().getLastHurtByMob() != null) {
				g.golem().setTarget(g.owner().getLastHurtByMob());
			}
		}

		// home star: heat waves from the caster
		Vfx.skill = "home_star";
		for (Iterator<Map.Entry<UUID, Integer>> it = STAR_NEXT.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Integer> e = it.next();
			ServerPlayer p = AbilityManager.player(e.getKey());
			if (p == null || !AbilityManager.isActive(p, "home_star")) {
				it.remove();
				continue;
			}
			if (now >= e.getValue()) {
				e.setValue(now + (int) Math.max(10, v(p, "home_star", "PULSE_INTERVAL")));
				ServerLevel level = (ServerLevel) p.level();
				for (LivingEntity ally : around(p, p.position(), v(p, "home_star", "RANGE"), true)) {
					for (LivingEntity enemy : around(p, ally.position(), v(p, "home_star", "PULSE_RANGE"), false)) {
						AbilityManager.damage(p, enemy, (float) (v(p, "home_star", "PULSE_DAMAGE") * AbilityManager.power(p)));
						ignite(p, enemy, v(p, "home_star", "PULSE_DOT"), (int) v(p, "home_star", "PULSE_DOT_DURATION"));
					}
					level.sendParticles(ParticleTypes.FLAME, ally.getX(), ally.getY(0.5), ally.getZ(), 30,
							v(p, "home_star", "PULSE_RANGE") / 2, 0.3, v(p, "home_star", "PULSE_RANGE") / 2, 0.02);
				}
				level.playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.6F, 0.6F);
			}
		}
	}

	// ------------------------------------------------------------------ damage hooks

	/** False cancels the hit (Nearby Invincibility, Home Star fire immunity). */
	public static boolean allowDamage(LivingEntity victim, DamageSource source) {
		Timed t = INVINCIBLE.get(victim);
		if (t != null && t.until() > now && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
		Timed f = FIRE_IMMUNE.get(victim);
		return f == null || f.until() <= now || !source.is(DamageTypeTags.IS_FIRE);
	}

	/** After a hit: Thorny strikes back, the stone golem stuns and mirrors damage. */
	public static void afterDamage(LivingEntity victim, DamageSource source, float taken) {
		Entity attacker = source.getEntity();
		if (victim instanceof ServerPlayer p && attacker instanceof LivingEntity a && source.getDirectEntity() == attacker
				&& AbilityManager.isActive(p, "thorny") && !AbilityManager.skillDamage) {
			AbilityManager.damage(p, a, (float) (v(p, "thorny", "DAMAGE") * AbilityManager.power(p)));
			((ServerLevel) p.level()).sendParticles(ParticleTypes.CRIT, a.getX(), a.getY(0.5), a.getZ(), 6, 0.3, 0.3, 0.3, 0.1);
		}
		if (attacker instanceof IronGolem golem && !AbilityManager.skillDamage) {
			for (Golem g : GOLEMS) {
				if (g.golem() == golem) {
					AbilityManager.stun(victim, g.stun());
					float mirror = (float) (g.owner().getAttributeValue(Attributes.ATTACK_DAMAGE) * g.mirror());
					AbilityManager.damage(g.owner(), victim, mirror);
				}
			}
		}
	}
}
