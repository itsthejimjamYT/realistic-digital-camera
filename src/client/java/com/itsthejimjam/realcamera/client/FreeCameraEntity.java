package com.itsthejimjam.realcamera.client;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A detached, free-flying camera used during photo mode. It is never added to the world;
 * {@link PhotoModeSession} drives it by hand once per client tick. Subclassing
 * {@link AbstractClientPlayer} gives it a body the vanilla camera code already knows how
 * to follow, so the real player can be left untouched while it flies.
 */
public class FreeCameraEntity extends AbstractClientPlayer {
	private static final double DIAGONAL = Mth.sin((float) Math.toRadians(45.0));

	/** Reads the real keyboard so WASD / space / shift move the camera. */
	public ClientInput input;

	/** Fixed negative id so it never collides with real (positive) entity ids. */
	private static final int CAMERA_ENTITY_ID = -8266;

	public FreeCameraEntity(ClientLevel level) {
		super(level, new GameProfile(UUID.randomUUID(), "PhotoModeCamera"));
		this.setId(CAMERA_ENTITY_ID);
		this.input = new KeyboardInput(Minecraft.getInstance().options);
		// Collide with the world (the drone must not fly through terrain) but no gravity —
		// it hovers. The SWIMMING pose gives it a small ~0.6 box so it fits tight spots.
		this.noPhysics = false;
		this.setNoGravity(true);
		this.setInvisible(true);
		this.setSilent(true);
		this.getAbilities().flying = true;
		this.setPose(Pose.SWIMMING);
	}

	/** Place the camera where the player is looking from, as the starting shot. */
	public void copyFrom(Entity entity) {
		this.snapTo(entity.getX(), entity.getEyeY(), entity.getZ(), entity.getYRot(), entity.getXRot());
		this.setYRot(entity.getYRot());
		this.setXRot(entity.getXRot());
		syncOld();
	}

	/** Snap to an explicit position + rotation without an interpolation jump. */
	public void placeAt(double x, double y, double z, float yaw, float pitch) {
		this.snapTo(x, y, z, yaw, pitch);
		this.setYRot(yaw);
		this.setXRot(pitch);
		syncOld();
	}

	/** Called once per client tick while photo mode is active. Speeds are blocks/tick. */
	public void driveTick(double horizontalSpeed, double verticalSpeed) {
		this.input.tick();
		syncOld();

		float yaw = this.getYRot();
		Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
		Vec3 side = Vec3.directionFromRotation(0.0F, yaw + 90.0F);

		double vx = 0.0, vy = 0.0, vz = 0.0;
		double h = horizontalSpeed * (this.input.keyPresses.sprint() ? 2.0 : 1.0);
		boolean straight = false, strafing = false;

		if (this.input.keyPresses.forward())  { vx += forward.x * h; vz += forward.z * h; straight = true; }
		if (this.input.keyPresses.backward()) { vx -= forward.x * h; vz -= forward.z * h; straight = true; }
		if (this.input.keyPresses.right())    { vx += side.x * h;    vz += side.z * h;    strafing = true; }
		if (this.input.keyPresses.left())     { vx -= side.x * h;    vz -= side.z * h;    strafing = true; }
		if (straight && strafing) { vx *= DIAGONAL; vz *= DIAGONAL; }
		if (this.input.keyPresses.jump())  { vy += verticalSpeed; }
		if (this.input.keyPresses.shift()) { vy -= verticalSpeed; }

		// move() clips the motion against world block collision so the drone stops at
		// terrain instead of passing through it.
		this.move(MoverType.SELF, new Vec3(vx, vy, vz));
		this.setDeltaMovement(Vec3.ZERO);
	}

	private void syncOld() {
		this.xOld = this.xo = this.getX();
		this.yOld = this.yo = this.getY();
		this.zOld = this.zo = this.getZ();
		this.xRotO = this.getXRot();
		this.yRotO = this.getYRot();
	}

	@Override
	public float getViewXRot(float partialTick) {
		return this.getXRot();
	}

	@Override
	public float getViewYRot(float partialTick) {
		return this.getYRot();
	}

	@Override
	public boolean isInWater() {
		return false;
	}

	@Override
	public boolean onClimbable() {
		return false;
	}

	@Override
	protected void checkFallDamage(double heightDifference, boolean onGround, BlockState landedState, BlockPos landedPos) {
		// no-op: the camera never falls
	}
}
