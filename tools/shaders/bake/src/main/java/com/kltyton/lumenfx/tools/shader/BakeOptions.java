package com.kltyton.lumenfx.tools.shader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public record BakeOptions(
        Path ueRoot,
        Path dxc,
        Path outputDirectory,
        Path workDirectory,
        Path manifest,
        Path compatDirectory,
        String sceneColorFormat,
        String sceneDepthFormat,
        String velocityFormat,
        boolean includeOptional,
        boolean keepWork,
        Duration dxcTimeout,
        Path generatedNiagaraSource,
        Path generatedIncludeDirectory,
        Path shaderDirectories,
        Path niagaraEffect,
        String niagaraEmitter
) {
    public static final String USAGE = """
            Usage: ShaderBake --pipeline <name> --ue-root <dir> --dxc <file> --output-dir <dir> --work-dir <dir>
                   [--manifest <file>] [--compat-dir <dir>]
                   [--scene-color-format <PF_Name|GpuFormat>]
                   [--scene-depth-format <PF_Name|GpuFormat>]
                   [--velocity-format <PF_Name|GpuFormat>]
                   [--include-optional] [--keep-work]
                   [--dxc-timeout-seconds <seconds>]
                   [--generated-niagara-source <file>]
                    [--generated-include-dir <dir>] [--shader-directories <file>]
                    [--niagara-effect <file> --niagara-emitter <name>]
            """;

    public static BakeOptions parse(final String[] args) {
        final Map<String, String> values = new LinkedHashMap<>();
        boolean includeOptional = false;
        boolean keepWork = false;
        for (int i = 0; i < args.length; i++) {
            final String argument = args[i];
            if ("--help".equals(argument) || "-h".equals(argument)) {
                throw new BakeException(USAGE);
            }
            if ("--include-optional".equals(argument)) {
                includeOptional = true;
                continue;
            }
            if ("--keep-work".equals(argument)) {
                keepWork = true;
                continue;
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

        final Path ueRoot = requiredDirectory(values, "--ue-root");
        final Path dxc = requiredFile(values, "--dxc");
        final Path outputDirectory = requiredPath(values, "--output-dir");
        final Path workDirectory = requiredPath(values, "--work-dir");
        final Path manifest = requiredFile(values, "--manifest");
        final Path compatDirectory = requiredDirectory(values, "--compat-dir");
        final long timeoutSeconds = values.containsKey("--dxc-timeout-seconds")
                ? parsePositiveLong(values.get("--dxc-timeout-seconds"), "--dxc-timeout-seconds")
                : 600L;

        final Path source = ueRoot.resolve("Engine/Shaders");
        if (!java.nio.file.Files.isDirectory(source)) {
            throw new BakeException("--ue-root does not contain the UE shader tree: " + source);
        }

        rejectUnknown(values);
        if (values.containsKey("--niagara-effect") != values.containsKey("--niagara-emitter"))
            throw new BakeException("--niagara-effect and --niagara-emitter must be supplied together");
        if (values.containsKey("--niagara-effect") && !values.containsKey("--generated-niagara-source"))
            throw new BakeException("A Niagara effect requires --generated-niagara-source");
        return new BakeOptions(
                ueRoot,
                dxc,
                outputDirectory,
                workDirectory,
                manifest,
                compatDirectory,
                values.get("--scene-color-format"),
                values.get("--scene-depth-format"),
                values.get("--velocity-format"),
                includeOptional,
                keepWork,
                Duration.ofSeconds(timeoutSeconds),
                values.containsKey("--generated-niagara-source") ? requiredFile(values, "--generated-niagara-source") : null,
                values.containsKey("--generated-include-dir") ? requiredDirectory(values, "--generated-include-dir") : null,
                values.containsKey("--shader-directories") ? requiredFile(values, "--shader-directories") : null,
                values.containsKey("--niagara-effect") ? requiredFile(values, "--niagara-effect") : null,
                values.get("--niagara-emitter")
        );
    }

    private static void rejectUnknown(final Map<String, String> values) {
        for (String key : values.keySet()) {
            switch (key) {
                case "--ue-root", "--dxc", "--output-dir", "--work-dir", "--manifest", "--compat-dir",
                        "--scene-color-format", "--scene-depth-format", "--velocity-format",
                        "--dxc-timeout-seconds", "--generated-niagara-source", "--generated-include-dir",
                        "--shader-directories", "--niagara-effect", "--niagara-emitter" -> {
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

    private static Path requiredDirectory(final Map<String, String> values, final String name) {
        final Path path = requiredPath(values, name);
        if (!java.nio.file.Files.isDirectory(path)) {
            throw new BakeException(name + " is not a directory: " + path);
        }
        return path;
    }

    private static Path requiredFile(final Map<String, String> values, final String name) {
        final Path path = requiredPath(values, name);
        if (!java.nio.file.Files.isRegularFile(path)) {
            throw new BakeException(name + " is not a file: " + path);
        }
        return path;
    }

    private static long parsePositiveLong(final String value, final String name) {
        try {
            final long parsed = Long.parseLong(value);
            if (parsed <= 0L) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new BakeException(name + " must be a positive integer: " + value);
        }
    }
}
