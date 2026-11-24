package com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs.Socket;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.render.math.NativeSceneMath;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.LocalTransform;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Vector3d;

final class NiagaraSocketTransforms {
    static final Transform IDENTITY = new Transform(0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
    static final int WORDS = 10;
    private NiagaraSocketTransforms() { }

    static Transform owner(Pose pose) {
        return new Transform(pose.position().x * 100, pose.position().z * 100, pose.position().y * 100,
                -pose.x(), -pose.z(), -pose.y(), pose.w(), pose.scaleX(), pose.scaleZ(), pose.scaleY());
    }

    static Transform translated(Transform value, double x, double y, double z) {
        return new Transform((float)(value.x() - x), (float)(value.y() - y), (float)(value.z() - z),
                (float)value.qx(), (float)value.qy(), (float)value.qz(), (float)value.qw(),
                (float)value.scaleX(), (float)value.scaleY(), (float)value.scaleZ());
    }

    static Transform compose(Transform local, Transform component) {
        var parent = new SceneComponentTransform();
        parent.source(position(component), rotation(component), scale(component));
        var child = new SceneComponentTransform();
        child.relative(position(local), rotation(local), scale(local), parent);
        var matrix = child.matrix();
        var rotation = child.rotation(new Quaterniond());
        var scale = child.scale(new Vector3d());
        return new Transform((float)matrix.m30(), (float)matrix.m31(), (float)matrix.m32(),
                (float)rotation.x, (float)rotation.y, (float)rotation.z, (float)rotation.w,
                (float)scale.x, (float)scale.y, (float)scale.z);
    }

    static List<Socket> asset(StaticMeshAsset mesh) {
        var result = new ArrayList<Socket>();
        var rotation = new double[4];
        for (var socket : mesh.sampling().sockets()) {
            var angles = socket.pitchYawRoll();
            NativeSceneMath.rotatorQuaternion(angles.x, angles.y, angles.z, rotation);
            var p = socket.location();
            var s = socket.scale();
            result.add(new Socket(socket.name(), new Transform(p.x, p.y, p.z,
                    rotation[0], rotation[1], rotation[2], rotation[3], s.x, s.y, s.z)));
        }
        return List.copyOf(result);
    }

    static List<Socket> asset(SkeletalMeshAsset mesh, List<LocalTransform> poses) {
        var sockets = new ArrayList<Socket>();
        for (int i = 0; i < mesh.bones().size(); i++) {
            var bone = mesh.bones().get(i);
            sockets.add(new Socket(bone.name(), local(poses.isEmpty() ? bone.componentReference() : poses.get(i))));
        }
        for (var socket : mesh.sockets()) {
            int bone = mesh.bone(socket.bone());
            Transform value = local(socket.relative());
            if (bone >= 0) value = compose(value, sockets.get(bone).local());
            sockets.add(new Socket(socket.name(), value));
        }
        return List.copyOf(sockets);
    }

    private static Transform local(LocalTransform value) {
        var p = value.location();
        var s = value.scale();
        return new Transform(p.x, p.y, p.z, value.qx(), value.qy(), value.qz(), value.qw(), s.x, s.y, s.z);
    }

    static void write(ByteBuffer output, Transform transform) {
        output.putFloat((float)transform.x()).putFloat((float)transform.y()).putFloat((float)transform.z());
        output.putFloat((float)transform.qx()).putFloat((float)transform.qy()).putFloat((float)transform.qz()).putFloat((float)transform.qw());
        output.putFloat((float)transform.scaleX()).putFloat((float)transform.scaleY()).putFloat((float)transform.scaleZ());
    }

    static Vector3d position(Transform value) { return new Vector3d(value.x(), value.y(), value.z()); }
    static Quaterniond rotation(Transform value) { return new Quaterniond(value.qx(), value.qy(), value.qz(), value.qw()); }
    static Vector3d scale(Transform value) { return new Vector3d(value.scaleX(), value.scaleY(), value.scaleZ()); }
}
