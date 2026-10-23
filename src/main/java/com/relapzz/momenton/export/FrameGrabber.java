package com.relapzz.momenton.export;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.relapzz.momenton.Momenton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.VertexFormats;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class FrameGrabber implements AutoCloseable {
	private static final int BUFFERS = 4;
	private static final RenderPipeline NV12 = RenderPipeline.builder()
			.withLocation(Momenton.id("pipeline/nv12"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Momenton.id("core/nv12"))
			.withSampler("InSampler")
			.withDepthWrite(false)
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
			.build();

	private final ArrayDeque<GpuBuffer> free = new ArrayDeque<>();
	private final List<Copy> copying = new ArrayList<>();
	private final ExportTimings timings;
	private final int width;
	private final int height;
	private final int bytes;
	private final GpuTexture nv12;
	private final GpuTextureView nv12View;
	private int pending;
	private boolean closed;

	FrameGrabber(int width, int height, FrameFormat format, ExportTimings timings) {
		this.timings = timings;
		this.width = width;
		this.height = height;
		this.bytes = format.bytes(width, height);
		GpuDevice device = RenderSystem.getDevice();
		if (format == FrameFormat.NV12) {
			nv12 = device.createTexture(() -> "Momenton NV12 frame", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
					TextureFormat.RGBA8, width / 4, format.rows(height), 1, 1);
			nv12View = device.createTextureView(nv12);
		} else {
			nv12 = null;
			nv12View = null;
		}
		int size = VulkanMod.LOADED ? FrameFormat.RGBA.bytes(width + 1, height + 1) : bytes;
		for (int i = 0; i < BUFFERS; i++) {
			free.add(device.createBuffer(() -> "Momenton frame grab", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_COPY_DST, size));
		}
	}

	static FrameFormat format(String args, int width, int height) {
		if (VulkanMod.LOADED) {
			boolean bgra = VulkanMod.bgra(MinecraftClient.getInstance().getFramebuffer().getColorAttachment());
			if (!args.contains(FrameFormat.PLACEHOLDER)) {
				return bgra ? FrameFormat.BGRA_TOP_DOWN : FrameFormat.RGBA_TOP_DOWN;
			}
			return bgra ? FrameFormat.NV12_FROM_BGRA : FrameFormat.NV12_FROM_RGBA;
		}
		if (!args.contains(FrameFormat.PLACEHOLDER) || width % 4 != 0 || height % 2 != 0) {
			return FrameFormat.RGBA;
		}
		try {
			if (RenderSystem.getDevice().precompilePipeline(NV12).isValid()) {
				return FrameFormat.NV12;
			}
			Momenton.LOGGER.warn("The NV12 conversion shader did not compile, exporting RGBA frames");
		} catch (RuntimeException e) {
			Momenton.LOGGER.warn("The NV12 conversion shader could not be built, exporting RGBA frames", e);
		}
		return FrameFormat.RGBA;
	}

	boolean grab(Framebuffer framebuffer, Function<ByteBuffer, CompletableFuture<?>> sink) {
		reclaim();
		GpuTexture source = framebuffer.getColorAttachment();
		if (VulkanMod.LOADED && ((source.getWidth(0) & ~1) != width || (source.getHeight(0) & ~1) != height)) {
			return false;
		}
		GpuBuffer buffer = free.poll();
		if (buffer == null) {
			return false;
		}
		pending++;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (nv12 != null) {
			long start = System.nanoTime();
			try (RenderPass pass = encoder.createRenderPass(() -> "Momenton NV12 conversion", nv12View, OptionalInt.empty())) {
				pass.setPipeline(NV12);
				pass.bindTexture("InSampler", framebuffer.getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
				pass.draw(0, 3);
			}
			timings.convert(System.nanoTime() - start);
			encoder.copyTextureToBuffer(nv12, buffer, 0, () -> deliver(buffer, sink), 0);
			return true;
		}
		if (VulkanMod.LOADED) {
			VulkanMod.flush();
			Object commands = VulkanMod.nextCommandBuffer();
			encoder.copyTextureToBuffer(source, buffer, 0, () -> deliver(buffer, sink), 0, 0, 0, width, height);
			VulkanMod.release(commands);
			return true;
		}
		encoder.copyTextureToBuffer(source, buffer, 0, () -> deliver(buffer, sink), 0, 0, 0, width, height);
		return true;
	}

	boolean idle() {
		reclaim();
		return pending == 0 && copying.isEmpty();
	}

	@Override
	public void close() {
		closed = true;
		copying.forEach(copy -> copy.done().exceptionally(error -> null).join());
		reclaim();
		free.forEach(GpuBuffer::close);
		free.clear();
		if (nv12 != null) {
			nv12View.close();
			nv12.close();
		}
	}

	private void deliver(GpuBuffer buffer, Function<ByteBuffer, CompletableFuture<?>> sink) {
		pending--;
		if (closed) {
			buffer.close();
			return;
		}
		long start = System.nanoTime();
		GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false);
		timings.map(System.nanoTime() - start);
		copying.add(new Copy(buffer, view, sink.apply(view.data().slice(0, bytes))));
	}

	private void reclaim() {
		copying.removeIf(copy -> {
			if (!copy.done().isDone()) {
				return false;
			}
			copy.view().close();
			free.add(copy.buffer());
			return true;
		});
	}

	private record Copy(GpuBuffer buffer, GpuBuffer.MappedView view, CompletableFuture<?> done) {
	}
}
