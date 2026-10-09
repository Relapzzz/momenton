package com.relapzz.momenton.mixin.client.playback;

import com.relapzz.momenton.format.FrameTrack;
import com.relapzz.momenton.playback.PovDriver;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the camera pose with the recorded one during playback.
 *
 * <p>At TAIL, so vanilla has finished its own positioning and this overwrites the result rather than
 * fighting it. Rotation first, then position: {@code setRotation} is what rebuilds the direction
 * vectors the renderer reads.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Shadow
	protected abstract void setPos(Vec3d pos);

	/**
	 * The player's look direction, before vanilla reads it. In a live client the mouse has already set
	 * this by now; in a replay the frame track is the mouse.
	 */
	@Inject(method = "update", at = @At("HEAD"))
	private void momenton$driveLook(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
			float tickProgress, CallbackInfo info) {
		PovDriver.driveLook(focusedEntity, tickProgress);
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void momenton$drivePov(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
			float tickProgress, CallbackInfo info) {
		FrameTrack.Pose pose = PovDriver.pose(tickProgress);
		if (pose != null) {
			setRotation(pose.yaw(), pose.pitch());
			setPos(new Vec3d(pose.x(), pose.y(), pose.z()));
		}
	}
}
