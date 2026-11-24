package com.kltyton.lumenfx.api.client.component;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Creates one client-thread scene object from an independently exported template. */
@FunctionalInterface
public interface ComponentFactory {
    int LIGHTING = 1, DECALS = 2, GEOMETRY = 4;
    record Context(ClientLevel level, EffectHandle.Input input, boolean localSpace, Vec3 simulationOrigin, ComponentOwner owner) {
        public Context(ClientLevel level, EffectHandle.Input input, boolean localSpace, Vec3 simulationOrigin) {
            this(level, input, localSpace, simulationOrigin,
                    com.kltyton.lumenfx.client.component.lifecycle.ComponentWorlds.createOwner(level));
        }
    }
    @FunctionalInterface
    interface Prepared { EffectComponent create(Context context); }

    EffectComponent create(Context context, JsonObject template);

    /** Scene attachments must be captured before these components are created or updated. */
    default int sceneOutputs() { return 0; }

    /** Resource preparation freezes a template; every created object owns independent mutable state. */
    default Prepared prepare(JsonObject template) {
        JsonObject frozen = template.deepCopy();
        return context -> create(context, frozen.deepCopy());
    }
}
