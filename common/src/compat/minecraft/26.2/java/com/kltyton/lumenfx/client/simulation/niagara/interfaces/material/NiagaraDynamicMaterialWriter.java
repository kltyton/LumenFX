package com.kltyton.lumenfx.client.simulation.niagara.interfaces.material;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.Vector;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** VectorVM captures one target before dispatch, then writes its first lane on the game thread. */
public final class NiagaraDynamicMaterialWriter {
    public record Definition(String binding, String defaultMaterial) {
        public static Definition read(JsonObject value) {
            return new Definition(value.get("binding").getAsString(), value.get("default").getAsString());
        }
    }

    private final Definition definition;
    private volatile WeakReference<DynamicMaterialValues> target = new WeakReference<>(null);

    public NiagaraDynamicMaterialWriter(Definition definition) { this.definition = definition; }
    public Definition definition() { return definition; }

    public void capture(@Nullable DynamicMaterialValues destination) {
        if (target.get() != destination) target = new WeakReference<>(destination);
    }

    public static @Nullable DynamicMaterialValues host(@Nullable RendererParameter value) {
        if (value instanceof RendererParameter.Material material && material.value() != null)
            return material.value().instanceParameters().dynamicValues();
        if (!(value instanceof RendererParameter.MaterialVariants variants)) return null;
        DynamicMaterialValues shared = null;
        for (var variant : variants.values().values()) if (variant.value() != null) {
            var current = variant.value().instanceParameters().dynamicValues();
            if (current == null) return null;
            if (shared != null && shared != current)
                throw new IllegalArgumentException("Niagara renderer variants refer to different dynamic materials");
            shared = current;
        }
        return shared;
    }

    public void setScalar(String name, int bits) {
        var destination = target;
        if (destination.get() != null) dispatch(() -> {
            var material = destination.get();
            if (material != null) material.setScalar(name, Float.intBitsToFloat(bits));
        });
    }

    public void setVector(String name, int x, int y, int z, int w) {
        var destination = target;
        if (destination.get() != null) dispatch(() -> {
            var material = destination.get();
            if (material != null) material.setVector(name, new Vector(Float.intBitsToFloat(x), Float.intBitsToFloat(y),
                    Float.intBitsToFloat(z), Float.intBitsToFloat(w)));
        });
    }

    private static void dispatch(Runnable write) {
        var client = Minecraft.getInstance();
        if (client != null) client.schedule(write);
        else write.run();
    }
}
