package com.relapzz.momenton.export;

public enum EncodingPreset {
	MP4_DEFAULT("-an -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p \"%FILENAME%\"", "mp4"),
	MP4_CUSTOM("-an -c:v libx264 -b:v %BITRATE% -pix_fmt yuv420p \"%FILENAME%\"", "mp4"),
	MP4_POTATO("-an -c:v libx264 -preset ultrafast -crf 51 -pix_fmt yuv420p \"%FILENAME%\"", "mp4"),
	WEBM_CUSTOM("-an -c:v libvpx -b:v %BITRATE% -pix_fmt yuv420p \"%FILENAME%\"", "webm"),
	MKV_LOSSLESS("-an -c:v libx264 -preset ultrafast -qp 0 \"%FILENAME%\"", "mkv");

	private static final String INPUT = "-y -f rawvideo -pix_fmt rgba -s %WIDTH%x%HEIGHT% -r %FPS% -i - %FILTERS%";

	private final String preset;
	private final String extension;

	EncodingPreset(String preset, String extension) {
		this.preset = preset;
		this.extension = extension;
	}

	public String arguments() {
		return INPUT + preset;
	}

	public String extension() {
		return extension;
	}

	public boolean hasBitrate() {
		return preset.contains("%BITRATE%");
	}
}
