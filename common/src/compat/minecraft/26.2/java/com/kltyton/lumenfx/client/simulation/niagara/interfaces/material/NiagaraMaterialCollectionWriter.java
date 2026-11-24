package com.kltyton.lumenfx.client.simulation.niagara.interfaces.material;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionInstance;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionWorlds;
import com.kltyton.lumenfx.client.resource.material.MaterialCollectionJson;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition.Vector;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Resolves the world-owned collection on the host thread before VectorVM submits writes. */
public final class NiagaraMaterialCollectionWriter {
    public record Definition(String binding, @Nullable MaterialCollectionDefinition defaultCollection) {
        public static Definition read(JsonObject source) {
            var defaults = source.get("default");
            return new Definition(source.get("binding").getAsString(), defaults == null || defaults.isJsonNull()
                    ? null : MaterialCollectionJson.read(defaults.getAsJsonObject()));
        }
    }

    private final Definition definition;
    private volatile WeakReference<MaterialCollectionInstance> target = new WeakReference<>(null);

    public NiagaraMaterialCollectionWriter(Definition definition) { this.definition = definition; }
    public String binding() { return definition.binding(); }

    public void capture(@Nullable ClientLevel world, @Nullable NiagaraParameterStore parameters, @Nullable Object override) {
        if (world == null) {
            if (target.get() != null) target = new WeakReference<>(null);
            return;
        }
        Object value = override;
        if (value == null && !definition.binding().isEmpty() && parameters != null)
            value = parameters.objectBinding(definition.binding()).value();
        if (value instanceof MaterialCollectionInstance direct) {
            setTarget(direct);
            return;
        }
        var collection = value instanceof MaterialCollectionDefinition bound ? bound : NiagaraParameterStoreSources.materialCollectionAsset(value);
        if (collection == null) collection = definition.defaultCollection();
        if (collection != null) setTarget(MaterialCollectionWorlds.instance(world, collection));
    }

    private void setTarget(MaterialCollectionInstance instance) {
        if (target.get() != instance) target = new WeakReference<>(instance);
    }

    public void setScalar(String name, int bits) {
        var destination = target;
        if (destination.get() != null) Minecraft.getInstance().schedule(() -> {
            var collection = destination.get();
            if (collection != null) collection.setScalar(name, Float.intBitsToFloat(bits));
        });
    }

    public void setVector(String name, int x, int y, int z, int w) {
        var destination = target;
        if (destination.get() != null) Minecraft.getInstance().schedule(() -> {
            var collection = destination.get();
            if (collection != null) collection.setVector(name, new Vector(x, y, z, w));
        });
    }
}
