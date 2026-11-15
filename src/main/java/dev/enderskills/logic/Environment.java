package dev.enderskills.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.enderskills.data.EsSettings;

/** What abilities do to the world: black fire and blood that go away by themselves, and holes blown into the ground. */
public final class Environment {
	private Environment() {
	}

	/** Puts a temporary block (black fire, blood) on the ground at or just below a spot; gone after the given ticks. */
	public static void place(ServerLevel level, BlockPos at, Block block, int ticks) {
		if (!EsSettings.get().modifyEnvironment) {
			return;
		}
		for (int dy = 1; dy >= -3; dy--) {
			BlockPos pos = at.above(dy);
			BlockState here = level.getBlockState(pos);
			BlockPos below = pos.below();
			if ((here.isAir() || (here.canBeReplaced() && here.getFluidState().isEmpty() && !here.is(block)))
					&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
				level.setBlockAndUpdate(pos, block.defaultBlockState());
				level.scheduleTick(pos, block, Math.max(20, ticks) + level.getRandom().nextInt(20));
				return;
			}
		}
	}

	public static void place(ServerLevel level, BlockPos at, Block block) {
		place(level, at, block, 20 * Math.max(1, EsSettings.get().environmentSeconds));
	}

	/** A few temporary blocks scattered in a circle. */
	public static void spread(ServerLevel level, Vec3 center, double radius, Block block, int count, int ticks) {
		if (!EsSettings.get().modifyEnvironment) {
			return;
		}
		for (int i = 0; i < count; i++) {
			double a = level.getRandom().nextDouble() * Math.PI * 2;
			double d = Math.sqrt(level.getRandom().nextDouble()) * radius;
			place(level, BlockPos.containing(center.x + Math.cos(a) * d, center.y + 0.2, center.z + Math.sin(a) * d), block, ticks);
		}
	}

	/** Covers every column of a circle (on the ground at each one, so it follows hills and steps). */
	public static void fill(ServerLevel level, Vec3 center, double radius, Block block, int ticks) {
		if (!EsSettings.get().modifyEnvironment) {
			return;
		}
		int r = (int) Math.ceil(Math.min(12, radius));
		for (int x = -r; x <= r; x++) {
			for (int z = -r; z <= r; z++) {
				if (x * x + z * z <= radius * radius) {
					place(level, BlockPos.containing(center.x + x, center.y + 0.2, center.z + z), block, ticks);
				}
			}
		}
	}

	/** Breaks the blocks in a ball (Black Hole, Explode, Fireball) when "destroy the environment" is on. Hard blocks stay. */
	public static void blast(ServerLevel level, Vec3 center, double radius) {
		if (!EsSettings.get().destroyEnvironment) {
			return;
		}
		double r = Math.max(1.0, Math.min(10.0, radius));
		int ri = (int) Math.ceil(r);
		BlockPos c = BlockPos.containing(center);
		for (int x = -ri; x <= ri; x++) {
			for (int y = -ri; y <= ri; y++) {
				for (int z = -ri; z <= ri; z++) {
					double d = Math.sqrt(x * x + y * y + z * z);
					if (d > r - level.getRandom().nextDouble() * 0.8) {
						continue;
					}
					BlockPos pos = c.offset(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || state.getDestroySpeed(level, pos) < 0 || state.getBlock().getExplosionResistance() > 600.0F
							|| !state.getFluidState().isEmpty()) {
						continue;
					}
					level.destroyBlock(pos, level.getRandom().nextInt(4) == 0);
				}
			}
		}
	}
}
