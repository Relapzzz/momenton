package com.relapzz.momenton.audio;

import com.relapzz.momenton.Momenton;
import com.relapzz.momenton.mixin.client.audio.SoundManagerAccessor;
import com.relapzz.momenton.mixin.client.audio.SoundSystemAccessor;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.openal.SOFTOutputLimiter;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class LoopbackRenderer implements AutoCloseable {
	public static final int SAMPLE_RATE = 48000;
	private static final int FRAME_BYTES = 4;

	private static boolean active;
	private static long device;

	private final MinecraftClient client;
	private final Path path;
	private final FileChannel pcm;
	private final int fps;
	private final ByteBuffer buffer;
	private long frames;
	private long samples;

	private LoopbackRenderer(MinecraftClient client, Path path, FileChannel pcm, int fps) {
		this.client = client;
		this.path = path;
		this.pcm = pcm;
		this.fps = fps;
		this.buffer = ByteBuffer.allocateDirect((SAMPLE_RATE / fps + 1) * FRAME_BYTES);
	}

	public static LoopbackRenderer start(MinecraftClient client, Path path, int fps) throws IOException {
		FileChannel pcm = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		active = true;
		client.getSoundManager().reloadSounds();
		return new LoopbackRenderer(client, path, pcm, fps);
	}

	public static boolean isActive() {
		return active;
	}

	public static long openDevice() {
		device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
		return device;
	}

	public static IntBuffer attributes(MemoryStack stack, boolean hrtf) {
		return stack.ints(
				ALC10.ALC_FREQUENCY, SAMPLE_RATE,
				SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
				SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_SHORT_SOFT,
				SOFTHRTF.ALC_HRTF_SOFT, hrtf ? ALC10.ALC_TRUE : ALC10.ALC_FALSE,
				SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT, ALC10.ALC_TRUE,
				0);
	}

	public Path path() {
		return path;
	}

	public void stopOneShots() {
		soundSystem().getSources().keySet().stream()
				.filter(sound -> !sound.isRepeatable())
				.toList()
				.forEach(client.getSoundManager()::stop);
	}

	public void render() throws IOException {
		soundSystem().getTaskQueue().submitAndJoin(() -> {});
		frames++;
		long total = Math.round((double) frames * SAMPLE_RATE / fps);
		int count = (int) (total - samples);
		samples = total;
		buffer.clear().limit(count * FRAME_BYTES);
		SOFTLoopback.alcRenderSamplesSOFT(device, buffer, count);
		pcm.write(buffer);
	}

	@Override
	public void close() {
		active = false;
		device = 0;
		try {
			pcm.close();
		} catch (IOException e) {
			Momenton.LOGGER.warn("Could not close {}", path, e);
		}
		client.getSoundManager().reloadSounds();
	}

	private SoundSystemAccessor soundSystem() {
		return (SoundSystemAccessor) ((SoundManagerAccessor) client.getSoundManager()).getSoundSystem();
	}
}
