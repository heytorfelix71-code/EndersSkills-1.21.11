package dev.enderskills.client.fx;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.Vec3;

/**
 * A black hole that looks like the real thing: a perfectly black event horizon, a bright photon ring, a tilted accretion
 * disk that glows from white-hot to deep red and swirls (one side brighter), the gravitational lens that bends its far side
 * over the top and under the bottom of the hole, blue-white jets and a hot halo. Everything is drawn with colored quads
 * (additive for the light, opaque black for the horizon), positions relative to the camera.
 */
public final class BlackHoleFx {
	/** Disk colors from the inner edge to the outer one: white-hot, yellow, orange, red, deep red. */
	private static final int[] STOPS = {0xFFF6E0, 0xFFD070, 0xFF9A2E, 0xE0481C, 0x7A1C28};

	private BlackHoleFx() {
	}

	private interface Shade {
		int at(double t, double angle);
	}

	private static int grad(double t) {
		double x = Math.max(0.0, Math.min(1.0, t)) * (STOPS.length - 1);
		int i = Math.min(STOPS.length - 2, (int) x);
		double f = x - i;
		int a = STOPS[i];
		int b = STOPS[i + 1];
		int r = (int) ((a >> 16 & 255) * (1 - f) + (b >> 16 & 255) * f);
		int g = (int) ((a >> 8 & 255) * (1 - f) + (b >> 8 & 255) * f);
		int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
		return r << 16 | g << 8 | bl;
	}

	private static void vert(VertexConsumer vc, Matrix4f m, Vec3 p, int argb) {
		vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(argb);
	}

	private static Vec3 at(Vec3 c, Vec3 u, Vec3 v, double r, double angle) {
		return c.add(u.scale(Math.cos(angle) * r)).add(v.scale(Math.sin(angle) * r));
	}

	/**
	 * A flat ring in the plane (u, v). With hideAxis set, the parts that are behind the black center as seen from the camera
	 * (the camera is at the origin) are not drawn: the horizon covers them.
	 */
	private static void annulus(VertexConsumer vc, Matrix4f m, Vec3 c, Vec3 u, Vec3 v, double rIn, double rOut, int rings, int segs,
			Vec3 hideAxis, double hideRadius, Shade shade) {
		for (int i = 0; i < rings; i++) {
			double t0 = i / (double) rings;
			double t1 = (i + 1) / (double) rings;
			double r0 = rIn + (rOut - rIn) * Math.pow(t0, 1.5);
			double r1 = rIn + (rOut - rIn) * Math.pow(t1, 1.5);
			for (int j = 0; j < segs; j++) {
				double a0 = Math.PI * 2 * j / segs;
				double a1 = Math.PI * 2 * (j + 1) / segs;
				Vec3 p00 = at(c, u, v, r0, a0);
				Vec3 p10 = at(c, u, v, r1, a0);
				Vec3 p11 = at(c, u, v, r1, a1);
				Vec3 p01 = at(c, u, v, r0, a1);
				if (hideAxis != null) {
					Vec3 mid = p00.add(p11).scale(0.5).subtract(c);
					double along = mid.dot(hideAxis);
					if (along > 0 && mid.subtract(hideAxis.scale(along)).length() < hideRadius) {
						continue;
					}
				}
				vert(vc, m, p00, shade.at(t0, a0));
				vert(vc, m, p10, shade.at(t1, a0));
				vert(vc, m, p11, shade.at(t1, a1));
				vert(vc, m, p01, shade.at(t0, a1));
			}
		}
	}

	private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3 p0, Vec3 p1, Vec3 side, double w0, double w1, int c0, int c1) {
		vert(vc, m, p0.subtract(side.scale(w0)), c0);
		vert(vc, m, p0.add(side.scale(w0)), c0);
		vert(vc, m, p1.add(side.scale(w1)), c1);
		vert(vc, m, p1.subtract(side.scale(w1)), c1);
	}

	/**
	 * @param glow   additive colored quads (RenderTypes.lightning)
	 * @param dark   opaque black quads, replayed before the glow (RenderTypes.debugQuads)
	 * @param radius the radius of the whole hole (the accretion disk reaches it)
	 */
	public static void draw(VertexConsumer glow, VertexConsumer dark, Matrix4f m, double cx, double cy, double cz, double radius,
			double time, float fade, float grow) {
		double core = Math.max(0.8, radius * 0.3) * grow * (1 + 0.015 * Math.sin(time * 0.3));
		if (core < 0.05 || fade <= 0.01F) {
			return;
		}
		float a = Math.min(1F, fade);
		Vec3 c = new Vec3(cx, cy, cz);
		double dist = Math.max(1.0E-3, c.length());
		Vec3 axis = c.scale(1.0 / dist);
		Vec3 right = axis.cross(new Vec3(0, 1, 0));
		if (right.lengthSqr() < 1.0E-6) {
			right = new Vec3(1, 0, 0);
		}
		right = right.normalize();
		Vec3 up = right.cross(axis).normalize();

		// 1. the event horizon: black, and a hot halo around everything
		FxDraw.sphere(dark, m, cx, cy, cz, core, 0xFF000000, 28);
		FxDraw.sphere(glow, m, cx, cy, cz, core * 5.0, FxDraw.argb(0x6A2CFF, 0.018F * a), 14);
		FxDraw.sphere(glow, m, cx, cy, cz, core * 3.3, FxDraw.argb(0xFF5A14, 0.03F * a), 18);
		FxDraw.sphere(glow, m, cx, cy, cz, core * 2.1, FxDraw.argb(0xFF8A24, 0.045F * a), 18);

		// 2. the accretion disk: tilted, swirling, brighter on the side that comes towards you, hidden where the horizon covers it
		Vec3 n = new Vec3(0.28, 0.93, 0.24).normalize();
		Vec3 u = n.cross(new Vec3(0, 0, 1)).normalize();
		Vec3 v = n.cross(u).normalize();
		double inner = core * 1.5;
		double outer = Math.max(radius, core * 3.0);
		annulus(glow, m, c, u, v, inner, outer, 24, 120, axis, core * 1.04, (t, ang) -> {
			double spin = time * 0.05 / (0.25 + t); // the inside turns faster than the outside
			double swirl = 0.5 + 0.5 * Math.sin((ang + spin) * 3 + Math.sqrt(t) * 14 - time * 0.2);
			double streak = 0.5 + 0.5 * Math.sin((ang + spin) * 11 - t * 38 + time * 0.6);
			double doppler = 0.55 + 0.45 * Math.cos(ang - 0.7);
			double light = (0.40 + 0.40 * swirl + 0.20 * streak) * doppler * Math.pow(1 - t, 0.9) + 0.35 * Math.pow(1 - t, 6);
			return FxDraw.argb(grad(Math.pow(t, 0.75)), (float) Math.min(1.0, light * 0.95) * a);
		});

		// 3. the gravitational lens: the photon ring and the far side of the disk bent around the hole (always faces you)
		annulus(glow, m, c, right, up, core * 1.0, core * 1.2, 4, 96, null, 0,
				(t, ang) -> FxDraw.argb(grad(0.05 + 0.2 * t), (float) (0.95 - 0.5 * t) * a));
		annulus(glow, m, c, right, up, core * 1.2, core * 2.3, 10, 96, null, 0, (t, ang) -> {
			double doppler = 0.6 + 0.4 * Math.cos(ang - 0.7);
			return FxDraw.argb(grad(0.1 + 0.8 * t), (float) (Math.pow(1 - t, 1.8) * 0.45 * doppler) * a);
		});

		// 4. the jets along the spin axis, blue-white
		Vec3 side1 = n.cross(axis);
		if (side1.lengthSqr() < 1.0E-6) {
			side1 = right;
		}
		side1 = side1.normalize();
		Vec3 side2 = n.cross(side1).normalize();
		int steps = 12;
		for (int sign = -1; sign <= 1; sign += 2) {
			for (int k = 0; k < steps; k++) {
				double t0 = k / (double) steps;
				double t1 = (k + 1) / (double) steps;
				Vec3 p0 = c.add(n.scale(sign * (core + core * 5.5 * t0)));
				Vec3 p1 = c.add(n.scale(sign * (core + core * 5.5 * t1)));
				double w0 = core * (0.10 + 0.30 * t0);
				double w1 = core * (0.10 + 0.30 * t1);
				float a0 = (float) (Math.pow(1 - t0, 1.6) * 0.5) * a;
				float a1 = (float) (Math.pow(1 - t1, 1.6) * 0.5) * a;
				ribbon(glow, m, p0, p1, side1, w0, w1, FxDraw.argb(0xB8D8FF, a0), FxDraw.argb(0xB8D8FF, a1));
				ribbon(glow, m, p0, p1, side2, w0, w1, FxDraw.argb(0xB8D8FF, a0), FxDraw.argb(0xB8D8FF, a1));
			}
		}
	}
}
