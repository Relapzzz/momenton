package com.relapzz.momenton.mixin.client.export;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface ExportGameRendererAccessor {
	@Accessor("guiState")
	GuiRenderState momenton$guiState();

	@Accessor("guiRenderer")
	GuiRenderer momenton$guiRenderer();

	@Accessor("fogRenderer")
	FogRenderer momenton$fogRenderer();
}
