package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ProcessRunner {
    record Result(int exitCode, String standardOutput, String standardError) {
        String describeFailure(final String description) {
            final StringBuilder message = new StringBuilder(description)
                    .append(" exited with code ").append(this.exitCode);
            append(message, "stdout", this.standardOutput);
            append(message, "stderr", this.standardError);
            return message.toString();
        }

        private static void append(final StringBuilder message, final String name, final String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            message.append('\n').append(name).append(":\n").append(text.strip());
        }
    }

    private ProcessRunner() {
    }

    static Result run(
            final List<String> command,
            final Path workingDirectory,
            final Duration timeout,
            final Map<String, String> environment
    ) {
        final Path temp = workingDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(temp);
        } catch (IOException error) {
            throw new BakeException("Unable to create process working directory " + temp, error);
        }

        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(temp.toFile());
        builder.environment().put("TEMP", temp.toString());
        builder.environment().put("TMP", temp.toString());
        builder.environment().put("TMPDIR", temp.toString());
        if (environment != null) {
            builder.environment().putAll(environment);
        }

        final Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new BakeException("Unable to start process " + command.get(0), error);
        }

        final AtomicReference<String> standardOutput = new AtomicReference<>("");
        final AtomicReference<String> standardError = new AtomicReference<>("");
        final Thread outputReader = read(process.getInputStream(), standardOutput);
        final Thread errorReader = read(process.getErrorStream(), standardError);
        try {
            final boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor();
                outputReader.join(1000L);
                errorReader.join(1000L);
                throw new BakeException(
                        "Process timed out after " + timeout.toSeconds() + "s: " + String.join(" ", command)
                                + describe("stdout", standardOutput.get())
                                + describe("stderr", standardError.get())
                );
            }
            outputReader.join();
            errorReader.join();
            return new Result(process.exitValue(), standardOutput.get(), standardError.get());
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BakeException("Interrupted while running " + command.get(0), error);
        }
    }

    private static String describe(final String name, final String text) {
        return text == null || text.isBlank() ? "" : "\n" + name + ":\n" + text.strip();
    }

    private static Thread read(final InputStream stream, final AtomicReference<String> target) {
        final Thread thread = Thread.ofVirtual().start(() -> {
            try (stream) {
                target.set(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException error) {
                target.set("<unable to read process stream: " + error.getMessage() + ">");
            }
        });
        return thread;
    }
}
