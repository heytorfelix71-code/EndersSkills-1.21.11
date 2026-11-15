package dev.enderskills.client.fx;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;

/**
 * Collects the effect geometry of a frame and hands it to the 1.21.11 submit queue (drawing straight into the buffers
 * at AFTER_ENTITIES shows nothing in this version). Positions are recorded camera-relative with an identity pose.
 */
public final class FxBuffers implements MultiBufferSource {
	private static final int POS = 0;
	private static final int COLOR = 3;
	private static final int UV = 4;
	private static final int UV1 = 6;
	private static final int UV2 = 8;
	private static final int NORMAL = 10;
	private static final int FLAGS = 13;
	private static final int SIZE = 14;

	private static final class Recorder implements VertexConsumer {
		float[] data = new float[SIZE * 256];
		int count;
		private int at = -SIZE;

		private float[] cur() {
			return data;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			at += SIZE;
			count++;
			if (at + SIZE > data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}
			data[at + POS] = x;
			data[at + POS + 1] = y;
			data[at + POS + 2] = z;
			data[at + FLAGS] = 0;
			data[at + COLOR] = Float.intBitsToFloat(0xFFFFFFFF);
			return this;
		}

		private void flag(int bit) {
			data[at + FLAGS] = (float) (((int) data[at + FLAGS]) | bit);
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return setColor((a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255));
		}

		@Override
		public VertexConsumer setColor(int argb) {
			cur()[at + COLOR] = Float.intBitsToFloat(argb);
			flag(1);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			data[at + UV] = u;
			data[at + UV + 1] = v;
			flag(2);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			data[at + UV1] = u;
			data[at + UV1 + 1] = v;
			flag(4);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			data[at + UV2] = u;
			data[at + UV2 + 1] = v;
			flag(8);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			data[at + NORMAL] = x;
			data[at + NORMAL + 1] = y;
			data[at + NORMAL + 2] = z;
			flag(16);
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}

		void replay(PoseStack.Pose pose, VertexConsumer out) {
			for (int i = 0; i < count; i++) {
				int o = i * SIZE;
				int f = (int) data[o + FLAGS];
				out.addVertex(pose, data[o + POS], data[o + POS + 1], data[o + POS + 2]);
				out.setColor(Float.floatToRawIntBits(data[o + COLOR]));
				if ((f & 2) != 0) {
					out.setUv(data[o + UV], data[o + UV + 1]);
				}
				if ((f & 2) == 0) {
					out.setUv(0F, 0F);
				}
				// entity-style formats (vanilla types used with shader packs) need these; other formats ignore them
				if ((f & 4) != 0) {
					out.setUv1((int) data[o + UV1], (int) data[o + UV1 + 1]);
				} else {
					out.setUv1(0, 10);
				}
				if ((f & 8) != 0) {
					out.setUv2((int) data[o + UV2], (int) data[o + UV2 + 1]);
				} else {
					out.setUv2(240, 240);
				}
				if ((f & 16) != 0) {
					out.setNormal(pose, data[o + NORMAL], data[o + NORMAL + 1], data[o + NORMAL + 2]);
				} else {
					out.setNormal(pose, 0F, 1F, 0F);
				}
			}
		}
	}

	private final Map<RenderType, Recorder> recorders = new LinkedHashMap<>();

	@Override
	public VertexConsumer getBuffer(RenderType type) {
		return recorders.computeIfAbsent(type, t -> new Recorder());
	}

	/** Sends everything recorded this frame to the game's submit queue. */
	public void submit(WorldRenderContext ctx) {
		PoseStack pose = new PoseStack();
		for (Map.Entry<RenderType, Recorder> e : recorders.entrySet()) {
			Recorder r = e.getValue();
			if (r.count == 0) {
				continue;
			}
			ctx.commandQueue().submitCustomGeometry(pose, e.getKey(), r::replay);
		}
		recorders.clear();
	}
}
