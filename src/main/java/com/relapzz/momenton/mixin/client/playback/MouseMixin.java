package com.relapzz.momenton.mixin.client.playback;

import com.relapzz.momenton.playback.ReplaySession;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps mouse-look off the replayed player.
 *
 * <p>{@code PovDriver} overrides the camera, so turning the mouse changes nothing visible - but it
 * still rotates the player entity, which drives the held item and the arm. Left alone, the replayed
 * body would face wherever the viewer's mouse last pointed.
 */
@Mixin(Mouse.class)
public class MouseMixin {
	@Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
	private void momenton$noLookDuringPlayback(double timeDelta, CallbackInfo info) {
		if (ReplaySession.isPlaying()) {
			info.cancel();
		}
	}
}
