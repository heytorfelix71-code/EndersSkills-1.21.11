package dev.enderskills.logic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Hasten from its top level: you walk through walls (and, holding Shift, sink through the ground). The player's
 * "no physics" flag is set before each tick's movement; the server keeps it on for the whole ability so it accepts the
 * positions the client sends, the client decides every tick whether you are in or at a wall (or holding Shift).
 */
public final class PhaseHooks {
	/** The local player (client side only). */
	public static volatile int clientPlayerId = -1;
	/** The local player walks through blocks right now. */
	public static volatile boolean clientPhasing;

	private PhaseHooks() {
	}

	public static boolean phasing(Player player) {
		if (player.level().isClientSide()) {
			return clientPhasing && player.getId() == clientPlayerId;
		}
		return player instanceof ServerPlayer sp && AbilityManager.phasing(sp);
	}
}
