package dev.enderskills.logic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.enderskills.data.EsSettings;
import dev.enderskills.network.EsNetwork;

/** 3D visual shapes drawn by the clients near an ability (light columns, beams, black holes, clouds, trails...). */
public final class Shapes {
	public static final int BEAM_UP = 0;
	public static final int BEAM_LINE = 1;
	public static final int SPHERE = 2;
	public static final int BLACK_HOLE = 3;
	public static final int CLOUD = 4;
	public static final int FIRE_RAIN = 5;
	public static final int AFTERIMAGE = 6;
	public static final int VIBRATE = 7;

	private Shapes() {
	}

	/** Color of a skill's effects: the custom color from the settings, or the default. */
	public static int color(String skill, int def) {
		Integer c = EsSettings.get().skillColors.get(skill);
		return c == null ? def : c;
	}

	public static void send(ServerLevel level, int type, Vec3 pos, double radius, double height, Vec3 dir, int color, int ticks, int entity) {
		if (type != AFTERIMAGE && type != VIBRATE && EsSettings.get().vfxStyle == EsSettings.VfxStyle.ORIGINAL) {
			return; // the client draws the original models instead
		}
		EsNetwork.VfxShape payload = new EsNetwork.VfxShape(type, pos.x, pos.y, pos.z, (float) radius, (float) height,
				dir == null ? 0F : (float) dir.x, dir == null ? 0F : (float) dir.y, dir == null ? 0F : (float) dir.z, color, ticks, entity);
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(pos) < 256 * 256 && ServerPlayNetworking.canSend(p, EsNetwork.VfxShape.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** Trail of see-through copies behind a moving player (Dash, Warp, Teleport, Speed Boost). */
	public static void afterimage(ServerPlayer p, String skill, int ticks) {
		send((ServerLevel) p.level(), AFTERIMAGE, p.position(), 0, 0, null, color(skill, 0x9A6BFF), ticks, p.getId());
	}

	/** Blurred, vibrating copies around a player (Speed Boost, like the Reverse Flash). */
	public static void vibrate(ServerPlayer p, String skill, int ticks) {
		send((ServerLevel) p.level(), VIBRATE, p.position(), 0, 0, null, color(skill, 0xFF4A2A), ticks, p.getId());
	}
}
