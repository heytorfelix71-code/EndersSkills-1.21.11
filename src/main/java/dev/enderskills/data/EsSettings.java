package dev.enderskills.data;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.enderskills.EnderSkills;
import dev.enderskills.dsl.SkillConfigs;

/** General settings (config/enderskills/settings.json). Defaults are the original mod's. */
public final class EsSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static EsSettings instance = new EsSettings();

	// ---- skill advancement
	/** Multiplies every XP cost in the skill configs. */
	public double globalCostMultiplier = 0.5;
	/** Each tree you start later costs more: cost x (1 + position of its first skill x this). */
	public double costIncrement = 0.9;
	/** Part of the spent XP you get back when you reset your skills. */
	public double retryXpReturn = 0.8;
	/** Part of the XP kept when storing it in the altar. */
	public double xpStoreTariff = 1.0;
	/** Part of the XP you get back when taking it out of the altar. */
	public double xpTakeTariff = 0.8;
	/** Only one element tree per class (Defense, Mobility, Offense). */
	public boolean oneTreePerClass = true;
	/** How many times a player can reset their skills. */
	public int maxResetUnlocks = 2;
	/** Ender Level a new player starts with. */
	public int defaultEnderLevel = 1;
	/** Ender Level progress given by one Ender Token, by current level: "(min level+){expression of x = level}". */
	public String[] levelFunction = {"(0+){0}", "(1+){1}", "(2+){x/2}", "(3+){x/4}", "(4+){x/6}", "(5+){x/8}",
			"(6+){x/10}", "(7+){x/12}", "(8+){x/14}", "(9+){x/(2 ^ x) * 0.1}"};

	// ---- endurance
	public double defaultEndurance = 30.0;

	// ---- world
	/** Chance that a monster killed by a skill drops an Ender Token. */
	public double enderTokenDropRate = 0.07;
	public boolean enderTokenDrops = true;

	// ---- hurt timer (Better Hurt Timer, built in)
	/** Each kind of damage has its own invulnerability time, so different sources can hurt at the same moment. */
	public boolean hurtTimer = true;
	/** Ticks before the same kind of damage can hurt again ("default" = kinds not listed). */
	public java.util.Map<String, Integer> hurtTimerTicks = defaultHurtTicks();
	/** Kinds of damage timed apart for each attacker (two archers can both hit you). */
	public java.util.List<String> hurtTimerPerAttacker = new java.util.ArrayList<>(java.util.List.of(
			"player", "mob", "arrow", "thrown", "trident", "indirectMagic", "fireball", "mobProjectile", "sting"));
	/** Inside the wait, a hit this much stronger than the last one still hurts. */
	public double hurtTimerDamageDifference = 0.5;

	private static java.util.Map<String, Integer> defaultHurtTicks() {
		java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
		m.put("default", 10);
		m.put("player", 5);
		m.put("mob", 10);
		m.put("fall", 0);
		m.put("flyIntoWall", 0);
		m.put("generic", 5);
		m.put("thorns", 5);
		m.put("explosion.player", 5);
		m.put("indirectMagic", 10);
		return m;
	}

	// ---- per-skill tuning (settings screen); values in display units: seconds, hearts, percent, blocks...
	/** Skill -> property -> [value at level 0, value at the max level]; replaces the .cfg curve (straight line). */
	public java.util.Map<String, java.util.Map<String, double[]>> skillTuning = new java.util.LinkedHashMap<>();
	/** Skill -> highest level that can be bought (replaces the .cfg value). */
	public java.util.Map<String, Integer> skillMaxLevel = new java.util.LinkedHashMap<>();
	/** Skills switched off: they can't be unlocked or used and give no bonus. */
	public java.util.List<String> disabledSkills = new java.util.ArrayList<>();
	/** Multipliers for every ability. */
	public double cooldownMultiplier = 1.0;
	public double enduranceCostMultiplier = 1.0;
	public double durationMultiplier = 1.0;
	public double rangeMultiplier = 1.0;

	// ---- ability power (balanced for playing with others; raise it for a power fantasy)
	/** Multiplies the damage and healing of every ability, from level 0. */
	public double abilityDamageMultiplier = 1.0;
	/** Extra power at the maximum level: a skill at its top level is (1 + this) times stronger than at level 0. */
	public double maxLevelOverdrive = 2.0;
	/** How late the overdrive kicks in (1 = steady, 3 = only near the top level). */
	public double overdriveCurve = 2.0;
	/** Ability damage against other players (PvP fairness). */
	public double playerDamageMultiplier = 0.4;
	/** Ability damage against bosses (Wither, Ender Dragon, Warden...). */
	public double bossDamageMultiplier = 0.6;
	/** Settings file version: older files get the new balance once. */
	public int balanceVersion = 0; // missing in old files (Gson keeps the field default), so 0 = migrate
	/** Extra multiplier for each skill tree, keyed "class.element" (defense.light, offense.void ...). */
	public java.util.Map<String, Double> groupPower = defaultGroups();

	private static java.util.Map<String, Double> defaultGroups() {
		java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
		for (dev.enderskills.skill.Skills.Page p : dev.enderskills.skill.Skills.PAGES) {
			m.put(p.tab().id + "." + p.element(), 1.0);
		}
		return m;
	}

	// ---- portal gun style Portal and long range Teleport
	/** Blocks between the two portals at level 0; grows up to the maximum at the top level. */
	public double portalRangeStart = 100.0;
	public double portalRangeMax = 29999984.0;
	/** How far you can aim to place a portal (the pair distance is limited by the range above). */
	public double portalAimRange = 256.0;
	public double teleportRangeStart = 100.0;
	public double teleportRangeMax = 2000.0;

	/** Portals never close by themselves (otherwise they close after a while until the top level). */
	public boolean portalAlwaysOpen = false;
	/** Seconds before portals close by themselves at level 0 and just below the top level. */
	public int portalAutoCloseStart = 30;
	public int portalAutoCloseEnd = 60;
	/** How many pairs of portals can be open at once; a new pair past this closes the oldest. */
	public int portalMaxPairs = 5;
	/** How close to the middle of a portal you must step to go through it, in blocks. */
	/** Share of the visible ring that works as the portal (0.85 = almost all of it). */
	public double portalReach = 0.85;
	/** Largest radius of the ring of an Unstable Portal, in blocks (its size grows with the level, up to 4). */
	public double unstableMaxRadius = 2.0;
	// ---- Unstable Portal: it throws you ridiculously far, with a way back
	public boolean unstableFar = true;
	public double unstableMinDistance = 5000.0;
	public double unstableMaxDistance = 100_000.0;
	public boolean unstableRandomDimension = true;
	public double unstableDimensionChance = 0.1;
	/** A portal back to the original one opens where you land when it is at least this far (or in another dimension). */
	public double unstableReturnDistance = 1000.0;
	public int unstableReturnSeconds = 180;
	/** How close to the middle of an Unstable Portal you must step to be thrown (blocks). */
	public double unstableReach = 0.85;
	/** Energy ("mana") never runs out: no costs, no limit. */
	public boolean infiniteEndurance = false;
	/** Teleport: from this level on its cooldown goes down to the top value at the last level (0 = off). */
	/**
	 * The passives of the altar are weak by design (Speed is +10% at its last level, Attack Speed +80%): these multiply what
	 * the game really applies. 1 = the original numbers.
	 */
	public double speedPassivePower = 10.0;
	/** Extra swimming push of the Swim Speed passive (0.3 = up to 30% faster at the last level). */
	public double swimSpeedPower = 0.3;
	/** How much of the water efficiency of the Swim Speed passive is used (1 = swims as fast as you run). */
	public double swimEfficiencyScale = 0.35;
	/** Extra air from the Swim Speed passive: at the last level you lose air this many times slower (before the infinite level). */
	public double swimBreathBonus = 10.0;
	/** From this Swim Speed level you see clearly under water (0 = never). */
	public int swimVisionLevel = 20;
	/** From this Swim Speed level your air never runs out and the bubbles don't even show (0 = never). */
	public int swimInfiniteAirLevel = 50;
	/** Fall Resistance also adds this many blocks of safe fall at its last level (on top of the extra jump height). */
	public double fallSafeBonus = 20.0;
	/** Dash (left Alt): how far it carries you, from its first to its last level. */
	public double dashMinBlocks = 5.0;
	public double dashMaxBlocks = 10.0;
	/** Warp / Distortion (right Alt): a short teleport through walls to where you look, from its first to its last level. */
	public double warpMinBlocks = 5.0;
	public double warpMaxBlocks = 10.0;
	/** Extra step height during the Dash, so it climbs blocks by itself (1 = one block more than normal). */
	public double dashStepHeight = 1.0;
	/** Unstable Portal stays open until you come back through the way back, from this level on (0 = never). */
	public int unstablePinLevel = 50;
	/** Pick and load the Unstable Portal destination as soon as the portal opens (faster travel, but loads far chunks early). Off = only when someone steps in. */
	public boolean unstablePreload = false;
	/** Dash and Warp need two quick taps of their key (off = one press). */
	public boolean dashDoubleTap = true;
	public double attackSpeedPower = 2.0;
	/** Damage multiplier of a critical hit (Critical Chance passive). */
	public double critMultiplier = 2.0;
	/** Multiplies the Damage passive (+100% at level 50 is a fist of 2 damage: a chicken needs two hits). */
	public double damagePassivePower = 4.0;
	/** Multiplies the Critical Chance passive (40% at its last level becomes 80%). */
	public double critChancePower = 2.0;
	/** Black Hole: extra radius at the top level, in blocks (0 to 1000); it grows with the level. */
	public double blackHoleTopBonus = 65.0;
	/** Black Hole: how far from its center it rips blocks out of the ground (with "destroy the environment"). */
	public double blackHoleMaxBlockRadius = 96.0;
	/** Black Hole: continuous damage to the mobs inside, as a share of its damage per second. */
	public double blackHoleDamagePerSecond = 0.4;
	public int teleportCooldownStartLevel = 40;
	public double teleportCooldownTopSeconds = 30.0;
	// ---- Wall and Dome
	/** From this skill level on, Wall and Dome stay until you use the ability again. */
	public int earthToggleLevel = 40;
	/** Wall width in blocks (0 = from the skill config). */
	public int wallWidth = 0;
	/** Wall height in blocks (0 = from the skill config). */
	public int wallHeight = 0;
	public int wallThickness = 1;
	/** Side walls folded towards you at both ends of the Wall, in blocks (0 = none). */
	public int wallWings = 2;
	/** Distance of the Wall in front of you (0 = automatic). */
	public double wallDistance = 3.0;
	/** Dome radius and height (0 = from the skill config). */
	public int domeRadius = 0;
	public int domeHeight = 0;
	public boolean domeRoof = true;
	/** Size multiplier of the automatic Wall size (1 = normal, 0.5 = half). */
	public double wallSizeScale = 1.0;
	/** In toggle mode (stays until used again) Wall and Dome still go away by themselves after this many seconds (0 = never). */
	public int earthToggleMaxSeconds = 120;
	/** Largest Wall width and height in blocks (0 = no limit). */
	public int wallMaxWidth = 0;
	public int wallMaxHeight = 0;
	/** Size multiplier of the automatic Dome size (1 = normal, 0.5 = half). */
	public double domeSizeScale = 1.0;
	/** Largest Dome radius and height in blocks (0 = no limit). */
	public int domeMaxRadius = 0;
	public int domeMaxHeight = 0;
	/** Build with the block found on the ground (dirt, stone, cobblestone...). */
	public boolean earthCopyGround = true;
	/** Block used when the ground can't be copied (sand, gravel, chests...) or copying is off. */
	public String earthFallbackBlock = "minecraft:cobbled_deepslate";
	/** Remove slabs, carpets, grass, flowers... in the way and put them back afterwards. */
	public boolean earthReplaceSmall = true;

	/** Invisibility: no name tag above your head and no dust while sprinting. */
	public boolean invisibilityHidesName = true;
	public boolean invisibilityNoSprintDust = true;
	/** While Invisibility is on, mobs forget you, even the ones you hit (they don't know where you are). */
	public boolean invisibilityMobsForget = true;
	/** While someone is invisible, no particle shows around them (potion swirls, steps, ability effects...). */
	public boolean invisibilityHidesParticles = true;
	/** Abilities also hurt passive and neutral mobs (off = like the original: only hostile mobs, or ones already aggressive). */
	public boolean damagePassiveMobs = false;
	/** From this Invisibility level on, taking damage no longer breaks it (0 = never breaks). */
	public int invisibilityUnbreakableLevel = 50;
	/** Hasten: from this level on, using it resets the cooldown of every other ability (0 = never). */
	public int hastenRefillLevel = 50;
	/** Hasten: its own cooldown is multiplied by this (1 = unchanged). */
	public double hastenCooldownFactor = 0.8;
	/** Hasten: from this level on, you walk through walls and, holding Shift, sink through blocks without damage (0 = never). */
	public int hastenPhaseLevel = 50;

	/** Teleport only when you aim at a block (never into the sky). */
	public boolean teleportNoSky = true;

	/** Status effects of the abilities: Minecraft's own (blindness, slowness...), the mod's own, or both. */
	public enum EffectStyle { ORIGINAL, BOTH, CURRENT }

	/** How abilities look: the original mod's models and shaders, the current particles, or both. */
	public enum VfxStyle { ORIGINAL, BOTH, CURRENT }

	public VfxStyle vfxStyle = VfxStyle.ORIGINAL;

	/** Shader packs (Iris): AUTO uses Minecraft's own render types while a shader pack is on, ON always, OFF never. */
	public enum ShaderCompat { AUTO, ON, OFF }

	public ShaderCompat shaderCompat = ShaderCompat.AUTO;

	// ---- Fog
	/** Radius of the Fog cloud around you (blocks); enemies inside are blinded. */
	public double fogRadius = 8.0;
	/** From this Fog level on, the fog stays until you use it again (or until the time below). */
	public int fogToggleLevel = 50;
	/** Longest time the fog stays at that level, in seconds. */
	public int fogMaxSeconds = 60;
	/** Extra step height while Speed Boost is on (1.4 = walks up 2 blocks). */
	public double speedBoostStep = 1.4;
	/**
	 * Strength of Speed Boost. It multiplies the speed you already have (Speed passive included): at 1 the skill's own bonus
	 * (level 50 = +100%) goes on top of it; at 2.5 (default) level 50 = +250%. 0.5 = half the bonus.
	 */
	public double speedBoostPower = 2.5;
	/** Gloom becomes a volley of shots (each at another mob) from this level on. */
	public int gloomVolleyLevel = 30;
	/** Number of Gloom shots at the max level. */
	public int gloomMaxShots = 5;
	/** Level from which Fire Spirit / Black Blessing Flame show a second rune circle around the player. */
	public int secondRuneRingLevel = 40;
	/** Shadow: from this level, shadows of you walk around and fight hostile mobs near you (2 at first). */
	public int shadowMinionLevel = 20;
	/** Shadow: energy per second for each fighting shadow (they are gone when your energy runs out). */
	public double shadowMinionDrain = 0.05;
	/** Shadow: at the top level the upkeep is multiplied by this (0.5 = half). */
	public double shadowMinionTopFactor = 0.5;
	/** Shadow: how many fighting shadows at the top level (one more every few levels until then; up to 100). */
	public int shadowMinionMax = 5;
	/** Shadow: damage of each hit of a shadow (times your ability power). */
	public double shadowMinionDamage = 4.0;
	/** Shadow: how far from you the shadows look for hostile mobs, in blocks. */
	public double shadowMinionRange = 16.0;
	/** How long the areas of the abilities stay (gas cloud, black hole, blood pool, flaming rain...): 1 = original time. */
	public double areaDurationMultiplier = 1.5;
	/** Size of the Gas Cloud (1 = original). */
	public double gasCloudSize = 1.4;
	/** Seconds the Gas Cloud holds a mob in place (it is also blinded). */
	public double gasCloudHoldSeconds = 3.0;
	/** Size and pull area of the Black Hole (1 = original). */
	public double blackHoleSize = 2.6;
	/** Extra time of the Black Hole, on top of the area time (1 = original). */
	public double blackHoleDuration = 3.0;
	/** Black Hole: how far it pulls and hurts, as a multiple of its size (the drawing keeps its size). */
	public double blackHolePullRange = 1.6;
	/** Black Hole: its cooldown starts when the hole is gone (instead of when you cast it). */
	public boolean blackHoleCooldownAfter = true;
	/** Black Hole with "destroy the environment": most blocks it removes in one tick (it spreads the crater over its life). */
	public int blackHoleBlocksPerTick = 250;
	/** Black Hole at the top level: how many blocks the crater takes in all (about 10-20% of a mountain). Grows with the level squared. */
	public int blackHoleMaxBlocks = 40000;
	/** Black Hole: how fast the blocks are sucked in (1 = slow, 2 = fast). */
	public double blackHoleSuctionSpeed = 2.0;
	/** Black Hole: how deep the crater goes at the top level, in blocks (it grows with the level from 3). */
	public int blackHoleCraterDepth = 12;
	/** What happens to Black Hole blocks left floating (you went far away and came back): they vanish or fall and drop. */
	public enum HoleBlocks { VANISH, DROP }

	public HoleBlocks blackHoleLeftover = HoleBlocks.VANISH;
	/** Seconds those floating blocks wait (counted from when they load again) before vanishing / falling. */
	public double blackHoleLeftoverSeconds = 1.5;
	/** Size of the Blood Pool (1 = original); the blood follows the ground. */
	public double bloodPoolSize = 1.4;

	/** Passive abilities (Shadow, Bleed, Life Steal, Fire Spirit): switched with a key, always on, or always on with a single tree. */
	public enum PassiveMode { BUTTON, ALWAYS, SINGLE_TREE }

	public PassiveMode passiveMode = PassiveMode.BUTTON;

	/** Abilities leave temporary things in the world (black fire, blood pools). */
	public boolean modifyEnvironment = true;
	/** Black Hole, Explode and Fireball break blocks around them. */
	public boolean destroyEnvironment = false;
	/** Seconds the black fire and the blood stay. */
	public int environmentSeconds = 6;

	/** Sounds of the abilities: the original Ender Skills sounds, the Minecraft sounds of the first port versions, or both. */
	public enum SoundStyle { ORIGINAL, MINECRAFT, BOTH }

	public SoundStyle soundStyle = SoundStyle.ORIGINAL;
	/** Per ability ("ORIGINAL", "MINECRAFT", "BOTH"; missing = the general setting). The Electric tree starts with Minecraft. */
	public java.util.Map<String, String> skillSoundStyle = new java.util.HashMap<>(java.util.Map.of(
			"shocking_aura", "MINECRAFT", "electric_pulse", "MINECRAFT", "magnetic_pull", "MINECRAFT",
			"power_drain", "MINECRAFT", "energize", "MINECRAFT", "voltaic_sentinel", "MINECRAFT"));

	public SoundStyle soundFor(String skill) {
		String s = skill == null ? null : skillSoundStyle.get(skill);
		if (s != null) {
			try {
				return SoundStyle.valueOf(s);
			} catch (IllegalArgumentException ignored) {
				// fall back to the general setting
			}
		}
		return soundStyle;
	}

	public EffectStyle effectStyle = EffectStyle.CURRENT;

	/** Sneak + ability key on Portal or Teleport asks for coordinates. */
	public boolean coordinateTargeting = true;
	/** Teleport always lands on the ground (never in the air). */
	public boolean teleportGroundOnly = true;

	// ---- sounds and visual effects
	/** Master volume of every Ender Skills sound (1 = full). */
	public double soundVolume = 0.5;
	/** Play the extra Minecraft sounds of the abilities (not only the original Ender Skills sounds). */
	public boolean extraSounds = true;
	/** Volume of each skill's original sound (1 = normal), on top of the master volume. */
	public java.util.Map<String, Double> skillVolume = defaultVolumes();
	/** Skill -> particle amount multiplier (1 = normal, 0 = none). */
	public java.util.Map<String, Double> skillParticles = new java.util.LinkedHashMap<>();
	/** Skill -> custom RGB color of its glows, shapes and trails (absent = automatic). */
	public java.util.Map<String, Integer> skillColors = new java.util.LinkedHashMap<>();
	/** Most particles a single effect may send (protects weak computers). */
	public int maxParticlesPerEffect = 120;
	/** 3D shapes: light columns, beams, black holes, clouds, fire rain. */
	public boolean vfxShapes = true;
	/** See-through trail behind Dash, Warp, Teleport and Speed Boost. */
	public boolean vfxTrails = true;
	/** Blurred vibrating copies around a player under Speed Boost (like the Reverse Flash). */
	public boolean vfxVibrate = true;
	/** Amount of particles in the visual effects (1 = normal, 2 = double...). */
	public double vfxDensity = 1.0;

	private static java.util.Map<String, Double> defaultVolumes() {
		java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
		for (dev.enderskills.skill.Skills.Skill s : dev.enderskills.skill.Skills.all()) {
			if (s.isAbility()) {
				m.put(s.id(), 1.0);
			}
		}
		return m;
	}

	/** In creative mode every skill can be unlocked and upgraded for free, without the tree rules. */
	public boolean creativeFree = false;

	/** Background behind the skill tree screen. */
	public enum ScreenBackground { COSMIC, BLUR, TRANSPARENT }

	public ScreenBackground altarBackground = ScreenBackground.COSMIC;

	/** HUD look: modern panel, the vertical list of 1.0-1.2, or the original mod's boss-bar style. */
	public enum HudStyle { MODERN, LIST, ORIGINAL }

	public HudStyle hudStyle = HudStyle.MODERN;
	/** HUD size (1 = normal). */
	public double hudScale = 1.0;
	/** Opacity of the HUD background panel (0 = invisible, 1 = solid). */
	public double hudPanelOpacity = 0.7;
	/** Also show Dash, Warp, Smash, Hover and Extra Jump in the HUD. */
	public boolean hudShowSpecial = false;
	/** Name of the current ability group above the hearts, food and XP bar. */
	public boolean hudGroupLabel = true;

	// ---- HUD (colors are RGB)
	/** Colored frame on the screen while you have an Ender Skills status effect. */
	public boolean statusOverlay = true;
	public boolean hudVisible = true;
	public int hudX = 5;
	public int hudY = 5;
	public int hudBarColor = 0x3C8CFF;
	public int hudTextColor = 0xB0D0FF;
	public int hudReadyColor = 0xFFFFFF;
	public int hudCooldownColor = 0xFF8080;
	public int hudActiveColor = 0x80FF80;

	public static void save() {
		Path file = SkillConfigs.dir().resolve("settings.json");
		try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(instance, w);
		} catch (IOException e) {
			EnderSkills.LOGGER.error("Could not save {}: {}", file, e.toString());
		}
	}

	public static EsSettings get() {
		return instance;
	}

	public static void load() {
		Path file = SkillConfigs.dir().resolve("settings.json");
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					EsSettings read = GSON.fromJson(r, EsSettings.class);
					if (read != null) {
						if (read.balanceVersion < 2) {
							// 1.11.0: the old power (62x at level 50) one-shot bosses; move to the fair balance once
							EsSettings fresh = new EsSettings();
							read.abilityDamageMultiplier = fresh.abilityDamageMultiplier;
							read.maxLevelOverdrive = fresh.maxLevelOverdrive;
							read.overdriveCurve = fresh.overdriveCurve;
							read.playerDamageMultiplier = fresh.playerDamageMultiplier;
							read.bossDamageMultiplier = fresh.bossDamageMultiplier;
							read.balanceVersion = 2;
						}
						if (read.balanceVersion < 3) {
							// 1.18.0: a bigger and longer Black Hole, and a slightly wider portal
							EsSettings fresh = new EsSettings();
							read.blackHoleSize = fresh.blackHoleSize;
							read.blackHoleDuration = fresh.blackHoleDuration;
							read.portalReach = fresh.portalReach;
							read.balanceVersion = 3;
						}
						if (read.balanceVersion < 4) {
							// 1.19.1: calmer swimming (it was hard to control), a little more running speed
							EsSettings fresh = new EsSettings();
							read.swimSpeedPower = fresh.swimSpeedPower;
							read.speedPassivePower = fresh.speedPassivePower;
							read.balanceVersion = 4;
						}
						if (read.balanceVersion < 5) {
							// 1.19.2: the collision follows the ring of the portal instead of its middle only
							EsSettings fresh = new EsSettings();
							read.portalReach = fresh.portalReach;
							read.unstableReach = fresh.unstableReach;
							read.unstableDimensionChance = fresh.unstableDimensionChance; // 10%
							read.balanceVersion = 5;
						}
						if (read.balanceVersion < 6) {
							// 1.20.0: the Speed passive doubles you at its last level (it adds up with the Monk), calmer water
							EsSettings fresh = new EsSettings();
							read.speedPassivePower = fresh.speedPassivePower;
							read.swimEfficiencyScale = fresh.swimEfficiencyScale;
							read.balanceVersion = 6;
						}
						if (read.balanceVersion < 7) {
							// 1.20.1: a Black Hole that sucks more blocks, faster, into a bigger crater
							EsSettings fresh = new EsSettings();
							read.blackHoleBlocksPerTick = fresh.blackHoleBlocksPerTick;
							read.blackHoleMaxBlockRadius = fresh.blackHoleMaxBlockRadius;
							read.balanceVersion = 7;
						}
						if (read.balanceVersion < 8) {
							// 1.20.2: Speed Boost makes you much faster on top of the Speed passive
							read.speedBoostPower = new EsSettings().speedBoostPower;
							read.balanceVersion = 8;
						}
						if (read.balanceVersion < 9) {
							// 1.20.3: Warp up to 10 blocks
							read.warpMaxBlocks = new EsSettings().warpMaxBlocks;
							read.balanceVersion = 9;
						}
						if (read.balanceVersion < 10) {
							// 1.20.6: a bigger Black Hole that carves a crater of tens of thousands of blocks, efficiently
							EsSettings fresh = new EsSettings();
							read.blackHoleSize = fresh.blackHoleSize;
							read.blackHoleTopBonus = fresh.blackHoleTopBonus;
							read.blackHoleBlocksPerTick = fresh.blackHoleBlocksPerTick;
							read.blackHoleMaxBlocks = fresh.blackHoleMaxBlocks;
							read.balanceVersion = 10;
						}
						if (read.balanceVersion < 11) {
							// 1.20.13: arriving a million blocks away made heavy world generation freeze some games; 100000 by default
							if (read.unstableMaxDistance >= 1_000_000.0) {
								read.unstableMaxDistance = new EsSettings().unstableMaxDistance;
							}
							read.balanceVersion = 11;
						}
						instance = read;
					}
				}
			}
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException | RuntimeException e) {
			EnderSkills.LOGGER.error("Could not read {}: {}", file, e.toString());
		}
	}
}
