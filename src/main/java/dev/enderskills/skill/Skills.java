package dev.enderskills.skill;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every skill of the original mod and the skill tree layout of its altar screen:
 * 3 classes (tabs), each with element pages. A page has a starter, two either/or pairs, an ultimate
 * and 6 class attributes shared by all pages of the class.
 */
public final class Skills {
	public enum Kind { ABILITY, ATTRIBUTE }

	public enum Tab {
		DEFENSE("defense", 0x6597CB),
		MOBILITY("mobility", 0x329CA2),
		OFFENSE("offense", 0xAF3A3E);

		public final String id;
		public final int color;

		Tab(String id, int color) {
			this.id = id;
			this.color = color;
		}
	}

	/** A skill: an ability (used with a key or passive) or an attribute (permanent stat). */
	public record Skill(String id, Kind kind, Tab tab, String cfgPath) {
		public boolean isAbility() {
			return kind == Kind.ABILITY;
		}
	}

	/** One element page of a tab: [starter, pairA1, pairA2, pairB1, pairB2, ultimate]. */
	public record Page(Tab tab, String element, List<String> abilities) {
		public String starter() {
			return abilities.get(0);
		}

		public String ultimate() {
			return abilities.get(5);
		}

		/** Column (0..6, left to right) of a slot in the tree, as in the original screen. */
		public static int column(int slot) {
			return switch (slot) {
				case 0 -> 0;
				case 1, 2 -> 2;
				case 3, 4 -> 4;
				default -> 6;
			};
		}

		/** Row (0..2) of a slot in the tree. */
		public static int row(int slot) {
			return switch (slot) {
				case 1, 3 -> 0;
				case 2, 4 -> 2;
				default -> 1;
			};
		}
	}

	public static final Map<String, Skill> ALL = new LinkedHashMap<>();
	public static final List<Page> PAGES = new ArrayList<>();
	/** Attributes of each tab: first 3 need the starter, the other 3 need a first-pair ability. */
	public static final Map<Tab, List<String>> ATTRIBUTES = new LinkedHashMap<>();

	static {
		page(Tab.DEFENSE, "light", "Defense/Light", "charm", "heal_aura", "power_boost", "heal_other", "heal_self", "nearby_invincibility");
		page(Tab.DEFENSE, "earth", "Defense/Earth", "taunt", "wall", "dome", "thorny", "shockwave", "animated_stone_golem");
		page(Tab.DEFENSE, "electric", "Defense/Electric", "shocking_aura", "electric_pulse", "magnetic_pull", "power_drain", "energize", "voltaic_sentinel");
		page(Tab.DEFENSE, "fire", "Defense/Fire", "flares", "blazing_aura", "ring_of_fire", "overheat", "warm_heart", "home_star");
		page(Tab.MOBILITY, "wind", "Mobility/Wind", "dash", "extra_jump", "fog", "smash", "hasten", "speed_boost");
		page(Tab.MOBILITY, "void", "Mobility/Void", "warp", "invisibility", "hover", "unstable_portal", "portal", "teleport");
		page(Tab.OFFENSE, "void", "Offence/Void", "shadow", "gloom", "shadow_jab", "gas_cloud", "grasp", "black_hole");
		page(Tab.OFFENSE, "blood", "Offence/Blood", "bleed", "blood_pool", "contaminate", "life_steal", "syphon", "sacrifice");
		page(Tab.OFFENSE, "wind", "Offence/Wind", "slash", "push", "pull", "crush", "updraft", "suffocate");
		page(Tab.OFFENSE, "fire", "Offence/Fire", "fire_spirit", "flaming_breath", "flaming_rain", "focus_flame", "fireball", "explode");
		page(Tab.OFFENSE, "light", "Offence/Light", "radiant_ray", "lumen_wave", "gleam_flash", "solar_lance", "barrage_wisps", "final_flash");
		page(Tab.OFFENSE, "black_flame", "Offence/BlackFlame", "black_flame_ball", "black_scouring_flame", "black_blessing_flame",
				"black_raging_flame_ball", "black_volley_flame", "black_flare_flame");

		attributes(Tab.DEFENSE, "Defense", "explosion_resistance", "damage_resistance", "knockback_resistance",
				"magic_resistance", "heart_boost", "fire_resistance");
		attributes(Tab.MOBILITY, "Mobility", "jump_height", "speed", "fall_resistance", "endurance", "stealth_damage", "swim_speed");
		attributes(Tab.OFFENSE, "Offence", "damage", "attack_speed", "knockback", "ability_power", "critical_chance", "armor_penetration");
	}

	private Skills() {
	}

	private static void page(Tab tab, String element, String folder, String... ids) {
		for (String id : ids) {
			ALL.put(id, new Skill(id, Kind.ABILITY, tab, "Ability/" + folder + "/" + id + ".cfg"));
		}
		PAGES.add(new Page(tab, element, List.of(ids)));
	}

	private static void attributes(Tab tab, String folder, String... ids) {
		for (String id : ids) {
			ALL.put(id, new Skill(id, Kind.ATTRIBUTE, tab, "Attribute/" + folder + "/" + id + ".cfg"));
		}
		ATTRIBUTES.put(tab, List.of(ids));
	}

	public static Skill get(String id) {
		return ALL.get(id);
	}

	public static Collection<Skill> all() {
		return ALL.values();
	}

	public static List<Page> pages(Tab tab) {
		return PAGES.stream().filter(p -> p.tab() == tab).toList();
	}

	/** The page an ability belongs to (null for attributes). */
	public static Page pageOf(String abilityId) {
		for (Page p : PAGES) {
			if (p.abilities().contains(abilityId)) {
				return p;
			}
		}
		return null;
	}
}
