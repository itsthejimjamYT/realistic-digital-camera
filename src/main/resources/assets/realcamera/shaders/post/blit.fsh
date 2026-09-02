#version 330

uniform sampler2D InSampler;

layout(std140) uniform FilmConfig {
    float ExposureMult;   // combined EV from the exposure triangle, as a linear multiplier
    float GrainAmount;    // 0..1, from ISO
    float GrainDensity;   // grain cells across the frame height
    float WhiteBalance;   // signed R/B channel shift: + warms, - cools
};

layout(std140) uniform GradeConfig {
    vec4 G0;   // x highlight(-2..4)  y shadow(-2..4)  z color(-4..4)  w clarity(-5..5)
    vec4 G1;   // x colorChrome(0..1)  y fxBlue(0..1)  z drCompress(0..1)  w fade(0..0.3)
    vec4 G2;   // x wbShiftR(-9..9)  y wbShiftB(-9..9)  z monoToneWarm(-9..9)  w mono(0/1)
    vec4 G3;   // x baseContrast  y basePivot  z baseSat  w strength (0 = off)
    vec4 G4;   // xyz film-sim colour cast (mul)   w grain boost
    vec4 G5;   // x grain "large" flag (0/1)   yzw spare
    vec4 G6;   // xyz split-tone shadow push    w split amount (0 = off)
    vec4 G7;   // xyz split-tone highlight push  w split balance (reserved)
};

layout(std140) uniform AidConfig {
    float Zebras;   // 0/1 blown-highlight warning (preview only)
    float Peaking;  // 0/1 focus peaking (preview only)
    float AidTime;  // seconds, animates the zebra stripes
    float _aidpad;
};

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// Film-recipe grade. A film-sim base (contrast/pivot/saturation +
// colour cast), then the camera's per-recipe knobs: Highlight/Shadow tone, Color,
// Color Chrome + FX Blue, WB Shift, Dynamic Range, and a matte "Fade". Clarity is
// a screen-space op done in main(). G3.w is the wet/dry mix; 0 (Standard) skips it.
vec3 applyGrade(vec3 c) {
    float strength = G3.w;
    if (strength <= 0.001) {
        return c;
    }

    vec3 g = c;

    // film-sim base: contrast about a pivot, base saturation, colour cast
    g = max((g - G3.y) * G3.x + G3.y, 0.0);
    g = mix(vec3(dot(g, LUMA)), g, G3.z);
    g *= G4.xyz;

    // monochrome + warm/cool toning
    if (G2.w > 0.5) {
        float y = dot(g, vec3(0.30, 0.59, 0.11));
        float warm = G2.z * 0.018;
        g = vec3(y) + vec3(warm, warm * 0.10, -warm);
    }

    // Highlight / Shadow tone: extra contrast at each end, around fixed anchors
    {
        float l = dot(g, LUMA);
        float wHi = smoothstep(0.42, 0.98, l);
        float wLo = 1.0 - smoothstep(0.04, 0.55, l);
        g = mix(g, max((g - 0.72) * (1.0 + G0.x * 0.10) + 0.72, 0.0), wHi);
        g = mix(g, max((g - 0.24) * (1.0 + G0.y * 0.10) + 0.24, 0.0), wLo);
    }

    // Color (saturation offset)
    g = mix(vec3(dot(g, LUMA)), g, max(1.0 + G0.z * 0.085, 0.0));

    // Color Chrome / FX Blue: deepen colour that is already saturated
    {
        float mx = max(max(g.r, g.g), g.b);
        float mn = min(min(g.r, g.g), g.b);
        float hsvS = (mx - mn) / max(mx, 1e-4);
        float k = G1.x * smoothstep(0.30, 0.90, hsvS) * 0.28;
        if (g.b >= g.r && g.b >= g.g) {
            k += G1.y * smoothstep(0.20, 0.85, hsvS) * 0.34;
        }
        g = mix(vec3(dot(g, LUMA)), g, 1.0 + k);
        g *= 1.0 - k * 0.20;
    }

    // WB Shift (Red / Blue grid)
    g *= vec3(1.0 + G2.x * 0.013 - G2.y * 0.004,
              1.0 - G2.x * 0.004 - G2.y * 0.004,
              1.0 + G2.y * 0.013 - G2.x * 0.004);

    // Dynamic Range: pull the highlights down, lift the toe a hair
    {
        float dr = G1.z;
        float knee = mix(0.84, 0.58, dr);
        vec3 over = max(g - knee, 0.0);
        g = min(g, vec3(knee)) + over / (1.0 + over / max(1.0 - knee, 0.05));
        g += dr * 0.018 * (1.0 - smoothstep(0.0, 0.35, dot(g, LUMA)));
    }

    // Split tone: every pixel leans toward the shadow tint (G6) or the highlight tint
    // (G7) by its brightness, so midtones are graded too — not just the tonal tips.
    {
        float amt = G6.w;
        if (amt > 0.001) {
            float l = dot(g, LUMA);
            float t = clamp(l * 1.35 + 0.04, 0.0, 1.0);   // shadow <-> highlight crossfade (leans warm past ~1/3 luma)
            vec3 off = mix(G6.xyz, G7.xyz, t) * amt;
            off -= vec3(dot(off, LUMA));                   // hold luminance
            g = max(g + off, 0.0);
        }
    }

    // Fade (matte black lift)
    g = g * (1.0 - G1.w) + G1.w;

    return mix(c, max(g, 0.0), strength);
}

// Hash without sine (Dave Hoskins). The sin() hash bands badly on an integer
// lattice — which is exactly how grain is sampled — and reads as a pattern.
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float grainCell(vec2 gp) {
    return hash12(floor(gp)) - 0.5;
}

// Finishing pass. (1) Cleanup: melt the DoF gather's residual noise in blurred
// regions — InSampler.a is the blur amount (0 = sharp, untouched). (2) Film:
// exposure-triangle multiplier, camera response, film recipe, then ISO grain.
void main() {
    // Handheld slow-shutter smear (G5.w, capture only): average along a tilted axis
    // so a shot taken below the 1/focal rule comes out soft, like camera shake.
    vec4 c;
    float shake = G5.w;
    if (shake > 0.001) {
        vec2 spx = 1.0 / vec2(textureSize(InSampler, 0));
        float span = shake * float(textureSize(InSampler, 0).y) * 0.009;
        vec2 dir = normalize(vec2(0.93, 0.36));
        vec4 acc = vec4(0.0);
        const int NS = 9;
        for (int i = 0; i < NS; i++) {
            float t = (float(i) / float(NS - 1) - 0.5) * 2.0;
            acc += texture(InSampler, texCoord + dir * (t * span) * spx);
        }
        c = acc / float(NS);
    } else {
        c = texture(InSampler, texCoord);
    }
    float amt = c.a;   // circle of confusion (0 = in focus)
    vec3 rgb = c.rgb;

    if (amt >= 0.03) {
        vec2 spx = 1.0 / vec2(textureSize(InSampler, 0));
        float rad = mix(0.7, 2.4, amt);
        vec3 sum = rgb;
        float wsum = 1.0;
        const int N = 16;
        for (int i = 0; i < N; i++) {
            float a = float(i) * 2.399963;
            float r = sqrt((float(i) + 0.5) / float(N)) * rad;
            sum += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * r * spx).rgb;
            wsum += 1.0;
        }
        rgb = sum / wsum;
    }

    // Exposure. The scene is already tonemapped by the shader pack, so this is a
    // straight photographic push/pull.
    rgb *= ExposureMult;

    // White balance: warm/cool the image by scaling red against blue.
    rgb *= vec3(1.0 + WhiteBalance, 1.0, 1.0 - WhiteBalance);

    // Camera response. A real sensor at a bright exposure has far less latitude than
    // the eye: deep shadows crush toward black, midtone contrast is punchy, highlights
    // sit high. The shader renders the scene lifted and flat so a player can see; this
    // puts the camera back. (The highlight shoulder below finishes the top end.)
    {
        const float pivot = 0.42;     // tonal centre the contrast pivots around
        const float contrast = 1.36;  // midtone S-curve strength
        const float toe = 0.055;      // extra crush right at the black point
        const float sat = 1.06;       // slight saturation, like a "standard" profile
        rgb = max((rgb - pivot) * contrast + pivot, 0.0);
        rgb = max(rgb - toe * exp(-rgb * 14.0), 0.0);
        float l = dot(rgb, LUMA);
        rgb = mix(vec3(l), rgb, sat);
    }

    // --- physical / panel filters (G5.y polarizer, G5.z mist) ---
    float polar = G5.y;
    if (polar > 0.001) {
        // A polarizer mostly kills glare and reflected haze: ease the highlights
        // down with a soft shoulder so bright surfaces stop blowing out.
        float l0 = dot(rgb, LUMA);
        float hi = smoothstep(0.5, 1.0, l0);
        rgb *= 1.0 - 0.19 * polar * hi;
        // Deepen a blue sky a touch (the classic polarizer cue).
        float skyMask = clamp((rgb.b - max(rgb.r, rgb.g)) * 4.0, 0.0, 1.0);
        rgb *= mix(vec3(1.0), vec3(0.92, 0.955, 1.01), skyMask * polar);
        // ...and a gentle overall saturation lift.
        float l = dot(rgb, LUMA);
        rgb = mix(vec3(l), rgb, 1.0 + 0.10 * polar);
    }
    float mist = G5.z;
    if (mist > 0.001) {
        // Bloom: a soft halo pulled from bright neighbours.
        vec2 mpx = 1.0 / vec2(textureSize(InSampler, 0));
        vec3 bloom = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.7853982;
            vec3 s = texture(InSampler, texCoord + vec2(cos(a), sin(a)) * 4.0 * mpx).rgb;
            bloom += s * smoothstep(0.45, 1.0, dot(s, LUMA));
        }
        rgb += bloom * (mist * 0.14);
        // Lift the blacks for the milky, low-contrast film look.
        rgb = rgb + mist * 0.05 * (1.0 - rgb) * (1.0 - smoothstep(0.0, 0.35, dot(rgb, LUMA)));
    }

    // Clarity: midtone local contrast. Negative = the soft, dreamy film look.
    if (abs(G0.w) > 0.01) {
        vec2 cpx = 1.0 / vec2(textureSize(InSampler, 0));
        vec3 lc = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.7853982;
            lc += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * 6.0 * cpx).rgb;
        }
        lc *= 0.125;
        float lm = dot(rgb, LUMA);
        float mid = clamp(1.0 - abs(lm - 0.5) * 1.8, 0.0, 1.0);
        rgb += (rgb - lc) * (G0.w * 0.085) * mid;
    }

    // Film recipe (color grade).
    rgb = applyGrade(rgb);

    // Soft highlight shoulder: identity below the knee, asymptotes to white above,
    // so a pushed exposure or a bright horizon rolls off instead of flat-clipping.
    {
        const float knee = 0.78;
        vec3 over = max(rgb - knee, 0.0);
        rgb = min(rgb, vec3(knee)) + over / (1.0 + over / (1.0 - knee));
    }

    // Film grain: monochrome luminance noise, two octaves for an organic (non-blocky)
    // look, eased out of the highlights so skies stay clean. Cell size tracks frame
    // height so grain survives high-res capture + downsample instead of averaging away.
    float grainAmt = max(GrainAmount, G4.w);
    if (grainAmt > 0.001) {
        vec2 res = vec2(textureSize(InSampler, 0));
        float density = G5.x > 0.5 ? GrainDensity * 0.5 : GrainDensity;   // "large" = fewer, bigger cells
        float cell = max(res.y / max(density, 1.0), 1.0);
        vec2 gp = texCoord * res / cell;
        float n = grainCell(gp) * 0.7 + grainCell(gp * 2.03 + 11.7) * 0.3;
        float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
        float shape = mix(1.0, 0.4, smoothstep(0.25, 0.95, luma));
        rgb += n * grainAmt * 0.26 * shape;
    }

    vec3 outc = clamp(rgb, 0.0, 1.0);

    // --- preview-only viewfinder aids (AidParams forces these to 0 during capture) ---
    if (Peaking > 0.5) {
        vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
        float l0 = dot(texture(InSampler, texCoord).rgb, vec3(0.299, 0.587, 0.114));
        float lx = dot(texture(InSampler, texCoord + vec2(px.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
        float ly = dot(texture(InSampler, texCoord + vec2(0.0, px.y)).rgb, vec3(0.299, 0.587, 0.114));
        float edge = abs(l0 - lx) + abs(l0 - ly);
        // Only mark pixels in the actual focus plane (CoC ~0). Blurred regions and the
        // sky sentinel (amt >= ~1.0) are excluded, so distant sharp terrain and sky
        // no longer read as "in focus".
        float sharpMask = 1.0 - smoothstep(0.015, 0.09, amt);
        outc = mix(outc, vec3(1.0, 0.15, 0.9), smoothstep(0.05, 0.16, edge) * 0.85 * sharpMask);
    }
    if (Zebras > 0.5) {
        float mx = max(max(outc.r, outc.g), outc.b);
        if (mx > 0.985) {
            vec2 res = vec2(textureSize(InSampler, 0));
            float d = texCoord.x * res.x + texCoord.y * res.y - AidTime * 40.0;
            float s = step(0.5, fract(d / 14.0));
            outc = mix(outc, vec3(s), 0.55);
        }
    }

    fragColor = vec4(outc, 1.0);
}
