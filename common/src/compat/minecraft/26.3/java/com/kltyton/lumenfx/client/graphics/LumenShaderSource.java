package com.kltyton.lumenfx.client.graphics;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Functional shader source bridge for MC 26.3 RenderPearl that preserves the single-method
 * {@link #get(Identifier, ShaderType)} contract while delegating {@code #moj_import} include
 * resolution to the active {@link net.minecraft.client.renderer.ShaderManager} configuration.
 */
@FunctionalInterface
public interface LumenShaderSource extends ShaderSource {
    @Nullable String get(Identifier id, ShaderType type);

    @Override
    default @Nullable String getShader(Identifier id, ShaderType type) {
        return get(id, type);
    }

    @Override
    default @Nullable CachedIncludeSource getInclude(Identifier id) {
        return RenderPearlCompat.getInclude(id);
    }

    @Override
    default void close() {
        // Host include sources remain owned by ShaderManager's active Configs.
    }
}
