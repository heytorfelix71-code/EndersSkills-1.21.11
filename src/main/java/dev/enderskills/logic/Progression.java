package dev.enderskills.logic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.enderskills.EnderSkills;
import dev.enderskills.block.AltarBlock;
import dev.enderskills.data.EsSettings;
import dev.enderskills.data.SkillData;
import dev.enderskills.network.EsNetwork;
import dev.enderskills.skill.SkillTree;
import dev.enderskills.skill.Skills;

/** Server side of the altar: unlocking and upgrading skills, the altar's XP storage, resets and Ender Tokens. */
public final class Progression {
	/** Saved with the player, kept on death and synced to that player's client. */
	public static final AttachmentType<SkillData> DATA = AttachmentRegistry.create(EnderSkills.id("skills"), b -> b
			.initializer(SkillData::create)
			.persistent(SkillData.CODEC)
			.copyOnDeath()
			.syncWith(SkillData.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

	private record Session(BlockPos pos, boolean ultimate, boolean book) {
	}

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	private Progression() {
	}

	public static void init() {
	}

	public static SkillData data(Player player) {
		return player.getAttachedOrCreate(DATA);
	}

	public static void set(ServerPlayer player, SkillData data) {
		player.setAttached(DATA, data);
		AttributeEffects.apply(player);
	}

	public static void openAltar(ServerPlayer player, BlockPos pos, boolean ultimate) {
		SESSIONS.put(player.getUUID(), new Session(pos.immutable(), ultimate, false));
		int crystals = AltarBlock.crystals(player.level(), pos);
		float power = (float) SkillTree.altarPower(crystals, ultimate);
		data(player); // make sure the data exists (and is synced) before the screen opens
		ServerPlayNetworking.send(player, new EsNetwork.OpenAltar(pos, power, ultimate, crystals));
	}

	/** Opens the skill screen from the Ender Book (power -1 = book mode). */
	public static void openBook(ServerPlayer player) {
		SESSIONS.put(player.getUUID(), new Session(player.blockPosition(), false, true));
		data(player);
		ServerPlayNetworking.send(player, new EsNetwork.OpenAltar(player.blockPosition(), -1.0F, false, 0));
	}

	/** Creative mode with the "free in creative" option. */
	public static boolean creativeFree(ServerPlayer player) {
		return EsSettings.get().creativeFree && player.isCreative();
	}

	public static void handle(ServerPlayer player, EsNetwork.AltarAction action) {
		Session session = SESSIONS.get(player.getUUID());
		if (session != null && session.book()) {
			// the Ender Book: read everything and use the XP storage anywhere; unlocking needs an altar
			if (action.action() == EsNetwork.AltarAction.STORE_XP) {
				storeXp(player, action.amount());
			} else if (action.action() == EsNetwork.AltarAction.TAKE_XP) {
				takeXp(player, action.amount());
			} else if (action.action() == EsNetwork.AltarAction.UPGRADE && creativeFree(player)) {
				upgradeMany(player, action.skill(), player.blockPosition(), true, action.amount());
			}
			return;
		}
		if (session == null || player.distanceToSqr(session.pos().getCenter()) > 64.0
				|| !(player.level().getBlockState(session.pos()).getBlock() instanceof AltarBlock altar)) {
			return;
		}
		switch (action.action()) {
			case EsNetwork.AltarAction.UPGRADE -> upgradeMany(player, action.skill(), session.pos(), altar.isUltimate(), action.amount());
			case EsNetwork.AltarAction.STORE_XP -> storeXp(player, action.amount());
			case EsNetwork.AltarAction.TAKE_XP -> takeXp(player, action.amount());
			case EsNetwork.AltarAction.RESET -> reset(player);
			default -> {
			}
		}
	}

	/** Ctrl + click: level up again and again until the XP (or the max level) runs out. */
	private static void upgradeMany(ServerPlayer player, String skillId, BlockPos pos, boolean ultimate, int amount) {
		int times = Math.max(1, Math.min(1000, amount));
		for (int i = 0; i < times; i++) {
			if (!upgrade(player, skillId, pos, ultimate, i > 0)) {
				return;
			}
		}
	}

	private static boolean upgrade(ServerPlayer player, String skillId, BlockPos pos, boolean ultimate, boolean quiet) {
		Skills.Skill skill = Skills.get(skillId);
		if (skill == null) {
			return false;
		}
		SkillData data = data(player);
		double power = SkillTree.altarPower(AltarBlock.crystals(player.level(), pos), ultimate);
		boolean free = creativeFree(player);
		String blocked = SkillTree.blocked(data, skillId, power, ultimate);
		if (free && blocked != null && !blocked.equals("max")) {
			blocked = null;
		}
		if (blocked != null) {
			if (!quiet) {
				fail(player, "enderskills.altar.blocked." + blocked);
			}
			return false;
		}
		if (!free && data.enderLevel() < SkillTree.levelsNeeded(data, skillId)) {
			if (!quiet) {
				fail(player, "enderskills.altar.blocked.levels");
			}
			return false;
		}
		int cost = free ? 0 : SkillTree.xpCost(data, skillId);
		if (data.storedXp() + XpUtil.total(player) < cost) {
			if (!quiet) {
				fail(player, "enderskills.altar.blocked.xp");
			}
			return false;
		}
		int fromStore = Math.min(cost, data.storedXp());
		if (cost > fromStore) {
			XpUtil.take(player, cost - fromStore);
		}
		boolean owned = data.owns(skillId);
		SkillData next = data.withStoredXp(data.storedXp() - fromStore)
				.withSkill(skillId, owned ? data.level(skillId) + 1 : 0, skill.isAbility())
				.withSpent(data.xpSpent() + cost);
		set(player, next);
		if (quiet) {
			return true;
		}
		player.level().playSound(null, pos, owned ? SoundEvents.PLAYER_LEVELUP : SoundEvents.ENCHANTMENT_TABLE_USE,
				SoundSource.PLAYERS, dev.enderskills.logic.Vfx.vol() * 0.8F, 1.0F);
		return true;
	}

	private static void storeXp(ServerPlayer player, int amount) {
		int points = Math.min(amount < 0 ? Integer.MAX_VALUE : amount, XpUtil.total(player));
		if (points <= 0) {
			return;
		}
		XpUtil.take(player, points);
		SkillData data = data(player);
		set(player, data.withStoredXp(data.storedXp() + (int) (points * EsSettings.get().xpStoreTariff)));
	}

	private static void takeXp(ServerPlayer player, int amount) {
		SkillData data = data(player);
		int points = Math.min(amount < 0 ? Integer.MAX_VALUE : amount, data.storedXp());
		if (points <= 0) {
			return;
		}
		set(player, data.withStoredXp(data.storedXp() - points));
		player.giveExperiencePoints((int) (points * EsSettings.get().xpTakeTariff));
	}

	private static void reset(ServerPlayer player) {
		SkillData data = data(player);
		if (data.skills().isEmpty()) {
			fail(player, "enderskills.altar.reset.nothing");
			return;
		}
		if (data.resets() >= EsSettings.get().maxResetUnlocks) {
			fail(player, "enderskills.altar.reset.limit");
			return;
		}
		int refund = (int) (data.xpSpent() * EsSettings.get().retryXpReturn);
		set(player, data.reset(refund));
		player.displayClientMessage(Component.translatable("enderskills.altar.reset.done", refund), false);
	}

	/** Uses Ender Tokens: each one adds progress towards the next Ender Level. */
	public static void useTokens(ServerPlayer player, int count) {
		SkillData data = data(player);
		int level = data.enderLevel();
		double progress = data.progress();
		for (int i = 0; i < count; i++) {
			progress += SkillTree.tokenProgress(level);
			while (progress >= 1.0) {
				progress -= 1.0;
				level++;
			}
		}
		if (level > data.enderLevel()) {
			player.displayClientMessage(Component.translatable("enderskills.level_up", level).withStyle(ChatFormatting.LIGHT_PURPLE), true);
		} else {
			player.displayClientMessage(Component.translatable("enderskills.level_progress", level,
					Math.round(progress * 100)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
		}
		set(player, data.withLevel(level, progress));
	}

	private static void fail(ServerPlayer player, String key) {
		player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
	}

	public static void forget(ServerPlayer player) {
		SESSIONS.remove(player.getUUID());
	}
}
