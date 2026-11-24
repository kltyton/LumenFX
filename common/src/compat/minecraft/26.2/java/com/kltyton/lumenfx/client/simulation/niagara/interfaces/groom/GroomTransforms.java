package com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom;

import com.kltyton.lumenfx.api.client.groom.NiagaraGroomInputs.Group;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;

final class GroomTransforms {
    record Motion(Vec3 linear, Vec3 angular, Vec3 linearAcceleration, Vec3 angularAcceleration) {
        private static final Motion ZERO = new Motion(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
    }

    private final Matrix4d previous = new Matrix4d();
    private Vec3 previousLinear = Vec3.ZERO;
    private Vec3 previousAngular = Vec3.ZERO;

    private final SceneComponentTransform component = new SceneComponentTransform();
    private final SceneComponentTransform bone = new SceneComponentTransform();
    private final SceneComponentTransform relativeBone = new SceneComponentTransform();
    private final SceneComponentTransform rigid = new SceneComponentTransform();
    private final SceneComponentTransform rendered = new SceneComponentTransform();
    private final SceneComponentTransform worldBone = new SceneComponentTransform();

    private final Matrix4d world = new Matrix4d();
    private Motion motion = Motion.ZERO;

    void update(Group group, boolean reset, float delta) {
        source(component, group.componentTransform());
        source(bone, group.simulationBone());
        motion = !reset && delta != 0 ? differentiate(group, delta) : Motion.ZERO;
        previous.set(bone.matrix());
        previousLinear = motion.linear();
        previousAngular = motion.angular();

        relativeBone.source(inverse(component.matrix()).mul(bone.matrix()));
        source(rigid, group.rigidWorld());
        worldBone.relative(translation(relativeBone.matrix()), relativeBone.rotation(new Quaterniond()),
                relativeBone.scale(new Vector3d()), rigid);

        source(rendered, group.renderedWorld());
        if (group.localSimulation()) world.set(inverse(worldBone.matrix())).mul(rendered.matrix());
        else world.set(rendered.matrix());
    }

    void clear() {
        var position = new Vector3d();
        component.source(position, new Quaterniond(), new Vector3d(1));
        world.identity();
        previous.identity();
        previousLinear = Vec3.ZERO;
        previousAngular = Vec3.ZERO;
        motion = Motion.ZERO;
    }

    Matrix4dc component() { return component.matrix(); }
    Matrix4dc world() { return world; }
    Matrix4dc bone() { return worldBone.matrix(); }
    Motion motion() { return motion; }

    static Matrix4d inverse(Matrix4dc source) {
        var result = new Matrix4d();
        if (source.determinant() != 0) result.set(source).invert();
        return result;
    }

    private Motion differentiate(Group group, float delta) {
        var transform = new SceneComponentTransform();
        transform.source(inverse(previous).mul(bone.matrix()));
        var rotation = transform.rotation(new Quaterniond());
        double w = Math.clamp(rotation.w, -1, 1);
        double sine = Math.sqrt(Math.max(0, 1 - w * w));
        var axis = sine >= 1.0e-4 ? new Vector3d(rotation.x, rotation.y, rotation.z).div(sine) : new Vector3d(1, 0, 0);
        double angle = 2 * Math.acos(w);
        var linear = vector(translation(transform.matrix())).scale(Math.clamp(group.linearVelocityScale(), 0, 1) / delta);
        var angular = vector(bone.matrix().transformDirection(axis.mul(angle * Math.clamp(group.angularVelocityScale(), 0, 1))))
                .scale(1 / delta);
        return new Motion(linear, angular, linear.subtract(previousLinear).scale(1 / delta),
                angular.subtract(previousAngular).scale(1 / delta));
    }

    private static void source(SceneComponentTransform target, Transform input) {
        target.source(new Vector3d(input.x(), input.y(), input.z()),
                new Quaterniond(input.qx(), input.qy(), input.qz(), input.qw()).normalize(),
                new Vector3d(input.scaleX(), input.scaleY(), input.scaleZ()));
    }

    private static Vector3d translation(Matrix4dc matrix) {
        return new Vector3d(matrix.m30(), matrix.m31(), matrix.m32());
    }

    private static Vec3 vector(Vector3d value) {
        return new Vec3(value.x, value.y, value.z);
    }
}
