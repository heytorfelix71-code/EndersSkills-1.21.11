package dev.enderskills.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import dev.enderskills.EnderSkills;

/** Portal windows: the client asks what is seen through a portal from where it stands; the server sends a small picture of it. */
public final class WindowPayloads {
	private WindowPayloads() {
	}

	/** "Show me portal #view, looked at in this direction." */
	public record Request(int view, float yaw, float pitch) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(EnderSkills.id("window_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Request::view, ByteBufCodecs.FLOAT, Request::yaw, ByteBufCodecs.FLOAT, Request::pitch, Request::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The picture: size x size pixels, RGB bytes, row by row from the top. */
	public record Image(int view, int size, byte[] rgb) implements CustomPacketPayload {
		public static final Type<Image> TYPE = new Type<>(EnderSkills.id("window_image"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Image> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Image::view, ByteBufCodecs.VAR_INT, Image::size, ByteBufCodecs.BYTE_ARRAY, Image::rgb, Image::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
