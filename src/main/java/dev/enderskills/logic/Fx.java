package dev.enderskills.logic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.enderskills.data.EsSettings;
import dev.enderskills.network.EsNetwork;

/**
 * Tells the clients what an ability is doing, so they draw it like the original mod (models, cosmic shader, glow sprites).
 * CAST: the ability was used; ZONE: an area was placed; BOLT: a projectile is here this tick; HIT: something was hit; END: stop.
 */
public final class Fx {
	public static final int CAST = 0;
	public static final int ZONE = 1;
	public static final int BOLT = 2;
	public static final int HIT = 3;
	public static final int END = 4;

	private Fx() {
	}

	private static boolean on() {
		return EsSettings.get().vfxStyle != EsSettings.VfxStyle.CURRENT;
	}

	public static void send(ServerLevel level, String skill, int phase, int id, int entity, Vec3 pos, Vec3 to, double radius, int ticks) {
		if (!on() || skill == null || skill.isEmpty()) {
			return;
		}
		EsNetwork.SkillFx payload = new EsNetwork.SkillFx(skill, phase, id, entity, pos.x, pos.y, pos.z,
				to == null ? pos.x : to.x, to == null ? pos.y : to.y, to == null ? pos.z : to.z, (float) radius, ticks);
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(pos) < 192 * 192 && ServerPlayNetworking.canSend(p, EsNetwork.SkillFx.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** An ability was used: from where (before moving), what it aims at, its size and duration. */
	public static void cast(ServerPlayer p, String skill, Vec3 origin, Vec3 aim) {
		if (!on()) {
			return;
		}
		double size = prop(p, skill, "SIZE", prop(p, skill, "RANGE", 3.0));
		int ticks = (int) Math.min(Integer.MAX_VALUE - 1, Math.max(0, prop(p, skill, "DURATION", 0)));
		if (ticks == 0 && (skill.equals("warp") || skill.equals("teleport"))) {
			ticks = 20; // the rift opens and closes in a second
		}
		if (AbilityManager.isActive(p, skill) && AbilityManager.TOGGLES.contains(skill)) {
			ticks = Integer.MAX_VALUE;
		}
		send((ServerLevel) p.level(), skill, CAST, 0, p.getId(), origin, aim == null ? p.position() : aim, size, ticks);
	}

	public static void end(ServerPlayer p, String skill) {
		send((ServerLevel) p.level(), skill, END, 0, p.getId(), p.position(), null, 0, 0);
	}

	public static void hit(Entity target) {
		if (target.level() instanceof ServerLevel level) {
			send(level, Vfx.skill, HIT, 0, target.getId(), target.position(), null, target.getBbHeight(), 0);
		}
	}

	public static void zone(ServerPlayer p, Vec3 center, double radius, int ticks) {
		send((ServerLevel) p.level(), Vfx.skill, ZONE, 0, p.getId(), center, null, radius, ticks);
	}

	public static void bolt(ServerLevel level, String skill, Object bolt, Vec3 pos, Vec3 dir, double radius) {
		send(level, skill, BOLT, System.identityHashCode(bolt), -1, pos, pos.add(dir), radius, 0);
	}

	public static void boltEnd(ServerLevel level, String skill, Object bolt, Vec3 pos) {
		send(level, skill, END, System.identityHashCode(bolt), -1, pos, null, 0, 0);
	}

	private static double prop(ServerPlayer p, String skill, String property, double fallback) {
		if (!dev.enderskills.dsl.SkillConfigs.get(skill).map.containsKey(property)) {
			return fallback;
		}
		double v = AbilityManager.value(p, skill, property);
		return Double.isFinite(v) && v > 0 ? v : fallback;
	}
}
