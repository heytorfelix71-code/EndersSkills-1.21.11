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
		net.minecraft.world.entity.monster.zombie.Zombie creature;
		Vec3 origin;
		Vec3 dir;
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

	/** A jagged electric arc between two points (the original Lightning effect). */
	private static final class Arc {
		Vec3 a;
		Vec3 b;
		int color;
		int age;
		int life;
		long seed;
	}

	private static final List<Arc> ARCS = new ArrayList<>();
	private static final java.util.Set<String> ELECTRIC = java.util.Set.of("shocking_aura", "electric_pulse", "magnetic_pull",
			"power_drain", "energize", "voltaic_sentinel");
	private static final RandomSource R = RandomSource.create();
	private static int clock;

	private OriginalFx() {
	}

	/** Set by the ability keys: the local player is holding jump to hover right now. */
	public static boolean localHovering;

	private static boolean hovering(Entity e) {
		if (e == null || e.onGround()) {
			return false;
		}
		if (e == Minecraft.getInstance().player) {
			return localHovering;
		}
		return Math.abs(e.getDeltaMovement().y) < 0.2;
	}

	/** The original-style effects are on (they draw the Black Hole). */
	public static boolean active() {
		return enabled();
	}

	private static boolean enabled() {
		return EsSettings.get().vfxStyle != EsSettings.VfxStyle.CURRENT;
	}

	public static void reset() {
		EFFECTS.clear();
		BOLTS.clear();
		SPRITES.clear();
		FISTS.clear();
		ARCS.clear();
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
		if (p.skill().endsWith("_view") && p.id() != 0) {
			// a portal ring that is kept alive by the server: refresh it instead of stacking a new one
			for (Fx old : EFFECTS) {
				if (old.id == p.id() && old.skill.equals(p.skill())) {
					old.pos = new Vec3(p.x(), p.y(), p.z());
					old.radius = Math.max(0.3F, p.radius());
					old.life = old.age + Math.max(10, p.ticks());
					return;
				}
			}
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
		Minecraft mc = Minecraft.getInstance();
		Entity caster = mc.level == null ? null : mc.level.getEntity(p.entity());
		if (caster != null && p.phase() == CAST) {
			e.origin = caster.getEyePosition().add(0, -0.4, 0);
			e.dir = caster.getViewVector(1F).normalize();
		}
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
			case "teleport", "warp" -> {
				for (Vec3 end : new Vec3[] {c, d}) {
					for (int i = 0; i < n(e.skill, 14); i++) {
						glow(end.x + rr() * 1.6, end.y + R.nextDouble() * 2.8, end.z + rr() * 1.6, g() * 0.03, 0.02 + R.nextDouble() * 0.03, g() * 0.03,
								R.nextBoolean() ? 0xE070FF : 0xFFFFFF, 1.5F + R.nextFloat() * 2F, 30 + R.nextInt(20));
					}
				}
			}
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
		if (ELECTRIC.contains(e.skill)) { // the bolt that hits a mob
			int col = color(e.skill, 0x9FEFFF);
			for (int i = 0; i < 2; i++) {
				bolt(c.add(g() * 0.8, 8, g() * 0.8), c.add(0, Math.max(0.5, h * 0.5), 0), col, 5);
			}
		}
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
			if (e.skill.equals("portal_view")) {
				PortalWindowClient.want(e.id, e.pos.add(0, 1.0, 0));
			}
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
		ARCS.removeIf(a -> ++a.age > a.life);
		for (Iterator<Fist> it = FISTS.iterator(); it.hasNext();) {
			Fist f = it.next();
			f.prev = f.pos;
			f.pos = f.pos.add(f.motion);
			if (++f.age > 5) {
				it.remove();
			}
		}
	}

	private static void arc(Vec3 a, Vec3 b, int color, int life) {
		if (ARCS.size() < 300) {
			Arc arc = new Arc();
			arc.a = a;
			arc.b = b;
			arc.color = color;
			arc.life = life;
			arc.seed = R.nextLong();
			ARCS.add(arc);
		}
	}

	private static Vec3 around(Vec3 c, double r) {
		double a = R.nextDouble() * Math.PI * 2;
		double d = r * (0.5 + R.nextDouble() * 0.5);
		return c.add(Math.cos(a) * d, R.nextDouble() * 1.8, Math.sin(a) * d);
	}

	/** Electric arcs of the Electric tree. */
	private static void electric(Fx e, Vec3 c) {
		int col = color(e.skill, 0x9FEFFF);
		Vec3 mid = c.add(0, 1, 0);
		float r = Math.max(1.5F, Math.min(8F, e.radius));
		switch (e.skill) {
			case "shocking_aura" -> {
				for (int i = 0; i < n(e.skill, 3); i++) {
					bolt(mid, around(c, r), col, 3);
				}
				if (e.age % 4 == 0) {
					strike(c, r, col);
				}
				if (e.age % 5 == 0) {
					chain(mid, r, col);
				}
			}
			case "electric_pulse" -> {
				if (e.age < 10) {
					for (int i = 0; i < n(e.skill, 8); i++) {
						bolt(mid, around(c, r * Math.min(1.0, e.age / 6.0 + 0.3)), col, 4);
					}
					if (e.age % 2 == 0) {
						strike(c, r, col);
					}
				}
				if (e.age == 0 || e.age == 4) {
					chain(mid, r, col);
				}
			}
			case "magnetic_pull" -> {
				for (int i = 0; i < n(e.skill, 3); i++) {
					bolt(around(c, r), mid, col, 3);
				}
				if (e.age % 6 == 0) {
					strike(c, 0.5, col);
				}
			}
			case "power_drain" -> {
				if (e.age % 2 == 0) {
					bolt(e.to.add(0, 1, 0), mid, col, 3);
					bolt(e.to.add(0, 1.5, 0), mid.add(0, 0.4, 0), col, 3);
				}
			}
			case "energize" -> {
				if (e.age < 40 && e.age % 2 == 0) {
					bolt(c.add(0, 0.1, 0), c.add((R.nextDouble() - 0.5) * 1.6, 2.2, (R.nextDouble() - 0.5) * 1.6), col, 3);
					if (e.age % 6 == 0) {
						strike(c, 1.5, col);
					}
				}
			}
			case "voltaic_sentinel" -> {
				if (e.age % 2 == 0) {
					bolt(e.to.add(0, 1.2, 0), around(e.to, 3), col, 3);
				}
				if (e.age % 8 == 0) {
					strike(e.to, 3, col);
				}
			}
			default -> {
			}
		}
	}

	/** A bolt with a couple of forks. */
	private static void bolt(Vec3 a, Vec3 b, int col, int life) {
		arc(a, b, col, life);
		for (int i = 0; i < 2; i++) {
			if (R.nextInt(3) == 0) {
				continue;
			}
			Vec3 p = a.lerp(b, 0.2 + R.nextDouble() * 0.6);
			arc(p, p.add(g() * 0.9, g() * 0.7, g() * 0.9), col, life);
		}
	}

	/** A bolt from the sky onto the ground around a spot. */
	private static void strike(Vec3 c, double r, int col) {
		Vec3 ground = around(c, r);
		bolt(ground.add((R.nextDouble() - 0.5) * 2, 9 + R.nextDouble() * 3, (R.nextDouble() - 0.5) * 2), ground, col, 4);
	}

	/** Arcs jumping from the middle to the mobs around. */
	private static void chain(Vec3 mid, double r, int col) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		int count = 0;
		for (Entity en : mc.level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(mid, mid).inflate(r),
				x -> x instanceof net.minecraft.world.entity.LivingEntity && x != mc.player && x.isAlive())) {
			bolt(mid, en.position().add(0, en.getBbHeight() * 0.6, 0), col, 4);
			if (++count >= 5) {
				break;
			}
		}
	}

	/** Particles an effect makes while it lasts (zones, channels, auras). */
	private static void emit(ClientLevel level, Fx e) {
		if (e.phase == HIT) {
			return; // a hit only makes its burst (hit()), not the whole ability again on the target
		}
		Entity caster = level.getEntity(e.entity);
		Vec3 c = e.phase == ZONE ? e.pos : (caster != null ? caster.position() : e.dest);
		float r = e.radius;
		if (ELECTRIC.contains(e.skill)) {
			electric(e, c);
		}
		switch (e.skill) {
			case "portal_view", "unstable_view" -> {
				if (e.age % 3 == 0) {
					double a = R.nextDouble() * Math.PI * 2;
					double rx = Math.max(0.6, e.radius);
					double ry = Math.max(1.1, Math.min(e.radius, 2.2));
					glow(e.pos.x + Math.cos(a) * rx * 0.9 * R.nextDouble(), e.pos.y + 1.0 + Math.sin(a) * ry * 0.9 * R.nextDouble(), e.pos.z + rr() * 0.3,
							g() * 0.01, 0.01 + R.nextDouble() * 0.02, g() * 0.01, e.skill.equals("portal_view") ? 0xB8A0FF : 0xFF90E0,
							1.2F + R.nextFloat() * 1.5F, 25 + R.nextInt(15));
				}
			}
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
				if (hovering(caster) && R.nextInt(2) == 0) {
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
		FxBuffers buffers = new FxBuffers();
		if (buffers == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		Matrix4f m = new Matrix4f();
		double time = clock + partial;
		buffers.getBuffer(dev.enderskills.client.fx.FxDraw.dark()); // first: black shapes are replayed before the glowing ones

		for (Fx e : EFFECTS) {
			draw(mc.level, buffers, m, camera, cam, e, partial, time);
			if (e.skill.equals("grasp") && e.phase == ZONE) {
				graspCreature(ctx, mc, e, cam, partial);
			}
		}
		for (Fx b : BOLTS.values()) {
			drawBolt(buffers, m, cam, b, time);
		}
		if (!FISTS.isEmpty()) {
			VertexConsumer black = buffers.getBuffer(dev.enderskills.client.fx.FxDraw.dark());
			for (Fist f : FISTS) {
				Vec3 at = f.prev.lerp(f.pos, partial).subtract(cam);
				FxDraw.box(black, m, at, f.motion, 0.25, 0.75, 0xFF000000);
			}
		}
		if (!ARCS.isEmpty()) {
			VertexConsumer vc = buffers.getBuffer(RenderTypes.lightning());
			for (Arc a : ARCS) {
				drawArc(vc, m, camera, cam, a);
			}
		}
		drawSprites(buffers, m, camera, cam, partial);
		buffers.submit(ctx);
	}

	private static float fade(Fx e, float partial) {
		float age = e.age + partial;
		float in = Math.min(1F, age / 5F);
		float out = e.life >= Integer.MAX_VALUE / 2 ? 1F : Math.min(1F, (e.life - age) / 8F);
		return Math.max(0F, Math.min(in, out));
	}

	private static void draw(ClientLevel level, MultiBufferSource buffers, Matrix4f m, Camera camera, Vec3 cam, Fx e, float partial, double time) {
		if (e.phase == HIT && !e.skill.equals("slash")) {
			return;
		}
		Entity caster = level.getEntity(e.entity);
		Vec3 follow = caster != null ? caster.getPosition(partial) : e.dest;
		float f = fade(e, partial);
		float r = e.radius;
		double life = (e.age + partial) / Math.max(1.0, e.life);
		switch (e.skill) {
			case "black_hole" -> {
				if (e.phase == CAST) {
					return; // only where it lands (the cast event sits on the caster)
				}
				Vec3 c = e.pos.add(0, 1, 0).subtract(cam);
				BlackHoleFx.draw(buffers.getBuffer(RenderTypes.lightning()), buffers.getBuffer(dev.enderskills.client.fx.FxDraw.dark()), m, c.x, c.y, c.z,
						r, time, f, (float) Math.min(1F, (e.age + partial) / 10F));
			}
			case "gas_cloud" -> {
				if (e.phase == CAST) {
					return; // only where it lands (the cast event sits on the caster)
				}
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
				// the ring itself is drawn by the portal view the server keeps refreshing
			}
			case "portal_view", "unstable_view" -> {
				boolean normal = e.skill.equals("portal_view");
				double rx = Math.max(0.6, e.radius);
				double ry = Math.max(1.1, Math.min(e.radius, 2.2));
				net.minecraft.resources.Identifier view = normal ? PortalWindowClient.texture(e.id) : null;
				if (view != null) {
					// see-through: what is on the other side, inside a glowing ring
					window(buffers, m, e.pos.add(0, 1.0, 0).subtract(cam), rx, ry, view, f, time, 0x7A5CFF);
				} else {
					galaxy(buffers, m, e.pos.add(0, 1.0, 0).subtract(cam), e.seed, rx, ry, 1F, f, time,
							normal ? 0xB8B8FF : 0xFFA0F0, normal ? 0x7A5CFF : 0xFF4FD8, 11);
				}
			}
			case "black_raging_flame_ball" -> {
				// the ball itself is drawn with the bolt
			}
			case "hover" -> {
				// a small platform of end sky under the feet, with a cosmic rim; only while really hovering
				if (!hovering(caster)) {
					return;
				}
				Vec3 c = follow.subtract(cam);
				galaxyDisc(buffers, m, c.x, c.y - 0.03, c.z, 0.95, f, time, color(e.skill, 0xC84CFF));
			}
			case "teleport", "warp" -> {
				if (life < 1) {
					// a crack in reality: opens fast, stays a moment, closes (about 2 seconds in all)
					double t = Math.max(0.0, Math.min(1.0, life));
					double up = Math.min(1.0, t / 0.3);
					double down = Math.min(1.0, (1.0 - t) / 0.4);
					double open = (1 - Math.pow(1 - up, 3)) * (1 - Math.pow(1 - down, 2)); // quick, soft opening; gentle closing
					double h = e.skill.equals("warp") ? 1.9 : 1.5;
					galaxy(buffers, m, e.pos.add(0, h, 0).subtract(cam), e.seed, 0.95, h, (float) open, 1F, time, 0xFFFFFF, 0xC84CFF, 9);
					galaxy(buffers, m, e.dest.add(0, h, 0).subtract(cam), e.seed ^ 0x5DEECE66DL, 0.95, h, (float) open, 1F, time, 0xFFFFFF, 0xC84CFF, 9);
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
				// oak-wood look: layers of light, medium and dark brown with the bark texture sliding at different speeds
				VertexConsumer wood = buffers.getBuffer(FxDraw.alpha("thorny"));
				FxDraw.texSphere(wood, m, c.x, c.y, c.z, 1.14, FxDraw.argb(color(e.skill, 0x6B4A25), 0.7F * f), 10, 4F, (float) (time * 0.004));
				FxDraw.texSphere(wood, m, c.x, c.y, c.z, 1.1, FxDraw.argb(color(e.skill, 0xB8864B), 0.7F * f), 10, 3F, (float) (-time * 0.006));
				FxDraw.texSphere(wood, m, c.x, c.y, c.z, 1.06, FxDraw.argb(0xD9B27A, 0.45F * f), 10, 5F, (float) (time * 0.003));
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
				// a standing blade of wind that flies forward from in front of you (not around you); hit mobs get their own cut
				if (e.phase == HIT) {
					Vec3 c = e.pos.add(0, Math.max(0.5, r) * 0.6, 0).subtract(cam);
					Vec3 side = new Vec3(camera.leftVector().x(), camera.leftVector().y(), camera.leftVector().z());
					Vec3 up = new Vec3(camera.upVector().x(), camera.upVector().y(), camera.upVector().z());
					double tilt = (e.seed & 1) == 0 ? 0.6 : -0.6;
					FxDraw.plane(buffers.getBuffer(FxDraw.additive("slash")), m, c, side.scale(0.9).add(up.scale(tilt * 0.9)),
							up.scale(0.9).subtract(side.scale(tilt * 0.9)), FxDraw.argb(color(e.skill, 0xFFFFFF), (float) (1 - life)));
				} else if (e.dir != null) {
					double travel = 1.6 + Math.min(1.0, life * 2.5) * Math.max(3.0, r);
					Vec3 c = e.origin.add(e.dir.scale(travel)).subtract(cam);
					Vec3 right = new Vec3(-e.dir.z, 0, e.dir.x).normalize();
					Vec3 up = right.cross(e.dir).normalize();
					double w = Math.max(1.2, r * 0.45);
					// tilted a little, like a sword swing
					FxDraw.plane(buffers.getBuffer(FxDraw.additive("slash")), m, c, right.scale(w).add(up.scale(w * 0.35)),
							up.scale(w * 0.6).subtract(right.scale(w * 0.2)), FxDraw.argb(color(e.skill, 0xFFFFFF), (float) Math.max(0, 1 - life * 1.4)));
				}
			}
			case "suffocate", "crush" -> {
				Vec3 c = e.to.add(0, 1, 0).subtract(cam);
				if (e.skill.equals("crush")) {
					double s = 1.4 * (1 - life * 0.6);
					// textured cube: the plain cube had no texture coordinates on this textured type (broken vertices every frame)
					FxDraw.texCube(buffers.getBuffer(FxDraw.alpha("crush")), m, c.x, c.y, c.z, s, FxDraw.argb(0xFFFFFF, f));
				} else {
					FxDraw.texSphere(buffers.getBuffer(FxDraw.alpha("suffocate")), m, c.x, c.y, c.z, 1.0,
							FxDraw.argb(color(e.skill, 0xFFFFFF), 0.8F * f), 10, 2F, (float) (time * 0.02));
				}
			}
			case "blood_pool" -> {
				if (e.phase == CAST) {
					return; // only where it lands (the cast event sits on the caster)
				}
				if (EsSettings.get().modifyEnvironment) {
					return; // the blood blocks on the ground show the pool (and follow the terrain)
				}
				Vec3 c = e.pos.add(0, 0.05, 0).subtract(cam);
				FxDraw.texFlat(buffers.getBuffer(FxDraw.alpha("blood")), m, c.x, c.y, c.z, Math.max(1.0, r), e.seed % 7,
						FxDraw.argb(color(e.skill, 0xFFFFFF), 0.85F * f), 0F, 1F);
			}
			case "home_star" -> { // Fire Spirit / Black Blessing Flame: their rune circles spin around the player (ShapeRenderer)
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

	private static final net.minecraft.resources.Identifier COSMOS = net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/end_portal.png");

	/**
	 * Grasp: a purple creature of the cosmos (shaped like a zombie, arms forward) rises out of the ground in the middle of the
	 * area, holding the mobs around it; drawn by the game's zombie renderer with a starry texture.
	 */
	private static void graspCreature(WorldRenderContext ctx, Minecraft mc, Fx e, Vec3 cam, float partial) {
		if (mc.level == null) {
			return;
		}
		if (e.creature == null) {
			e.creature = new net.minecraft.world.entity.monster.zombie.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, mc.level);
			e.creature.setAggressive(true);
			e.creature.setNoAi(true);
		}
		net.minecraft.world.entity.monster.zombie.Zombie z = e.creature;
		float age = e.age + partial;
		// faces the nearest enemy in the area, swaying a little
		Entity look = null;
		double best = Double.MAX_VALUE;
		for (Entity other : mc.level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(e.pos, e.pos).inflate(e.radius + 1),
				x -> x instanceof net.minecraft.world.entity.LivingEntity && x != mc.player)) {
			double d = other.distanceToSqr(e.pos);
			if (d < best) {
				best = d;
				look = other;
			}
		}
		float yaw = look == null ? (float) (age * 2) : (float) Math.toDegrees(Math.atan2(-(look.getX() - e.pos.x), look.getZ() - e.pos.z));
		z.setPos(e.pos.x, e.pos.y, e.pos.z);
		z.setYRot(yaw);
		z.yRotO = yaw;
		z.setYHeadRot(yaw);
		z.yHeadRotO = yaw;
		z.yBodyRot = yaw;
		z.yBodyRotO = yaw;
		z.tickCount = (int) age;
		net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		net.minecraft.client.renderer.entity.state.EntityRenderState state = dispatcher.extractEntity(z, partial);
		state.nameTag = null;
		state.shadowPieces.clear();
		float rise = Math.min(1F, age / 12F);
		float sink = e.life >= Integer.MAX_VALUE / 2 ? 1F : Math.min(1F, (e.life - age) / 12F);
		float scale = 1.25F;
		com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
		pose.translate(e.pos.x - cam.x, e.pos.y - cam.y - 2.4 * (1 - Math.min(rise, sink)), e.pos.z - cam.z);
		pose.scale(scale, scale, scale);
		int tint = FxDraw.argb(color(e.skill, 0xB070FF), 0.92F);
		dispatcher.submit(state, ctx.worldState().cameraRenderState, 0, 0, 0, pose,
				new dev.enderskills.client.TintCollector(ctx.commandQueue(), tint, COSMOS));
	}

	/** Jagged glowing ribbon: a white core and a colored glow, facing the camera. */
	private static void drawArc(VertexConsumer vc, Matrix4f m, Camera camera, Vec3 cam, Arc arc) {
		RandomSource seeded = RandomSource.create(arc.seed + arc.age / 2);
		int seg = Math.max(3, Math.min(14, (int) (arc.a.distanceTo(arc.b) * 2.5)));
		Vec3[] p = new Vec3[seg + 1];
		for (int i = 0; i <= seg; i++) {
			double t = i / (double) seg;
			Vec3 at = arc.a.lerp(arc.b, t);
			if (i > 0 && i < seg) {
				at = at.add(seeded.nextGaussian() * 0.18, seeded.nextGaussian() * 0.18, seeded.nextGaussian() * 0.18);
			}
			p[i] = at.subtract(cam);
		}
		float fade = 1F - arc.age / (float) (arc.life + 1);
		Vec3 view = new Vec3(camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z());
		for (int pass = 0; pass < 2; pass++) {
			double w = pass == 0 ? 0.09 : 0.03;
			int col = pass == 0 ? FxDraw.argb(arc.color, 0.55F * fade) : FxDraw.argb(0xFFFFFF, 0.95F * fade);
			for (int i = 0; i < seg; i++) {
				Vec3 d = p[i + 1].subtract(p[i]);
				Vec3 side = d.cross(view);
				side = side.lengthSqr() < 1.0E-8 ? new Vec3(w, 0, 0) : side.normalize().scale(w);
				vc.addVertex(m, (float) (p[i].x - side.x), (float) (p[i].y - side.y), (float) (p[i].z - side.z)).setColor(col);
				vc.addVertex(m, (float) (p[i].x + side.x), (float) (p[i].y + side.y), (float) (p[i].z + side.z)).setColor(col);
				vc.addVertex(m, (float) (p[i + 1].x + side.x), (float) (p[i + 1].y + side.y), (float) (p[i + 1].z + side.z)).setColor(col);
				vc.addVertex(m, (float) (p[i + 1].x - side.x), (float) (p[i + 1].y - side.y), (float) (p[i + 1].z - side.z)).setColor(col);
			}
		}
	}

	/**
	 * A jagged, faceted shard filled with a galaxy (nebula and stars), always facing the camera, with a glowing rim.
	 * open = 0 is a thin line, 1 is fully open (the crack of a Warp / Teleport); portals keep it at 1.
	 * center is camera-relative; rx / ry are the half width and height.
	 */
	private static void galaxy(MultiBufferSource buffers, Matrix4f m, Vec3 center, long seed, double rx, double ry, float open, float fade,
			double time, int tint, int rim, int points) {
		if (open <= 0.01F || fade <= 0.01F) {
			return;
		}
		RandomSource rnd = RandomSource.create(seed);
		Vec3 h = new Vec3(center.x, 0, center.z);
		Vec3 right = h.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : new Vec3(h.z, 0, -h.x).normalize();
		Vec3 toCam = h.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : h.normalize().scale(-1);
		double w = rx * (0.12 + 0.88 * open);
		double hh = ry * open;
		double[] px = new double[points];
		double[] py = new double[points];
		for (int i = 0; i < points; i++) {
			double a = Math.PI * 2 * i / points + (rnd.nextDouble() - 0.5) * 0.5;
			double k = 0.62 + rnd.nextDouble() * 0.38;
			px[i] = Math.cos(a) * k;
			py[i] = Math.sin(a) * k;
		}
		VertexConsumer vc = buffers.getBuffer(FxDraw.alpha("galaxy"));
		int base = FxDraw.argb(tint, 0.95F * fade);
		for (int i = 0; i < points; i++) {
			int j = (i + 1) % points;
			float shade = 0.72F + 0.28F * (float) ((i * 37L + seed) % 7) / 6F; // each facet a little different
			int col = FxDraw.argb(tint, 0.95F * fade * shade);
			Vec3[] v = {center, vertex(center, right, w, hh, px[i], py[i], toCam, 0), vertex(center, right, w, hh, px[j], py[j], toCam, 0)};
			double drift = 0.06 * Math.sin(time * 0.04);
			float[][] uv = {{0.5F, 0.5F}, {(float) (0.5 + px[i] * 0.42 + drift), (float) (0.5 - py[i] * 0.42)},
					{(float) (0.5 + px[j] * 0.42 + drift), (float) (0.5 - py[j] * 0.42)}};
			for (int q = 0; q < 4; q++) {
				int idx = q == 3 ? 2 : q;
				vc.addVertex(m, (float) v[idx].x, (float) v[idx].y, (float) v[idx].z).setUv(uv[idx][0], uv[idx][1]).setColor(col);
			}
		}
		// glowing rim
		VertexConsumer glow = buffers.getBuffer(RenderTypes.lightning());
		for (int i = 0; i < points; i++) {
			int j = (i + 1) % points;
			Vec3 a0 = vertex(center, right, w, hh, px[i], py[i], toCam, 0);
			Vec3 a1 = vertex(center, right, w, hh, px[j], py[j], toCam, 0);
			Vec3 b0 = vertex(center, right, w, hh, px[i] * 1.22, py[i] * 1.22, toCam, 0);
			Vec3 b1 = vertex(center, right, w, hh, px[j] * 1.22, py[j] * 1.22, toCam, 0);
			int in = FxDraw.argb(rim, 0.65F * fade * open);
			int outc = FxDraw.argb(rim, 0F);
			glow.addVertex(m, (float) a0.x, (float) a0.y, (float) a0.z).setColor(in);
			glow.addVertex(m, (float) a1.x, (float) a1.y, (float) a1.z).setColor(in);
			glow.addVertex(m, (float) b1.x, (float) b1.y, (float) b1.z).setColor(outc);
			glow.addVertex(m, (float) b0.x, (float) b0.y, (float) b0.z).setColor(outc);
		}
	}

	/** A flat disc of galaxy (nebula and stars) with a glowing, softly pulsing rim: the platform of Hover. */
	private static void galaxyDisc(MultiBufferSource buffers, Matrix4f m, double cx, double cy, double cz, double radius, float fade, double time, int rim) {
		if (fade <= 0.01F) {
			return;
		}
		int seg = 32;
		VertexConsumer vc = buffers.getBuffer(FxDraw.alpha("galaxy"));
		int col = FxDraw.argb(0xFFFFFF, 0.92F * fade);
		double spin = time * 0.01;
		for (int i = 0; i < seg; i++) {
			double a0 = Math.PI * 2 * i / seg;
			double a1 = Math.PI * 2 * (i + 1) / seg;
			double[][] p = {{0, 0}, {Math.cos(a0), Math.sin(a0)}, {Math.cos(a1), Math.sin(a1)}, {Math.cos(a1), Math.sin(a1)}};
			for (double[] q : p) {
				double u = 0.5 + 0.42 * (q[0] * Math.cos(spin) - q[1] * Math.sin(spin));
				double v = 0.5 + 0.42 * (q[0] * Math.sin(spin) + q[1] * Math.cos(spin));
				vc.addVertex(m, (float) (cx + q[0] * radius), (float) cy, (float) (cz + q[1] * radius)).setUv((float) u, (float) v).setColor(col);
			}
		}
		VertexConsumer glow = buffers.getBuffer(RenderTypes.lightning());
		float pulse = 0.55F + 0.15F * (float) Math.sin(time * 0.2);
		int in = FxDraw.argb(rim, pulse * fade);
		int out = FxDraw.argb(rim, 0F);
		for (int i = 0; i < seg; i++) {
			double a0 = Math.PI * 2 * i / seg;
			double a1 = Math.PI * 2 * (i + 1) / seg;
			double o = radius * 1.28;
			glow.addVertex(m, (float) (cx + Math.cos(a0) * radius * 0.96), (float) (cy + 0.01), (float) (cz + Math.sin(a0) * radius * 0.96)).setColor(in);
			glow.addVertex(m, (float) (cx + Math.cos(a1) * radius * 0.96), (float) (cy + 0.01), (float) (cz + Math.sin(a1) * radius * 0.96)).setColor(in);
			glow.addVertex(m, (float) (cx + Math.cos(a1) * o), (float) (cy + 0.01), (float) (cz + Math.sin(a1) * o)).setColor(out);
			glow.addVertex(m, (float) (cx + Math.cos(a0) * o), (float) (cy + 0.01), (float) (cz + Math.sin(a0) * o)).setColor(out);
		}
	}

	/** The view of the other side on a smooth oval facing you, with a soft glowing rim that breathes. */
	private static void window(MultiBufferSource buffers, Matrix4f m, Vec3 center, double rx, double ry, net.minecraft.resources.Identifier texture,
			float fade, double time, int rim) {
		if (fade <= 0.01F) {
			return;
		}
		Vec3 h = new Vec3(center.x, 0, center.z);
		Vec3 right = h.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : new Vec3(h.z, 0, -h.x).normalize();
		int seg = 40;
		VertexConsumer vc = buffers.getBuffer(FxDraw.alphaTex(texture));
		int col = FxDraw.argb(0xFFFFFF, 0.97F * fade);
		for (int i = 0; i < seg; i++) {
			double a0 = Math.PI * 2 * i / seg;
			double a1 = Math.PI * 2 * (i + 1) / seg;
			double[][] pts = {{0, 0}, {Math.cos(a0), Math.sin(a0)}, {Math.cos(a1), Math.sin(a1)}, {Math.cos(a1), Math.sin(a1)}};
			for (double[] q : pts) {
				Vec3 p = vertex(center, right, rx, ry, q[0], q[1], null, 0);
				vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setUv((float) (0.5 - 0.5 * q[0]), (float) (0.5 - 0.5 * q[1])).setColor(col);
			}
		}
		VertexConsumer glow = buffers.getBuffer(RenderTypes.lightning());
		float pulse = 0.55F + 0.15F * (float) Math.sin(time * 0.15);
		int in = FxDraw.argb(rim, pulse * fade);
		int out = FxDraw.argb(rim, 0F);
		for (int i = 0; i < seg; i++) {
			double a0 = Math.PI * 2 * i / seg;
			double a1 = Math.PI * 2 * (i + 1) / seg;
			Vec3 p0 = vertex(center, right, rx, ry, Math.cos(a0) * 0.97, Math.sin(a0) * 0.97, null, 0);
			Vec3 p1 = vertex(center, right, rx, ry, Math.cos(a1) * 0.97, Math.sin(a1) * 0.97, null, 0);
			Vec3 q0 = vertex(center, right, rx, ry, Math.cos(a0) * 1.2, Math.sin(a0) * 1.2, null, 0);
			Vec3 q1 = vertex(center, right, rx, ry, Math.cos(a1) * 1.2, Math.sin(a1) * 1.2, null, 0);
			glow.addVertex(m, (float) p0.x, (float) p0.y, (float) p0.z).setColor(in);
			glow.addVertex(m, (float) p1.x, (float) p1.y, (float) p1.z).setColor(in);
			glow.addVertex(m, (float) q1.x, (float) q1.y, (float) q1.z).setColor(out);
			glow.addVertex(m, (float) q0.x, (float) q0.y, (float) q0.z).setColor(out);
		}
	}

	private static Vec3 vertex(Vec3 c, Vec3 right, double w, double hh, double x, double y, Vec3 toCam, double lift) {
		return toCam == null ? c.add(right.scale(x * w)).add(0, y * hh, 0) : c.add(right.scale(x * w)).add(0, y * hh, 0).add(toCam.scale(lift));
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
