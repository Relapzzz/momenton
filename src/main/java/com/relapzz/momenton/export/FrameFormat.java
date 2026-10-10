package com.relapzz.momenton.export;

enum FrameFormat {
	RGBA("-f rawvideo -pix_fmt rgba -color_primaries bt709 -color_trc bt709 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf scale=out_color_matrix=bt709:out_range=tv"),
	NV12("-f rawvideo -pix_fmt nv12 -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv -s %WIDTH%x%HEIGHT% -r %FPS% -i -");

	static final String PLACEHOLDER = "%INPUT%";

	private final String input;

	FrameFormat(String input) {
		this.input = input;
	}

	String input() {
		return input;
	}

	String expand(String args) {
		return args.replace(PLACEHOLDER, input);
	}

	int stride(int width) {
		return this == RGBA ? width * 4 : width;
	}

	int rows(int height) {
		return this == RGBA ? height : height * 3 / 2;
	}

	int bytes(int width, int height) {
		return stride(width) * rows(height);
	}
}
