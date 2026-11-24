package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitHead {
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final Pattern REF = Pattern.compile("^ref:\\s*(\\S+)\\s*$");

    private GitHead() {
    }

    static void verify(final Path ueRoot, final String expectedHead) {
        if (expectedHead == null || !COMMIT.matcher(expectedHead).matches()) {
            throw new BakeException("Manifest engine.head is not a 40-character commit id: " + expectedHead);
        }
        final Path git = findGitDirectory(ueRoot, expectedHead);
        final String actual = readHead(git, 0);
        if (!actual.equalsIgnoreCase(expectedHead)) {
            throw new BakeException(
                    "UE checkout HEAD mismatch: expected " + expectedHead + " from manifest, found " + actual
                            + " in " + git
            );
        }
    }

    private static Path findGitDirectory(final Path ueRoot, final String expectedHead) {
        Path current = ueRoot.toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            final Path dotGit = current.resolve(".git");
            if (Files.isDirectory(dotGit)) {
                return dotGit;
            }
            if (Files.isRegularFile(dotGit)) {
                try {
                    final String text = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
                    if (!text.startsWith("gitdir:")) {
                        throw new BakeException("Unsupported .git file at " + dotGit);
                    }
                    final Path gitDirectory = Path.of(text.substring("gitdir:".length()).strip());
                    final Path resolved = gitDirectory.isAbsolute()
                            ? gitDirectory
                            : current.resolve(gitDirectory).normalize();
                    if (!Files.isDirectory(resolved)) {
                        throw new BakeException("Git directory referenced by " + dotGit + " is missing: " + resolved);
                    }
                    return resolved;
                } catch (IOException error) {
                    throw new BakeException("Unable to read " + dotGit, error);
                }
            }
            current = current.getParent();
        }
        throw new BakeException("No .git metadata found for --ue-root " + ueRoot
                + "; cannot verify the fixed UE HEAD " + expectedHead);
    }

    private static String readHead(final Path gitDirectory, final int depth) {
        if (depth > 4) {
            throw new BakeException("Git HEAD reference chain is too deep in " + gitDirectory);
        }
        final Path headFile = gitDirectory.resolve("HEAD");
        final String head;
        try {
            head = Files.readString(headFile, StandardCharsets.UTF_8).strip();
        } catch (IOException error) {
            throw new BakeException("Unable to read git HEAD " + headFile, error);
        }
        final Matcher ref = REF.matcher(head);
        if (ref.matches()) {
            final String refName = ref.group(1);
            final Path loose = gitDirectory.resolve(refName);
            if (Files.isRegularFile(loose)) {
                try {
                    final String value = Files.readString(loose, StandardCharsets.UTF_8).strip();
                    if (!COMMIT.matcher(value).matches()) {
                        throw new BakeException("Invalid commit id in " + loose + ": " + value);
                    }
                    return value;
                } catch (IOException error) {
                    throw new BakeException("Unable to read git ref " + loose, error);
                }
            }
            final String packed = readPackedRef(gitDirectory.resolve("packed-refs"), refName);
            if (packed != null) {
                return packed;
            }
            throw new BakeException("Git ref " + refName + " not found in " + gitDirectory);
        }
        if (!COMMIT.matcher(head).matches()) {
            throw new BakeException("Unsupported git HEAD in " + headFile + ": " + head);
        }
        return head;
    }

    private static String readPackedRef(final Path packedRefs, final String refName) {
        if (!Files.isRegularFile(packedRefs)) {
            return null;
        }
        try {
            final List<String> lines = Files.readAllLines(packedRefs, StandardCharsets.UTF_8);
            for (String line : lines) {
                final String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("^")) {
                    continue;
                }
                final int space = stripped.indexOf(' ');
                if (space <= 0) {
                    continue;
                }
                final String commit = stripped.substring(0, space);
                final String name = stripped.substring(space + 1).strip();
                if (name.equals(refName) && COMMIT.matcher(commit).matches()) {
                    return commit;
                }
            }
            return null;
        } catch (IOException error) {
            throw new BakeException("Unable to read " + packedRefs, error);
        }
    }
}
