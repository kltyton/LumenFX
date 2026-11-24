package com.kltyton.lumenfx.client.compat.shaderpack;

import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import java.util.EnumSet;

@FunctionalInterface
public interface FrameListener {
    void onFrame(ShaderpackFrame frame);
    default void onOpaqueFrame(ShaderpackFrame frame) { }

    default void onBeforeComposite(ShaderpackFrame frame, EnumSet<TranslucencyPass> immediateStages, boolean moreStagesFollow) {
        onFrame(frame);
    }

    default void onCompositeStage(ShaderpackFrame frame, EnumSet<TranslucencyPass> stages, boolean finalStage) {
    }
}
