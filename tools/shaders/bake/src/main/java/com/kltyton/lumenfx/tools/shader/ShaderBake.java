package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ShaderBake {
    private ShaderBake() {
    }

    public static void main(final String[] args) {
        final List<String> filtered = new ArrayList<>();
        String pipeline = null;
        for (int i = 0; i < args.length; i++) {
            final String argument = args[i];
            if (argument.startsWith("--pipeline=")) {
                pipeline = argument.substring("--pipeline=".length());
            } else if ("--pipeline".equals(argument)) {
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for --pipeline");
                    System.exit(2);
                    return;
                }
                pipeline = args[++i];
            } else {
                filtered.add(argument);
            }
        }

        if (pipeline == null || pipeline.isBlank()
                || !hasOption(filtered, "--manifest") || !hasOption(filtered, "--compat-dir")) {
            System.err.println("ShaderBake requires --pipeline, --manifest and --compat-dir");
            System.exit(2);
            return;
        }

        final BakeOptions options;
        try {
            options = BakeOptions.parse(filtered.toArray(String[]::new));
        } catch (BakeException error) {
            System.err.println(error.getMessage());
            System.exit(2);
            return;
        }

        try {
            final Path temporary = options.workDirectory().resolve("tmp");
            Files.createDirectories(temporary);
            System.setProperty("java.io.tmpdir", temporary.toString());
            Files.createDirectories(options.outputDirectory());
            StageBake.run(options, pipeline);
        } catch (BakeException error) {
            System.err.println("ShaderBake failed: " + error.getMessage());
            if (error.getCause() != null) {
                System.err.println("Caused by: " + error.getCause());
            }
            System.exit(1);
        } catch (IOException | RuntimeException error) {
            System.err.println("ShaderBake failed: " + error);
            System.exit(1);
        } catch (LinkageError error) {
            System.err.println("ShaderBake failed to load an LWJGL native library (spvc/shaderc): " + error);
            System.exit(1);
        }
    }

    private static boolean hasOption(final List<String> args, final String name) {
        for (String argument : args) {
            if (argument.equals(name) || argument.startsWith(name + "=")) {
                return true;
            }
        }
        return false;
    }
}
