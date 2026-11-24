package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.util.Objects;

record ReflectionLightmapMixing(float scale, float bias, float limit) {
    static ReflectionLightmapMixing read(NiagaraPlatformEnvironment platform) {
        float limit = bits(platform, "r.ReflectionEnvironmentLightmapMixLargestWeight");
        if (!platform.staticLightingAllowed() || bits(platform, "r.ReflectionEnvironmentLightmapMixing") == 0)
            return new ReflectionLightmapMixing(0, 0, limit);
        float begin = Float.intBitsToFloat(bits(platform, "r.ReflectionEnvironmentBeginMixingRoughness"));
        float end = Float.intBitsToFloat(bits(platform, "r.ReflectionEnvironmentEndMixingRoughness"));
        if ((begin == 0 && end == 0) || bits(platform, "r.ReflectionEnvironmentLightmapMixBasedOnRoughness") == 0)
            return new ReflectionLightmapMixing(0, 1, limit);
        float scale = 1 / Math.max(end - begin, .001f);
        return new ReflectionLightmapMixing(scale, -begin * scale, limit);
    }

    private static int bits(NiagaraPlatformEnvironment platform, String name) {
        return Objects.requireNonNull(platform.console(name)).bits();
    }
}
