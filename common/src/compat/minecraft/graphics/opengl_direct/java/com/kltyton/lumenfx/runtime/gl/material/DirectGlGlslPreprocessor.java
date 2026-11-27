package com.kltyton.lumenfx.runtime.gl.material;

import com.kltyton.lumenfx.runtime.gl.material.DirectGlGlslConstExpr.ConstVal;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlGlslMacroExpander.MacroDef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Comment- and preprocessor-aware GLSL source processor for custom material wrapping and strict
 * OpenGL 3.2 Core (GLSL 150) compatibility lowering.
 *
 * <p>For {@code #version 330} shaders targeting strict OpenGL 3.2 Core contexts, runs a GLSL 3.30
 * preprocessing pass (preserving physical line numbers across backslash continuations and multi-line
 * block comments, evaluating {@code #define}/{@code #undef}/{@code #line}, token-stream macro expansion
 * with {@code ##} pasting and rescanning, and nested {@code #if}/{@code #ifdef}/{@code #ifndef}/{@code #elif}/{@code #else}/{@code #endif}
 * conditionals) before stripping {@code layout(location = <expr>)} qualifiers from active declarations only.
 */
final class DirectGlGlslPreprocessor {
    private static final Set<String> AUX_QUALIFIERS = Set.of(
            "centroid", "flat", "smooth", "noperspective", "invariant", "precise",
            "highp", "mediump", "lowp"
    );

    /**
     * Standard OpenGL 3.2/3.3 Core GLSL shader extensions that define {@code #define <EXT> 1}
     * in GLSL preprocessors (excludes API-only GL extensions such as {@code GL_ARB_vertex_array_object}).
     * When a live OpenGL context is active, this set is intersected with the driver's real
     * {@code GL_EXTENSIONS}; in offline/headless lowering without a GL context, this set provides
     * the portable OpenGL 3.2/3.3 Core baseline.
     */
    private static final Set<String> STANDARD_GLSL_EXTENSIONS = Set.of(
            "GL_ARB_ES3_1_compatibility",
            "GL_ARB_ES3_2_compatibility",
            "GL_ARB_arrays_of_arrays",
            "GL_ARB_compute_shader",
            "GL_ARB_conservative_depth",
            "GL_ARB_cull_distance",
            "GL_ARB_derivative_control",
            "GL_ARB_draw_buffers",
            "GL_ARB_draw_instanced",
            "GL_ARB_enhanced_layouts",
            "GL_ARB_explicit_attrib_location",
            "GL_ARB_explicit_uniform_location",
            "GL_ARB_fragment_coord_conventions",
            "GL_ARB_fragment_layer_viewport",
            "GL_ARB_gpu_shader5",
            "GL_ARB_gpu_shader_fp64",
            "GL_ARB_post_depth_coverage",
            "GL_ARB_sample_shading",
            "GL_ARB_separate_shader_objects",
            "GL_ARB_shader_atomic_counter_ops",
            "GL_ARB_shader_atomic_counters",
            "GL_ARB_shader_ballot",
            "GL_ARB_shader_bit_encoding",
            "GL_ARB_shader_clock",
            "GL_ARB_shader_draw_parameters",
            "GL_ARB_shader_group_vote",
            "GL_ARB_shader_image_load_store",
            "GL_ARB_shader_image_size",
            "GL_ARB_shader_precision",
            "GL_ARB_shader_stencil_export",
            "GL_ARB_shader_storage_buffer_object",
            "GL_ARB_shader_subroutine",
            "GL_ARB_shader_texture_image_samples",
            "GL_ARB_shader_texture_lod",
            "GL_ARB_shader_viewport_layer_array",
            "GL_ARB_shading_language_420pack",
            "GL_ARB_shading_language_include",
            "GL_ARB_shading_language_packing",
            "GL_ARB_tessellation_shader",
            "GL_ARB_texture_cube_map_array",
            "GL_ARB_texture_gather",
            "GL_ARB_texture_multisample",
            "GL_ARB_texture_query_levels",
            "GL_ARB_texture_query_lod",
            "GL_ARB_texture_rectangle",
            "GL_ARB_uniform_buffer_object",
            "GL_ARB_vertex_attrib_64bit",
            "GL_ARB_viewport_array",
            "GL_EXT_draw_instanced",
            "GL_EXT_shader_integer_mix",
            "GL_EXT_texture_array"
    );

    /**
     * Additional vendor-specific GLSL shader extensions that are only predefined when a live
     * OpenGL context explicitly advertises them via {@code glGetStringi(GL_EXTENSIONS, i)}.
     */
    private static final Set<String> VENDOR_GLSL_EXTENSIONS = Set.of(
            "GL_AMD_conservative_depth",
            "GL_AMD_gpu_shader_int64",
            "GL_AMD_shader_stencil_export",
            "GL_AMD_shader_trinary_minmax",
            "GL_AMD_texture_texture4",
            "GL_AMD_vertex_shader_layer",
            "GL_AMD_vertex_shader_viewport_index",
            "GL_ARB_gpu_shader_int64",
            "GL_ARM_shader_framebuffer_fetch_depth_stencil",
            "GL_EXT_shader_clock",
            "GL_EXT_shader_framebuffer_fetch",
            "GL_EXT_shader_framebuffer_fetch_non_coherent",
            "GL_EXT_shader_image_load_formatted",
            "GL_EXT_texture_shadow_lod",
            "GL_INTEL_shader_atomic_float_minmax",
            "GL_KHR_blend_equation_advanced",
            "GL_MESA_shader_integer_functions",
            "GL_NV_shader_atomic_float",
            "GL_OVR_multiview",
            "GL_OVR_multiview2"
    );

    /** Isolated helper so offline callers without LWJGL on the classpath never fail class loading. */
    private static final class LiveGlExtensionProbe {
        private static final Map<String, Boolean> PREPROCESSOR_32BIT_CACHE = new ConcurrentHashMap<>();
        private static final Map<String, Boolean> GLSL150_LINE_OFFSET_CACHE = new ConcurrentHashMap<>();

        private LiveGlExtensionProbe() { }

        static @Nullable Set<String> queryCurrentContextExtensions() {
            if (GL.getCapabilities() == null) {
                return null;
            }
            int count = GL11C.glGetInteger(GL30C.GL_NUM_EXTENSIONS);
            if (count <= 0) {
                return null;
            }
            Set<String> exts = new HashSet<>(count * 2);
            for (int i = 0; i < count; i++) {
                String name = GL30C.glGetStringi(GL11C.GL_EXTENSIONS, i);
                if (name != null && name.startsWith("GL_")) {
                    exts.add(name);
                }
            }
            return exts;
        }

        private static String driverCacheKey() {
            return GL11C.glGetString(GL11C.GL_VENDOR) + "\0"
                    + GL11C.glGetString(GL11C.GL_RENDERER) + "\0"
                    + GL11C.glGetString(GL11C.GL_VERSION);
        }

        private static boolean probeVertexShaderCompiles(String source) {
            int shader = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER);
            if (shader == 0) {
                return false;
            }
            try {
                GL20C.glShaderSource(shader, source);
                GL20C.glCompileShader(shader);
                return GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 0;
            } finally {
                GL20C.glDeleteShader(shader);
            }
        }

        static boolean queryPreprocessor32Bit() {
            if (GL.getCapabilities() == null) {
                return false;
            }
            String key = driverCacheKey();
            Boolean cached = PREPROCESSOR_32BIT_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            boolean is32Bit = probeVertexShaderCompiles(
                    "#version 150 core\n#if (0xffffffff == -1)\nvoid main(){gl_Position=vec4(0.0);}\n#else\n#error 64bit\n#endif\n");
            PREPROCESSOR_32BIT_CACHE.put(key, is32Bit);
            return is32Bit;
        }

        /**
         * Returns true when the active driver follows GLSL 1.50 §3.3 (setting the line after
         * {@code #line N} to {@code N + 1}, e.g. NVIDIA) rather than GLSL 3.30 §3.3 ({@code N}, e.g. Mesa).
         */
        static boolean queryGlsl150LineOffsetsByOne() {
            if (GL.getCapabilities() == null) {
                return false;
            }
            String key = driverCacheKey();
            Boolean cached = GLSL150_LINE_OFFSET_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            boolean offsetsByOne = probeVertexShaderCompiles(
                    "#version 150 core\n#line 1\n#if __LINE__ == 2\nvoid main(){gl_Position=vec4(0.0);}\n#else\n#error exact\n#endif\n");
            GLSL150_LINE_OFFSET_CACHE.put(key, offsetsByOne);
            return offsetsByOne;
        }
    }

    static @Nullable Set<String> tryQueryLiveGlExtensions() {
        try {
            return LiveGlExtensionProbe.queryCurrentContextExtensions();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean tryQueryLivePreprocessor32Bit() {
        try {
            return LiveGlExtensionProbe.queryPreprocessor32Bit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean tryQueryLiveGlsl150LineOffsetsByOne() {
        try {
            return LiveGlExtensionProbe.queryGlsl150LineOffsetsByOne();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static Map<String, MacroDef> createInitialMacros(int versionNumber, @Nullable Set<String> liveGlExtensions) {
        Map<String, MacroDef> macros = new HashMap<>();
        macros.put("GL_core_profile", DirectGlGlslMacroExpander.defineObject("GL_core_profile", "1"));
        if (versionNumber >= 330) {
            macros.put("GL_ARB_explicit_attrib_location",
                    DirectGlGlslMacroExpander.defineObject("GL_ARB_explicit_attrib_location", "1"));
        }
        macros.put("__VERSION__", DirectGlGlslMacroExpander.defineObject("__VERSION__", Integer.toString(versionNumber)));
        if (liveGlExtensions != null) {
            for (String ext : liveGlExtensions) {
                if (STANDARD_GLSL_EXTENSIONS.contains(ext) || VENDOR_GLSL_EXTENSIONS.contains(ext)) {
                    macros.putIfAbsent(ext, DirectGlGlslMacroExpander.defineObject(ext, "1"));
                }
            }
        } else {
            for (String ext : STANDARD_GLSL_EXTENSIONS) {
                macros.putIfAbsent(ext, DirectGlGlslMacroExpander.defineObject(ext, "1"));
            }
        }
        return macros;
    }

    record LeadingExtensions(String extensionBlock, String remainingSource) { }

    /**
     * Hoists active {@code #extension} directives that occur before the first active non-preprocessor
     * token in a versionless fragment source so they can be emitted immediately after
     * {@code #version 150 core} and before any generated preamble declarations (GLSL §3.3).
     * User {@code #define} directives remain after the preamble so they cannot collide with
     * preamble parameter/local identifiers.
     */
    static LeadingExtensions extractLeadingExtensions(String source) {
        String s = normalizeSource(source);
        List<LogicalLine> lines = scanLogicalLines(s);
        Set<String> liveGlExtensions = tryQueryLiveGlExtensions();
        boolean preprocessor32Bit = tryQueryLivePreprocessor32Bit();
        int lineOffset = tryQueryLiveGlsl150LineOffsetsByOne() ? 1 : 0;
        Map<String, MacroDef> macros = createInitialMacros(150, liveGlExtensions);
        Deque<CondFrame> condStack = new ArrayDeque<>();
        LineState lineState = new LineState(1);

        StringBuilder extensions = new StringBuilder();
        StringBuilder remaining = new StringBuilder(s.length());

        for (int idx = 0; idx < lines.size(); idx++) {
            LogicalLine ll = lines.get(idx);
            String text = ll.text;
            int first = skipHorizWs(text, 0);
            if (first >= text.length()) {
                remaining.append(s, ll.rawStartOffset, ll.rawEndOffset);
                lineState.logicalLine += ll.physLineSpan;
                continue;
            }
            if (text.charAt(first) == '#') {
                int afterHash = first + 1;
                int kwStart = skipHorizWs(text, afterHash);
                String kw = (kwStart < text.length() && isIdentStart(text.charAt(kwStart)))
                        ? text.substring(kwStart, scanIdentEnd(text, kwStart))
                        : "";
                int lineShift = lineState.logicalLine - ll.startPhysLine;
                boolean activeBefore = isCondActive(condStack);
                boolean lineOverridden = handleDirective(
                        ll, afterHash, lineShift, macros, liveGlExtensions, preprocessor32Bit, lineOffset,
                        condStack, null, lineState);
                if (!lineOverridden) {
                    lineState.logicalLine += ll.physLineSpan;
                }
                if ("extension".equals(kw)) {
                    if (activeBefore) {
                        int argStart = skipHorizWs(text, kwStart + kw.length());
                        int argEnd = text.length();
                        while (argEnd > argStart
                                && (text.charAt(argEnd - 1) == ' ' || text.charAt(argEnd - 1) == '\t')) {
                            argEnd--;
                        }
                        String arg = text.substring(argStart, argEnd);
                        if (!arg.isEmpty()) {
                            extensions.append("#extension ").append(arg).append('\n');
                        }
                    }
                    if (ll.endedWithNewline) {
                        for (int n = 0; n < ll.physLineSpan; n++) {
                            remaining.append('\n');
                        }
                    }
                } else {
                    remaining.append(s, ll.rawStartOffset, ll.rawEndOffset);
                }
                continue;
            }
            if (!isCondActive(condStack)) {
                remaining.append(s, ll.rawStartOffset, ll.rawEndOffset);
                lineState.logicalLine += ll.physLineSpan;
                continue;
            }
            remaining.append(s, ll.rawStartOffset, s.length());
            break;
        }
        return new LeadingExtensions(extensions.toString(), remaining.toString());
    }

    private DirectGlGlslPreprocessor() { }

    static String stripLeadingBom(String source) {
        return (!source.isEmpty() && source.charAt(0) == '\uFEFF') ? source.substring(1) : source;
    }

    /** Returns true when the first non-whitespace, non-comment directive is a GLSL {@code #version} directive. */
    static boolean hasLeadingVersionDirective(String source) {
        String s = normalizeSource(source);
        return findLeadingVersion(s, scanLogicalLines(s)) != null;
    }

    /**
     * Returns true when the preprocessed active source declares a top-level {@code void main(...)}
     * entry point (after comment stripping, line continuations, conditional compilation, and macro
     * expansion). Ignores {@code void main()} inside comments or inactive preprocessor branches,
     * and detects macro-generated entry points such as {@code #define ENTRY main; void ENTRY()}.
     */
    static boolean hasMainFunction(String source) {
        String s = normalizeSource(source);
        List<LogicalLine> allLines = scanLogicalLines(s);
        LeadingVersion version = findLeadingVersion(s, allLines);
        int versionNumber = version != null ? version.number : 150;
        int bodyStartLine;
        List<LogicalLine> bodyLines;
        if (version != null) {
            LogicalLine versionLine = allLines.get(version.lineIndex);
            bodyStartLine = versionLine.startPhysLine + versionLine.physLineSpan;
            bodyLines = allLines.subList(version.lineIndex + 1, allLines.size());
        } else {
            bodyStartLine = 1;
            bodyLines = allLines;
        }
        String text = preprocessGlslBody(bodyLines, bodyStartLine, versionNumber);
        int len = text.length();
        int braceDepth = 0;
        boolean lineStart = true;
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (c == '\n') {
                lineStart = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\f') {
                i++;
                continue;
            }
            if (lineStart && c == '#') {
                int endLine = text.indexOf('\n', i);
                i = endLine < 0 ? len : endLine;
                continue;
            }
            lineStart = false;
            if (c == '{') {
                braceDepth++;
                i++;
                continue;
            }
            if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                i++;
                continue;
            }
            if (braceDepth == 0 && isIdentStart(c)) {
                int end = scanIdentEnd(text, i);
                if ("void".equals(text.substring(i, end))) {
                    int next = skipAllWs(text, end);
                    if (next < len && isIdentStart(text.charAt(next))) {
                        int mainEnd = scanIdentEnd(text, next);
                        if ("main".equals(text.substring(next, mainEnd))) {
                            int paren = skipAllWs(text, mainEnd);
                            if (paren < len && text.charAt(paren) == '(') {
                                int close = findMatchingParenSimple(text, paren);
                                if (close > paren) {
                                    int afterParen = skipAllWs(text, close + 1);
                                    if (afterParen < len && text.charAt(afterParen) == '{') {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
                i = end;
            } else {
                i++;
            }
        }
        return false;
    }

    /**
     * Adapts a GLSL 330 Core shader down to strict GLSL 150 Core by first evaluating GLSL 3.30
     * preprocessor conditionals, macros, and {@code #line} remappings with exact physical line
     * accounting across continuations and block comments, then stripping active
     * {@code layout(location = <expr>[, index = <expr>])} qualifiers and recording the active
     * attribute / fragment-output bindings.
     */
    static String downgradeGlsl330To150(String source, boolean vertexStage,
                                         Map<String, Integer> attribBindings,
                                         Map<String, Integer> fragBindings) {
        return downgradeGlsl330To150(source, vertexStage, attribBindings, fragBindings, new HashMap<>());
    }

    static String downgradeGlsl330To150(String source, boolean vertexStage,
                                         Map<String, Integer> attribBindings,
                                         Map<String, Integer> fragBindings,
                                         Map<String, Integer> fragIndexBindings) {
        String s = normalizeSource(source);
        List<LogicalLine> allLines = scanLogicalLines(s);
        LeadingVersion version = findLeadingVersion(s, allLines);
        if (version == null || version.number != 330) {
            return source;
        }

        // Preserve any leading license/header comments verbatim before #version 150 core.
        String leadingPrefix = s.substring(0, version.hashOffset);
        LogicalLine versionLine = allLines.get(version.lineIndex);
        int bodyStartLine = versionLine.startPhysLine + versionLine.physLineSpan;
        List<LogicalLine> bodyLines = allLines.subList(version.lineIndex + 1, allLines.size());

        // Stage 1: GLSL 3.30 preprocessor evaluation (macros, conditionals, and #line remapping).
        String preprocessed = preprocessGlslBody(bodyLines, bodyStartLine, 330);

        // Stage 2: Lower layout(location = <const-int-expr>[, index = <const-int-expr>]) on active declarations only.
        String loweredBody = lowerLayoutLocations(preprocessed, vertexStage, attribBindings, fragBindings, fragIndexBindings);

        StringBuilder header = new StringBuilder(leadingPrefix.length() + 24 + versionLine.physLineSpan);
        header.append(leadingPrefix).append("#version 150 core\n");
        for (int extra = 1; extra < versionLine.physLineSpan; extra++) {
            header.append('\n');
        }
        header.append(loweredBody);
        return header.toString();
    }

    static String normalizeSource(String source) {
        return stripLeadingBom(source).replace("\r\n", "\n").replace('\r', '\n');
    }

    // -------------------------------------------------------------------------
    // Phase 0: Logical-Line Scanner (Backslash Continuations + Comments)
    // -------------------------------------------------------------------------

    record LogicalLine(
            String text,
            int[] charLines,
            int startPhysLine,
            int physLineSpan,
            int rawStartOffset,
            int rawEndOffset,
            boolean endedWithNewline
    ) { }

    record LeadingVersion(int lineIndex, int hashOffset, int number) { }

    private static final class IntList {
        private int[] data;
        private int size;

        IntList(int initialCapacity) {
            this.data = new int[Math.max(16, initialCapacity)];
        }

        void add(int value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = value;
        }

        void clear() {
            size = 0;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    /**
     * Scans normalized GLSL source into logical lines per GLSL 3.30 + {@code GL_ARB_shading_language_420pack}:
     * <ul>
     *   <li>Backslash-newline ({@code \\\n}) continuations splice tokens onto the current logical line
     *       without advancing the line number of subsequent tokens on that same logical line, while
     *       still contributing to {@code physLineSpan} so the next logical line gets the exact physical line.</li>
     *   <li>Single-line {@code //} comments consume spliced continuations and stop before the unescaped newline.</li>
     *   <li>Block {@code /* ... *&#47;} comments are replaced by a single space without splitting logical
     *       lines (including {@code #version}, {@code #define}, and {@code #if} directives), while advancing
     *       both the intra-line character line offset and {@code physLineSpan} for each embedded newline.</li>
     * </ul>
     */
    static List<LogicalLine> scanLogicalLines(String s) {
        int len = s.length();
        List<LogicalLine> lines = new ArrayList<>();
        int physLine = 1;
        int i = 0;

        StringBuilder text = new StringBuilder();
        IntList charLines = new IntList(64);

        while (i < len) {
            int rawStart = i;
            int startPhysLine = physLine;
            int lineOffset = 0;
            text.setLength(0);
            charLines.clear();
            boolean endedWithNewline = false;

            while (i < len) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < len && s.charAt(i + 1) == '\n') {
                    // Line continuation: join lines; next logical line sees the incremented physLine.
                    i += 2;
                    physLine++;
                    continue;
                }
                if (c == '\n') {
                    i++;
                    physLine++;
                    endedWithNewline = true;
                    break;
                }
                if (c == '/') {
                    int nextIdx = skipContinuations(s, i + 1);
                    if (nextIdx < len && s.charAt(nextIdx) == '/') {
                        int splicedBeforeSlash = (nextIdx - (i + 1)) / 2;
                        physLine += splicedBeforeSlash;
                        i = nextIdx + 1;
                        // Single-line comment extends through backslash-newline continuations.
                        while (i < len) {
                            char cc = s.charAt(i);
                            if (cc == '\\' && i + 1 < len && s.charAt(i + 1) == '\n') {
                                i += 2;
                                physLine++;
                            } else if (cc == '\n') {
                                break;
                            } else {
                                i++;
                            }
                        }
                        continue;
                    }
                    if (nextIdx < len && s.charAt(nextIdx) == '*') {
                        int splicedBeforeStar = (nextIdx - (i + 1)) / 2;
                        physLine += splicedBeforeStar;
                        lineOffset += splicedBeforeStar;
                        text.append(' ');
                        charLines.add(startPhysLine + lineOffset);
                        i = nextIdx + 1;
                        while (i < len) {
                            char bc = s.charAt(i);
                            if (bc == '\\' && i + 1 < len && s.charAt(i + 1) == '\n') {
                                i += 2;
                                physLine++;
                                lineOffset++;
                            } else if (bc == '\n') {
                                i++;
                                physLine++;
                                lineOffset++;
                            } else if (bc == '*') {
                                int slashIdx = skipContinuations(s, i + 1);
                                if (slashIdx < len && s.charAt(slashIdx) == '/') {
                                    int splicedBeforeEnd = (slashIdx - (i + 1)) / 2;
                                    physLine += splicedBeforeEnd;
                                    lineOffset += splicedBeforeEnd;
                                    i = slashIdx + 1;
                                    break;
                                }
                                i++;
                            } else {
                                i++;
                            }
                        }
                        continue;
                    }
                }
                text.append(c);
                charLines.add(startPhysLine + lineOffset);
                i++;
            }

            int span = endedWithNewline
                    ? (physLine - startPhysLine)
                    : Math.max(1, physLine - startPhysLine + 1);
            lines.add(new LogicalLine(text.toString(), charLines.toArray(), startPhysLine, span, rawStart, i, endedWithNewline));
        }

        return lines;
    }

    static int skipContinuations(String s, int i) {
        int len = s.length();
        while (i + 1 < len && s.charAt(i) == '\\' && s.charAt(i + 1) == '\n') {
            i += 2;
        }
        return i;
    }

    static @Nullable LeadingVersion findLeadingVersion(String rawSource, List<LogicalLine> lines) {
        for (int idx = 0; idx < lines.size(); idx++) {
            LogicalLine ll = lines.get(idx);
            String t = ll.text;
            int first = skipHorizWs(t, 0);
            if (first >= t.length()) {
                continue;
            }
            if (t.charAt(first) != '#') {
                return null;
            }
            int kwStart = skipHorizWs(t, first + 1);
            if (!matchesWord(t, kwStart, "version")) {
                return null;
            }
            int afterKw = skipHorizWs(t, kwStart + "version".length());
            if (afterKw == kwStart + "version".length() || afterKw >= t.length() || !Character.isDigit(t.charAt(afterKw))) {
                return null;
            }
            int numEnd = afterKw;
            while (numEnd < t.length() && Character.isDigit(t.charAt(numEnd))) {
                numEnd++;
            }
            int number;
            try {
                number = Integer.parseInt(t.substring(afterKw, numEnd));
            } catch (NumberFormatException ignored) {
                return null;
            }
            int tail = skipHorizWs(t, numEnd);
            if (tail > numEnd && tail < t.length() && isIdentStart(t.charAt(tail))) {
                int profEnd = scanIdentEnd(t, tail);
                String prof = t.substring(tail, profEnd);
                if (!("core".equals(prof) || "compatibility".equals(prof) || "es".equals(prof))) {
                    return null;
                }
                tail = skipHorizWs(t, profEnd);
            }
            if (tail < t.length()) {
                return null;
            }
            int hashOffset = skipWsCommentsAndContinuations(rawSource, ll.rawStartOffset);
            if (hashOffset >= rawSource.length() || rawSource.charAt(hashOffset) != '#') {
                return null;
            }
            return new LeadingVersion(idx, hashOffset, number);
        }
        return null;
    }

    static int skipWsCommentsAndContinuations(String s, int i) {
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < len && s.charAt(i + 1) == '\n') {
                i += 2;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f') {
                i++;
                continue;
            }
            if (c == '/') {
                int nextIdx = skipContinuations(s, i + 1);
                if (nextIdx < len && s.charAt(nextIdx) == '/') {
                    i = nextIdx + 1;
                    while (i < len) {
                        if (s.charAt(i) == '\\' && i + 1 < len && s.charAt(i + 1) == '\n') {
                            i += 2;
                        } else if (s.charAt(i) == '\n') {
                            i++;
                            break;
                        } else {
                            i++;
                        }
                    }
                    continue;
                }
                if (nextIdx < len && s.charAt(nextIdx) == '*') {
                    i = nextIdx + 1;
                    while (i < len) {
                        if (s.charAt(i) == '*') {
                            int slashIdx = skipContinuations(s, i + 1);
                            if (slashIdx < len && s.charAt(slashIdx) == '/') {
                                i = slashIdx + 1;
                                break;
                            }
                        }
                        i++;
                    }
                    continue;
                }
            }
            break;
        }
        return i;
    }

    // -------------------------------------------------------------------------
    // Stage 1: GLSL 3.30 Preprocessor
    // -------------------------------------------------------------------------

    static final class CondFrame {
        final boolean parentActive;
        boolean branchActive;
        boolean anyTaken;
        boolean seenElse;

        CondFrame(boolean parentActive) {
            this.parentActive = parentActive;
        }
    }

    static final class LineState {
        int logicalLine;
        int sourceFile;
        int chunkStartLine;

        LineState(int startLine) {
            this(startLine, 0);
        }

        LineState(int startLine, int sourceFile) {
            this.logicalLine = startLine;
            this.sourceFile = sourceFile;
            this.chunkStartLine = startLine;
        }
    }

    private static String preprocessGlslBody(List<LogicalLine> bodyLines, int bodyStartLine, int versionNumber) {
        Set<String> liveGlExtensions = tryQueryLiveGlExtensions();
        boolean preprocessor32Bit = tryQueryLivePreprocessor32Bit();
        int lineOffset = (versionNumber < 330 && tryQueryLiveGlsl150LineOffsetsByOne()) ? 1 : 0;
        Map<String, MacroDef> macros = createInitialMacros(versionNumber, liveGlExtensions);

        Deque<CondFrame> condStack = new ArrayDeque<>();
        StringBuilder out = new StringBuilder(Math.max(256, bodyLines.size() * 32));
        StringBuilder activeChunkText = new StringBuilder();
        IntList activeChunkLines = new IntList(256);
        LineState lineState = new LineState(bodyStartLine);

        for (int lineIdx = 0; lineIdx < bodyLines.size(); lineIdx++) {
            LogicalLine ll = bodyLines.get(lineIdx);
            String line = ll.text;
            int first = skipHorizWs(line, 0);
            if (first < line.length() && line.charAt(first) == '#') {
                // Flush accumulated active code chunk before processing directive so multi-line
                // function-like macro invocations across adjacent lines expand cleanly.
                if (activeChunkText.length() > 0) {
                    out.append(DirectGlGlslMacroExpander.expand(
                            activeChunkText.toString(), activeChunkLines.toArray(),
                            macros, lineState.chunkStartLine, lineState.sourceFile));
                    activeChunkText.setLength(0);
                    activeChunkLines.clear();
                }
                int lineShift = lineState.logicalLine - ll.startPhysLine;
                boolean lineOverridden = handleDirective(
                        ll, first + 1, lineShift, macros, liveGlExtensions, preprocessor32Bit, lineOffset,
                        condStack, out, lineState);
                if (!lineOverridden) {
                    lineState.logicalLine += ll.physLineSpan;
                }
                if (ll.endedWithNewline || lineIdx + 1 < bodyLines.size()) {
                    out.append('\n');
                    if (!lineOverridden) {
                        for (int extra = 1; extra < ll.physLineSpan; extra++) {
                            out.append('\n');
                        }
                    }
                }
            } else if (isCondActive(condStack)) {
                if (activeChunkText.length() == 0) {
                    lineState.chunkStartLine = lineState.logicalLine;
                }
                int lineShift = lineState.logicalLine - ll.startPhysLine;
                activeChunkText.append(line);
                for (int k = 0; k < ll.charLines.length; k++) {
                    activeChunkLines.add(ll.charLines[k] + lineShift);
                }
                if (ll.endedWithNewline || lineIdx + 1 < bodyLines.size()) {
                    activeChunkText.append('\n');
                    activeChunkLines.add(lineState.logicalLine + ll.physLineSpan - 1);
                }
                lineState.logicalLine += ll.physLineSpan;
            } else {
                // Inactive branch: drop code, emit blank lines to preserve exact physical line span.
                if (ll.endedWithNewline || lineIdx + 1 < bodyLines.size()) {
                    for (int n = 0; n < ll.physLineSpan; n++) {
                        out.append('\n');
                    }
                }
                lineState.logicalLine += ll.physLineSpan;
            }
        }
        if (activeChunkText.length() > 0) {
            out.append(DirectGlGlslMacroExpander.expand(
                    activeChunkText.toString(), activeChunkLines.toArray(),
                    macros, lineState.chunkStartLine, lineState.sourceFile));
        }
        return out.toString();
    }

    static boolean isCondActive(Deque<CondFrame> stack) {
        return stack.isEmpty() || stack.peek().branchActive;
    }

    static boolean handleDirective(LogicalLine ll, int afterHash, int lineShift,
                                   Map<String, MacroDef> macros, @Nullable Set<String> liveGlExtensions,
                                   boolean preprocessor32Bit, int lineOffset, Deque<CondFrame> condStack,
                                   @Nullable StringBuilder out, LineState lineState) {
        String text = ll.text;
        int i = skipHorizWs(text, afterHash);
        if (i >= text.length() || !isIdentStart(text.charAt(i))) return false;
        int kwEnd = scanIdentEnd(text, i);
        String kw = text.substring(i, kwEnd);
        int argStart = skipHorizWs(text, kwEnd);
        int argEnd = text.length();
        while (argEnd > argStart && (text.charAt(argEnd - 1) == ' ' || text.charAt(argEnd - 1) == '\t')) {
            argEnd--;
        }
        String arg = text.substring(argStart, argEnd);

        switch (kw) {
            case "if" -> {
                boolean parent = isCondActive(condStack);
                CondFrame frame = new CondFrame(parent);
                if (parent) {
                    int[] argLines = sliceShiftedLines(ll.charLines, argStart, argEnd, lineShift);
                    boolean take = evaluateIfCondition(
                            arg, argLines, macros, lineState.logicalLine, lineState.sourceFile, preprocessor32Bit) != 0L;
                    frame.branchActive = take;
                    frame.anyTaken = take;
                }
                condStack.push(frame);
            }
            case "ifdef" -> {
                boolean parent = isCondActive(condStack);
                CondFrame frame = new CondFrame(parent);
                if (parent) {
                    String name = readLeadingIdent(arg);
                    boolean take = name != null && macros.containsKey(name);
                    frame.branchActive = take;
                    frame.anyTaken = take;
                }
                condStack.push(frame);
            }
            case "ifndef" -> {
                boolean parent = isCondActive(condStack);
                CondFrame frame = new CondFrame(parent);
                if (parent) {
                    String name = readLeadingIdent(arg);
                    boolean take = name != null && !macros.containsKey(name);
                    frame.branchActive = take;
                    frame.anyTaken = take;
                }
                condStack.push(frame);
            }
            case "elif" -> {
                if (condStack.isEmpty()) return false;
                CondFrame frame = condStack.peek();
                if (!frame.seenElse && frame.parentActive && !frame.anyTaken) {
                    int[] argLines = sliceShiftedLines(ll.charLines, argStart, argEnd, lineShift);
                    boolean take = evaluateIfCondition(
                            arg, argLines, macros, lineState.logicalLine, lineState.sourceFile, preprocessor32Bit) != 0L;
                    frame.branchActive = take;
                    if (take) frame.anyTaken = true;
                } else {
                    frame.branchActive = false;
                }
            }
            case "else" -> {
                if (condStack.isEmpty()) return false;
                CondFrame frame = condStack.peek();
                if (!frame.seenElse) {
                    frame.seenElse = true;
                    frame.branchActive = frame.parentActive && !frame.anyTaken;
                    if (frame.branchActive) frame.anyTaken = true;
                } else {
                    frame.branchActive = false;
                }
            }
            case "endif" -> {
                if (!condStack.isEmpty()) condStack.pop();
            }
            case "define" -> {
                if (!isCondActive(condStack)) return false;
                parseDefine(arg, macros);
            }
            case "undef" -> {
                if (!isCondActive(condStack)) return false;
                String name = readLeadingIdent(arg);
                if (name != null) macros.remove(name);
            }
            case "line" -> {
                if (!isCondActive(condStack)) return false;
                int[] argLines = sliceShiftedLines(ll.charLines, argStart, argEnd, lineShift);
                return handleLineDirective(arg, argLines, macros, lineOffset, out, lineState);
            }
            case "extension" -> {
                if (!isCondActive(condStack)) return false;
                handleExtensionDirective(arg, macros, liveGlExtensions, out);
            }
            case "pragma", "error" -> {
                if (!isCondActive(condStack)) return false;
                if (out != null) {
                    out.append('#').append(kw);
                    if (!arg.isEmpty()) out.append(' ').append(arg);
                }
            }
            default -> {
                // Unknown directive in active branch: preserve verbatim.
                if (isCondActive(condStack) && out != null) {
                    out.append('#').append(text.substring(afterHash).trim());
                }
            }
        }
        return false;
    }

    private static void handleExtensionDirective(String arg, Map<String, MacroDef> macros,
                                                 @Nullable Set<String> liveGlExtensions, @Nullable StringBuilder out) {
        int colon = arg.indexOf(':');
        String extName = (colon >= 0 ? arg.substring(0, colon) : arg).trim();
        String behavior = (colon >= 0 ? arg.substring(colon + 1) : "").trim();
        // Strict OpenGL 3.2 Core may lack GL_ARB_explicit_attrib_location; we lower locations instead.
        if ("GL_ARB_explicit_attrib_location".equals(extName)) {
            return;
        }
        if (out != null) {
            out.append("#extension ").append(arg);
        }
        if ("require".equals(behavior) && !"all".equals(extName) && extName.startsWith("GL_")) {
            if (liveGlExtensions == null || liveGlExtensions.contains(extName)) {
                macros.putIfAbsent(extName, DirectGlGlslMacroExpander.defineObject(extName, "1"));
            }
        }
    }

    private static int[] sliceShiftedLines(int[] charLines, int start, int end, int shift) {
        int count = Math.max(0, end - start);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = charLines[start + i] + shift;
        }
        return out;
    }

    /**
     * Handles GLSL {@code #line <line> [<source-string-number>]} with macro expansion.
     * Per GLSL 3.30 Core §3.3, the line immediately following {@code #line N [F]} has
     * {@code __LINE__ == N} ({@code lineOffset == 0}); drivers following GLSL 1.50 §3.3
     * set the following line to {@code N + 1} ({@code lineOffset == 1}).
     */
    private static boolean handleLineDirective(String arg, int[] argLines, Map<String, MacroDef> macros,
                                               int lineOffset, @Nullable StringBuilder out, LineState lineState) {
        String expanded = DirectGlGlslMacroExpander.expand(
                arg, argLines, macros, lineState.logicalLine, lineState.sourceFile).trim();
        if (expanded.isEmpty()) {
            return false;
        }
        String[] parts = expanded.split("\\s+");
        Integer newLine = DirectGlGlslConstExpr.evaluateLocationExpr(parts[0], Map.of());
        if (newLine == null) {
            if (out != null) {
                out.append("#line ").append(expanded);
            }
            return false;
        }
        lineState.logicalLine = newLine + lineOffset;
        Integer newFile = null;
        if (parts.length >= 2) {
            newFile = DirectGlGlslConstExpr.evaluateLocationExpr(parts[1], Map.of());
            if (newFile != null) {
                lineState.sourceFile = newFile;
            }
        }
        if (out != null) {
            out.append("#line ").append(newLine);
            if (newFile != null) {
                out.append(' ').append(newFile);
            }
        }
        return true;
    }

    private static void parseDefine(String arg, Map<String, MacroDef> macros) {
        if (arg.isEmpty() || !isIdentStart(arg.charAt(0))) return;
        int nameEnd = scanIdentEnd(arg, 0);
        String name = arg.substring(0, nameEnd);
        // GLSL/C rule: '(' immediately following name (with NO intervening whitespace) defines a function-like macro.
        if (nameEnd < arg.length() && arg.charAt(nameEnd) == '(') {
            int close = findMatchingParenSimple(arg, nameEnd);
            if (close < 0) return;
            String paramText = arg.substring(nameEnd + 1, close).trim();
            List<String> params = new ArrayList<>();
            if (!paramText.isEmpty()) {
                for (String p : paramText.split(",")) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) params.add(trimmed);
                }
            }
            String body = arg.substring(close + 1).trim();
            macros.put(name, DirectGlGlslMacroExpander.defineFunction(name, params, body));
        } else {
            String body = arg.substring(nameEnd).trim();
            macros.put(name, DirectGlGlslMacroExpander.defineObject(name, body));
        }
    }

    private record ExprWithLines(String text, int[] charLines) { }

    private static long evaluateIfCondition(String rawExpr, int[] rawExprLines, Map<String, MacroDef> macros,
                                            int logicalLine, int sourceFile, boolean preprocessor32Bit) {
        // First resolve `defined(NAME)` and `defined NAME` BEFORE macro expansion, preserving per-char lines.
        ExprWithLines resolvedDefined = resolveDefinedOperators(rawExpr, rawExprLines, macros, logicalLine);
        // Then expand remaining macros with full token-stream rescanning and ## pasting.
        String expanded = DirectGlGlslMacroExpander.expand(
                resolvedDefined.text, resolvedDefined.charLines, macros, logicalLine, sourceFile);
        // Finally evaluate GLSL integer constant expression (unknown identifiers -> 0).
        return DirectGlGlslConstExpr.evaluatePreprocessorExpr(expanded, preprocessor32Bit);
    }

    private static ExprWithLines resolveDefinedOperators(String expr, int[] exprLines,
                                                         Map<String, MacroDef> macros, int fallbackLine) {
        int len = expr.length();
        StringBuilder sb = new StringBuilder(len);
        IntList lines = new IntList(len);
        int i = 0;
        while (i < len) {
            char c = expr.charAt(i);
            int curLine = i < exprLines.length ? exprLines[i] : fallbackLine;
            if (isIdentStart(c)) {
                int end = scanIdentEnd(expr, i);
                String tok = expr.substring(i, end);
                if ("defined".equals(tok)) {
                    int p = skipAllWs(expr, end);
                    boolean paren = (p < len && expr.charAt(p) == '(');
                    if (paren) p = skipAllWs(expr, p + 1);
                    if (p < len && isIdentStart(expr.charAt(p))) {
                        int idEnd = scanIdentEnd(expr, p);
                        String id = expr.substring(p, idEnd);
                        int after = idEnd;
                        if (paren) {
                            after = skipAllWs(expr, idEnd);
                            if (after < len && expr.charAt(after) == ')') after++;
                        }
                        sb.append(macros.containsKey(id) ? '1' : '0');
                        lines.add(curLine);
                        i = after;
                        continue;
                    }
                }
                sb.append(tok);
                for (int k = i; k < end; k++) {
                    lines.add(k < exprLines.length ? exprLines[k] : curLine);
                }
                i = end;
            } else {
                sb.append(c);
                lines.add(curLine);
                i++;
            }
        }
        return new ExprWithLines(sb.toString(), lines.toArray());
    }

    private static List<String> splitCommaTopLevel(String inner) {
        List<String> args = new ArrayList<>();
        int len = inner.length();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < len; i++) {
            char c = inner.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) {
                args.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        args.add(inner.substring(start));
        return args;
    }

    // -------------------------------------------------------------------------
    // Stage 2: Active layout(location = <const-expr>) Lowering
    // -------------------------------------------------------------------------

    private static String lowerLayoutLocations(String s, boolean vertexStage,
                                               Map<String, Integer> attribBindings,
                                               Map<String, Integer> fragBindings,
                                               Map<String, Integer> fragIndexBindings) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        Map<String, ConstVal> globalConstInts = new HashMap<>();

        int i = 0;
        int braceDepth = 0;
        boolean lineStart = true;
        String stmtStorage = null;

        while (i < len) {
            char c = s.charAt(i);
            if (c == '\n') {
                out.append(c);
                lineStart = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\f') {
                out.append(c);
                i++;
                continue;
            }
            if (lineStart && c == '#') {
                int endLine = s.indexOf('\n', i);
                if (endLine < 0) endLine = len;
                out.append(s, i, endLine);
                i = endLine;
                continue;
            }
            lineStart = false;
            if (c == ';') {
                stmtStorage = null;
                out.append(c);
                i++;
                continue;
            }
            if (c == '{') {
                braceDepth++;
                stmtStorage = null;
                out.append(c);
                i++;
                continue;
            }
            if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                stmtStorage = null;
                out.append(c);
                i++;
                continue;
            }

            if (isIdentStart(c)) {
                int identEnd = scanIdentEnd(s, i);
                String word = s.substring(i, identEnd);

                // Track global `const int NAME = <expr>;` / `const uint NAME = <expr>;` for location expressions.
                if (braceDepth == 0 && "const".equals(word)) {
                    tryRecordGlobalConstInt(s, identEnd, globalConstInts);
                }
                if (braceDepth == 0 && ("in".equals(word) || "out".equals(word))) {
                    stmtStorage = word;
                }

                if ("layout".equals(word)) {
                    LayoutResult layout = tryParseLayoutQualifiers(s, identEnd, vertexStage, globalConstInts);
                    if (layout != null) {
                        DeclBindings bindings = parseFollowingDeclaration(s, layout.closeParenEnd, stmtStorage);
                        boolean applicable = bindings != null && (
                                (vertexStage && "in".equals(bindings.storage) && layout.location != null)
                                        || (!vertexStage && "out".equals(bindings.storage)
                                        && (layout.location != null || layout.index != null)));
                        if (applicable) {
                            for (String name : bindings.names) {
                                if (vertexStage) {
                                    attribBindings.put(name, layout.location);
                                } else {
                                    if (layout.location != null) {
                                        fragBindings.put(name, layout.location);
                                    }
                                    if (layout.index != null) {
                                        fragIndexBindings.put(name, layout.index);
                                    }
                                }
                            }
                            if (!layout.remainingQualifiers.isEmpty()) {
                                out.append("layout(").append(String.join(", ", layout.remainingQualifiers)).append(')');
                                i = layout.closeParenEnd;
                            } else {
                                int next = layout.closeParenEnd;
                                if (next < len && (s.charAt(next) == ' ' || s.charAt(next) == '\t')) {
                                    while (next < len && (s.charAt(next) == ' ' || s.charAt(next) == '\t')) next++;
                                }
                                if (out.length() > 0 && isIdentPart(out.charAt(out.length() - 1))
                                        && next < len && isIdentStart(s.charAt(next))) {
                                    out.append(' ');
                                }
                                i = next;
                            }
                            continue;
                        }
                    }
                }
                out.append(word);
                i = identEnd;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static void tryRecordGlobalConstInt(String s, int afterConst, Map<String, ConstVal> constInts) {
        int len = s.length();
        int i = skipAllWs(s, afterConst);
        DirectGlGlslConstExpr.Kind scalarKind = null;
        int vecDim = 0;
        DirectGlGlslConstExpr.Kind vecElemKind = null;
        while (i < len && isIdentStart(s.charAt(i))) {
            int end = scanIdentEnd(s, i);
            String tok = s.substring(i, end);
            if ("highp".equals(tok) || "mediump".equals(tok) || "lowp".equals(tok)) {
                i = skipAllWs(s, end);
                continue;
            }
            scalarKind = DirectGlGlslConstExpr.scalarTypeKind(tok);
            if (scalarKind == null) {
                vecDim = DirectGlGlslConstExpr.vectorTypeDim(tok);
                vecElemKind = DirectGlGlslConstExpr.vectorElemKind(tok);
                if (vecDim <= 0 || vecElemKind == null) return;
            }
            i = skipAllWs(s, end);
            break;
        }
        if (scalarKind == null && vecDim <= 0) return;
        // Skip optional type-side array brackets (e.g. `const int[2] SLOTS = ...;`)
        if (i < len && s.charAt(i) == '[') {
            int close = s.indexOf(']', i + 1);
            if (close < 0) return;
            i = skipAllWs(s, close + 1);
        }
        int semi = s.indexOf(';', i);
        if (semi < 0) return;
        for (String decl : splitCommaTopLevel(s.substring(i, semi))) {
            String trimmed = decl.trim();
            if (trimmed.isEmpty() || !isIdentStart(trimmed.charAt(0))) continue;
            int nameEnd = scanIdentEnd(trimmed, 0);
            String varName = trimmed.substring(0, nameEnd);
            int p = skipAllWs(trimmed, nameEnd);
            boolean isArrayDecl = false;
            if (p < trimmed.length() && trimmed.charAt(p) == '[') {
                int close = trimmed.indexOf(']', p + 1);
                if (close < 0) continue;
                isArrayDecl = true;
                p = skipAllWs(trimmed, close + 1);
            }
            if (p >= trimmed.length() || trimmed.charAt(p) != '=') continue;
            ConstVal val = DirectGlGlslConstExpr.evaluateConstExpr(trimmed.substring(p + 1).trim(), constInts);
            if (val != null) {
                if (isArrayDecl || val.isVector()) {
                    constInts.put(varName, val);
                } else if (scalarKind != null) {
                    constInts.put(varName, val.castScalar(scalarKind));
                }
            }
        }
    }

    private record LayoutResult(int closeParenEnd, @Nullable Integer location,
                                @Nullable Integer index, List<String> remainingQualifiers) { }

    private record DeclBindings(String storage, List<String> names) { }

    private static @Nullable LayoutResult tryParseLayoutQualifiers(String s, int afterLayoutKw,
                                                                   boolean vertexStage,
                                                                   Map<String, ConstVal> constInts) {
        int len = s.length();
        int open = skipAllWs(s, afterLayoutKw);
        if (open >= len || s.charAt(open) != '(') return null;
        int close = findMatchingParenSimple(s, open);
        if (close < 0) return null;

        String inner = s.substring(open + 1, close);
        List<String> items = splitCommaTopLevel(inner);
        Integer foundLocation = null;
        Integer foundIndex = null;
        List<String> remaining = new ArrayList<>();
        for (String rawItem : items) {
            Integer loc = parseNamedIntQualifier(rawItem, "location", constInts);
            if (loc != null) {
                foundLocation = loc;
                continue;
            }
            if (!vertexStage) {
                Integer idx = parseNamedIntQualifier(rawItem, "index", constInts);
                if (idx != null) {
                    foundIndex = idx;
                    continue;
                }
            }
            String trimmed = rawItem.trim();
            if (!trimmed.isEmpty()) remaining.add(trimmed);
        }
        if (foundLocation == null && foundIndex == null) return null;
        return new LayoutResult(close + 1, foundLocation, foundIndex, remaining);
    }

    private static @Nullable Integer parseNamedIntQualifier(String item, String qualifierName, Map<String, ConstVal> constInts) {
        int len = item.length();
        int i = skipAllWs(item, 0);
        if (i >= len || !isIdentStart(item.charAt(i))) return null;
        int kwEnd = scanIdentEnd(item, i);
        if (!qualifierName.equals(item.substring(i, kwEnd))) return null;
        int eq = skipAllWs(item, kwEnd);
        if (eq >= len || item.charAt(eq) != '=') return null;
        String expr = item.substring(eq + 1).trim();
        if (expr.isEmpty()) return null;
        return DirectGlGlslConstExpr.evaluateLocationExpr(expr, constInts);
    }

    private static @Nullable DeclBindings parseFollowingDeclaration(String s, int startPos,
                                                                    @Nullable String precedingStorage) {
        int len = s.length();
        int i = skipAllWs(s, startPos);
        String storage = precedingStorage;

        while (i < len && isIdentStart(s.charAt(i))) {
            int end = scanIdentEnd(s, i);
            String tok = s.substring(i, end);
            if ("in".equals(tok) || "out".equals(tok)) {
                storage = tok;
                i = skipAllWs(s, end);
                break;
            }
            if (AUX_QUALIFIERS.contains(tok)) {
                i = skipAllWs(s, end);
                continue;
            }
            if ("layout".equals(tok)) {
                int open = skipAllWs(s, end);
                int close = (open < len && s.charAt(open) == '(') ? findMatchingParenSimple(s, open) : -1;
                if (close < 0) return null;
                i = skipAllWs(s, close + 1);
                continue;
            }
            if (storage != null) {
                break;
            }
            return null;
        }
        if (storage == null) return null;

        while (i < len && isIdentStart(s.charAt(i))) {
            int end = scanIdentEnd(s, i);
            String tok = s.substring(i, end);
            if (AUX_QUALIFIERS.contains(tok)) {
                i = skipAllWs(s, end);
                continue;
            }
            if ("layout".equals(tok)) {
                int open = skipAllWs(s, end);
                int close = (open < len && s.charAt(open) == '(') ? findMatchingParenSimple(s, open) : -1;
                if (close < 0) return null;
                i = skipAllWs(s, close + 1);
                continue;
            }
            break;
        }

        if (i >= len || !isIdentStart(s.charAt(i))) return null;
        int typeEnd = scanIdentEnd(s, i);
        i = skipAllWs(s, typeEnd);
        // Handle GLSL type-side array brackets such as `vec4[2] fragArr;`
        while (i < len && s.charAt(i) == '[') {
            int closeBracket = findMatchingDelimiterSimple(s, i, '[', ']');
            if (closeBracket < 0) return null;
            i = skipAllWs(s, closeBracket + 1);
        }
        if (i >= len || s.charAt(i) == '{' || !isIdentStart(s.charAt(i))) return null;

        List<String> names = new ArrayList<>(2);
        while (i < len && isIdentStart(s.charAt(i))) {
            int nameEnd = scanIdentEnd(s, i);
            names.add(s.substring(i, nameEnd));
            int afterName = skipAllWs(s, nameEnd);
            while (afterName < len && s.charAt(afterName) == '[') {
                int closeBracket = findMatchingDelimiterSimple(s, afterName, '[', ']');
                if (closeBracket < 0) return null;
                afterName = skipAllWs(s, closeBracket + 1);
            }
            if (afterName < len && s.charAt(afterName) == ';') {
                return new DeclBindings(storage, names);
            }
            if (afterName < len && s.charAt(afterName) == ',') {
                i = skipAllWs(s, afterName + 1);
                continue;
            }
            return null;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Token & Delimiter Helpers
    // -------------------------------------------------------------------------

    private static @Nullable String readLeadingIdent(String s) {
        if (s.isEmpty() || !isIdentStart(s.charAt(0))) return null;
        return s.substring(0, scanIdentEnd(s, 0));
    }

    private static int findMatchingParenSimple(String s, int openIdx) {
        return findMatchingDelimiterSimple(s, openIdx, '(', ')');
    }

    private static int findMatchingDelimiterSimple(String s, int openIdx, char openCh, char closeCh) {
        int len = s.length();
        int depth = 0;
        for (int i = openIdx; i < len; i++) {
            char c = s.charAt(i);
            if (c == openCh) depth++;
            else if (c == closeCh) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int skipAllWs(String s, int i) {
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f') i++;
            else break;
        }
        return i;
    }

    static int skipHorizWs(String s, int i) {
        int len = s.length();
        while (i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    private static boolean matchesWord(String s, int i, String word) {
        int end = i + word.length();
        if (end > s.length() || !s.regionMatches(i, word, 0, word.length())) return false;
        return end == s.length() || !isIdentPart(s.charAt(end));
    }

    static int scanIdentEnd(String s, int start) {
        int len = s.length();
        int i = start + 1;
        while (i < len && isIdentPart(s.charAt(i))) i++;
        return i;
    }

    static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }
}
