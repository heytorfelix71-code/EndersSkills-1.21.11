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
		double bonus = 0.0;
		if (player instanceof ServerPlayer sp) {
			bonus += AttributeEffects.modifier(sp, "speed");
		} else if (player.level().isClientSide()) {
			SkillData data = player.getAttachedOrElse(Progression.DATA, null);
			if (data != null && data.owns("speed")) {
				bonus += SkillConfigs.value("speed", "MODIFIER", data.level("speed"));
			}
		}
		return bonus == 0.0 ? speed : (float) (speed * (1.0 + bonus));
	}
}
