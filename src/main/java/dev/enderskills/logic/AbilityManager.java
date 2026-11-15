package dev.enderskills.logic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.enderskills.data.EsSettings;
import dev.enderskills.data.SkillData;
import dev.enderskills.dsl.SkillConfigs;
import dev.enderskills.network.EsNetwork;
import dev.enderskills.registry.ModContent;
import dev.enderskills.skill.Abilities;

/** Server side of the abilities: endurance, cooldowns, timed effects and what each ability does. */
public final class AbilityManager {
	/** True while an ability is dealing damage (so melee attributes don't apply, and kills can drop Ender Tokens). */
	public static boolean skillDamage;

	private static final class State {
		double endurance = -1;
		int lastUse = -10000;
		final Map<String, Integer> cooldownUntil = new HashMap<>();
		final Map<String, Integer> activeUntil = new HashMap<>();
		int jumpsUsed;
		boolean smashing;
		double smashStartY;
		int smashStarted;
		boolean dirty = true;
		float sentEndurance = -1;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();
	private static final Map<Mob, Integer> FOGGED = new WeakHashMap<>();
	/** Players that were already told what their passives do (tick when to tell them). */
	private static final Map<UUID, Integer> REPORT_AT = new HashMap<>();
	private static int now;

	private AbilityManager() {
	}

	private static State state(ServerPlayer player) {
		return STATES.computeIfAbsent(player.getUUID(), u -> new State());
	}

	public static void forget(ServerPlayer player) {
		REPORT_AT.remove(player.getUUID());
		State st = STATES.get(player.getUUID());
		if (st != null) {
			STATES.remove(player.getUUID()); // portals live in Portals and stay open
		}
	}

	private static double maxEndurance(ServerPlayer player) {
		if (EsSettings.get().infiniteEndurance) {
			return 1.0E15;
		}
		MobEffectInstance potion = player.getEffect(dev.enderskills.registry.ModEffects.ENDURANCE);
		return EsSettings.get().defaultEndurance + AttributeEffects.modifier(player, "endurance")
				+ (potion == null ? 0 : 10 * (potion.getAmplifier() + 1));
	}

	private static boolean active(State s, String skill) {
		return s.activeUntil.getOrDefault(skill, 0) > now;
	}

	public static double value(ServerPlayer player, String skill, String property) {
		return SkillConfigs.value(skill, property, Math.max(0, Progression.data(player).level(skill)));
	}

	/** Toggle abilities: on until used again or out of endurance (their cost is drained every second). */
	static final java.util.Set<String> TOGGLES = java.util.Set.of("shocking_aura", "blazing_aura", "flares");
	private static MinecraftServer server;

	public static ServerPlayer player(UUID id) {
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}

	/** Strength multiplier of abilities: Ability Power attribute + Power Boost. */
	public static double power(ServerPlayer player) {
		return BOOST.getOrDefault(player.getUUID(), EsSettings.get().abilityDamageMultiplier) * (1.0 + AttributeEffects.modifier(player, "ability_power") + DefenseAbilities.boost(player)
				+ (isActive(player, "sacrifice") ? value(player, "sacrifice", "POWER") : 0.0)
				+ (player.hasEffect(dev.enderskills.registry.ModEffects.ABILITY_POWER) ? 0.25 * (player.getEffect(dev.enderskills.registry.ModEffects.ABILITY_POWER).getAmplifier() + 1) : 0.0));
	}

	private static final Map<UUID, Double> BOOST = new HashMap<>();

	/** Power of the ability about to run: global multiplier x tree multiplier x overdrive that grows with the skill level. */
	private static void boostFor(ServerPlayer p, String skill) {
		EsSettings e = EsSettings.get();
		dev.enderskills.skill.Skills.Page page = dev.enderskills.skill.Skills.pageOf(skill);
		double group = page == null ? 1.0 : e.groupPower.getOrDefault(page.tab().id + "." + page.element(), 1.0);
		int cap = Math.max(1, Math.min(50, SkillConfigs.maxLevel(skill)));
		double f = Math.min(1.0, Math.max(0, Progression.data(p).level(skill)) / (double) cap);
		BOOST.put(p.getUUID(), e.abilityDamageMultiplier * group * (1.0 + e.maxLevelOverdrive * Math.pow(f, e.overdriveCurve)));
	}

	/** Distance that grows geometrically from a start value (level 0) to a maximum (level 50 or the skill's top level). */
	private static double scaled(ServerPlayer p, String skill, double start, double max) {
		int cap = Math.max(1, Math.min(50, SkillConfigs.maxLevel(skill)));
		double f = Math.min(1.0, Math.max(0, Progression.data(p).level(skill)) / (double) cap);
		start = Math.max(1.0, start);
		return start * Math.pow(Math.max(start, max) / start, f);
	}

	/** Passive on-hit abilities: owned, off cooldown and enough endurance; pays and starts the cooldown. */
	public static boolean tryPassive(ServerPlayer player, String skill) {
		if (!Progression.data(player).owns(skill) || SkillConfigs.disabled(skill)) {
			return false;
		}
		State s = state(player);
		double cost = value(player, skill, "ENDURANCE");
		boostFor(player, skill);
		if (dev.enderskills.skill.Abilities.PASSIVES.contains(skill) && dev.enderskills.skill.Abilities.passiveKeys(Progression.data(player))
				&& !active(s, skill)) {
			return false; // switched off with its key
		}
		if (s.cooldownUntil.getOrDefault(skill, 0) > now || s.endurance < cost) {
			return false;
		}
		s.endurance -= cost;
		s.lastUse = now;
		boostFor(player, skill);
		Vfx.skill = skill;
		s.cooldownUntil.put(skill, now + (int) value(player, skill, "COOLDOWN"));
		s.dirty = true;
		ModEffectsHook.sound(player, skill);
		Fx.cast(player, skill, player.position(), player.position());
		return true;
	}

	public static boolean isActive(ServerPlayer player, String skill) {
		return active(state(player), skill);
	}

	public static void setActive(ServerPlayer player, String skill, int ticks) {
		State s = state(player);
		s.activeUntil.put(skill, now + ticks);
		s.dirty = true;
	}

	public static double endurance(ServerPlayer player) {
		return Math.max(0, state(player).endurance);
	}

	public static void addEndurance(ServerPlayer player, double amount) {
		State s = state(player);
		s.endurance = Math.max(0, Math.min(maxEndurance(player), s.endurance + amount));
		s.dirty = true;
	}

	// ------------------------------------------------------------------ tick

	public static void tick(MinecraftServer srv) {
		server = srv;
		now = srv.getTickCount();
		Zones.tick(now);
		Portals.tick(now);
		UnstableTravel.tick(srv);
		ShadowMinions.tick(srv, now);
		DefenseAbilities.tick(now);
		EarthStructures.tick(now);
		OffenseAbilities.tick(now);
		if (now % 5 == 0) {
			for (Iterator<Map.Entry<Mob, Integer>> it = FOGGED.entrySet().iterator(); it.hasNext();) {
				Map.Entry<Mob, Integer> e = it.next();
				if (e.getValue() <= now || !e.getKey().isAlive()) {
					it.remove();
				} else {
					e.getKey().setTarget(null);
				}
			}
		}
		for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
			State s = state(player);
			if (active(s, "invisibility") && EsSettings.get().invisibilityMobsForget) {
				unseen(player);
			}
			if (phasing(player)) {
				player.resetFallDistance(); // sinking through blocks never hurts
			}
			if (now % 20 == 0) {
				AttributeEffects.apply(player); // every second (it changes nothing when nothing changed)
				Integer at = REPORT_AT.get(player.getUUID());
				if (at == null) {
					REPORT_AT.put(player.getUUID(), now + 120);
				} else if (at > 0 && now >= at) {
					REPORT_AT.put(player.getUUID(), 0);
					if (EsSettings.get().passiveReport) {
						AttributeEffects.report(player);
					}
				}
			}
			if (now % 5 == 0 && active(s, "fog")) {
				fogPulse(player, now % 10 == 0);
			}
			if (now % 100 == 0) {
				AttributeEffects.apply(player); // after joining, reloads and config changes too
				for (String passive : dev.enderskills.skill.Abilities.PASSIVES) {
					if (active(s, passive)) {
						passiveLook(player, passive, true); // players who came near later see it too
					}
				}
			}
			double max = maxEndurance(player);
			if (EsSettings.get().infiniteEndurance) {
				s.endurance = max; // never runs out
			}
			if (s.endurance < 0) {
				s.endurance = max;
			}
			int delay = (int) SkillConfigs.value("endurance", "DELAY", 0);
			int regen = Math.max(1, (int) SkillConfigs.value("endurance", "REGEN", 0));
			MobEffectInstance fast = player.getEffect(dev.enderskills.registry.ModEffects.ENDURANCE_REGEN);
			if (fast != null) {
				regen = Math.max(1, regen / (fast.getAmplifier() + 2));
			}
			if (s.endurance < max && now - s.lastUse >= delay && now % regen == 0) {
				s.endurance = Math.min(max, s.endurance + 1);
			}
			if (s.endurance > max) {
				s.endurance = max;
			}
			if (player.onGround()) {
				s.jumpsUsed = 0;
			}
			for (Iterator<Map.Entry<String, Integer>> it = s.activeUntil.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Integer> e = it.next();
				if (e.getValue() <= now) {
					it.remove();
					end(player, e.getKey());
					s.dirty = true;
				}
			}
			for (String toggle : TOGGLES) {
				if (active(s, toggle)) {
					if (now % 20 == 0) {
						s.endurance -= value(player, toggle, "ENDURANCE");
						s.lastUse = now;
						if (s.endurance <= 0) {
							s.endurance = 0;
							switchOff(player, s, toggle);
							continue;
						}
					}
					DefenseAbilities.aura(player, toggle);
				}
			}
			if (active(s, "hover")) {
				player.resetFallDistance();
			}
			if (s.smashing) {
				player.resetFallDistance();
				if (player.onGround() || player.isInWater() || now - s.smashStarted > 200) {
					s.smashing = false;
					if (player.onGround()) {
						smashLand(player, s);
					}
				}
			}
			if (s.dirty || (now % 10 == 0 && (float) s.endurance != s.sentEndurance)) {
				sync(player, s, max);
			}
		}
	}

	private static void sync(ServerPlayer player, State s, double max) {
		Map<String, Integer> cds = new HashMap<>();
		s.cooldownUntil.forEach((k, v) -> {
			if (v > now) {
				cds.put(k, v - now);
			}
		});
		Map<String, Integer> act = new HashMap<>();
		s.activeUntil.forEach((k, v) -> {
			if (v > now) {
				act.put(k, v - now);
			}
		});
		s.dirty = false;
		s.sentEndurance = (float) s.endurance;
		if (ServerPlayNetworking.canSend(player, EsNetwork.AbilitySync.TYPE)) {
			ServerPlayNetworking.send(player, new EsNetwork.AbilitySync((float) s.endurance, (float) max, cds, act));
		}
	}

	private static void switchOff(ServerPlayer player, State s, String toggle) {
		Fx.end(player, toggle);
		dev.enderskills.registry.ModEffects.stop((ServerLevel) player.level(), player.position(), toggle);
		s.activeUntil.remove(toggle);
		s.cooldownUntil.put(toggle, now + (int) value(player, toggle, "COOLDOWN"));
		s.dirty = true;
	}

	/** Shadow clone at your side, rune rings of Fire Spirit (2 at the max level), red look of Bleed. */
	static void passiveLook(ServerPlayer player, String skill, boolean on) {
		int ticks = on ? Integer.MAX_VALUE : 0;
		switch (skill) {
			case "shadow" -> Shapes.onPlayer(player, Shapes.CLONE, ticks, 1, false);
			case "bleed" -> Shapes.onPlayer(player, Shapes.RED, ticks, 1, false);
			case "fire_spirit" -> Shapes.onPlayer(player, Shapes.RUNES, ticks, runeRings(player, skill), false);
			default -> {
			}
		}
	}

	/** One rune circle, two from the second-ring level on (40 by default). */
	static int runeRings(ServerPlayer player, String skill) {
		int from = Math.max(1, Math.min(EsSettings.get().secondRuneRingLevel, SkillConfigs.maxLevel(skill)));
		return Progression.data(player).level(skill) >= from ? 2 : 1;
	}

	/** From the toggle level on (50 by default) Fog stays until used again or until the time limit. */
	private static boolean fogToggle(ServerPlayer player) {
		int cap = Math.max(1, Math.min(EsSettings.get().fogToggleLevel, SkillConfigs.maxLevel("fog")));
		return Progression.data(player).level("fog") >= cap;
	}

	/** Fog and smoke around the player; enemies inside are blinded and forget their target. */
	private static void fogPulse(ServerPlayer player, boolean visuals) {
		ServerLevel level = (ServerLevel) player.level();
		double r = Math.max(1.0, EsSettings.get().fogRadius);
		Vec3 c = player.position();
		// It used to send up to r*r*0.8 single particles every 5 ticks, a third of them campfire smoke that lives ~14 s:
		// thousands of big see-through particles piled up and froze the game. Now: fewer spots, a few particles each,
		// short-lived smoke only, every 10 ticks, and never more than 30 spots.
		if (visuals) {
			Vfx.loud(() -> {
				int spots = Math.max(3, Math.min(30, Vfx.n((int) (r * r * 0.35))));
				for (int i = 0; i < spots; i++) {
					double a = level.getRandom().nextDouble() * Math.PI * 2;
					double d = Math.sqrt(level.getRandom().nextDouble()) * r;
					double x = c.x + Math.cos(a) * d;
					double z = c.z + Math.sin(a) * d;
					double y = c.y + level.getRandom().nextDouble() * 2.2;
					level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 3, 0.6, 0.4, 0.6, 0.004);
					if (i % 3 == 0) {
						level.sendParticles(ParticleTypes.CLOUD, x, y, z, 2, 0.6, 0.4, 0.6, 0.0);
					}
				}
			});
		}
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(r),
				x -> x != player && x.isAlive() && !x.isSpectator() && x.distanceToSqr(player) <= r * r && !DefenseAbilities.isAlly(player, x))) {
			status(e, null, MobEffects.BLINDNESS, 60, 0);
			if (e instanceof Mob mob) {
				mob.setTarget(null);
				FOGGED.put(mob, now + 40);
			}
		}
	}

	/** A timed ability ran out. */
	private static void end(ServerPlayer player, String skill) {
		dev.enderskills.registry.ModEffects.stop((ServerLevel) player.level(), player.position(), skill);
		if (skill.equals("hasten")) {
			rescueFromBlocks(player);
		}
		if (skill.equals("speed_boost")) {
			AttributeEffects.set(player, Attributes.MOVEMENT_SPEED, "speed_boost", 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
			AttributeEffects.set(player, Attributes.STEP_HEIGHT, "speed_boost_step", 0, AttributeModifier.Operation.ADD_VALUE);
		}
	}

	// ------------------------------------------------------------------ using abilities

	/** Portal / Teleport aimed at typed coordinates (sneak + ability key). */
	public static void useAt(ServerPlayer player, String skill, Vec3 target) {
		if (!EsSettings.get().coordinateTargeting || !(skill.equals("portal") || skill.equals("teleport"))
				|| !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)) {
			return;
		}
		if (skill.equals("portal")) {
			portalMenu(player, dev.enderskills.network.EsNetwork.PortalAction.WAIT_AT, 0, 0, target, "");
			return;
		}
		use(player, skill, 0, 0, target);
	}

	/**
	 * The portal menu (sneak + Portal key): list, close, move a portal, or make a pair: the entrance in front of you, the exit
	 * at the coordinates in the dimension you picked ("" = the one you are in).
	 */
	public static void portalMenu(ServerPlayer player, int action, int id, int which, Vec3 target, String dimId) {
		SkillData data = Progression.data(player);
		if (!data.owns("portal") || SkillConfigs.disabled("portal") || !player.isAlive()) {
			return;
		}
		boolean finite = Double.isFinite(target.x) && Double.isFinite(target.y) && Double.isFinite(target.z)
				&& Math.abs(target.x) < 3.0E7 && Math.abs(target.z) < 3.0E7 && Math.abs(target.y) < 1.0E5;
		ServerLevel level = (ServerLevel) player.level();
		ServerLevel destLevel = level;
		if (dimId != null && !dimId.isEmpty()) {
			net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(dimId);
			destLevel = key == null ? null : level.getServer().getLevel(
					net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, key));
			if (destLevel == null) {
				return;
			}
		}
		switch (action) {
			case dev.enderskills.network.EsNetwork.PortalAction.CLOSE -> Portals.close(player, id);
			case dev.enderskills.network.EsNetwork.PortalAction.CANCEL_WAIT -> Portals.cancelWaiting(player);
			case dev.enderskills.network.EsNetwork.PortalAction.MOVE -> {
				if (!finite || !EsSettings.get().coordinateTargeting) {
					return;
				}
				double range = scaled(player, "portal", EsSettings.get().portalRangeStart, EsSettings.get().portalRangeMax);
				if (!Portals.move(player, id, which, destLevel, target, range)) {
					tooFar(player, range);
					Portals.sync(player);
				}
			}
			case dev.enderskills.network.EsNetwork.PortalAction.WAIT_AT -> {
				if (!finite || !EsSettings.get().coordinateTargeting
						|| !destLevel.getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(target))) {
					return;
				}
				State s = state(player);
				double cost = value(player, "portal", "ENDURANCE");
				if (s.endurance < cost) {
					player.displayClientMessage(net.minecraft.network.chat.Component.translatable("enderskills.no_endurance",
							net.minecraft.network.chat.Component.translatable("skill.enderskills.portal")), true);
					return;
				}
				double range = scaled(player, "portal", EsSettings.get().portalRangeStart, EsSettings.get().portalRangeMax);
				Vec3 entrance = player.position().add(player.getViewVector(1.0F).multiply(2, 0, 2));
				if (destLevel == level && entrance.distanceTo(target) > range) {
					tooFar(player, range); // between dimensions there is no distance limit
					return;
				}
				s.endurance -= cost;
				s.lastUse = now;
				s.dirty = true;
				Portals.pair(player, level, entrance, destLevel, target, portalLife(player));
			}
			default -> Portals.sync(player);
		}
	}

	public static void use(ServerPlayer player, String skill, float dx, float dz) {
		use(player, skill, dx, dz, null);
	}

	/** Endurance full and every cooldown over (command). */
	public static void refill(ServerPlayer player) {
		State s = state(player);
		s.endurance = maxEndurance(player);
		s.cooldownUntil.clear();
		s.dirty = true;
	}

	private static void tooFar(ServerPlayer player, double range) {
		player.displayClientMessage(net.minecraft.network.chat.Component.translatable("enderskills.portal.too_far", (long) range), true);
	}

	/** Free spot for the player at (or above) a point; with "ground only" it is moved down onto the ground. */
	private static Vec3 safeSpot(ServerLevel level, ServerPlayer player, Vec3 point, boolean ground) {
		Vec3 base = player.position();
		Vec3 spot = null;
		for (double up = 0; up <= 64; up += 1.0) {
			Vec3 at = point.add(0, up, 0);
			if (level.noCollision(player, player.getBoundingBox().move(at.subtract(base)))) {
				spot = at;
				break;
			}
		}
		if (spot == null || !ground) {
			return spot;
		}
		for (int i = 0; i < 400; i++) {
			AABB box = player.getBoundingBox().move(spot.subtract(base));
			if (!level.noCollision(player, box.move(0, -0.2, 0)) || level.containsAnyLiquid(box.move(0, -0.2, 0))) {
				return spot;
			}
			spot = spot.add(0, -0.5, 0);
			if (spot.y < level.getMinY()) {
				return null;
			}
		}
		return null;
	}

	private static boolean moveTo(ServerLevel level, ServerPlayer player, Vec3 spot) {
		Vec3 base = player.position();
		level.sendParticles(ParticleTypes.PORTAL, base.x, base.y + 1, base.z, 30, 0.3, 0.6, 0.3, 0.3);
		if (player.isPassenger()) {
			player.stopRiding();
		}
		player.teleportTo(spot.x, spot.y, spot.z);
		player.resetFallDistance();
		level.sendParticles(ParticleTypes.PORTAL, spot.x, spot.y + 1, spot.z, 30, 0.3, 0.6, 0.3, 0.3);
		return true;
	}

	/** How long portals stay open: grows from the start to the end value; never closes at the top level or with "always open". */
	private static int portalLife(ServerPlayer player) {
		EsSettings cfg = EsSettings.get();
		int cap = Math.max(1, Math.min(50, SkillConfigs.maxLevel("portal")));
		int level = Math.max(0, Progression.data(player).level("portal"));
		if (cfg.portalAlwaysOpen || level >= cap) {
			return Integer.MAX_VALUE;
		}
		double f = level / (double) cap;
		return now + (int) (20 * (cfg.portalAutoCloseStart + (cfg.portalAutoCloseEnd - cfg.portalAutoCloseStart) * f));
	}

	public static void use(ServerPlayer player, String skill, float dx, float dz, Vec3 aimAt) {
		Vec3 origin = player.position();
		Vfx.quietly(() -> {
			if (use0(player, skill, dx, dz, aimAt)) {
				Fx.cast(player, skill, origin, aimAt != null ? aimAt : aim((ServerLevel) player.level(), player,
						Math.min(64, Math.max(4, value(player, skill, "RANGE")))));
			}
		});
	}

	/** True when the ability was really used. */
	private static boolean use0(ServerPlayer player, String skill, float dx, float dz, Vec3 aimAt) {
		SkillData data = Progression.data(player);
		if (!data.owns(skill) || !Abilities.implemented(skill) || SkillConfigs.disabled(skill) || !player.isAlive() || player.isSpectator()) {
			return false;
		}
		State s = state(player);
		ServerLevel level = (ServerLevel) player.level();
		Vfx.skill = skill;
		boostFor(player, skill);
		double cost = value(player, skill, "ENDURANCE");
		Portals.Waiting waiting = skill.equals("portal") ? Portals.waiting(player) : null;
		boolean secondPortal = waiting != null && waiting.level() == level;
		if ((skill.equals("wall") || skill.equals("dome")) && EarthStructures.has(player, skill) && EarthStructures.toggleMode(player, skill)) {
			EarthStructures.remove(player, skill); // toggle mode: the second use takes the wall down
			s.activeUntil.remove(skill);
			s.cooldownUntil.put(skill, now + 20);
			s.dirty = true;
			return false;
		}
		if (dev.enderskills.skill.Abilities.PASSIVES.contains(skill)) {
			// passives: the key switches them on and off (no cost)
			if (active(s, skill)) {
				s.activeUntil.remove(skill);
				Fx.end(player, skill);
				dev.enderskills.registry.ModEffects.stop((ServerLevel) player.level(), player.position(), skill);
				passiveLook(player, skill, false);
			} else {
				s.activeUntil.put(skill, Integer.MAX_VALUE);
				ModEffectsHook.sound(player, skill);
				passiveLook(player, skill, true);
			}
			s.dirty = true;
			return false;
		}
		if (skill.equals("fog") && active(s, skill) && fogToggle(player)) {
			switchOff(player, s, skill); // from the toggle level on, using Fog again lifts it
			return false;
		}
		if (TOGGLES.contains(skill) && active(s, skill)) {
			switchOff(player, s, skill);
			return false;
		}
		if (s.cooldownUntil.getOrDefault(skill, 0) > now) {
			return false;
		}
		if (skill.equals("extra_jump") && (player.onGround() || s.jumpsUsed >= (int) value(player, skill, "JUMPS"))) {
			return false;
		}
		if (skill.equals("smash") && (player.onGround() || s.smashing)) {
			return false;
		}
		if (!secondPortal && s.endurance < cost) {
			player.displayClientMessage(net.minecraft.network.chat.Component.translatable("enderskills.no_endurance",
					net.minecraft.network.chat.Component.translatable("skill.enderskills." + skill)), true);
			return false;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		boolean done = true;
		if (skill.equals("warp") || skill.equals("teleport")) {
			Shapes.afterimage(player, skill, 10);
		}
		switch (skill) {
			case "dash" -> {
				effect(level, player, ParticleTypes.CLOUD, SoundEvents.BREEZE_JUMP);
				Shapes.afterimage(player, skill, 12);
			}
			case "extra_jump" -> {
				s.jumpsUsed++;
				player.resetFallDistance();
				effect(level, player, ParticleTypes.CLOUD, SoundEvents.BREEZE_LAND);
			}
			case "hover" -> {
				s.activeUntil.put(skill, now + (int) value(player, skill, "DURATION"));
				player.resetFallDistance();
			}
			case "smash" -> {
				s.smashing = true;
				s.smashStartY = player.getY();
				s.smashStarted = now;
			}
			case "warp" -> {
				Vec3 dir = new Vec3(dx, 0, dz);
				if (dir.lengthSqr() < 1.0E-4) {
					dir = new Vec3(look.x, 0, look.z);
				}
				dir = dir.normalize();
				done = teleport(level, player, player.position().add(0, 0.5, 0), dir, value(player, skill, "RANGE"));
			}
			case "teleport" -> {
				double range = scaled(player, skill, EsSettings.get().teleportRangeStart, EsSettings.get().teleportRangeMax);
				if (aimAt != null) {
					if (aimAt.distanceTo(player.position()) > range) {
						tooFar(player, range);
						return false;
					}
					Vec3 spot = safeSpot(level, player, aimAt, EsSettings.get().teleportGroundOnly);
					done = spot != null && moveTo(level, player, spot);
				} else {
					BlockHitResult aimHit = level.clip(new ClipContext(eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER,
							ClipContext.Fluid.NONE, player));
					if (EsSettings.get().teleportNoSky && aimHit.getType() == HitResult.Type.MISS) {
						player.displayClientMessage(net.minecraft.network.chat.Component.translatable("enderskills.teleport.no_target"), true);
						return false;
					}
					done = teleport(level, player, eye, look, range);
				}
			}
			case "speed_boost" -> {
				s.activeUntil.put(skill, now + (int) value(player, skill, "DURATION"));
				effect(level, player, ParticleTypes.CLOUD, SoundEvents.BREEZE_WIND_CHARGE_BURST.value());
				Shapes.afterimage(player, skill, (int) value(player, skill, "DURATION"));
				// the speed itself is pushed on the client (ClientAbilities.speedBoost), the step assist is an attribute
				AttributeEffects.set(player, Attributes.STEP_HEIGHT, "speed_boost_step", EsSettings.get().speedBoostStep,
						AttributeModifier.Operation.ADD_VALUE);
			}
			case "hasten" -> {
				s.activeUntil.put(skill, now + (int) value(player, skill, "DURATION"));
				effect(level, player, ParticleTypes.ENCHANT, SoundEvents.BEACON_ACTIVATE);
				Shapes.vibrate(player, skill, (int) value(player, skill, "DURATION"));
				// top level: it is a refill, every other cooldown is reset
				int refill = EsSettings.get().hastenRefillLevel;
				if (refill > 0 && Progression.data(player).level(skill) >= Math.min(refill, SkillConfigs.maxLevel(skill))) {
					s.cooldownUntil.clear();
					player.displayClientMessage(net.minecraft.network.chat.Component.translatable("enderskills.hasten.refill"), true);
				}
			}
			case "invisibility" -> {
				int duration = (int) value(player, skill, "DURATION");
				s.activeUntil.put(skill, now + duration);
				player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, duration, 0, false, false, true));
				effect(level, player, ParticleTypes.PORTAL, SoundEvents.ILLUSIONER_MIRROR_MOVE);
			}
			case "fog" -> {
				// a cloud of fog and smoke around you: everyone else inside is blinded and loses track of you
				int duration = fogToggle(player) ? 20 * Math.max(1, EsSettings.get().fogMaxSeconds) : (int) Math.max(20, value(player, skill, "DURATION"));
				s.activeUntil.put(skill, now + duration);
				fogPulse(player, true);
				level.playSound(null, player.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.8F, 0.6F);
			}
			case "unstable_portal" -> {
				Vec3 spot = aim(level, player, value(player, skill, "RANGE"));
				Zones.add(new Zones.Zone(level, spot, value(player, skill, "SIZE") / 2.0, now + (int) value(player, skill, "DURATION"),
						null, value(player, skill, "DISPLACEMENT")));
				level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.4F, 1.6F);
			}
			case "portal" -> {
				// 1st use: a portal waiting for its partner; 2nd use: the partner, linked to it (a pair). 3rd and 4th: another pair...
				EsSettings cfg = EsSettings.get();
				Vec3 spot = aim(level, player, cfg.portalAimRange);
				if (!secondPortal) {
					s.endurance -= cost;
					s.lastUse = now;
					s.dirty = true;
					Portals.setWaiting(player, level, spot);
					return false; // its partner is placed with the next use
				}
				double range = scaled(player, skill, cfg.portalRangeStart, cfg.portalRangeMax);
				if (waiting.at().distanceTo(spot) > range) {
					tooFar(player, range);
					return false;
				}
				Portals.link(player, spot, portalLife(player));
				cost = 0;
			}
			default -> {
				if (TOGGLES.contains(skill)) {
					s.activeUntil.put(skill, Integer.MAX_VALUE);
					ModEffectsHook.sound(player, skill);
					s.endurance -= cost;
					s.lastUse = now;
					s.dirty = true;
					return true; // the cooldown starts when switched off
				}
				done = DefenseAbilities.use(player, skill) || OffenseAbilities.use(player, skill);
			}
		}
		if (!done) {
			return false;
		}
		s.endurance -= cost;
		s.lastUse = now;
		ModEffectsHook.sound(player, skill);
		double cooldown = value(player, skill, "COOLDOWN");
		if (skill.equals("heal_other") && cooldown < 20) {
			cooldown = value(player, "heal_self", "COOLDOWN"); // the original config has percentages here
		}
		MobEffectInstance quick = player.getEffect(dev.enderskills.registry.ModEffects.COOLDOWN);
		if (quick != null) {
			cooldown *= Math.max(0.1, 1.0 - 0.15 * (quick.getAmplifier() + 1));
		}
		if (active(s, "hasten") && !skill.equals("hasten")) {
			cooldown *= 1.0 - Math.min(0.95, value(player, "hasten", "REDUCTION"));
		}
		if (skill.equals("hasten")) {
			cooldown *= Math.max(0.1, EsSettings.get().hastenCooldownFactor);
		}
		if (skill.equals("teleport") && EsSettings.get().teleportCooldownStartLevel > 0) {
			// levels 40 to 50: the cooldown goes down to the configured top value
			int start = EsSettings.get().teleportCooldownStartLevel;
			int lv = Progression.data(player).level(skill);
			int top = Math.max(start + 1, SkillConfigs.maxLevel(skill));
			double target = Math.min(cooldown, Math.max(1.0, EsSettings.get().teleportCooldownTopSeconds) * 20.0);
			if (lv >= start) {
				cooldown += (target - cooldown) * Math.min(1.0, (lv - start) / (double) (top - start));
			}
		}
		if (skill.equals("black_hole") && EsSettings.get().blackHoleCooldownAfter) {
			cooldown += OffenseAbilities.lastHoleTicks; // the cooldown starts when the hole is gone
		}
		if (skill.equals("portal")) {
			cooldown = 0;
		}
		if (cooldown >= 1) {
			s.cooldownUntil.put(skill, now + (int) cooldown);
		}
		s.dirty = true;
		return true;
	}


	private static void effect(ServerLevel level, ServerPlayer player, net.minecraft.core.particles.SimpleParticleType particle, SoundEvent sound) {
		level.sendParticles(particle, player.getX(), player.getY(0.3), player.getZ(), Vfx.n(20), 0.3, 0.3, 0.3, 0.02);
		Vfx.ring(level, player.position(), 1.2, Vfx.halo(particle), 18);
		Vfx.spiral(level, player.position(), 0.7, particle);
		level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.7F, 1.2F);
	}

	/** Point the player aims at (a block, or the end of the range). */
	public static Vec3 aim(ServerLevel level, ServerPlayer player, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(range));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i()).scale(0.3));
	}

	/** Moves the player along a direction until something solid, to the nearest spot where they fit. */
	private static boolean teleport(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 dir, double range) {
		Vec3 end = from.add(dir.scale(range));
		BlockHitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double distance = hit.getType() == HitResult.Type.MISS ? range : from.distanceTo(hit.getLocation()) - 0.4;
		Vec3 base = player.position();
		Vec3 offset = from.subtract(base);
		for (double d = distance; d > 0.5; d -= 0.5) {
			Vec3 feet = from.add(dir.scale(d)).subtract(0, offset.y > 1 ? 0.2 : offset.y, 0);
			for (double up = 0; up <= 1.5; up += 0.5) {
				Vec3 spot = feet.add(0, up, 0);
				AABB box = player.getBoundingBox().move(spot.subtract(base));
				if (level.noCollision(player, box) && EsSettings.get().teleportGroundOnly) {
					Vec3 ground = safeSpot(level, player, spot, true);
					if (ground == null) {
						continue;
					}
					return moveTo(level, player, ground);
				}
				if (level.noCollision(player, box)) {
					level.sendParticles(ParticleTypes.PORTAL, base.x, base.y + 1, base.z, 30, 0.3, 0.6, 0.3, 0.3);
								if (player.isPassenger()) {
						player.stopRiding();
					}
					player.teleportTo(spot.x, spot.y, spot.z);
					player.resetFallDistance();
					level.sendParticles(ParticleTypes.PORTAL, spot.x, spot.y + 1, spot.z, 30, 0.3, 0.6, 0.3, 0.3);
								return true;
				}
			}
		}
		return false;
	}

	/** The living entity the player looks at. */
	public static LivingEntity lookTarget(ServerPlayer player, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(range));
		BlockHitResult wall = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double best = wall.getType() == HitResult.Type.MISS ? range * range : eye.distanceToSqr(wall.getLocation());
		LivingEntity found = null;
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(1.0),
				x -> x != player && x.isAlive() && !x.isSpectator())) {
			var hit = e.getBoundingBox().inflate(0.3).clip(eye, end);
			if (hit.isPresent() && eye.distanceToSqr(hit.get()) < best) {
				best = eye.distanceToSqr(hit.get());
				found = e;
			}
		}
		return found;
	}

	private static void smashLand(ServerPlayer player, State s) {
		ServerLevel level = (ServerLevel) player.level();
		double fallen = s.smashStartY - player.getY();
		float damage = (float) (Math.max(0, fallen - 3.0) * (1.0 + AttributeEffects.modifier(player, "ability_power")));
		double range = value(player, "smash", "RANGE");
		int stun = (int) value(player, "smash", "STUN");
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range, 1.5, range),
				x -> x != player && x.isAlive() && !x.isSpectator())) {
			damage(player, e, damage);
			stun(e, stun);
		}
		level.sendParticles(ParticleTypes.EXPLOSION, player.getX(), player.getY() + 0.2, player.getZ(), 6, range / 2, 0.1, range / 2, 0);
		level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.2, player.getZ(), 40, range / 2, 0.1, range / 2, 0.05);
		level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.8F, 0.8F);
	}

	/** Stun (until the status effects phase: can't move and hits for almost nothing). */
	public static void stun(LivingEntity target, int ticks) {
		status(target, dev.enderskills.registry.ModEffects.STUNNED, MobEffects.SLOWNESS, ticks, 10);
		if (useVanilla()) {
			target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 10, false, true, true));
		}
		if (target instanceof Mob mob) {
			mob.getNavigation().stop();
		}
	}

	private static boolean useVanilla() {
		return EsSettings.get().effectStyle != EsSettings.EffectStyle.CURRENT;
	}

	private static boolean useCustom() {
		return EsSettings.get().effectStyle != EsSettings.EffectStyle.ORIGINAL;
	}

	/**
	 * Applies a status effect following the "status effects" setting: the mod's own effect, Minecraft's equivalent
	 * (vanilla, may be null) or both.
	 */
	public static void status(LivingEntity target, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> custom,
			net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> vanilla, int ticks, int vanillaAmplifier) {
		if (custom != null && useCustom()) {
			target.addEffect(new MobEffectInstance(custom, ticks, 0, false, true, true));
		}
		if (vanilla != null && useVanilla()) {
			target.addEffect(new MobEffectInstance(vanilla, ticks, vanillaAmplifier, false, true, true));
		}
	}

	/** Damage dealt by an ability. */
	public static void damage(ServerPlayer player, LivingEntity target, float amount) {
		if (amount <= 0) {
			return;
		}
		EsSettings cfg = EsSettings.get();
		if (target instanceof net.minecraft.world.entity.player.Player) {
			amount *= (float) cfg.playerDamageMultiplier;
		} else if (target.getType().is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalEntityTypeTags.BOSSES)
				|| target instanceof net.minecraft.world.entity.boss.wither.WitherBoss
				|| target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
				|| target instanceof net.minecraft.world.entity.monster.warden.Warden) {
			amount *= (float) cfg.bossDamageMultiplier;
		}
		if (amount <= 0) {
			return;
		}
		skillDamage = true;
		try {
			target.hurtServer((ServerLevel) player.level(), player.damageSources().indirectMagic(player, player), amount);
		} finally {
			skillDamage = false;
		}
	}

	/** Called when something dies: monsters killed by an ability may drop an Ender Token. */
	public static void onDeath(LivingEntity entity) {
		EsSettings settings = EsSettings.get();
		if (skillDamage && settings.enderTokenDrops && entity instanceof Enemy && entity.level() instanceof ServerLevel level
				&& level.getRandom().nextDouble() < settings.enderTokenDropRate) {
			level.addFreshEntity(new ItemEntity(level, entity.getX(), entity.getY() + 0.5, entity.getZ(), new ItemStack(ModContent.TOKEN)));
		}
	}

	/** Invisible: every mob around forgets the player, even one that was just hit (it doesn't know where the player is). */
	private static void unseen(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(48), Mob::isAlive)) {
			if (mob.getTarget() == player) {
				mob.setTarget(null);
			}
			if (mob.getLastHurtByMob() == player) {
				mob.setLastHurtByMob(null);
			}
			if (mob instanceof net.minecraft.world.entity.NeutralMob neutral && neutral.isAngryAt(player, level)) {
				neutral.stopBeingAngry();
			}
			var brain = mob.getBrain();
			if (brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
					&& brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET).orElse(null) == player) {
				brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
			}
			if (brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.ANGRY_AT)
					&& player.getUUID().equals(brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ANGRY_AT).orElse(null))) {
				brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ANGRY_AT);
			}
		}
	}

	/** A player with Invisibility on stands right here: particles sent from the server at this spot are dropped. */
	public static boolean nearInvisible(ServerLevel level, double x, double y, double z) {
		if (!EsSettings.get().invisibilityHidesParticles) {
			return false;
		}
		for (ServerPlayer p : level.players()) {
			State s = STATES.get(p.getUUID());
			if (s != null && active(s, "invisibility") && p.getBoundingBox().inflate(1.5, 1.0, 1.5).contains(x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/** Takes energy for things that stay around (the fighting shadows); false when it ran out. No regeneration while it drains. */
	public static boolean drain(ServerPlayer p, double amount) {
		State s = state(p);
		if (s.endurance < 0) {
			s.endurance = maxEndurance(p);
		}
		s.endurance = Math.max(0, s.endurance - amount);
		s.lastUse = now;
		s.dirty = true;
		return s.endurance > 0;
	}

	/** Energy left, from 0 to 1. */
	public static double enduranceFraction(ServerPlayer p) {
		State s = state(p);
		double max = maxEndurance(p);
		return max <= 0 ? 0 : (s.endurance < 0 ? 1.0 : s.endurance / max);
	}

	/** Walking through walls (Hasten at its top level) is on for this player. */
	public static boolean phasing(ServerPlayer p) {
		int need = EsSettings.get().hastenPhaseLevel;
		return need > 0 && isActive(p, "hasten") && Progression.data(p).level("hasten") >= Math.min(need, SkillConfigs.maxLevel("hasten"));
	}

	/** The ability ended while you were inside blocks: you are put on the first free spot above. */
	private static void rescueFromBlocks(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (level.noCollision(p, p.getBoundingBox())) {
			return;
		}
		for (int up = 1; up <= 96; up++) {
			if (level.noCollision(p, p.getBoundingBox().move(0, up, 0))) {
				p.teleportTo(p.getX(), p.getY() + up, p.getZ());
				p.resetFallDistance();
				return;
			}
		}
	}

	/** Called after a player took damage: Invisibility breaks. */
	public static void onHurt(ServerPlayer player) {
		State s = state(player);
		int keep = Math.min(EsSettings.get().invisibilityUnbreakableLevel, SkillConfigs.maxLevel("invisibility"));
		if (active(s, "invisibility") && Progression.data(player).level("invisibility") < keep) {
			s.activeUntil.remove("invisibility");
			player.removeEffect(MobEffects.INVISIBILITY);
			s.dirty = true;
		}
	}
}
