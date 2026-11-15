package dev.enderskills.logic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import dev.enderskills.registry.ModEffects;

/** Plays the original sound of a skill when it is used. */
final class ModEffectsHook {
	private ModEffectsHook() {
	}

	static void sound(ServerPlayer player, String skill) {
		ModEffects.play((ServerLevel) player.level(), player.position(), skill);
	}
}
