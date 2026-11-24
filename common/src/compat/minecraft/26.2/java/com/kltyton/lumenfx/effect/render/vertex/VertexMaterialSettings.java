package com.kltyton.lumenfx.effect.render.vertex;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import java.util.Objects;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public record VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask,
                                     boolean worldPositionOffset, boolean vertexBasis, boolean sphericalParticleNormals,
                                     boolean previousView, boolean previousMatrices, boolean primitiveMatrices, boolean previousFirstPerson,
                                     int firstPersonInputs, boolean previousClock,
                                     @Nullable Identifier naniteShader, List<Integer> naniteInterpolatorWidths) {
    public static final int FIRST_PERSON_INPUTS = 4095 | MaterialDefinition.ATMOSPHERE_LIGHT_VIEW_INPUT;

    public int firstPersonViewInputs() { return firstPersonInputs & (255 | MaterialDefinition.ATMOSPHERE_LIGHT_VIEW_INPUT); }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask, boolean worldPositionOffset,
                                  boolean vertexBasis, boolean sphericalParticleNormals, boolean previousView, boolean previousMatrices,
                                  boolean primitiveMatrices, boolean previousFirstPerson, int firstPersonInputs, boolean previousClock) {
        this(shader, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals,
                previousView, previousMatrices, primitiveMatrices, previousFirstPerson, firstPersonInputs, previousClock, null, List.of());
    }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask, boolean worldPositionOffset,
                                  boolean vertexBasis, boolean sphericalParticleNormals, boolean previousView, boolean previousMatrices,
                                  boolean primitiveMatrices, boolean previousFirstPerson) {
        this(shader, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals,
                previousView, previousMatrices, primitiveMatrices, previousFirstPerson, 0, worldPositionOffset);
    }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask, boolean worldPositionOffset,
                                  boolean vertexBasis, boolean sphericalParticleNormals, boolean previousView, boolean previousMatrices,
                                  boolean primitiveMatrices) {
        this(shader, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals,
                previousView, previousMatrices, primitiveMatrices, false);
    }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask, boolean worldPositionOffset,
                                  boolean vertexBasis, boolean sphericalParticleNormals, boolean previousView, boolean previousMatrices) {
        this(shader, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals, previousView, previousMatrices, false);
    }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask, boolean worldPositionOffset,
                                  boolean vertexBasis, boolean sphericalParticleNormals, boolean previousView) {
        this(shader, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals, previousView, false);
    }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask, boolean worldPositionOffset,
                                  boolean vertexBasis, boolean sphericalParticleNormals) {
        this(shader, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, sphericalParticleNormals, false);
    }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask, boolean worldPositionOffset,
                                  boolean vertexBasis) {
        this(shader, maximumDisplacement, customizedUVMask, worldPositionOffset, vertexBasis, false);
    }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement, int customizedUVMask, boolean worldPositionOffset) {
        this(shader, maximumDisplacement, customizedUVMask, worldPositionOffset, false);
    }
    public VertexMaterialSettings(Identifier shader, float maximumDisplacement) {
        this(shader, maximumDisplacement, 0, true);
    }
    public VertexMaterialSettings {
        Objects.requireNonNull(shader, "shader");
        naniteInterpolatorWidths = List.copyOf(naniteInterpolatorWidths);
        if (naniteShader == null && !naniteInterpolatorWidths.isEmpty())
            throw new IllegalArgumentException("Nanite vertex interpolators require their shader");
        for (int width : naniteInterpolatorWidths)
            if (width < 1 || width > 4) throw new IllegalArgumentException("Invalid Nanite vertex interpolator width");
        if (!Float.isFinite(maximumDisplacement))
            throw new IllegalArgumentException("Material vertex displacement bound must be finite");
        if ((customizedUVMask & ~255) != 0)
            throw new IllegalArgumentException("Material customized UV mask must fit eight channels");
        if ((firstPersonInputs & ~FIRST_PERSON_INPUTS) != 0)
            throw new IllegalArgumentException("Invalid first-person material input requirements");
    }
}
