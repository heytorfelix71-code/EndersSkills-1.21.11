package dev.enderskills.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;

@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
	@Inject(method = "click", at = @At("HEAD"), require = 0)
	private static void enderskills$shiftAtPress(InputConstants.Key key, CallbackInfo ci) {
		dev.enderskills.client.ShiftOnPress.record(key);
	}
}
