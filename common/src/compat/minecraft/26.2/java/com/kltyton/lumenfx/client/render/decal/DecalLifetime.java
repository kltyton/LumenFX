package com.kltyton.lumenfx.client.render.decal;

public final class DecalLifetime {
    private DecalLifetime() { }

    /** Niagara resets the proxy's one-second fade-out while retaining its constructor fade-in parameters. */
    public static float opacity(float boundFade, double updateWorldSeconds, double viewWorldSeconds) {
        if (viewWorldSeconds == 0) return 1;
        float elapsed = com.kltyton.lumenfx.client.render.math.UnrealRendererMath.clamp(1f - boundFade, 0, 1);
        float absoluteSpawnTime = (float)(updateWorldSeconds - elapsed);
        float normalizedStart = absoluteSpawnTime + 1f;
        double fadeOut = -viewWorldSeconds + normalizedStart;
        double value = fadeOut < viewWorldSeconds ? fadeOut : viewWorldSeconds;
        return (float)(value < 0 ? 0 : value < 1 ? value : 1);
    }
}
