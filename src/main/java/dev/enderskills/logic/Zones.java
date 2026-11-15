package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
	public record Zone(ServerLevel level, Vec3 center, double radius, int until, Vec3 exit, double displacement,
			ServerLevel exitLevel, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> onUse) {
		public Zone(ServerLevel level, Vec3 center, double radius, int until, Vec3 exit, double displacement) {
			this(level, center, radius, until, exit, displacement, null, null);
		}
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

	/** Server stopping. */
	static void clear() {
		ZONES.clear();
		RECENT.clear();
		INSIDE.clear();
	}

	/** Entities that are standing inside a portal right now (they go through only when they step in). */
	private static final java.util.Set<UUID> INSIDE = new java.util.HashSet<>();

	/** Moves a player, to another dimension when needed. */
	static void sendPlayer(ServerPlayer p, ServerLevel level, Vec3 at) {
		if (p.level() == level) {
			p.teleportTo(at.x, at.y, at.z);
		} else {
			p.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), p.getYRot(), p.getXRot(), true);
		}
	}

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
			Vfx.skill = z.exit() != null ? "portal" : "unstable_portal";
			Vec3 c = z.center();
			dev.enderskills.data.EsSettings es = dev.enderskills.data.EsSettings.get();
			// the visible ring: an Unstable Portal's size grows with the level, so it is capped
			double ring = z.exit() == null ? Math.min(z.radius(), Math.max(0.5, es.unstableMaxRadius)) : z.radius();
			double r = Math.max(0.5, ring);
			if (now % 10 == 0 && z.radius() > 0) {
				Fx.send(z.level(), z.exit() != null ? "portal_view" : "unstable_view", Fx.ZONE, System.identityHashCode(z), -1, c, null, r, 22);
			}
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
				// only stepping (almost) into the middle sends you through, not standing a block or two away
				dev.enderskills.data.EsSettings cfg = dev.enderskills.data.EsSettings.get();
				// the whole round portal works (not only its middle): the collision is a share of the visible ring
				double share = Math.max(0.1, Math.min(1.0, z.exit() != null ? cfg.portalReach : cfg.unstableReach));
				double reach = Math.max(0.3, ring * share);
				for (LivingEntity e : z.level().getEntitiesOfClass(LivingEntity.class,
						new AABB(c.x - reach - 1, c.y - 0.5, c.z - reach - 1, c.x + reach + 1, c.y + 2.5, c.z + reach + 1),
						x -> x.isAlive() && !x.isSpectator() && Math.hypot(x.getX() - c.x, x.getZ() - c.z) <= reach
								&& x.getY() >= c.y - 0.5 && x.getY() <= c.y + 2.0)) {
					UUID id = e.getUUID();
					insideNow.add(id);
					if (INSIDE.contains(id) || RECENT.containsKey(id) || UnstableTravel.busy(id)) {
						continue; // already standing in a portal: no ping-pong
					}
					if (z.exit() == null && cfg.unstableFar && e instanceof ServerPlayer thrown) {
						RECENT.put(id, now + 40);
						flash(z.level(), c);
						UnstableTravel.start(thrown, z); // far away: a random place (or dimension), with a way back
						continue;
					}
					Vec3 to = z.exit() != null ? z.exit() : randomSpot(z, e);
					if (to == null) {
						continue;
					}
					ServerLevel target = z.exitLevel() != null ? z.exitLevel() : z.level();
					if (target != z.level() && !(e instanceof ServerPlayer)) {
						continue; // only players cross dimensions
					}
					RECENT.put(id, now + 4);
					Vec3 motion = e.getDeltaMovement();
					if (e instanceof ServerPlayer traveller) {
						sendPlayer(traveller, target, to);
					} else {
						e.teleportTo(to.x, to.y, to.z);
					}
					e.setDeltaMovement(motion);
					e.resetFallDistance();
					flash(target, to);
					dev.enderskills.registry.ModEffects.play(target, to, "portal_active");
					if (z.onUse() != null && e instanceof ServerPlayer used) {
						z.onUse().accept(used);
					}
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
