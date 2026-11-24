package com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom;

import com.kltyton.lumenfx.api.client.groom.NiagaraGroomInputs.Group;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.jspecify.annotations.Nullable;

/** Per-step render proxy data. Persistent guide and bounds buffers are owned by the render thread. */
public final class HairStrandsFrame {
    private static final byte[] IDENTITY = matrix(new Matrix4f());
    private static final byte[] IDENTITY_ROTATION = floats(0, 0, 0, 1);
    private static final byte[] ZERO_VECTOR = vector(Vec3.ZERO);

    private final @Nullable Group group;
    private final boolean reset;
    private final byte[] world;
    private final byte[] worldInverse;
    private final byte[] worldRotation;
    private final byte[] bone;
    private final byte[] boneInverse;
    private final byte[] boneRotation;
    private final GroomTransforms.Motion motion;

    HairStrandsFrame(@Nullable Group group, GroomTransforms transforms, boolean reset) {
        this.group = group == null || group.nanite() ? null : group;
        this.reset = reset;
        motion = transforms.motion();
        if (this.group == null) {
            world = worldInverse = bone = boneInverse = IDENTITY;
            worldRotation = boneRotation = IDENTITY_ROTATION;
        } else {
            var worldMatrix = new Matrix4f(transforms.world());
            var boneMatrix = new Matrix4f(transforms.bone());
            world = matrix(worldMatrix);
            worldInverse = matrix(inverse(worldMatrix));
            worldRotation = rotation(transforms.world());
            bone = matrix(boneMatrix);
            boneInverse = matrix(inverse(boneMatrix));
            boneRotation = rotation(transforms.bone());
        }
    }

    public @Nullable Group group() { return group; }
    public boolean reset() { return reset; }

    public @Nullable byte[] value(String field) {
        var guides = group == null ? null : group.guides();
        var roots = group == null ? null : group.roots();
        return switch (field) {
            case "WorldTransform" -> world;
            case "WorldInverse" -> worldInverse;
            case "WorldRotation" -> worldRotation;
            case "BoneTransform" -> bone;
            case "BoneInverse" -> boneInverse;
            case "BoneRotation" -> boneRotation;
            case "NumStrands" -> integer(guides == null ? 1 : guides.curves());
            case "StrandSize" -> integer(group == null ? 1 : group.physics().strandSize());
            case "InterpolationMode" -> integer(roots == null ? 0 : group.globalInterpolation() ? 2 : 1);
            case "RestRootOffset", "DeformedRootOffset" -> ZERO_VECTOR;
            case "RestPositionOffset" -> guides == null ? ZERO_VECTOR : vector(guides.restOffset());
            case "SampleCount" -> integer(roots == null ? 0 : roots.sampleCount());
            case "LocalSimulation" -> integer(group != null && group.localSimulation() ? 1 : 0);
            case "RBFLocalSpace" -> integer(group != null && group.rbfLocalSpace() ? 1 : 0);
            case "BoneLinearVelocity" -> group == null ? ZERO_VECTOR : vector(motion.linear());
            case "BoneAngularVelocity" -> group == null ? ZERO_VECTOR : vector(motion.angular());
            case "BoneLinearAcceleration" -> group == null ? ZERO_VECTOR : vector(motion.linearAcceleration());
            case "BoneAngularAcceleration" -> group == null ? ZERO_VECTOR : vector(motion.angularAcceleration());
            default -> null;
        };
    }

    private static Matrix4f inverse(Matrix4f value) {
        return value.determinant() == 0 ? new Matrix4f() : new Matrix4f(value).invert();
    }

    private static byte[] rotation(Matrix4dc value) {
        var source = new SceneComponentTransform();
        source.source(value);
        var q = source.rotation(new Quaterniond());
        return floats(q.x, q.y, q.z, q.w);
    }

    private static byte[] matrix(Matrix4f value) {
        var bytes = bytes(16);
        bytes.asFloatBuffer().put(value.get(new float[16]));
        return bytes.array();
    }

    private static byte[] vector(Vec3 value) { return floats(value.x, value.y, value.z); }
    private static byte[] integer(int value) { return bytes(1).putInt(value).array(); }
    private static byte[] floats(double... values) {
        var bytes = bytes(values.length);
        for (double value : values) bytes.putFloat((float) value);
        return bytes.array();
    }
    private static ByteBuffer bytes(int words) { return ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN); }
}
