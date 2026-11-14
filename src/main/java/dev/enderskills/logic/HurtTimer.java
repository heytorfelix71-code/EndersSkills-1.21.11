package dev.enderskills.logic;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import dev.enderskills.data.EsSettings;

/**
 * Better Hurt Timer, built in: the single vanilla invulnerability time is replaced by one timer per kind of
 * damage (and per attacker for attacks), so fire and a zombie can hurt you in the same moment, while the same
 * source can't hit faster than its own timer.
 */
public final class HurtTimer {
	private record Hit(long tick, float amount) {
	}

	private static final Map<LivingEntity, Map<String, Hit>> HITS = new WeakHashMap<>();

	private HurtTimer() {
	}

	/** Fabric ALLOW_DAMAGE: false cancels the hit. */
	public static boolean allow(LivingEntity victim, DamageSource source, float amount) {
		EsSettings s = EsSettings.get();
		if (!s.hurtTimer || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
			return true;
		}
		victim.invulnerableTime = 0; // our timers replace the vanilla one
		String kind = source.getMsgId();
		int wait = s.hurtTimerTicks.getOrDefault(kind, s.hurtTimerTicks.getOrDefault("default", 10));
		if (wait <= 0) {
			return true;
		}
		String key = kind;
		if (s.hurtTimerPerAttacker.contains(kind) && source.getEntity() != null) {
			key = kind + ":" + source.getEntity().getUUID();
		}
		long now = victim.level().getGameTime();
		Map<String, Hit> hits = HITS.computeIfAbsent(victim, v -> new HashMap<>());
		Hit last = hits.get(key);
		if (last != null && now >= last.tick() && now - last.tick() < wait && amount <= last.amount() + s.hurtTimerDamageDifference) {
			return false;
		}
		hits.put(key, new Hit(now, amount));
		if (hits.size() > 64) {
			hits.values().removeIf(h -> now - h.tick() > 200);
		}
		return true;
	}

	/** Fabric AFTER_DAMAGE: clears the vanilla invulnerability the hit just started. */
	public static void after(LivingEntity victim) {
		if (EsSettings.get().hurtTimer) {
			victim.invulnerableTime = 0;
		}
	}
}
