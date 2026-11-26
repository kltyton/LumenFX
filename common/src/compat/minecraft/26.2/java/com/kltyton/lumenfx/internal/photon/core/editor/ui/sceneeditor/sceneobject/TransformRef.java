package com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject;

import com.kltyton.lumenfx.internal.photon.core.math.Transform;
import com.lowdragmc.photon.utils.PhotonValueSerializable;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.UUID;

@ParametersAreNonnullByDefault
public class TransformRef implements PhotonValueSerializable {
    @Nullable
    @Getter
    @Setter
    private UUID transformId = null;

    public TransformRef() { }

    public TransformRef(@Nullable Transform transform) {
        this.transformId = transform == null ? null : transform.id();
    }

    public TransformRef(UUID transformId) {
        this.transformId = transformId;
    }

    public void setTransform(Transform transform) {
        this.transformId = transform.id();
    }

    @Nullable
    public Transform getTransform(@Nullable IScene scene) {
        if (transformId == null || scene == null) return null;
        var obj = scene.getSceneObject(transformId);
        return obj != null ? obj.transform() : null;
    }

    @Override
    public void serialize(ValueOutput valueOutput) {
        if (transformId != null) {
            valueOutput.putString("uuid", transformId.toString());
        }
    }

    @Override
    public void deserialize(ValueInput valueInput) {
        transformId = null;
        valueInput.getString("uuid").ifPresent(uuid -> {
            try {
                transformId = UUID.fromString(uuid);
            } catch (Exception ignored) {
                transformId = null;
            }
        });
    }

    @Override
    public String toString() {
        return transformId == null ? "null" : transformId.toString();
    }
}
