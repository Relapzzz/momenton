package com.relapzz.momenton.mixin.client;

import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Chat scroll is private client state that never crosses the wire, so it has to be recorded. */
@Mixin(ChatHud.class)
public interface ChatHudAccessor {
	@Accessor("scrolledLines")
	int getScrolledLines();

	@Accessor("scrolledLines")
	void setScrolledLines(int lines);
}
