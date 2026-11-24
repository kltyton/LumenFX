package com.kltyton.lumenfx.effect.render.mesh;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Resolved immutable geometry and transform rules for one material section draw. */
public record MeshBatchGeometry(MeshSectionGeometry geometry, MeshRendererSettings renderer,
                                MeshRendererSettings.Entry mesh, int section, boolean localSpace,
                                float subImageColumns, float subImageRows, boolean subImageBlend,
                                com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion,
                                StaticMeshAsset.@Nullable Bounds boundsOverride, boolean reversedIndices,
                                boolean instanceWinding, int subImageUvMask) {
    public MeshBatchGeometry(MeshSectionGeometry geometry, MeshRendererSettings renderer,
                             MeshRendererSettings.Entry mesh, int section, boolean localSpace,
                             float subImageColumns, float subImageRows, boolean subImageBlend,
                             com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion,
                             StaticMeshAsset.@Nullable Bounds boundsOverride, boolean reversedIndices,
                             boolean instanceWinding) {
        this(geometry, renderer, mesh, section, localSpace, subImageColumns, subImageRows, subImageBlend,
                motion, boundsOverride, reversedIndices, instanceWinding, 1);
    }
    public MeshBatchGeometry(MeshSectionGeometry geometry, MeshRendererSettings renderer,
                             MeshRendererSettings.Entry mesh, int section, boolean localSpace,
                             float subImageColumns, float subImageRows, boolean subImageBlend,
                             com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion,
                             StaticMeshAsset.@Nullable Bounds boundsOverride) {
        this(geometry, renderer, mesh, section, localSpace, subImageColumns, subImageRows, subImageBlend,
                motion, boundsOverride, false, false);
    }

    public MeshBatchGeometry(MeshSectionGeometry geometry, MeshRendererSettings renderer,
                             MeshRendererSettings.Entry mesh, int section, boolean localSpace,
                             float subImageColumns, float subImageRows, boolean subImageBlend,
                             com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion) {
        this(geometry, renderer, mesh, section, localSpace, subImageColumns, subImageRows, subImageBlend, motion, null);
    }
    public MeshBatchGeometry(MeshSectionGeometry geometry, MeshRendererSettings renderer, MeshRendererSettings.Entry mesh,
                             int section, boolean localSpace, float subImageColumns, float subImageRows, boolean subImageBlend) {
        this(geometry, renderer, mesh, section, localSpace, subImageColumns, subImageRows, subImageBlend,
                com.kltyton.lumenfx.effect.render.ParticleMotionSettings.DEFAULT);
    }
    public MeshBatchGeometry(MeshSectionGeometry geometry, MeshRendererSettings renderer,
                             MeshRendererSettings.Entry mesh, int section) {
        this(geometry, renderer, mesh, section, false, 1, 1, false);
    }
    public MeshBatchGeometry {
        Objects.requireNonNull(geometry); Objects.requireNonNull(renderer); Objects.requireNonNull(mesh);
        Objects.requireNonNull(motion);
        if (section < 0 || section >= geometry.sections().size()) throw new IllegalArgumentException("Mesh section is out of range");
        if (!Float.isFinite(subImageColumns) || !Float.isFinite(subImageRows) || subImageColumns <= 0 || subImageRows <= 0)
            throw new IllegalArgumentException("Invalid mesh subimage dimensions");
        if (reversedIndices && !(geometry instanceof StaticMeshGeometry))
            throw new IllegalArgumentException("Reversed indices require static mesh geometry");
    }

    public StaticMeshAsset.Bounds sourceBounds() {
        return boundsOverride == null ? Objects.requireNonNull(mesh.asset(), "Mesh draw has no source bounds").extendedBounds() : boundsOverride;
    }
}
