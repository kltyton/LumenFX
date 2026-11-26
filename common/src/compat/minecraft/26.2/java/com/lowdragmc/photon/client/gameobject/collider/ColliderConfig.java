/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.collider;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;

/** Pure-data collider config; timeline slots live on the co-located {@link Runtime}. */
@Getter
@Setter
public class ColliderConfig implements IPersistedSerializable {

    public enum Shape {
        /** an infinite plane through the object, facing its local +Y; solid from the front only */
        Plane,
        Box,
        Sphere
    }

    @Persisted
    protected Shape shape = Shape.Plane;
    @Persisted
    protected Vector3f size = new Vector3f(1, 1, 1);
    @Persisted
    protected float radius = 0.5f;
    @Persisted
    protected boolean trigger = false;

    public Shape getShape() {
        return shape == null ? Shape.Plane : shape;
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        public final RuntimeValue<Float> radius;
        public final RuntimeValue<Boolean> trigger;

        public Runtime(ColliderConfig config) {
            this.radius = new RuntimeValue<>(config::getRadius);
            this.trigger = new RuntimeValue<>(config::isTrigger);
        }

        public void clear() {
            radius.clear();
            trigger.clear();
        }
    }
}
