package dev.enderskills.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;

import dev.enderskills.data.EsSettings;
import dev.enderskills.logic.Shapes;
import dev.enderskills.network.EsNetwork;

/** Draws the 3D ability effects (glowing additive shapes) and the player trails / vibration copies. */
final class ShapeRenderer {
	private record Shape(int kind, Vec3 pos, float r, float h, Vec3 dir, int color, long start, int ticks) {
	}

	private static final List<Shape> SHAPES = new ArrayList<>();
	private static final Map<Integer, long[]> TRAIL = new HashMap<>(); // entity -> [until, color]
	private static final Map<Integer, long[]> VIBRATE = new HashMap<>();
	private static final Map<Integer, Deque<double[]>> HISTORY = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private ShapeRenderer() {
	}

	private static long time() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime();
	}

	static void add(EsNetwork.VfxShape p) {
		long now = time();
		if (p.kind() == Shapes.AFTERIMAGE) {
			TRAIL.put(p.entity(), new long[] {now + p.ticks(), p.color()});
			return;
		}
		if (p.kind() == Shapes.VIBRATE) {
			VIBRATE.put(p.entity(), new long[] {now + p.ticks(), p.color()});
			return;
		}
		if (SHAPES.size() < 64) {
			SHAPES.add(new Shape(p.kind(), new Vec3(p.x(), p.y(), p.z()), p.radius(), p.height(),
					new Vec3(p.dx(), p.dy(), p.dz()), p.color(), now, Math.max(1, p.ticks())));
		}
	}

	static void reset() {
		SHAPES.clear();
		TRAIL.clear();
		VIBRATE.clear();
		HISTORY.clear();
	}

	static void tick(Minecraft mc) {
		if (mc.level == null) {
			reset();
			return;
		}
		long now = mc.level.getGameTime();
		SHAPES.removeIf(s -> now > s.start() + s.ticks());
		TRAIL.values().removeIf(v -> v[0] < now);
		VIBRATE.values().removeIf(v -> v[0] < now);
		HISTORY.keySet().removeIf(id -> !TRAIL.containsKey(id));
		for (int id : TRAIL.keySet()) {
			Entity e = mc.level.getEntity(id);
			if (e == null) {
				continue;
			}
			Deque<double[]> h = HISTORY.computeIfAbsent(id, k -> new ArrayDeque<>());
			h.addFirst(new double[] {e.getX(), e.getY(), e.getZ(), e instanceof AbstractClientPlayer p ? p.yBodyRot : e.getYRot()});
			while (h.size() > 12) {
				h.removeLast();
			}
		}
	}

	// ------------------------------------------------------------------ rendering

	static void render(WorldRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		MultiBufferSource buffers = ctx.consumers();
		if (buffers == null) {
			return;
		}
		EsSettings cfg = EsSettings.get();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double now = mc.level.getGameTime() + partial;
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack pose = ctx.matrices();
		if (cfg.vfxShapes && !SHAPES.isEmpty()) {
			Matrix4f m = pose.last().pose();
			VertexConsumer glow = buffers.getBuffer(RenderTypes.lightning());
			for (Shape s : SHAPES) {
				double age = now - s.start();
				float life = (float) (age / s.ticks());
				float fade = Math.min(1F, (float) age / 4F) * Math.min(1F, (1F - life) * 3F);
				drawShape(glow, buffers, m, s, cam, now, life, Math.max(0F, fade));
			}
		}
		if ((cfg.vfxTrails && !TRAIL.isEmpty()) || (cfg.vfxVibrate && !VIBRATE.isEmpty())) {
			for (Map.Entry<Integer, long[]> e : TRAIL.entrySet()) {
				if (cfg.vfxTrails && mc.level.getEntity(e.getKey()) instanceof AbstractClientPlayer p) {
					trail(ctx, p, HISTORY.get(e.getKey()), (int) e.getValue()[1], cam, partial);
				}
			}
			for (Map.Entry<Integer, long[]> e : VIBRATE.entrySet()) {
				if (cfg.vfxVibrate && mc.level.getEntity(e.getKey()) instanceof AbstractClientPlayer p
						&& (p != mc.player || mc.gameRenderer.getMainCamera().isDetached())) {
					vibrate(ctx, p, (int) e.getValue()[1], cam, partial);
				}
			}
		}
	}

	private static int argb(int rgb, float alpha) {
		return ((int) (Math.max(0, Math.min(1, alpha)) * 255) << 24) | (rgb & 0xFFFFFF);
	}

	private static void drawShape(VertexConsumer glow, MultiBufferSource buffers, Matrix4f m, Shape s, Vec3 cam, double now, float life, float fade) {
		double x = s.pos().x - cam.x;
		double y = s.pos().y - cam.y;
		double z = s.pos().z - cam.z;
		int c = s.color();
		switch (s.kind()) {
			case Shapes.BEAM_UP -> {
				float w = s.r() * (1F - life * 0.6F);
				cylinder(glow, m, x, y, z, x, y + s.h(), z, w, w, argb(c, 0.55F * fade), argb(c, 0.0F));
				cylinder(glow, m, x, y, z, x, y + s.h(), z, w * 0.35F, w * 0.35F, argb(0xFFFFFF, 0.8F * fade), argb(0xFFFFFF, 0.1F * fade));
				disc(glow, m, x, y + 0.05, z, 0, w * 1.6F, (float) (now * 0.1), argb(c, 0.35F * fade));
			}
			case Shapes.BEAM_LINE -> {
				Vec3 d = s.dir().normalize();
				double ex = x + d.x * s.h();
				double ey = y + d.y * s.h();
				double ez = z + d.z * s.h();
				float w = s.r() * (1F + 0.15F * (float) Math.sin(now * 1.3));
				cylinder(glow, m, x, y, z, ex, ey, ez, w, w, argb(c, 0.5F * fade), argb(c, 0.25F * fade));
				cylinder(glow, m, x, y, z, ex, ey, ez, w * 0.35F, w * 0.35F, argb(0xFFFFFF, 0.85F * fade), argb(0xFFFFFF, 0.5F * fade));
			}
			case Shapes.SPHERE -> {
				float r = s.r() * (0.4F + life * 0.8F);
				sphere(glow, m, x, y, z, r, argb(c, 0.45F * fade));
				sphere(glow, m, x, y, z, r * 0.55F, argb(0xFFFFFF, 0.6F * fade));
			}
			case Shapes.BLACK_HOLE -> {
				float r = s.r() * Math.min(1F, (float) (now - s.start()) / 10F);
				VertexConsumer dark = buffers.getBuffer(RenderTypes.debugQuads());
				sphere(dark, m, x, y, z, r, argb(0x040008, 0.97F * Math.max(0.2F, fade)));
				float spin = (float) (now * 0.25);
				disc(glow, m, x, y, z, r * 1.15F, r * 2.4F, spin, argb(c, 0.6F * fade));
				disc(glow, m, x, y, z, r * 1.05F, r * 1.6F, -spin * 1.7F, argb(0xFF7AF0, 0.5F * fade));
				sphere(glow, m, x, y, z, r * 1.25F, argb(c, 0.18F * fade));
			}
			case Shapes.CLOUD -> {
				RandomSource rnd = RandomSource.create((long) (s.pos().x * 31 + s.pos().z * 17));
				for (int i = 0; i < 7; i++) {
					double ox = (rnd.nextDouble() - 0.5) * s.r() + Math.sin(now * 0.05 + i) * 0.2;
					double oy = rnd.nextDouble() * s.r() * 0.6 + 0.3;
					double oz = (rnd.nextDouble() - 0.5) * s.r() + Math.cos(now * 0.05 + i) * 0.2;
					float r = (float) (s.r() * (0.35 + rnd.nextDouble() * 0.3));
					sphere(glow, m, x + ox, y + oy, z + oz, r, argb(c, 0.22F * fade));
				}
				for (int i = 0; i < 18; i++) { // stars inside the cloud
					double ox = (rnd.nextDouble() - 0.5) * s.r() * 1.4;
					double oy = rnd.nextDouble() * s.r() + 0.2;
					double oz = (rnd.nextDouble() - 0.5) * s.r() * 1.4;
					float tw = 0.5F + 0.5F * (float) Math.sin(now * 0.3 + i * 1.7);
					sphere(glow, m, x + ox, y + oy, z + oz, 0.06F + 0.05F * tw, argb(0xF0D8FF, 0.9F * tw * fade));
				}
			}
			case Shapes.FIRE_RAIN -> {
				RandomSource rnd = RandomSource.create((long) (s.pos().x * 13 + s.pos().z * 7));
				for (int i = 0; i < 16; i++) {
					double ox = (rnd.nextDouble() - 0.5) * 2 * s.r();
					double oz = (rnd.nextDouble() - 0.5) * 2 * s.r();
					double speed = 0.35 + rnd.nextDouble() * 0.25;
					double fall = (now * speed + rnd.nextDouble() * s.h()) % s.h();
					double oy = s.h() - fall;
					sphere(glow, m, x + ox, y + oy, z + oz, 0.45F, argb(c, 0.45F * fade));
					sphere(glow, m, x + ox, y + oy, z + oz, 0.2F, argb(0xFFF4C0, 0.9F * fade));
					cylinder(glow, m, x + ox, y + oy, z + oz, x + ox, y + oy + 1.2, z + oz, 0.2F, 0.02F, argb(c, 0.35F * fade), argb(c, 0F));
				}
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ geometry (double-sided quads)

	private static void quad(VertexConsumer vc, Matrix4f m, double[] a, double[] b, double[] c, double[] d, int ca, int cb) {
		vc.addVertex(m, (float) a[0], (float) a[1], (float) a[2]).setColor(ca);
		vc.addVertex(m, (float) b[0], (float) b[1], (float) b[2]).setColor(cb);
		vc.addVertex(m, (float) c[0], (float) c[1], (float) c[2]).setColor(cb);
		vc.addVertex(m, (float) d[0], (float) d[1], (float) d[2]).setColor(ca);
		vc.addVertex(m, (float) d[0], (float) d[1], (float) d[2]).setColor(ca);
		vc.addVertex(m, (float) c[0], (float) c[1], (float) c[2]).setColor(cb);
		vc.addVertex(m, (float) b[0], (float) b[1], (float) b[2]).setColor(cb);
		vc.addVertex(m, (float) a[0], (float) a[1], (float) a[2]).setColor(ca);
	}

	private static void sphere(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, float r, int color) {
		int lat = 8;
		int lon = 14;
		for (int i = 0; i < lat; i++) {
			double t1 = Math.PI * i / lat;
			double t2 = Math.PI * (i + 1) / lat;
			for (int j = 0; j < lon; j++) {
				double p1 = 2 * Math.PI * j / lon;
				double p2 = 2 * Math.PI * (j + 1) / lon;
				quad(vc, m, sp(cx, cy, cz, r, t1, p1), sp(cx, cy, cz, r, t2, p1), sp(cx, cy, cz, r, t2, p2), sp(cx, cy, cz, r, t1, p2), color, color);
			}
		}
	}

	private static double[] sp(double cx, double cy, double cz, float r, double t, double p) {
		return new double[] {cx + r * Math.sin(t) * Math.cos(p), cy + r * Math.cos(t), cz + r * Math.sin(t) * Math.sin(p)};
	}

	/** Tube from a to b with radius ra..rb and colors ca..cb. */
	private static void cylinder(VertexConsumer vc, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz,
			float ra, float rb, int ca, int cb) {
		Vec3 axis = new Vec3(bx - ax, by - ay, bz - az);
		if (axis.lengthSqr() < 1.0E-6) {
			return;
		}
		axis = axis.normalize();
		Vec3 u = Math.abs(axis.y) < 0.9 ? axis.cross(new Vec3(0, 1, 0)).normalize() : axis.cross(new Vec3(1, 0, 0)).normalize();
		Vec3 v = axis.cross(u).normalize();
		int seg = 12;
		for (int i = 0; i < seg; i++) {
			double a1 = 2 * Math.PI * i / seg;
			double a2 = 2 * Math.PI * (i + 1) / seg;
			Vec3 o1 = u.scale(Math.cos(a1)).add(v.scale(Math.sin(a1)));
			Vec3 o2 = u.scale(Math.cos(a2)).add(v.scale(Math.sin(a2)));
			quad(vc, m,
					new double[] {ax + o1.x * ra, ay + o1.y * ra, az + o1.z * ra},
					new double[] {bx + o1.x * rb, by + o1.y * rb, bz + o1.z * rb},
					new double[] {bx + o2.x * rb, by + o2.y * rb, bz + o2.z * rb},
					new double[] {ax + o2.x * ra, ay + o2.y * ra, az + o2.z * ra}, ca, cb);
		}
	}

	/** Flat horizontal ring (accretion disk, ground glow), rotated by an angle. */
	private static void disc(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, float r1, float r2, float angle, int color) {
		int seg = 24;
		int outer = color & 0x00FFFFFF;
		for (int i = 0; i < seg; i++) {
			double a1 = angle + 2 * Math.PI * i / seg;
			double a2 = angle + 2 * Math.PI * (i + 1) / seg;
			double tilt = 0.25;
			quad(vc, m,
					new double[] {cx + Math.cos(a1) * r1, cy + Math.sin(a1) * r1 * tilt, cz + Math.sin(a1) * r1},
					new double[] {cx + Math.cos(a1) * r2, cy + Math.sin(a1) * r2 * tilt, cz + Math.sin(a1) * r2},
					new double[] {cx + Math.cos(a2) * r2, cy + Math.sin(a2) * r2 * tilt, cz + Math.sin(a2) * r2},
					new double[] {cx + Math.cos(a2) * r1, cy + Math.sin(a2) * r1 * tilt, cz + Math.sin(a2) * r1},
					color, outer);
		}
	}

	// ------------------------------------------------------------------ player copies

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void ghosts(WorldRenderContext ctx, AbstractClientPlayer player, List<double[]> spots, int color, float alphaStart,
			float alphaStep, Vec3 cam, float partial) {
		Minecraft mc = Minecraft.getInstance();
		EntityRenderer<?, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
		if (!(renderer instanceof AvatarRenderer avatar) || spots.isEmpty()) {
			return;
		}
		AvatarRenderState state = (AvatarRenderState) avatar.createRenderState();
		avatar.extractRenderState(player, state, partial);
		state.isInvisible = false;
		state.isInvisibleToPlayer = false;
		PlayerModel model = (PlayerModel) avatar.getModel();
		Identifier texture = avatar.getTextureLocation(state);
		VertexConsumer consumer = ctx.consumers().getBuffer(RenderTypes.entityTranslucent(texture));
		model.setupAnim(state);
		PoseStack pose = ctx.matrices();
		float scale = state.scale * 0.9375F;
		float alpha = alphaStart;
		for (double[] s : spots) {
			if (alpha <= 0.02F) {
				break;
			}
			pose.pushPose();
			pose.translate(s[0] - cam.x, s[1] - cam.y, s[2] - cam.z);
			pose.mulPose(Axis.YP.rotationDegrees(180.0F - (float) s[3]));
			pose.scale(-1.0F, -1.0F, 1.0F);
			pose.scale(scale, scale, scale);
			pose.translate(0.0F, -1.501F, 0.0F);
			model.renderToBuffer(pose, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, argb(color, alpha));
			pose.popPose();
			alpha -= alphaStep;
		}
	}

	/** See-through copies on the last positions of the player. */
	private static void trail(WorldRenderContext ctx, AbstractClientPlayer p, Deque<double[]> history, int color, Vec3 cam, float partial) {
		if (history == null || history.size() < 3) {
			return;
		}
		List<double[]> spots = new ArrayList<>();
		double[] last = new double[] {p.getX(), p.getY(), p.getZ()};
		int i = 0;
		for (Iterator<double[]> it = history.iterator(); it.hasNext();) {
			double[] h = it.next();
			i++;
			if (i < 2 || i % 2 != 0) {
				continue;
			}
			double dx = h[0] - last[0];
			double dz = h[2] - last[2];
			if (dx * dx + dz * dz < 0.36) { // too close to the previous copy (standing still)
				continue;
			}
			spots.add(h);
			last = h;
		}
		ghosts(ctx, p, spots, color, 0.45F, 0.08F, cam, partial);
	}

	/** A few blurred copies shaking around the player (Reverse Flash look). */
	private static void vibrate(WorldRenderContext ctx, AbstractClientPlayer p, int color, Vec3 cam, float partial) {
		Vec3 at = p.getPosition(partial);
		float yaw = p.yBodyRot;
		List<double[]> spots = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			spots.add(new double[] {at.x + RANDOM.nextGaussian() * 0.08, at.y + RANDOM.nextGaussian() * 0.04,
					at.z + RANDOM.nextGaussian() * 0.08, yaw + (float) RANDOM.nextGaussian() * 4});
		}
		ghosts(ctx, p, spots, color, 0.32F, 0.04F, cam, partial);
	}
}
