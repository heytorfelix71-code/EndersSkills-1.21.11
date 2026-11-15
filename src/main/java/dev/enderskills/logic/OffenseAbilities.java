package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The Offense class: Void, Blood, Wind, Fire, Light and Black Flame trees. */
public final class OffenseAbilities {
	/** A projectile simulated on the server (no entity): flies, hits living things, ends on a wall or at its range. */
	private static final class Bolt {
		ServerPlayer owner;
		ServerLevel level;
		Vec3 pos;
		Vec3 dir;
		double speed = 1.5;
		double left;
		double radius = 0.5;
		int pierce = 1;
		int wait;
		LivingEntity homing;
		ParticleOptions particle = ParticleTypes.CRIT;
		BiConsumer<LivingEntity, Vec3> onHit = (e, at) -> { };
		Consumer<Vec3> onEnd = at -> { };
		final Set<LivingEntity> hit = new HashSet<>();
		final String skill = Vfx.skill;
	}

	/** An area on the ground that does something to the enemies inside every few ticks. */
	private record Zone(ServerPlayer owner, ServerLevel level, Vec3 center, double radius, int until, int every,
			ParticleOptions particle, Consumer<LivingEntity> action, String skill) {
	}

	private record Delayed(int at, Runnable run, String skill) {
	}

	/** A cast that lasts a few ticks (breath, punches, volleys, beams). */
	private record Channel(ServerPlayer owner, int until, int every, Runnable step, String skill) {
	}

	private static final List<Bolt> BOLTS = new ArrayList<>();
	private static final List<Zone> ZONES = new ArrayList<>();
	private static final List<Delayed> DELAYED = new ArrayList<>();
	private static final List<Channel> CHANNELS = new ArrayList<>();
	private static final List<Bolt> NEW_BOLTS = new ArrayList<>();
	private static int now;

	private OffenseAbilities() {
	}

	// ------------------------------------------------------------------ helpers

	private static double v(ServerPlayer p, String skill, String property) {
		return AbilityManager.value(p, skill, property);
	}

	private static boolean enemy(ServerPlayer p, LivingEntity e) {
		return e.isAlive() && !e.isSpectator() && !DefenseAbilities.isAlly(p, e) && DefenseAbilities.worthHitting(p, e);
	}

	private static List<LivingEntity> enemies(ServerPlayer p, Vec3 center, double radius) {
		return p.level().getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius),
				e -> enemy(p, e) && e.distanceToSqr(center) <= radius * radius);
	}

	/** Ability damage: a flat part and a part in percent of the target's max health ("Damage+"). */
	private static void hit(ServerPlayer p, LivingEntity t, double damage, double truePercent) {
		Fx.hit(t);
		// the percent-of-max-health part is not multiplied by the power (it would melt bosses)
		AbilityManager.damage(p, t, (float) (damage * AbilityManager.power(p) + Math.min(0.25, truePercent) * t.getMaxHealth()));
	}

	private static void bleed(LivingEntity t, double ticks) {
		if (t.level() instanceof ServerLevel level && level.getRandom().nextInt(3) == 0) {
			Environment.place(level, t.blockPosition(), dev.enderskills.registry.ModContent.BLOOD);
		}
		AbilityManager.status(t, dev.enderskills.registry.ModEffects.BLEEDING, null, (int) Math.max(20, ticks), 0);
	}

	private static void dot(ServerPlayer p, LivingEntity t, double dot, double truePercent, double ticks) {
		Fx.hit(t);
		if (truePercent > 0 || Vfx.skill.startsWith("black_")) {
			Environment.place((ServerLevel) p.level(), t.blockPosition(), dev.enderskills.registry.ModContent.BLACK_FIRE);
		}
		if (truePercent > 0) {
			AbilityManager.status(t, dev.enderskills.registry.ModEffects.BLACK_FLAME, null, (int) Math.max(20, ticks), 0);
		}
		DefenseAbilities.dot(p, t, dot * AbilityManager.power(p) + Math.min(0.25, truePercent) * t.getMaxHealth(), (int) ticks);
	}

	private static void burn(ServerPlayer p, LivingEntity t, String skill) {
		Fx.hit(t);
		DefenseAbilities.ignite(p, t, v(p, skill, "DOT") + v(p, skill, "TRUE_DOT") * t.getMaxHealth(), (int) v(p, skill, "DOT_DURATION"));
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * volume, pitch);
	}

	private static void burst(ServerLevel level, Vec3 at, ParticleOptions particle, int count, double spread) {
		level.sendParticles(particle, at.x, at.y + 0.5, at.z, Vfx.n(count + count / 2), spread, spread / 2, spread, 0.05);
		level.sendParticles(Vfx.halo(particle), at.x, at.y + 0.5, at.z, Vfx.n(count / 2 + 2), spread + 0.2, spread / 2 + 0.1, spread + 0.2, 0.01);
		if (spread >= 0.6) {
			Vfx.shockwave(level, at, Math.max(1.5, spread * 2), particle);
		}
	}

	/** Light skills: Glowing; hitting an already glowing target makes it burst (original "Glowing" effect). */
	private static void glow(ServerPlayer p, LivingEntity t) {
		if (t.hasEffect(MobEffects.GLOWING)) {
			t.removeEffect(MobEffects.GLOWING);
			for (LivingEntity e : enemies(p, t.position(), 2.0)) {
				hit(p, e, 16.0 * 0.25, 0);
			}
			burst((ServerLevel) p.level(), t.position(), ParticleTypes.END_ROD, 20, 0.6);
		} else {
			t.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false, true));
		}
	}

	private static Bolt bolt(ServerPlayer p, Vec3 dir, double range) {
		Bolt b = new Bolt();
		b.owner = p;
		b.level = (ServerLevel) p.level();
		b.pos = p.getEyePosition().add(0, -0.2, 0);
		b.dir = dir.normalize();
		b.left = range;
		NEW_BOLTS.add(b);
		return b;
	}

	private static Bolt bolt(ServerPlayer p, double range) {
		return bolt(p, p.getViewVector(1.0F), range);
	}

	private static void zone(ServerPlayer p, Vec3 center, double radius, int duration, int every, ParticleOptions particle,
			Consumer<LivingEntity> action) {
		duration = (int) Math.max(1, duration * Math.max(0.1, dev.enderskills.data.EsSettings.get().areaDurationMultiplier));
		ZONES.add(new Zone(p, (ServerLevel) p.level(), center, radius, now + Math.max(1, duration), Math.max(1, every), particle, action, Vfx.skill));
		Fx.zone(p, center, radius, Math.max(1, duration));
	}

	/** Extra radius from the level: nothing at the start, the configured top bonus at the last level (grows with the square). */
	private static double holeBonus(ServerPlayer p, String skill) {
		int cap = Math.max(1, Math.min(50, dev.enderskills.dsl.SkillConfigs.maxLevel(skill)));
		double f = Math.min(1.0, Math.max(0, Progression.data(p).level(skill)) / (double) cap);
		return Math.max(0.0, Math.min(1000.0, dev.enderskills.data.EsSettings.get().blackHoleTopBonus)) * f * f;
	}

	/** Ticks of the last Black Hole cast (its cooldown starts when it is gone). */
	public static int lastHoleTicks;

	private static boolean pullable(ServerPlayer p, net.minecraft.world.entity.Entity x) {
		if (x instanceof net.minecraft.world.entity.player.Player || x instanceof net.minecraft.world.entity.decoration.ArmorStand
				|| x instanceof dev.enderskills.entity.ShadowEntity) {
			return false;
		}
		if (x instanceof LivingEntity) {
			return !DefenseAbilities.isAlly(p, x);
		}
		return x instanceof net.minecraft.world.entity.item.ItemEntity || x instanceof net.minecraft.world.entity.ExperienceOrb
				|| x instanceof net.minecraft.world.entity.projectile.Projectile;
	}

	/** One tick of a Black Hole: pulls everything around it, hurts the mobs once a second, tears blocks out of the ground. */
	private static void blackHoleTick(ServerPlayer p, ServerLevel level, Vec3 at, double reach, double r, double damage, int[] age,
			List<net.minecraft.world.entity.item.FallingBlockEntity> bag, boolean last) {
		age[0]++;
		dev.enderskills.data.EsSettings cfg = dev.enderskills.data.EsSettings.get();
		for (net.minecraft.world.entity.Entity x : level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(at, at).inflate(reach),
				x -> x != p && x.isAlive() && !x.isSpectator() && x.distanceToSqr(at) <= reach * reach && pullable(p, x))) {
			Vec3 d = at.subtract(x.position().add(0, x.getBbHeight() * 0.5, 0));
			double dist = d.length();
			if (dist < 0.4) {
				continue;
			}
			double f = 0.14 + Math.min(0.5, reach * 0.004) + 0.12 * (1.0 - Math.min(1.0, dist / reach));
			Vec3 pull = d.scale(1.0 / dist).scale(f);
			if (x instanceof LivingEntity) {
				x.setDeltaMovement(x.getDeltaMovement().scale(0.7).add(pull.scale(1.4)));
			} else {
				x.setDeltaMovement(x.getDeltaMovement().scale(0.8).add(pull));
			}
			x.hurtMarked = true;
			if (x instanceof net.minecraft.world.entity.projectile.Projectile && dist < 1.0) {
				x.discard(); // swallowed
			}
		}
		if (age[0] % 10 == 1) {
			for (LivingEntity e : enemies(p, at, reach)) {
				hit(p, e, damage * Math.max(0.0, cfg.blackHoleDamagePerSecond) * 0.5, 0); // continuous damage: twice a second
			}
		}
		if (cfg.destroyEnvironment) {
			carve(p, level, at, reach, cfg, age, bag, last);
		}
		for (java.util.Iterator<net.minecraft.world.entity.item.FallingBlockEntity> it = bag.iterator(); it.hasNext();) {
			net.minecraft.world.entity.item.FallingBlockEntity fb = it.next();
			Vec3 d = at.subtract(fb.position());
			double dist = d.length();
			if (!fb.isAlive() || last || dist < 1.3 || fb.tickCount > 200) {
				fb.discard(); // swallowed by the hole: the ground stays torn (a crater)
				it.remove();
				continue;
			}
			fb.setDeltaMovement(d.scale(1.0 / dist).scale(Math.min(1.6, (0.3 + dist * 0.03) * Math.max(0.1, cfg.blackHoleSuctionSpeed))));
			fb.hurtMarked = true;
		}
	}

	/** The crater of a Black Hole: its columns, worked out once, from the middle outwards. */
	private static final class Crater {
		int[] x;
		int[] z;
		int[] top;
		int[] depth;
		int n;
		int cursor;
		int removed;
		int budget;
	}

	private static final java.util.Map<int[], Crater> CRATERS = new java.util.WeakHashMap<>();

	/**
	 * Carves the crater without a big cost: the columns are listed once (nearest to the middle first), each tick takes a few
	 * hundred blocks off the top of the next columns with cheap block changes (no neighbor updates), and only a handful of
	 * them are shown flying into the hole as falling blocks. Size and depth grow with the level of the ability.
	 */
	private static void carve(ServerPlayer p, ServerLevel level, Vec3 at, double reach, dev.enderskills.data.EsSettings cfg, int[] age,
			List<net.minecraft.world.entity.item.FallingBlockEntity> bag, boolean last) {
		Crater c = CRATERS.get(age);
		if (last) {
			CRATERS.remove(age);
			return;
		}
		if (c == null) {
			c = new Crater();
			CRATERS.put(age, c);
			int cap = Math.max(1, Math.min(50, dev.enderskills.dsl.SkillConfigs.maxLevel("black_hole")));
			double share = Math.min(1.0, Math.max(0, Progression.data(p).level("black_hole")) / (double) cap);
			int depthMax = 3 + (int) Math.round(Math.max(0, cfg.blackHoleCraterDepth - 3) * share);
			c.budget = (int) (Math.max(0, cfg.blackHoleMaxBlocks) * share * share) + 150;
			double rb = Math.min(Math.max(2.0, cfg.blackHoleMaxBlockRadius), Math.max(2.0, reach));
			// a bowl: depth D at the middle, nothing at the edge, so its volume is about pi * rc^2 * D / 2
			double rc = Math.max(2.0, Math.min(rb, Math.sqrt(c.budget / (Math.PI * depthMax / 2.0))));
			int ir = (int) Math.ceil(rc);
			int cap2 = (2 * ir + 1) * (2 * ir + 1);
			int[] xs = new int[cap2];
			int[] zs = new int[cap2];
			int[] tops = new int[cap2];
			int[] deps = new int[cap2];
			long[] order = new long[cap2];
			int n = 0;
			for (int dx = -ir; dx <= ir; dx++) {
				for (int dz = -ir; dz <= ir; dz++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					int bx = (int) Math.floor(at.x) + dx;
					int bz = (int) Math.floor(at.z) + dz;
					if (d > rc || !level.hasChunk(bx >> 4, bz >> 4)) {
						continue; // never loads chunks just to tear them
					}
					xs[n] = bx;
					zs[n] = bz;
					tops[n] = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz) - 1;
					double edge = d / rc;
					deps[n] = Math.max(1, (int) Math.round(depthMax * (1.0 - edge * edge)));
					order[n] = ((long) (int) ((d + level.getRandom().nextDouble() * 3.0) * 64) << 32) | n;
					n++;
				}
			}
			java.util.Arrays.sort(order, 0, n);
			c.x = new int[n];
			c.z = new int[n];
			c.top = new int[n];
			c.depth = new int[n];
			for (int i = 0; i < n; i++) {
				int k = (int) (order[i] & 0xFFFFFFFFL);
				c.x[i] = xs[k];
				c.z[i] = zs[k];
				c.top[i] = tops[k];
				c.depth[i] = deps[k];
			}
			c.n = n;
		}
		// spread what is left over about 80% of the life of the hole, never more than the per-tick limit
		double ticksLeft = Math.max(1.0, age[1] * 0.8 - age[0]);
		int perTick = (int) Math.min(Math.max(1, cfg.blackHoleBlocksPerTick), Math.max(20, Math.ceil((c.budget - c.removed) / ticksLeft)));
		int done = 0;
		int shown = 0;
		net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		while (c.cursor < c.n && done < perTick && c.removed < c.budget) {
			int i = c.cursor++;
			for (int y = c.top[i]; y > c.top[i] - c.depth[i] && y >= level.getMinY(); y--) {
				pos.set(c.x[i], y, c.z[i]);
				net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
				if (state.isAir() || state.getDestroySpeed(level, pos) < 0 || state.getBlock().getExplosionResistance() > 600.0F
						|| !state.getFluidState().isEmpty() || state.hasBlockEntity()
						|| state.getBlock() instanceof dev.enderskills.block.AltarBlock) {
					continue;
				}
				if (shown < 10 && bag.size() < 150 && level.getRandom().nextInt(6) == 0) {
					net.minecraft.world.entity.item.FallingBlockEntity fb = net.minecraft.world.entity.item.FallingBlockEntity.fall(level, pos.immutable(), state);
					fb.disableDrop();
					fb.setNoGravity(true);
					fb.noPhysics = true;
					bag.add(fb);
					shown++;
				} else {
					level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
							net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
				}
				done++;
				c.removed++;
			}
		}
	}

	private static void later(int ticks, Runnable run) {
		DELAYED.add(new Delayed(now + Math.max(1, ticks), run, Vfx.skill));
	}

	private static void channel(ServerPlayer p, int duration, int every, Runnable step) {
		CHANNELS.add(new Channel(p, now + Math.max(1, duration), Math.max(1, every), step, Vfx.skill));
	}

	/** Enemies in a cone in front of the player. */
	private static List<LivingEntity> cone(ServerPlayer p, double range, double cos) {
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1.0F);
		return p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range), e -> enemy(p, e)
				&& e.distanceToSqr(p) <= range * range && e.getEyePosition().subtract(eye).normalize().dot(look) >= cos);
	}

	/** A straight beam: every enemy along it (up to a count), stops at walls. */
	private static List<LivingEntity> beam(ServerPlayer p, Vec3 from, Vec3 dir, double range, double width, int max, ParticleOptions particle) {
		ServerLevel level = (ServerLevel) p.level();
		Vec3 end = from.add(dir.scale(range));
		BlockHitResult wall = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (wall.getType() != HitResult.Type.MISS) {
			end = wall.getLocation();
		}
		double len = from.distanceTo(end);
		Vfx.beam(level, from, from.add(dir.scale(len)), particle, width);
		List<LivingEntity> found = new ArrayList<>();
		final Vec3 e2 = end;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, e2).inflate(width), x -> enemy(p, x))) {
			if (e.getBoundingBox().inflate(width / 2).clip(from, e2).isPresent()) {
				found.add(e);
			}
		}
		found.sort((a, b) -> Double.compare(a.distanceToSqr(from), b.distanceToSqr(from)));
		return found.size() > max ? found.subList(0, max) : found;
	}

	// ------------------------------------------------------------------ passives (melee hits)

	/** A player hit something in melee: Bleed, Life Steal, Fire Spirit, Shadow, Black Blessing Flame. */
	public static void onMelee(ServerPlayer p, LivingEntity target, float damage) {
		if (!enemy(p, target)) {
			return;
		}
		if (AbilityManager.tryPassive(p, "bleed")) {
			dot(p, target, v(p, "bleed", "DOT"), 0, v(p, "bleed", "DOT_DURATION"));
			bleed(target, v(p, "bleed", "DOT_DURATION"));
			burst((ServerLevel) p.level(), target.position(), ParticleTypes.DAMAGE_INDICATOR, 6, 0.3);
		}
		if (AbilityManager.tryPassive(p, "life_steal")) {
			p.heal((float) (damage * v(p, "life_steal", "HEAL") * AbilityManager.power(p)));
			Fx.hit(target);
		}
		if (AbilityManager.tryPassive(p, "fire_spirit")) {
			burn(p, target, "fire_spirit");
		}
		if (AbilityManager.isActive(p, "black_blessing_flame")) {
			dot(p, target, v(p, "black_blessing_flame", "DOT"), v(p, "black_blessing_flame", "TRUE_DOT"),
					v(p, "black_blessing_flame", "DOT_DURATION"));
			burst((ServerLevel) p.level(), target.position(), ParticleTypes.SOUL_FIRE_FLAME, 8, 0.3);
		}
		if (AbilityManager.tryPassive(p, "shadow")) {
			float mirror = (float) (damage * v(p, "shadow", "DAMAGE_MIRROR"));
			later(6, () -> {
				if (target.isAlive()) {
					AbilityManager.damage(p, target, mirror);
					burst((ServerLevel) p.level(), target.position(), ParticleTypes.SQUID_INK, 8, 0.3);
				}
			});
		}
	}

	// ------------------------------------------------------------------ using

	public static boolean use(ServerPlayer p, String skill) {
		ServerLevel level = (ServerLevel) p.level();
		Vec3 look = p.getViewVector(1.0F);
		switch (skill) {
			// ---------------- Void
			case "gloom" -> {
				// from level 30 on it becomes a volley: one shot more every few levels, each aimed at another mob in front
				dev.enderskills.data.EsSettings cfgG = dev.enderskills.data.EsSettings.get();
				int lvl = Progression.data(p).level(skill);
				int shots = 1;
				if (lvl >= cfgG.gloomVolleyLevel) {
					int span = Math.max(1, Math.min(50, dev.enderskills.dsl.SkillConfigs.maxLevel(skill)) - cfgG.gloomVolleyLevel);
					shots = 2 + (int) Math.round((cfgG.gloomMaxShots - 2) * Math.min(1.0, (lvl - cfgG.gloomVolleyLevel) / (double) span));
				}
				List<LivingEntity> targets = new ArrayList<>(cone(p, v(p, skill, "RANGE"), 0.6));
				targets.sort((a, c) -> Double.compare(a.distanceToSqr(p), c.distanceToSqr(p)));
				for (int i = 0; i < shots; i++) {
					Vec3 dir = look;
					LivingEntity target = i < targets.size() ? targets.get(i) : null;
					if (target != null) {
						dir = target.getEyePosition().subtract(p.getEyePosition()).normalize();
					} else if (i > 0) {
						dir = look.yRot((float) Math.toRadians((i % 2 == 0 ? 1 : -1) * 7.0 * ((i + 1) / 2))).normalize();
					}
					Bolt b = bolt(p, dir, v(p, skill, "RANGE"));
					b.speed = 3.0;
					b.homing = target;
					b.particle = ParticleTypes.SQUID_INK;
					b.onHit = (e, at) -> {
						hit(p, e, v(p, skill, "DAMAGE"), 0);
						dot(p, e, v(p, skill, "DOT"), 0, v(p, skill, "DOT_DURATION"));
						AbilityManager.status(e, null, MobEffects.DARKNESS, 60, 0);
					};
				}
				sound(level, p.position(), SoundEvents.WARDEN_SONIC_CHARGE, 0.5F, 1.8F);
			}
			case "shadow_jab" -> {
				double range = v(p, skill, "RANGE");
				int duration = (int) v(p, skill, "DURATION");
				int hits = Math.max(1, duration / 3);
				channel(p, duration, 3, () -> {
					for (LivingEntity e : cone(p, range, 0.6)) {
						hit(p, e, v(p, skill, "DAMAGE") / hits, 0);
						dot(p, e, v(p, skill, "DOT"), 0, v(p, skill, "DOT_DURATION"));
						burst(level, e.position(), ParticleTypes.SQUID_INK, 4, 0.3);
					}
					sound(level, p.position(), SoundEvents.PLAYER_ATTACK_STRONG, 0.6F, 1.4F);
				});
			}
			case "gas_cloud" -> {
				Bolt b = bolt(p, v(p, skill, "RANGE"));
				b.particle = ParticleTypes.SNEEZE;
				b.onEnd = at -> {
					double r = v(p, skill, "SIZE") / 2 * Math.max(0.1, dev.enderskills.data.EsSettings.get().gasCloudSize);
					int hold = (int) (20 * Math.max(0, dev.enderskills.data.EsSettings.get().gasCloudHoldSeconds));
					Shapes.send(level, Shapes.CLOUD, at, r, r, null, Shapes.color(skill, 0x7A3CC8), (int) Math.max(40, v(p, skill, "DURATION")), -1);
					for (LivingEntity e : enemies(p, at, r)) {
						hit(p, e, v(p, skill, "DAMAGE"), 0);
					}
					zone(p, at, r, (int) Math.max(40, v(p, skill, "DURATION")), 10, ParticleTypes.SNEEZE, e -> {
						if (!e.hasEffect(dev.enderskills.registry.ModEffects.GASSED) && !e.hasEffect(MobEffects.POISON)) {
							dot(p, e, v(p, skill, "DOT"), 0, v(p, skill, "DOT_DURATION"));
							AbilityManager.status(e, dev.enderskills.registry.ModEffects.GASSED, MobEffects.POISON, (int) v(p, skill, "DOT_DURATION"), 0);
							// caught in the cloud: held in place and blinded for a moment
							if (hold > 0) {
								AbilityManager.status(e, dev.enderskills.registry.ModEffects.ROOTED, MobEffects.SLOWNESS, hold, 10);
								AbilityManager.status(e, null, MobEffects.BLINDNESS, hold + 20, 0);
								if (e instanceof net.minecraft.world.entity.Mob mob) {
									mob.setTarget(null);
								}
							}
						}
					});
					sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 1.6F);
				};
			}
			case "grasp" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				zone(p, at, v(p, skill, "SIZE") / 2, (int) v(p, skill, "DURATION"), 10, ParticleTypes.SCULK_SOUL, e -> {
					AbilityManager.status(e, dev.enderskills.registry.ModEffects.ROOTED, MobEffects.SLOWNESS, 15, 10);
					e.setDeltaMovement(0, Math.min(0, e.getDeltaMovement().y), 0);
					if (now % 20 == 0) {
						dot(p, e, v(p, skill, "DOT"), 0, v(p, skill, "DOT_DURATION"));
					}
				});
				sound(level, at, SoundEvents.SCULK_SHRIEKER_SHRIEK, 0.6F, 0.8F);
			}
			case "black_hole" -> {
				dev.enderskills.data.EsSettings cfgB = dev.enderskills.data.EsSettings.get();
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE")).add(0, 1.0, 0);
				double r = v(p, skill, "SIZE") * Math.max(0.1, cfgB.blackHoleSize) + holeBonus(p, skill);
				double reach = r * Math.max(1.0, cfgB.blackHolePullRange); // it pulls and hurts farther than it looks
				int life = (int) (v(p, skill, "DURATION") * Math.max(0.1, cfgB.blackHoleDuration));
				int total = (int) Math.max(1, life * Math.max(0.1, cfgB.areaDurationMultiplier));
				lastHoleTicks = total;
				Shapes.send(level, Shapes.BLACK_HOLE, at, Math.max(0.8, r * 0.3), r, null, Shapes.color(skill, 0x8A3CFF), total, -1);
				for (LivingEntity e : enemies(p, at, reach)) {
					hit(p, e, v(p, skill, "DAMAGE"), 0);
					dot(p, e, v(p, skill, "DOT"), 0, v(p, skill, "DOT_DURATION"));
				}
				zone(p, at, r, life, 20, ParticleTypes.REVERSE_PORTAL, e -> {
				});
				// every tick: pulls everything around (mobs, items, xp, arrows) and, with "destroy the environment", rips blocks out of the ground
				double damage = v(p, skill, "DAMAGE");
				int[] age = {0, total};
				List<net.minecraft.world.entity.item.FallingBlockEntity> bag = new ArrayList<>();
				channel(p, total, 1, () -> blackHoleTick(p, level, at, reach, r, damage, age, bag, age[0] >= total - 2));
				sound(level, at, SoundEvents.END_PORTAL_SPAWN, 0.6F, 0.5F);
				later(total, () -> Environment.blast(level, at, Math.min(20.0, reach * 0.8))); // collapses and tears the ground
			}
			// ---------------- Blood
			case "blood_pool" -> {
				Vec3 at = AbilityManager.aim(level, p, 8);
				double pool = v(p, skill, "POOL_RANGE") / 2 * Math.max(0.1, dev.enderskills.data.EsSettings.get().bloodPoolSize);
				int poolTicks = (int) (v(p, skill, "POOL_DURATION") * Math.max(0.1, dev.enderskills.data.EsSettings.get().areaDurationMultiplier));
				Environment.fill(level, at, pool, dev.enderskills.registry.ModContent.BLOOD, poolTicks); // the blood follows the ground
				zone(p, at, pool, (int) v(p, skill, "POOL_DURATION"), 20, ParticleTypes.DAMAGE_INDICATOR,
						e -> {
							dot(p, e, v(p, skill, "DOT") / Math.max(1, v(p, skill, "DOT_DURATION") / 20), 0, 20);
							bleed(e, 30);
						});
				sound(level, at, SoundEvents.HONEY_BLOCK_SLIDE, 1.0F, 0.6F);
			}
			case "contaminate" -> {
				Bolt b = bolt(p, v(p, skill, "RANGE"));
				b.particle = ParticleTypes.DAMAGE_INDICATOR;
				b.onHit = (e, at) -> {
					hit(p, e, v(p, skill, "DAMAGE"), 0);
					dot(p, e, v(p, skill, "DOT"), 0, v(p, skill, "DURATION"));
					bleed(e, v(p, skill, "DURATION"));
				};
				sound(level, p.position(), SoundEvents.LLAMA_SPIT, 1.0F, 0.8F);
			}
			case "syphon" -> {
				LivingEntity t = AbilityManager.lookTarget(p, v(p, skill, "RANGE"));
				if (t == null || !enemy(p, t)) {
					return false;
				}
				float amount = (float) (t.getMaxHealth() * v(p, skill, "HEAL") * AbilityManager.power(p));
				amount = Math.min(amount, t.getHealth());
				AbilityManager.damage(p, t, amount);
				p.heal(amount);
				beam(p, t.getEyePosition(), p.getEyePosition().subtract(t.getEyePosition()).normalize(),
						t.distanceTo(p), 0.2, 0, ParticleTypes.DAMAGE_INDICATOR);
				sound(level, p.position(), SoundEvents.WITCH_DRINK, 1.0F, 0.6F);
			}
			case "sacrifice" -> {
				float cost = (float) (p.getMaxHealth() * v(p, skill, "HEAL"));
				if (p.getHealth() <= cost + 1) {
					return false;
				}
				p.setHealth(p.getHealth() - cost);
				AbilityManager.setActive(p, skill, (int) v(p, skill, "DURATION"));
				burst(level, p.position(), ParticleTypes.DAMAGE_INDICATOR, 20, 0.5);
				sound(level, p.position(), SoundEvents.WITHER_SPAWN, 0.4F, 1.6F);
			}
			// ---------------- Wind
			case "slash" -> {
				Bolt b = bolt(p, new Vec3(look.x, 0, look.z), v(p, skill, "RANGE"));
				b.radius = v(p, skill, "SIZE") / 2;
				b.pierce = 99;
				b.speed = 1.2;
				b.particle = ParticleTypes.SWEEP_ATTACK;
				b.onHit = (e, at) -> hit(p, e, v(p, skill, "DAMAGE"), 0);
				sound(level, p.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.2F);
			}
			case "push", "pull" -> {
				Bolt b = bolt(p, v(p, skill, "RANGE"));
				b.radius = skill.equals("push") ? v(p, skill, "SIZE") / 2 : 0.5;
				b.speed = 2.0;
				b.particle = ParticleTypes.GUST;
				b.onHit = (e, at) -> {
					Vec3 d = e.position().subtract(p.position()).normalize();
					if (skill.equals("push")) {
						double f = v(p, skill, "FORCE") * 0.12;
						e.setDeltaMovement(d.x * f, 0.4, d.z * f);
					} else {
						double dist = e.distanceTo(p);
						e.setDeltaMovement(-d.x * dist * 0.18, 0.35, -d.z * dist * 0.18);
					}
					e.hurtMarked = true;
					AbilityManager.stun(e, (int) v(p, skill, "STUN"));
				};
				sound(level, p.position(), SoundEvents.BREEZE_SHOOT, 1.0F, skill.equals("push") ? 1.0F : 1.5F);
			}
			case "crush" -> {
				Bolt b = bolt(p, v(p, skill, "RANGE"));
				b.speed = 1.6;
				b.particle = ParticleTypes.GUST;
				b.onEnd = at -> {
					for (LivingEntity e : enemies(p, at, v(p, skill, "SIZE") / 2)) {
						hit(p, e, v(p, skill, "DAMAGE"), 0);
						AbilityManager.stun(e, 20);
					}
					sound(level, at, SoundEvents.WIND_CHARGE_BURST.value(), 1.0F, 0.7F);
				};
				b.onHit = (e, at) -> b.left = 0;
			}
			case "updraft" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				boolean any = false;
				for (LivingEntity e : enemies(p, at, v(p, skill, "SIZE") / 2 + 0.5)) {
					e.setDeltaMovement(e.getDeltaMovement().x, v(p, skill, "FORCE") * 0.06, e.getDeltaMovement().z);
					e.hurtMarked = true;
					any = true;
				}
				burst(level, at, ParticleTypes.GUST, 6, v(p, skill, "SIZE") / 2);
				sound(level, at, SoundEvents.BREEZE_JUMP, 1.0F, 0.8F);
				return any || true;
			}
			case "suffocate" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				double r = v(p, skill, "SIZE") / 2;
				for (LivingEntity e : enemies(p, at, r)) {
					hit(p, e, v(p, skill, "DAMAGE"), 0);
				}
				zone(p, at, r, (int) v(p, skill, "DURATION"), 10, ParticleTypes.WHITE_ASH, e -> {
					e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 5, false, false, true));
					e.setAirSupply(Math.max(0, e.getAirSupply() - 30));
					if (now % 20 == 0) {
						dot(p, e, v(p, skill, "DOT"), 0, v(p, skill, "DOT_DURATION"));
					}
				});
				sound(level, at, SoundEvents.BREEZE_INHALE, 1.0F, 0.6F);
			}
			// ---------------- Fire
			case "flaming_breath" -> {
				double range = v(p, skill, "RANGE");
				channel(p, (int) Math.max(10, v(p, skill, "DURATION")), 2, () -> {
					Vec3 eye = p.getEyePosition();
					Vec3 l = p.getViewVector(1.0F);
					level.sendParticles(ParticleTypes.FLAME, eye.x + l.x, eye.y + l.y - 0.2, eye.z + l.z, 8, 0.1, 0.1, 0.1, 0.0);
					for (int i = 1; i <= 6; i++) {
						Vec3 at = eye.add(l.scale(range * i / 6.0));
						level.sendParticles(ParticleTypes.FLAME, at.x, at.y - 0.2, at.z, 2, 0.2 * i / 3, 0.2 * i / 3, 0.2 * i / 3, 0.01);
					}
					for (LivingEntity e : cone(p, range + 1, 0.7)) {
						if (e.getRemainingFireTicks() <= 0) {
							burn(p, e, skill);
						}
					}
				});
				sound(level, p.position(), SoundEvents.BLAZE_SHOOT, 1.0F, 0.8F);
			}
			case "flaming_rain" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				double r = v(p, skill, "SIZE") / 2;
				Shapes.send(level, Shapes.FIRE_RAIN, at, r, 9, null, Shapes.color(skill, 0xFFB030), (int) v(p, skill, "DURATION"), -1);
				zone(p, at, r, (int) v(p, skill, "DURATION"), 10, ParticleTypes.LAVA, e -> {
					if (e.getRemainingFireTicks() <= 0) {
						burn(p, e, skill);
					}
				});
				channel(p, (int) v(p, skill, "DURATION"), 2, () -> level.sendParticles(ParticleTypes.FALLING_LAVA,
						at.x, at.y + 6, at.z, 6, r / 2, 0.2, r / 2, 0));
				sound(level, at, SoundEvents.FIRECHARGE_USE, 1.0F, 0.6F);
			}
			case "focus_flame" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				double r = Math.max(1.0, v(p, skill, "SIZE") / 2);
				Set<LivingEntity> damaged = new HashSet<>();
				zone(p, at, r, (int) v(p, skill, "DURATION"), 5, ParticleTypes.FLAME, e -> {
					if (damaged.add(e)) {
						hit(p, e, v(p, skill, "DAMAGE"), 0);
					}
					if (e.getRemainingFireTicks() <= 0) {
						burn(p, e, skill);
					}
				});
				channel(p, (int) v(p, skill, "DURATION"), 2, () -> {
					for (int y = 0; y < 8; y++) {
						double a = now * 0.4 + y;
						level.sendParticles(ParticleTypes.FLAME, at.x + Math.cos(a) * r, at.y + y * 0.6, at.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
					}
				});
				sound(level, at, SoundEvents.BLAZE_SHOOT, 1.0F, 0.5F);
			}
			case "fireball" -> {
				Bolt b = bolt(p, v(p, skill, "RANGE"));
				b.particle = ParticleTypes.FLAME;
				b.speed = 1.4;
				b.onHit = (e, at) -> b.left = 0;
				b.onEnd = at -> {
					for (LivingEntity e : enemies(p, at, v(p, skill, "SIZE") / 2 + 0.5)) {
						hit(p, e, v(p, skill, "DAMAGE"), 0);
						burn(p, e, skill);
					}
					burst(level, at, ParticleTypes.EXPLOSION, 3, v(p, skill, "SIZE") / 3);
					Shapes.send(level, Shapes.SPHERE, at, v(p, skill, "SIZE") / 2 + 0.5, 0, null, Shapes.color(skill, 0xFF8A2A), 8, -1);
					sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 1.2F);
					Environment.blast(level, at, v(p, skill, "SIZE") / 3);
				};
				sound(level, p.position(), SoundEvents.BLAZE_SHOOT, 1.0F, 1.0F);
			}
			case "explode" -> {
				double r = v(p, skill, "RANGE");
				for (LivingEntity e : enemies(p, p.position(), r)) {
					hit(p, e, v(p, skill, "DAMAGE"), 0);
					burn(p, e, skill);
					Vec3 d = e.position().subtract(p.position()).normalize();
					e.setDeltaMovement(d.x * 1.2, 0.6, d.z * 1.2);
					e.hurtMarked = true;
				}
				burst(level, p.position(), ParticleTypes.EXPLOSION_EMITTER, 1, 0);
				Shapes.send(level, Shapes.SPHERE, p.position().add(0, 1, 0), r, 0, null, Shapes.color(skill, 0xFF6A1A), 12, -1);
				burst(level, p.position(), ParticleTypes.FLAME, 80, r / 2);
				sound(level, p.position(), SoundEvents.GENERIC_EXPLODE.value(), 1.5F, 0.8F);
				Environment.blast(level, p.position().add(0, -0.5, 0), r * 0.5);
			}
			// ---------------- Light
			case "radiant_ray" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				double r = v(p, skill, "SIZE") / 2 + 0.5;
				later((int) Math.max(5, v(p, skill, "DURATION")), () -> {
					Shapes.send(level, Shapes.BEAM_UP, at, r, 28, null, Shapes.color(skill, 0x8FEAFF), 16, -1);
					for (double y = 0; y < 12; y += 0.5) {
						level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + y, at.z, 2, r / 3, 0.1, r / 3, 0);
					}
					for (LivingEntity e : enemies(p, at, r)) {
						hit(p, e, v(p, skill, "DAMAGE"), 0);
						glow(p, e);
					}
					sound(level, at, SoundEvents.BEACON_DEACTIVATE, 1.0F, 1.8F);
				});
				level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.2, at.z, 10, r / 2, 0.05, r / 2, 0);
			}
			case "lumen_wave" -> {
				Bolt b = bolt(p, new Vec3(look.x, 0, look.z), v(p, skill, "RANGE"));
				b.radius = v(p, skill, "SIZE");
				b.pierce = 99;
				b.particle = ParticleTypes.END_ROD;
				b.onHit = (e, at) -> {
					hit(p, e, v(p, skill, "DAMAGE"), 0);
					e.setDeltaMovement(e.getDeltaMovement().x, v(p, skill, "FORCE") * 0.1, e.getDeltaMovement().z);
					e.hurtMarked = true;
					AbilityManager.stun(e, (int) v(p, skill, "STUN"));
					glow(p, e);
				};
				sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.4F);
			}
			case "gleam_flash" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				double r = v(p, skill, "SIZE") / 2;
				channel(p, (int) v(p, skill, "DELAY"), 4, () -> level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.3, at.z, 4, r / 2, 0.1, r / 2, 0));
				later((int) v(p, skill, "DELAY"), () -> {
					for (LivingEntity e : enemies(p, at, r)) {
						hit(p, e, v(p, skill, "DAMAGE"), 0);
						AbilityManager.status(e, null, MobEffects.BLINDNESS, 40, 0);
						glow(p, e);
					}
					burst(level, at, ParticleTypes.FIREWORK, 30, 0.6);
					Shapes.send(level, Shapes.SPHERE, at, r, 0, null, Shapes.color(skill, 0xFFFBE0), 7, -1);
					burst(level, at, ParticleTypes.END_ROD, 40, r / 2);
					sound(level, at, SoundEvents.FIREWORK_ROCKET_BLAST, 1.0F, 1.2F);
				});
			}
			case "solar_lance" -> {
				Shapes.send(level, Shapes.BEAM_LINE, p.getEyePosition().add(0, -0.2, 0), Math.max(0.25, v(p, skill, "SIZE") / 4), v(p, skill, "RANGE"),
						look, Shapes.color(skill, 0xFFE38A), 10, -1);
				for (LivingEntity e : beam(p, p.getEyePosition(), look, v(p, skill, "RANGE"), v(p, skill, "SIZE") / 2,
						(int) v(p, skill, "PIERCING"), ParticleTypes.END_ROD)) {
					hit(p, e, v(p, skill, "DAMAGE"), 0);
					glow(p, e);
				}
				sound(level, p.position(), SoundEvents.TRIDENT_THUNDER.value(), 0.6F, 1.8F);
			}
			case "barrage_wisps" -> {
				LivingEntity target = AbilityManager.lookTarget(p, v(p, skill, "RANGE"));
				int amount = (int) v(p, skill, "AMOUNT");
				for (int i = 0; i < amount; i++) {
					double spread = (i - (amount - 1) / 2.0) * 0.25;
					Vec3 dir = look.yRot((float) spread).add(0, 0.4, 0);
					Bolt b = bolt(p, dir, v(p, skill, "RANGE") + 8);
					b.speed = 0.4;
					b.wait = 20;
					b.homing = target;
					b.radius = v(p, skill, "SIZE") / 2;
					b.particle = ParticleTypes.END_ROD;
					b.onHit = (e, at) -> {
						hit(p, e, v(p, skill, "DAMAGE"), 0);
						glow(p, e);
						b.left = 0;
					};
				}
				sound(level, p.position(), SoundEvents.ALLAY_THROW, 1.0F, 1.2F);
			}
			case "final_flash" -> {
				int delay = (int) v(p, skill, "DELAY");
				channel(p, delay, 2, () -> {
					Vec3 eye = p.getEyePosition().add(p.getViewVector(1.0F));
					level.sendParticles(ParticleTypes.END_ROD, eye.x, eye.y, eye.z, 4, 0.3, 0.3, 0.3, 0.02);
				});
				sound(level, p.position(), SoundEvents.BEACON_POWER_SELECT, 1.0F, 0.6F);
				later(delay, () -> {
					sound(level, p.position(), SoundEvents.BEACON_ACTIVATE, 1.5F, 1.8F);
					channel(p, (int) v(p, skill, "DURATION"), 5, () -> {
						Shapes.send(level, Shapes.BEAM_LINE, p.getEyePosition().add(0, -0.2, 0), v(p, skill, "SIZE") / 3, v(p, skill, "RANGE"),
								p.getViewVector(1.0F), Shapes.color(skill, 0xFFF6C8), 6, -1);
						for (LivingEntity e : beam(p, p.getEyePosition(), p.getViewVector(1.0F), v(p, skill, "RANGE"),
								v(p, skill, "SIZE") / 2, 99, ParticleTypes.END_ROD)) {
							hit(p, e, v(p, skill, "DOT") / 4, 0);
							glow(p, e);
						}
					});
				});
			}
			// ---------------- Black Flame
			case "black_flame_ball", "black_raging_flame_ball" -> {
				boolean raging = skill.equals("black_raging_flame_ball");
				Bolt b = bolt(p, v(p, skill, "RANGE"));
				b.particle = ParticleTypes.SOUL_FIRE_FLAME;
				b.radius = raging ? 1.0 : 0.6;
				b.onHit = (e, at) -> b.left = 0;
				b.onEnd = at -> {
					double r = v(p, skill, "SIZE") / 2 + 0.5;
					for (LivingEntity e : enemies(p, at, r)) {
						hit(p, e, v(p, skill, "DAMAGE"), v(p, skill, "TRUE_DAMAGE"));
						dot(p, e, v(p, skill, "DOT"), v(p, skill, "TRUE_DOT"), v(p, skill, "DOT_DURATION"));
					}
					burst(level, at, ParticleTypes.SOUL_FIRE_FLAME, raging ? 120 : 30, r / 2);
					Shapes.send(level, Shapes.SPHERE, at, r, 0, null, Shapes.color(skill, 0x5A2AD0), raging ? 14 : 8, -1);
					sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), raging ? 1.2F : 0.6F, 0.7F);
					if (raging) {
						zone(p, at, r, (int) v(p, skill, "DURATION"), 20, ParticleTypes.SOUL_FIRE_FLAME,
								e -> dot(p, e, v(p, skill, "DOT"), v(p, skill, "TRUE_DOT"), v(p, skill, "DOT_DURATION")));
					}
				};
				sound(level, p.position(), SoundEvents.BLAZE_SHOOT, 1.0F, 0.5F);
			}
			case "black_scouring_flame" -> {
				int amount = (int) v(p, skill, "AMOUNT");
				for (int i = 0; i < amount; i++) {
					double angle = (i - (amount - 1) / 2.0) * 0.22;
					int index = i;
					later(1 + (int) (v(p, skill, "INTERVAL") * index / 2), () -> blackBolt(p, skill, look.yRot((float) angle)));
				}
				sound(level, p.position(), SoundEvents.BLAZE_SHOOT, 1.0F, 0.4F);
			}
			case "black_volley_flame" -> {
				int every = (int) Math.max(2, v(p, skill, "INTERVAL"));
				channel(p, (int) v(p, skill, "DURATION"), every, () -> {
					blackBolt(p, skill, p.getViewVector(1.0F));
					sound(level, p.position(), SoundEvents.BLAZE_SHOOT, 0.5F, 1.4F);
				});
			}
			case "black_blessing_flame" -> {
				int blessing = (int) Math.max(60, v(p, skill, "DURATION") * 10);
				AbilityManager.setActive(p, skill, blessing);
				Shapes.onPlayer(p, Shapes.RUNES, blessing, AbilityManager.runeRings(p, skill), true);
				burst(level, p.position(), ParticleTypes.SOUL_FIRE_FLAME, 40, 0.5);
				sound(level, p.position(), SoundEvents.SOUL_ESCAPE.value(), 1.0F, 0.6F);
			}
			case "black_flare_flame" -> {
				Vec3 at = AbilityManager.aim(level, p, v(p, skill, "RANGE"));
				double r = v(p, skill, "SIZE") / 4;
				int delay = (int) v(p, skill, "DELAY");
				channel(p, delay, 2, () -> level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.3, at.z, 10, r / 2, 0.1, r / 2, 0.02));
				later(delay, () -> {
					for (LivingEntity e : enemies(p, at, r)) {
						hit(p, e, v(p, skill, "DAMAGE"), v(p, skill, "TRUE_DAMAGE"));
						dot(p, e, v(p, skill, "DOT"), v(p, skill, "TRUE_DOT"), v(p, skill, "DOT_DURATION"));
					}
					burst(level, at, ParticleTypes.EXPLOSION_EMITTER, 1, 0);
					Shapes.send(level, Shapes.SPHERE, at, r, 0, null, Shapes.color(skill, 0x4A1AB0), 16, -1);
					burst(level, at, ParticleTypes.SOUL_FIRE_FLAME, 200, r / 2);
					sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 2.0F, 0.5F);
				});
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private static void blackBolt(ServerPlayer p, String skill, Vec3 dir) {
		Bolt b = bolt(p, dir, v(p, skill, "RANGE"));
		b.particle = ParticleTypes.SOUL_FIRE_FLAME;
		b.speed = 1.8;
		b.onHit = (e, at) -> {
			hit(p, e, v(p, skill, "DAMAGE"), v(p, skill, "TRUE_DAMAGE"));
			dot(p, e, v(p, skill, "DOT"), v(p, skill, "TRUE_DOT"), v(p, skill, "DOT_DURATION"));
			b.left = 0;
		};
	}

	// ------------------------------------------------------------------ tick

	/** Black Hole blocks found floating after a long absence: {entity, tick they act}. */
	private static final List<Object[]> STRAY = new ArrayList<>();

	/** A flying block of a Black Hole loaded from the save (its hole is long gone): it vanishes or falls after a moment. */
	public static void strayHoleBlock(net.minecraft.world.entity.item.FallingBlockEntity fb) {
		STRAY.add(new Object[] {fb, now + (int) (20 * Math.max(0.0, dev.enderskills.data.EsSettings.get().blackHoleLeftoverSeconds))});
	}

	static void tick(int tick) {
		now = tick;
		if (!STRAY.isEmpty()) {
			for (Iterator<Object[]> it = STRAY.iterator(); it.hasNext();) {
				Object[] s = it.next();
				net.minecraft.world.entity.item.FallingBlockEntity fb = (net.minecraft.world.entity.item.FallingBlockEntity) s[0];
				if (!fb.isAlive()) {
					it.remove();
				} else if (now >= (Integer) s[1]) {
					if (dev.enderskills.data.EsSettings.get().blackHoleLeftover == dev.enderskills.data.EsSettings.HoleBlocks.DROP) {
						fb.setNoGravity(false);
						fb.noPhysics = false;
						fb.setDeltaMovement(fb.getDeltaMovement().scale(0.2));
						fb.hurtMarked = true;
					} else {
						fb.discard();
					}
					it.remove();
				}
			}
		}
		BOLTS.addAll(NEW_BOLTS);
		NEW_BOLTS.clear();
		for (Iterator<Bolt> it = BOLTS.iterator(); it.hasNext();) {
			Bolt b = it.next();
			Vfx.skill = b.skill;
			if (!b.owner.isAlive() || b.owner.level() != b.level) {
				Fx.boltEnd(b.level, b.skill, b, b.pos);
				it.remove();
				continue;
			}
			Fx.bolt(b.level, b.skill, b, b.pos, b.dir.scale(b.speed), b.radius);
			if (b.wait > 0) {
				b.wait--;
				b.pos = b.pos.add(b.dir.scale(b.speed * 0.3));
				b.level.sendParticles(b.particle, b.pos.x, b.pos.y, b.pos.z, 1, 0, 0, 0, 0);
				continue;
			}
			if (b.homing != null && b.homing.isAlive()) {
				b.dir = b.homing.getEyePosition().subtract(b.pos).normalize();
				if (!b.skill.equals("gloom")) {
					b.speed = 1.0; // Gloom shots stay fast, like a shot
				}
			}
			if (!step(b)) {
				Fx.boltEnd(b.level, b.skill, b, b.pos);
				if (b.skill.startsWith("black_")) {
					Environment.spread(b.level, b.pos, 1.5, dev.enderskills.registry.ModContent.BLACK_FIRE, 3,
							20 * Math.max(1, dev.enderskills.data.EsSettings.get().environmentSeconds));
				}
				b.onEnd.accept(b.pos);
				it.remove();
			}
		}
		DELAYED.removeIf(d -> {
			if (d.at() <= now) {
				Vfx.skill = d.skill();
				d.run().run();
				return true;
			}
			return false;
		});
		CHANNELS.removeIf(c -> {
			if (c.until() < now || !c.owner().isAlive()) {
				return true;
			}
			if ((c.until() - now) % c.every() == 0) {
				Vfx.skill = c.skill();
				c.step().run();
			}
			return false;
		});
		ZONES.removeIf(z -> z.until() <= now || !z.owner().isAlive());
		for (Zone z : List.copyOf(ZONES)) {
			Vfx.skill = z.skill();
			if (now % 4 == 0) {
				z.level().sendParticles(z.particle(), z.center().x, z.center().y + 0.3, z.center().z, Vfx.n(8), z.radius() / 2, 0.2, z.radius() / 2, 0.01);
				Vfx.ring(z.level(), z.center(), z.radius(), Vfx.halo(z.particle()), (int) (z.radius() * 8 + 6));
			}
			if (now % z.every() == 0) {
				for (LivingEntity e : enemies(z.owner(), z.center().add(0, 0.5, 0), z.radius() + 0.5)) {
					z.action().accept(e);
				}
			}
		}
	}

	/** Moves a bolt one tick; false when it is done. */
	private static boolean step(Bolt b) {
		Vec3 next = b.pos.add(b.dir.scale(b.speed));
		BlockHitResult wall = b.level.clip(new ClipContext(b.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b.owner));
		boolean blocked = wall.getType() != HitResult.Type.MISS;
		Vec3 end = blocked ? wall.getLocation() : next;
		for (LivingEntity e : b.level.getEntitiesOfClass(LivingEntity.class, new AABB(b.pos, end).inflate(b.radius), x -> enemy(b.owner, x))) {
			if (b.hit.contains(e) || b.hit.size() >= b.pierce) {
				continue;
			}
			if (e.getBoundingBox().inflate(b.radius).clip(b.pos, end).isPresent() || e.getBoundingBox().inflate(b.radius).contains(b.pos)) {
				b.hit.add(e);
				b.onHit.accept(e, e.position());
				if (b.left <= 0) {
					b.pos = e.position().add(0, e.getBbHeight() / 2, 0);
					return false;
				}
			}
		}
		for (double d = 0; d < b.speed; d += 0.5) {
			Vec3 at = b.pos.add(b.dir.scale(d));
			Vfx.glow(b.level, at, b.particle, b.radius / 4);
		}
		b.left -= b.pos.distanceTo(end);
		b.pos = end;
		return !blocked && b.left > 0;
	}
}
