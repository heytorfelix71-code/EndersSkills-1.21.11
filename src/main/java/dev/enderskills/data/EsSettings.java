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
