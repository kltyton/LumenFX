package com.kltyton.lumenfx.runtime.gl.material;

import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlGlslMacroExpander.MacroDef;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlGlslPreprocessor.CondFrame;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlGlslPreprocessor.LeadingVersion;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlGlslPreprocessor.LineState;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlGlslPreprocessor.LogicalLine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Comment- and preprocessor-conditional-aware resolver for Minecraft {@code #moj_import} GLSL
 * directives through the same resource-pack overlay as the material.
 *
 * <p>Handles:
 * <ul>
 *   <li>Ignoring {@code #moj_import} inside single-line ({@code //}) comments (including
 *       backslash-newline continuations), multi-line ({@code /* ... *&#47;}) comments, and inactive
 *       preprocessor conditional branches ({@code #if 0}, false {@code #ifdef}/{@code #ifndef}/
 *       {@code #elif}/{@code #else}), so inactive branches neither read missing resources nor
 *       shadow subsequent active imports of the same file.</li>
 *   <li>Directive token-level comments, zero-space delimiters ({@code #moj_import"file.glsl"}),
 *       and backslash-newline continuations inside {@code #moj_import} directives.</li>
 *   <li>Per-file {@code #line 1 <childFile>} isolation and parent {@code #line <nextLine> <parentFile>}
 *       restoration across active imports (matching vanilla {@code GlslPreprocessor}).</li>
 *   <li>Stripping {@code #version} directives from included files and merging their version number
 *       into the root {@code #version} directive when higher.</li>
 * </ul>
 */
public final class DirectGlShaderImports {
    private DirectGlShaderImports() { }

    public static String expand(String source, @Nullable String sourceId, McResourceBridge resources) {
        return expand(source, sourceId, resources, DirectGlGlslPreprocessor.tryQueryLiveGlsl150LineOffsetsByOne());
    }

    public static String expand(String source, @Nullable String sourceId, McResourceBridge resources,
                                boolean glsl150LineOffsetsByOne) {
        String normalized = DirectGlGlslPreprocessor.normalizeSource(source);
        List<LogicalLine> lines = DirectGlGlslPreprocessor.scanLogicalLines(normalized);
        LeadingVersion version = DirectGlGlslPreprocessor.findLeadingVersion(normalized, lines);
        int rootVersionNumber = version != null ? version.number() : 150;
        Set<String> liveGlExtensions = DirectGlGlslPreprocessor.tryQueryLiveGlExtensions();
        boolean preprocessor32Bit = DirectGlGlslPreprocessor.tryQueryLivePreprocessor32Bit();

        int effectiveVersion = rootVersionNumber;
        String expanded = normalized;
        String pendingMissingImport = null;
        // Fixed-point re-expansion when an active import promotes the root #version, so earlier
        // __VERSION__ conditionals, predefined extension macros, and GLSL 1.50 vs 3.30 #line
        // offset semantics all match the final effective #version. Missing imports encountered
        // during a provisional pass are deferred until the effective #version converges so an
        // import in a branch that becomes inactive after promotion cannot abort version discovery.
        for (int pass = 0; pass < 8; pass++) {
            int lineOffset = (effectiveVersion < 330 && glsl150LineOffsetsByOne) ? 1 : 0;
            int lineDirectiveDelta = -lineOffset;
            Map<String, MacroDef> macros = DirectGlGlslPreprocessor.createInitialMacros(effectiveVersion, liveGlExtensions);
            Set<String> imported = new HashSet<>();
            if (sourceId != null) {
                imported.add(sourceId);
            }
            int[] nextSourceFileId = { 1 };
            int[] maxIncludedVersion = { 0 };
            String[] firstMissingImport = { null };
            expanded = expandLines(
                    normalized, lines, sourceId, resources, imported, macros, liveGlExtensions,
                    preprocessor32Bit, lineOffset, lineDirectiveDelta, false, 0,
                    nextSourceFileId, maxIncludedVersion, firstMissingImport);
            pendingMissingImport = firstMissingImport[0];
            if (version != null && maxIncludedVersion[0] > effectiveVersion) {
                effectiveVersion = maxIncludedVersion[0];
                continue;
            }
            break;
        }
        if (pendingMissingImport != null) {
            throw new IllegalArgumentException(pendingMissingImport);
        }
        if (version != null && effectiveVersion > version.number()) {
            expanded = upgradeLeadingVersion(expanded, effectiveVersion);
        }
        return expanded;
    }

    private static String expandLines(String rawSource, List<LogicalLine> lines, @Nullable String sourceId,
                                      McResourceBridge resources, Set<String> imported,
                                      Map<String, MacroDef> macros, @Nullable Set<String> liveGlExtensions,
                                      boolean preprocessor32Bit, int lineOffset, int lineDirectiveDelta,
                                      boolean isIncluded, int initialFileId,
                                      int[] nextSourceFileId, int[] maxIncludedVersion,
                                      String[] firstMissingImport) {
        StringBuilder result = new StringBuilder(rawSource.length());
        Deque<CondFrame> condStack = new ArrayDeque<>();
        LineState lineState = new LineState(1, initialFileId);

        for (int idx = 0; idx < lines.size(); idx++) {
            LogicalLine ll = lines.get(idx);
            String text = ll.text();
            int first = DirectGlGlslPreprocessor.skipHorizWs(text, 0);
            if (first < text.length() && text.charAt(first) == '#') {
                int afterHash = first + 1;
                int kwStart = DirectGlGlslPreprocessor.skipHorizWs(text, afterHash);
                String kw = (kwStart < text.length() && DirectGlGlslPreprocessor.isIdentStart(text.charAt(kwStart)))
                        ? text.substring(kwStart, DirectGlGlslPreprocessor.scanIdentEnd(text, kwStart))
                        : "";
                if ("moj_import".equals(kw)) {
                    int savedParentFile = lineState.sourceFile;
                    int nextParentLine = lineState.logicalLine + ll.physLineSpan();
                    if (!DirectGlGlslPreprocessor.isCondActive(condStack)) {
                        if (ll.endedWithNewline()) {
                            for (int n = 0; n < ll.physLineSpan(); n++) {
                                result.append('\n');
                            }
                        }
                        lineState.logicalLine = nextParentLine;
                        continue;
                    }
                    ImportMatch match = tryMatchImportDirective(text, first);
                    if (match != null) {
                        String id = match.angled ? absolute(match.path) : relative(sourceId, match.path);
                        if (imported.add(id)) {
                            byte[] bytes = resources.readResource(id);
                            if (bytes == null) {
                                if (firstMissingImport[0] == null) {
                                    firstMissingImport[0] = "Missing GLSL import " + id + " in " + sourceId;
                                }
                                if (ll.endedWithNewline()) {
                                    for (int n = 0; n < ll.physLineSpan(); n++) {
                                        result.append('\n');
                                    }
                                }
                                lineState.logicalLine = nextParentLine;
                                lineState.sourceFile = savedParentFile;
                                continue;
                            }
                            String nestedRaw = DirectGlGlslPreprocessor.normalizeSource(
                                    new String(bytes, StandardCharsets.UTF_8));
                            List<LogicalLine> nestedLines = DirectGlGlslPreprocessor.scanLogicalLines(nestedRaw);
                            int childFile = nextSourceFileId[0]++;
                            if (childFile == savedParentFile) {
                                childFile = nextSourceFileId[0]++;
                            }
                            String nested = expandLines(
                                    nestedRaw, nestedLines, id, resources, imported, macros, liveGlExtensions,
                                    preprocessor32Bit, lineOffset, lineDirectiveDelta, true, childFile,
                                    nextSourceFileId, maxIncludedVersion, firstMissingImport);
                            result.append("#line ").append(Math.max(0, 1 + lineDirectiveDelta)).append(' ').append(childFile).append('\n');
                            result.append(nested);
                            if (!nested.endsWith("\n")) {
                                result.append('\n');
                            }
                            result.append("#line ").append(Math.max(0, nextParentLine + lineDirectiveDelta))
                                    .append(' ').append(savedParentFile);
                            if (ll.endedWithNewline() || idx + 1 < lines.size()) {
                                result.append('\n');
                            }
                        } else if (ll.endedWithNewline()) {
                            for (int n = 0; n < ll.physLineSpan(); n++) {
                                result.append('\n');
                            }
                        }
                        lineState.logicalLine = nextParentLine;
                        lineState.sourceFile = savedParentFile;
                        continue;
                    }
                } else if ("version".equals(kw)) {
                    if (isIncluded) {
                        if (DirectGlGlslPreprocessor.isCondActive(condStack)) {
                            Integer includedVer = parseVersionNumber(text, kwStart + kw.length());
                            if (includedVer != null && includedVer > maxIncludedVersion[0]) {
                                maxIncludedVersion[0] = includedVer;
                            }
                        }
                        if (ll.endedWithNewline()) {
                            for (int n = 0; n < ll.physLineSpan(); n++) {
                                result.append('\n');
                            }
                        }
                        lineState.logicalLine += ll.physLineSpan();
                        continue;
                    }
                    lineState.logicalLine += ll.physLineSpan();
                } else {
                    int lineShift = lineState.logicalLine - ll.startPhysLine();
                    boolean lineOverridden = DirectGlGlslPreprocessor.handleDirective(
                            ll, afterHash, lineShift, macros, liveGlExtensions, preprocessor32Bit, lineOffset,
                            condStack, null, lineState);
                    if (!lineOverridden) {
                        lineState.logicalLine += ll.physLineSpan();
                    }
                }
                result.append(rawSource, ll.rawStartOffset(), ll.rawEndOffset());
                continue;
            }
            result.append(rawSource, ll.rawStartOffset(), ll.rawEndOffset());
            lineState.logicalLine += ll.physLineSpan();
        }
        return result.toString();
    }

    private static @Nullable Integer parseVersionNumber(String text, int afterKw) {
        int i = DirectGlGlslPreprocessor.skipHorizWs(text, afterKw);
        if (i >= text.length() || !Character.isDigit(text.charAt(i))) {
            return null;
        }
        int end = i;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(text.substring(i, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String upgradeLeadingVersion(String expanded, int targetVersion) {
        List<LogicalLine> lines = DirectGlGlslPreprocessor.scanLogicalLines(expanded);
        LeadingVersion version = DirectGlGlslPreprocessor.findLeadingVersion(expanded, lines);
        if (version == null || version.number() >= targetVersion) {
            return expanded;
        }
        int len = expanded.length();
        int p = skipRawToken(expanded, version.hashOffset(), "#");
        if (p < 0) {
            return expanded;
        }
        p = DirectGlGlslPreprocessor.skipWsCommentsAndContinuations(expanded, p);
        p = skipRawToken(expanded, p, "version");
        if (p < 0) {
            return expanded;
        }
        int numStart = DirectGlGlslPreprocessor.skipWsCommentsAndContinuations(expanded, p);
        if (numStart >= len || !Character.isDigit(expanded.charAt(numStart))) {
            return expanded;
        }
        int numEnd = numStart;
        int removedNewlines = 0;
        while (numEnd < len && Character.isDigit(expanded.charAt(numEnd))) {
            numEnd++;
            int afterCont = DirectGlGlslPreprocessor.skipContinuations(expanded, numEnd);
            if (afterCont > numEnd && afterCont < len && Character.isDigit(expanded.charAt(afterCont))) {
                removedNewlines += (afterCont - numEnd) / 2;
                numEnd = afterCont;
            }
        }
        String replacement = Integer.toString(targetVersion);
        if (removedNewlines > 0) {
            replacement += "\\\n".repeat(removedNewlines);
        }
        return expanded.substring(0, numStart) + replacement + expanded.substring(numEnd);
    }

    private static int skipRawToken(String s, int pos, String token) {
        int len = s.length();
        int i = pos;
        for (int k = 0; k < token.length(); k++) {
            i = DirectGlGlslPreprocessor.skipContinuations(s, i);
            if (i >= len || s.charAt(i) != token.charAt(k)) {
                return -1;
            }
            i++;
        }
        return i;
    }

    private record ImportMatch(boolean angled, String path) { }

    private static @Nullable ImportMatch tryMatchImportDirective(String text, int hashIdx) {
        int len = text.length();
        int p = DirectGlGlslPreprocessor.skipHorizWs(text, hashIdx + 1);
        if (!text.regionMatches(p, "moj_import", 0, "moj_import".length())) {
            return null;
        }
        int afterKw = p + "moj_import".length();
        int argStart = DirectGlGlslPreprocessor.skipHorizWs(text, afterKw);
        if (argStart >= len) {
            return null;
        }
        char open = text.charAt(argStart);
        char close;
        boolean angled;
        if (open == '<') {
            close = '>';
            angled = true;
        } else if (open == '"') {
            close = '"';
            angled = false;
        } else {
            return null;
        }
        int pathStart = argStart + 1;
        int pathEnd = pathStart;
        while (pathEnd < len && text.charAt(pathEnd) != close && text.charAt(pathEnd) != '\n') {
            pathEnd++;
        }
        if (pathEnd >= len || text.charAt(pathEnd) != close || pathEnd == pathStart) {
            return null;
        }
        String path = text.substring(pathStart, pathEnd);
        int tail = DirectGlGlslPreprocessor.skipHorizWs(text, pathEnd + 1);
        if (tail < len) {
            return null;
        }
        return new ImportMatch(angled, path);
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
