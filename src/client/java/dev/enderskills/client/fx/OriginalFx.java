package dev.enderskills.client.fx;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;

import dev.enderskills.data.EsSettings;
import dev.enderskills.network.EsNetwork;

/**
 * The look of the original Ender Skills (1.12.2) abilities: the cosmic "universe" shaders (black hole, gas cloud, rifts,
 * solar beams), the glow sprites with the original textures, the black fists of Shadow Jab, auras and icons.
 */
public final class OriginalFx {
	private static final int CAST = 0;
	private static final int ZONE = 1;
	private static final int BOLT = 2;
	private static final int HIT = 3;
	private static final int END = 4;

	private static final int FIRE = 0xFFE077;
	private static final int VOID = 0x5C1F66; // the original shader tint (0.36, 0.12, 0.4)
	private static final int[] CLOUD_COLORS = {0x1E0034, 0x260742, 0x30104F, 0x38185B, 0x401E68, 0x472476, 0x4E2A84, 0x5B3C8C,
		0x684C96, 0x765E9F, 0x836EA9};

	private static final class Fx {
		String skill;
		int phase;
		int id;
		int entity;
		Vec3 pos;
		Vec3 to;
		Vec3 dest;
		float radius;
		int life;
		int age;
		int lastSeen;
		boolean ended;
		long seed;
		final ArrayDeque<Vec3> trail = new ArrayDeque<>();
	}

	private static final class Sprite {
		String tex;
		boolean add;
		double x, y, z, px, py, pz, vx, vy, vz;
		int rgb;
		float alpha;
		float half;
		int age;
		int life;
		float gravity;
		boolean shrink = true;
		float roll;
	}

	private static final class Fist {
		Vec3 pos;
		Vec3 prev;
		Vec3 motion;
		int age;
	}

	private static final List<Fx> EFFECTS = new ArrayList<>();
	private static final Map<Integer, Fx> BOLTS = new HashMap<>();
	private static final List<Sprite> SPRITES = new ArrayList<>();
	private static final List<Fist> FISTS = new ArrayList<>();
	private static final RandomSource R = RandomSource.create();
	private static int clock;

	private OriginalFx() {
	}

	private static boolean enabled() {
		return EsSettings.get().vfxStyle != EsSettings.VfxStyle.CURRENT;
	}

	public static void reset() {
		EFFECTS.clear();
		BOLTS.clear();
		SPRITES.clear();
		FISTS.clear();
	}

	// ------------------------------------------------------------------ incoming

	public static void add(EsNetwork.SkillFx p) {
		Minecraft mc = Minecraft.getInstance();
		if (!enabled() || mc.level == null) {
			return;
		}
		if (p.phase() == BOLT) {
			Fx b = BOLTS.get(p.id());
			if (b == null) {
				b = fx(p);
				BOLTS.put(p.id(), b);
			} else {
				b.pos = new Vec3(p.x(), p.y(), p.z());
				b.to = new Vec3(p.tx(), p.ty(), p.tz());
			}
			b.lastSeen = clock;
			b.trail.addFirst(b.pos);
			while (b.trail.size() > 12) {
				b.trail.removeLast();
			}
			return;
		}
		if (p.phase() == END) {
			if (p.id() != 0) {
				Fx b = BOLTS.remove(p.id());
				if (b != null) {
					boltEnd(mc.level, b, new Vec3(p.x(), p.y(), p.z()));
				}
			} else {
				EFFECTS.removeIf(e -> e.skill.equals(p.skill()) && e.entity == p.entity());
			}
			return;
		}
		Fx e = fx(p);
		if (p.phase() == HIT) {
			e.life = 20;
			hit(mc.level, e);
		} else {
			e.life = p.ticks() > 0 ? p.ticks() : 20;
			Entity caster = mc.level.getEntity(p.entity());
			e.dest = caster != null ? caster.position() : e.pos;
			start(mc.level, e);
		}
		if (EFFECTS.size() < 400) {
			EFFECTS.add(e);
		}
	}

	private static Fx fx(EsNetwork.SkillFx p) {
		Fx e = new Fx();
		e.skill = p.skill();
		e.phase = p.phase();
		e.id = p.id();
		e.entity = p.entity();
		e.pos = new Vec3(p.x(), p.y(), p.z());
		e.to = new Vec3(p.tx(), p.ty(), p.tz());
		e.radius = Math.max(0.3F, p.radius());
		e.seed = R.nextLong();
		return e;
	}

	// ------------------------------------------------------------------ amounts and colors

	private static int n(String skill, int count) {
		EsSettings s = EsSettings.get();
		double k = s.vfxDensity * s.skillParticles.getOrDefault(skill, 1.0);
		if (k <= 0) {
			return 0;
		}
		return Math.max(1, Math.min(Math.max(1, s.maxParticlesPerEffect), (int) Math.round(count * k)));
	}

	private static int color(String skill, int def) {
		Integer c = EsSettings.get().skillColors.get(skill);
		return c == null ? def : c;
	}

	private static Sprite sprite(String tex, boolean add, double x, double y, double z, double vx, double vy, double vz, int rgb, float half, int life) {
		Sprite s = new Sprite();
		s.tex = tex;
		s.add = add;
		s.x = s.px = x;
		s.y = s.py = y;
		s.z = s.pz = z;
		s.vx = vx;
		s.vy = vy;
		s.vz = vz;
		s.rgb = rgb;
		s.alpha = 1F;
		s.half = half;
		s.life = Math.max(1, life);
		s.roll = R.nextFloat() * 6.28F;
		if (SPRITES.size() < 4000) {
			SPRITES.add(s);
		}
		return s;
	}

	/** Original "luminescence" glow (scale like the original: half size = 0.1 x scale). */
	private static Sprite glow(double x, double y, double z, double vx, double vy, double vz, int rgb, float scale, int life) {
		return sprite("glow_particle", true, x, y, z, vx, vy, vz, rgb, scale * 0.1F, life);
	}

	private static Sprite dark(double x, double y, double z, double vx, double vy, double vz, float scale, int life) {
		return sprite("glow_particle_negative", false, x, y, z, vx, vy, vz, 0xFFFFFF, scale * 0.1F, life);
	}

	private static Sprite mote(double x, double y, double z, double vx, double vy, double vz, int rgb, float scale, int life) {
		return sprite("mote", true, x, y, z, vx, vy, vz, rgb, scale * 0.1F, life);
	}

	private static void icon(String tex, double x, double y, double z, int rgb) {
		Sprite s = sprite(tex, false, x + (R.nextDouble() - 0.5) * 0.6, y + R.nextDouble() * 0.4, z + (R.nextDouble() - 0.5) * 0.6,
				0, 0.04, 0, rgb, 0.18F, 20);
		s.shrink = false;
		s.roll = 0;
	}

	private static void vanilla(ClientLevel level, ParticleOptions p, double x, double y, double z, double vx, double vy, double vz) {
		level.addParticle(p, x, y, z, vx, vy, vz);
	}

	private static double g() {
		return R.nextGaussian();
	}

	private static double rr() {
		return R.nextDouble() - 0.5;
	}

	// ------------------------------------------------------------------ one-shot effects

	private static void start(ClientLevel level, Fx e) {
		Vec3 c = e.pos;
		Vec3 t = e.to;
		Vec3 d = e.dest;
		switch (e.skill) {
			case "explode" -> {
				for (int i = 0; i < n(e.skill, 25); i++) {
					glow(d.x + rr(), d.y + 1 + rr(), d.z + rr(), g() * 0.25, g() * 0.25, g() * 0.25, color(e.skill, FIRE), 3F + R.nextFloat() * 3F, 20);
				}
			}
			case "heal_self", "heal_other", "nearby_invincibility", "heal_aura" -> {
				Vec3 at = e.skill.equals("heal_other") ? t : d;
				for (int i = 0; i < n(e.skill, 6); i++) {
					icon("plus", at.x, at.y + 1.2, at.z, color(e.skill, 0x5CFF5C));
				}
			}
			case "taunt", "charm" -> {
				for (int i = 0; i < n(e.skill, 5); i++) {
					icon("angry", t.x, t.y + 1.6, t.z, 0xFFFFFF);
				}
			}
			case "sacrifice" -> {
				for (int i = 0; i < n(e.skill, 6); i++) {
					icon("cross", d.x, d.y + 1.2, d.z, color(e.skill, 0xC00010));
				}
			}
			case "dash", "shockwave" -> {
				BlockState ground = level.getBlockState(BlockPos.containing(c.x, c.y - 0.5, c.z));
				if (!ground.isAir()) {
					BlockParticleOption crack = new BlockParticleOption(ParticleTypes.BLOCK, ground);
					int count = e.skill.equals("dash") ? 20 : 60;
					for (int i = 0; i < n(e.skill, count); i++) {
						double a = R.nextDouble() * Math.PI * 2;
						double r = e.skill.equals("dash") ? R.nextDouble() * 0.6 : R.nextDouble() * e.radius;
						vanilla(level, crack, c.x + Math.cos(a) * r, c.y + 0.1, c.z + Math.sin(a) * r, Math.cos(a) * 0.2, 0.3, Math.sin(a) * 0.2);
					}
				}
			}
			case "extra_jump" -> {
				for (int i = 0; i < n(e.skill, 16); i++) {
					double a = Math.PI * 2 * i / 16;
					vanilla(level, ParticleTypes.CLOUD, d.x, d.y, d.z, Math.cos(a) * 0.15, -0.05, Math.sin(a) * 0.15);
				}
			}
			case "push", "pull" -> {
				boolean push = e.skill.equals("push");
				for (int i = 0; i < n(e.skill, 30); i++) {
					double a = R.nextDouble() * Math.PI * 2;
					double r = push ? 0.5 : e.radius;
					double sp = push ? 0.5 : -0.4;
					mote(d.x + Math.cos(a) * r, d.y + 1 + rr(), d.z + Math.sin(a) * r, Math.cos(a) * sp, 0, Math.sin(a) * sp,
							color(e.skill, 0xE8F6FF), 4F, 12);
				}
			}
			case "syphon" -> {
				for (int i = 0; i < n(e.skill, 20); i++) {
					double a = R.nextDouble() * Math.PI * 2;
					Vec3 from = d.add(Math.cos(a) * e.radius, 1 + rr(), Math.sin(a) * e.radius);
					Vec3 v = d.add(0, 1, 0).subtract(from).scale(1 / 15.0);
					mote(from.x, from.y, from.z, v.x, v.y, v.z, color(e.skill, 0xB00010), 3F, 15);
				}
			}
			case "energize", "power_boost", "overheat", "warm_heart", "hasten" -> {
				int col = switch (e.skill) {
					case "energize" -> 0x8FE8FF;
					case "power_boost" -> 0xB070FF;
					case "hasten" -> 0xFFFFFF;
					default -> 0xFF8A2A;
				};
				for (int i = 0; i < n(e.skill, 16); i++) {
					double a = R.nextDouble() * Math.PI * 2;
					mote(d.x + Math.cos(a) * 0.6, d.y + R.nextDouble() * 0.4, d.z + Math.sin(a) * 0.6, 0, 0.08 + R.nextDouble() * 0.06, 0,
							color(e.skill, col), 2.5F, 20);
				}
			}
			case "invisibility", "fog" -> {
				for (int i = 0; i < n(e.skill, 30); i++) {
					vanilla(level, ParticleTypes.LARGE_SMOKE, d.x + rr() * 2, d.y + R.nextDouble() * 2, d.z + rr() * 2, 0, 0.02, 0);
				}
			}
			default -> {
			}
		}
	}

	private static void hit(ClientLevel level, Fx e) {
		Vec3 c = e.pos;
		double h = Math.max(0.5, e.radius);
		switch (e.skill) {
			case "bleed", "blood_pool" -> {
				for (int i = 0; i < n(e.skill, 3); i++) {
					icon("minus", c.x, c.y + h + 0.2, c.z, color(e.skill, 0xC00010));
				}
			}
			case "contaminate" -> {
				for (int i = 0; i < n(e.skill, 2); i++) {
					icon("skull", c.x, c.y + h + 0.2, c.z, color(e.skill, 0x7CFF4A));
				}
			}
			case "life_steal" -> {
				for (int i = 0; i < n(e.skill, 8); i++) {
					mote(c.x + rr(), c.y + h * 0.6 + rr(), c.z + rr(), 0, 0.06, 0, color(e.skill, 0xB00010), 3F, 16);
				}
			}
			case "fireball", "flaming_breath", "flaming_rain", "focus_flame", "fire_spirit" -> {
				for (int i = 0; i < n(e.skill, 6); i++) {
					glow(c.x + rr() * 0.6, c.y + h * 0.5 + rr(), c.z + rr() * 0.6, 0, 0.04, 0, color(e.skill, FIRE), 4F, 12);
				}
			}
			case "black_flame_ball", "black_raging_flame_ball", "black_scouring_flame", "black_volley_flame", "black_flare_flame",
					"black_blessing_flame" -> {
				for (int i = 0; i < n(e.skill, 6); i++) {
					dark(c.x + rr() * 0.6, c.y + h * 0.5 + rr(), c.z + rr() * 0.6, 0, 0.04, 0, 5F, 14);
				}
			}
			case "gloom", "grasp", "shadow_jab", "shadow" -> {
				for (int i = 0; i < n(e.skill, 4); i++) {
					vanilla(level, ParticleTypes.SQUID_INK, c.x + rr() * 0.5, c.y + h * 0.5 + rr(), c.z + rr() * 0.5, 0, 0.02, 0);
				}
			}
			default -> {
			}
		}
	}

	private static void boltEnd(ClientLevel level, Fx b, Vec3 at) {
		switch (b.skill) {
			case "fireball" -> {
				vanilla(level, ParticleTypes.EXPLOSION, at.x, at.y, at.z, 0, 0, 0);
				for (int i = 0; i < n(b.skill, 20); i++) {
					glow(at.x, at.y, at.z, g() * 0.2, g() * 0.2, g() * 0.2, color(b.skill, FIRE), 4F + R.nextFloat() * 4F, 18);
				}
			}
			case "black_flame_ball", "black_raging_flame_ball" -> {
				vanilla(level, ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 0, 0.05, 0);
				for (int i = 0; i < n(b.skill, 20); i++) {
					dark(at.x, at.y, at.z, g() * 0.15, g() * 0.15, g() * 0.15, 6F + R.nextFloat() * 4F, 20);
				}
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ tick

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.isPaused()) {
			return;
		}
		clock++;
		for (Iterator<Fx> it = EFFECTS.iterator(); it.hasNext();) {
			Fx e = it.next();
			e.age++;
			if (e.age > e.life) {
				it.remove();
				continue;
			}
			emit(level, e);
		}
		for (Iterator<Fx> it = BOLTS.values().iterator(); it.hasNext();) {
			Fx b = it.next();
			if (clock - b.lastSeen > 4) {
				it.remove();
				continue;
			}
			b.age++;
			emitBolt(level, b);
		}
		for (Iterator<Sprite> it = SPRITES.iterator(); it.hasNext();) {
			Sprite s = it.next();
			s.px = s.x;
			s.py = s.y;
			s.pz = s.z;
			s.x += s.vx;
			s.y += s.vy;
			s.z += s.vz;
			s.vy -= s.gravity;
			if (++s.age >= s.life) {
				it.remove();
			}
		}
		for (Iterator<Fist> it = FISTS.iterator(); it.hasNext();) {
			Fist f = it.next();
			f.prev = f.pos;
			f.pos = f.pos.add(f.motion);
			if (++f.age > 5) {
				it.remove();
			}
		}
	}

	/** Particles an effect makes while it lasts (zones, channels, auras). */
	private static void emit(ClientLevel level, Fx e) {
		Entity caster = level.getEntity(e.entity);
		Vec3 c = e.phase == ZONE ? e.pos : (caster != null ? caster.position() : e.dest);
		float r = e.radius;
		switch (e.skill) {
			case "shadow_jab" -> {
				if (caster != null) {
					for (int i = 0; i < Math.max(1, n(e.skill, 1)); i++) {
						fist(caster, Math.max(2.0, Math.min(8.0, r)));
					}
				}
			}
			case "gas_cloud" -> {
				for (int i = 0; i < n(e.skill, 2); i++) {
					Sprite s = glow(c.x + rr() * r * 1.2, c.y + 0.5 + R.nextDouble() * r * 0.8, c.z + rr() * r * 1.2, 0, 0.005, 0,
							CLOUD_COLORS[R.nextInt(CLOUD_COLORS.length)] | 0x404040, 2F + (float) Math.abs(g()) * 3F, 40 + R.nextInt(40));
					s.shrink = true;
				}
			}
			case "flaming_rain" -> {
				for (int i = 0; i < n(e.skill, 2); i++) {
					double x = c.x + rr() * r * 2;
					double z = c.z + rr() * r * 2;
					Sprite s = sprite("rain", true, x, c.y + 4 + R.nextDouble() * 1.4, z, 0, -0.5, 0, color(e.skill, FIRE), 1.5F, 12);
					s.roll = 0;
					s.shrink = false;
					mote(x, c.y + 4, z, 0, -0.5, 0, color(e.skill, FIRE), 12F, 10);
				}
			}
			case "focus_flame", "ring_of_fire" -> {
				for (int i = 0; i < n(e.skill, 3); i++) {
					double a = R.nextDouble() * Math.PI * 2;
					double d = e.skill.equals("ring_of_fire") ? r : R.nextDouble() * r;
					glow(c.x + Math.cos(a) * d, c.y + 0.2, c.z + Math.sin(a) * d, 0, 0.05 + R.nextDouble() * 0.05, 0, color(e.skill, FIRE),
							3F + R.nextFloat() * 3F, 16);
				}
			}
			case "flaming_breath" -> {
				if (caster != null) {
					Vec3 eye = caster.getEyePosition();
					Vec3 look = caster.getViewVector(1F);
					for (int i = 0; i < n(e.skill, 4); i++) {
						Vec3 v = look.add(g() * 0.15, g() * 0.15, g() * 0.15).normalize().scale(0.6 + R.nextDouble() * 0.4);
						glow(eye.x + look.x, eye.y - 0.2 + look.y, eye.z + look.z, v.x, v.y, v.z, color(e.skill, FIRE), 4F + R.nextFloat() * 4F, 10);
					}
				}
			}
			case "updraft" -> {
				for (int i = 0; i < n(e.skill, 4); i++) {
					double a = e.age * 0.4 + i * 1.6;
					mote(c.x + Math.cos(a) * r * 0.6, c.y + R.nextDouble() * 0.5, c.z + Math.sin(a) * r * 0.6, 0, 0.3, 0,
							color(e.skill, 0xE8F6FF), 3F, 14);
				}
			}
			case "blood_pool" -> {
				if (R.nextInt(3) == 0) {
					Sprite s = sprite("droplet", false, c.x + rr() * r * 1.6, c.y + 0.1, c.z + rr() * r * 1.6, 0, 0.12, 0,
							color(e.skill, 0xB00010), 0.15F, 14);
					s.gravity = 0.02F;
					s.roll = 0;
				}
			}
			case "hover" -> {
				if (R.nextInt(2) == 0) {
					vanilla(level, ParticleTypes.PORTAL, c.x + rr(), c.y, c.z + rr(), rr() * 0.5, -0.3, rr() * 0.5);
				}
			}
			case "flares" -> {
				for (int i = 0; i < 3; i++) {
					double a = e.age * 0.15 + i * Math.PI * 2 / 3;
					glow(c.x + Math.cos(a) * 1.2, c.y + 1.2, c.z + Math.sin(a) * 1.2, 0, 0, 0, color(e.skill, FIRE), 4F, 4);
				}
			}
			case "speed_boost" -> {
				if (caster != null && R.nextInt(2) == 0) {
					mote(c.x + rr() * 0.6, c.y + R.nextDouble() * 1.8, c.z + rr() * 0.6, 0, 0, 0, color(e.skill, 0xFF4A2A), 2F, 8);
				}
			}
			case "syphon", "power_drain" -> {
				if (e.age % 3 == 0) {
					double a = R.nextDouble() * Math.PI * 2;
					Vec3 from = c.add(Math.cos(a) * r, 1, Math.sin(a) * r);
					Vec3 v = c.add(0, 1, 0).subtract(from).scale(1 / 12.0);
					mote(from.x, from.y, from.z, v.x, v.y, v.z, color(e.skill, e.skill.equals("syphon") ? 0xB00010 : 0x8FE8FF), 3F, 12);
				}
			}
			default -> {
			}
		}
	}

	private static void emitBolt(ClientLevel level, Fx b) {
		Vec3 p = b.pos;
		Vec3 v = b.to.subtract(b.pos);
		switch (b.skill) {
			case "fireball", "barrage_wisps" -> {
				int col = color(b.skill, b.skill.equals("fireball") ? FIRE : 0xFFFBE0);
				for (int i = 0; i < n(b.skill, 3); i++) {
					glow(p.x + rr() * 0.3, p.y + rr() * 0.3, p.z + rr() * 0.3, v.x * 0.025, v.y * 0.025, v.z * 0.025, col,
							5F + R.nextFloat() * 5F, 10);
				}
				vanilla(level, ParticleTypes.SMOKE, p.x, p.y, p.z, 0, 0, 0);
			}
			case "black_flame_ball", "black_raging_flame_ball", "black_scouring_flame", "black_volley_flame", "black_flare_flame" -> {
				for (int i = 0; i < n(b.skill, 3); i++) {
					dark(p.x + rr() * 0.3, p.y + rr() * 0.3, p.z + rr() * 0.3, 0, 0, 0, 6F + R.nextFloat() * 4F, 10);
				}
				vanilla(level, ParticleTypes.SMOKE, p.x, p.y, p.z, 0, 0, 0);
			}
			case "contaminate" -> {
				Sprite s = sprite("spit", true, p.x, p.y, p.z, 0, 0, 0, color(b.skill, 0x7CFF4A), 0.35F, 6);
				s.shrink = true;
			}
			case "gloom" -> {
				// drawn as a cosmic tube in render()
			}
			default -> {
				int col = switch (b.skill) {
					case "crush", "slash", "push", "pull", "updraft", "suffocate" -> 0xE8F6FF;
					case "bleed", "blood_pool", "syphon", "sacrifice" -> 0xB00010;
					case "lumen_wave", "gleam_flash", "solar_lance", "radiant_ray", "final_flash" -> 0xFFFBE0;
					case "electric_pulse", "magnetic_pull", "power_drain", "energize" -> 0x8FE8FF;
					case "grasp", "black_hole", "gas_cloud" -> 0x9A6BFF;
					default -> FIRE;
				};
				for (int i = 0; i < n(b.skill, 4); i++) {
					double off = 0.3;
					mote(p.x + rr() * off, p.y + rr() * off, p.z + rr() * off, 0, 0, 0, color(b.skill, col), 3F, 25);
				}
			}
		}
	}

	private static void fist(Entity caster, double range) {
		Vec3 dir = caster.getViewVector(1F);
		// random direction inside a 40 degree cone, like the original
		double yaw = Math.toRadians((R.nextDouble() * 2 - 1) * 40);
		double pitch = Math.toRadians((R.nextDouble() * 2 - 1) * 40);
		dir = dir.yRot((float) yaw).xRot((float) pitch * 0.5F).normalize();
		Vec3 eye = caster.getEyePosition();
		Fist f = new Fist();
		f.pos = eye.add(dir);
		f.prev = f.pos;
		Vec3 end = eye.add(dir.scale(range));
		f.motion = end.subtract(f.pos).scale(1 / 5.0);
		if (FISTS.size() < 400) {
			FISTS.add(f);
		}
	}

	// ------------------------------------------------------------------ rendering

	public static void render(WorldRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !enabled()) {
			return;
		}
		MultiBufferSource buffers = ctx.consumers();
		if (buffers == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		Matrix4f m = ctx.matrices().last().pose();
		double time = clock + partial;

		for (Fx e : EFFECTS) {
			draw(mc.level, buffers, m, camera, cam, e, partial, time);
		}
		for (Fx b : BOLTS.values()) {
			drawBolt(buffers, m, cam, b, time);
		}
		if (!FISTS.isEmpty()) {
			VertexConsumer black = buffers.getBuffer(RenderTypes.debugQuads());
			for (Fist f : FISTS) {
				Vec3 at = f.prev.lerp(f.pos, partial).subtract(cam);
				FxDraw.box(black, m, at, f.motion, 0.25, 0.75, 0xFF000000);
			}
		}
		drawSprites(buffers, m, camera, cam, partial);
	}

	private static float fade(Fx e, float partial) {
		float age = e.age + partial;
		float in = Math.min(1F, age / 5F);
		float out = e.life >= Integer.MAX_VALUE / 2 ? 1F : Math.min(1F, (e.life - age) / 8F);
		return Math.max(0F, Math.min(in, out));
	}

	private static void draw(ClientLevel level, MultiBufferSource buffers, Matrix4f m, Camera camera, Vec3 cam, Fx e, float partial, double time) {
		Entity caster = level.getEntity(e.entity);
		Vec3 follow = caster != null ? caster.getPosition(partial) : e.dest;
		float f = fade(e, partial);
		float r = e.radius;
		double life = (e.age + partial) / Math.max(1.0, e.life);
		switch (e.skill) {
			case "black_hole" -> {
				Vec3 c = e.pos.add(0, 1, 0).subtract(cam);
				double core = Math.max(0.8, r * 0.3) * (1 + 0.04 * Math.sin(time * 0.3)) * Math.min(1F, (e.age + partial) / 10F);
				int tint = FxDraw.argb(color(e.skill, VOID), 0.9F * f);
				VertexConsumer arms = buffers.getBuffer(FxDraw.universe());
				for (int arm = 0; arm < 3; arm++) {
					int pts = 14;
					Vec3[] p = new Vec3[pts];
					double[] rad = new double[pts];
					for (int i = 0; i < pts; i++) {
						double t = i / (double) (pts - 1);
						double dist = core * 0.8 + (r * 0.95 - core * 0.8) * (1 - t);
						double ang = arm * Math.PI * 2 / 3 - time * 0.12 + (1 - t) * 2.6;
						p[i] = c.add(Math.cos(ang) * dist, Math.sin(ang * 2 + arm) * 0.15 * (1 - t), Math.sin(ang) * dist);
						rad[i] = 0.025 + core * 0.55 * t * t;
					}
					FxDraw.tube(arms, m, p, rad, tint, 10);
				}
				FxDraw.sphere(buffers.getBuffer(FxDraw.universeDark()), m, c.x, c.y, c.z, core, FxDraw.argb(0xFFFFFF, f), 14);
			}
			case "gas_cloud" -> {
				Vec3 c = e.pos.add(0, Math.max(0.6, r * 0.45), 0).subtract(cam);
				VertexConsumer vc = buffers.getBuffer(FxDraw.universe());
				int tint = FxDraw.argb(color(e.skill, VOID), 0.82F * f);
				RandomSource seeded = RandomSource.create(e.seed);
				double grow = Math.min(1.0, (e.age + partial) / 12.0);
				FxDraw.sphere(vc, m, c.x, c.y, c.z, r * 0.62 * grow, tint, 10);
				for (int i = 0; i < 6; i++) {
					double a = seeded.nextDouble() * Math.PI * 2;
					double d = r * (0.3 + seeded.nextDouble() * 0.35);
					double y = (seeded.nextDouble() - 0.4) * r * 0.4;
					double wob = Math.sin(time * 0.05 + i) * 0.08 * r;
					FxDraw.sphere(vc, m, c.x + Math.cos(a) * d, c.y + y + wob, c.z + Math.sin(a) * d, r * (0.32 + seeded.nextDouble() * 0.2) * grow,
							tint, 8);
				}
			}
			case "grasp" -> {
				Vec3 c = e.to.add(0, 1, 0).subtract(cam);
				double s = (1 - life) * 0.9 + 0.2;
				FxDraw.sphere(buffers.getBuffer(FxDraw.universe()), m, c.x, c.y, c.z, s, FxDraw.argb(color(e.skill, VOID), 0.9F * f), 10);
			}
			case "unstable_portal" -> {
				Vec3 c = e.to.add(0, 1.2, 0).subtract(cam);
				double s = 0.7 + 0.1 * Math.sin(time * 0.2);
				FxDraw.cube(buffers.getBuffer(FxDraw.universeDark()), m, c.x, c.y, c.z, s, time * 0.05, time * 0.03, FxDraw.argb(0xFFFFFF, f));
				FxDraw.cube(buffers.getBuffer(FxDraw.universe()), m, c.x, c.y, c.z, s * 1.25, -time * 0.04, time * 0.02,
						FxDraw.argb(color(e.skill, VOID), 0.6F * f));
			}
			case "black_raging_flame_ball" -> {
				// the ball itself is drawn with the bolt
			}
			case "hover" -> {
				Vec3 c = follow.subtract(cam);
				FxDraw.sphere(buffers.getBuffer(FxDraw.universe()), m, c.x, c.y - 0.1, c.z, 0.45, FxDraw.argb(color(e.skill, VOID), 0.7F * f), 8);
			}
			case "teleport", "warp" -> {
				if (life < 1) {
					rift(buffers, m, e.pos.subtract(cam), e.seed, f, color(e.skill, VOID));
					rift(buffers, m, e.dest.subtract(cam), e.seed ^ 0x5DEECE66DL, f, color(e.skill, VOID));
				}
			}
			case "solar_lance", "final_flash" -> {
				if (caster != null) {
					Vec3 eye = caster.getEyePosition(partial).add(0, -0.2, 0);
					Vec3 look = caster.getViewVector(partial);
					double len = Math.max(4, r);
					double w = e.skill.equals("final_flash") ? 0.9 : 0.45;
					Vec3[] p = new Vec3[8];
					double[] rad = new double[8];
					for (int i = 0; i < 8; i++) {
						double t = i / 7.0;
						p[i] = eye.add(look.scale(0.6 + t * len)).subtract(cam);
						rad[i] = w * (i == 0 ? 0.4 : 1.0) * (1 + 0.08 * Math.sin(time * 0.8 + i));
					}
					FxDraw.tube(buffers.getBuffer(FxDraw.universeWhite()), m, p, rad, FxDraw.argb(0xFFFFFF, f), 14);
					if (e.skill.equals("final_flash")) {
						VertexConsumer glow = buffers.getBuffer(FxDraw.additive("fire_spirit_"));
						Vec3 hand = eye.add(look.scale(0.8)).subtract(cam);
						FxDraw.sprite(glow, m, camera.leftVector(), camera.upVector(), hand.x, hand.y, hand.z, w * 1.6, (float) time * 0.1F,
								FxDraw.argb(color(e.skill, 0xFFF6C8), f));
					}
				}
			}
			case "radiant_ray" -> {
				Vec3 c = e.to.subtract(cam);
				double h = 28 * Math.min(1.0, (e.age + partial) / 4.0);
				VertexConsumer vc = buffers.getBuffer(FxDraw.additive("shocking_aura2"));
				float scroll = (float) (time * 0.05);
				FxDraw.texCylinder(vc, m, c.x, c.y, c.z, Math.max(0.6, r * 0.5), h, FxDraw.argb(color(e.skill, 0xFFF4C0), f), 20, 3F, scroll,
						scroll + 6F);
				FxDraw.texCylinder(vc, m, c.x, c.y, c.z, Math.max(0.3, r * 0.25), h, FxDraw.argb(0xFFFFFF, f), 16, 2F, -scroll, -scroll + 4F);
			}
			case "explode" -> {
				Vec3 c = follow.add(0, 1, 0).subtract(cam);
				double s = Math.max(1.0, r) * Math.min(1.0, life * 2.5);
				FxDraw.texSphere(buffers.getBuffer(FxDraw.additive("explode")), m, c.x, c.y, c.z, s, FxDraw.argb(color(e.skill, FIRE),
						0.7F * (float) (1 - life)), 12, 4F, (float) (time * 0.02));
			}
			case "gleam_flash" -> {
				Vec3 c = e.to.add(0, 1, 0).subtract(cam);
				double s = Math.max(1.0, r) * Math.min(1.0, life * 3);
				FxDraw.texSphere(buffers.getBuffer(FxDraw.additive("glowing")), m, c.x, c.y, c.z, s,
						FxDraw.argb(color(e.skill, 0xFFFBE0), (float) (1 - life)), 12, 2F, 0F);
			}
			case "electric_pulse", "power_drain", "blazing_aura" -> {
				Vec3 c = (e.skill.equals("power_drain") ? e.to : follow).add(0, 1, 0).subtract(cam);
				boolean aura = e.skill.equals("blazing_aura");
				double s = aura ? 1.3 : Math.max(1.0, r) * Math.min(1.0, life * 2);
				int col = color(e.skill, aura ? 0xFF8A2A : 0x8FE8FF);
				FxDraw.texSphere(buffers.getBuffer(FxDraw.additive("shocking_aura")), m, c.x, c.y, c.z, s,
						FxDraw.argb(col, (aura ? 0.8F : (float) (1 - life)) * f), 12, 3F, (float) (time * 0.07));
			}
			case "shocking_aura" -> {
				Vec3 c = follow.add(0, 0.05, 0).subtract(cam);
				int frame = (int) (time / 2) % 8;
				VertexConsumer vc = buffers.getBuffer(FxDraw.additive("electric_ring"));
				FxDraw.texFlat(vc, m, c.x, c.y, c.z, Math.max(1.5, r), time * 0.05, FxDraw.argb(color(e.skill, 0x8FE8FF), f), frame / 8F,
						(frame + 1) / 8F);
			}
			case "thorny" -> {
				Vec3 c = follow.add(0, 1, 0).subtract(cam);
				FxDraw.texSphere(buffers.getBuffer(FxDraw.alpha("thorny")), m, c.x, c.y, c.z, 1.1,
						FxDraw.argb(color(e.skill, 0xFFFFFF), 0.8F * f), 10, 4F, 0F);
			}
			case "nearby_invincibility" -> {
				Vec3 c = follow.add(0, 1, 0).subtract(cam);
				FxDraw.texSphere(buffers.getBuffer(FxDraw.additive("nearby_invincibility")), m, c.x, c.y, c.z, Math.max(1.5, r),
						FxDraw.argb(color(e.skill, 0xFFF0A0), 0.6F * f), 14, 4F, (float) (time * 0.01));
			}
			case "heal_aura" -> {
				Vec3 c = follow.subtract(cam);
				VertexConsumer vc = buffers.getBuffer(FxDraw.additive("heal_aura"));
				FxDraw.texCylinder(vc, m, c.x, c.y, c.z, Math.max(1.5, r), 1.2, FxDraw.argb(color(e.skill, 0x7CFF7C), 0.7F * f), 24, 6F, 0F, 1F);
			}
			case "magnetic_pull" -> {
				Vec3 c = follow.subtract(cam);
				int frame = (int) (time / 2) % 8;
				double s = Math.max(1.0, r) * (1 - life * 0.7);
				FxDraw.texCylinder(buffers.getBuffer(FxDraw.additive("magnetic_pull")), m, c.x, c.y, c.z, s, 2.2,
						FxDraw.argb(color(e.skill, 0x8FE8FF), f), 20, 6F, frame / 8F, (frame + 1) / 8F);
			}
			case "taunt", "lumen_wave" -> {
				Vec3 c = follow.add(0, 0.1, 0).subtract(cam);
				double s = Math.max(1.0, r) * Math.min(1.0, life * 1.5);
				String tex = e.skill.equals("taunt") ? "taunt" : "lumen_wave";
				FxDraw.texFlat(buffers.getBuffer(FxDraw.additive(tex)), m, c.x, c.y, c.z, s, 0,
						FxDraw.argb(color(e.skill, e.skill.equals("taunt") ? 0xFF4040 : 0xFFFBE0), (float) (1 - life)), 0F, 1F);
			}
			case "slash" -> {
				if (caster != null) {
					Vec3 c = caster.getPosition(partial).add(0, 1.1, 0).subtract(cam);
					double yaw = Math.toRadians(-caster.getViewYRot(partial)) + Math.PI;
					FxDraw.texFlat(buffers.getBuffer(FxDraw.additive("slash")), m, c.x + Math.sin(yaw) * -1.2, c.y, c.z + Math.cos(yaw) * -1.2,
							Math.max(1.5, r * 0.5), -yaw, FxDraw.argb(color(e.skill, 0xFFFFFF), (float) (1 - life)), 0F, 1F);
				}
			}
			case "suffocate", "crush" -> {
				Vec3 c = e.to.add(0, 1, 0).subtract(cam);
				if (e.skill.equals("crush")) {
					double s = 1.4 * (1 - life * 0.6);
					FxDraw.cube(buffers.getBuffer(FxDraw.alpha("crush")), m, c.x, c.y, c.z, s, 0, 0, FxDraw.argb(0xFFFFFF, f));
				} else {
					FxDraw.texSphere(buffers.getBuffer(FxDraw.alpha("suffocate")), m, c.x, c.y, c.z, 1.0,
							FxDraw.argb(color(e.skill, 0xFFFFFF), 0.8F * f), 10, 2F, (float) (time * 0.02));
				}
			}
			case "blood_pool" -> {
				Vec3 c = e.pos.add(0, 0.05, 0).subtract(cam);
				FxDraw.texFlat(buffers.getBuffer(FxDraw.alpha("blood")), m, c.x, c.y, c.z, Math.max(1.0, r), e.seed % 7,
						FxDraw.argb(color(e.skill, 0xFFFFFF), 0.85F * f), 0F, 1F);
			}
			case "fire_spirit", "black_blessing_flame", "home_star" -> {
				Vec3 c = (e.skill.equals("home_star") ? e.to.add(0, 1.5, 0) : follow.add(0, 1, 0)).subtract(cam);
				String tex = e.skill.equals("black_blessing_flame") ? "fire_spirit_" : "fire_spirit";
				VertexConsumer vc = e.skill.equals("black_blessing_flame") ? buffers.getBuffer(FxDraw.alpha(tex)) : buffers.getBuffer(FxDraw.additive(tex));
				FxDraw.sprite(vc, m, camera.leftVector(), camera.upVector(), c.x, c.y, c.z, 0.9, (float) time * 0.05F,
						FxDraw.argb(color(e.skill, 0xFFFFFF), 0.9F * f));
			}
			case "smash" -> {
				Vec3 c = follow.subtract(cam);
				int frame = (int) (time) % 32;
				double s = Math.max(1.0, r) * Math.min(1.0, life * 2);
				FxDraw.texCylinder(buffers.getBuffer(FxDraw.alpha("smash_sides")), m, c.x, c.y, c.z, s, 1.0,
						FxDraw.argb(0xFFFFFF, (float) (1 - life)), 16, 4F, frame / 32F, (frame + 1) / 32F);
			}
			default -> {
			}
		}
	}

	/** Jagged cosmic crack where someone teleported (TeleportRift / WarpRift of the original). */
	private static void rift(MultiBufferSource buffers, Matrix4f m, Vec3 base, long seed, float alpha, int tint) {
		RandomSource seeded = RandomSource.create(seed);
		int pts = 10;
		Vec3[] p = new Vec3[pts];
		double[] rad = new double[pts];
		for (int i = 0; i < pts; i++) {
			double t = i / (double) (pts - 1);
			p[i] = base.add(seeded.nextGaussian() * 0.35 * Math.sin(t * Math.PI), 0.1 + t * 2.0, seeded.nextGaussian() * 0.35 * Math.sin(t * Math.PI));
			rad[i] = 0.15 * Math.sin(t * Math.PI) + 0.01;
		}
		FxDraw.tube(buffers.getBuffer(FxDraw.universeDark()), m, p, rad, FxDraw.argb(0xFFFFFF, 0.92F * alpha), 8);
		double[] glow = new double[pts];
		for (int i = 0; i < pts; i++) {
			glow[i] = rad[i] * 1.6 + 0.02;
		}
		FxDraw.tube(buffers.getBuffer(FxDraw.universe()), m, p, glow, FxDraw.argb(tint, 0.6F * alpha), 8);
	}

	private static void drawBolt(MultiBufferSource buffers, Matrix4f m, Vec3 cam, Fx b, double time) {
		switch (b.skill) {
			case "gloom" -> {
				if (b.trail.size() >= 2) {
					Vec3[] p = new Vec3[b.trail.size()];
					double[] rad = new double[p.length];
					int i = 0;
					for (Vec3 at : b.trail) {
						p[i] = at.subtract(cam);
						rad[i] = 0.18 * (1 - i / (double) p.length) + 0.02;
						i++;
					}
					FxDraw.tube(buffers.getBuffer(FxDraw.universe()), m, p, rad, FxDraw.argb(color(b.skill, VOID), 0.9F), 8);
				}
			}
			case "black_raging_flame_ball" -> {
				Vec3 c = b.pos.subtract(cam);
				FxDraw.cube(buffers.getBuffer(FxDraw.universeDark()), m, c.x, c.y, c.z, 0.35, time * 0.2, time * 0.13, 0xFFFFFFFF);
				FxDraw.cube(buffers.getBuffer(FxDraw.universe()), m, c.x, c.y, c.z, 0.5, -time * 0.15, time * 0.1, FxDraw.argb(VOID, 0.7F));
			}
			default -> {
			}
		}
	}

	private static void drawSprites(MultiBufferSource buffers, Matrix4f m, Camera camera, Vec3 cam, float partial) {
		if (SPRITES.isEmpty()) {
			return;
		}
		Vector3fc left = camera.leftVector();
		Vector3fc up = camera.upVector();
		Map<String, List<Sprite>> groups = new HashMap<>();
		for (Sprite s : SPRITES) {
			groups.computeIfAbsent((s.add ? "+" : "-") + s.tex, k -> new ArrayList<>()).add(s);
		}
		for (Map.Entry<String, List<Sprite>> g : groups.entrySet()) {
			String tex = g.getKey().substring(1);
			RenderType type = g.getKey().charAt(0) == '+' ? FxDraw.additive(tex) : FxDraw.alpha(tex);
			VertexConsumer vc = buffers.getBuffer(type);
			for (Sprite s : g.getValue()) {
				float age = (s.age + partial) / s.life;
				float half = s.shrink ? s.half * Math.max(0F, 1F - age) : s.half;
				float alpha = s.alpha * (s.shrink ? 1F : Math.min(1F, (1F - age) * 3F));
				double x = s.px + (s.x - s.px) * partial - cam.x;
				double y = s.py + (s.y - s.py) * partial - cam.y;
				double z = s.pz + (s.z - s.pz) * partial - cam.z;
				FxDraw.sprite(vc, m, left, up, x, y, z, half, s.roll, FxDraw.argb(s.rgb, alpha));
			}
		}
	}
}
