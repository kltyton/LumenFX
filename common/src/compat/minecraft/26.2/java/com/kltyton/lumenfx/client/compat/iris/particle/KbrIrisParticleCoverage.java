package com.kltyton.lumenfx.client.compat.iris.particle;

import com.kltyton.lumenfx.client.compat.kbr.KbrCompat;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;

/** Applies the KBR texture coverage rule inside the pack's particle shader. */
public final class KbrIrisParticleCoverage {
    public record Sources(String vertex, String fragment) { }

    private static final String MODE = "lfxKbrCoverageMode";
    private static final String UV = "lfxKbrCoverageUv";
    private static final String COVERED_COLOR = "lfxKbrCoveredColor";
    private static final Pattern ALBEDO = Pattern.compile(
            "\\buniform\\s+sampler2D\\s+(tex|gtexture|iris_Sampler0|Sampler0)\\s*;");
    private static final Pattern VERTEX_TINT = Pattern.compile(
            "\\(iris_Color\\s*\\*\\s*iris_transforms\\.ColorModulator\\)");
    private static final Pattern IRIS_ALPHA = Pattern.compile(
            "\\bif\\s*\\(\\s*!\\(iris_FragData0\\.a\\s*>\\s*iris_currentAlphaTest\\)\\s*\\)");
    private static final Int2IntOpenHashMap LOCATIONS = new Int2IntOpenHashMap();
    private static int activeProgram;

    static {
        LOCATIONS.defaultReturnValue(-1);
    }

    private KbrIrisParticleCoverage() { }

    public static @Nullable Sources prepare(String name, String vertex, String fragment, boolean hasIntermediateStage) {
        if (hasIntermediateStage || !isParticleProgram(name) || !KbrCompat.isPresent()
                || !vertex.contains("iris_UV0") || !fragment.contains("iris_FragData0")) return null;
        var atlas = ALBEDO.matcher(fragment);
        if (!atlas.find()) return null;
        String sampler = atlas.group(1);
        // Iris appends its generic particle cutoff. Preserve it for all non-KBR submissions.
        String guarded = IRIS_ALPHA.matcher(fragment).replaceAll("if (" + MODE
                + " == 0 && !(iris_FragData0.a > iris_currentAlphaTest))");
        // A surviving KBR cutout pixel enters shading with full opacity, including the vertex tint.
        var sampleColor = Pattern.compile("(\\bvec4\\s+\\w+\\s*=\\s*)(texture(?:Lod)?\\(\\s*"
                + sampler + "\\s*,[^;]+\\))(?=\\s*;)");
        String coveredFragment = sampleColor.matcher(guarded).replaceAll("$1" + COVERED_COLOR + "($2)");
        String coveredVertex = VERTEX_TINT.matcher(vertex).replaceAll(COVERED_COLOR + "($0)");
        return new Sources(addToMain(coveredVertex, "out vec2 " + UV + ";", UV + " = iris_UV0;", ""),
                addToMain(coveredFragment, "in vec2 " + UV + ";",
                        "if (" + MODE + " == 2 && texture(" + sampler + ", " + UV + ").a < 0.5) discard;",
                        "if (" + MODE + " == 2) iris_FragData0.a = 1.0;"));
    }

    private static boolean isParticleProgram(String name) {
        return switch (name) {
            case "particles", "particles_trans", "shadow_particles" -> true;
            default -> false;
        };
    }

    private static String addToMain(String shader, String declaration, String beforeBody, String afterBody) {
        var main = GlslSourceScanner.functions(shader, "main").getFirst();
        int header = GlslSourceScanner.header(shader).insertionIndex();
        return shader.substring(0, header) + "\nuniform int " + MODE + ";\nvec4 " + COVERED_COLOR
                + "(vec4 value) { return " + MODE + " == 2 ? vec4(value.rgb, 1.0) : value; }\n"
                + shader.substring(header, main.start()) + "\n" + declaration + "\n"
                + shader.substring(main.start(), main.bodyStart()) + "\n" + beforeBody + "\n"
                + shader.substring(main.bodyStart(), main.bodyEnd()) + "\n" + afterBody + "\n"
                + shader.substring(main.bodyEnd());
    }

    public static void registerProgram(int program) {
        int location = GL20C.glGetUniformLocation(program, MODE);
        if (location >= 0) LOCATIONS.put(program, location);
    }

    public static void removeProgram(int program) {
        if (activeProgram == program) activeProgram = 0;
        LOCATIONS.remove(program);
    }

    public static void beginDraw(int program, RenderPipeline pipeline) {
        int location = LOCATIONS.get(program);
        if (location < 0) return;
        var layer = KbrIrisParticlePipelines.layer(pipeline);
        if (layer == null) return;
        GL41C.glProgramUniform1i(program, location, layer == KbrIrisParticlePipelines.Layer.CUTOUT ? 2 : 1);
        activeProgram = program;
    }

    public static void endDraw() {
        if (activeProgram == 0) return;
        GL41C.glProgramUniform1i(activeProgram, LOCATIONS.get(activeProgram), 0);
        activeProgram = 0;
    }
}
