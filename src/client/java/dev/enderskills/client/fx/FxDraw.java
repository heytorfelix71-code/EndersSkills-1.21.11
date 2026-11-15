package dev.enderskills.client.fx;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import dev.enderskills.EnderSkills;
import dev.enderskills.mixin.client.RenderTypeAccessor;

/** Render types (the original mod's shaders) and the shapes they are drawn on: spheres, tubes, cubes, sprites, rings. */
public final class FxDraw {
	private FxDraw() {
	}

	public static Identifier tex(String name) {
		return EnderSkills.id("textures/fx/" + name + ".png");
	}

	private static final Identifier END_PORTAL = Identifier.withDefaultNamespace("textures/entity/end_portal.png");

	private static RenderPipeline universePipeline(String name, String shader, boolean white) {
		RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
				.withLocation(EnderSkills.id("pipeline/" + name))
				.withVertexShader(EnderSkills.id("core/" + shader))
				.withFragmentShader(EnderSkills.id("core/" + shader))
				.withSampler("Sampler0")
				.withBlend(BlendFunction.TRANSLUCENT)
				.withCull(false)
				.withDepthWrite(false)
				.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS);
		if (white) {
			b.withShaderDefine("WHITE");
		}
		return RenderPipelines.register(b.build());
	}

	private static RenderPipeline spritePipeline(String name, BlendFunction blend) {
		return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
				.withLocation(EnderSkills.id("pipeline/" + name))
				.withVertexShader("core/position_tex_color")
				.withFragmentShader("core/position_tex_color")
				.withSampler("Sampler0")
				.withBlend(blend)
				.withCull(false)
				.withDepthWrite(false)
				.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
				.build());
	}

	private static final RenderPipeline UNIVERSE = universePipeline("universe", "universe", false);
	private static final RenderPipeline UNIVERSE_DEFAULT = universePipeline("universe_default", "universe_default", false);
	private static final RenderPipeline UNIVERSE_WHITE = universePipeline("universe_default_white", "universe_default", true);
	private static final RenderPipeline ADD = spritePipeline("fx_additive", BlendFunction.ADDITIVE);
	private static final RenderPipeline ALPHA = spritePipeline("fx_alpha", BlendFunction.TRANSLUCENT);

	private static final Map<String, RenderType> TYPES = new HashMap<>();

	private static RenderType type(String key, RenderPipeline pipeline, Identifier texture) {
		return TYPES.computeIfAbsent(key, k -> RenderTypeAccessor.enderskills$create("enderskills_" + k.replace(':', '_').replace('/', '_'),
				RenderSetup.builder(pipeline).withTexture("Sampler0", texture).sortOnUpload().createRenderSetup()));
	}

	/** Volumetric star field tinted with the vertex color (gas cloud, black hole arms, rifts, grasp). */
	public static RenderType universe() {
		return type("universe", UNIVERSE, tex("gas_cloud"));
	}

	/** Dark layered end sky (black hole core, raging flame ball, unstable portal). */
	public static RenderType universeDark() {
		return type("universe_default", UNIVERSE_DEFAULT, END_PORTAL);
	}

	/** White layered sky (Solar Lance, Final Flash). */
	public static RenderType universeWhite() {
		return type("universe_white", UNIVERSE_WHITE, END_PORTAL);
	}

	/** Glowing textured quads (added light). */
	public static RenderType additive(String texture) {
		return type("add/" + texture, ADD, tex(texture));
	}

	/** Textured quads with transparency. */
	public static RenderType alpha(String texture) {
		return type("alpha/" + texture, ALPHA, tex(texture));
	}

	// ------------------------------------------------------------------ shapes (positions relative to the camera)

	public static int argb(int rgb, float alpha) {
		return ((int) (Math.max(0F, Math.min(1F, alpha)) * 255F) << 24) | (rgb & 0xFFFFFF);
	}

	private static void v(VertexConsumer vc, Matrix4f m, double x, double y, double z, int color) {
		vc.addVertex(m, (float) x, (float) y, (float) z).setColor(color);
	}

	private static void vt(VertexConsumer vc, Matrix4f m, double x, double y, double z, float u, float w, int color) {
		vc.addVertex(m, (float) x, (float) y, (float) z).setUv(u, w).setColor(color);
	}

	/** Sphere for the universe shaders (POSITION_COLOR). */
	public static void sphere(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, double r, int color, int seg) {
		for (int i = 0; i < seg; i++) {
			double t0 = Math.PI * i / seg - Math.PI / 2;
			double t1 = Math.PI * (i + 1) / seg - Math.PI / 2;
			for (int j = 0; j < seg * 2; j++) {
				double p0 = Math.PI * 2 * j / (seg * 2);
				double p1 = Math.PI * 2 * (j + 1) / (seg * 2);
				v(vc, m, cx + r * Math.cos(t0) * Math.cos(p0), cy + r * Math.sin(t0), cz + r * Math.cos(t0) * Math.sin(p0), color);
				v(vc, m, cx + r * Math.cos(t0) * Math.cos(p1), cy + r * Math.sin(t0), cz + r * Math.cos(t0) * Math.sin(p1), color);
				v(vc, m, cx + r * Math.cos(t1) * Math.cos(p1), cy + r * Math.sin(t1), cz + r * Math.cos(t1) * Math.sin(p1), color);
				v(vc, m, cx + r * Math.cos(t1) * Math.cos(p0), cy + r * Math.sin(t1), cz + r * Math.cos(t1) * Math.sin(p0), color);
			}
		}
	}

	/** Textured sphere (POSITION_TEX_COLOR); frames > 1 picks one frame of a vertical strip. */
	public static void texSphere(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, double r, int color, int seg,
			float uScale, float vOffset) {
		for (int i = 0; i < seg; i++) {
			double t0 = Math.PI * i / seg - Math.PI / 2;
			double t1 = Math.PI * (i + 1) / seg - Math.PI / 2;
			float v0 = (float) i / seg + vOffset;
			float v1 = (float) (i + 1) / seg + vOffset;
			for (int j = 0; j < seg * 2; j++) {
				double p0 = Math.PI * 2 * j / (seg * 2);
				double p1 = Math.PI * 2 * (j + 1) / (seg * 2);
				float u0 = uScale * j / (seg * 2);
				float u1 = uScale * (j + 1) / (seg * 2);
				vt(vc, m, cx + r * Math.cos(t0) * Math.cos(p0), cy + r * Math.sin(t0), cz + r * Math.cos(t0) * Math.sin(p0), u0, v0, color);
				vt(vc, m, cx + r * Math.cos(t0) * Math.cos(p1), cy + r * Math.sin(t0), cz + r * Math.cos(t0) * Math.sin(p1), u1, v0, color);
				vt(vc, m, cx + r * Math.cos(t1) * Math.cos(p1), cy + r * Math.sin(t1), cz + r * Math.cos(t1) * Math.sin(p1), u1, v1, color);
				vt(vc, m, cx + r * Math.cos(t1) * Math.cos(p0), cy + r * Math.sin(t1), cz + r * Math.cos(t1) * Math.sin(p0), u0, v1, color);
			}
		}
	}

	/** Open textured cylinder standing on (cx, cy, cz). v from vMin to vMax along the height. */
	public static void texCylinder(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, double r, double h, int color, int seg,
			float uScale, float vMin, float vMax) {
		for (int j = 0; j < seg; j++) {
			double p0 = Math.PI * 2 * j / seg;
			double p1 = Math.PI * 2 * (j + 1) / seg;
			float u0 = uScale * j / seg;
			float u1 = uScale * (j + 1) / seg;
			vt(vc, m, cx + r * Math.cos(p0), cy, cz + r * Math.sin(p0), u0, vMax, color);
			vt(vc, m, cx + r * Math.cos(p1), cy, cz + r * Math.sin(p1), u1, vMax, color);
			vt(vc, m, cx + r * Math.cos(p1), cy + h, cz + r * Math.sin(p1), u1, vMin, color);
			vt(vc, m, cx + r * Math.cos(p0), cy + h, cz + r * Math.sin(p0), u0, vMin, color);
		}
	}

	/** Flat textured square on the ground, turned by an angle. */
	public static void texFlat(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, double half, double angle, int color,
			float v0, float v1) {
		double c = Math.cos(angle) * half;
		double s = Math.sin(angle) * half;
		vt(vc, m, cx - c + s, cy, cz - s - c, 0, v0, color);
		vt(vc, m, cx + c + s, cy, cz + s - c, 1, v0, color);
		vt(vc, m, cx + c - s, cy, cz + s + c, 1, v1, color);
		vt(vc, m, cx - c - s, cy, cz - s + c, 0, v1, color);
	}

	/** Flat disc (both sides drawn: no culling) for the universe shaders, turning with the angle. */
	public static void disc(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, double r, int color, int seg, double angle) {
		for (int j = 0; j < seg; j++) {
			double p0 = angle + Math.PI * 2 * j / seg;
			double p1 = angle + Math.PI * 2 * (j + 1) / seg;
			v(vc, m, cx, cy, cz, color);
			v(vc, m, cx + r * Math.cos(p0), cy, cz + r * Math.sin(p0), color);
			v(vc, m, cx + r * Math.cos(p1), cy, cz + r * Math.sin(p1), color);
			v(vc, m, cx, cy, cz, color);
		}
	}

	/** Square facing the camera. */
	public static void sprite(VertexConsumer vc, Matrix4f m, Vector3fc left, Vector3fc up, double x, double y, double z, double half,
			float roll, int color) {
		Vector3f l = new Vector3f(left).mul((float) half);
		Vector3f u = new Vector3f(up).mul((float) half);
		if (roll != 0F) {
			float c = (float) Math.cos(roll);
			float s = (float) Math.sin(roll);
			Vector3f l2 = new Vector3f(l).mul(c).add(new Vector3f(u).mul(s));
			Vector3f u2 = new Vector3f(u).mul(c).sub(new Vector3f(l).mul(s));
			l = l2;
			u = u2;
		}
		vt(vc, m, x + l.x - u.x, y + l.y - u.y, z + l.z - u.z, 0, 1, color);
		vt(vc, m, x - l.x - u.x, y - l.y - u.y, z - l.z - u.z, 1, 1, color);
		vt(vc, m, x - l.x + u.x, y - l.y + u.y, z - l.z + u.z, 1, 0, color);
		vt(vc, m, x + l.x + u.x, y + l.y + u.y, z + l.z + u.z, 0, 0, color);
	}

	/** Tapered tube through points (the original glePolyCone), POSITION_COLOR for the universe shaders. */
	public static void tube(VertexConsumer vc, Matrix4f m, Vec3[] pts, double[] radii, int color, int seg) {
		Vec3[][] rings = new Vec3[pts.length][];
		for (int i = 0; i < pts.length; i++) {
			Vec3 dir = (i + 1 < pts.length ? pts[i + 1] : pts[i]).subtract(i > 0 ? pts[i - 1] : pts[i]);
			if (dir.lengthSqr() < 1.0E-8) {
				dir = new Vec3(0, 1, 0);
			}
			dir = dir.normalize();
			Vec3 a = Math.abs(dir.y) < 0.9 ? dir.cross(new Vec3(0, 1, 0)).normalize() : dir.cross(new Vec3(1, 0, 0)).normalize();
			Vec3 b = dir.cross(a);
			rings[i] = new Vec3[seg];
			for (int k = 0; k < seg; k++) {
				double ang = Math.PI * 2 * k / seg;
				rings[i][k] = pts[i].add(a.scale(Math.cos(ang) * radii[i])).add(b.scale(Math.sin(ang) * radii[i]));
			}
		}
		for (int i = 0; i + 1 < pts.length; i++) {
			for (int k = 0; k < seg; k++) {
				Vec3 p0 = rings[i][k];
				Vec3 p1 = rings[i][(k + 1) % seg];
				Vec3 p2 = rings[i + 1][(k + 1) % seg];
				Vec3 p3 = rings[i + 1][k];
				v(vc, m, p0.x, p0.y, p0.z, color);
				v(vc, m, p1.x, p1.y, p1.z, color);
				v(vc, m, p2.x, p2.y, p2.z, color);
				v(vc, m, p3.x, p3.y, p3.z, color);
			}
		}
	}

	/** Cube for the universe shaders, turned around Y and X. */
	public static void cube(VertexConsumer vc, Matrix4f m, double cx, double cy, double cz, double half, double yaw, double pitch, int color) {
		double[][] c = new double[8][];
		for (int i = 0; i < 8; i++) {
			double x = (i & 1) == 0 ? -half : half;
			double y = (i & 2) == 0 ? -half : half;
			double z = (i & 4) == 0 ? -half : half;
			double y2 = y * Math.cos(pitch) - z * Math.sin(pitch);
			double z2 = y * Math.sin(pitch) + z * Math.cos(pitch);
			double x3 = x * Math.cos(yaw) - z2 * Math.sin(yaw);
			double z3 = x * Math.sin(yaw) + z2 * Math.cos(yaw);
			c[i] = new double[] {cx + x3, cy + y2, cz + z3};
		}
		int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
		for (int[] f : faces) {
			for (int i : f) {
				v(vc, m, c[i][0], c[i][1], c[i][2], color);
			}
		}
	}

	/** Box along a direction (the black fists of Shadow Jab): width x width x length. */
	public static void box(VertexConsumer vc, Matrix4f m, Vec3 center, Vec3 dir, double width, double length, int color) {
		Vec3 d = dir.lengthSqr() < 1.0E-8 ? new Vec3(0, 0, 1) : dir.normalize();
		Vec3 a = Math.abs(d.y) < 0.9 ? d.cross(new Vec3(0, 1, 0)).normalize() : d.cross(new Vec3(1, 0, 0)).normalize();
		Vec3 b = d.cross(a);
		Vec3[] c = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			c[i] = center.add(a.scale(((i & 1) == 0 ? -0.5 : 0.5) * width)).add(b.scale(((i & 2) == 0 ? -0.5 : 0.5) * width))
					.add(d.scale(((i & 4) == 0 ? -0.5 : 0.5) * length));
		}
		int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
		for (int[] f : faces) {
			for (int i : f) {
				v(vc, m, c[i].x, c[i].y, c[i].z, color);
			}
		}
	}
}
