package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.client.material.collection.MaterialCollectionInstance;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionWorlds;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Scalar and linear-color MPC values belong to a world and preserve the source collection's typed lookup. */
public final class MaterialParameterCollections {
    private MaterialParameterCollections() { }

    public static boolean setScalar(ClientLevel world, MaterialCollectionDefinition definition, String name, float value) {
        return MaterialCollectionWorlds.instance(world, definition).setScalar(name, value);
    }
    public static boolean setVector(ClientLevel world, MaterialCollectionDefinition definition, String name,
                                    float red, float green, float blue, float alpha) {
        return MaterialCollectionWorlds.instance(world, definition).setVector(name, MaterialCollectionDefinition.Vector.of(red, green, blue, alpha));
    }
    public static @Nullable Float scalar(ClientLevel world, MaterialCollectionDefinition definition, String name) {
        Integer value = snapshot(world, definition).scalar(name);
        return value == null ? null : Float.intBitsToFloat(value);
    }
    public static MaterialCollectionDefinition.@Nullable Vector vector(ClientLevel world, MaterialCollectionDefinition definition, String name) {
        return snapshot(world, definition).vector(name);
    }
    public static void reset(ClientLevel world, MaterialCollectionDefinition definition) {
        MaterialCollectionWorlds.instance(world, definition).reset();
    }
    public static MaterialCollectionInstance.Snapshot snapshot(ClientLevel world, MaterialCollectionDefinition definition) {
        return MaterialCollectionWorlds.instance(world, definition).snapshot();
    }
}
