package dev.enderskills.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

import dev.enderskills.logic.SpeedHooks;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/** Chains with other mods changing the speed (Monk Mod...) instead of overriding them. */
	@com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "getSpeed", at = @At("RETURN"))
	private float enderskills$speed(float speed) {
		return SpeedHooks.modify((Player) (Object) this, speed);
	}
}
