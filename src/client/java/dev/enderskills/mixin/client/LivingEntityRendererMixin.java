package dev.enderskills.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import dev.enderskills.data.EsSettings;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	/** Invisible players show no name tag (even to teammates). */
	@com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"))
	private boolean enderskills$hideName(boolean show, LivingEntity entity, double distance) {
		return show && !(entity instanceof Player && entity.isInvisible() && EsSettings.get().invisibilityHidesName);
	}
}
