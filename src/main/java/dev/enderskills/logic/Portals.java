package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.enderskills.data.EsSettings;
import dev.enderskills.network.EsNetwork;

/**
 * The Portal ability's portals, per player: any number of linked pairs (1st and 2nd use make a pair, 3rd and 4th
 * another...), plus the first portal of the next pair, waiting for its partner. Managed from the portal menu
 * (sneak + ability key): close pairs, move a portal to typed coordinates, or put the waiting portal at coordinates.
 */
public final class Portals {
	static final class Pair {
		final int id;
		final ServerLevel level;
		Vec3 a;
		Vec3 b;
		final int until;
		Zones.Zone za;
		Zones.Zone zb;

		Pair(int id, ServerLevel level, Vec3 a, Vec3 b, int until) {
			this.id = id;
			this.level = level;
			this.a = a;
			this.b = b;
			this.until = until;
		}

		void open() {
			za = new Zones.Zone(level, a, 1.0, until, b, 0);
			zb = new Zones.Zone(level, b, 1.0, until, a, 0);
			Zones.add(za);
			Zones.add(zb);
		}

		void shut() {
			Zones.remove(za);
			Zones.remove(zb);
		}
	}

	/** The first portal of the next pair. */
	public record Waiting(ServerLevel level, Vec3 at, Zones.Zone marker) {
	}

	private static final class Mine {
		final List<Pair> pairs = new ArrayList<>();
		Waiting waiting;
		int nextId = 1;
	}

	private static final Map<UUID, Mine> ALL = new HashMap<>();
	private static int now;

	private Portals() {
	}

	private static Mine mine(ServerPlayer p) {
		return ALL.computeIfAbsent(p.getUUID(), u -> new Mine());
	}

	/** Something is still open (the player's ability state must be kept). */
	public static boolean any(UUID id) {
		Mine m = ALL.get(id);
		return m != null && (!m.pairs.isEmpty() || m.waiting != null);
	}

	public static Waiting waiting(ServerPlayer p) {
		Mine m = ALL.get(p.getUUID());
		return m == null ? null : m.waiting;
	}

	/** The first portal of a new pair (from aiming, or from coordinates typed in the menu). */
	public static void setWaiting(ServerPlayer p, ServerLevel level, Vec3 at) {
		Mine m = mine(p);
		if (m.waiting != null) {
			Zones.remove(m.waiting.marker());
		}
		Zones.Zone marker = new Zones.Zone(level, at, 0.0, Integer.MAX_VALUE, null, 0);
		Zones.add(marker);
		m.waiting = new Waiting(level, at, marker);
		Zones.flash(level, at);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.PORTAL_AMBIENT, SoundSource.PLAYERS, Vfx.vol() * 0.5F, 1.4F);
		sync(p);
	}

	/** Links the waiting portal with a new one: a new pair. Past the maximum the oldest pair closes. */
	public static void link(ServerPlayer p, Vec3 second, int until) {
		Mine m = mine(p);
		Waiting w = m.waiting;
		if (w == null) {
			return;
		}
		Zones.remove(w.marker());
		m.waiting = null;
		pair(p, w.level(), w.at(), second, until);
	}

	/** A new pair of linked portals right away (portal menu: entrance in front of you, exit at the coordinates). */
	public static void pair(ServerPlayer p, ServerLevel level, Vec3 a, Vec3 b, int until) {
		Mine m = mine(p);
		Pair pair = new Pair(m.nextId++, level, a, b, until);
		pair.open();
		m.pairs.add(pair);
		int max = Math.max(1, EsSettings.get().portalMaxPairs);
		while (m.pairs.size() > max) {
			m.pairs.remove(0).shut();
		}
		Zones.flash(level, a);
		Zones.flash(level, b);
		level.playSound(null, b.x, b.y, b.z, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, Vfx.vol() * 0.4F, 1.6F);
		sync(p);
	}

	/** Closes one pair (id), or everything (id -1, the waiting portal too). */
	public static void close(ServerPlayer p, int id) {
		Mine m = ALL.get(p.getUUID());
		if (m == null) {
			return;
		}
		for (java.util.Iterator<Pair> it = m.pairs.iterator(); it.hasNext();) {
			Pair pair = it.next();
			if (id == -1 || pair.id == id) {
				pair.shut();
				it.remove();
			}
		}
		if (id == -1 && m.waiting != null) {
			Zones.remove(m.waiting.marker());
			m.waiting = null;
		}
		p.level().playSound(null, p.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, Vfx.vol() * 0.3F, 1.8F);
		sync(p);
	}

	/** Takes the waiting portal away. */
	public static void cancelWaiting(ServerPlayer p) {
		Mine m = ALL.get(p.getUUID());
		if (m != null && m.waiting != null) {
			Zones.remove(m.waiting.marker());
			m.waiting = null;
		}
		sync(p);
	}

	/** Moves one portal of a pair (which 0 = A, 1 = B) to new coordinates; false when the pair would be too far apart. */
	public static boolean move(ServerPlayer p, int id, int which, Vec3 to, double range) {
		Mine m = ALL.get(p.getUUID());
		if (m == null) {
			return false;
		}
		for (Pair pair : m.pairs) {
			if (pair.id != id) {
				continue;
			}
			if (!pair.level.getWorldBorder().isWithinBounds(BlockPos.containing(to))) {
				return false;
			}
			Vec3 other = which == 0 ? pair.b : pair.a;
			if (other.distanceTo(to) > range) {
				return false;
			}
			pair.shut();
			if (which == 0) {
				pair.a = to;
			} else {
				pair.b = to;
			}
			pair.open();
			Zones.flash(pair.level, to);
			sync(p);
			return true;
		}
		return false;
	}

	/** Pairs that ran out of time are dropped (their zones are removed by Zones). */
	static void tick(int tick) {
		now = tick;
		for (Map.Entry<UUID, Mine> e : ALL.entrySet()) {
			if (e.getValue().pairs.removeIf(pair -> pair.until <= now)) {
				ServerPlayer p = AbilityManager.player(e.getKey());
				if (p != null) {
					sync(p);
				}
			}
		}
		ALL.values().removeIf(m -> m.pairs.isEmpty() && m.waiting == null);
	}

	/** Server stopping: nothing may keep a level of the old world. */
	public static void clear() {
		ALL.clear();
		Zones.clear();
	}

	/** Sends the list to the player (shown by the portal menu when it is open). */
	public static void sync(ServerPlayer p) {
		if (!ServerPlayNetworking.canSend(p, EsNetwork.PortalList.TYPE)) {
			return;
		}
		Mine m = ALL.get(p.getUUID());
		List<double[]> list = new ArrayList<>();
		double[] wait = new double[0];
		if (m != null) {
			for (Pair pair : m.pairs) {
				double left = pair.until == Integer.MAX_VALUE ? -1 : Math.max(0, (pair.until - now) / 20.0);
				list.add(new double[] {pair.id, pair.a.x, pair.a.y, pair.a.z, pair.b.x, pair.b.y, pair.b.z, left});
			}
			if (m.waiting != null) {
				wait = new double[] {m.waiting.at().x, m.waiting.at().y, m.waiting.at().z};
			}
		}
		ServerPlayNetworking.send(p, new EsNetwork.PortalList(list, wait));
	}
}
