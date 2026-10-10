package com.relapzz.momenton.export;

enum FrameFormat {
	RGBA("-f rawvideo -pix_fmt rgba -color_primaries bt709 -color_trc bt709 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf scale=out_color_matrix=bt709:out_range=tv", true),
	NV12("-f rawvideo -pix_fmt nv12 -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv -s %WIDTH%x%HEIGHT% -r %FPS% -i -", false),
	RGBA_TOP_DOWN("-f rawvideo -pix_fmt rgba -color_primaries bt709 -color_trc bt709 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf scale=out_color_matrix=bt709:out_range=tv", false),
	BGRA_TOP_DOWN("-f rawvideo -pix_fmt bgra -color_primaries bt709 -color_trc bt709 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf scale=out_color_matrix=bt709:out_range=tv", false);

	static final String PLACEHOLDER = "%INPUT%";

	private final String input;
	private final boolean bottomUp;

	FrameFormat(String input, boolean bottomUp) {
		this.input = input;
		this.bottomUp = bottomUp;
	}

	String input() {
		return input;
	}

	boolean bottomUp() {
		return bottomUp;
	}

	String expand(String args) {
		String spelled = this == BGRA_TOP_DOWN ? args.replace(RGBA.input, input) : args;
		return spelled.replace(PLACEHOLDER, input);
	}

	int stride(int width) {
		return this == NV12 ? width : width * 4;
	}

	int rows(int height) {
		return this == NV12 ? height * 3 / 2 : height;
	}

	int bytes(int width, int height) {
		return stride(width) * rows(height);
	}
}
