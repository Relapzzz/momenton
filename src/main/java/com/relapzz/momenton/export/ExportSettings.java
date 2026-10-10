package com.relapzz.momenton.export;

import com.relapzz.momenton.edit.Clip;

import java.nio.file.Path;
import java.util.List;

public record ExportSettings(Path replay, List<Clip> clips, int width, int height, int fps, Path output, String ffmpegCommand,
		String ffmpegArgs, int bitrate, boolean drawCursor) {
}
