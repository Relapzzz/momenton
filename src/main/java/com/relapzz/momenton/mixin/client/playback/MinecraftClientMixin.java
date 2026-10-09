package com.relapzz.momenton.mixin.client.playback;

import com.relapzz.momenton.playback.ReplaySession;
import net.minecraft.client.MinecraftClient;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops local input from reaching a replayed world, and makes pausing a replay actually pause it.
 *
 * <p>Silencing {@code player.input} kills movement, but attack, use and block breaking come through
 * {@code handleInputEvents} instead - so without this, clicking during playback mines the replay's
 * blocks and swings at its mobs. The recording is a record of what happened; the viewer does not get
 * to change it.
 *
 * <p>Pausing is the same problem from the other side. {@code MinecraftClient.paused} is only ever set
 * for an integrated server, and a replay has none, so freezing the replay tick left the client ticking
 * the world underneath it - {@code ticksSinceLastAttack++} and {@code tickHandSwing} kept running, so
 * the crosshair cooldown filled and the arm finished its swing under a frozen camera. Setting vanilla's
 * own flag freezes everything a singleplayer pause freezes.
 *
 * <p>It has to be set at exactly this point and no earlier. Two lines below the assignment this
 * overwrites, {@code render} passes the flag to {@code RenderTickCounter.tick}, and that is what pins
 * tick progress - without it the world stops ticking while tick progress keeps sweeping 0 to 1, so
 * every interpolated value oscillates between its last and current tick once per tick. That is a
 * violent shake on every entity and on the held item, and it is worse than not pausing at all. Setting
 * the flag at the head of {@code tick} produces exactly that, because {@code render} recomputes it
 * afterwards.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Shadow
	private volatile boolean paused;

	@Inject(method = "render", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/MinecraftClient;paused:Z", opcode = Opcodes.PUTFIELD,
			shift = At.Shift.AFTER))
	private void momenton$pausePlayback(boolean tick, CallbackInfo info) {
		if (ReplaySession.isPlaying()) {
			this.paused = ReplaySession.isPaused();
		}
	}

	@Inject(method = "handleInputEvents", at = @At("HEAD"), cancellable = true)
	private void momenton$noInputDuringPlayback(CallbackInfo info) {
		if (ReplaySession.isPlaying()) {
			info.cancel();
		}
	}

	@Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void momenton$noBreakingDuringPlayback(boolean breaking, CallbackInfo info) {
		if (ReplaySession.isPlaying()) {
			info.cancel();
		}
	}
}
