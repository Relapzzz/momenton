package com.relapzz.momenton.export;

import com.relapzz.momenton.Momenton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class EncoderProbe {
	private static final CompletableFuture<Set<EncodingPreset>> WORKING = new CompletableFuture<>();

	private EncoderProbe() {
	}

	public static void start() {
		Thread.ofPlatform().name("Momenton encoder probe").daemon().start(() -> {
			String command = FfmpegProcess.defaultCommand();
			Set<EncodingPreset> working = EnumSet.noneOf(EncodingPreset.class);
			Arrays.stream(EncodingPreset.values()).filter(EncodingPreset::hardware).filter(preset -> works(command, preset)).forEach(working::add);
			Momenton.LOGGER.info("Hardware encoders that work with {}: {}", command, working);
			WORKING.complete(working);
		});
	}

	public static Set<EncodingPreset> working() {
		return WORKING.getNow(Set.of());
	}

	static boolean works(String command, EncodingPreset preset) {
		List<String> commandLine = new ArrayList<>(List.of(command, "-hide_banner", "-nostdin", "-f", "lavfi", "-i", "color=s=1280x720:r=60", "-frames:v", "1"));
		commandLine.addAll(FfmpegProcess.split(FfmpegProcess.substitute(preset.codec(), 1280, 720, 60, 10_000_000, "")));
		commandLine.addAll(List.of("-f", "null", "-"));
		try {
			Process process = new ProcessBuilder(commandLine).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			if (!process.waitFor(15, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
