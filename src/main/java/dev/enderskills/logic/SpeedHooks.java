package dev.enderskills.logic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;

/** Speed attribute and Speed Boost without a speed attribute modifier, so the camera FOV does not stretch. */
public final class SpeedHooks {
	/** Speed Boost bonus on this client (set while the ability is active). */
	public static volatile double clientBoost;

	private SpeedHooks() {
	}

	public static float modify(Player player, float speed) {
		return speed; // Speed and Speed Boost are attribute modifiers now (see AttributeEffects)
	}
}
