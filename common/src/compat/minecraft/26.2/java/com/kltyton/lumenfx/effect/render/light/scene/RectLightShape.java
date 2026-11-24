package com.kltyton.lumenfx.effect.render.light.scene;

import com.kltyton.lumenfx.math.SourceFloatMath;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Immutable source dimensions, barn doors and emitter texture mapping. */
public record RectLightShape(float halfWidth, float halfHeight, float barnCosine, float barnLength,
                             float lightFunctionTangent, float scaleX, float scaleY, float offsetX, float offsetY,
                             @Nullable Identifier texture) {
    public static RectLightShape fromSource(float width, float height, float barnAngle, float barnLength,
                                           float lightFunctionCone, float scaleX, float scaleY, float offsetX, float offsetY,
                                           @Nullable Identifier texture) {
        float angle = SourceFloatMath.clamp(barnAngle, 0f, 88f) * ((float)Math.PI / 180f);
        float lightFunctionTangent = lightFunctionCone > 0f
                ? (float)Math.tan(SourceFloatMath.clamp(lightFunctionCone, 0f, 89f) * (float)Math.PI / 180f) : 0f;
        return new RectLightShape(SourceFloatMath.max(1f, width) * .5f, SourceFloatMath.max(1f, height) * .5f,
                (float)Math.cos(angle), SourceFloatMath.max(.1f, barnLength), lightFunctionTangent,
                SourceFloatMath.clamp(scaleX, 0f, 1f), SourceFloatMath.clamp(scaleY, 0f, 1f),
                SourceFloatMath.clamp(offsetX, 0f, 1f), SourceFloatMath.clamp(offsetY, 0f, 1f), texture);
    }

    public float areaNormalization() { return 2f * halfWidth * halfHeight; }
}
