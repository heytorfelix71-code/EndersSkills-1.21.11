package dev.enderskills.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.enderskills.EnderSkills;
import dev.enderskills.logic.Progression;

/** Network messages: the server opens the altar screen, the screen sends the player's choices back. */
public final class EsNetwork {
	private EsNetwork() {
	}

	/** Server → client: open the altar screen. */
	public record OpenAltar(BlockPos pos, float power, boolean ultimate, int crystals) implements CustomPacketPayload {
		public static final Type<OpenAltar> TYPE = new Type<>(EnderSkills.id("open_altar"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenAltar> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, OpenAltar::pos,
				ByteBufCodecs.FLOAT, OpenAltar::power,
				ByteBufCodecs.BOOL, OpenAltar::ultimate,
				ByteBufCodecs.VAR_INT, OpenAltar::crystals,
				OpenAltar::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: something done in the altar screen. */
	public record AltarAction(int action, String skill, int amount) implements CustomPacketPayload {
		public static final int UPGRADE = 0;
		public static final int STORE_XP = 1;
		public static final int TAKE_XP = 2;
		public static final int RESET = 3;
		public static final Type<AltarAction> TYPE = new Type<>(EnderSkills.id("altar_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, AltarAction> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, AltarAction::action,
				ByteBufCodecs.STRING_UTF8, AltarAction::skill,
				ByteBufCodecs.VAR_INT, AltarAction::amount,
				AltarAction::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.playS2C().register(OpenAltar.TYPE, OpenAltar.CODEC);
		PayloadTypeRegistry.playC2S().register(AltarAction.TYPE, AltarAction.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(AltarAction.TYPE, (payload, context) ->
				Progression.handle(context.player(), payload));
	}
}
