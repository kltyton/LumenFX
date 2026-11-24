package com.kltyton.lumenfx.client.render.scene.surface;

import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceException;
import java.util.regex.Pattern;

/** Separates native material tint from the vertex shader's interpolated lighting. */
final class NativeSurfaceResponseShader {
    private static final String P = SceneSurfaceShaderAdapter.PREFIX;

    private NativeSurfaceResponseShader() { }

    static String vertex(String source, boolean entity) {
        if (!entity) return replace(source, "sample_lightmap\\s*\\(\\s*Sampler2\\s*,\\s*UV2\\s*\\)",
                "(" + P + "vertexUnitFront = sample_lightmap(Sampler2, UV2))");
        NativeSurfaceResponseContract.requireVertexLighting(source);
        source = replace(source, "minecraft_mix_light_separate\\s*\\(\\s*-light\\s*,\\s*Color\\s*\\)",
                "((" + P + "vertexUnitBack = minecraft_mix_light_separate(-light, vec4(1.0))) * Color)");
        source = replace(source, "minecraft_mix_light_separate\\s*\\(\\s*light\\s*,\\s*Color\\s*\\)",
                "((" + P + "vertexUnitFront = minecraft_mix_light_separate(light, vec4(1.0))) * Color)");
        return replace(source, "minecraft_mix_light\\s*\\(\\s*Light0_Direction\\s*,\\s*Light1_Direction\\s*,\\s*Normal\\s*,\\s*Color\\s*\\)",
                "((" + P + "vertexUnitFront = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, vec4(1.0))) * Color)");
    }

    static String sodiumVertex(String source) {
        return replace(source, "texture\\s*\\(\\s*u_LightTex\\s*,\\s*_vert_tex_light_coord\\s*\\)",
                "(" + P + "vertexUnitFront = texture(u_LightTex, _vert_tex_light_coord))");
    }

    private static String replace(String source, String expression, String replacement) {
        var match = Pattern.compile(expression).matcher(source);
        if (match.results().count() != 1) throw new GlslSourceException("Native vertex lighting expression changed: " + expression);
        return match.reset().replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    static String vertexGlobals() {
        return "vec4 " + P + "vertexUnitFront, " + P + "vertexUnitBack;\nout vec3 " + P + "unitLightFront;\nout vec3 " + P + "unitLightBack;\n";
    }

    static String vertexInitialize() {
        return "    " + P + "vertexUnitFront = vec4(1.0);\n    " + P + "vertexUnitBack = vec4(1.0);\n";
    }

    static String vertexEnd(boolean entity) {
        return "    " + P + "unitLightFront = " + P + "vertexUnitFront.rgb;\n    " + P + "unitLightBack = "
                + P + (entity ? "vertexUnitBack" : "vertexUnitFront") + ".rgb;\n";
    }

    static String fragmentGlobals() {
        return "in vec3 " + P + "unitLightFront;\nin vec3 " + P + "unitLightBack;\n";
    }

    static String fragmentFunction(String body) {
        String response = body.replaceAll("\\b" + P + "texel\\b", P + "responseTexel")
                .replaceAll("\\bfragColor\\b", P + "responseColor");
        return "vec4 " + P + "response(vec3 rgb) {\n    vec4 " + P + "responseTexel = vec4(rgb, " + P
                + "texel.a);\n    vec4 " + P + "responseColor;\n" + response + "\n    return " + P + "responseColor;\n}\n";
    }

    static String fragmentEnd(boolean entity, boolean terrain) {
        return fragmentEnd(entity, terrain, false);
    }

    static String fragmentEnd(boolean entity, boolean terrain, boolean sodiumTerrain) {
        String tint = P + "tint.rgb" + (terrain || sodiumTerrain ? "" : " * ColorModulator.rgb");
        String overlay = entity ? "#ifndef NO_OVERLAY\n    float " + P + "overlayWeight = overlayColor.a;\n#else\n"
                + "    float " + P + "overlayWeight = 1.0;\n#endif\n" : "    float " + P + "overlayWeight = 1.0;\n";
        String colorVar = sodiumTerrain ? "v_Color" : "vertexColor";
        String lighting = entity ? "#ifdef PER_FACE_LIGHTING\n    vec3 " + P
                + "vertexLighting = gl_FrontFacing ? vertexPerFaceColorFront.rgb : vertexPerFaceColorBack.rgb;\n    vec3 "
                + P + "unitLighting = gl_FrontFacing ? " + P + "unitLightFront : " + P + "unitLightBack;\n#else\n"
                + "    vec3 " + P + "vertexLighting = " + colorVar + ".rgb;\n    vec3 " + P + "unitLighting = " + P + "unitLightFront;\n#endif\n"
                : "    vec3 " + P + "vertexLighting = " + colorVar + ".rgb;\n    vec3 " + P + "unitLighting = " + P + "unitLightFront;\n";
        return "    vec3 " + P + "materialTint = " + tint + ";\n"
                + "    vec4 " + P + "zero = " + P + "response(vec3(0.0));\n" + lighting
                + "    vec3 " + P + "coefficient;\n    for (int channel = 0; channel < 3; channel++) {\n"
                + "        " + P + "coefficient[channel] = " + P + "tint[channel] != 0.0 ? " + P + "vertexLighting[channel] / "
                + P + "tint[channel] : " + P + "unitLighting[channel];\n    }\n" + overlay
                + "    " + P + "coefficient *= transmission * " + P + "overlayWeight;\n"
                + (terrain ? "    " + P + "coefficient *= ChunkVisibility;\n" : "")
                + (entity ? "#ifndef EMISSIVE\n    " + P + "coefficient *= lightMapColor.rgb;\n#endif\n" : "")
                + "    " + P + "responseBase = vec4(" + P + "texel.rgb * " + P + "materialTint, 1.0 + " + P + "overlayWeight);\n"
                + "    " + P + "responseZero = " + P + "zero;\n"
                + "    " + P + "responseUnit = vec4(" + P + "zero.rgb + " + P + "coefficient, " + P + "zero.a);\n";
    }
}
