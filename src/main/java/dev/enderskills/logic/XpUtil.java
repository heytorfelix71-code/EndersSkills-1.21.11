package dev.enderskills.logic;

import net.minecraft.world.entity.player.Player;

/** Experience points of a player (levels converted to points). */
public final class XpUtil {
	private XpUtil() {
	}

	/** Points needed to go from level 0 to the given level. */
	public static int forLevel(int level) {
		if (level <= 16) {
			return level * level + 6 * level;
		}
		if (level <= 31) {
			return (int) (2.5 * level * level - 40.5 * level + 360);
		}
		return (int) (4.5 * level * level - 162.5 * level + 2220);
	}

	public static int total(Player player) {
		return forLevel(player.experienceLevel) + Math.round(player.experienceProgress * player.getXpNeededForNextLevel());
	}

	/** Takes points from the player (never below zero). */
	public static void take(Player player, int points) {
		player.giveExperiencePoints(-Math.min(points, total(player)));
	}
}
