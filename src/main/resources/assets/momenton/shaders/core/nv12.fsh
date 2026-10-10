#version 330

uniform sampler2D InSampler;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float TAPS[8] = float[](-0.0140625, -0.0421875, 0.1203125, 0.4359375, 0.4359375, 0.1203125, -0.0421875, -0.0140625);

vec3 rgb(int x, int y) {
	return texelFetch(InSampler, ivec2(x, y), 0).rgb;
}

float luma(int x, int y) {
	return (16.0 + 219.0 * dot(LUMA, rgb(x, y))) / 255.0;
}

vec2 chroma(int x, int row, int height) {
	vec3 sum = vec3(0.0);
	for (int i = 0; i < 8; i++) {
		int y = height - 1 - clamp(2 * row - 3 + i, 0, height - 1);
		sum += TAPS[i] * (rgb(2 * x, y) + rgb(2 * x + 1, y)) * 0.5;
	}
	float l = dot(LUMA, sum);
	return (128.0 + 224.0 * vec2((sum.b - l) / 1.8556, (sum.r - l) / 1.5748)) / 255.0;
}

void main() {
	ivec2 size = textureSize(InSampler, 0);
	int x = int(gl_FragCoord.x) * 4;
	int row = int(gl_FragCoord.y);
	if (row < size.y) {
		int y = size.y - 1 - row;
		fragColor = vec4(luma(x, y), luma(x + 1, y), luma(x + 2, y), luma(x + 3, y));
	} else {
		fragColor = vec4(chroma(x / 2, row - size.y, size.y), chroma(x / 2 + 1, row - size.y, size.y));
	}
}
