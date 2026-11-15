package dev.enderskills.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.entity.player.Player;

/** No particle at all around an invisible player (potion swirls, steps, hits, ability effects): nothing gives them away. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
	private void enderskills$hideNearInvisible(Particle particle, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !dev.enderskills.data.EsSettings.get().invisibilityHidesParticles) {
			return;
		}
		ParticleAccessor p = (ParticleAccessor) particle;
		double x = p.enderskills$x();
		double y = p.enderskills$y();
		double z = p.enderskills$z();
		for (Player player : mc.level.players()) {
			if (player.isInvisible() && player.getBoundingBox().inflate(1.5, 1.0, 1.5).contains(x, y, z)) {
				ci.cancel();
				return;
			}
		}
	}
}
