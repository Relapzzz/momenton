package com.relapzz.momenton.mixin.client;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerListHud.class)
public interface ServerStatePlayerListHudAccessor {
	@Accessor("header")
	Text momenton$getHeader();

	@Accessor("footer")
	Text momenton$getFooter();
}
