const vec3 AP1_RGB2Y = vec3(0.2722287168, 0.6740817658, 0.0536895174);

mat3 rows(vec3 a, vec3 b, vec3 c) {
    return transpose(mat3(a, b, c));
}

mat3 xyzToAp1() {
    return rows(vec3(1.6410233797, -0.3248032942, -0.2364246952),
                vec3(-0.6636628587, 1.6153315917, 0.0167563477),
                vec3(0.0117218943, -0.0082844420, 0.9883948585));
}

mat3 ap1ToXyz() {
    return rows(vec3(0.6624541811, 0.1340042065, 0.1561876870),
                vec3(0.2722287168, 0.6740817658, 0.0536895174),
                vec3(-0.0055746495, 0.0040607335, 1.0103391003));
}

mat3 srgbToXyz() {
    return rows(vec3(0.4123907993, 0.3575843394, 0.1804807884),
                vec3(0.2126390059, 0.7151686788, 0.0721923154),
                vec3(0.0193308187, 0.1191947798, 0.9505321522));
}

mat3 xyzToSrgb() {
    return rows(vec3(3.2409699419, -1.5373831776, -0.4986107603),
                vec3(-0.9692436363, 1.8759675015, 0.0415550574),
                vec3(0.0556300797, -0.2039769589, 1.0569715142));
}

mat3 d65ToD60() {
    return rows(vec3(1.0130349146, 0.0061052578, -0.0149709436),
                vec3(0.0076982301, 0.9981633521, -0.0050320385),
                vec3(-0.0028413174, 0.0046851567, 0.9245061375));
}

mat3 d60ToD65() {
    return rows(vec3(0.9872240087, -0.0061132286, 0.0159532883),
                vec3(-0.0075983718, 1.0018614847, 0.0053300358),
                vec3(0.0030725771, -0.0050959615, 1.0816806031));
}

mat3 ap1ToAp0() {
    return rows(vec3(0.6954522414, 0.1406786965, 0.1638690622),
                vec3(0.0447945634, 0.8596711185, 0.0955343182),
                vec3(-0.0055258826, 0.0040252103, 1.0015006723));
}

mat3 ap0ToAp1() {
    return rows(vec3(1.4514393161, -0.2365107469, -0.2149285693),
                vec3(-0.0765537734, 1.1762296998, -0.0996759264),
                vec3(0.0083161484, -0.0060324498, 0.9977163014));
}

mat3 srgbToAp1() {
    return xyzToAp1() * d65ToD60() * srgbToXyz();
}

mat3 ap1ToSrgb() {
    return xyzToSrgb() * d60ToD65() * ap1ToXyz();
}

float rgb2Saturation(vec3 rgb) {
    float minrgb = min(min(rgb.r, rgb.g), rgb.b);
    float maxrgb = max(max(rgb.r, rgb.g), rgb.b);
    return (max(maxrgb, 1.0e-10) - max(minrgb, 1.0e-10)) / max(maxrgb, 1.0e-2);
}

float rgb2Yc(vec3 rgb) {
    float chroma = sqrt(rgb.b * (rgb.b - rgb.g) + rgb.g * (rgb.g - rgb.r) + rgb.r * (rgb.r - rgb.b));
    return (rgb.b + rgb.g + rgb.r + 1.75 * chroma) / 3.0;
}

float rgb2Hue(vec3 rgb) {
    if (rgb.r == rgb.g && rgb.g == rgb.b) return 0.0;
    float hue = (180.0 / 3.141592653589793) * atan(sqrt(3.0) * (rgb.g - rgb.b), 2.0 * rgb.r - rgb.g - rgb.b);
    if (hue < 0.0) hue += 360.0;
    return clamp(hue, 0.0, 360.0);
}

float centerHue(float hue, float center) {
    float centered = hue - center;
    if (centered < -180.0) centered += 360.0;
    else if (centered > 180.0) centered -= 360.0;
    return centered;
}

float glowForward(float ycIn, float gain, float mid) {
    if (ycIn <= 2.0 / 3.0 * mid) return gain;
    if (ycIn >= 2.0 * mid) return 0.0;
    return gain * (mid / ycIn - 0.5);
}

float sigmoidShaper(float x) {
    float t = max(1.0 - abs(0.5 * x), 0.0);
    return 0.5 * (1.0 + sign(x) * (1.0 - t * t));
}

vec3 filmToneMap(vec3 color, float slope, float toe, float shoulder, float blackClip, float whiteClip) {
    // UE 5.8.4 TonemapCommon.ush FilmToneMap(), ACES.ush glow/red modifiers.
    vec3 colorAp0 = ap1ToAp0() * color;
    float saturation = rgb2Saturation(colorAp0);
    float ycIn = rgb2Yc(colorAp0);
    float glowScale = sigmoidShaper((saturation - 0.4) / 0.2);
    colorAp0 *= 1.0 + glowForward(ycIn, 0.05 * glowScale, 0.08);

    float centeredHue = centerHue(rgb2Hue(colorAp0), 0.0);
    float hueWeight = 1.0 - abs(2.0 * centeredHue / 135.0);
    hueWeight = clamp(hueWeight, 0.0, 1.0);
    hueWeight = hueWeight * hueWeight * (3.0 - 2.0 * hueWeight);
    hueWeight *= hueWeight;
    colorAp0.r += hueWeight * saturation * (0.03 - colorAp0.r) * (1.0 - 0.82);

    vec3 working = max(ap0ToAp1() * colorAp0, vec3(0.0));
    working = mix(vec3(dot(working, AP1_RGB2Y)), working, 0.96);

    float toeScale = 1.0 + blackClip - toe;
    float shoulderScale = 1.0 + whiteClip - shoulder;
    float toeMatch;
    if (toe > 0.8) {
        toeMatch = (1.0 - toe - 0.18) / slope + log(0.18) / log(10.0);
    } else {
        float bt = (0.18 + blackClip) / toeScale - 1.0;
        toeMatch = log(0.18) / log(10.0) - 0.5 * log((1.0 + bt) / (1.0 - bt)) * (toeScale / slope);
    }
    float straightMatch = (1.0 - toe) / slope - toeMatch;
    float shoulderMatch = shoulder / slope - straightMatch;

    vec3 logColor = log(max(working, vec3(1.0e-10))) / log(10.0);
    vec3 straightColor = slope * (logColor + straightMatch);
    vec3 toeColor = -blackClip + (2.0 * toeScale) /
                    (1.0 + exp((-2.0 * slope / toeScale) * (logColor - toeMatch)));
    vec3 shoulderColor = (1.0 + whiteClip) - (2.0 * shoulderScale) /
                    (1.0 + exp((2.0 * slope / shoulderScale) * (logColor - shoulderMatch)));

    toeColor = mix(straightColor, toeColor, 1.0 - step(vec3(toeMatch), logColor));
    shoulderColor = mix(straightColor, shoulderColor, step(vec3(shoulderMatch), logColor));

    vec3 t = clamp((logColor - toeMatch) / (shoulderMatch - toeMatch), 0.0, 1.0);
    t = shoulderMatch < toeMatch ? 1.0 - t : t;
    t = (3.0 - 2.0 * t) * t * t;
    vec3 toneColor = mix(toeColor, shoulderColor, t);
    toneColor = mix(vec3(dot(toneColor, AP1_RGB2Y)), toneColor, 0.93);
    return max(toneColor, vec3(0.0));
}
