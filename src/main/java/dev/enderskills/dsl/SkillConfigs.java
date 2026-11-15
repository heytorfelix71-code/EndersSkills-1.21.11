package dev.enderskills.dsl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;

import dev.enderskills.EnderSkills;
import dev.enderskills.skill.Skills;

/**
 * Skill values, read from config/enderskills/(Ability|Attribute)/.../*.cfg.
 * Missing files are written from the defaults of the original mod, so players can tweak them.
 */
public final class SkillConfigs {
	private static final Map<String, DslConfig> CONFIGS = new HashMap<>();

	private SkillConfigs() {
	}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("enderskills");
	}

	public static synchronized void load() {
		CONFIGS.clear();
		for (Skills.Skill skill : Skills.all()) {
			Path file = dir().resolve(skill.cfgPath());
			try {
				if (!Files.exists(file)) {
					Files.createDirectories(file.getParent());
					try (InputStream in = SkillConfigs.class.getResourceAsStream("/enderskills_defaults/" + skill.cfgPath())) {
						if (in != null) {
							Files.copy(in, file);
						}
					}
				}
				List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : defaults(skill);
				CONFIGS.put(skill.id(), DslConfig.parse(lines));
			} catch (IOException | RuntimeException e) {
				EnderSkills.LOGGER.error("Could not read skill config {}: {}", file, e.toString());
				try {
					CONFIGS.put(skill.id(), DslConfig.parse(defaults(skill)));
				} catch (IOException | RuntimeException ignored) {
					CONFIGS.put(skill.id(), new DslConfig());
				}
			}
		}
		EnderSkills.LOGGER.info("Loaded {} Ender Skills configs", CONFIGS.size());
	}

	private static List<String> defaults(Skills.Skill skill) throws IOException {
		try (InputStream in = SkillConfigs.class.getResourceAsStream("/enderskills_defaults/" + skill.cfgPath())) {
			if (in == null) {
				return List.of();
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		}
	}

	public static DslConfig get(String skill) {
		if (CONFIGS.isEmpty()) {
			load();
		}
		return CONFIGS.getOrDefault(skill, new DslConfig());
	}

	/** A property of a skill at a level ("COOLDOWN", "DAMAGE", "MODIFIER", ...). */
	public static double value(String skill, String property, int level) {
		return tuned(skill, property, level) * factor(property);
	}

	/** The value before the global multipliers: the screen's override (straight line) or the .cfg curve. */
	public static double tuned(String skill, String property, int level) {
		java.util.Map<String, double[]> props = dev.enderskills.data.EsSettings.get().skillTuning.get(skill);
		double[] range = props == null ? null : props.get(property);
		if (range != null && range.length == 2) {
			int cap = Math.max(1, maxLevel(skill));
			double f = Math.max(0.0, Math.min(1.0, level / (double) cap));
			Units u = Units.of(skill, property);
			return u.toInternal(range[0] + (range[1] - range[0]) * f);
		}
		return raw(skill, property, level);
	}

	/** The .cfg value. */
	public static double raw(String skill, String property, int level) {
		return get(skill).get(property, level, 1.0);
	}

	private static double factor(String property) {
		dev.enderskills.data.EsSettings s = dev.enderskills.data.EsSettings.get();
		if (property.equals("COOLDOWN")) {
			return s.cooldownMultiplier;
		}
		if (property.equals("ENDURANCE")) {
			return s.enduranceCostMultiplier;
		}
		if (property.contains("DURATION")) {
			return s.durationMultiplier;
		}
		if (property.contains("RANGE") || property.equals("SIZE") || property.equals("WIDTH")) {
			return s.rangeMultiplier;
		}
		return 1.0;
	}

	public static boolean disabled(String skill) {
		return dev.enderskills.data.EsSettings.get().disabledSkills.contains(skill);
	}

	/** XP cost of buying a level of a skill (level 0 = unlocking), before the global multipliers. */
	public static double experience(String skill, int level) {
		return tuned(skill, "XP", level);
	}

	public static int maxLevel(String skill) {
		Integer over = dev.enderskills.data.EsSettings.get().skillMaxLevel.get(skill);
		return over != null && over >= 0 ? Math.min(over, 1000) : get(skill).buyableMax();
	}

	/** Max level of the .cfg file. */
	public static int fileMaxLevel(String skill) {
		return get(skill).buyableMax();
	}
}
