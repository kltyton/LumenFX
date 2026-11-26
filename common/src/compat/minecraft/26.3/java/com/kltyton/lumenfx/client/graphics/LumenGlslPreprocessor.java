package com.kltyton.lumenfx.client.graphics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.ShaderDefines;
import org.jspecify.annotations.Nullable;

/**
 * MC 26.3 compatibility counterpart for MC 26.2 {@code GlslPreprocessor} used by shader preprocessing tests.
 */
public abstract class LumenGlslPreprocessor {
    private static final Pattern REGEX_MOJ_IMPORT = Pattern.compile(
            "^[ \\t]*#moj_import\\s+(?:<([^>]+)>|\"([^\"]+)\")[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern REGEX_VERSION = Pattern.compile(
            "^[ \\t]*#version\\s+\\d+(?:\\s+\\w+)?[ \\t]*$", Pattern.MULTILINE);

    public abstract @Nullable String applyImport(boolean isRelative, String path);

    public List<String> process(String source) {
        Set<String> imported = new HashSet<>();
        String expanded = expandImports(source, imported);
        return List.of(expanded);
    }

    private String expandImports(String source, Set<String> imported) {
        String normalized = source.replace("\r\n", "\n");
        Matcher matcher = REGEX_MOJ_IMPORT.matcher(normalized);
        StringBuilder out = new StringBuilder(normalized.length());
        int cursor = 0;
        while (matcher.find()) {
            out.append(normalized, cursor, matcher.start());
            boolean isRelative = matcher.group(2) != null;
            String target = isRelative ? matcher.group(2) : matcher.group(1);
            String key = (isRelative ? "rel:" : "abs:") + target;
            if (imported.add(key)) {
                String included = applyImport(isRelative, target);
                if (included != null) {
                    out.append(expandImports(included, imported));
                    if (!included.endsWith("\n")) {
                        out.append('\n');
                    }
                }
            }
            cursor = matcher.end();
        }
        out.append(normalized, cursor, normalized.length());
        return out.toString();
    }

    public static String injectDefines(String source, ShaderDefines defines) {
        if (defines == null || defines.isEmpty()) {
            return source;
        }
        String directives = defines.asSourceDirectives();
        if (directives == null || directives.isEmpty()) {
            return source;
        }
        Matcher version = REGEX_VERSION.matcher(source);
        if (version.find()) {
            int insertAt = version.end();
            String prefix = source.substring(0, insertAt);
            String suffix = source.substring(insertAt);
            String nl = suffix.startsWith("\n") || suffix.startsWith("\r") ? "" : "\n";
            return prefix + "\n" + directives + nl + suffix;
        }
        return directives + "\n" + source;
    }
}
