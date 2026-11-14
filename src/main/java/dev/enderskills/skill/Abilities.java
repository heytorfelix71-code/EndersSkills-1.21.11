package dev.enderskills.skill;

import java.util.Set;

/** Abilities whose in-game effect is already ported (filled family by family in the next versions). */
public final class Abilities {
	private static final Set<String> IMPLEMENTED = Set.of();

	private Abilities() {
	}

	public static boolean implemented(String ability) {
		return IMPLEMENTED.contains(ability);
	}
}
