package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Portals placed in the world: a Portal sends to its exit, an Unstable Portal to a random spot nearby. */
public final class Zones {
	/**
	 * @param radius       reach of the portal (0 = only a visual marker, like a portal exit)
	 * @param exit         where a Portal sends you (null for unstable portals and markers)
	 * @param displacement how far an Unstable Portal throws you
	 */
	public record Zone(ServerLevel level, Vec3 center, double radius, int until, Vec3 exit, double displacement) {
	}

	private static final List<Zone> ZONES = new ArrayList<>();
	/** Entities that just went through a portal (no ping-pong). */
	private static final Map<UUID, Integer> RECENT = new HashMap<>();

	private Zones() {
	}

	public static void remove(Zone zone) {
		ZONES.remove(zone);
	}

	public static void add(Zone zone) {
		ZONES.add(zone);
	}

	/** Entities that are standing inside a portal right now (they go through only when they step in). */
	private static final java.util.Set<UUID> INSIDE = new java.util.HashSet<>();

	/** Bright burst so a new portal shows up at once. */
	public static void flash(ServerLevel level, Vec3 c) {
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y + 1.0, c.z, Vfx.n(60), 0.4, 0.8, 0.4, 0.15);
		level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 1.0, c.z, Vfx.n(20), 0.3, 0.7, 0.3, 0.05);
	}

	static void tick(int now) {
		if (ZONES.isEmpty()) {
			INSIDE.clear();
			return;
		}
		ZONES.removeIf(z -> z.until() <= now);
		RECENT.values().removeIf(t -> t <= now);
		java.util.Set<UUID> insideNow = new java.util.HashSet<>();
		for (Zone z : List.copyOf(ZONES)) {
			Vec3 c = z.center();
			double r = Math.max(0.5, z.radius());
			if (now % 2 == 0) {
				z.level().sendParticles(z.exit() == null && z.radius() > 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.PORTAL,
						c.x, c.y + 1.0, c.z, Vfx.n(8), r / 2, 0.7, r / 2, 0.2);
				if (z.radius() > 0) {
					for (int i = 0; i < 12; i++) { // standing portal ring
						double a = Math.PI * 2 * i / 12 + now * 0.08;
						z.level().sendParticles(ParticleTypes.END_ROD, c.x + Math.cos(a) * r, c.y + 1.0 + Math.sin(a) * 1.0, c.z, 1, 0, 0, 0, 0);
						z.level().sendParticles(new net.minecraft.core.particles.DustParticleOptions(0x9A4DFF, 1.2F),
								c.x + Math.cos(a) * r * 0.9, c.y + 1.0 + Math.sin(a) * 0.9, c.z, 1, 0, 0, 0, 0);
					}
				}
			}
			if (z.radius() <= 0) {
				continue;
			}
			double reach = z.radius() + 0.4;
			for (LivingEntity e : z.level().getEntitiesOfClass(LivingEntity.class,
					new AABB(c.x - reach, c.y - 0.5, c.z - reach, c.x + reach, c.y + 2.5, c.z + reach), x -> x.isAlive() && !x.isSpectator())) {
				UUID id = e.getUUID();
				insideNow.add(id);
				if (INSIDE.contains(id) || RECENT.containsKey(id)) {
					continue; // already standing in a portal: no ping-pong
				}
				Vec3 to = z.exit() != null ? z.exit() : randomSpot(z, e);
				if (to == null) {
					continue;
				}
				RECENT.put(id, now + 4);
				Vec3 motion = e.getDeltaMovement();
				e.teleportTo(to.x, to.y, to.z);
				e.setDeltaMovement(motion);
				e.resetFallDistance();
				flash(z.level(), to);
				dev.enderskills.registry.ModEffects.play(z.level(), to, "portal_active");
			}
		}
		INSIDE.clear();
		INSIDE.addAll(insideNow);
		INSIDE.addAll(RECENT.keySet());
	}

	private static Vec3 randomSpot(Zone z, LivingEntity e) {
		var random = z.level().getRandom();
		for (int i = 0; i < 12; i++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double dist = z.displacement() * (0.5 + random.nextDouble() * 0.5);
			for (int up = 0; up <= 4; up++) {
				Vec3 spot = new Vec3(z.center().x + Math.cos(angle) * dist, e.getY() + up, z.center().z + Math.sin(angle) * dist);
				if (z.level().noCollision(e, e.getBoundingBox().move(spot.subtract(e.position())))) {
					return spot;
				}
			}
		}
		return null;
	}
}
