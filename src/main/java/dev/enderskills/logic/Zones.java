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

	public static void add(Zone zone) {
		ZONES.add(zone);
	}

	static void tick(int now) {
		if (ZONES.isEmpty()) {
			return;
		}
		ZONES.removeIf(z -> z.until() <= now);
		RECENT.values().removeIf(t -> t <= now);
		for (Zone z : ZONES) {
			Vec3 c = z.center();
			if (now % 4 == 0) {
				double r = Math.max(0.5, z.radius());
				z.level().sendParticles(z.exit() == null && z.radius() > 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.PORTAL,
						c.x, c.y + 1.0, c.z, 12, r / 2, 0.7, r / 2, 0.2);
			}
			if (z.radius() <= 0) {
				continue;
			}
			double r = z.radius();
			for (LivingEntity e : z.level().getEntitiesOfClass(LivingEntity.class,
					new AABB(c.x - r, c.y - 0.5, c.z - r, c.x + r, c.y + 2.5, c.z + r), x -> x.isAlive() && !x.isSpectator())) {
				if (RECENT.containsKey(e.getUUID())) {
					continue;
				}
				Vec3 to = z.exit() != null ? z.exit() : randomSpot(z, e);
				if (to == null) {
					continue;
				}
				RECENT.put(e.getUUID(), now + 30);
				e.teleportTo(to.x, to.y, to.z);
				e.resetFallDistance();
				z.level().playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.6F, 1.3F);
			}
		}
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
