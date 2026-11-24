package com.kltyton.lumenfx.effect.niagara.interfaces.physics;

import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Bone-local primitives supported by the native Niagara Physics Asset interface. */
public record NiagaraPhysicsAsset(List<Primitive> elements, @Nullable SkeletalMeshAsset previewMesh) {
    public NiagaraPhysicsAsset { elements = List.copyOf(elements); }

    public enum Kind { BOX, SPHERE, CAPSULE }

    public record Primitive(String bone, Kind kind, Vec3 center, Vec3 pitchYawRoll, Vec3 extent, boolean queryAndPhysics) {
        public Primitive {
            Objects.requireNonNull(bone);
            Objects.requireNonNull(kind);
            for (Vec3 value : List.of(center, pitchYawRoll, extent))
                if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z))
                    throw new IllegalArgumentException("Non-finite Niagara Physics Asset primitive");
        }
    }
}
