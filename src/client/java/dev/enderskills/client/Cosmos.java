package dev.enderskills.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Animated End-themed space background and the glass panels drawn over it. */
public final class Cosmos {
	private static final Identifier END_SKY = Identifier.withDefaultNamespace("textures/environment/end_sky.png");

	private Cosmos() {
	}

	private static double hash(int i, int salt) {
		long x = (i * 0x9E3779B97F4A7C15L) ^ (salt * 0xC2B2AE3D27D4EB4FL);
		x ^= x >>> 31;
		x *= 0xBF58476D1CE4E5B9L;
		x ^= x >>> 29;
		return (x & 0xFFFFFF) / (double) 0x1000000;
	}

	private static int alpha(int argb, float a) {
		return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (argb & 0xFFFFFF);
	}

	/** Full-screen background: deep space, drifting End sky, nebulae, twinkling stars and rising star dust. */
	public static void background(GuiGraphics g, int w, int h) {
		double t = System.currentTimeMillis() / 50.0;
		g.fillGradient(0, 0, w, h, 0xFF05020D, 0xFF12061F);
		// drifting End sky texture, tinted violet
		int off = (int) (t * 0.15) % 128;
		for (int x = -off; x < w; x += 128) {
			for (int y = -off / 2; y < h; y += 128) {
				g.blit(RenderPipelines.GUI_TEXTURED, END_SKY, x, y, 0.0F, 0.0F, 128, 128, 128, 128, 0x553A1A6A);
			}
		}
		// nebulae (soft glowing discs)
		nebula(g, (int) (w * 0.22), (int) (h * 0.30), Math.max(w, h) / 3, 0x7A2BD9, t * 0.01);
		nebula(g, (int) (w * 0.78), (int) (h * 0.68), Math.max(w, h) / 3, 0x1F9E9E, t * 0.013 + 2);
		nebula(g, (int) (w * 0.55), (int) (h * 0.15), Math.max(w, h) / 5, 0xC04FD0, t * 0.017 + 4);
		// stars
		for (int i = 0; i < 160; i++) {
			int x = (int) (hash(i, 1) * w);
			int y = (int) (hash(i, 2) * h);
			float tw = 0.35F + 0.65F * (float) (0.5 + 0.5 * Math.sin(t * (0.05 + hash(i, 3) * 0.12) + i));
			int color = hash(i, 4) < 0.15 ? 0xB8F4FF : hash(i, 4) < 0.3 ? 0xE6C8FF : 0xFFFFFF;
			int size = hash(i, 5) < 0.12 ? 2 : 1;
			g.fill(x, y, x + size, y + size, alpha(color, tw));
			if (size == 2 && tw > 0.8F) { // little cross glare on the brightest stars
				g.fill(x - 2, y, x + 4, y + 1, alpha(color, (tw - 0.8F) * 2));
				g.fill(x, y - 2, x + 1, y + 4, alpha(color, (tw - 0.8F) * 2));
			}
		}
		// star dust rising like End rod particles
		for (int i = 0; i < 40; i++) {
			double speed = 0.2 + hash(i, 6) * 0.5;
			int x = (int) (hash(i, 7) * w + Math.sin(t * 0.02 + i) * 6);
			int y = (int) (h - ((t * speed + hash(i, 8) * h) % (h + 20)));
			g.fill(x, y, x + 1, y + 2, alpha(0xF0E0FF, 0.25F + 0.4F * (float) hash(i, 9)));
		}
	}

	private static void nebula(GuiGraphics g, int cx, int cy, int radius, int color, double phase) {
		cx += (int) (Math.sin(phase) * radius * 0.08);
		cy += (int) (Math.cos(phase * 0.8) * radius * 0.06);
		for (int layer = 0; layer < 4; layer++) {
			int r = radius * (4 - layer) / 4;
			int a = 0x0E + layer * 0x06;
			for (int dy = -r; dy <= r; dy += 2) {
				int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
				g.fill(cx - half, cy + dy, cx + half, cy + dy + 2, (a << 24) | color);
			}
		}
	}

	/** Dark glass panel with a glowing gradient border. */
	public static void panel(GuiGraphics g, int x, int y, int w, int h, int accent) {
		g.fillGradient(x, y, x + w, y + h, 0xD00C0618, 0xE0080412);
		int glow = accent & 0xFFFFFF;
		g.fill(x - 1, y - 1, x + w + 1, y, 0xC0000000 | glow);
		g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0x60000000 | glow);
		g.fillGradient(x - 1, y, x, y + h, 0xC0000000 | glow, 0x60000000 | glow);
		g.fillGradient(x + w, y, x + w + 1, y + h, 0xC0000000 | glow, 0x60000000 | glow);
		g.fill(x - 3, y - 3, x + w + 3, y - 1, 0x20000000 | glow);
		g.fill(x - 3, y + h + 1, x + w + 3, y + h + 3, 0x14000000 | glow);
	}

	/** Rounded "chip" (buttons, tabs, bars). */
	public static void chip(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
		g.fill(x + 1, y, x + w - 1, y + h, fill);
		g.fill(x, y + 1, x + 1, y + h - 1, fill);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, fill);
		g.fill(x + 1, y, x + w - 1, y + 1, border);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
		g.fill(x, y + 1, x + 1, y + h - 1, border);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
	}

	/** Progress bar with a shiny gradient fill. */
	public static void bar(GuiGraphics g, int x, int y, int w, int h, float value, int from, int to) {
		chip(g, x, y, w, h, 0xA0000000, 0x60FFFFFF);
		int fw = Math.round((w - 2) * Math.max(0, Math.min(1, value)));
		if (fw > 0) {
			g.fillGradient(x + 1, y + 1, x + 1 + fw, y + h - 1, 0xFF000000 | to, 0xFF000000 | from);
			g.fill(x + 1, y + 1, x + 1 + fw, y + 2, 0x60FFFFFF);
		}
	}
}
