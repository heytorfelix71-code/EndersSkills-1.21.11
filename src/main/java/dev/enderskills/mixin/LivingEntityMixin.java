package dev.enderskills.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import dev.enderskills.logic.AttributeEffects;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	/** Resistance attributes of the victim and offensive attributes of a player attacker. */
	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float enderskills$attributes(float amount, ServerLevel level, DamageSource source) {
		return AttributeEffects.modifyDamage((LivingEntity) (Object) this, source, amount);
	}
}
