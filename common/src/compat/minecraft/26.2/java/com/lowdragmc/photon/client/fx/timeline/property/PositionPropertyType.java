/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.fx.timeline.property;

import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.FXObject;
import org.joml.Vector3f;

public class PositionPropertyType extends TransformPropertyType {
    @LDLRegisterClient(name = "position", registry = "photon:animated_property")
    public static final PositionPropertyType INSTANCE = new PositionPropertyType();

    @Override
    public float[] capture(FXObject target) {
        var p = target.transform().localPosition();
        return new float[]{p.x, p.y, p.z};
    }

    @Override
    public void apply(FXObject target, float[] values) {
        target.transform().localPosition(new Vector3f(values[0], values[1], values[2]));
    }
}
