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

	/** Client → server: use an ability (dx/dz = horizontal direction for Dash and Warp). */
	public record UseAbility(String skill, float dx, float dz) implements CustomPacketPayload {
		public static final Type<UseAbility> TYPE = new Type<>(EnderSkills.id("use_ability"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UseAbility> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, UseAbility::skill,
				ByteBufCodecs.FLOAT, UseAbility::dx,
				ByteBufCodecs.FLOAT, UseAbility::dz,
				UseAbility::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: use Portal or Teleport towards typed coordinates. */
	public record UseAbilityAt(String skill, double x, double y, double z) implements CustomPacketPayload {
		public static final Type<UseAbilityAt> TYPE = new Type<>(EnderSkills.id("use_ability_at"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UseAbilityAt> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, UseAbilityAt::skill,
				ByteBufCodecs.DOUBLE, UseAbilityAt::x,
				ByteBufCodecs.DOUBLE, UseAbilityAt::y,
				ByteBufCodecs.DOUBLE, UseAbilityAt::z,
				UseAbilityAt::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: the portal menu (list, close a pair, move a portal, new pair); dim = dimension id ("" = the current one). */
	public record PortalAction(int action, int id, int which, double x, double y, double z, String dim) implements CustomPacketPayload {
		public static final int LIST = 0;
		public static final int CLOSE = 1;
		public static final int MOVE = 2;
		public static final int WAIT_AT = 3;
		public static final int CANCEL_WAIT = 4;
		public static final Type<PortalAction> TYPE = new Type<>(EnderSkills.id("portal_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PortalAction> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, PortalAction::action,
				ByteBufCodecs.VAR_INT, PortalAction::id,
				ByteBufCodecs.VAR_INT, PortalAction::which,
				ByteBufCodecs.DOUBLE, PortalAction::x,
				ByteBufCodecs.DOUBLE, PortalAction::y,
				ByteBufCodecs.DOUBLE, PortalAction::z,
				ByteBufCodecs.STRING_UTF8, PortalAction::dim,
				PortalAction::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server → client: the player's portal pairs {id, ax, ay, az, bx, by, bz, seconds left or -1}, the waiting portal
	 * {x, y, z}, the dimensions of the server, and the two dimensions of every pair.
	 */
	public record PortalList(java.util.List<double[]> pairs, double[] waiting, java.util.List<String> dims,
			java.util.List<String[]> pairDims) implements CustomPacketPayload {
		public static final Type<PortalList> TYPE = new Type<>(EnderSkills.id("portal_list"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PortalList> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.pairs().size());
			for (double[] d : v.pairs()) {
				writeDoubles(buf, d);
			}
			writeDoubles(buf, v.waiting());
			buf.writeVarInt(v.dims().size());
			for (String d : v.dims()) {
				buf.writeUtf(d);
			}
			buf.writeVarInt(v.pairDims().size());
			for (String[] d : v.pairDims()) {
				buf.writeUtf(d[0]);
				buf.writeUtf(d[1]);
			}
		}, buf -> {
			int n = Math.min(256, buf.readVarInt());
			java.util.List<double[]> list = new java.util.ArrayList<>();
			for (int i = 0; i < n; i++) {
				list.add(readDoubles(buf));
			}
			double[] waiting = readDoubles(buf);
			int nd = Math.min(64, buf.readVarInt());
			java.util.List<String> dims = new java.util.ArrayList<>();
			for (int i = 0; i < nd; i++) {
				dims.add(buf.readUtf());
			}
			int np = Math.min(256, buf.readVarInt());
			java.util.List<String[]> pairDims = new java.util.ArrayList<>();
			for (int i = 0; i < np; i++) {
				pairDims.add(new String[] {buf.readUtf(), buf.readUtf()});
			}
			return new PortalList(list, waiting, dims, pairDims);
		});

		private static void writeDoubles(RegistryFriendlyByteBuf buf, double[] d) {
			buf.writeVarInt(d.length);
			for (double x : d) {
				buf.writeDouble(x);
			}
		}

		private static double[] readDoubles(RegistryFriendlyByteBuf buf) {
			double[] d = new double[Math.min(16, buf.readVarInt())];
			for (int i = 0; i < d.length; i++) {
				d[i] = buf.readDouble();
			}
			return d;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: how many fighting shadows the player wants (sneak + Shadow key). */
	public record ShadowCount(int count) implements CustomPacketPayload {
		public static final Type<ShadowCount> TYPE = new Type<>(EnderSkills.id("shadow_count"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShadowCount> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ShadowCount::count, ShadowCount::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server → client: draw a 3D effect (see Shapes). */
	/** What an ability is doing, for the original-style rendering on the client (see logic.Fx). */
	public record SkillFx(String skill, int phase, int id, int entity, double x, double y, double z, double tx, double ty, double tz,
			float radius, int ticks) implements CustomPacketPayload {
		public static final Type<SkillFx> TYPE = new Type<>(EnderSkills.id("skill_fx"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SkillFx> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeUtf(v.skill());
			buf.writeByte(v.phase());
			buf.writeInt(v.id());
			buf.writeVarInt(v.entity() + 1);
			buf.writeDouble(v.x());
			buf.writeDouble(v.y());
			buf.writeDouble(v.z());
			buf.writeDouble(v.tx());
			buf.writeDouble(v.ty());
			buf.writeDouble(v.tz());
			buf.writeFloat(v.radius());
			buf.writeInt(v.ticks());
		}, buf -> new SkillFx(buf.readUtf(), buf.readByte(), buf.readInt(), buf.readVarInt() - 1, buf.readDouble(), buf.readDouble(),
				buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record VfxShape(int kind, double x, double y, double z, float radius, float height, float dx, float dy, float dz,
			int color, int ticks, int entity) implements CustomPacketPayload {
		public static final Type<VfxShape> TYPE = new Type<>(EnderSkills.id("vfx_shape"));
		public static final StreamCodec<RegistryFriendlyByteBuf, VfxShape> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.kind());
			buf.writeDouble(v.x());
			buf.writeDouble(v.y());
			buf.writeDouble(v.z());
			buf.writeFloat(v.radius());
			buf.writeFloat(v.height());
			buf.writeFloat(v.dx());
			buf.writeFloat(v.dy());
			buf.writeFloat(v.dz());
			buf.writeInt(v.color());
			buf.writeVarInt(v.ticks());
			buf.writeVarInt(v.entity() + 1);
		}, buf -> new VfxShape(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
				buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readInt(), buf.readVarInt(), buf.readVarInt() - 1));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server → client: endurance, and remaining ticks of cooldowns and active abilities. */
	public record AbilitySync(float endurance, float maxEndurance, java.util.Map<String, Integer> cooldowns,
			java.util.Map<String, Integer> active) implements CustomPacketPayload {
		public static final Type<AbilitySync> TYPE = new Type<>(EnderSkills.id("ability_sync"));
		private static final StreamCodec<io.netty.buffer.ByteBuf, java.util.Map<String, Integer>> MAP =
				ByteBufCodecs.map(java.util.HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT);
		public static final StreamCodec<RegistryFriendlyByteBuf, AbilitySync> CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT, AbilitySync::endurance,
				ByteBufCodecs.FLOAT, AbilitySync::maxEndurance,
				MAP, AbilitySync::cooldowns,
				MAP, AbilitySync::active,
				AbilitySync::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.playS2C().register(OpenAltar.TYPE, OpenAltar.CODEC);
		PayloadTypeRegistry.playS2C().register(AbilitySync.TYPE, AbilitySync.CODEC);
		PayloadTypeRegistry.playS2C().register(VfxShape.TYPE, VfxShape.CODEC);
		PayloadTypeRegistry.playS2C().register(SkillFx.TYPE, SkillFx.CODEC);
		PayloadTypeRegistry.playC2S().register(AltarAction.TYPE, AltarAction.CODEC);
		PayloadTypeRegistry.playC2S().register(UseAbility.TYPE, UseAbility.CODEC);
		PayloadTypeRegistry.playC2S().register(UseAbilityAt.TYPE, UseAbilityAt.CODEC);
		PayloadTypeRegistry.playC2S().register(PortalAction.TYPE, PortalAction.CODEC);
		PayloadTypeRegistry.playC2S().register(ShadowCount.TYPE, ShadowCount.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ShadowCount.TYPE, (payload, context) ->
				dev.enderskills.logic.ShadowMinions.choose(context.player(), payload.count()));
		PayloadTypeRegistry.playS2C().register(PortalList.TYPE, PortalList.CODEC);
		PayloadTypeRegistry.playC2S().register(WindowPayloads.Request.TYPE, WindowPayloads.Request.CODEC);
		PayloadTypeRegistry.playS2C().register(WindowPayloads.Image.TYPE, WindowPayloads.Image.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(WindowPayloads.Request.TYPE, (payload, context) ->
				dev.enderskills.logic.PortalWindows.request(context.player(), payload.view(), payload.yaw(), payload.pitch()));
		ServerPlayNetworking.registerGlobalReceiver(PortalAction.TYPE, (payload, context) ->
				dev.enderskills.logic.AbilityManager.portalMenu(context.player(), payload.action(), payload.id(), payload.which(),
						new net.minecraft.world.phys.Vec3(payload.x(), payload.y(), payload.z()), payload.dim()));
		ServerPlayNetworking.registerGlobalReceiver(UseAbilityAt.TYPE, (payload, context) ->
				dev.enderskills.logic.AbilityManager.useAt(context.player(), payload.skill(),
						new net.minecraft.world.phys.Vec3(payload.x(), payload.y(), payload.z())));
		ServerPlayNetworking.registerGlobalReceiver(UseAbility.TYPE, (payload, context) ->
				dev.enderskills.logic.AbilityManager.use(context.player(), payload.skill(), payload.dx(), payload.dz()));
		ServerPlayNetworking.registerGlobalReceiver(AltarAction.TYPE, (payload, context) ->
				Progression.handle(context.player(), payload));
	}
}
