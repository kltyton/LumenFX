package com.kltyton.lumenfx.api.client.component;

import com.kltyton.lumenfx.api.client.EffectHandle;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Client-thread scene object with explicit lifecycle and typed property/setter access. */
public interface EffectComponent extends AutoCloseable {
    interface Primitive extends EffectComponent {
        void setOwnerVisibility(PrimitiveOwnerVisibility visibility);
    }

    interface Property {
        ComponentValue.Kind type();
        void set(ComponentValue value);
    }

    interface Setter<R> {
        ComponentValue.Kind inputType();
        R invoke(ComponentValue value, Map<String, String> parameterDefaults);
    }

    boolean alive();
    boolean active();
    void updateAttachment(EffectHandle.Pose pose);
    void setVisible(boolean visible, boolean propagateToChildren);
    void activate(boolean reset);
    void deactivate();
    void resetSceneVelocity();
    void finishRenderFrame(boolean paused);
    @Nullable Property property(String path);
    @Nullable Setter<?> setter(String name);
    @Override void close();
}
