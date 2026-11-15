package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * (2 at level 20, one more every 10 levels, up to the configured maximum at the top level). Sneak + the Shadow key picks
 * how many you want; using Shadow again switches it (and them) off.
 */
public final class ShadowMinions {
	private static final Map<UUID, List<ShadowEntity>> ALL = new HashMap<>();
	/** How many the player wants (Integer.MAX_VALUE = all they can have). */
	private static final Map<UUID, Integer> CHOSEN = new HashMap<>();

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
			int want = AbilityManager.isActive(p, "shadow") && p.isAlive() && !p.isSpectator()
					? Math.min(allowed(p), CHOSEN.getOrDefault(p.getUUID(), Integer.MAX_VALUE)) : 0;
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

	/** One shadow's mind: guard the owner, fight what threatens them, otherwise stay close. */
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
		if (shadow.getTarget() == null) {
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
	}
}
