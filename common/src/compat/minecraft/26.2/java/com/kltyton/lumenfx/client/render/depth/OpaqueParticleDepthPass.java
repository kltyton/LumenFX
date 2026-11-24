package com.kltyton.lumenfx.client.render.depth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Runs the native opaque stages that provide Niagara's full and partial depth inputs. */
public final class OpaqueParticleDepthPass {
    @FunctionalInterface
    public interface Draw<B> {
        void draw(List<B> batches, boolean secondary, boolean last);
    }

    private OpaqueParticleDepthPass() { }

    public static <B> void draw(List<B> batches, boolean partialDepthRequested,
                                Predicate<B> readsPartialDepth, boolean secondaryMeshes,
                                Draw<B> draw, Runnable preserveDepth) {
        if (!partialDepthRequested) {
            draw.draw(batches, false, true);
            return;
        }

        List<B> primary = new ArrayList<>(batches.size());
        List<B> secondary = new ArrayList<>();
        for (B batch : batches) {
            if (readsPartialDepth.test(batch)) secondary.add(batch);
            else primary.add(batch);
        }
        boolean hasSecondary = !secondary.isEmpty() || secondaryMeshes;
        draw.draw(primary, false, !secondaryMeshes);
        if (hasSecondary) {
            preserveDepth.run();
            draw.draw(secondary, true, true);
        }
    }
}
