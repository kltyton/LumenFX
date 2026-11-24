package com.kltyton.lumenfx.effect.render.mesh;

/** Source engine minimum-LOD selection and its scalability quality key. */
public record MeshLodPolicy(boolean perQuality, int qualityLevel) {
    public static final MeshLodPolicy DEFAULT = new MeshLodPolicy(false, -1);
}
