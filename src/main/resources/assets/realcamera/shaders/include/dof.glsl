// Shared depth-of-field gather — the approved baseline (Aug 29).
//
// Circle of confusion in DIOPTRIC space (|1/here - 1/focus|) so it does not depend on
// the absolute metre scale of the depth mapping — only the ratio matters, and the
// magnitude is carried by BlurStrength / MaxRadiusFrac (tuned to feel). Exponential
// falloff, so blur never flat-maxes: it keeps building gradually with distance.
//
// Gathered with an R2 low-discrepancy disc + a per-pixel Cranley-Patterson rotation,
// so blurred foliage stays smooth (no golden-angle spokes). Reads the pre-blurred
// PreSampler so the disc spreads an already-smooth signal, not raw block texture. The
// blur fades in over the first ~2 px of radius (onset), so there is no hard line where
// it switches on.
//
// #define PHOTOMODE_SHADERPACK_DEPTH before importing for shaderpack depth (~0 near);
// otherwise reversed-Z.

uniform sampler2D MainSampler;
uniform sampler2D PreSampler;
uniform sampler2D MainDepthSampler;

layout(std140) uniform DofConfig {
    float BlurStrength;    // CoC ramp rate, from focal length / aperture
    float MaxRadiusFrac;   // blur radius at full CoC, as a fraction of frame height
    vec2  FocusUV;         // screen-space focus point
    float FarBlurGain;     // extra gain on the no-depth far layer (config)
    float SoftKnee;        // dioptric knee width around the focus plane (config)
    float HlBoost;         // out-of-focus highlight bloom (config)
    float HlThreshold;     // luma above which a sample is a highlight (config)
    float OnsetMaxPx;      // reference-px blur radius at which the effect is fully on (config)
};

in vec2 texCoord;

out vec4 fragColor;

const float NEAR = 0.05;
// Far enough for LOD-terrain mods (e.g. Voxy) at their maximum render
// distance — 4096 chunks is ~65 k blocks. A low clamp collapsed everything past it onto
// a single plane, so distant terrain could not be focused on and read as fully blurred.
const float FAR_CLAMP = 131072.0;
const float COC_CAP = 0.99;          // real blur asymptotes below this; sky uses the cap
const int   MIN_TAPS = 180;
const int   MAX_TAPS = 400;
const vec2  R2 = vec2(0.75487766624669, 0.56984029099805);

float toDist(float depth) {
#ifdef PHOTOMODE_SHADERPACK_DEPTH
    float term = 1.0 - depth;
#else
    float term = depth;
#endif
    return clamp(NEAR / max(term, 1e-6), NEAR, FAR_CLAMP);
}

bool isSkyRaw(float raw) {
#ifdef PHOTOMODE_SHADERPACK_DEPTH
    return raw > 0.999995;
#else
    // Reversed-Z: the sky is the exact 0.0 clear value. Real geometry keeps a small
    // positive depth even at extreme LOD ranges, so the old 5e-6 epsilon
    // false-flagged distant terrain as sky and forced it to full blur.
    return raw < 1e-9;
#endif
}

float coc(float d, float focus) {
    float diopter = abs(1.0 / d - 1.0 / focus);
    // Smooth knee instead of a hard sharp-band edge: ~diopter^2 right around focus, so
    // the blur eases in with zero initial slope (no visible "switch-on" line on a
    // receding surface), then ~diopter further out so blur strength is unchanged.
    diopter = (diopter * diopter) / (diopter + SoftKnee);
    return min(1.0 - exp(-diopter * BlurStrength), COC_CAP);
}

// CoC for the "no depth" layer (real sky + depthless far LOD terrain). No soft knee:
// that layer is a single far plane with no receding gradient, so there is no onset line
// to smooth — the knee would only crush legitimate background separation, which at a
// long focal length is exactly the blur the shot wants. FarBlurGain lifts it a touch
// above a strict dioptric CoC.
float cocFar(float focus) {
    float diopter = abs(1.0 / FAR_CLAMP - 1.0 / focus);
    return min(1.0 - exp(-diopter * BlurStrength * FarBlurGain), COC_CAP);
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec2 res = vec2(textureSize(MainSampler, 0));
    float aspect = res.x / res.y;
    float rawHere = texture(MainDepthSampler, texCoord).r;
    vec3 sharp = texture(MainSampler, texCoord).rgb;

    // If the reticle is on the sky — or on far LOD terrain that a mod
    // (e.g. Voxy) never wrote depth for, which reads identically — treat it as focusing at
    // infinity: those "no depth" pixels then render sharp instead of being forced to
    // full blur.
    float focusRaw = texture(MainDepthSampler, FocusUV).r;
    bool  focusInf = isSkyRaw(focusRaw);
    float focusDist = focusInf ? FAR_CLAMP : toDist(focusRaw);
    float here = toDist(rawHere);
    // "No depth" pixels (real sky, or depthless far LOD terrain) blur as if they sit at
    // the far clamp — a graduated amount that scales with how far focus is from infinity,
    // not a hard slam to the cap. Sharp when focusing at infinity.
    float skyCoc = focusInf ? 0.0 : cocFar(focusDist);
    float cc = isSkyRaw(rawHere) ? skyCoc : coc(here, focusDist);

    float rPx = cc * MaxRadiusFrac * res.y;
    // onset / srcMix thresholds are in pixels; normalise them to a fixed reference
    // height so the in-focus slab is the same at any capture resolution (otherwise
    // 8K silently gets a much shallower DoF from the same settings).
    float rRef = rPx * (1440.0 / res.y);
    float onset = smoothstep(0.5, max(OnsetMaxPx, 0.75), rRef);
    if (onset <= 0.0) {
        fragColor = vec4(sharp, 0.0);
        return;
    }

    float rFrac = cc * MaxRadiusFrac;
    int taps = int(clamp(rPx * 3.5, float(MIN_TAPS), float(MAX_TAPS)));

    // Gather source: at a small blur read the sharp frame (so a lightly-mixed gather
    // can't drag pre-blur mud over near-sharp foliage — the aperture-dependent grey
    // splotch); at a big blur read the pre-blurred frame (so heavy bokeh spreads a
    // smooth signal, no block sparkle).
    float srcMix = smoothstep(2.0, 12.0, rRef);

    vec2 cp = vec2(hash12(texCoord * res + 0.5), hash12(texCoord * res + 19.7));
    vec3 sum = mix(sharp, texture(PreSampler, texCoord).rgb, srcMix);
    float wsum = 1.0;
    for (int i = 0; i < taps; i++) {
        vec2 u = fract(cp + R2 * float(i + 1));
        float r = sqrt(u.x) * rFrac;
        float a = u.y * 6.28318530718;
        vec2 suv = texCoord + vec2(cos(a) / aspect, sin(a)) * r;

        float sraw = texture(MainDepthSampler, suv).r;
        float sd = toDist(sraw);
        float sCoc = isSkyRaw(sraw) ? skyCoc : coc(sd, focusDist);
        // clearly-nearer sharp occluders are held back; same-plane / behind samples
        // contribute fully, with a wide relative blend so depth layers don't seam.
        float behind = smoothstep(here * 0.85, here * 1.05, sd);
        float w = mix(sCoc, 1.0, behind);

        vec3 tap = mix(texture(MainSampler, suv).rgb, texture(PreSampler, suv).rgb, srcMix);
        // A bright sample much farther than this pixel is the sky or the atmospheric
        // haze band behind it — let it barely contribute, so it can't bleed a glow
        // onto a blurred FOREGROUND element backed by sky. Only foreground pixels
        // (nearer than focus) get this — a background hill must still blur softly into
        // the sky behind it.
        float isForeground = 1.0 - smoothstep(0.9, 1.2, here / focusDist);
        float farBright = smoothstep(3.0, 8.0, sd / max(here, 0.1))
                        * smoothstep(0.50, 0.80, luma(tap));
        w *= 1.0 - 0.95 * farBright * isForeground;

        w *= 1.0 + HlBoost * smoothstep(min(HlThreshold, 0.98), 1.0, luma(tap));
        sum += tap * w;
        wsum += w;
    }

    vec3 gathered = sum / max(wsum, 1e-4);
    fragColor = vec4(mix(sharp, gathered, onset), cc * onset);
}
