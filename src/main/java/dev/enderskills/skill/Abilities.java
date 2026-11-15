package dev.enderskills.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.enderskills.data.SkillData;

/** Abilities whose in-game effect is already ported (filled family by family). */
public final class Abilities {
	private static final Set<String> IMPLEMENTED = Set.of(
			"dash", "extra_jump", "fog", "smash", "hasten", "speed_boost",
			"warp", "invisibility", "hover", "unstable_portal", "portal", "teleport",
			"charm", "heal_aura", "power_boost", "heal_other", "heal_self", "nearby_invincibility",
			"taunt", "wall", "dome", "thorny", "shockwave", "animated_stone_golem",
			"shocking_aura", "electric_pulse", "magnetic_pull", "power_drain", "energize", "voltaic_sentinel",
			"flares", "blazing_aura", "ring_of_fire", "overheat", "warm_heart", "home_star",
			"shadow", "gloom", "shadow_jab", "gas_cloud", "grasp", "black_hole",
			"bleed", "blood_pool", "contaminate", "life_steal", "syphon", "sacrifice",
			"slash", "push", "pull", "crush", "updraft", "suffocate",
			"fire_spirit", "flaming_breath", "flaming_rain", "focus_flame", "fireball", "explode",
			"radiant_ray", "lumen_wave", "gleam_flash", "solar_lance", "barrage_wisps", "final_flash",
			"black_flame_ball", "black_scouring_flame", "black_blessing_flame", "black_raging_flame_ball",
			"black_volley_flame", "black_flare_flame");
	/** Abilities with their own trigger (double tap, jump, sneak) instead of an ability key. */
	private static final Set<String> SPECIAL = Set.of("dash", "warp", "extra_jump", "hover", "smash",
			"shadow", "bleed", "life_steal", "fire_spirit");

	private Abilities() {
	}

	public static boolean implemented(String ability) {
		return IMPLEMENTED.contains(ability);
	}

	/** Abilities used with the ability keys 1-6, in the order the player unlocked them. */
	public static List<String> slots(SkillData data) {
		List<String> list = new ArrayList<>();
		for (String id : data.order()) {
			if (data.owns(id) && IMPLEMENTED.contains(id) && !SPECIAL.contains(id)) {
				list.add(id);
			}
		}
		return list;
	}
}
