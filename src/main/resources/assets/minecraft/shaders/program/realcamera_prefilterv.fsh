#version 330

uniform sampler2D DiffuseSampler;

uniform float MaxRadiusFrac;

in vec2 texCoord;

out vec4 fragColor;

// Vertical half of the separable Gaussian pre-blur. See prefilterh.
void main() {
    vec2 ts = vec2(textureSize(DiffuseSampler, 0));
    float rad = clamp(MaxRadiusFrac * ts.y * 0.6, 1.0, 48.0);
    float sigma = max(rad * 0.5, 0.5);
    float px = 1.0 / ts.y;

    vec3 sum = texture(DiffuseSampler, texCoord).rgb;
    float wsum = 1.0;
    const int N = 16;
    for (int i = 1; i <= N; i++) {
        float o = (float(i) / float(N)) * rad;
        float w = exp(-(o * o) / (2.0 * sigma * sigma));
        sum += texture(DiffuseSampler, texCoord + vec2(0.0, o * px)).rgb * w;
        sum += texture(DiffuseSampler, texCoord - vec2(0.0, o * px)).rgb * w;
        wsum += 2.0 * w;
    }
    fragColor = vec4(sum / wsum, 1.0);
}
