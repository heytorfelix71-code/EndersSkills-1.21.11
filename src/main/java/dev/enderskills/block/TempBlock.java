package dev.enderskills.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Something an ability leaves on the ground for a while (black fire, blood); it goes away by itself. */
public class TempBlock extends Block {
	public static final MapCodec<TempBlock> CODEC = simpleCodec(p -> new TempBlock(p, false));
	private static final VoxelShape FLAT = Block.box(0, 0, 0, 16, 1, 16);
	private static final VoxelShape FIRE = Block.box(0, 0, 0, 16, 1, 16);
	private final boolean fire;

	public TempBlock(Properties properties, boolean fire) {
		super(properties);
		this.fire = fire;
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return fire ? FIRE : FLAT;
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		level.removeBlock(pos, false);
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier applier, boolean intersects) {
		if (fire && level instanceof ServerLevel server && entity instanceof LivingEntity living && living.tickCount % 10 == 0) {
			living.hurtServer(server, server.damageSources().inFire(), 1.0F);
			living.addEffect(new net.minecraft.world.effect.MobEffectInstance(dev.enderskills.registry.ModEffects.BLACK_FLAME, 40, 0));
		}
	}
}
