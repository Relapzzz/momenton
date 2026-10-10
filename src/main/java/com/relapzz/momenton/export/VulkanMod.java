package com.relapzz.momenton.export;

import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Queue;

public final class VulkanMod {
	public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("vulkanmod");
	private static final int B8G8R8A8_UNORM = 44;

	private VulkanMod() {
	}

	static void flush() {
		try {
			Object renderer = (Object) Handles.RENDERER.invokeExact();
			Handles.FLUSH.invokeExact(renderer);
			Handles.BOUND_PIPELINE.invokeExact(renderer, 0L);
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean bgra(GpuTexture texture) {
		try {
			return (int) Handles.FORMAT.invokeExact(texture) == B8G8R8A8_UNORM;
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean terrainBuilt() {
		try {
			Object dispatcher = (Object) Handles.DISPATCHER.invokeExact();
			return !(boolean) Handles.GRAPH_NEEDS_UPDATE.invokeExact()
					&& (boolean) Handles.IDLE.invokeExact(dispatcher)
					&& ((Queue<?>) Handles.HIGH_PRIORITY.invokeExact(dispatcher)).isEmpty()
					&& ((Queue<?>) Handles.LOW_PRIORITY.invokeExact(dispatcher)).isEmpty();
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class Handles {
		static final MethodHandle RENDERER;
		static final MethodHandle FLUSH;
		static final MethodHandle BOUND_PIPELINE;
		static final MethodHandle FORMAT;
		static final MethodHandle GRAPH_NEEDS_UPDATE;
		static final MethodHandle DISPATCHER;
		static final MethodHandle IDLE;
		static final MethodHandle HIGH_PRIORITY;
		static final MethodHandle LOW_PRIORITY;

		static {
			try {
				MethodHandles.Lookup lookup = MethodHandles.lookup();
				Class<?> renderer = Class.forName("net.vulkanmod.vulkan.Renderer");
				Class<?> texture = Class.forName("net.vulkanmod.render.engine.VkGpuTexture");
				Class<?> image = Class.forName("net.vulkanmod.vulkan.texture.VulkanImage");
				Class<?> world = Class.forName("net.vulkanmod.render.chunk.WorldRenderer");
				Class<?> dispatcher = Class.forName("net.vulkanmod.render.chunk.build.task.TaskDispatcher");
				MethodHandle worldRenderer = lookup.findStatic(world, "getInstance", MethodType.methodType(world));
				RENDERER = lookup.findStatic(renderer, "getInstance", MethodType.methodType(renderer)).asType(MethodType.methodType(Object.class));
				FLUSH = lookup.findVirtual(renderer, "flushCmds", MethodType.methodType(void.class)).asType(MethodType.methodType(void.class, Object.class));
				BOUND_PIPELINE = MethodHandles.privateLookupIn(renderer, lookup).findSetter(renderer, "boundPipelineHandle", long.class)
						.asType(MethodType.methodType(void.class, Object.class, long.class));
				FORMAT = MethodHandles.filterReturnValue(lookup.findVirtual(texture, "getVulkanImage", MethodType.methodType(image)),
						lookup.findGetter(image, "format", int.class)).asType(MethodType.methodType(int.class, GpuTexture.class));
				GRAPH_NEEDS_UPDATE = MethodHandles.filterReturnValue(worldRenderer,
						lookup.findVirtual(world, "graphNeedsUpdate", MethodType.methodType(boolean.class)));
				DISPATCHER = MethodHandles.filterReturnValue(worldRenderer,
						lookup.findVirtual(world, "getTaskDispatcher", MethodType.methodType(dispatcher))).asType(MethodType.methodType(Object.class));
				IDLE = lookup.findVirtual(dispatcher, "isIdle", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class));
				MethodHandles.Lookup tasks = MethodHandles.privateLookupIn(dispatcher, lookup);
				HIGH_PRIORITY = tasks.findGetter(dispatcher, "highPriorityTasks", Queue.class).asType(MethodType.methodType(Queue.class, Object.class));
				LOW_PRIORITY = tasks.findGetter(dispatcher, "lowPriorityTasks", Queue.class).asType(MethodType.methodType(Queue.class, Object.class));
			} catch (ReflectiveOperationException e) {
				throw new ExceptionInInitializerError(e);
			}
		}
	}
}
