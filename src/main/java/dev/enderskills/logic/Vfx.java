package dev.enderskills.logic;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import dev.enderskills.data.EsSettings;

/**
 * Visual effects shared by all abilities: glowing cores, halos, rings, shock waves and forked lightning.
 * The bright particles (end rod, electric spark, flames, soul fire, glow) are emissive, so shader packs make them bloom.
 */
public final class Vfx {
	private Vfx() {
	}

	/** Skill whose effects are being drawn right now (for its particle amount and color). */
	public static String skill = "";

	/** Above 0 while ability code runs: in "original" style its particles are not sent (the client draws the original look). */
	public static int casting;

	public static boolean quiet() {
		return casting > 0 && EsSettings.get().vfxStyle == EsSettings.VfxStyle.ORIGINAL;
	}

	/** Particles that are part of the gameplay (the Fog cloud): sent in every style. */
	public static void loud(Runnable run) {
		int was = casting;
		casting = 0;
		try {
			run.run();
		} finally {
			casting = was;
		}
	}

	public static void quietly(Runnable run) {
		casting++;
		try {
			run.run();
		} finally {
			casting--;
		}
	}

	/** Particle count scaled by the global and the skill's particle settings, never above the cap. */
	public static int n(int count) {
		EsSettings s = EsSettings.get();
		double k = s.vfxDensity * s.skillParticles.getOrDefault(skill, 1.0);
		if (k <= 0) {
			return 0;
		}
		return Math.max(1, Math.min(Math.max(1, s.maxParticlesPerEffect), (int) Math.round(count * k)));
	}

	/** Heavy particles (gusts, emitters, explosions) replaced by light ones in rings, beams and halos. */
	public static ParticleOptions light(ParticleOptions p) {
		if (p == ParticleTypes.GUST || p == ParticleTypes.GUST_EMITTER_SMALL || p == ParticleTypes.GUST_EMITTER_LARGE
				|| p == ParticleTypes.SWEEP_ATTACK) {
			return ParticleTypes.CLOUD;
		}
		if (p == ParticleTypes.EXPLOSION || p == ParticleTypes.EXPLOSION_EMITTER) {
			return ParticleTypes.POOF;
		}
		return p;
	}

	/** Volume factor for the extra (vanilla) sounds of the abilities. */
	public static float vol() {
		EsSettings s = EsSettings.get();
		if (!s.extraSounds) {
			return 0.0F;
		}
		// "Original" sound style: only the Ender Skills sound plays (when the ability has one)
		if (s.soundFor(skill) == EsSettings.SoundStyle.ORIGINAL && !skill.isEmpty() && dev.enderskills.registry.ModEffects.hasSound(skill)) {
			return 0.0F;
		}
		return (float) (s.soundVolume * s.skillVolume.getOrDefault(skill, 1.0));
	}

	/** Glow color that goes with a particle (used for the soft halo around cores). */
	public static ParticleOptions halo(ParticleOptions core) {
		Integer custom = EsSettings.get().skillColors.get(skill);
		if (custom != null) {
			return new DustParticleOptions(custom, 1.4F);
		}
		if (core == ParticleTypes.ELECTRIC_SPARK) {
			return new DustParticleOptions(0x8FE8FF, 1.2F);
		}
		if (core == ParticleTypes.FLAME || core == ParticleTypes.LAVA || core == ParticleTypes.FALLING_LAVA) {
			return new DustParticleOptions(0xFF8A2A, 1.4F);
		}
		if (core == ParticleTypes.SOUL_FIRE_FLAME) {
			return new DustParticleOptions(0x6A3CFF, 1.5F);
		}
		if (core == ParticleTypes.END_ROD) {
			return new DustParticleOptions(0xFFF4C0, 1.2F);
		}
		if (core == ParticleTypes.SQUID_INK || core == ParticleTypes.SCULK_SOUL || core == ParticleTypes.REVERSE_PORTAL) {
			return new DustParticleOptions(0x5A1E8C, 1.5F);
		}
		if (core == ParticleTypes.DAMAGE_INDICATOR) {
			return new DustParticleOptions(0xB00010, 1.3F);
		}
		if (core == ParticleTypes.GUST || core == ParticleTypes.SWEEP_ATTACK || core == ParticleTypes.CLOUD) {
			return new DustParticleOptions(0xDDF6FF, 1.0F);
		}
		return ParticleTypes.END_ROD;
	}

	/** A glowing point: the core particle plus a soft halo and a sparkle now and then. */
	public static void glow(ServerLevel level, Vec3 at, ParticleOptions core, double spread) {
		ParticleOptions c = light(core);
		if (c != core && level.getRandom().nextInt(4) == 0) {
			level.sendParticles(core, at.x, at.y, at.z, 1, spread, spread, spread, 0.0);
		}
		core = c;
		if (n(2) == 0) {
			return;
		}
		level.sendParticles(core, at.x, at.y, at.z, n(2), spread, spread, spread, 0.0);
		level.sendParticles(halo(core), at.x, at.y, at.z, n(2), spread * 1.8 + 0.05, spread * 1.8 + 0.05, spread * 1.8 + 0.05, 0.0);
		if (level.getRandom().nextInt(4) == 0) {
			level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 1, spread, spread, spread, 0.01);
		}
	}

	/** A beam between two points: dense core with a halo. Electric beams fork like lightning. */
	public static void beam(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions core, double width) {
		if (core == ParticleTypes.ELECTRIC_SPARK) {
			lightning(level, from, to);
			return;
		}
		core = light(core);
		double len = from.distanceTo(to);
		int steps = Math.min(Math.max(2, EsSettings.get().maxParticlesPerEffect * 2), (int) Math.max(2, n((int) (len * 3))));
		ParticleOptions halo = halo(core);
		for (int i = 0; i <= steps; i++) {
			Vec3 at = from.lerp(to, i / (double) steps);
			level.sendParticles(core, at.x, at.y, at.z, 1, width * 0.15, width * 0.15, width * 0.15, 0.0);
			if (i % 2 == 0) {
				level.sendParticles(halo, at.x, at.y, at.z, 1, width * 0.4 + 0.05, width * 0.4 + 0.05, width * 0.4 + 0.05, 0.0);
			}
		}
	}

	/** Forked, jagged lightning between two points (bright sparks + blue-white glow). */
	public static void lightning(ServerLevel level, Vec3 from, Vec3 to) {
		RandomSource r = level.getRandom();
		DustParticleOptions glow = new DustParticleOptions(0xBFF4FF, 1.0F);
		Vec3 prev = from;
		int segments = (int) Math.min(40, Math.max(3, from.distanceTo(to) * 1.2));
		for (int i = 1; i <= segments; i++) {
			Vec3 next = from.lerp(to, i / (double) segments);
			if (i < segments) {
				next = next.add((r.nextDouble() - 0.5) * 0.7, (r.nextDouble() - 0.5) * 0.7, (r.nextDouble() - 0.5) * 0.7);
			}
			int steps = Math.min(12, (int) Math.max(2, n((int) (prev.distanceTo(next) * 5))));
			for (int k = 0; k <= steps; k++) {
				Vec3 at = prev.lerp(next, k / (double) steps);
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0.02, 0.02, 0.02, 0.0);
				if (k % 2 == 0) {
					level.sendParticles(glow, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0.0);
				}
			}
			if (r.nextInt(3) == 0 && i < segments) { // a small fork
				Vec3 fork = next.add((r.nextDouble() - 0.5) * 1.5, (r.nextDouble() - 0.5) * 1.5, (r.nextDouble() - 0.5) * 1.5);
				for (int k = 0; k <= 4; k++) {
					Vec3 at = next.lerp(fork, k / 4.0);
					level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0, 0, 0, 0.0);
				}
			}
			prev = next;
		}
		level.sendParticles(ParticleTypes.END_ROD, to.x, to.y, to.z, n(6), 0.15, 0.15, 0.15, 0.05);
	}

	/** A flat ring on the ground. */
	public static void ring(ServerLevel level, Vec3 center, double radius, ParticleOptions particle, int count) {
		particle = light(particle);
		int c = Math.min(n(count), 64);
		double phase = level.getRandom().nextDouble() * Math.PI;
		for (int i = 0; i < c; i++) {
			double a = phase + Math.PI * 2 * i / c;
			level.sendParticles(particle, center.x + Math.cos(a) * radius, center.y + 0.1, center.z + Math.sin(a) * radius, 1, 0, 0.02, 0, 0.0);
		}
	}

	/** Expanding shock wave: a few rings and an upward flash of the halo color. */
	public static void shockwave(ServerLevel level, Vec3 center, double radius, ParticleOptions core) {
		core = light(core);
		ParticleOptions halo = halo(core);
		for (double r = radius * 0.3; r <= radius; r += Math.max(0.5, radius / 3)) {
			ring(level, center, r, core, (int) (r * 10));
			ring(level, center, r + 0.2, halo, (int) (r * 6));
		}
		level.sendParticles(halo, center.x, center.y + 0.8, center.z, n(20), 0.4, 0.8, 0.4, 0.02);
	}

	/** Rising spiral around a point (casting aura). */
	public static void spiral(ServerLevel level, Vec3 center, double radius, ParticleOptions core) {
		for (int i = 0; i < n(16); i++) {
			double a = i * 0.7;
			level.sendParticles(core, center.x + Math.cos(a) * radius, center.y + i * 0.13, center.z + Math.sin(a) * radius, 1, 0, 0, 0, 0.0);
		}
	}
}
