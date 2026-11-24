package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FogSourceStager {
    record StagedSource(Path sourceFile, String entryName, String flattenedSource, List<String> provenance) {
    }

    private static final String NAMESPACE = "lumenfx:";
    private static final String ENTRY = "fog/integrate.comp";
    private static final int MAX_DEPTH = 32;
    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*#\\s*moj_import\\s*<([^>\\r\\n]+)>\\s*$"
    );
    private static final Pattern UNBOUND_FOG_VOLUME = Pattern.compile(
            "layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+FogVolume\\b"
    );
    private static final Pattern BOUND_FOG_VOLUME = Pattern.compile(
            "layout\\s*\\(\\s*std140\\s*,\\s*binding\\s*=\\s*0\\s*\\)\\s*uniform\\s+FogVolume\\b"
    );

    private final Path shaderRoot;
    private final Path stageRoot;
    private final Path includeRoot;

    FogSourceStager(final Path shaderRoot, final Path stageRoot) {
        this.shaderRoot = shaderRoot.toAbsolutePath().normalize();
        this.stageRoot = stageRoot.toAbsolutePath().normalize();
        this.includeRoot = this.shaderRoot.resolve("include").normalize();
    }

    StagedSource stage() {
        final Path entry = this.shaderRoot.resolve(ENTRY).normalize();
        if (!entry.startsWith(this.shaderRoot)) {
            throw new BakeException("Fog entry escapes the shader root: " + entry);
        }
        if (!Files.isRegularFile(entry)) {
            throw new BakeException("Fog integration source is missing: " + entry);
        }
        try {
            Files.createDirectories(this.stageRoot);
            Files.createDirectories(this.stageRoot.resolve("include"));
            final Set<String> provenance = new LinkedHashSet<>();
            final Map<String, String> expandedFiles = new LinkedHashMap<>();
            final Expansion expansion = expand(entry, ENTRY, new LinkedHashSet<>(), 0, provenance, expandedFiles);
            final String flattened = bindFogVolume(expansion.text());
            verifyNoUnresolvedImports(flattened);
            expandedFiles.put(ENTRY, flattened);
            for (Map.Entry<String, String> file : expandedFiles.entrySet()) {
                final Path target = this.stageRoot.resolve(file.getKey()).normalize();
                if (!target.startsWith(this.stageRoot)) {
                    throw new BakeException("Staged shader path escapes the work directory: " + file.getKey());
                }
                Files.createDirectories(target.getParent());
                final String text = UNBOUND_FOG_VOLUME.matcher(file.getValue()).find()
                        || BOUND_FOG_VOLUME.matcher(file.getValue()).find()
                        ? bindFogVolume(file.getValue())
                        : file.getValue();
                Files.writeString(target, text, StandardCharsets.UTF_8);
            }
            return new StagedSource(
                    this.stageRoot.resolve(ENTRY),
                    ENTRY,
                    flattened,
                    List.copyOf(provenance)
            );
        } catch (IOException error) {
            throw new BakeException("Unable to stage fog integration sources in " + this.stageRoot, error);
        }
    }

    private Expansion expand(
            final Path file,
            final String logicalName,
            final Set<Path> chain,
            final int depth,
            final Set<String> provenance,
            final Map<String, String> expandedFiles
    ) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new BakeException("Fog import nesting exceeds " + MAX_DEPTH + " while reading " + logicalName);
        }
        final Path normalized = file.toAbsolutePath().normalize();
        if (!chain.add(normalized)) {
            throw new BakeException("Fog import cycle at " + logicalName);
        }
        try {
            final List<String> lines = Files.readAllLines(normalized, StandardCharsets.UTF_8);
            final StringBuilder text = new StringBuilder();
            for (String line : lines) {
                final Matcher matcher = IMPORT.matcher(line);
                if (matcher.matches()) {
                    final String spec = matcher.group(1).trim();
                    if (!spec.startsWith(NAMESPACE)) {
                        throw new BakeException(
                                "Unsupported #moj_import namespace '" + spec + "' in " + logicalName
                        );
                    }
                    final String name = spec.substring(NAMESPACE.length());
                    final Path target = resolveInclude(name, logicalName);
                    final String importedName = "include/" + name.replace('\\', '/');
                    final Expansion imported = expand(
                            target, importedName, chain, depth + 1, provenance, expandedFiles
                    );
                    text.append(imported.text());
                    if (!imported.text().endsWith("\n")) {
                        text.append('\n');
                    }
                    provenance.add(importedName);
                    continue;
                }
                final String trimmed = line.stripLeading();
                if (trimmed.startsWith("#include")) {
                    throw new BakeException(
                            "Unsupported #include in fog source " + logicalName + "; use #moj_import <lumenfx:...>"
                    );
                }
                if (line.contains("#moj_import")) {
                    throw new BakeException("Malformed #moj_import line in " + logicalName + ": " + line);
                }
                text.append(line).append('\n');
            }
            final String result = text.toString();
            expandedFiles.put(logicalName, result);
            provenance.add(logicalName);
            return new Expansion(result);
        } finally {
            chain.remove(normalized);
        }
    }

    private Path resolveInclude(final String name, final String including) {
        if (name.isBlank() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.startsWith("/")) {
            throw new BakeException("Invalid lumenfx import '" + name + "' in " + including);
        }
        final Path relative;
        try {
            relative = Path.of(name).normalize();
        } catch (InvalidPathException error) {
            throw new BakeException("Invalid lumenfx import '" + name + "' in " + including, error);
        }
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
            throw new BakeException("Lumenfx import escapes include/: '" + name + "' in " + including);
        }
        final Path target = this.includeRoot.resolve(relative).normalize();
        if (!target.startsWith(this.includeRoot)) {
            throw new BakeException("Lumenfx import escapes include/: '" + name + "' in " + including);
        }
        if (!Files.isRegularFile(target)) {
            throw new BakeException("Lumenfx import '" + name + "' is missing at " + target);
        }
        return target;
    }

    private static String bindFogVolume(final String source) {
        final Matcher unbound = UNBOUND_FOG_VOLUME.matcher(source);
        final List<Integer> unboundStarts = new ArrayList<>();
        while (unbound.find()) {
            unboundStarts.add(unbound.start());
        }
        final Matcher bound = BOUND_FOG_VOLUME.matcher(source);
        int boundCount = 0;
        while (bound.find()) {
            boundCount++;
        }
        if (unboundStarts.size() == 1 && boundCount == 0) {
            return unbound.replaceFirst("layout(std140, binding = 0) uniform FogVolume");
        }
        if (unboundStarts.isEmpty() && boundCount == 1) {
            return source;
        }
        throw new BakeException(
                "Expected exactly one layout(std140) uniform FogVolume block; found unbound="
                        + unboundStarts.size() + ", bound=" + boundCount
        );
    }

    private static void verifyNoUnresolvedImports(final String source) {
        for (String line : source.split("\\R", -1)) {
            final String trimmed = line.stripLeading();
            if (trimmed.startsWith("#include") || line.contains("#moj_import")) {
                throw new BakeException("Unresolved fog shader import after staging: " + line);
            }
        }
    }

    private record Expansion(String text) {
    }
}
