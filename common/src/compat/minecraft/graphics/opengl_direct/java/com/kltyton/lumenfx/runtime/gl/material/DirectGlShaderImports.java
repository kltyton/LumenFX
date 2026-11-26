package com.kltyton.lumenfx.runtime.gl.material;

import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Resolves Minecraft GLSL imports through the same resource-pack overlay as the material. */
final class DirectGlShaderImports {
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^[\\t ]*#moj_import[\\t ]+(?:<([^>]+)>|\"([^\"]+)\")[\\t ]*(?://[^\\r\\n]*)?$");

    private DirectGlShaderImports() { }

    static String expand(String source, @Nullable String sourceId, McResourceBridge resources) {
        Set<String> imported = new HashSet<>();
        if (sourceId != null) imported.add(sourceId);
        return expand(source.replace("\r\n", "\n"), sourceId, resources, imported);
    }

    private static String expand(String source, @Nullable String sourceId, McResourceBridge resources,
                                 Set<String> imported) {
        var matcher = IMPORT.matcher(source);
        StringBuilder result = new StringBuilder(source.length());
        int cursor = 0;
        while (matcher.find()) {
            result.append(source, cursor, matcher.start());
            String id = matcher.group(1) != null ? absolute(matcher.group(1)) : relative(sourceId, matcher.group(2));
            if (imported.add(id)) {
                byte[] bytes = resources.readResource(id);
                if (bytes == null) throw new IllegalArgumentException("Missing GLSL import " + id + " in " + sourceId);
                result.append(expand(new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                        id, resources, imported)).append('\n');
            }
            cursor = matcher.end();
        }
        return result.append(source, cursor, source.length()).toString();
    }

    private static String absolute(String id) {
        int split = id.indexOf(':');
        String namespace = split < 0 ? "minecraft" : id.substring(0, split);
        return namespace + ":shaders/include/" + id.substring(split + 1);
    }

    private static String relative(@Nullable String sourceId, String path) {
        if (sourceId == null || sourceId.lastIndexOf('/') < 0) {
            throw new IllegalArgumentException("Relative GLSL import has no source path: " + path);
        }
        int split = sourceId.indexOf(':');
        String parent = sourceId.substring(split + 1, sourceId.lastIndexOf('/') + 1);
        String resolved = java.nio.file.Path.of(parent + path).normalize().toString().replace('\\', '/');
        return sourceId.substring(0, split + 1) + resolved;
    }
}

