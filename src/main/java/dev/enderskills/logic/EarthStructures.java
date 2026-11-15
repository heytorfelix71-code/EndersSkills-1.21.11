package dev.enderskills.logic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.enderskills.data.EsSettings;

/**
 * Wall and Dome: temporary walls made of the ground's own blocks. Small blocks in the way (slabs, carpets, grass,
 * flowers...) are removed and put back when the wall goes away; broken wall blocks drop nothing.
 */
public final class EarthStructures {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private static final class Structure {
		final UUID owner;
		final String skill;
		final ServerLevel level;
		final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
		final Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
		int until;

		Structure(UUID owner, String skill, ServerLevel level, int until) {
			this.owner = owner;
			this.skill = skill;
			this.level = level;
			this.until = until;
		}
	}

	private static final List<Structure> STRUCTURES = new ArrayList<>();
	private static int now;

	private EarthStructures() {
	}

	public static boolean has(ServerPlayer p, String skill) {
		return find(p.getUUID(), skill) != null;
	}

	private static Structure find(UUID owner, String skill) {
		for (Structure s : STRUCTURES) {
			if (s.owner.equals(owner) && s.skill.equals(skill)) {
				return s;
			}
		}
		return null;
	}

	/** Toggle mode from the configured level on: the wall stays until the ability is used again. */
	public static boolean toggleMode(ServerPlayer p, String skill) {
		return Progression.data(p).level(skill) >= EsSettings.get().earthToggleLevel;
	}

	/** Builds the Wall (in front of the player) or the Dome (around the player). */
	public static boolean build(ServerPlayer p, String skill) {
		EsSettings cfg = EsSettings.get();
		ServerLevel level = (ServerLevel) p.level();
		Structure old = find(p.getUUID(), skill);
		if (old != null) {
			dissolve(old);
			STRUCTURES.remove(old);
		}
		int duration = toggleMode(p, skill) ? Integer.MAX_VALUE : now + (int) Math.max(20, AbilityManager.value(p, skill, "DURATION"));
		Structure s = new Structure(p.getUUID(), skill, level, duration);
		double force = AbilityManager.value(p, skill, "FORCE");
		if (skill.equals("wall")) {
			int width = cfg.wallWidth > 0 ? cfg.wallWidth : (int) AbilityManager.value(p, skill, "WIDTH");
			int height = cfg.wallHeight > 0 ? cfg.wallHeight : (int) AbilityManager.value(p, skill, "HEIGHT");
			double range = cfg.wallDistance > 0 ? cfg.wallDistance : Math.min(AbilityManager.value(p, skill, "RANGE"), 6.0);
			Vec3 look = p.getViewVector(1.0F);
			Vec3 fwd = new Vec3(look.x, 0, look.z);
			fwd = fwd.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : fwd.normalize();
			// snap to the main axes so the wall is straight, like a real wall
			Vec3 n = Math.abs(fwd.x) > Math.abs(fwd.z) ? new Vec3(Math.signum(fwd.x), 0, 0) : new Vec3(0, 0, Math.signum(fwd.z));
			Vec3 side = new Vec3(-n.z, 0, n.x);
			Vec3 center = p.position().add(n.scale(range));
			int half = Math.max(0, (width - 1) / 2);
			for (int w = -half; w <= half; w++) {
				for (int t = 0; t < Math.max(1, cfg.wallThickness); t++) {
					Vec3 at = center.add(side.scale(w)).add(n.scale(t));
					column(s, p, BlockPos.containing(at.x, p.getY(), at.z), height, force);
				}
			}
			for (int k = 1; k <= Math.max(0, cfg.wallWings); k++) { // side walls folded towards the player
				for (int sign = -1; sign <= 1; sign += 2) {
					Vec3 at = center.add(side.scale(sign * half)).subtract(n.scale(k));
					column(s, p, BlockPos.containing(at.x, p.getY(), at.z), height, force);
				}
			}
		} else {
			int r = cfg.domeRadius > 0 ? cfg.domeRadius : (int) Math.round(AbilityManager.value(p, skill, "WIDTH"));
			int height = cfg.domeHeight > 0 ? cfg.domeHeight : (int) AbilityManager.value(p, skill, "HEIGHT");
			BlockPos c = p.blockPosition();
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					double d = Math.sqrt(x * x + z * z);
					if (d > r + 0.5) {
						continue;
					}
					if (d >= r - 0.5) {
						column(s, p, c.offset(x, 0, z), height, force);
					} else if (cfg.domeRoof) {
						BlockPos roof = c.offset(x, height, z);
						set(s, roof, material(level, c.offset(x, 0, z)));
					}
				}
			}
		}
		if (s.placed.isEmpty()) {
			return false;
		}
		STRUCTURES.add(s);
		if (duration == Integer.MAX_VALUE) {
			AbilityManager.setActive(p, skill, Integer.MAX_VALUE);
		} else {
			AbilityManager.setActive(p, skill, duration - now);
		}
		return true;
	}

	/** A column of wall from the ground up, pushing enemies standing in it upwards. */
	private static void column(Structure s, ServerPlayer p, BlockPos foot, int height, double force) {
		ServerLevel level = s.level;
		BlockPos base = foot;
		// stand on the ground: go down through air / small blocks, up out of solid ground
		for (int i = 0; i < 4 && isSoft(level, base) && isSoft(level, base.below()); i++) {
			base = base.below();
		}
		for (int i = 0; i < 4 && !isSoft(level, base); i++) {
			base = base.above();
		}
		BlockState mat = material(level, base);
		for (int y = 0; y < height; y++) {
			set(s, base.above(y), mat);
		}
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(base).expandTowards(0, height, 0))) {
			if (!DefenseAbilities.isAlly(p, e)) {
				e.setDeltaMovement(e.getDeltaMovement().add(0, force * 0.15, 0));
				e.hurtMarked = true;
			}
		}
		Vec3 c = Vec3.atBottomCenterOf(base);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, mat), c.x, c.y + 0.2, c.z, Vfx.n(8), 0.4, 0.2, 0.4, 0.1);
	}

	/** Air, liquids and small blocks (slabs, carpets, grass, flowers, snow...) that the wall may take the place of. */
	private static boolean isSoft(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		return st.isAir() || st.canBeReplaced() || !st.isCollisionShapeFullBlock(level, pos);
	}

	/** The block to build with: the ground below, or the fallback block when the ground can't be used. */
	private static BlockState material(Level level, BlockPos foot) {
		EsSettings cfg = EsSettings.get();
		if (cfg.earthCopyGround) {
			for (int i = 1; i <= 4; i++) {
				BlockPos below = foot.below(i);
				BlockState st = level.getBlockState(below);
				if (st.isAir() || st.canBeReplaced()) {
					continue;
				}
				if (st.isCollisionShapeFullBlock(level, below) && !(st.getBlock() instanceof FallingBlock) && !st.hasBlockEntity()) {
					return st.getBlock().defaultBlockState();
				}
				break;
			}
		}
		Block fallback = BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(cfg.earthFallbackBlock));
		return fallback == null || fallback == Blocks.AIR ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : fallback.defaultBlockState();
	}

	private static void set(Structure s, BlockPos pos, BlockState mat) {
		ServerLevel level = s.level;
		BlockState st = level.getBlockState(pos);
		boolean soft = st.isAir() || st.canBeReplaced() || (EsSettings.get().earthReplaceSmall && !st.isCollisionShapeFullBlock(level, pos));
		if (!soft || st.hasBlockEntity() || owned(level, pos)) {
			return;
		}
		s.original.put(pos.immutable(), st);
		s.placed.put(pos.immutable(), mat);
		level.setBlock(pos, mat, FLAGS);
	}

	private static boolean owned(Level level, BlockPos pos) {
		for (Structure s : STRUCTURES) {
			if (s.level == level && s.placed.containsKey(pos)) {
				return true;
			}
		}
		return false;
	}

	/** Puts back what was there before (only where the wall block is still in place). */
	private static void dissolve(Structure s) {
		for (Map.Entry<BlockPos, BlockState> e : s.placed.entrySet()) {
			BlockPos pos = e.getKey();
			if (s.level.getBlockState(pos) == e.getValue()) {
				s.level.setBlock(pos, s.original.get(pos), FLAGS);
				if (s.level.getRandom().nextInt(3) == 0) {
					s.level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, e.getValue()),
							pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.05);
				}
			}
		}
	}

	/** Ability used again in toggle mode: the wall goes away. */
	public static void remove(ServerPlayer p, String skill) {
		Structure s = find(p.getUUID(), skill);
		if (s != null) {
			dissolve(s);
			STRUCTURES.remove(s);
		}
	}

	static void tick(int tick) {
		now = tick;
		for (Iterator<Structure> it = STRUCTURES.iterator(); it.hasNext();) {
			Structure s = it.next();
			if (s.until <= now) {
				dissolve(s);
				it.remove();
			}
		}
	}

	/** A wall block broken by a player: no drop, the original block comes back. False cancels the vanilla break. */
	public static boolean onBreak(Level level, BlockPos pos) {
		for (Structure s : STRUCTURES) {
			if (s.level == level && s.placed.containsKey(pos)) {
				BlockState original = s.original.remove(pos);
				s.placed.remove(pos);
				level.setBlock(pos, original == null ? Blocks.AIR.defaultBlockState() : original, Block.UPDATE_ALL);
				return false;
			}
		}
		return true;
	}

	/** Server stopping: put everything back so no wall stays in the world. */
	public static void restoreAll() {
		for (Structure s : STRUCTURES) {
			dissolve(s);
		}
		STRUCTURES.clear();
	}
}
