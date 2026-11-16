package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.enderskills.data.EsSettings;

/**
 * The Unstable Portal throws players ridiculously far: to a random place, sometimes to another dimension. The destination
 * chunk is requested in the background (so the server never freezes), a safe spot is found there, and when it is far from the
 * portal a portal back is opened where you land: going through it dissipates it and the original portal stays.
 */
public final class UnstableTravel {
	private static final class Job {
		final Zones.Zone origin;
		ServerLevel level;
		double x;
		double z;
		int cx;
		int cz;
		int waited;
		int tries;

		Job(Zones.Zone origin) {
			this.origin = origin;
		}
	}

	private static final Map<UUID, Job> JOBS = new HashMap<>();
	/** Length of a portal that stays open until you come back (12 hours of ticks). */
	public static final int PIN = 20 * 3600 * 12;

	private UnstableTravel() {
	}

	/** Destinations picked ahead for the open unstable portals, so the way there is already loaded when someone steps in. */
	private static final Map<Zones.Zone, Job> PREP = new java.util.IdentityHashMap<>();

	/** Called every tick for an open unstable portal: picks its destination once and keeps its chunks loaded. */
	static void prepare(MinecraftServer server, Zones.Zone zone, int now) {
		Job job = PREP.get(zone);
		if (job == null) {
			job = new Job(zone);
			if (pick(server, server.overworld().getRandom(), job)) {
				PREP.put(zone, job);
			}
		} else if (now % 200 == 0) {
			job.level.getChunkSource().addTicketAndLoadWithRadius(TicketType.PORTAL, new ChunkPos(job.cx, job.cz), 1);
		}
	}

	static void forgetClosed(List<Zones.Zone> open) {
		if (!PREP.isEmpty()) {
			PREP.keySet().removeIf(z -> !open.contains(z));
		}
	}

	public static boolean busy(UUID id) {
		return JOBS.containsKey(id);
	}

	private static void say(ServerPlayer p, String key, Object... args) {
		p.displayClientMessage(Component.translatable(key, args), true);
	}

	public static void start(ServerPlayer p, Zones.Zone origin) {
		Job job = new Job(origin);
		Job ready = PREP.remove(origin);
		if (ready != null) {
			ready.waited = 0; // the destination was picked and its chunks loading since the portal opened
			JOBS.put(p.getUUID(), ready);
			say(p, "enderskills.unstable.travel");
			return;
		}
		if (!pick(p.level().getServer(), p.getRandom(), job)) {
			say(p, "enderskills.unstable.no_place");
			return;
		}
		JOBS.put(p.getUUID(), job);
		say(p, "enderskills.unstable.travel");
	}

	/** A random destination (a random dimension now and then), and its chunk requested. */
	private static boolean pick(MinecraftServer server, RandomSource rnd, Job job) {
		EsSettings c = EsSettings.get();
		
		
		ServerLevel current = job.origin.level();
		ServerLevel dest = current;
		if (c.unstableRandomDimension && rnd.nextDouble() < c.unstableDimensionChance) {
			List<ServerLevel> others = new ArrayList<>();
			for (ServerLevel l : server.getAllLevels()) {
				if (l != current) {
					others.add(l);
				}
			}
			if (!others.isEmpty()) {
				dest = others.get(rnd.nextInt(others.size()));
			}
		}
		double a = rnd.nextDouble() * Math.PI * 2;
		double x;
		double z;
		if (dest.dimension() == Level.END) { // the main island: everything else out there is void
			double r = rnd.nextDouble() * 70.0;
			x = Math.cos(a) * r;
			z = Math.sin(a) * r;
		} else {
			double min = Math.max(0, c.unstableMinDistance);
			double max = Math.max(min + 1, c.unstableMaxDistance);
			double d = min + rnd.nextDouble() * (max - min);
			x = job.origin.center().x + Math.cos(a) * d;
			z = job.origin.center().z + Math.sin(a) * d;
		}
		for (int i = 0; i < 60 && (Math.abs(x) > 29_999_000.0 || Math.abs(z) > 29_999_000.0
				|| !dest.getWorldBorder().isWithinBounds(BlockPos.containing(x, 0, z))); i++) {
			x *= 0.9; // back inside the world and its border
			z *= 0.9;
		}
		if (!dest.getWorldBorder().isWithinBounds(BlockPos.containing(x, 0, z))) {
			return false;
		}
		job.level = dest;
		job.x = x;
		job.z = z;
		job.cx = (int) Math.floor(x) >> 4;
		job.cz = (int) Math.floor(z) >> 4;
		job.waited = 0;
		dest.getChunkSource().addTicketAndLoadWithRadius(TicketType.PORTAL, new ChunkPos(job.cx, job.cz), 1);
		return true;
	}

	static void tick(MinecraftServer server) {
		if (JOBS.isEmpty()) {
			return;
		}
		for (Iterator<Map.Entry<UUID, Job>> it = JOBS.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Job> e = it.next();
			Job job = e.getValue();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || !p.isAlive()) {
				it.remove();
				continue;
			}
			job.waited++;
			if (job.level.getChunkSource().getChunkNow(job.cx, job.cz) != null) {
				Vec3 spot = landing(p, job.level, job.x, job.z);
				if (spot == null) {
					if (++job.tries < 6 && pick(server, p.getRandom(), job)) {
						continue; // nowhere safe here (void, lava...): another place
					}
					it.remove();
					say(p, "enderskills.unstable.no_place");
					continue;
				}
				it.remove();
				arrive(p, job, spot, server);
			} else if (job.waited > 20 * 90) {
				it.remove();
				say(p, "enderskills.unstable.timeout");
			} else if (job.waited % 40 == 0) {
				say(p, "enderskills.unstable.travel");
			}
		}
	}

	private static void arrive(ServerPlayer p, Job job, Vec3 spot, MinecraftServer server) {
		EsSettings c = EsSettings.get();
		Zones.sendPlayer(p, job.level, spot);
		p.resetFallDistance();
		Zones.flash(job.level, spot);
		say(p, "enderskills.unstable.arrived", (long) spot.x, (long) spot.y, (long) spot.z, job.level.dimension().identifier().getPath());
		boolean far = job.level != job.origin.level() || spot.distanceTo(job.origin.center()) >= Math.max(0, c.unstableReturnDistance);
		if (far) {
			openReturn(p, job, spot, server);
		}
	}

	/** The way back: a portal next to where you landed, to the original portal. Using it dissipates it; the original stays. */
	private static void openReturn(ServerPlayer p, Job job, Vec3 spot, MinecraftServer server) {
		EsSettings c = EsSettings.get();
		Vec3 at = null;
		int[][] offsets = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}, {4, 4}, {-4, -4}};
		for (int[] o : offsets) {
			Vec3 cand = standing(p, job.level, (int) Math.floor(spot.x) + o[0], (int) Math.floor(spot.z) + o[1]);
			if (cand != null) {
				at = cand;
				break;
			}
		}
		if (at == null) {
			at = spot.add(2, 0, 0);
		}
		Zones.Zone[] self = new Zones.Zone[1];
		int tick = server.getTickCount();
		// at the top level both portals stay open until you come back; then the one out here closes, and the first one soon after
		boolean pinned = job.origin.until() - tick > PIN / 2;
		int until = pinned ? tick + PIN : tick + 20 * Math.max(5, c.unstableReturnSeconds);
		self[0] = new Zones.Zone(job.level, at, 1.0, until, job.origin.center(), 0, job.origin.level(), who -> {
			Zones.remove(self[0]);
			if (pinned) {
				Zones.Zone o = job.origin;
				Zones.remove(o);
				Zones.add(new Zones.Zone(o.level(), o.center(), o.radius(), server.getTickCount() + 20 * 15, o.exit(), o.displacement(),
						o.exitLevel(), o.onUse()));
			}
		});
		Zones.add(self[0]);
		Zones.flash(job.level, at);
		if (pinned) {
			say(p, "enderskills.unstable.return_open");
		} else {
			say(p, "enderskills.unstable.return", Math.max(5, c.unstableReturnSeconds));
		}
	}

	// ---- a safe place to stand in the loaded destination chunk

	private static Vec3 landing(ServerPlayer p, ServerLevel level, double x, double z) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		for (int r = 0; r <= 6; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					Vec3 s = standing(p, level, bx + dx, bz + dz);
					if (s != null) {
						return s;
					}
				}
			}
		}
		return null;
	}

	private static boolean safe(ServerPlayer p, ServerLevel level, Vec3 at) {
		AABB box = p.getBoundingBox().move(at.subtract(p.position()));
		if (!level.noCollision(p, box)) {
			return false;
		}
		BlockPos feet = BlockPos.containing(at);
		for (BlockPos q : new BlockPos[] {feet, feet.above(), feet.below()}) {
			if (level.getFluidState(q).is(FluidTags.LAVA)) {
				return false;
			}
			BlockState st = level.getBlockState(q);
			if (st.is(BlockTags.FIRE) || st.is(Blocks.MAGMA_BLOCK) || st.is(Blocks.CACTUS)) {
				return false;
			}
		}
		AABB below = new AABB(box.minX, box.minY - 0.6, box.minZ, box.maxX, box.minY, box.maxZ);
		return !level.noCollision(p, below) || level.containsAnyLiquid(below.inflate(0, 0.01, 0));
	}

	private static Vec3 standing(ServerPlayer p, ServerLevel level, int x, int z) {
		int min = level.getMinY() + 1;
		int max = level.getMaxY() - 2;
		if (level.dimensionType().hasCeiling()) {
			int from = Math.max(min, Math.min(max, 70));
			for (int y = from; y >= min; y--) {
				Vec3 at = new Vec3(x + 0.5, y, z + 0.5);
				if (safe(p, level, at)) {
					return at;
				}
			}
			for (int y = from + 1; y <= max; y++) {
				Vec3 at = new Vec3(x + 0.5, y, z + 0.5);
				if (safe(p, level, at)) {
					return at;
				}
			}
			return null;
		}
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		for (int y = Math.max(min, top); y <= Math.min(max, top + 8); y++) {
			Vec3 at = new Vec3(x + 0.5, y, z + 0.5);
			if (safe(p, level, at)) {
				return at;
			}
		}
		return null;
	}

	/** Server stopping. */
	public static void clear() {
		JOBS.clear();
		PREP.clear();
	}
}
