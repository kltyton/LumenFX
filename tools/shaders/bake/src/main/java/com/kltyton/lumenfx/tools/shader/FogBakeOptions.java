package com.kltyton.lumenfx.tools.shader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public record FogBakeOptions(Path shaderRoot, Path outputDirectory, Path workDirectory) {
    public static final String USAGE = """
            Usage: FogIntegrationBake --shader-root <assets/lumenfx/shaders>
                   --output-dir <assets/lumenfx/shaders/compute/fog/integrate>
                   --work-dir <work directory>
            """;

    public static FogBakeOptions parse(final String[] args) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            final String argument = args[i];
            if ("--help".equals(argument) || "-h".equals(argument)) {
                throw new BakeException(USAGE);
            }
            if (!argument.startsWith("--")) {
                throw new BakeException("Unexpected argument '" + argument + "'\n" + USAGE);
            }
            final int equals = argument.indexOf('=');
            final String name;
            final String value;
            if (equals >= 0) {
                name = argument.substring(0, equals);
                value = argument.substring(equals + 1);
            } else {
                name = argument;
                if (i + 1 >= args.length) {
                    throw new BakeException("Missing value for " + argument + "\n" + USAGE);
                }
                value = args[++i];
            }
            if (value.isBlank()) {
                throw new BakeException("Blank value for " + name + "\n" + USAGE);
            }
            if (values.put(name, value) != null) {
                throw new BakeException("Duplicate option " + name + "\n" + USAGE);
            }
        }

        final Path shaderRoot = requiredPath(values, "--shader-root");
        final Path outputDirectory = requiredPath(values, "--output-dir");
        final Path workDirectory = requiredPath(values, "--work-dir");
        if (!Files.isDirectory(shaderRoot)) {
            throw new BakeException("--shader-root is not a directory: " + shaderRoot);
        }
        if (!Files.isDirectory(shaderRoot.resolve("include"))) {
            throw new BakeException("--shader-root does not contain include/: " + shaderRoot);
        }
        if (!Files.isRegularFile(shaderRoot.resolve("fog/integrate.comp"))) {
            throw new BakeException("--shader-root does not contain fog/integrate.comp: " + shaderRoot);
        }
        rejectUnknown(values);
        return new FogBakeOptions(shaderRoot, outputDirectory, workDirectory);
    }

    private static void rejectUnknown(final Map<String, String> values) {
        for (String key : values.keySet()) {
            switch (key) {
                case "--shader-root", "--output-dir", "--work-dir" -> {
                }
                default -> throw new BakeException("Unknown option " + key + "\n" + USAGE);
            }
        }
    }

    private static Path requiredPath(final Map<String, String> values, final String name) {
        final String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new BakeException("Missing required option " + name + "\n" + USAGE);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
