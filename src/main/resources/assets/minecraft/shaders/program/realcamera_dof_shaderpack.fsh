#version 330

// Shader-pack depth variant of realcamera_dof.fsh — see that file's header comment for
// why this is inlined rather than #moj_import'd. Identical except toDist()/isSkyRaw()
// read shader-pack depth convention (~0 near) instead of vanilla reversed-Z.

uniform sampler2D DiffuseSampler;
uniform sampler2D PreSampler;
uniform sampler2D MainDepthSampler;

uniform float BlurStrength;    // CoC ramp rate, from focal length / aperture
uniform float MaxRadiusFrac;   // blur radius at full CoC, as a fraction of frame height
uniform vec2  FocusUV;         // screen-space focus point
uniform float FarBlurGain;     // extra gain on the no-depth far layer (config)
uniform float SoftKnee;        // dioptric knee width around the focus plane (config)
uniform float HlBoost;         // out-of-focus highlight bloom (config)
uniform float HlThreshold;     // luma above which a sample is a highlight (config)
uniform float OnsetMaxPx;      // reference-px blur radius at which the effect is fully on (config)

in vec2 texCoord;

out vec4 fragColor;

const float NEAR = 0.05;
const float FAR_CLAMP = 131072.0;
const float COC_CAP = 0.99;
const int   MIN_TAPS = 180;
const int   MAX_TAPS = 400;
const vec2  R2 = vec2(0.75487766624669, 0.56984029099805);

float toDist(float depth) {
    float term = 1.0 - depth;
    return clamp(NEAR / max(term, 1e-6), NEAR, FAR_CLAMP);
}

bool isSkyRaw(float raw) {
    return raw > 0.999995;
}

float coc(float d, float focus) {
    float diopter = abs(1.0 / d - 1.0 / focus);
    diopter = (diopter * diopter) / (diopter + SoftKnee);
    return min(1.0 - exp(-diopter * BlurStrength), COC_CAP);
}

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
    vec2 res = vec2(textureSize(DiffuseSampler, 0));
    float aspect = res.x / res.y;
    float rawHere = texture(MainDepthSampler, texCoord).r;
    vec3 sharp = texture(DiffuseSampler, texCoord).rgb;

    float focusRaw = texture(MainDepthSampler, FocusUV).r;
    bool  focusInf = isSkyRaw(focusRaw);
    float focusDist = focusInf ? FAR_CLAMP : toDist(focusRaw);
    float here = toDist(rawHere);
    float skyCoc = focusInf ? 0.0 : cocFar(focusDist);
    float cc = isSkyRaw(rawHere) ? skyCoc : coc(here, focusDist);

    float rPx = cc * MaxRadiusFrac * res.y;
    float rRef = rPx * (1440.0 / res.y);
    float onset = smoothstep(0.5, max(OnsetMaxPx, 0.75), rRef);
    if (onset <= 0.0) {
        fragColor = vec4(sharp, 0.0);
        return;
    }

    float rFrac = cc * MaxRadiusFrac;
    int taps = int(clamp(rPx * 3.5, float(MIN_TAPS), float(MAX_TAPS)));

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
        float behind = smoothstep(here * 0.85, here * 1.05, sd);
        float w = mix(sCoc, 1.0, behind);

        vec3 tap = mix(texture(DiffuseSampler, suv).rgb, texture(PreSampler, suv).rgb, srcMix);
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
