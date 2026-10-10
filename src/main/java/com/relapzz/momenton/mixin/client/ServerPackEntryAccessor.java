package com.relapzz.momenton.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;
import java.util.UUID;

@Mixin(targets = "net.minecraft.client.resource.server.ServerResourcePackManager$PackEntry")
public interface ServerPackEntryAccessor {
	@Accessor("id")
	UUID momenton$getId();

	@Accessor("path")
	Path momenton$getPath();

	@Accessor("accepted")
	boolean momenton$isAccepted();

	@Invoker("isDiscarded")
	boolean momenton$isDiscarded();
}
