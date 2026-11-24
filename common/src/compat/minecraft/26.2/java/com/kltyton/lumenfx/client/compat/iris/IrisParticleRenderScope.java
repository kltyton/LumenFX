package com.kltyton.lumenfx.client.compat.iris;

import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.vertices.ImmediateState;

/** Keeps Iris from replacing the custom world particle pipelines during a batch. */
public final class IrisParticleRenderScope {
    private IrisParticleRenderScope() {
    }

    public static boolean isShadowPass() {
        return ShaderpackCompat.isIrisPresent() && IrisAccess.isShadowPass();
    }

    public static void withParticlePrograms(Runnable draw) {
        if (!ShaderpackCompat.isIrisPresent()) {
            draw.run();
            return;
        }
        IrisAccess.withParticlePrograms(draw);
    }

    private static final class IrisAccess {
        private static boolean isShadowPass() {
            return ShadowRenderer.ACTIVE;
        }

        private static void withParticlePrograms(Runnable draw) {
            var previous = ImmediateState.bypass;
            ImmediateState.bypass = true;
            try {
                draw.run();
            } finally {
                ImmediateState.bypass = previous;
            }
        }
    }
}
