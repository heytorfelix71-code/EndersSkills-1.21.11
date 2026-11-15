package dev.enderskills.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Stone of the Wall and Dome abilities: drops nothing and crumbles by itself after the ability's duration. */
public class EarthBlock extends Block {
	public static final MapCodec<EarthBlock> CODEC = simpleCodec(EarthBlock::new);

	public EarthBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		level.destroyBlock(pos, false);
	}
}
