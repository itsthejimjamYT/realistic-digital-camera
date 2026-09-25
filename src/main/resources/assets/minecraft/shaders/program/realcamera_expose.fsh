#version 330

// The high-precision RAW Mode / HDR-merge pass (see HdrCapture.java) — 1.21.1 port of 26.2's
// post/expose.fsh. Deliberately minimal: just the two camera-accurate steps (exposure, white
// balance) plus a generous highlight roll-off so extreme values don't need hard clipping —
// none of realcamera_blit.fsh's destructive camera-response crush, film recipe grade, tight
// highlight shoulder, or grain. Output isn't clamped to [0,1] and this pass's target is
// RGBA16F, so headroom this preserves actually survives to the saved file.
//
// Its input is the DoF-gathered frame (the realcamera_hdr chain runs the same prefilter +
// gather passes as the live preview first), so the enhanced file keeps the photo's blur.

uniform sampler2D DiffuseSampler;

uniform float ExposureMult;
uniform float WhiteBalance;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 rgb = texture(DiffuseSampler, texCoord).rgb;

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
