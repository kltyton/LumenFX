package com.kltyton.lumenfx.client.render.scene.surface;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSource;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceException;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/** Preserves the host color shader and appends material outputs to the same draw. */
public final class SceneSurfaceShaderAdapter {
    public enum Mode { OPAQUE, TRANSMISSION, IGNORE }
    public record Shaders(Identifier vertex, Identifier fragment, ShaderSource source) { }
    private static final Set<String> FAMILIES = Set.of("core/terrain", "core/block", "core/entity");
    private static final Identifier SODIUM_TERRAIN = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");
    private static final AtomicLong NEXT = new AtomicLong();
    static final String PREFIX = "lfxSurfaceCapture_";
    private final ShaderSource host;

    public SceneSurfaceShaderAdapter(ShaderSource host) { this.host = host; }

    public Shaders create(RenderPipeline pipeline, Mode mode) {
        return create(pipeline, mode, false);
    }

    public Shaders create(RenderPipeline pipeline, Mode mode, boolean materialResponse) {
        Identifier vertexId = pipeline.getVertexShader(), fragmentId = pipeline.getFragmentShader();
        String fragment = require(fragmentId, ShaderType.FRAGMENT);
        GlslSource parsed = model(fragment);
        boolean vanillaKnown = vertexId.getNamespace().equals("minecraft") && fragmentId.getNamespace().equals("minecraft")
                && vertexId.getPath().equals(fragmentId.getPath()) && FAMILIES.contains(vertexId.getPath());
        boolean sodiumTerrain = vertexId.equals(SODIUM_TERRAIN) && fragmentId.equals(SODIUM_TERRAIN);
        boolean known = vanillaKnown || sodiumTerrain;
        boolean normals = vanillaKnown && vertexId.getPath().equals("core/entity");
        boolean terrain = vanillaKnown && vertexId.getPath().equals("core/terrain");
        boolean captureMaterial = known && mode == Mode.OPAQUE;
        int decalReception = captureMaterial ? NativeDecalReception.packed(pipeline) : 0;
        String vertex = null;
        if (captureMaterial) {
            vertex = require(vertexId, ShaderType.VERTEX);
            model(vertex);
            if (materialResponse) {
                vertex = sodiumTerrain ? NativeSurfaceResponseShader.sodiumVertex(vertex) : NativeSurfaceResponseShader.vertex(vertex, normals);
            }
            String tail = sodiumTerrain
                    ? sodiumVertexTail(materialResponse)
                    : vertexTail(normals, materialResponse);
            vertex = wrap(vertex, materialResponse ? NativeSurfaceResponseShader.vertexGlobals() : "", tail);
        }
        String globals = "";
        String helper = "";
        String responseHelper = "";
        if (captureMaterial) {
            var main = parsed.mains().getFirst();
            int bodyStart = fragment.indexOf('{', main.start()) + 1;
            String body = fragment.substring(bodyStart, main.end() - 1);
            var sample = Pattern.compile(normals || sodiumTerrain ? "vec4\\s+color\\s*=\\s*([^;]+);"
                    : "vec4\\s+color\\s*=\\s*([^;]+?)\\s*\\*\\s*vertexColor\\b[^;]*;").matcher(body);
            if (!sample.find()) throw new GlslSourceException("Host surface texture sample changed: " + fragmentId);
            int sampleStart = sample.start(1), sampleEnd = sample.end(1);
            String expression = sample.group(1);
            if (sample.find()) throw new GlslSourceException("Ambiguous host surface texture sample: " + fragmentId);
            String originalBody = body.substring(0, sampleStart) + "(" + PREFIX + "texel = " + expression + ")" + body.substring(sampleEnd);
            fragment = fragment.substring(0, bodyStart) + originalBody + fragment.substring(main.end() - 1);
            body = body.substring(0, sampleStart) + PREFIX + "texel" + body.substring(sampleEnd);
            if (materialResponse) {
                if (sodiumTerrain) {
                    NativeSurfaceResponseContract.requireSodium(fragment, body, PREFIX + "texel");
                } else {
                    NativeSurfaceResponseContract.require(fragment, body, PREFIX + "texel", normals, terrain);
                }
                if (parsed.identifiers().stream().anyMatch(name -> name.equals("buffer") || name.startsWith("image") || name.startsWith("atomic")))
                    throw new GlslSourceException("Native material response requires a side-effect-free fragment shader: " + fragmentId);
                responseHelper = NativeSurfaceResponseShader.fragmentFunction(body);
            }
            if (sodiumTerrain) {
                body = replace(body, "fragColor\\s*=\\s*_linearFog\\s*\\(\\s*color\\s*,[^;]+\\)\\s*;", "fragColor = color;", 1);
                body = body.replaceAll("\\bv_Color\\b", PREFIX + "tint");
            } else {
                body = replace(body, "fragColor\\s*=\\s*apply_fog\\s*\\(\\s*color\\s*,[^;]+\\)\\s*;", "fragColor = color;", 1);
                if (terrain) body = replace(body,
                        "color\\s*=\\s*mix\\s*\\(\\s*FogColor\\s*\\*\\s*vec4\\s*\\(\\s*1(?:\\.0)?\\s*,\\s*1(?:\\.0)?\\s*,\\s*1(?:\\.0)?\\s*,\\s*color\\.a\\s*\\)\\s*,\\s*color\\s*,\\s*ChunkVisibility\\s*\\)\\s*;",
                        "", 1);
                body = body.replaceAll("\\b(?:vertexColor|vertexPerFaceColorFront|vertexPerFaceColorBack)\\b", PREFIX + "tint");
                body = body.replaceAll("\\blightMapColor\\b", "vec4(1.0)");
            }
            body = body.replaceAll("\\bfragColor\\b", PREFIX + "baseColor");
            globals = "in vec4 " + PREFIX + "tint;\nin vec3 " + PREFIX + "position;\nin vec3 " + PREFIX + "normal;\n"
                    + "in float " + PREFIX + "ambient;\n"
                    + "vec4 " + PREFIX + "baseColor;\nvec4 " + PREFIX + "texel;\n"
                    + (materialResponse ? NativeSurfaceResponseShader.fragmentGlobals() : "");
            helper = "void " + PREFIX + "material() {\n" + body + "\n}\n";
        }
        String hostOutput = parsed.outputs().size() == 1 && parsed.outputs().getFirst().type().equals("vec4")
                && !parsed.outputs().getFirst().array() ? parsed.outputs().getFirst().name() : null;
        if (captureMaterial && !"fragColor".equals(hostOutput))
            throw new GlslSourceException("Host surface color output changed: " + fragmentId);
        if (hostOutput != null) {
            var output = parsed.outputs().getFirst();
            if (output.declarators() != 1 || output.conditionalDepth() != 0)
                throw new GlslSourceException("Host surface color declaration changed: " + fragmentId);
            String declaration = "layout(location=0) out " + (output.precision() == null ? "" : output.precision() + " ")
                    + output.type() + " " + output.name() + ";";
            fragment = fragment.substring(0, output.start()) + declaration + fragment.substring(output.end());
        }
        String tail = helper + responseHelper + fragmentTail(mode, captureMaterial, normals, terrain, sodiumTerrain, hostOutput, materialResponse, decalReception);
        fragment = wrap(fragment, globals, tail);
        long id = NEXT.getAndIncrement();
        Identifier generatedVertex = vertex == null ? vertexId : LumenFx.id("surface_shader/" + id + "_vertex");
        Identifier generatedFragment = LumenFx.id("surface_shader/" + id + "_fragment");
        String finalVertex = vertex, finalFragment = fragment;
        return new Shaders(generatedVertex, generatedFragment, (shader, type) -> {
            if (finalVertex != null && shader.equals(generatedVertex) && type == ShaderType.VERTEX) return finalVertex;
            if (shader.equals(generatedFragment) && type == ShaderType.FRAGMENT) return finalFragment;
            return host.get(shader, type);
        });
    }

    private String require(Identifier id, ShaderType type) {
        String value = host.get(id, type);
        if (value == null) throw new GlslSourceException("Host surface shader source is missing: " + id);
        return value;
    }

    private static GlslSource model(String source) {
        GlslSource parsed = GlslSourceScanner.scan(source);
        if (parsed.mains().size() != 1 || parsed.identifiers().stream().anyMatch(name -> name.startsWith(PREFIX)))
            throw new GlslSourceException("Host surface main or capture namespace changed");
        return parsed;
    }

    private static String replace(String source, String expression, String replacement, int expected) {
        var matcher = Pattern.compile(expression).matcher(source);
        long count = matcher.results().count();
        if (count != expected) throw new GlslSourceException("Host surface shader expression changed: " + expression);
        return matcher.reset().replaceAll(replacement);
    }

    private static String wrap(String source, String globals, String tail) {
        int index = GlslSourceScanner.header(source).insertionIndex();
        return source.substring(0, index) + "\n" + globals + "\n#define main " + PREFIX + "originalMain\n"
                + source.substring(index) + "\n#undef main\n" + tail;
    }

    private static String vertexTail(boolean normalAttribute, boolean materialResponse) {
        return "out vec4 " + PREFIX + "tint;\nout vec3 " + PREFIX + "position;\nout vec3 " + PREFIX + "normal;\n"
                + "out float " + PREFIX + "ambient;\n"
                + "void main() {\n" + (materialResponse ? NativeSurfaceResponseShader.vertexInitialize() : "") + "    " + PREFIX + "originalMain();\n"
                + "    vec4 position = inverse(ProjMat) * gl_Position;\n"
                + "    " + PREFIX + "position = position.xyz / position.w;\n"
                + "    " + PREFIX + "tint = Color;\n"
                + "    " + PREFIX + "ambient = 1.0;\n"
                + "    " + PREFIX + "normal = " + (normalAttribute ? "transpose(inverse(mat3(ModelViewMat))) * Normal" : "vec3(0.0)") + ";\n"
                + (materialResponse ? NativeSurfaceResponseShader.vertexEnd(normalAttribute) : "") + "}\n";
    }

    private static String sodiumVertexTail(boolean materialResponse) {
        return "out vec4 " + PREFIX + "tint;\nout vec3 " + PREFIX + "position;\nout vec3 " + PREFIX + "normal;\n"
                + "out float " + PREFIX + "ambient;\n"
                + "void main() {\n" + (materialResponse ? NativeSurfaceResponseShader.vertexInitialize() : "") + "    " + PREFIX + "originalMain();\n"
                + "    vec4 position = inverse(u_ProjectionMatrix) * gl_Position;\n"
                + "    " + PREFIX + "position = position.xyz / position.w;\n"
                + "    " + PREFIX + "tint = a_Color;\n"
                + "    " + PREFIX + "ambient = 1.0;\n"
                + "    " + PREFIX + "normal = vec3(0.0);\n"
                + (materialResponse ? NativeSurfaceResponseShader.vertexEnd(false) : "") + "}\n";
    }

    private static String fragmentTail(Mode mode, boolean material, boolean normalAttribute, boolean terrain, boolean sodiumTerrain,
                                       String output, boolean materialResponse, int decalReception) {
        String declarations = "layout(location=1) out vec4 " + PREFIX + "albedo;\n"
                + "layout(location=2) out vec4 " + PREFIX + "normalAmbient;\n"
                + "layout(location=3) out vec4 " + PREFIX + "positionDepth;\n";
        if (materialResponse) declarations += "layout(location=4) out vec4 " + PREFIX + "responseBase;\n"
                + "layout(location=5) out vec4 " + PREFIX + "responseZero;\nlayout(location=6) out vec4 " + PREFIX + "responseUnit;\n";
        String begin = "void main() {\n    " + PREFIX + "originalMain();\n";
        if (!material) return declarations + begin
                + "    " + PREFIX + "albedo = vec4(0.0);\n"
                + "    " + PREFIX + "normalAmbient = vec4(0.0, 0.0, 0.0, "
                + (mode == Mode.TRANSMISSION && output != null ? output + ".a" : "0.0") + ");\n"
                + "    " + PREFIX + "positionDepth = vec4(0.0);\n"
                + (!materialResponse ? "" : "    " + PREFIX + "responseBase = vec4(0.0);\n    " + PREFIX + "responseZero = "
                    + (output == null ? "vec4(0.0)" : output) + ";\n    " + PREFIX + "responseUnit = "
                    + (output == null ? "vec4(0.0)" : output) + ";\n") + "}\n";
        String transmissionExpr = sodiumTerrain
                ? "_linearFog(vec4(1.0), v_FragDistance, vec4(0.0, 0.0, 0.0, u_FogColor.a), u_EnvironmentFog, u_RenderFog, fadeFactor).r"
                : "apply_fog(vec4(1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, vec4(0.0, 0.0, 0.0, FogColor.a)).r";
        return declarations + "void main() {\n"
                + "    vec3 normal = " + (normalAttribute ? PREFIX + "normal" : "cross(dFdx(" + PREFIX + "position), dFdy(" + PREFIX + "position))") + ";\n"
                + "    " + PREFIX + "originalMain();\n    " + PREFIX + "material();\n"
                + (normalAttribute ? "#ifdef PER_FACE_LIGHTING\n    if (!gl_FrontFacing) normal = -normal;\n#endif\n"
                        : "    if (dot(normal, -" + PREFIX + "position) < 0.0) normal = -normal;\n")
                + "    float squaredLength = dot(normal, normal);\n    normal = squaredLength > 0.0 ? normal * inversesqrt(squaredLength) : vec3(0.0);\n"
                + "#ifdef EMISSIVE\n    float response = 0.0;\n#else\n    float response = 1.0;\n#endif\n"
                + (terrain ? "    response *= ChunkVisibility;\n" : "")
                + "    float transmission = " + transmissionExpr + ";\n"
                + "    " + PREFIX + "albedo = vec4(" + PREFIX + "baseColor.rgb, response * transmission);\n"
                + "    " + PREFIX + "normalAmbient = vec4(normal, " + PREFIX + "ambient);\n"
                + "#ifdef EMISSIVE\n    const int geometryFlags = " + (decalReception | 128 | 256)
                + ";\n#else\n    const int geometryFlags = " + (decalReception | 128) + ";\n#endif\n"
                + "    " + PREFIX + "positionDepth = vec4(" + PREFIX + "position.xy, float(geometryFlags), gl_FragCoord.z);\n"
                + (materialResponse ? NativeSurfaceResponseShader.fragmentEnd(normalAttribute, terrain, sodiumTerrain) : "") + "}\n";
    }
}
