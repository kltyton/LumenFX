package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionWorlds;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** World instances publish numeric and texture-object inputs without exposing mutable stores to workers. */
public final class NiagaraParameterCollections {
    private NiagaraParameterCollections() { }

    public static void set(ClientLevel level, String collection, String name, String sourceType, EffectHandle.ParameterValue value) {
        NiagaraCollectionWorlds.set(level, collection, name, sourceType, value);
    }
    public static void setTexture(ClientLevel level, String collection, String name, String sourceType, @Nullable TextureParameter value) {
        NiagaraCollectionWorlds.setTexture(level, collection, name, sourceType, value);
    }
    /** Publishes a prepared reference for a UObject parameter. Null is a bound null. */
    public static void setObject(ClientLevel level, String collection, String name, String sourceType, @Nullable ObjectValue value) {
        NiagaraCollectionWorlds.setObject(level, collection, name, sourceType, value);
    }
    public static void remove(ClientLevel level, String collection, String name, String sourceType) {
        NiagaraCollectionWorlds.remove(level, collection, name, sourceType);
    }
    public static void reset(ClientLevel level, String collection) {
        NiagaraCollectionWorlds.reset(level, collection);
    }
    public static ParameterCollectionValues snapshot(@Nullable ClientLevel level) {
        return NiagaraCollectionWorlds.snapshot(level);
    }
    public static void clearWorld(ClientLevel level) { NiagaraCollectionWorlds.clearWorld(level); }
    public static void clearWorlds() { NiagaraCollectionWorlds.clearWorlds(); }
}
