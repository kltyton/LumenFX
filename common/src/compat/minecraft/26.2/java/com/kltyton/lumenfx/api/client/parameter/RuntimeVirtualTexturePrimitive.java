package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Request;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The live mesh batches of a source primitive that writes runtime virtual texture pages. */
public interface RuntimeVirtualTexturePrimitive {
    enum MainPass { Never, Exclusive, Always }

    record Settings(Set<String> targets, MainPass mainPass, int lodBias, int skipMips, int minCoverage, int sortPriority) {
        public Settings {
            targets = targets.stream().map(SourceNames::canonical).collect(Collectors.toUnmodifiableSet());
            Objects.requireNonNull(mainPass);
            if (lodBias < -7 || lodBias > 8 || skipMips < 0 || skipMips > 7 || minCoverage < 0 || minCoverage > 7
                    || sortPriority < Short.MIN_VALUE || sortPriority > Short.MAX_VALUE)
                throw new IllegalArgumentException("Invalid RVT primitive settings");
        }

        public boolean drawInMainPass(boolean hidePrimitives) {
            return targets.isEmpty() || mainPass == MainPass.Always || mainPass == MainPass.Exclusive && !hidePrimitives;
        }
    }

    record Bounds(Vec3 center, double radius) {
        public Bounds {
            if (!Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                    || !Double.isFinite(radius) || radius < 0) throw new IllegalArgumentException("Invalid RVT primitive bounds");
        }
    }

    record StaticLods(int minimum, int maximum, int currentFirst) {
        public StaticLods {
            if (minimum < 0 || maximum < minimum || maximum > 15 || currentFirst < 0 || currentFirst > 15)
                throw new IllegalArgumentException("Invalid RVT static mesh LOD range");
        }
    }

    record Batch(int meshId, RenderPipeline pipeline, Consumer<RenderPass> draw) {
        public Batch {
            if (meshId < 0 || meshId > 65535) throw new IllegalArgumentException("Invalid RVT mesh id");
            Objects.requireNonNull(pipeline);
            Objects.requireNonNull(draw);
        }
    }

    /** Dynamic vertex factories return null and collect their geometry from the actual page view. */
    @Nullable StaticLods staticLods();

    /** The source material and vertex factory prepare their exact batches for this page, using -1 for dynamic geometry. */
    List<Batch> prepare(Request request, int lod, CommandEncoder encoder);

    interface Registration extends AutoCloseable {
        /** Invalidates the old and new world bounds, including material edits at an unchanged transform. */
        void changed(Bounds bounds);
        boolean drawInMainPass();
        @Override void close();
    }
}
