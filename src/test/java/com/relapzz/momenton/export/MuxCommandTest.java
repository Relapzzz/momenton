package com.relapzz.momenton.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MuxCommandTest {
	private static final Path VIDEO = Path.of("video.mp4");
	private static final Path GAME = Path.of("game.pcm");
	private static final Path MIC = Path.of("mic.pcm");
	private static final List<String> START = List.of("ffmpeg", "-nostdin", "-y", "-i", "video.mp4");
	private static final List<String> GAME_INPUT = List.of("-f", "s16le", "-ar", "48000", "-ac", "2", "-i", "game.pcm");
	private static final List<String> MIC_INPUT = List.of("-f", "s16le", "-ar", "48000", "-ac", "1", "-i", "mic.pcm");

	@Test
	void gameOnlyKeepsOneTitledTrack() {
		assertEquals(concat(START, GAME_INPUT, List.of("-filter_complex", "[1:a]volume=1.0[a0]", "-map", "0:v", "-map", "[a0]",
						"-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-metadata:s:a:0", "title=Game", "-metadata:s:a:0", "handler_name=Game",
						"-disposition:a:0", "default", "-shortest", "out.mp4")),
				command(new FfmpegProcess.MuxAudio(GAME, 1.0f, null, 0, 0, false), "out.mp4"));
	}

	@Test
	void commentaryIsASecondTrackWithItsOwnVolume() {
		assertEquals(concat(START, GAME_INPUT, MIC_INPUT, List.of("-filter_complex", "[1:a]volume=1.5[a0];[2:a]volume=0.25[a1]",
						"-map", "0:v", "-map", "[a0]", "-map", "[a1]", "-c:v", "copy", "-c:a", "libopus", "-b:a", "192k",
						"-metadata:s:a:0", "title=Game", "-metadata:s:a:0", "handler_name=Game", "-disposition:a:0", "default",
						"-metadata:s:a:1", "title=Commentary", "-metadata:s:a:1", "handler_name=Commentary", "-disposition:a:1", "0",
						"-shortest", "out.webm")),
				command(new FfmpegProcess.MuxAudio(GAME, 1.5f, MIC, 48000, 0.25f, false), "out.webm"));
	}

	@Test
	void mixAddsTheCentredVoiceWithoutNormalising() {
		assertEquals(concat(START, GAME_INPUT, MIC_INPUT, List.of("-filter_complex",
						"[1:a]volume=0.5[a0];[2:a]volume=2.0[a1];[a1]pan=stereo|c0=c0|c1=c0[voice];[a0][voice]amix=inputs=2:duration=first:normalize=0[mix]",
						"-map", "0:v", "-map", "[mix]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
						"-metadata:s:a:0", "title=Game and commentary", "-metadata:s:a:0", "handler_name=Game and commentary",
						"-disposition:a:0", "default", "-shortest", "out.mkv")),
				command(new FfmpegProcess.MuxAudio(GAME, 0.5f, MIC, 48000, 2.0f, true), "out.mkv"));
	}

	@Test
	void mutedGameLeavesCommentaryAsTheOnlyTrack() {
		for (boolean mix : new boolean[] {false, true}) {
			assertEquals(concat(START, List.of("-f", "s16le", "-ar", "44100", "-ac", "1", "-i", "mic.pcm",
							"-filter_complex", "[1:a]volume=1.0[a0]", "-map", "0:v", "-map", "[a0]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
							"-metadata:s:a:0", "title=Commentary", "-metadata:s:a:0", "handler_name=Commentary", "-disposition:a:0", "default",
							"-shortest", "out.mp4")),
					command(new FfmpegProcess.MuxAudio(GAME, 0, MIC, 44100, 1.0f, mix), "out.mp4"));
		}
	}

	@Test
	void mutedCommentaryIsOmitted() {
		assertEquals(command(new FfmpegProcess.MuxAudio(GAME, 1.0f, null, 0, 0, true), "out.mp4"),
				command(new FfmpegProcess.MuxAudio(GAME, 1.0f, MIC, 48000, 0, true), "out.mp4"));
	}

	@Test
	void everythingMutedCopiesTheVideoAlone() {
		assertEquals(concat(START, List.of("-map", "0:v", "-c:v", "copy", "-shortest", "out.mp4")),
				command(new FfmpegProcess.MuxAudio(GAME, 0, MIC, 48000, 0, false), "out.mp4"));
	}

	private static List<String> command(FfmpegProcess.MuxAudio audio, String output) {
		return FfmpegProcess.muxCommand("ffmpeg", VIDEO, audio, Path.of(output));
	}

	@SafeVarargs
	private static List<String> concat(List<String>... parts) {
		return Arrays.stream(parts).flatMap(List::stream).toList();
	}
}
