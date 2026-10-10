package com.relapzz.momenton.mixin.client.playback;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.relapzz.momenton.playback.PovDriver;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = GameRenderer.class, priority = 1500)
public class FovMixin {
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private float momenton$recordedFov(float fov, @Local(argsOnly = true) float tickProgress,
			@Local(argsOnly = true) boolean changingFov) {
		return changingFov ? PovDriver.fov(tickProgress, fov) : fov;
	}
}
