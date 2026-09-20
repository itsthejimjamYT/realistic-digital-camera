#version 330

uniform sampler2D DiffuseSampler;

uniform float MaxRadiusFrac;   // how blurred the shot will be, as a fraction of frame height

in vec2 texCoord;

out vec4 fragColor;

// Horizontal half of a separable Gaussian pre-blur before the DoF gather. Separable so
// it stays genuinely smooth at any radius, unlike a single-pass disc scatter, which
// undersamples and breaks into patchy grey splotches once the pixel radius gets large
// (i.e. at capture resolution). The radius tracks the shot's blur amount so a wide /
// stopped-down shot barely pre-blurs.
void main() {
    vec2 ts = vec2(textureSize(DiffuseSampler, 0));
    float rad = clamp(MaxRadiusFrac * ts.y * 0.6, 1.0, 48.0);
    float sigma = max(rad * 0.5, 0.5);
    float px = 1.0 / ts.x;

    vec3 sum = texture(DiffuseSampler, texCoord).rgb;
    float wsum = 1.0;
    const int N = 16;
    for (int i = 1; i <= N; i++) {
        float o = (float(i) / float(N)) * rad;
        float w = exp(-(o * o) / (2.0 * sigma * sigma));
        sum += texture(DiffuseSampler, texCoord + vec2(o * px, 0.0)).rgb * w;
        sum += texture(DiffuseSampler, texCoord - vec2(o * px, 0.0)).rgb * w;
        wsum += 2.0 * w;
    }
    fragColor = vec4(sum / wsum, 1.0);
}
