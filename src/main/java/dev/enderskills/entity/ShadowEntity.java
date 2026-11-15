package dev.enderskills.entity;

import java.util.UUID;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.Level;

/**
 * A shadow of the player (Shadow, level 20 and up): drawn on the client as a black copy of its owner with its own
 * walking, swimming, looking and hitting; follows the owner and attacks hostile mobs around them. It swims and sinks like
 * the player (no floating on the surface), never dies of anything and is never saved: it only goes away when the
 * owner's energy runs out or the ability is switched off.
 */
public class ShadowEntity extends PathfinderMob {
	private static final EntityDataAccessor<Integer> OWNER = SynchedEntityData.defineId(ShadowEntity.class, EntityDataSerializers.INT);

	private UUID ownerId;
	private int attackCooldown;

	public ShadowEntity(EntityType<? extends ShadowEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder attributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.36)
				.add(Attributes.ATTACK_DAMAGE, 4.0)
				.add(Attributes.FOLLOW_RANGE, 40.0)
				.add(Attributes.STEP_HEIGHT, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(OWNER, -1);
	}

	@Override
	protected void registerGoals() {
		// no float goal: a shadow sinks and swims like the player instead of bobbing on the surface
		goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.5, true));
	}

	public void setOwner(ServerPlayer owner) {
		ownerId = owner.getUUID();
		entityData.set(OWNER, owner.getId());
	}

	public UUID owner() {
		return ownerId;
	}

	/** Network id of the owner (client side, to draw the owner's look). */
	public int ownerEntityId() {
		return entityData.get(OWNER);
	}

	public boolean attackReady() {
		return attackCooldown <= 0;
	}

	public void resetAttack() {
		attackCooldown = 20;
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		if (attackCooldown > 0) {
			attackCooldown--;
		}
		setPose(isSwimming() ? Pose.SWIMMING : Pose.STANDING);
		dev.enderskills.logic.ShadowMinions.think(this);
	}

	@Override
	public boolean doHurtTarget(ServerLevel level, Entity target) {
		boolean hit = super.doHurtTarget(level, target);
		if (hit && target instanceof LivingEntity living && ownerId != null && level.getPlayerByUUID(ownerId) instanceof ServerPlayer owner) {
			living.setLastHurtByPlayer(owner, 100); // the owner gets the experience and the drops
		}
		return hit;
	}

	// ---- nothing hurts it, nothing kills it, it never runs out of air

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
		return true;
	}

	@Override
	public void kill(ServerLevel level) {
	}

	@Override
	protected void onBelowWorld() {
	}

	@Override
	public boolean canBreatheUnderwater() {
		return true;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}
}
