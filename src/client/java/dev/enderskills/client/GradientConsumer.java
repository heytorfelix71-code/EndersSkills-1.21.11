package dev.enderskills.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Recolors a model as it is drawn: one color, opaque at the top and fading out towards the feet
 * (the black afterimages of Speed Boost and the Shadow clone).
 */
final class GradientConsumer implements VertexConsumer {
	private final VertexConsumer out;
	private final int rgb;
	private final float feet;
	private final float height;
	private final float top;
	private final float bottom;
	private float y;

	GradientConsumer(VertexConsumer out, int rgb, float feet, float height, float top, float bottom) {
		this.out = out;
		this.rgb = rgb & 0xFFFFFF;
		this.feet = feet;
		this.height = Math.max(0.1F, height);
		this.top = top;
		this.bottom = bottom;
	}

	private int color() {
		float f = Math.max(0F, Math.min(1F, (y - feet) / height));
		float a = bottom + (top - bottom) * f;
		return ((int) (Math.max(0F, Math.min(1F, a)) * 255F) << 24) | rgb;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		this.y = y;
		out.addVertex(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setColor(int r, int g, int b, int a) {
		out.setColor(color());
		return this;
	}

	@Override
	public VertexConsumer setColor(int argb) {
		out.setColor(color());
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		out.setUv(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		out.setUv1(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		out.setUv2(u, v);
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		out.setNormal(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setLineWidth(float width) {
		out.setLineWidth(width);
		return this;
	}
}
