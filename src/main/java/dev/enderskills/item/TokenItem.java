package dev.enderskills.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import dev.enderskills.logic.Progression;

/** Ender Token: right-click to raise your Ender Level (sneak to use the whole stack). */
public class TokenItem extends Item {
	public TokenItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player instanceof ServerPlayer serverPlayer) {
			int count = player.isShiftKeyDown() ? stack.getCount() : 1;
			Progression.useTokens(serverPlayer, count);
			if (!player.getAbilities().instabuild) {
				stack.shrink(count);
			}
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP,
					SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 1.0F, (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.35F + 0.9F);
		}
		return InteractionResult.SUCCESS;
	}
}
