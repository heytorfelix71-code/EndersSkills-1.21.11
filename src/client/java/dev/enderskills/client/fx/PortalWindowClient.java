package dev.enderskills.client.fx;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.enderskills.EnderSkills;
import dev.enderskills.data.EsSettings;
import dev.enderskills.network.WindowPayloads;

/**
 * The client side of the see-through portals: asks the server for the view through each portal close by (from the angle you look at
 * it), keeps one small texture per portal and gives it to the portal drawing. Old pictures are dropped when the portal is gone.
 */
public final class PortalWindowClient {
	private static final class Window {
		DynamicTexture texture;
		Identifier id;
		int lastAsk = -1000;
		int yawBucket = Integer.MIN_VALUE;
		int pitchBucket = Integer.MIN_VALUE;
		int lastSeen;
		boolean ready;
	}

	private static final Map<Integer, Window> WINDOWS = new HashMap<>();
	private static int clock;

	private PortalWindowClient() {
	}

	/** The texture showing the other side of this portal, or null (not received yet, or windows switched off). */
	public static Identifier texture(int view) {
		Window w = WINDOWS.get(view);
		if (w == null || !w.ready || !EsSettings.get().portalWindows) {
			return null;
		}
		w.lastSeen = clock;
		return w.id;
	}

	/** Called each tick for every portal close by (view id and the middle of its ring). */
	static void want(int view, Vec3 center) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !EsSettings.get().portalWindows || !ClientPlayNetworking.canSend(WindowPayloads.Request.TYPE)) {
			return;
		}
		Vec3 eye = mc.gameRenderer.getMainCamera().position();
		Vec3 d = center.subtract(eye);
		double dist = d.length();
		if (dist > 40 || dist < 0.5) {
			return;
		}
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		Window w = WINDOWS.computeIfAbsent(view, k -> new Window());
		w.lastSeen = clock;
		int yb = Math.round(yaw / 10F);
		int pb = Math.round(Math.max(-60F, Math.min(60F, pitch)) / 12F);
		boolean moved = yb != w.yawBucket || pb != w.pitchBucket;
		if ((moved && clock - w.lastAsk >= 4) || clock - w.lastAsk >= 50) {
			w.yawBucket = yb;
			w.pitchBucket = pb;
			w.lastAsk = clock;
			ClientPlayNetworking.send(new WindowPayloads.Request(view, yaw, pitch));
		}
	}

	public static void receive(WindowPayloads.Image image) {
		int size = image.size();
		if (size <= 0 || size > 256 || image.rgb().length < size * size * 3) {
			return;
		}
		Window w = WINDOWS.computeIfAbsent(image.view(), k -> new Window());
		w.lastSeen = clock;
		if (w.texture == null) {
			w.id = EnderSkills.id("portal_window/" + Integer.toUnsignedString(image.view()));
			w.texture = new DynamicTexture(() -> "enderskills portal window", new NativeImage(size, size, false));
			Minecraft.getInstance().getTextureManager().register(w.id, w.texture);
		}
		NativeImage pixels = w.texture.getPixels();
		if (pixels == null || pixels.getWidth() != size) {
			return;
		}
		byte[] rgb = image.rgb();
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int o = (y * size + x) * 3;
				pixels.setPixel(x, y, 0xFF000000 | (rgb[o] & 255) << 16 | (rgb[o + 1] & 255) << 8 | (rgb[o + 2] & 255));
			}
		}
		w.texture.upload();
		w.ready = true;
	}

	public static void tick(Minecraft mc) {
		clock++;
		if (clock % 100 == 0) {
			for (Iterator<Map.Entry<Integer, Window>> it = WINDOWS.entrySet().iterator(); it.hasNext();) {
				Window w = it.next().getValue();
				if (clock - w.lastSeen > 200) {
					if (w.id != null) {
						mc.getTextureManager().release(w.id);
					}
					it.remove();
				}
			}
		}
	}

	public static void reset() {
		Minecraft mc = Minecraft.getInstance();
		for (Window w : WINDOWS.values()) {
			if (w.id != null) {
				mc.getTextureManager().release(w.id);
			}
		}
		WINDOWS.clear();
	}
}
