package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Offline snapshot of Unreal's virtual shader directories. */
final class ShaderDirectoryMappings {
    private static final String STAGED_ROOT = "LumenFxVirtualIncludes";
    static final ShaderDirectoryMappings EMPTY = new ShaderDirectoryMappings(Map.of());
    private final List<Map.Entry<String, Path>> mounts;

    private ShaderDirectoryMappings(Map<String, Path> roots) {
        mounts = roots.entrySet().stream().sorted(Comparator.comparingInt(
                (Map.Entry<String, Path> mount) -> mount.getKey().length()).reversed()).toList();
    }

    static ShaderDirectoryMappings read(@Nullable Path file) throws IOException {
        if (file == null) return EMPTY;
        var roots = new LinkedHashMap<String, Path>();
        try (var reader = Files.newBufferedReader(file)) {
            for (var entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                String mount = entry.getKey();
                if (!mount.matches("(?:/[A-Za-z0-9_-]+)+"))
                    throw new BakeException("Invalid UE shader directory mount " + mount);
                Path directory = file.getParent().resolve(entry.getValue().getAsString()).normalize();
                if (!Files.isDirectory(directory))
                    throw new BakeException("UE shader directory for " + mount + " does not exist: " + directory);
                String key = mount.toLowerCase(Locale.ROOT);
                if (roots.putIfAbsent(key, directory) != null)
                    throw new BakeException("Duplicate UE shader directory mount " + mount);
            }
        }
        return new ShaderDirectoryMappings(roots);
    }

    @Nullable String stagePath(String include) {
        var mount = mount(include);
        if (mount == null) return null;
        String relative = STAGED_ROOT + mount.getKey() + include.substring(mount.getKey().length());
        if (!Path.of(relative).normalize().equals(Path.of(relative)))
            throw new BakeException("Invalid UE virtual include path: " + include);
        return relative;
    }

    boolean isMapped(String staged) {
        return staged.startsWith(STAGED_ROOT + "/") && mount(staged.substring(STAGED_ROOT.length())) != null;
    }

    Path source(String staged) {
        String include = staged.substring(STAGED_ROOT.length());
        var mount = mount(include);
        if (mount == null) throw new BakeException("UE shader directory is not mapped: " + include);
        return mount.getValue().resolve(include.substring(mount.getKey().length() + 1));
    }

    private Map.@Nullable Entry<String, Path> mount(String path) {
        for (var mount : mounts) {
            String prefix = mount.getKey() + "/";
            if (path.regionMatches(true, 0, prefix, 0, prefix.length())) return mount;
        }
        return null;
    }
}
