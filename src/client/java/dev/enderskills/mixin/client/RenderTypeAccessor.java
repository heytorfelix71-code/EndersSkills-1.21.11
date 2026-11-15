package dev.enderskills.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/** RenderType.create is package-private: needed for the original-style shaders and textures. */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
	@Invoker("create")
	static RenderType enderskills$create(String name, RenderSetup setup) {
		throw new AssertionError();
	}
}
