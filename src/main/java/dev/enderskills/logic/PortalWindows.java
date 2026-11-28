package dev.enderskills.logic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.enderskills.data.EsSettings;
import dev.enderskills.network.WindowPayloads;

/**
 * See-through portals: what is on the other side, as a small picture drawn on the portal.
 * The server traces a few thousand rays from the exit portal (the same way you look through the entrance), reading only chunks that
 * are already loaded (it never loads or generates anything for a ray), and spreads the work over several ticks, a few rows each tick.
 * Pictures are shared by everyone looking from about the same angle and kept for a couple of seconds. Nothing runs while nobody looks.
 */
public final class PortalWindows {
	public static final int SIZE = 64;
	private static final int ROWS_PER_TICK = 8;
	private static final int MAX_JOBS = 4;
	private static final double MAX_DIST = 48.0;
	private static final double TAN_HALF_FOV = Math.tan(Math.toRadians(35.0));

	private static final class Job {
		final Zones.Zone zone;
		final int view;
		final float yaw;
		final float pitch;
		final byte[] rgb = new byte[SIZE * SIZE * 3];
		final Set<UUID> viewers = new HashSet<>();
		int row;

		Job(Zones.Zone zone, int view, float yaw, float pitch) {
			this.zone = zone;
			this.view = view;
			this.yaw = yaw;
			this.pitch = pitch;
		}
	}

	private record Done(byte[] rgb, int tick) {
	}

	private static final Map<Long, Job> JOBS = new HashMap<>();
	private static final Map<Long, Done> CACHE = new HashMap<>();
	private static final Map<Integer, Integer> TICKETED = new HashMap<>();

	private PortalWindows() {
	}

	private static long key(int view, int yawBucket, int pitchBucket) {
		return ((long) view << 16) ^ ((long) (yawBucket & 0xFF) << 8) ^ (pitchBucket & 0xFF);
	}

	/** A client looks at a portal: answer from the cache or start (or join) a picture. */
	public static void request(ServerPlayer p, int view, float yaw, float pitch) {
		if (!EsSettings.get().portalWindows || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
			return;
		}
		Zones.Zone zone = Zones.byView(view);
		if (zone == null || zone.exit() == null || zone.level() != p.level() || zone.center().distanceToSqr(p.position()) > 64 * 64) {
			return;
		}
		int yb = Math.floorMod(Math.round(yaw / 10F), 36);
		int pb = Math.round(Math.max(-60F, Math.min(60F, pitch)) / 12F);
		long k = key(view, yb, pb);
		int now = p.level().getServer().getTickCount();
		Done done = CACHE.get(k);
		if (done != null && now - done.tick() < 40) {
			ServerPlayNetworking.send(p, new WindowPayloads.Image(view, SIZE, done.rgb()));
			return;
		}
		Job job = JOBS.get(k);
		if (job == null) {
			if (JOBS.size() >= MAX_JOBS) {
				return; // busy: the client asks again in a moment
			}
			job = new Job(zone, view, yb * 10F, pb * 12F);
			JOBS.put(k, job);
			ServerLevel far = zone.exitLevel() != null ? zone.exitLevel() : zone.level();
			Integer last = TICKETED.get(view);
			if (last == null || now - last > 200) { // the other side stays loaded around the exit only while someone looks
				far.getChunkSource().addTicketAndLoadWithRadius(TicketType.PORTAL, new ChunkPos(BlockPos.containing(zone.exit())), 2);
				TICKETED.put(view, now);
			}
		}
		job.viewers.add(p.getUUID());
	}

	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		if (now % 200 == 0) {
			CACHE.values().removeIf(d -> now - d.tick() > 200);
			TICKETED.values().removeIf(t -> now - t > 400);
		}
		if (JOBS.isEmpty()) {
			return;
		}
		int budget = ROWS_PER_TICK;
		for (Iterator<Map.Entry<Long, Job>> it = JOBS.entrySet().iterator(); it.hasNext() && budget > 0;) {
			Map.Entry<Long, Job> e = it.next();
			Job job = e.getValue();
			ServerLevel far = job.zone.exitLevel() != null ? job.zone.exitLevel() : job.zone.level();
			while (budget > 0 && job.row < SIZE) {
				traceRow(far, job);
				job.row++;
				budget--;
			}
			if (job.row >= SIZE) {
				it.remove();
				CACHE.put(e.getKey(), new Done(job.rgb, now));
				for (UUID id : job.viewers) {
					ServerPlayer p = server.getPlayerList().getPlayer(id);
					if (p != null && ServerPlayNetworking.canSend(p, WindowPayloads.Image.TYPE)) {
						ServerPlayNetworking.send(p, new WindowPayloads.Image(job.view, SIZE, job.rgb));
					}
				}
			}
		}
	}

	private static void traceRow(ServerLevel level, Job job) {
		Vec3 eye = job.zone.exit().add(0, 1.0, 0);
		double yaw = Math.toRadians(job.yaw);
		double pitch = Math.toRadians(job.pitch);
		Vec3 fwd = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 up = right.cross(fwd);
		int sky = skyColor(level, eye);
		float light = daylight(level);
		int j = job.row;
		double v = 1.0 - (j + 0.5) / SIZE * 2.0;
		for (int i = 0; i < SIZE; i++) {
			double u = (i + 0.5) / SIZE * 2.0 - 1.0;
			Vec3 dir = fwd.add(right.scale(u * TAN_HALF_FOV)).add(up.scale(v * TAN_HALF_FOV)).normalize();
			int c = trace(level, eye, dir, sky, light);
			int o = (j * SIZE + i) * 3;
			job.rgb[o] = (byte) (c >> 16);
			job.rgb[o + 1] = (byte) (c >> 8);
			job.rgb[o + 2] = (byte) c;
		}
	}

	/** One ray through the blocks (only loaded chunks): the color of the first visible block, fogged with distance; the sky if none. */
	private static int trace(ServerLevel level, Vec3 from, Vec3 dir, int sky, float light) {
		int x = (int) Math.floor(from.x);
		int y = (int) Math.floor(from.y);
		int z = (int) Math.floor(from.z);
		int sx = dir.x > 0 ? 1 : -1;
		int sy = dir.y > 0 ? 1 : -1;
		int sz = dir.z > 0 ? 1 : -1;
		double tdx = dir.x == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.x);
		double tdy = dir.y == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.y);
		double tdz = dir.z == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.z);
		double tmx = dir.x == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - from.x : from.x - x) * tdx);
		double tmy = dir.y == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - from.y : from.y - y) * tdy);
		double tmz = dir.z == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - from.z : from.z - z) * tdz);
		LevelChunk chunk = null;
		int ccx = Integer.MIN_VALUE;
		int ccz = Integer.MIN_VALUE;
		int face = 1; // 0 = x side, 1 = top/bottom, 2 = z side
		double t = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int water = -1;
		double waterT = 0;
		while (t < MAX_DIST) {
			if (y < level.getMinY() || y > level.getMaxY()) {
				break;
			}
			int cx = x >> 4;
			int cz = z >> 4;
			if (cx != ccx || cz != ccz) {
				chunk = level.getChunkSource().getChunkNow(cx, cz);
				ccx = cx;
				ccz = cz;
				if (chunk == null) {
					break; // not loaded: never load anything for a picture
				}
			}
			pos.set(x, y, z);
			BlockState state = chunk.getBlockState(pos);
			if (!state.isAir()) {
				FluidState fluid = state.getFluidState();
				if (!fluid.isEmpty() && water < 0) {
					if (fluid.is(FluidTags.LAVA)) {
						return fog(0xD8641A, sky, t);
					}
					water = 0x3F76E4;
					waterT = t;
				}
				if (state.getRenderShape() != RenderShape.INVISIBLE) {
					MapColor mc = state.getMapColor(level, pos);
					if (mc != MapColor.NONE && mc != MapColor.WATER) {
						float shade = face == 1 ? (dir.y < 0 ? 1.0F : 0.55F) : (face == 0 ? 0.82F : 0.7F);
						int c = scale(mc.col, shade * light);
						if (water >= 0) {
							c = mix(c, scale(water, light), Math.min(0.85, 0.45 + (t - waterT) * 0.08));
						}
						return fog(c, sky, t);
					}
				}
			}
			if (tmx < tmy && tmx < tmz) {
				t = tmx;
				tmx += tdx;
				x += sx;
				face = 0;
			} else if (tmy < tmz) {
				t = tmy;
				tmy += tdy;
				y += sy;
				face = 1;
			} else {
				t = tmz;
				tmz += tdz;
				z += sz;
				face = 2;
			}
		}
		int s = horizon(sky, dir);
		return water >= 0 ? fog(mix(scale(water, light), s, 0.3), sky, waterT) : s;
	}

	private static float daylight(ServerLevel level) {
		return Math.max(0.25F, 1.0F - level.getSkyDarken() / 11.0F * 0.75F);
	}

	private static int skyColor(ServerLevel level, Vec3 at) {
		if (level.dimension() == Level.NETHER) {
			return 0x3A0E0A;
		}
		if (level.dimension() == Level.END) {
			return 0x171022;
		}
		return mix(0x78A7FF, 0x0B1026, level.getSkyDarken() / 11.0); // day blue to night
	}

	private static int horizon(int sky, Vec3 dir) {
		double h = Math.pow(1.0 - Math.min(1.0, Math.abs(dir.y)), 3);
		return mix(sky, 0xC8DCFF, h * 0.35);
	}

	private static int fog(int c, int sky, double t) {
		double f = Math.pow(Math.min(1.0, t / MAX_DIST), 1.6);
		return mix(c, sky, f);
	}

	private static int scale(int c, float k) {
		int r = Math.min(255, (int) ((c >> 16 & 255) * k));
		int g = Math.min(255, (int) ((c >> 8 & 255) * k));
		int b = Math.min(255, (int) ((c & 255) * k));
		return r << 16 | g << 8 | b;
	}

	private static int mix(int a, int b, double f) {
		f = Math.max(0, Math.min(1, f));
		int r = (int) ((a >> 16 & 255) * (1 - f) + (b >> 16 & 255) * f);
		int g = (int) ((a >> 8 & 255) * (1 - f) + (b >> 8 & 255) * f);
		int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
		return r << 16 | g << 8 | bl;
	}

	public static void clear() {
		JOBS.clear();
		CACHE.clear();
		TICKETED.clear();
	}
}
