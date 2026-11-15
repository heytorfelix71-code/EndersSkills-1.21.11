package dev.enderskills.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;

/** In the "original" effect style, the particles sent by ability code are dropped: clients draw the original look instead. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I", at = @At("HEAD"), cancellable = true)
	private <T extends ParticleOptions> void enderskills$quiet(T particle, boolean force, boolean always, double x, double y, double z,
			int count, double dx, double dy, double dz, double speed, CallbackInfoReturnable<Integer> cir) {
		if (dev.enderskills.logic.Vfx.quiet()
				|| dev.enderskills.logic.AbilityManager.nearInvisible((ServerLevel) (Object) this, x, y, z)) {
			cir.setReturnValue(0);
		}
	}
}
