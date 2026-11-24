package com.kltyton.lumenfx.client.render.scene.surface;

import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceException;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import java.util.Optional;

/** Affine material responses are generated only for the matched native RGB and fog equations. */
final class NativeSurfaceResponseContract {
    private NativeSurfaceResponseContract() { }

    static boolean isAffineBlend(Optional<BlendFunction> blend) {
        if (blend.isEmpty()) return true;
        var color = blend.get().color();
        return color.op() != BlendOp.MIN && color.op() != BlendOp.MAX
                && color.destFactor() != BlendFactor.DST_COLOR
                && color.destFactor() != BlendFactor.ONE_MINUS_DST_COLOR;
    }

    static void requireBlend(Optional<BlendFunction> blend) {
        if (!isAffineBlend(blend))
            throw new GlslSourceException("Native material response requires affine destination RGB blending: " + blend.get().color());
    }

    static void requireVertexLighting(String source) {
        function(source, "minecraft_compute_light", "return vec2(dot(lightDir0, normal), dot(lightDir1, normal));");
        function(source, "minecraft_mix_light_separate", """
                vec2 lightValue = max(vec2(0.0), light);
                float lightAccum = min(1.0, (lightValue.x + lightValue.y) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
                return vec4(color.rgb * lightAccum, color.a);
                """);
        function(source, "minecraft_mix_light", """
                vec2 light = minecraft_compute_light(lightDir0, lightDir1, normal);
                return minecraft_mix_light_separate(light, color);
                """);
    }

    static void require(String source, String body, String texel, boolean entity, boolean terrain) {
        String fogCall = "fragColor=apply_fog(color,sphericalVertexDistance,cylindricalVertexDistance,FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);";
        String normalized = compact(body).replace(texel, "T").replace(fogCall, "F;");
        String expected = entity ? """
                vec4 color = T;
                #ifdef ALPHA_CUTOUT
                if (color.a < ALPHA_CUTOUT) { discard; }
                #endif
                #ifdef PER_FACE_LIGHTING
                vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
                #else
                vec4 faceVertexColor = vertexColor;
                #endif
                #ifdef DISSOLVE
                if (faceVertexColor.a < texture(DissolveMaskSampler, texCoord0).a) { discard; }
                faceVertexColor.a = 1.0;
                #endif
                color *= faceVertexColor * ColorModulator;
                #ifndef NO_OVERLAY
                color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
                #endif
                #ifndef EMISSIVE
                color *= lightMapColor;
                #endif
                F;
                """ : terrain ? """
                vec4 color = T * vertexColor;
                color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
                #ifdef ALPHA_CUTOUT
                if (color.a < ALPHA_CUTOUT) { discard; }
                #endif
                F;
                """ : """
                vec4 color = T * vertexColor * ColorModulator;
                #ifdef ALPHA_CUTOUT
                if (color.a < ALPHA_CUTOUT) { discard; }
                #endif
                F;
                """;
        if (!normalized.equals(compact(expected))) throw new GlslSourceException("Native material RGB response contract changed");
        function(source, "apply_fog", """
                float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd);
                return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
                """);
        function(source, "total_fog_value", """
                return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
                """);
        function(source, "linear_fog_value", """
                if (vertexDistance <= fogStart) { return 0.0; } else if (vertexDistance >= fogEnd) { return 1.0; }
                return (vertexDistance - fogStart) / (fogEnd - fogStart);
                """);
    }

    static void requireSodium(String source, String body, String texel) {
        String fogCall = "fragColor=_linearFog(color,v_FragDistance,u_FogColor,u_EnvironmentFog,u_RenderFog,fadeFactor);";
        String normalized = compact(body).replace(texel, "T").replace(fogCall, "F;");
        String expected = """
                vec4 color = T;
                color *= v_Color;
                #ifdef ALPHA_CUTOUT
                if (color.a < ALPHA_CUTOUT) { discard; }
                #endif
                F;
                """;
        if (!normalized.equals(compact(expected))) throw new GlslSourceException("Sodium terrain RGB response contract changed");
        function(source, "_linearFog", """
                #ifdef USE_FOG
                float fogValue = max(1.0 - fadeFactor, total_fog_value(fragDistance.y, fragDistance.x, environmentFog.x, environmentFog.y, renderFog.x, renderFog.y));
                return vec4(mix(fragColor.rgb, fogColor.rgb, fogValue * fogColor.a), fragColor.a);
                #else
                return fragColor;
                #endif
                """);
        function(source, "total_fog_value", """
                return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
                """);
        function(source, "linear_fog_value", """
                if (vertexDistance <= fogStart) { return 0.0; } else if (vertexDistance >= fogEnd) { return 1.0; }
                return (vertexDistance - fogStart) / (fogEnd - fogStart);
                """);
    }

    private static void function(String source, String name, String expected) {
        var definitions = GlslSourceScanner.functions(source, name);
        if (definitions.size() != 1) throw new GlslSourceException("Native material fog function changed: " + name);
        var definition = definitions.getFirst();
        int start = source.indexOf('{', definition.start()) + 1;
        int end = source.lastIndexOf('}', definition.end() - 1);
        if (!compact(source.substring(start, end)).equals(compact(expected)))
            throw new GlslSourceException("Native material fog response changed: " + name);
    }

    private static String compact(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "").replaceAll("\\s+", "");
    }
}
