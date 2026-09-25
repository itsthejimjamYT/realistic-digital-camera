#version 330

// The high-precision capture pass (see HdrCapture.java). Deliberately minimal: just the
// two camera-accurate steps (exposure, white balance) plus a generous highlight roll-off
// so extreme values don't need hard clipping — none of blit.fsh's destructive camera-
// response crush, film recipe grade, tight highlight shoulder, or grain. Output isn't
// clamped to [0,1] and this pass's target is RGBA16_FLOAT, so headroom this preserves
// actually survives to the saved file instead of being thrown away.
//
// Its input is the DoF-gathered frame from HdrCapture's own private prefilter + gather
// passes (not the live chain's "swap" output — reading that repeatedly crashed the game;
// see HdrCapture's class doc), so the RAW file keeps the photo's blur.

uniform sampler2D InSampler;

layout(std140) uniform ExposeConfig {
    float ExposureMult;
    float WhiteBalance;
    float _pad0;
    float _pad1;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 rgb = texture(InSampler, texCoord).rgb;

    rgb *= ExposureMult;
    rgb *= vec3(1.0 + WhiteBalance, 1.0, 1.0 - WhiteBalance);

    // Soft highlight roll-off: identity up to the knee, then asymptotic — real extra
    // headroom above "nominal white" instead of a flat clip, without letting a value run
    // away unbounded (which would make the 16-bit encode's highlight range meaningless).
    const float knee = 1.0;
    vec3 over = max(rgb - knee, 0.0);
    rgb = min(rgb, vec3(knee)) + over / (1.0 + over / 2.5);

    fragColor = vec4(rgb, 1.0);
}
