package com.kltyton.lumenfx.client.compat.iris.material;

import com.kltyton.lumenfx.client.resource.shader.glsl.GlslFunctionDefinition;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceException;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Captures resolved Complementary lighting inputs and its actual post-parallax specular sample. */
public record IrisSurfaceInputs(String fragment, GlslFunctionDefinition lighting,
                                @Nullable GlslFunctionDefinition customMaterials) {
    private static final String PREFIX = "lfxPbrSurface_";
    private static final Pattern DIFFUSE_DARKNESS = Pattern.compile(
            "\\bcolor\\.rgb\\s*\\*=\\s*pow2\\(\\s*1\\.0[fF]?\\s*-\\s*darknessLightFactor\\s*\\)\\s*;");
    private static final Pattern PUDDLE_BLEND = Pattern.compile(
            "\\bcolor\\.rgb\\s*=\\s*mix\\(\\s*color\\.rgb\\s*,\\s*sky\\s*,\\s*fresnel\\s*\\*\\s*puddleMixer\\s*\\)\\s*;");
    private static final Pattern DEBUG_COLOR = Pattern.compile("\\bColorCodeProgram\\(\\s*color\\s*,\\s*mat\\s*\\)\\s*;");
    private static final Pattern REFLECTION_MATERIAL = Pattern.compile(
            "\\biris_FragData1\\s*=\\s*vec4\\(\\s*smoothnessD\\s*,\\s*materialMask\\s*,\\s*skyLightFactor\\s*,\\s*1\\.0[fF]?\\s*\\)\\s*;");
    private static final Pattern REFLECTION_NORMAL = Pattern.compile(
            "\\biris_FragData2\\s*=\\s*vec4\\(\\s*mat3\\(\\s*gbufferModelViewInverse\\s*\\)\\s*\\*\\s*normalM\\s*,\\s*1\\.0[fF]?\\s*\\)\\s*;");
    private static final String[] TYPES = {"vec4", "vec3", "vec3", "vec3", "float", "vec3", "vec3", "float",
            "vec3", "vec2", "bool", "bool", "bool", "bool", "int", "float", "float", "float"};
    private static final String[] NAMES = {"color", "shadowMult", "playerPos", "viewPos", "lViewPos", "geoNormal",
            "normalM", "dither", "worldGeoNormal", "lightmap", "noSmoothLighting", "noDirectionalShading",
            "noVanillaAO", "centerShadowBias", "subsurfaceMode", "smoothnessG", "highlightMult", "emission"};
    private record Edit(int start, int end, String value) { }

    public @Nullable String specularSource() {
        var ggx = findUniqueGgx(fragment, lighting);
        if (ggx == null) return null;
        StringBuilder result = new StringBuilder("vec3 lfxHostGeometryNormal;\n");
        for (String name : List.of("min1", "max0", "pow2", "sqrt1", "sqrt3", "GetNoHSquared")) {
            for (var function : GlslSourceScanner.functions(fragment, name)) {
                if (!function.returnType().equals("float")
                        || !function.parameters().stream().allMatch(parameter -> parameter.type().equals("float"))) continue;
                result.append(fragment, function.start(), function.end()).append('\n');
            }
        }
        result.append(fragment.substring(ggx.start(), ggx.end()).replaceAll("\\bnormal\\b", "lfxHostGeometryNormal")).append('\n');
        return result.toString();
    }

    private static @Nullable GlslFunctionDefinition findUniqueGgx(String fragment, GlslFunctionDefinition lighting) {
        if (!Pattern.compile("\\bGGX\\s*\\(").matcher(fragment.substring(lighting.bodyStart(), lighting.bodyEnd())).find()) {
            return null;
        }
        var ggx = GlslSourceScanner.functions(fragment, "GGX").stream()
                .filter(function -> function.returnType().equals("float") && function.parameters().size() == 5
                        && function.parameters().subList(0, 3).stream().allMatch(parameter -> parameter.type().equals("vec3"))
                        && function.parameters().subList(3, 5).stream().allMatch(parameter -> parameter.type().equals("float")))
                .toList();
        return ggx.size() == 1 ? ggx.getFirst() : null;
    }

    public static @Nullable IrisSurfaceInputs find(String fragment) {
        if (fragment == null || !fragment.contains("DoLighting") || !fragment.contains("finalDiffuse")) return null;
        var definitions = GlslSourceScanner.functions(fragment, "DoLighting");
        if (definitions.size() != 1) return null;
        var lighting = definitions.getFirst();
        if (!lighting.returnType().equals("void") || lighting.parameters().size() != TYPES.length) return null;
        for (int index = 0; index < TYPES.length; index++) {
            var parameter = lighting.parameters().get(index);
            if (!parameter.type().equals(TYPES[index]) || !parameter.name().equals(NAMES[index])
                    || !parameter.direction().equals(index < 2 ? "inout" : "in")) return null;
        }
        String body = fragment.substring(lighting.bodyStart(), lighting.bodyEnd());
        if (!Pattern.compile("\\bcolor\\.rgb\\s*\\*=\\s*finalDiffuse\\s*;").matcher(body).find()
                || !Pattern.compile("\\bcolor\\.rgb\\s*\\+=\\s*lightHighlight\\s*;").matcher(body).find()) return null;
        var materials = GlslSourceScanner.functions(fragment, "GetCustomMaterials");
        if (materials.size() > 1) return null;
        var custom = materials.isEmpty() ? null : materials.getFirst();
        if (custom != null) {
            String customBody = fragment.substring(custom.bodyStart(), custom.bodyEnd());
            var sample = Pattern.compile("\\bvec4\\s+specularMap\\s*=\\s*[^;]+;").matcher(customBody);
            if (!sample.find() || sample.find()) return null;
        }
        var scanned = GlslSourceScanner.scan(fragment);
        if (scanned.mains().size() != 1 || scanned.identifiers().stream().anyMatch(name -> name.startsWith(PREFIX))) {
            return null;
        }
        if (Pattern.compile("\\bGGX\\s*\\(").matcher(body).find() && findUniqueGgx(fragment, lighting) == null) {
            return null;
        }
        return new IrisSurfaceInputs(fragment, lighting, custom);
    }

    public String appendOutputs(int firstOutput) {
        return appendOutputs(firstOutput, 3);
    }

    public boolean hasDiffuseResponse() {
        return DIFFUSE_DARKNESS.matcher(fragment.substring(lighting.bodyStart(), lighting.bodyEnd())).find();
    }

    public String appendOutputs(int firstOutput, int outputs) {
        if (outputs < 3 || outputs > 5 || outputs == 5 && !hasDiffuseResponse())
            throw new IllegalArgumentException("Iris material capture outputs must match the shader's material inputs");
        var source = GlslSourceScanner.scan(fragment);
        if (source.mains().size() != 1 || source.identifiers().stream().anyMatch(name -> name.startsWith(PREFIX)))
            throw new GlslSourceException("Iris material capture main or namespace changed");
        List<Edit> edits = new ArrayList<>();
        edits.add(new Edit(lighting.bodyStart(), lighting.bodyStart(), "\n"
                + PREFIX + "base = color;\n"
                + PREFIX + "normal = vec4(normalM, smoothnessG);\n"
                + PREFIX + "position = vec4(viewPos, 1.0);\n"
                + PREFIX + "parameters = vec4(highlightMult, emission, float(subsurfaceMode), " + PREFIX + "hasRawSpecular);\n"));
        if (outputs >= 4) edits.add(new Edit(lighting.bodyStart(), lighting.bodyStart(),
                "\n" + PREFIX + "geometricNormal = normal;\n"));
        if (outputs == 5) {
            var response = DIFFUSE_DARKNESS.matcher(fragment);
            response.region(lighting.bodyStart(), lighting.bodyEnd());
            if (!response.find()) throw new GlslSourceException("Iris diffuse response changed");
            edits.add(new Edit(response.end(), response.end(),
                    "\n" + PREFIX + "diffuse = vec4(finalDiffuse * pow2(1.0 - darknessLightFactor), 1.0);\n"));
            var drawMain = source.mains().getFirst();
            var puddles = PUDDLE_BLEND.matcher(fragment);
            var debug = DEBUG_COLOR.matcher(fragment);
            puddles.region(drawMain.start(), drawMain.end());
            debug.region(drawMain.start(), drawMain.end());
            while (puddles.find()) edits.add(new Edit(puddles.end(), puddles.end(),
                    "\n" + PREFIX + "diffuse.rgb *= 1.0 - fresnel * puddleMixer;\n"));
            // These are the transformed terrain outputs for Complementary's colortex6 and colortex4.
            var reflectionMaterial = REFLECTION_MATERIAL.matcher(fragment).region(drawMain.start(), drawMain.end());
            var reflectionNormal = REFLECTION_NORMAL.matcher(fragment).region(drawMain.start(), drawMain.end());
            if (reflectionMaterial.find()) {
                edits.add(new Edit(reflectionMaterial.end(), reflectionMaterial.end(),
                        "\nif (" + PREFIX + "diffuse.a > 0.0) " + PREFIX + "diffuse.a += 2.0;\n"));
                if (reflectionNormal.find()) edits.add(new Edit(reflectionNormal.end(), reflectionNormal.end(),
                        "\nif (" + PREFIX + "diffuse.a == 3.0) " + PREFIX + "diffuse.a += 4.0;\n"));
            }
            while (debug.find()) edits.add(new Edit(debug.end(), debug.end(), "\n" + PREFIX + "diffuse = vec4(0.0);\n"));
        }
        if (customMaterials != null) {
            String body = fragment.substring(customMaterials.bodyStart(), customMaterials.bodyEnd());
            var sample = Pattern.compile("\\bvec4\\s+specularMap\\s*=\\s*[^;]+;").matcher(body);
            if (!sample.find()) throw new GlslSourceException("Iris material specular sample changed");
            int end = customMaterials.bodyStart() + sample.end();
            if (sample.find()) throw new GlslSourceException("Iris material has ambiguous specular samples");
            edits.add(new Edit(end, end, "\n" + PREFIX + "specular = specularMap;\n"
                    + PREFIX + "hasRawSpecular = 1.0;\n"));
        }
        String globals = "\nvec4 " + PREFIX + "base = vec4(0.0);\n"
                + "vec4 " + PREFIX + "normal = vec4(0.0);\n"
                + "vec4 " + PREFIX + "position = vec4(0.0);\n"
                + "vec4 " + PREFIX + "specular = vec4(0.0);\n"
                + "vec4 " + PREFIX + "parameters = vec4(0.0);\n"
                + (outputs >= 4 ? "vec3 " + PREFIX + "geometricNormal = vec3(0.0);\n" : "")
                + (outputs == 5 ? "vec4 " + PREFIX + "diffuse = vec4(0.0);\n" : "")
                + "float " + PREFIX + "hasRawSpecular = 0.0;\n"
                + "#define main " + PREFIX + "originalMain\n";
        edits.add(new Edit(source.header().insertionIndex(), source.header().insertionIndex(), globals));
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder capture = new StringBuilder(fragment);
        for (var edit : edits) capture.replace(edit.start(), edit.end(), edit.value());
        capture.append("\n#undef main\n")
                .append("layout(location=").append(firstOutput).append(") out vec4 ").append(PREFIX).append("baseDepthOutput;\n")
                .append("layout(location=").append(firstOutput + 1).append(") out vec4 ").append(PREFIX).append("geometryOutput;\n")
                .append("layout(location=").append(firstOutput + 2).append(") out vec4 ").append(PREFIX).append("materialOutput;\n")
                .append(outputs >= 4 ? "layout(location=" + (firstOutput + 3) + ") out vec4 " + PREFIX + "lightingOutput;\n" : "")
                .append(outputs == 5 ? "layout(location=" + (firstOutput + 4) + ") out vec4 " + PREFIX + "diffuseOutput;\n" : "")
                .append("vec2 ").append(PREFIX).append("octahedral(vec3 n) {\n")
                .append("    n /= abs(n.x) + abs(n.y) + abs(n.z);\n")
                .append("    vec2 p = n.xy;\n")
                .append("    if (n.z < 0.0) p = (1.0 - abs(p.yx)) * mix(vec2(-1.0), vec2(1.0), step(vec2(0.0), p));\n")
                .append("    return p;\n}\n")
                .append("void main() {\n    ").append(PREFIX).append("originalMain();\n")
                .append("    bool valid = ").append(PREFIX).append("position.w > 0.0 && dot(").append(PREFIX)
                .append("normal.xyz, ").append(PREFIX).append("normal.xyz) > 0.0;\n")
                .append("    vec2 normal = valid ? ").append(PREFIX).append("octahedral(").append(PREFIX).append("normal.xyz) : vec2(0.0);\n")
                .append("    ").append(PREFIX).append("baseDepthOutput = vec4(").append(PREFIX)
                .append("base.rgb, valid ? gl_FragCoord.z : -1.0);\n")
                .append("    ").append(PREFIX).append("geometryOutput = vec4(").append(PREFIX).append("position.xy, normal);\n")
                .append("    ").append(PREFIX).append("materialOutput = ").append(PREFIX).append("hasRawSpecular > 0.0 ? ")
                .append(PREFIX).append("specular : vec4(").append(PREFIX).append("normal.w, -1.0, ")
                .append(PREFIX).append("parameters.x, ").append(PREFIX).append("parameters.z);\n");
        if (outputs >= 4) {
            boolean highlights = Pattern.compile("\\bGGX\\s*\\(").matcher(fragment.substring(lighting.bodyStart(), lighting.bodyEnd())).find();
            capture.append("    vec3 geo = ").append(PREFIX).append("geometricNormal;\n")
                    .append("    vec2 geoNormal = dot(geo, geo) > 0.0 ? ").append(PREFIX).append("octahedral(geo) : vec2(0.0);\n")
                    .append("    ").append(PREFIX).append("lightingOutput = vec4(geoNormal, ").append(PREFIX).append("normal.w, ")
                    .append(highlights ? PREFIX + "parameters.x" : "0.0").append(");\n");
        }
        if (outputs == 5) capture.append("    ").append(PREFIX).append("diffuseOutput = valid ? ")
                .append(PREFIX).append("diffuse : vec4(0.0);\n");
        capture.append("}\n");
        return capture.toString();
    }
}
