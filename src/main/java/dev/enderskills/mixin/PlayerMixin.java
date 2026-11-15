package dev.enderskills.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

import dev.enderskills.logic.SpeedHooks;

@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
	private void enderskills$speed(CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(SpeedHooks.modify((Player) (Object) this, cir.getReturnValueF()));
	}
}
