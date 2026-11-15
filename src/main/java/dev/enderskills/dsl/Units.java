package dev.enderskills.dsl;

/**
 * Display units of the skill properties (the config files store ticks, health points and fractions; the settings
 * screen shows seconds, hearts and percent).
 */
public enum Units {
	SECONDS(20.0, "s"),
	HEARTS(2.0, "❤"),
	PERCENT(0.01, "%"),
	PLAIN(1.0, "");

	/** Internal value = display value x this. */
	public final double factor;
	public final String suffix;

	Units(double factor, String suffix) {
		this.factor = factor;
		this.suffix = suffix;
	}

	public static Units of(String skill, String prop) {
		if (prop.contains("COOLDOWN") || prop.contains("DURATION") || prop.contains("DELAY") || prop.contains("INTERVAL")
				|| prop.equals("REGEN") || prop.equals("STUN")) {
			return SECONDS;
		}
		if (prop.equals("MODIFIER")) {
			return switch (skill) {
				case "heart_boost" -> HEARTS;
				case "endurance", "knockback" -> PLAIN;
				default -> PERCENT;
			};
		}
		if (prop.equals("POWER")) {
			return skill.equals("energize") || skill.equals("power_drain") ? PLAIN : PERCENT;
		}
		if (prop.startsWith("TRUE_") || prop.equals("HEAL") || prop.equals("SLOW") || prop.equals("SPEED") || prop.equals("REDUCTION")
				|| prop.equals("DAMAGE_MIRROR")) {
			return PERCENT;
		}
		if (prop.contains("DAMAGE") || prop.contains("DOT") || prop.equals("HEALTH")) {
			return HEARTS;
		}
		return PLAIN;
	}

	public double toDisplay(double internal) {
		return internal / factor;
	}

	public double toInternal(double display) {
		return display * factor;
	}
}
