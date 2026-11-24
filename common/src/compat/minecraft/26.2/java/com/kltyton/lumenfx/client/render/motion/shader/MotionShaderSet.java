package com.kltyton.lumenfx.client.render.motion.shader;

import com.mojang.blaze3d.shaders.ShaderSource;
import java.util.Objects;
import net.minecraft.resources.Identifier;

public record MotionShaderSet(Identifier vertexShader, Identifier fragmentShader, ShaderSource source) {
    public MotionShaderSet {
        Objects.requireNonNull(vertexShader, "vertexShader");
        Objects.requireNonNull(fragmentShader, "fragmentShader");
        Objects.requireNonNull(source, "source");
    }
}
