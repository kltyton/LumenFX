package com.kltyton.lumenfx.client.compat.iris.material;

import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import com.kltyton.lumenfx.client.compat.iris.motion.IrisShaderSources;
import org.jspecify.annotations.Nullable;

/** Auxiliary outputs are appended to the live shader; original outputs and shader side effects execute once. */
public final class IrisMaterialPrograms {
    public record Prepared(String fragment, int firstOutput, int auxiliaryOutputs) { }
    public record Layout(int firstOutput, int auxiliaryOutputs) { }
    private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)\\b");
    private static final Map<Integer, Layout> OUTPUTS = new HashMap<>();
    private static final Map<Integer, String> SPECULAR = new HashMap<>();
    private static boolean sourceCreation;

    private IrisMaterialPrograms() { }

    public static boolean enterSourceCreation(boolean capture) {
        RenderSystem.assertOnRenderThread();
        boolean previous = sourceCreation; sourceCreation = capture; return previous;
    }

    public static void restoreSourceCreation(boolean previous) { sourceCreation = previous; }

    public static @Nullable Prepared prepare(String fragment) {
        if (!sourceCreation) return null;
        IrisSurfaceInputs inputs = IrisSurfaceInputs.find(fragment);
        if (inputs == null) return null;
        var source = GlslSourceScanner.scan(fragment);
        int first = 0;
        for (var output : source.outputs()) {
            if (output.array() || output.declarators() != 1 || output.conditionalDepth() != 0) return null;
            var location = LOCATION.matcher(fragment.substring(output.start(), output.end()));
            if (!location.find()) return null;
            first = Math.max(first, Integer.parseInt(location.group(1)) + 1);
        }
        if (first == 0) return null;
        int maximum = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL20C.GL_MAX_DRAW_BUFFERS);
        if (first + 3 > maximum) return new Prepared(fragment, first, 0);
        int auxiliary = first + 5 <= maximum && inputs.hasDiffuseResponse() ? 5 : first + 4 <= maximum ? 4 : 3;
        return new Prepared(inputs.appendOutputs(first, auxiliary), first, auxiliary);
    }

    public static void register(int program, @Nullable Prepared prepared) {
        RenderSystem.assertOnRenderThread();
        SPECULAR.remove(program);
        if (prepared != null) OUTPUTS.put(program, new Layout(prepared.firstOutput(), prepared.auxiliaryOutputs()));
        else OUTPUTS.remove(program);
    }

    public static @Nullable Layout layout(int program) { return OUTPUTS.get(program); }
    public static @Nullable String specularSource(int program) {
        if (!SPECULAR.containsKey(program)) {
            var entry = IrisShaderSources.get(program);
            if (entry == null || entry.materialInputs() == null)
                throw new IllegalStateException("Iris material program has no source contract: " + program);
            SPECULAR.put(program, entry.materialInputs().specularSource());
        }
        return SPECULAR.get(program);
    }
    public static int firstOutput(int program) {
        Layout layout = OUTPUTS.get(program);
        return layout == null || layout.auxiliaryOutputs() == 0 ? -1 : layout.firstOutput();
    }
    public static void remove(int program) { OUTPUTS.remove(program); SPECULAR.remove(program); }
}
