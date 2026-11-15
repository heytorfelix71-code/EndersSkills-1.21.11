package dev.enderskills.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

/**
 * Whether Shift was held at the moment a key was pressed. The ability keys are read once per tick (20 times a second):
 * a quick Shift + key tap can be over before that, so the Shift state is taken when the key is pressed instead.
 */
public final class ShiftOnPress {
	private static final Map<InputConstants.Key, ArrayDeque<Boolean>> PRESSES = new HashMap<>();

	private ShiftOnPress() {
	}

	/** Called by the game for every key press. */
	public static void record(InputConstants.Key key) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getWindow() == null) {
			return;
		}
		ArrayDeque<Boolean> q = PRESSES.computeIfAbsent(key, k -> new ArrayDeque<>());
		q.addLast(mc.hasShiftDown());
		while (q.size() > 8) {
			q.removeFirst();
		}
	}

	/** Shift state of the oldest press of this key not read yet. */
	public static boolean take(KeyMapping mapping) {
		ArrayDeque<Boolean> q = PRESSES.get(KeyBindingHelper.getBoundKeyOf(mapping));
		Boolean shift = q == null ? null : q.pollFirst();
		return shift != null && shift;
	}
}
