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
	/** entity -> kind -> [until, count, white] (Shadow clone, rune rings, red look). */
	private static final Map<Integer, Map<Integer, long[]>> ON_PLAYER = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();
	/** Geometry of the frame being drawn (sent to the submit queue at the end). */
	private static dev.enderskills.client.fx.FxBuffers frame = new dev.enderskills.client.fx.FxBuffers();

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
		if (p.kind() == Shapes.CLONE || p.kind() == Shapes.RUNES || p.kind() == Shapes.RED) {
			if (p.ticks() <= 0) {
				Map<Integer, long[]> m = ON_PLAYER.get(p.entity());
				if (m != null) {
					m.remove(p.kind());
				}
			} else {
				ON_PLAYER.computeIfAbsent(p.entity(), k -> new HashMap<>()).put(p.kind(),
						new long[] {p.ticks() >= Integer.MAX_VALUE / 2 ? Long.MAX_VALUE : now + p.ticks(), (long) p.height(), p.dx() > 0.5F ? 1 : 0});
			}
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
		ON_PLAYER.clear();
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
		ON_PLAYER.values().forEach(m -> m.values().removeIf(v -> v[0] < now));
		ON_PLAYER.values().removeIf(Map::isEmpty);
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
		dev.enderskills.client.fx.FxBuffers buffers = new dev.enderskills.client.fx.FxBuffers();
		frame = buffers;
		if (buffers == null) {
			return;
		}
		EsSettings cfg = EsSettings.get();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double now = mc.level.getGameTime() + partial;
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack pose = new PoseStack();
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
		for (Entity ent : mc.level.entitiesForRendering()) {
			if (ent instanceof dev.enderskills.entity.ShadowEntity shadow && mc.level.getEntity(shadow.ownerEntityId()) instanceof AbstractClientPlayer owner) {
				minion(ctx, owner, shadow, cam, partial);
			}
		}
		for (Map.Entry<Integer, Map<Integer, long[]>> e : ON_PLAYER.entrySet()) {
			if (mc.level.getEntity(e.getKey()) instanceof AbstractClientPlayer p) {
				onPlayer(ctx, p, e.getValue(), cam, partial, now);
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
		buffers.submit(ctx);
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
		ghosts(ctx, player, spots, color, alphaStart, alphaStep, alphaStart, cam, partial, 1.0F);
	}

	/**
	 * Copies of the player in their current pose and animation (other animation mods included), at the given spots.
	 * Each copy is one color, {@code bottomFactor} x as opaque at the feet as at the top.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void ghosts(WorldRenderContext ctx, AbstractClientPlayer player, List<double[]> spots, int color, float alphaStart,
			float alphaStep, float bottomFactor, Vec3 cam, float partial, float grow) {
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
		VertexConsumer consumer = frame.getBuffer(RenderTypes.entityTranslucent(texture));
		model.setupAnim(state);
		PoseStack pose = new PoseStack();
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
			pose.scale(scale * grow, scale * grow, scale * grow);
			pose.translate(0.0F, -1.501F, 0.0F);
			float feet = (float) (s[1] - cam.y);
			VertexConsumer tinted = new GradientConsumer(consumer, color, feet, state.boundingBoxHeight > 0 ? state.boundingBoxHeight : 1.8F,
					alpha, alpha * bottomFactor);
			model.renderToBuffer(pose, tinted, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, argb(color, alpha));
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
			spots.add(new double[] {h[0], h[1], h[2], p.yBodyRot});
			last = h;
		}
		// black, fading from the head down to the feet; each copy moves and looks like the player right now
		real(ctx, p, spots, 0x000000, 0.6F, 0.1F, cam, partial);
	}

	/**
	 * Copies drawn by the game's own player renderer (so EMF / Fresh Animations / Smooth Player Animations animate them:
	 * walking, looking, hitting), recolored with one see-through color.
	 */
	private static void real(WorldRenderContext ctx, AbstractClientPlayer player, List<double[]> spots, int rgb, float alphaStart,
			float alphaStep, Vec3 cam, float partial) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		net.minecraft.client.renderer.state.CameraRenderState camera = ctx.worldState().cameraRenderState;
		float alpha = alphaStart;
		for (double[] s : spots) {
			if (alpha <= 0.02F) {
				break;
			}
			net.minecraft.client.renderer.entity.state.EntityRenderState state = dispatcher.extractEntity(player, partial);
			state.nameTag = null;
			state.shadowPieces.clear();
			state.shadowRadius = 0F;
			state.outlineColor = 0;
			if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) {
				living.isInvisibleToPlayer = false;
			}
			dispatcher.submit(state, camera, s[0] - cam.x, s[1] - cam.y, s[2] - cam.z, new PoseStack(),
					new TintCollector(ctx.commandQueue(), argb(rgb, alpha)));
			alpha -= alphaStep;
		}
	}

	/** Shadow clone at the side, rune rings, red look. */
	private static void onPlayer(WorldRenderContext ctx, AbstractClientPlayer p, Map<Integer, long[]> fx, Vec3 cam, float partial, double now) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 at = p.getPosition(partial);
		boolean firstPerson = p == mc.player && !mc.gameRenderer.getMainCamera().isDetached();
		long[] clone = fx.get(Shapes.CLONE);
		if (clone != null) {
			double yaw = Math.toRadians(p.yBodyRot);
			double rx = -Math.cos(yaw);
			double rz = -Math.sin(yaw);
			double fx2 = -Math.sin(yaw);
			double fz2 = Math.cos(yaw);
			List<double[]> spot = List.of(new double[] {at.x + rx * 1.1 - fx2 * 0.3, at.y, at.z + rz * 1.1 - fz2 * 0.3, p.yBodyRot});
			real(ctx, p, spot, 0x000000, 0.9F, 0F, cam, partial);
		}
		long[] red = fx.get(Shapes.RED);
		if (red != null && !firstPerson) {
			ghosts(ctx, p, List.of(new double[] {at.x, at.y, at.z, p.yBodyRot}), 0xC00010, 0.32F, 0F, 0.5F, cam, partial, 1.04F);
		}
		long[] runes = fx.get(Shapes.RUNES);
		if (runes != null) {
			boolean white = runes[2] == 1;
			VertexConsumer vc = frame.getBuffer(dev.enderskills.client.fx.FxDraw.additive(white ? "fire_spirit_" : "fire_spirit"));
			Matrix4f m = new Matrix4f();
			int count = (int) Math.max(1, Math.min(2, runes[1]));
			int color = dev.enderskills.client.fx.FxDraw.argb(0xFFFFFF, 0.9F);
			if (firstPerson) {
				// in first person they stay flat at the feet, so they don't cover the view
				for (int i = 0; i < count; i++) {
					dev.enderskills.client.fx.FxDraw.texFlat(vc, m, at.x - cam.x, at.y - cam.y + 0.1 + i * 0.05, at.z - cam.z, 1.25 + i * 0.3,
							now * (i % 2 == 0 ? 0.04 : -0.06), color, 0F, 1F);
				}
			} else {
				// standing rune circles that spin around the player; the second one (level 40+) crosses the first and turns the other way
				Vec3 c = at.add(0, p.getBbHeight() * 0.55, 0).subtract(cam);
				for (int i = 0; i < count; i++) {
					double dirSign = i == 0 ? 1 : -1;
					double yaw = now * 0.08 * dirSign + i * Math.PI / 2;
					double tilt = 0.25 * dirSign;
					double roll = now * 0.04 * dirSign;
					double half = 1.05 + i * 0.12;
					Vec3 n = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
					Vec3 u = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
					Vec3 v = new Vec3(0, Math.cos(tilt), 0).add(n.scale(Math.sin(tilt)));
					Vec3 a = u.scale(Math.cos(roll)).add(v.scale(Math.sin(roll))).scale(half);
					Vec3 b = v.scale(Math.cos(roll)).subtract(u.scale(Math.sin(roll))).scale(half);
					dev.enderskills.client.fx.FxDraw.plane(vc, m, c, a, b, color);
				}
			}
		}
	}

	/**
	 * A fighting shadow: the owner's own look (skin, model, EMF / Fresh Animations), but standing, walking, looking and
	 * hitting like the shadow entity, all in black.
	 */
	private static void minion(WorldRenderContext ctx, AbstractClientPlayer owner, dev.enderskills.entity.ShadowEntity shadow, Vec3 cam, float partial) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		net.minecraft.client.renderer.entity.state.EntityRenderState state = dispatcher.extractEntity(owner, partial);
		Vec3 at = shadow.getPosition(partial);
		state.nameTag = null;
		state.shadowPieces.clear();
		state.shadowRadius = 0F;
		state.outlineColor = 0;
		state.x = at.x;
		state.y = at.y;
		state.z = at.z;
		if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) {
			living.isInvisibleToPlayer = false;
			living.bodyRot = net.minecraft.util.Mth.rotLerp(partial, shadow.yBodyRotO, shadow.yBodyRot);
			float head = net.minecraft.util.Mth.rotLerp(partial, shadow.yHeadRotO, shadow.yHeadRot);
			living.yRot = net.minecraft.util.Mth.wrapDegrees(head - living.bodyRot);
			living.xRot = shadow.getXRot(partial);
			living.walkAnimationPos = shadow.walkAnimation.position(partial);
			living.walkAnimationSpeed = shadow.walkAnimation.speed(partial);
			// swims and sinks like the owner: the swimming pose and its lean come from the shadow itself
			living.pose = shadow.getPose() == net.minecraft.world.entity.Pose.SWIMMING ? net.minecraft.world.entity.Pose.SWIMMING
					: net.minecraft.world.entity.Pose.STANDING;
			living.isInWater = shadow.isInWater();
		}
		if (state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid) {
			humanoid.isCrouching = false;
			humanoid.swimAmount = shadow.getSwimAmount(partial);
		}
		if (state instanceof net.minecraft.client.renderer.entity.state.ArmedEntityRenderState armed) {
			armed.attackTime = shadow.getAttackAnim(partial);
		}
		dispatcher.submit(state, ctx.worldState().cameraRenderState, at.x - cam.x, at.y - cam.y, at.z - cam.z, new PoseStack(),
				new TintCollector(ctx.commandQueue(), argb(0x000000, 0.9F)));
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
		real(ctx, p, spots, color, 0.32F, 0.04F, cam, partial);
	}
}
