package dev.enderskills.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import dev.enderskills.data.EsSettings;

@Mixin(Entity.class)
public abstract class EntityMixin {
	/** Invisible players don't kick up dust while sprinting. */
	@com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "canSpawnSprintParticle", at = @At("RETURN"))
	private boolean enderskills$noDust(boolean can) {
		Entity self = (Entity) (Object) this;
		return can && !(self instanceof Player && self.isInvisible() && EsSettings.get().invisibilityNoSprintDust);
	}
}
