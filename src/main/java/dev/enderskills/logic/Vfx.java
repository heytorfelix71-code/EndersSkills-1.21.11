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

	/** Particle count scaled by the "effects density" setting. */
	public static int n(int count) {
		return Math.max(1, (int) Math.round(count * EsSettings.get().vfxDensity));
	}

	/** Volume factor for the extra (vanilla) sounds of the abilities. */
	public static float vol() {
		EsSettings s = EsSettings.get();
		return s.extraSounds ? (float) s.soundVolume : 0.0F;
	}

	/** Glow color that goes with a particle (used for the soft halo around cores). */
	public static ParticleOptions halo(ParticleOptions core) {
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
		double len = from.distanceTo(to);
		int steps = (int) Math.max(2, len * 3 * EsSettings.get().vfxDensity);
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
		int segments = (int) Math.max(3, from.distanceTo(to) * 1.2);
		for (int i = 1; i <= segments; i++) {
			Vec3 next = from.lerp(to, i / (double) segments);
			if (i < segments) {
				next = next.add((r.nextDouble() - 0.5) * 0.7, (r.nextDouble() - 0.5) * 0.7, (r.nextDouble() - 0.5) * 0.7);
			}
			int steps = (int) Math.max(2, prev.distanceTo(next) * 5 * EsSettings.get().vfxDensity);
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
		int c = n(count);
		double phase = level.getRandom().nextDouble() * Math.PI;
		for (int i = 0; i < c; i++) {
			double a = phase + Math.PI * 2 * i / c;
			level.sendParticles(particle, center.x + Math.cos(a) * radius, center.y + 0.1, center.z + Math.sin(a) * radius, 1, 0, 0.02, 0, 0.0);
		}
	}

	/** Expanding shock wave: a few rings and an upward flash of the halo color. */
	public static void shockwave(ServerLevel level, Vec3 center, double radius, ParticleOptions core) {
		ParticleOptions halo = halo(core);
		for (double r = radius * 0.3; r <= radius; r += Math.max(0.5, radius / 4)) {
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
