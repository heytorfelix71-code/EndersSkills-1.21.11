package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import dev.enderskills.data.EsSettings;
import dev.enderskills.dsl.SkillConfigs;
import dev.enderskills.entity.ShadowEntity;
import dev.enderskills.registry.ModContent;

/**
 * Shadow from level 20: besides the shadow at your back, shadows of you walk around and fight the hostile mobs near you
 * (2 at level 20, one more every 10 levels, up to the configured maximum at the top level). They swim and sink like you
 * and cannot die: they cost energy every second (half of that at the top level) and are gone only when it runs out.
 * Sneak + the Shadow key picks how many you want; using Shadow again switches it (and them) off.
 */
public final class ShadowMinions {
	private static final Map<UUID, List<ShadowEntity>> ALL = new HashMap<>();
	/** How many the player wants (Integer.MAX_VALUE = all they can have). */
	private static final Map<UUID, Integer> CHOSEN = new HashMap<>();
	/** Players whose energy ran out: no shadows until they have recovered some of it. */
	private static final Set<UUID> EXHAUSTED = new HashSet<>();

	private ShadowMinions() {
	}

	/** How many shadows the player's Shadow level allows: from the start level, growing to the maximum at the top level. */
	public static int allowed(ServerPlayer p) {
		EsSettings c = EsSettings.get();
		int level = Progression.data(p).level("shadow");
		int start = Math.max(0, c.shadowMinionLevel);
		int max = Math.max(0, Math.min(100, c.shadowMinionMax));
		if (level < start || max == 0) {
			return 0;
		}
		int first = Math.min(2, max);
		int top = Math.max(start + 1, Math.min(50, SkillConfigs.maxLevel("shadow")));
		double f = Math.min(1.0, (level - start) / (double) (top - start));
		return (int) Math.floor(first + (max - first) * f + 1.0E-6);
	}

	/** Sneak + Shadow key: how many shadows the player wants. */
	public static void choose(ServerPlayer p, int count) {
		int allowed = allowed(p);
		int n = Math.max(0, Math.min(100, count));
		CHOSEN.put(p.getUUID(), n);
		p.displayClientMessage(Component.translatable("enderskills.shadows.count", Math.min(n, allowed), allowed), true);
	}

	/** Energy per second for each shadow: lower at the top level. */
	private static double upkeep(ServerPlayer p) {
		EsSettings c = EsSettings.get();
		boolean top = Progression.data(p).level("shadow") >= Math.min(50, SkillConfigs.maxLevel("shadow"));
		return Math.max(0, c.shadowMinionDrain) * (top ? Math.max(0, c.shadowMinionTopFactor) : 1.0);
	}

	static void tick(MinecraftServer server, int now) {
		if (now % 10 != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			List<ShadowEntity> list = ALL.computeIfAbsent(p.getUUID(), u -> new ArrayList<>());
			list.removeIf(e -> {
				if (e.isRemoved() || e.level() != p.level()) {
					e.discard();
					return true;
				}
				return false;
			});
			boolean on = AbilityManager.isActive(p, "shadow") && p.isAlive() && !p.isSpectator();
			if (!on) {
				EXHAUSTED.remove(p.getUUID());
			} else if (EXHAUSTED.contains(p.getUUID()) && AbilityManager.enduranceFraction(p) >= 0.25) {
				EXHAUSTED.remove(p.getUUID()); // enough energy again
			}
			int want = on && !EXHAUSTED.contains(p.getUUID()) ? Math.min(allowed(p), CHOSEN.getOrDefault(p.getUUID(), Integer.MAX_VALUE)) : 0;
			while (list.size() > want) {
				list.remove(list.size() - 1).discard();
			}
			ServerLevel level = (ServerLevel) p.level();
			while (list.size() < want) {
				ShadowEntity e = ModContent.SHADOW.create(level, EntitySpawnReason.MOB_SUMMONED);
				if (e == null) {
					break;
				}
				double a = level.getRandom().nextDouble() * Math.PI * 2;
				e.snapTo(p.getX() + Math.cos(a) * 1.5, p.getY(), p.getZ() + Math.sin(a) * 1.5, p.getYRot(), 0);
				e.setOwner(p);
				level.addFreshEntity(e);
				list.add(e);
				level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, e.getX(), e.getY() + 1, e.getZ(), 8, 0.3, 0.6, 0.3, 0.01);
			}
			// they cost energy every second; when it runs out they are gone
			if (!list.isEmpty() && now % 20 == 0) {
				double perSecond = upkeep(p) * list.size();
				if (perSecond > 0 && !AbilityManager.drain(p, perSecond)) {
					EXHAUSTED.add(p.getUUID());
					p.displayClientMessage(Component.translatable("enderskills.shadows.exhausted"), true);
					list.forEach(ShadowEntity::discard);
					list.clear();
					continue;
				}
			}
			double damage = Math.max(0, EsSettings.get().shadowMinionDamage) * AbilityManager.power(p);
			for (ShadowEntity e : list) {
				var attr = e.getAttribute(Attributes.ATTACK_DAMAGE);
				if (attr != null && attr.getBaseValue() != damage) {
					attr.setBaseValue(damage);
				}
			}
		}
		ALL.entrySet().removeIf(e -> {
			if (server.getPlayerList().getPlayer(e.getKey()) == null) {
				e.getValue().forEach(ShadowEntity::discard);
				return true;
			}
			return false;
		});
	}

	/** One shadow's mind: guard the owner, fight what threatens them, otherwise stay close. In water it swims. */
	public static void think(ShadowEntity shadow) {
		if (!(shadow.level() instanceof ServerLevel level) || shadow.owner() == null
				|| !(level.getPlayerByUUID(shadow.owner()) instanceof ServerPlayer owner)) {
			shadow.discard();
			return;
		}
		double range = Math.max(4, EsSettings.get().shadowMinionRange);
		LivingEntity target = shadow.getTarget();
		if (target != null && (!target.isAlive() || target.distanceToSqr(owner) > (range + 8) * (range + 8))) {
			shadow.setTarget(null);
			target = null;
		}
		if (target == null && shadow.tickCount % 5 == 0) {
			target = pick(owner, range);
			if (target != null) {
				shadow.setTarget(target);
			}
		}
		target = shadow.getTarget();
		if (shadow.getY() < level.getMinY() - 8) { // fell out of the world
			shadow.teleportTo(owner.getX(), owner.getY(), owner.getZ());
			return;
		}
		if (shadow.isInWater()) {
			swim(shadow, level, owner, target);
			return;
		}
		if (shadow.isSprinting()) {
			shadow.setSprinting(false);
		}
		if (target == null) {
			double d2 = shadow.distanceToSqr(owner);
			if (d2 > 32 * 32) {
				Vec3 at = owner.position().add(level.getRandom().nextDouble() * 2 - 1, 0, level.getRandom().nextDouble() * 2 - 1);
				shadow.teleportTo(at.x, at.y, at.z);
				shadow.getNavigation().stop();
			} else if (d2 > 4.0 * 4.0) {
				shadow.getNavigation().moveTo(owner, 1.4);
			} else if (d2 < 2.5 * 2.5) {
				shadow.getNavigation().stop();
				shadow.getLookControl().setLookAt(owner);
			}
		}
	}

	/** In water the shadow swims in 3D towards its target or its owner, sinks when idle, and hits with its own timing. */
	private static void swim(ShadowEntity shadow, ServerLevel level, ServerPlayer owner, LivingEntity target) {
		Vec3 here = shadow.position().add(0, shadow.getBbHeight() * 0.5, 0);
		Vec3 dest = target != null ? target.position().add(0, target.getBbHeight() * 0.5, 0) : owner.position().add(0, owner.getBbHeight() * 0.5, 0);
		Vec3 to = dest.subtract(here);
		double len = to.length();
		double stop = target != null ? 1.5 : 2.5;
		boolean moving = len > stop;
		if (len > 40) {
			shadow.teleportTo(owner.getX(), owner.getY(), owner.getZ());
			return;
		}
		if (moving) {
			Vec3 dir = to.scale(1.0 / len);
			shadow.setDeltaMovement(shadow.getDeltaMovement().add(dir.scale(0.07)));
			shadow.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
			shadow.setYHeadRot(shadow.getYRot());
			shadow.setXRot((float) -Math.toDegrees(Math.atan2(dir.y, Math.hypot(dir.x, dir.z))));
		}
		shadow.setSprinting(moving); // sprinting under water is the swimming pose
		if (target != null && shadow.attackReady() && shadow.distanceToSqr(target) <= 6.5) {
			shadow.swing(InteractionHand.MAIN_HAND);
			shadow.doHurtTarget(level, target);
			shadow.resetAttack();
		}
	}

	/** What to fight: whatever hurt the owner, what the owner is fighting, then the nearest hostile mob around the owner. */
	private static LivingEntity pick(ServerPlayer owner, double range) {
		LivingEntity hurtBy = owner.getLastHurtByMob();
		if (valid(owner, hurtBy, range)) {
			return hurtBy;
		}
		LivingEntity fighting = owner.getLastHurtMob();
		if (valid(owner, fighting, range) && (fighting instanceof Enemy || (fighting instanceof Mob m && m.getTarget() == owner))) {
			return fighting;
		}
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (Mob m : owner.level().getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(range),
				m -> (hostile(owner, m) || m.getTarget() == owner) && valid(owner, m, range))) {
			double d = m.distanceToSqr(owner);
			if (d < bestD) {
				bestD = d;
				best = m;
			}
		}
		return best;
	}

	/** Hostile, but not a neutral mob that leaves you alone (zombified piglins, endermen...) unless it is angry at you. */
	private static boolean hostile(ServerPlayer owner, Mob m) {
		if (!(m instanceof Enemy)) {
			return false;
		}
		return !(m instanceof net.minecraft.world.entity.NeutralMob n) || n.isAngryAt(owner, (ServerLevel) owner.level());
	}

	private static boolean valid(ServerPlayer owner, LivingEntity e, double range) {
		return e != null && e.isAlive() && !(e instanceof Player) && !(e instanceof ShadowEntity) && !e.isInvisible()
				&& e.level() == owner.level() && e.distanceToSqr(owner) <= range * range && !DefenseAbilities.isAlly(owner, e);
	}

	/** Server stopping. */
	public static void clear() {
		ALL.values().forEach(l -> l.forEach(ShadowEntity::discard));
		ALL.clear();
		EXHAUSTED.clear();
	}
}
