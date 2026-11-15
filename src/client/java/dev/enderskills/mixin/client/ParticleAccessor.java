package dev.enderskills.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.particle.Particle;

@Mixin(Particle.class)
public interface ParticleAccessor {
	@Accessor("x")
	double enderskills$x();

	@Accessor("y")
	double enderskills$y();

	@Accessor("z")
	double enderskills$z();
}
