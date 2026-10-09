package com.relapzz.momenton.mixin.client.hud;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.relapzz.momenton.edit.HudToggles.Element;
import com.relapzz.momenton.ui.editor.EditSession;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.hud.bar.Bar;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
	@Inject(method = {"renderHotbar", "renderHeldItemTooltip"}, at = @At("HEAD"), cancellable = true)
	private void momenton$hotbar(CallbackInfo info) {
		momenton$hide(Element.HOTBAR, info);
	}

	@Inject(method = {"renderStatusBars", "renderMountHealth"}, at = @At("HEAD"), cancellable = true)
	private void momenton$statusBars(CallbackInfo info) {
		momenton$hide(Element.STATUS_BARS, info);
	}

	@WrapWithCondition(method = "renderMainHud", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/bar/Bar;renderBar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"))
	private boolean momenton$bar(Bar bar, DrawContext context, RenderTickCounter counter) {
		return !EditSession.hides(Element.EXPERIENCE);
	}

	@WrapWithCondition(method = "renderMainHud", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/bar/Bar;renderAddons(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"))
	private boolean momenton$barAddons(Bar bar, DrawContext context, RenderTickCounter counter) {
		return !EditSession.hides(Element.EXPERIENCE);
	}

	@WrapWithCondition(method = "renderMainHud", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/bar/Bar;drawExperienceLevel(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;I)V"))
	private boolean momenton$experienceLevel(DrawContext context, TextRenderer textRenderer, int level) {
		return !EditSession.hides(Element.EXPERIENCE);
	}

	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void momenton$crosshair(CallbackInfo info) {
		momenton$hide(Element.CROSSHAIR, info);
	}

	@Inject(method = "renderChat", at = @At("HEAD"), cancellable = true)
	private void momenton$chat(CallbackInfo info) {
		momenton$hide(Element.CHAT, info);
	}

	@Inject(method = "renderBossBarHud", at = @At("HEAD"), cancellable = true)
	private void momenton$bossBar(CallbackInfo info) {
		momenton$hide(Element.BOSS_BAR, info);
	}

	@Inject(method = "renderStatusEffectOverlay", at = @At("HEAD"), cancellable = true)
	private void momenton$statusEffects(CallbackInfo info) {
		momenton$hide(Element.STATUS_EFFECTS, info);
	}

	@Inject(method = "renderTitleAndSubtitle", at = @At("HEAD"), cancellable = true)
	private void momenton$title(CallbackInfo info) {
		momenton$hide(Element.TITLE, info);
	}

	@Inject(method = "renderOverlayMessage", at = @At("HEAD"), cancellable = true)
	private void momenton$actionBar(CallbackInfo info) {
		momenton$hide(Element.ACTION_BAR, info);
	}

	@Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
			at = @At("HEAD"), cancellable = true)
	private void momenton$scoreboard(CallbackInfo info) {
		momenton$hide(Element.SCOREBOARD, info);
	}

	private static void momenton$hide(Element element, CallbackInfo info) {
		if (EditSession.hides(element)) info.cancel();
	}
}
