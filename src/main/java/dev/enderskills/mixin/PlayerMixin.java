package dev.enderskills.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.world.entity.player.Player;

import dev.enderskills.logic.PhaseHooks;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/** Walking through walls (Hasten, top level): the game sets "no physics" at the start of the tick only for spectators. */
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isSpectator()Z", ordinal = 0), require = 0)
	private boolean enderskills$phase(boolean spectator) {
		return spectator || PhaseHooks.phasing((Player) (Object) this);
	}
}
