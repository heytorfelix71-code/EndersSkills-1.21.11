package dev.enderskills.skill;

import java.util.List;

import dev.enderskills.data.EsSettings;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;

/**
 * Unlock and upgrade rules of the altar screen, ported from the original conditions:
 * <ul>
 * <li>starter: any altar (power 0.164);</li>
 * <li>first pair: the starter, and not the other skill of the pair (both once you own the ultimate); power 0.470;</li>
 * <li>second pair: one skill of the first pair, and not the other skill of the pair (both after the ultimate,
 * if you also own both first-pair skills); power 0.739;</li>
 * <li>ultimate: one skill of the second pair, on an Ultimate Altar;</li>
 * <li>class attributes: the first 3 need the starter of a page of the class, the other 3 a first-pair skill and power 0.470.</li>
 * </ul>
 */
public final class SkillTree {
	public static final double POWER_STARTER = 0.1641791044776119;
	public static final double POWER_TIER_1 = 0.4701492537313433;
	public static final double POWER_TIER_2 = 0.7388059701492538;

	private SkillTree() {
	}

	/** Altar power from the number of Crystal Blocks around it (original formula; 32 crystals = full). */
	public static double altarPower(int crystals, boolean ultimate) {
		if (ultimate || crystals >= 32) {
			return 1.0;
		}
		if (crystals > 10) {
			return POWER_TIER_2 + 0.26119402985074625 / 32.0 * crystals;
		}
		if (crystals > 2) {
			return POWER_TIER_1 + 0.26865671641791045 / 10.0 * crystals;
		}
		if (crystals > 0) {
			return POWER_STARTER + 0.30597014925373145 / 2.0 * crystals;
		}
		return POWER_STARTER;
	}

	/** Why a skill can't be bought right now, or null if the tree allows it (costs are checked apart). */
	public static String blocked(SkillData data, String skillId, double power, boolean ultimate) {
		Skills.Skill skill = Skills.get(skillId);
		if (skill == null) {
			return "unknown";
		}
		int level = data.level(skillId);
		if (level >= SkillConfigs.maxLevel(skillId)) {
			return "max";
		}
		if (!skill.isAbility()) {
			List<String> attrs = Skills.ATTRIBUTES.get(skill.tab());
			int index = attrs.indexOf(skillId);
			boolean starter = false;
			boolean firstPair = false;
			for (Skills.Page page : Skills.pages(skill.tab())) {
				starter |= data.owns(page.starter());
				firstPair |= data.owns(page.abilities().get(1)) || data.owns(page.abilities().get(2));
			}
			if (index < 3) {
				return starter ? null : "needs_starter";
			}
			if (!firstPair) {
				return "needs_previous";
			}
			return power >= POWER_TIER_1 ? null : "needs_power";
		}
		Skills.Page page = Skills.pageOf(skillId);
		int slot = page.abilities().indexOf(skillId);
		List<String> a = page.abilities();
		boolean ownsUltimate = data.owns(a.get(5));
		switch (slot) {
			case 0 -> {
				if (!data.owns(skillId) && EsSettings.get().oneTreePerClass) {
					for (Skills.Page other : Skills.pages(page.tab())) {
						if (other != page && data.owns(other.starter())) {
							return "one_tree";
						}
					}
				}
				return power >= POWER_STARTER ? null : "needs_power";
			}
			case 1, 2 -> {
				if (!data.owns(a.get(0))) {
					return "needs_previous";
				}
				String other = a.get(slot == 1 ? 2 : 1);
				if (data.owns(other) && !ownsUltimate && !data.owns(skillId)) {
					return "either_or";
				}
				return power >= POWER_TIER_1 ? null : "needs_power";
			}
			case 3, 4 -> {
				if (!data.owns(a.get(1)) && !data.owns(a.get(2))) {
					return "needs_previous";
				}
				String other = a.get(slot == 3 ? 4 : 3);
				if (data.owns(other) && !ownsUltimate && !data.owns(skillId)) {
					return "either_or";
				}
				if (!data.owns(skillId) && ownsUltimate && !(data.owns(a.get(1)) && data.owns(a.get(2)))) {
					return "needs_both";
				}
				return power >= POWER_TIER_2 ? null : "needs_power";
			}
			default -> {
				if (!data.owns(a.get(3)) && !data.owns(a.get(4))) {
					return "needs_previous";
				}
				return ultimate ? null : "needs_ultimate";
			}
		}
	}

	/** Ender Level needed (abilities only, when unlocking: your N-th ability needs EL N). */
	public static int levelsNeeded(SkillData data, String skillId) {
		Skills.Skill skill = Skills.get(skillId);
		if (skill == null || !skill.isAbility() || data.owns(skillId)) {
			return 0;
		}
		return (int) data.abilityCount() + 1;
	}

	/** XP cost of the next level of a skill. */
	public static int xpCost(SkillData data, String skillId) {
		Skills.Skill skill = Skills.get(skillId);
		if (skill == null) {
			return 0;
		}
		int next = data.owns(skillId) ? data.level(skillId) + 1 : 0;
		EsSettings s = EsSettings.get();
		int cost = (int) (SkillConfigs.experience(skillId, next) * s.globalCostMultiplier);
		if (!skill.isAbility()) {
			return cost;
		}
		Skills.Page page = Skills.pageOf(skillId);
		int index = data.order().indexOf(page.starter());
		if (index == -1) {
			index = data.order().size();
		}
		return (int) (cost * (1.0 + index * s.costIncrement));
	}

	/** Ender Level progress one Ender Token gives at the given level. */
	public static double tokenProgress(int level) {
		double best = 0.0;
		int bestMin = Integer.MIN_VALUE;
		for (String f : EsSettings.get().levelFunction) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\(([+\\-\\d]+)\\)\\{(.+)\\}$").matcher(f.trim());
			if (!m.matches()) {
				continue;
			}
			String cond = m.group(1);
			int min = Integer.parseInt(cond.replace("+", "").replace("-", "").isEmpty() ? "0" : cond.replace("+", ""));
			if (level >= min && min >= bestMin) {
				try {
					best = dev.enderskills.dsl.Expr.eval(m.group(2), java.util.Map.of("x", (double) level, "y", (double) Integer.MAX_VALUE));
					bestMin = min;
				} catch (RuntimeException ignored) {
					// bad expression in the settings: skip it
				}
			}
		}
		return best;
	}
}
