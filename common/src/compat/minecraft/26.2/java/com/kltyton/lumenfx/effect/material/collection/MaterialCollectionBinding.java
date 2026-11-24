package com.kltyton.lumenfx.effect.material.collection;

import java.util.Objects;

/** A compiled material names one MPC uniform block and the shader stages that read it. */
public record MaterialCollectionBinding(String uniform, int stages, MaterialCollectionDefinition source) {
    public MaterialCollectionBinding {
        Objects.requireNonNull(uniform); Objects.requireNonNull(source);
        if (!uniform.matches("[A-Za-z_][A-Za-z0-9_]*") || stages == 0 || (stages & ~3) != 0 || source.vectorCount() <= 0)
            throw new IllegalArgumentException("Invalid material collection uniform binding");
    }
}
