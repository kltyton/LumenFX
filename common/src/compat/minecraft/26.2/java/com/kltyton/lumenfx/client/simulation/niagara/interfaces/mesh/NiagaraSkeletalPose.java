package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalMeshDefinition.Skinning;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.LocalTransform;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Lod;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.SkinWeightBuffer;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** One immutable skeletal pose with source inverse binds and native 16-bit skin weights. */
final class NiagaraSkeletalPose {
    record Vertex(Vec3 position, Vec3 normal, Vec3 bitangent, Vec3 tangent) { }
    static final Vertex DEFAULT = new Vertex(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 1, 0), new Vec3(1, 0, 0));

    private final LocalTransform[] bones;
    private final Matrix4f[] component, skin;
    private final @Nullable Lod lod;
    private final @Nullable SkinWeightBuffer weightBuffer;
    private final Skinning mode;
    private final boolean reference;
    private volatile Vertex @Nullable [] preSkinned;

    NiagaraSkeletalPose(SkeletalMeshAsset mesh, @Nullable Lod lod, @Nullable SkinWeightBuffer weightBuffer,
                        List<LocalTransform> pose, Skinning mode) {
        this.lod = lod;
        this.weightBuffer = weightBuffer;
        this.mode = mode;
        reference = pose.isEmpty() || mode == Skinning.NONE;
        int count = mesh.bones().size();
        bones = new LocalTransform[count];
        component = new Matrix4f[count];
        skin = new Matrix4f[count];
        for (int index = 0; index < count; index++) {
            var bone = mesh.bones().get(index);
            bones[index] = reference ? bone.componentReference() : pose.get(index);
            component[index] = matrix(bones[index]);
            float[] inverse = bone.inverseBind();
            if (inverse != null) skin[index] = reference ? new Matrix4f()
                    : new Matrix4f(component[index]).mul(new Matrix4f().set(inverse));
        }
    }

    private NiagaraSkeletalPose(NiagaraSkeletalPose source, @Nullable SkinWeightBuffer weightBuffer) {
        bones = source.bones;
        component = source.component;
        skin = source.skin;
        lod = source.lod;
        mode = source.mode;
        reference = source.reference;
        this.weightBuffer = weightBuffer;
    }

    NiagaraSkeletalPose withWeights(@Nullable SkinWeightBuffer weights) {
        return weightBuffer == weights ? this : new NiagaraSkeletalPose(this, weights);
    }

    LocalTransform bone(int index) { return bones[index]; }
    Matrix4fc component(int index) { return component[index]; }
    Matrix4fc skinMatrix(int index) {
        Matrix4f value = skin[index];
        if (value == null) throw new IllegalArgumentException("Skeletal vertex references a virtual bone without a skin bind");
        return value;
    }
    Matrix4fc gpuSkinMatrix(int index) { return skin[index] == null ? component[index] : skin[index]; }
    int bones() { return bones.length; }
    int vertices() { return lod == null || !lod.cpuAccess() ? 0 : Objects.requireNonNull(lod.geometry()).vertexCount(); }

    Vertex vertex(int index) {
        return vertex(index, true);
    }

    /** The native GetSkinnedTriangleVertexData family binds without the mesh CPU-access gate. */
    Vertex vertexIgnoringCpuAccess(int index) {
        return vertex(index, false);
    }

    private Vertex vertex(int index, boolean requireCpuAccess) {
        int vertexCount = lod == null || requireCpuAccess && !lod.cpuAccess() ? 0 : lod.vertexCount();
        if (index < 0 || index >= vertexCount) return DEFAULT;
        if (mode != Skinning.PRE_SKIN || reference) return sample(index);
        var prepared = preSkinned;
        if (prepared == null) prepared = prepareVertices(vertexCount);
        return prepared[index];
    }

    private synchronized Vertex[] prepareVertices(int vertexCount) {
        var prepared = preSkinned;
        if (prepared == null) {
            prepared = new Vertex[vertexCount];
            for (int index = 0; index < prepared.length; index++) prepared[index] = sample(index);
            preSkinned = prepared;
        }
        return prepared;
    }

    private Vertex sample(int index) {
        var selected = Objects.requireNonNull(lod);
        var geometry = Objects.requireNonNull(selected.geometry());
        var bind = referenceVertex(geometry, index);
        if (reference) return bind;
        var weights = Objects.requireNonNull(weightBuffer).weights();
        var position = new Vector3f();
        var normal = new Vector3f();
        var bitangent = new Vector3f();
        var tangent = new Vector3f();
        var scratch = new Vector3f();
        for (int influence = 0; influence < weights.count(index); influence++) {
            int bone = selected.bone(weights, index, influence);
            if (bone < 0) continue;
            var matrix = skinMatrix(bone);
            float weight = weights.weight(index, influence) * (1f / 65535f);
            position.add(matrix.transformPosition(set(scratch, bind.position())).mul(weight));
            normal.add(matrix.transformDirection(set(scratch, bind.normal())).mul(weight));
            bitangent.add(matrix.transformDirection(set(scratch, bind.bitangent())).mul(weight));
            tangent.add(matrix.transformDirection(set(scratch, bind.tangent())).mul(weight));
        }
        return new Vertex(vector(position), vector(normal), vector(bitangent), vector(tangent));
    }

    static Vertex referenceVertex(StaticMeshGeometry geometry, int index) {
        var position = new Vec3(geometry.position(index, 0), geometry.position(index, 1), geometry.position(index, 2));
        var normal = new Vec3(geometry.normal(index, 0), geometry.normal(index, 1), geometry.normal(index, 2));
        var tangent = new Vec3(geometry.tangent(index, 0), geometry.tangent(index, 1), geometry.tangent(index, 2));
        return new Vertex(position, normal, normal.cross(tangent).scale(geometry.binormalSign(index)), tangent);
    }

    private static Vector3f set(Vector3f target, Vec3 value) { return target.set((float)value.x, (float)value.y, (float)value.z); }
    private static Vec3 vector(Vector3f value) { return new Vec3(value.x, value.y, value.z); }
    static Matrix4f matrix(LocalTransform value) {
        var p = value.location(); var s = value.scale();
        return new Matrix4f(NiagaraComponentMatrices.srt(value.qx(), value.qy(), value.qz(), value.qw(),
                s.x, s.y, s.z, p.x, p.y, p.z, new Matrix4d()));
    }
}
