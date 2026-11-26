/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.aratrail;
import com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.lowdragmc.photon.client.gameobject.emitter.data.ValueSpace;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;

/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote PhysicsSetting
 */
@Setter
@Getter
public class AraPhysicsSetting extends ToggleGroup {
    @Persisted
    public float warmup = 0;               /**< simulation warmup seconds.*/
    @Persisted
    public Vector3f gravity = new Vector3f();  /**< gravity applied to the trail, in {@link #gravitySpace} axes. */
    @Persisted
    public ValueSpace gravitySpace = ValueSpace.World;
    @Persisted
    public final TransformRef gravityCustomSpace = new TransformRef();
    @Persisted
    public float inertia = 0;               /**< amount of GameObject velocity transferred to the trail.*/
    @Persisted
    public float velocitySmoothing = 0.75f;     /**< velocity smoothing amount.*/
    @Persisted
    public float damping = 0.75f;               /**< velocity damping amount.*/
    @Persisted
    public boolean collideWithBlocks = false;
    @Persisted
    public float collisionRadius = 0.05f;
    @Persisted
    public float collisionBounce = 0;
    @Persisted
    public float collisionFriction = 0.5f;

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime layer holding timeline-overridable slots over the immutable physics config. */
    public static class Runtime {
        private final AraPhysicsSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<Float> warmup;
        public final RuntimeValue<Vector3f> gravity;   // slot only (Vector3f → no timeline binding)
        public final RuntimeValue<ValueSpace> gravitySpace; // slot only (enum → no timeline binding)
        public final RuntimeValue<Float> inertia;
        public final RuntimeValue<Float> velocitySmoothing;
        public final RuntimeValue<Float> damping;
        public final RuntimeValue<Boolean> collideWithBlocks;

        public Runtime(AraPhysicsSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.warmup = new RuntimeValue<>(() -> config.warmup);
            this.gravity = new RuntimeValue<>(() -> config.gravity);
            this.gravitySpace = new RuntimeValue<>(() -> config.gravitySpace);
            this.inertia = new RuntimeValue<>(() -> config.inertia);
            this.velocitySmoothing = new RuntimeValue<>(() -> config.velocitySmoothing);
            this.damping = new RuntimeValue<>(() -> config.damping);
            this.collideWithBlocks = new RuntimeValue<>(() -> config.collideWithBlocks);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public boolean collides() {
            return enable.get() && collideWithBlocks.get();
        }

        public AraPhysicsSetting config() {
            return config;
        }

        public void clear() {
            enable.clear();
            warmup.clear();
            gravity.clear();
            gravitySpace.clear();
            inertia.clear();
            velocitySmoothing.clear();
            damping.clear();
            collideWithBlocks.clear();
        }
    }
}
