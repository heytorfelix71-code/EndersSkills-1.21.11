package dev.enderskills.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Speed Boost makes you faster without stretching the camera (the FOV ignores its speed bonus). */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@WrapOperation(method = "getFieldOfViewModifier", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/AbstractClientPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
	private double enderskills$noBoostFov(AbstractClientPlayer player, Holder<Attribute> attribute, Operation<Double> original) {
		double value = original.call(player, attribute);
		if (attribute.is(Attributes.MOVEMENT_SPEED)) {
			AttributeInstance instance = player.getAttribute(Attributes.MOVEMENT_SPEED);
			AttributeModifier boost = instance == null ? null : instance.getModifier(dev.enderskills.EnderSkills.id("speed_boost"));
			if (boost != null && boost.amount() > -1) {
				value /= 1.0 + boost.amount();
			}
		}
		return value;
	}
}
