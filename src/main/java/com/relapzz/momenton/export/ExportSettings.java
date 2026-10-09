package com.relapzz.momenton.export;

import java.nio.file.Path;

public record ExportSettings(Path replay, int startTick, int endTick, int width, int height, int fps, Path output, Path ffmpeg, boolean drawCursor) {
}
