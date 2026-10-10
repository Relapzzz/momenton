package com.relapzz.momenton.export;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public enum EncodingPreset {
	MP4_NVENC("-an -c:v h264_nvenc -preset p5 -tune hq -rc vbr -cq 19 -b:v 0 -pix_fmt yuv420p", "mp4", true),
	MP4_AMF("-an -c:v h264_amf -usage transcoding -quality quality -rc cqp -qp_i 18 -qp_p 20 -pix_fmt nv12", "mp4", true),
	MP4_QSV("-an -c:v h264_qsv -preset medium -global_quality 17 -look_ahead 0 -pix_fmt nv12", "mp4", true),
	MP4_DEFAULT("-an -c:v libx264 -preset superfast -crf 18 -pix_fmt yuv420p", "mp4", false),
	MP4_CUSTOM("-an -c:v libx264 -b:v %BITRATE% -pix_fmt yuv420p", "mp4", false),
	MP4_POTATO("-an -c:v libx264 -preset ultrafast -crf 51 -pix_fmt yuv420p", "mp4", false),
	WEBM_CUSTOM("-an -c:v libvpx -b:v %BITRATE% -pix_fmt yuv420p", "webm", false),
	MKV_LOSSLESS("-an -c:v libx264 -preset ultrafast -qp 0", "mkv", false);

	private final String codec;
	private final String extension;
	private final boolean hardware;

	EncodingPreset(String codec, String extension, boolean hardware) {
		this.codec = codec;
		this.extension = extension;
		this.hardware = hardware;
	}

	public static List<EncodingPreset> available(Set<EncodingPreset> workingHardware) {
		return Arrays.stream(values()).filter(preset -> !preset.hardware || workingHardware.contains(preset)).toList();
	}

	public String arguments() {
		String input = this == MKV_LOSSLESS ? FrameFormat.RGBA.input() : FrameFormat.PLACEHOLDER;
		return "-y " + input + " " + codec + " \"%FILENAME%\"";
	}

	public String codec() {
		return codec;
	}

	public String extension() {
		return extension;
	}

	public boolean hardware() {
		return hardware;
	}

	public boolean hasBitrate() {
		return codec.contains("%BITRATE%");
	}
}
