package dev.enderskills.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.enderskills.logic.Progression;
import dev.enderskills.registry.ModContent;

/**
 * Break Altar (6 woods) and Ultimate Altar: right-click to open the skill screen. Crystal Blocks within 5 blocks
 * raise its power; dropping (or using) an Ender Matrix on a normal altar turns it into an Ultimate Altar.
 */
public class AltarBlock extends Block {
	public static final MapCodec<AltarBlock> CODEC = simpleCodec(p -> new AltarBlock(false, p));
	private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);
	private final boolean ultimate;

	public AltarBlock(boolean ultimate, Properties properties) {
		super(properties);
		this.ultimate = ultimate;
	}

	public boolean isUltimate() {
		return ultimate;
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) {
			Progression.openAltar(serverPlayer, pos, ultimate);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		if (!ultimate && stack.is(ModContent.CRYSTAL_MATRIX)) {
			if (level instanceof ServerLevel serverLevel) {
				stack.consume(1, player);
				upgrade(serverLevel, pos);
			}
			return InteractionResult.SUCCESS;
		}
		return super.useItemOn(stack, state, level, pos, player, hand, hit);
	}

	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (!ultimate && level instanceof ServerLevel serverLevel && entity instanceof ItemEntity item
				&& item.getItem().is(ModContent.CRYSTAL_MATRIX)) {
			item.getItem().shrink(1);
			if (item.getItem().isEmpty()) {
				item.discard();
			}
			upgrade(serverLevel, pos);
		}
		super.stepOn(level, pos, state, entity);
	}

	private static void upgrade(ServerLevel level, BlockPos pos) {
		level.setBlockAndUpdate(pos, ModContent.ULTIMATE_ALTAR.defaultBlockState());
		level.sendParticles(ParticleTypes.PORTAL, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 60, 0.4, 0.6, 0.4, 0.5);
		level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
	}

	/** Crystal Blocks in the 11x11x11 cube around the altar. */
	public static int crystals(Level level, BlockPos pos) {
		int count = 0;
		for (BlockPos p : BlockPos.betweenClosed(pos.offset(-5, -5, -5), pos.offset(5, 5, 5))) {
			if (level.getBlockState(p).is(ModContent.CRYSTAL)) {
				count++;
			}
		}
		return count;
	}
}
