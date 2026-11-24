package com.kltyton.lumenfx.client.render.temporal.geometry;

import net.minecraft.client.CameraType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;
import com.kltyton.lumenfx.client.render.scene.material.FirstPersonMaterialView;

/** Actual render-view history, independent of the emitter's simulation tick history. */
public final class ParticleMotionView {
    public record Frame(Vec3 camera, Quaternionfc orientation, Matrix4fc viewProjection,
                        double realTime, double engineTime, @Nullable Matrix4fc projection, @Nullable Matrix4fc modelView,
                        @Nullable FirstPersonMaterialView firstPerson, long renderFrame, @Nullable CameraType cameraType) {
        public Frame(Vec3 camera, Quaternionfc orientation, Matrix4fc viewProjection, double realTime, double engineTime,
                     @Nullable Matrix4fc projection, @Nullable Matrix4fc modelView, @Nullable FirstPersonMaterialView firstPerson) {
            this(camera, orientation, viewProjection, realTime, engineTime, projection, modelView, firstPerson, 0, null);
        }
        public Frame(Vec3 camera, Quaternionfc orientation, Matrix4fc viewProjection, double realTime, double engineTime,
                     @Nullable Matrix4fc projection, @Nullable Matrix4fc modelView) {
            this(camera, orientation, viewProjection, realTime, engineTime, projection, modelView, null);
        }
        public Frame(Vec3 camera, Quaternionfc orientation, Matrix4fc viewProjection, double realTime, double engineTime,
                     @Nullable Matrix4fc projection) {
            this(camera, orientation, viewProjection, realTime, engineTime, projection, null);
        }
        public Frame(Vec3 camera, Quaternionfc orientation, Matrix4fc viewProjection, double realTime, double engineTime) {
            this(camera, orientation, viewProjection, realTime, engineTime, null);
        }
        public Frame(Vec3 camera, Quaternionfc orientation, Matrix4fc viewProjection) {
            this(camera, orientation, viewProjection, Double.NaN, Double.NaN);
        }
        public Frame {
            if (!(Double.isFinite(realTime) && Double.isFinite(engineTime))
                    && !(Double.isNaN(realTime) && Double.isNaN(engineTime)))
                throw new IllegalArgumentException("Motion frame clocks must be finite or both unspecified");
            orientation = new Quaternionf(orientation);
            viewProjection = new Matrix4f(viewProjection);
            if (projection != null) projection = new Matrix4f(projection);
            if (modelView != null) modelView = new Matrix4f(modelView);
        }
    }
    public record Snapshot(Frame current, Frame previous, boolean historyValid) {
        public Vec3 cameraVelocity() {
            if (!historyValid || current.cameraType() != previous.cameraType()
                    || current.renderFrame() != previous.renderFrame() + 1) return Vec3.ZERO;
            double seconds = current.engineTime() - previous.engineTime();
            return seconds > 0 ? current.camera().subtract(previous.camera()).scale(1 / seconds) : Vec3.ZERO;
        }
    }
    private Object world, cameraOwner;
    private long cut;
    private Frame previous;

    public Snapshot capture(Object world, Object cameraOwner, long cut, Vec3 camera, Quaternionfc orientation,
                            Matrix4fc projection, Matrix4fc modelView) {
        return capture(world, cameraOwner, cut, camera, orientation, projection, modelView, Double.NaN, Double.NaN);
    }

    public Snapshot capture(Object world, Object cameraOwner, long cut, Vec3 camera, Quaternionfc orientation,
                            Matrix4fc projection, Matrix4fc modelView, double realTime, double engineTime) {
        return capture(world, cameraOwner, cut, camera, orientation, projection, modelView, realTime, engineTime, null);
    }

    public Snapshot capture(Object world, Object cameraOwner, long cut, Vec3 camera, Quaternionfc orientation,
                            Matrix4fc projection, Matrix4fc modelView, double realTime, double engineTime,
                            @Nullable FirstPersonMaterialView firstPerson) {
        return capture(world, cameraOwner, cut, camera, orientation, projection, modelView, realTime, engineTime, firstPerson, 0, null);
    }

    public Snapshot capture(Object world, Object cameraOwner, long cut, Vec3 camera, Quaternionfc orientation,
                            Matrix4fc projection, Matrix4fc modelView, double realTime, double engineTime,
                            @Nullable FirstPersonMaterialView firstPerson, long renderFrame, @Nullable CameraType cameraType) {
        if (this.world != world || this.cameraOwner != cameraOwner || this.cut != cut) previous = null;
        this.world = world; this.cameraOwner = cameraOwner; this.cut = cut;
        Frame current = new Frame(camera, orientation, new Matrix4f(projection).mul(modelView), realTime, engineTime,
                projection, modelView, firstPerson, renderFrame, cameraType);
        return new Snapshot(current, previous == null ? current : previous, previous != null);
    }

    public void commit(Snapshot frame, boolean holdHistory) { if (!holdHistory) previous = frame.current(); }
    public void reset() { world = cameraOwner = null; previous = null; }
}
