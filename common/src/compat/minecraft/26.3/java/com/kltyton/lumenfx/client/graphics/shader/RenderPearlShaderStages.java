package com.kltyton.lumenfx.client.graphics.shader;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/** Links LumenFX and Photon GLSL stage interfaces before RenderPearl compiles the pair to SPIR-V. */
public final class RenderPearlShaderStages implements ShaderSource {
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\h*#\\h*moj_import\\h+(?:<([^>]+)>|\"([^\"]+)\")\\h*");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\h*#\\h*version[^\\r\\n]*");
    private static final Pattern EXPLICIT_LOCATION =
            Pattern.compile("(?m)^\\h*layout\\h*\\([^)]*\\blocation\\h*=\\h*(\\d+)[^)]*\\)\\h*");
    private static final Pattern INTERFACE = Pattern.compile(
            "(?m)(?:^|(?<=;))\\h*(?:(layout\\h*\\([^)]*\\))\\h*)?"
            + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\h+)*"
            + "(in|out)\\h+(?:highp\\h+|mediump\\h+|lowp\\h+)?(\\w+)\\h+(\\w+)"
            + "(?:\\h*\\[\\h*(\\d+)\\h*\\])?\\h*;");
    private static final Pattern MATRIX = Pattern.compile("(?:d?mat)([234])(?:x[234])?");
    private static final Pattern OLD_BUILTINS = Pattern.compile("\\bgl_(Vertex|Instance)ID\\b");

    private final ShaderSource original;
    private final Map<ShaderType, Identifier> ids;
    private final Map<ShaderType, String> sources;

    private RenderPearlShaderStages(ShaderSource original, Map<ShaderType, Identifier> ids,
                                    Map<ShaderType, String> sources) {
        this.original = original;
        this.ids = ids;
        this.sources = sources;
    }

    public static ShaderSource adapt(RenderPipeline pipeline, ShaderSource source) {
        var ids = pipeline.getShaders();
        var sources = new EnumMap<ShaderType, String>(ShaderType.class);
        boolean needsAdaptation = false;
        for (var stage : ids.entrySet()) {
            String text = source.getShader(stage.getValue(), stage.getKey());
            if (text == null) return source; // RenderPearl reports a missing stage.
            sources.put(stage.getKey(), text);
            needsAdaptation |= IMPORT.matcher(text).find() || OLD_BUILTINS.matcher(text).find()
                    || INTERFACE.matcher(text).results().anyMatch(decl -> decl.group(1) == null);
        }
        if (!needsAdaptation) return source;

        sources.replaceAll((stage, text) -> expandImports(text, ids.get(stage), source, new HashSet<>()));
        var attributes = new Locations();
        var varyings = new Locations();
        var outputs = new Locations();
        sources.forEach((stage, text) -> INTERFACE.matcher(text).results().forEach(decl -> {
            var explicit = EXPLICIT_LOCATION.matcher(decl.group());
            if (explicit.find()) {
                locations(stage, decl, attributes, varyings, outputs)
                        .reserve(decl.group(4), Integer.parseInt(explicit.group(1)), slots(decl));
            }
        }));
        // The legacy source declares mutually exclusive vertex layouts through #ifdef.
        // Assign the pipeline's actual bindings first so inactive attributes consume no hardware slots.
        var boundAttributes = new LinkedHashMap<String, Integer>();
        for (var binding : pipeline.getVertexFormatBindings()) {
            if (binding == null) continue;
            for (var element : binding.getElements()) boundAttributes.merge(element.name(), 1, Integer::sum);
        }
        boundAttributes.forEach(attributes::allocate);
        sources.replaceAll((stage, text) -> {
            String linked = INTERFACE.matcher(text).replaceAll(decl -> {
                String declaration = decl.group();
                if (decl.group(1) != null) return java.util.regex.Matcher.quoteReplacement(declaration);
                int location = locations(stage, decl, attributes, varyings, outputs)
                        .allocate(decl.group(4), slots(decl));
                return java.util.regex.Matcher.quoteReplacement("layout(location = " + location + ") " + declaration);
            });
            linked = OLD_BUILTINS.matcher(linked).replaceAll("gl_$1Index");
            if (!linked.contains("GL_ARB_separate_shader_objects")) {
                // GLSL 330 requires this extension for locations on inter-stage varyings.
                linked = VERSION.matcher(linked).replaceFirst("$0\n#extension GL_ARB_separate_shader_objects : require");
            }
            return linked;
        });
        return new RenderPearlShaderStages(source, ids, sources);
    }

    private static Locations locations(ShaderType stage, MatchResult decl, Locations attributes,
                                       Locations varyings, Locations outputs) {
        boolean input = decl.group(2).equals("in");
        return stage == ShaderType.VERTEX ? (input ? attributes : varyings) : (input ? varyings : outputs);
    }

    private static int slots(MatchResult decl) {
        var matrix = MATRIX.matcher(decl.group(3));
        int columns = matrix.matches() ? Integer.parseInt(matrix.group(1)) : 1;
        int elements = decl.group(5) == null ? 1 : Integer.parseInt(decl.group(5));
        return columns * elements;
    }

    private static String expandImports(String text, Identifier owner, ShaderSource source, Set<Identifier> seen) {
        return IMPORT.matcher(text).replaceAll(directive -> {
            String rooted = directive.group(1);
            Identifier id = rooted != null ? Identifier.parse(rooted)
                    : owner.withPath(path -> path.substring(0, path.lastIndexOf('/') + 1) + directive.group(2));
            if (!seen.add(id)) return "";
            var include = source.getInclude(id);
            if (include == null) return java.util.regex.Matcher.quoteReplacement("#include <" + id + ">");
            // RenderPearl stores length-delimited UTF-8; shaderc's contentString() expects a NUL terminator.
            var result = ShadercIncludeResult.create(include.includeResultPtr());
            long address = MemoryUtil.memGetAddress(result.address() + ShadercIncludeResult.CONTENT);
            String contents = MemoryUtil.memUTF8(address, Math.toIntExact(result.content_length()));
            return java.util.regex.Matcher.quoteReplacement(expandImports(contents, id, source, seen));
        });
    }

    @Override
    public @Nullable String getShader(Identifier id, ShaderType type) {
        return id.equals(ids.get(type)) ? sources.get(type) : original.getShader(id, type);
    }

    @Override
    public @Nullable CachedIncludeSource getInclude(Identifier id) {
        return original.getInclude(id);
    }

    @Override
    public void close() {
        // The compilation borrows the original source and its includes.
    }

    private static final class Locations {
        private final Map<String, Integer> byName = new HashMap<>();
        private final BitSet reserved = new BitSet();

        void reserve(String name, int base, int slots) {
            byName.putIfAbsent(name, base);
            reserved.set(base, base + slots);
        }

        int allocate(String name, int slots) {
            Integer previous = byName.get(name);
            if (previous != null) return previous;
            int base = reserved.nextClearBit(0);
            while (reserved.nextSetBit(base) >= base && reserved.nextSetBit(base) < base + slots) {
                base = reserved.nextClearBit(reserved.nextSetBit(base));
            }
            reserve(name, base, slots);
            return base;
        }
    }
}
