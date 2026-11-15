package dev.enderskills.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import dev.enderskills.logic.Progression;

/** Ender Book (as in the original mod): opens your skills anywhere; unlocking and upgrading still need an altar. */
public class BookItem extends Item {
	public BookItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer serverPlayer) {
			Progression.openBook(serverPlayer);
		}
		return InteractionResult.SUCCESS;
	}
}
