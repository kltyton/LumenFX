package com.kltyton.lumenfx.client.compat.iris;

import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackClassifier;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackKind;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces.RawSpecularEncoding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.FileNode;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.option.MergedStringOption;
import net.irisshaders.iris.shaderpack.option.values.OptionValues;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;

final class IrisShaderProfile {
    static final IrisShaderProfile UNKNOWN = new IrisShaderProfile(
            ShaderpackKind.UNKNOWN,
            ColourEncoding.UNKNOWN,
            false,
            Float.NaN
    );

    private static final String JITTER_PATH = "/lib/antialiasing/jitter.glsl";
    private static final Pattern COMPOSITE_DECODE = Pattern.compile(
            "\\bcolor\\s*=\\s*pow\\s*\\(\\s*color\\s*,\\s*vec3\\s*\\(\\s*2\\.2(?:0*)?\\s*\\)\\s*\\)\\s*;");
    private static final Pattern GLSL_COMMENTS = Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*");
    private static final Pattern COLORIMG0_WRITE = Pattern.compile("\\bimageStore\\s*\\(\\s*colorimg0\\b");
    private static final Pattern GENERIC_JITTER_HINT = Pattern.compile(
            "(?i)\\b[A-Za-z0-9_]*(?:framemod\\d+|jitter|halton|subpixel)[A-Za-z0-9_]*\\b");
    private static final ProgramId[] GBUFFER_JITTER_PROGRAMS = {
            ProgramId.Terrain,
            ProgramId.TerrainSolid,
            ProgramId.TerrainCutout,
            ProgramId.Basic,
            ProgramId.Water,
            ProgramId.Entities,
            ProgramId.DhTerrain,
            ProgramId.DhWater
    };

    private static final float[][] STANDARD_JITTER_OFFSETS = {
            {0.125F, -0.375F},
            {-0.125F, 0.375F},
            {0.625F, 0.125F},
            {0.375F, -0.625F},
            {-0.625F, 0.625F},
            {-0.875F, -0.125F},
            {0.375F, -0.875F},
            {0.875F, 0.875F}
    };
    private static final float[][] STANDARD_JITTER_OFFSETS_2 = {
            {1.0F, 0.0F},
            {0.0F, 1.0F}
    };
    private static final Pattern VEC2_ARRAY_DECL = Pattern.compile(
            "\\bvec2(?:\\s*\\[\\s*\\d*\\s*\\])?\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\[\\s*\\d*\\s*\\])?\\s*=");
    private static final Pattern VEC2_LITERAL = Pattern.compile(
            "^vec2\\s*\\(\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?f?)\\s*,\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?f?)\\s*\\)$");

    record JitterDetection(boolean known, float[] affine, int sampleCount, float @Nullable [][] offsets) {
        static final JitterDetection UNKNOWN_JITTER = new JitterDetection(
                false, new float[] { Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN }, 0, null);
        static final JitterDetection NO_JITTER = new JitterDetection(
                true, new float[] { 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F }, 1, null);

        JitterDetection(boolean known, float scaleX, float scaleY, int sampleCount, float @Nullable [][] offsets) {
            this(known, new float[] { scaleX, 0.0F, 0.0F, 0.0F, scaleY, 0.0F }, sampleCount, offsets);
        }

        JitterDetection(boolean known, float scale, int sampleCount, float @Nullable [][] offsets) {
            this(known, scale, scale, sampleCount, offsets);
        }

        JitterDetection(boolean known, float scale, int sampleCount) {
            this(known, scale, scale, sampleCount, sampleCount == 8 ? STANDARD_JITTER_OFFSETS
                    : sampleCount == 2 ? STANDARD_JITTER_OFFSETS_2 : null);
        }

        float[] matrix() {
            return new float[] { affine[0], affine[1], affine[3], affine[4] };
        }

        float scaleX() {
            return affine[1] == 0.0F && affine[2] == 0.0F ? affine[0] : Float.NaN;
        }

        float scaleY() {
            return affine[3] == 0.0F && affine[5] == 0.0F ? affine[4] : Float.NaN;
        }

        float scale() {
            float sx = scaleX();
            float sy = scaleY();
            return Float.compare(sx, sy) == 0 ? sx : Float.NaN;
        }
    }

    private record ParsedJitterTable(String arrayName, float[][] offsets) { }

    private final ShaderpackKind kind;
    private final ColourEncoding colourEncoding;
    private final boolean jitterKnown;
    private final float[] jitterAffine;
    private final RawSpecularEncoding rawSpecularEncoding;
    private final float cameraExposure;
    private final int temporalSampleCount;
    private final float @Nullable [][] jitterOffsets;

    private IrisShaderProfile(
            final ShaderpackKind kind,
            final ColourEncoding colourEncoding,
            final boolean jitterKnown,
            final float jitterScale
    ) {
        this(kind, colourEncoding, jitterKnown, jitterScale, RawSpecularEncoding.UNKNOWN, Float.NaN);
    }

    private IrisShaderProfile(ShaderpackKind kind, ColourEncoding colourEncoding, boolean jitterKnown,
            float jitterScale, RawSpecularEncoding rawSpecularEncoding, float cameraExposure) {
        this(kind, colourEncoding, jitterKnown, jitterScale, rawSpecularEncoding, cameraExposure,
                jitterKnown ? jitterScale == 0 ? 1 : 8 : 0);
    }

    private IrisShaderProfile(ShaderpackKind kind, ColourEncoding colourEncoding, boolean jitterKnown,
            float jitterScale, RawSpecularEncoding rawSpecularEncoding, float cameraExposure, int temporalSampleCount) {
        this(kind, colourEncoding, jitterKnown,
                new float[] { jitterScale, 0.0F, 0.0F, 0.0F, jitterScale, 0.0F },
                rawSpecularEncoding, cameraExposure, temporalSampleCount,
                jitterKnown && temporalSampleCount == 8 ? STANDARD_JITTER_OFFSETS
                        : jitterKnown && temporalSampleCount == 2 ? STANDARD_JITTER_OFFSETS_2 : null);
    }

    private IrisShaderProfile(ShaderpackKind kind, ColourEncoding colourEncoding, boolean jitterKnown,
            float[] jitterAffine, RawSpecularEncoding rawSpecularEncoding, float cameraExposure,
            int temporalSampleCount, float @Nullable [][] jitterOffsets) {
        this.kind = kind;
        this.colourEncoding = colourEncoding;
        this.jitterKnown = jitterKnown;
        this.jitterAffine = jitterAffine.clone();
        this.rawSpecularEncoding = rawSpecularEncoding;
        this.cameraExposure = cameraExposure;
        this.temporalSampleCount = temporalSampleCount;
        this.jitterOffsets = jitterOffsets;
    }

    ShaderpackKind kind() {
        return this.kind;
    }

    ColourEncoding colourEncoding() {
        return this.colourEncoding;
    }

    boolean jitterKnown() {
        return this.jitterKnown;
    }

    float jitterScale() {
        float sx = jitterScaleX();
        float sy = jitterScaleY();
        return Float.compare(sx, sy) == 0 ? sx : Float.NaN;
    }

    float jitterScaleX() {
        return this.jitterAffine[1] == 0.0F && this.jitterAffine[2] == 0.0F ? this.jitterAffine[0] : Float.NaN;
    }

    float jitterScaleY() {
        return this.jitterAffine[3] == 0.0F && this.jitterAffine[5] == 0.0F ? this.jitterAffine[4] : Float.NaN;
    }

    float[] jitterMatrix() {
        return new float[] { this.jitterAffine[0], this.jitterAffine[1], this.jitterAffine[3], this.jitterAffine[4] };
    }

    float[] jitterAffine() {
        return this.jitterAffine.clone();
    }

    RawSpecularEncoding rawSpecularEncoding(ShaderPack pack) {
        return rawSpecularEncoding;
    }

    float cameraExposure() { return cameraExposure; }
    int temporalSampleCount() { return temporalSampleCount; }
    float @Nullable [][] jitterOffsets() { return jitterOffsets; }

    private static RawSpecularEncoding readRawSpecularEncoding(ShaderPack pack) {
        Integer mode = integerOption(pack, "RP_MODE");
        if (mode == null) return RawSpecularEncoding.UNKNOWN;
        return switch (mode) {
            case 0, 1 -> RawSpecularEncoding.NONE;
            case 2 -> RawSpecularEncoding.SEUS_PBR;
            case 3 -> RawSpecularEncoding.LAB_PBR;
            default -> RawSpecularEncoding.UNKNOWN;
        };
    }

    static IrisShaderProfile of(final ShaderPack pack, final ProgramSet programs) {
        return of(pack, programs, null);
    }

    static IrisShaderProfile of(final ShaderPack pack, final ProgramSet programs,
                                final @Nullable InternalTextureFormat colorFormat) {
        if (pack == null || programs == null) {
            return UNKNOWN;
        }
        IrisShaderProfile profile = jitterProfile(pack, programs);
        if (profile.kind == ShaderpackKind.COMPLEMENTARY_REIMAGINED) {
            float exposure = readExposure(pack, programs);
            return new IrisShaderProfile(profile.kind, profile.colourEncoding, profile.jitterKnown,
                    profile.jitterAffine,
                    readRawSpecularEncoding(pack), exposure, profile.temporalSampleCount, profile.jitterOffsets);
        }
        List<String> postSources = orderedPostSources(programs);
        ColourEncoding inferred = IrisCompositeStagePlanner.inferPreCompositeEncoding(
                postSources, isFloatingPointFormat(colorFormat));
        JitterDetection genericJitter = detectGenericJitter(programs);
        return new IrisShaderProfile(ShaderpackKind.GENERIC, inferred,
                genericJitter.known(), genericJitter.affine(),
                readRawSpecularEncoding(pack), readExposure(pack, programs),
                genericJitter.sampleCount(), genericJitter.offsets());
    }

    private static float readExposure(final ShaderPack pack, final ProgramSet programs) {
        float exposure = Float.NaN;
        String tone = source(pack.getShaderPackOptions().getIncludes(), "/program/composite5.glsl");
        var composite = programs.getComposite(ProgramArrayId.Composite);
        if (composite.length > 5 && composite[5] != null && composite[5].isValid() && tone != null
                && Pattern.compile("\\bcolor\\s*=\\s*TM_EXPOSURE\\s*\\*\\s*color\\s*;").matcher(tone).find()
                && Pattern.compile("\\bDoCompTonemap\\s*\\(\\s*color\\s*\\)\\s*;").matcher(tone).find()) {
            String value = stringOption(pack, "TM_EXPOSURE");
            if (value != null) try {
                float parsed = Float.parseFloat(value.trim());
                if (Float.isFinite(parsed) && parsed > 0) exposure = parsed;
            } catch (NumberFormatException ignored) {
                // An unavailable exposure contract is carried as NaN, rather than a fabricated multiplier.
            }
        }
        return exposure;
    }

    private static List<String> orderedPostSources(final ProgramSet programs) {
        List<String> sources = new ArrayList<>();
        ProgramSource[] composites = programs.getComposite(ProgramArrayId.Composite);
        ComputeSource[][] computes = programs.getCompute(ProgramArrayId.Composite);
        int maxCount = Math.max(composites == null ? 0 : composites.length, computes == null ? 0 : computes.length);
        for (int i = 0; i < maxCount; i++) {
            if (computes != null && i < computes.length && computes[i] != null) {
                for (ComputeSource compute : computes[i]) {
                    if (compute != null && compute.isValid()) {
                        compute.getSource().ifPresent(src -> {
                            String cleaned = GLSL_COMMENTS.matcher(src).replaceAll(" ");
                            if (COLORIMG0_WRITE.matcher(IrisCompositeStagePlanner.extractReachableMainSource(cleaned)).find()) {
                                sources.add(src);
                            }
                        });
                    }
                }
            }
            ProgramSource pass = composites != null && i < composites.length ? composites[i] : null;
            if (pass != null && pass.isValid() && writesColortex0(pass)) {
                pass.getFragmentSource().ifPresent(sources::add);
            }
        }
        ComputeSource[] finalCompute = programs.getFinalCompute();
        if (finalCompute != null) {
            for (ComputeSource compute : finalCompute) {
                if (compute != null && compute.isValid()) {
                    compute.getSource().ifPresent(sources::add);
                }
            }
        }
        programs.get(ProgramId.Final).filter(ProgramSource::isValid)
                .flatMap(ProgramSource::getFragmentSource)
                .ifPresent(sources::add);
        return sources;
    }

    private static JitterDetection detectGenericJitter(final ProgramSet programs) {
        List<String> vertexSources = new ArrayList<>();
        for (ProgramId id : GBUFFER_JITTER_PROGRAMS) {
            programs.get(id).filter(ProgramSource::isValid)
                    .flatMap(ProgramSource::getVertexSource)
                    .ifPresent(vertexSources::add);
        }
        return detectGenericJitterFromSources(vertexSources);
    }

    static JitterDetection detectGenericJitterFromSources(final List<String> vertexSources) {
        if (vertexSources == null || vertexSources.isEmpty()) {
            return JitterDetection.UNKNOWN_JITTER;
        }
        boolean anyNonEmpty = false;
        boolean anyJitterHint = false;
        JitterDetection detected = null;
        for (String rawVertex : vertexSources) {
            String cleaned = GLSL_COMMENTS.matcher(rawVertex == null ? "" : rawVertex).replaceAll(" ").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            anyNonEmpty = true;
            String reachable = IrisCompositeStagePlanner.extractReachableMainSource(cleaned);
            if (!GENERIC_JITTER_HINT.matcher(reachable).find()) {
                continue;
            }
            anyJitterHint = true;
            String compactFull = cleaned.replaceAll("\\s+", "");
            boolean hasFramemod8 = reachable.contains("framemod8")
                    || (reachable.contains("TAAJitter") && cleaned.contains("framemod8"));
            boolean hasFramemod2 = reachable.contains("framemod2")
                    || (reachable.contains("TAAJitter") && cleaned.contains("framemod2"));
            ParsedJitterTable parsedTable = null;
            int sampleCount = 0;
            if (hasFramemod8) {
                parsedTable = extractOrderedJitterTable(cleaned, "framemod8", 8);
                sampleCount = 8;
            } else if (hasFramemod2) {
                parsedTable = extractOrderedJitterTable(cleaned, "framemod2", 2);
                sampleCount = 2;
            }
            if (parsedTable != null) {
                float[] affine = extractGenericJitterScale(reachable, parsedTable.arrayName());
                if (affine == null) {
                    return JitterDetection.UNKNOWN_JITTER;
                }
                JitterDetection current = new JitterDetection(true, affine, sampleCount, parsedTable.offsets());
                if (detected != null && (detected.sampleCount() != current.sampleCount()
                        || !matrixEquals(detected.affine(), current.affine())
                        || !tablesEqual(detected.offsets(), current.offsets()))) {
                    return JitterDetection.UNKNOWN_JITTER;
                }
                detected = current;
            } else {
                return JitterDetection.UNKNOWN_JITTER;
            }
        }
        if (!anyNonEmpty) {
            return JitterDetection.UNKNOWN_JITTER;
        }
        if (!anyJitterHint) {
            return JitterDetection.NO_JITTER;
        }
        return detected != null ? detected : JitterDetection.UNKNOWN_JITTER;
    }

    private static @Nullable ParsedJitterTable extractOrderedJitterTable(
            final String cleanedSource,
            final String frameUniformName,
            final int expectedCount
    ) {
        ParsedJitterTable found = null;
        Matcher matcher = VEC2_ARRAY_DECL.matcher(cleanedSource);
        while (matcher.find()) {
            String arrayName = matcher.group(1);
            int idx = matcher.end();
            while (idx < cleanedSource.length() && Character.isWhitespace(cleanedSource.charAt(idx))) {
                idx++;
            }
            if (idx >= cleanedSource.length()) {
                continue;
            }
            char openChar;
            char closeChar;
            int innerStart;
            if (cleanedSource.charAt(idx) == '{') {
                openChar = '{';
                closeChar = '}';
                innerStart = idx + 1;
            } else if (cleanedSource.startsWith("vec2", idx)) {
                int pos = idx + 4;
                while (pos < cleanedSource.length() && Character.isWhitespace(cleanedSource.charAt(pos))) {
                    pos++;
                }
                if (pos < cleanedSource.length() && cleanedSource.charAt(pos) == '[') {
                    int brClose = cleanedSource.indexOf(']', pos + 1);
                    if (brClose < 0) {
                        continue;
                    }
                    pos = brClose + 1;
                    while (pos < cleanedSource.length() && Character.isWhitespace(cleanedSource.charAt(pos))) {
                        pos++;
                    }
                }
                if (pos >= cleanedSource.length() || cleanedSource.charAt(pos) != '(') {
                    continue;
                }
                openChar = '(';
                closeChar = ')';
                innerStart = pos + 1;
            } else {
                continue;
            }
            int closeIdx = findMatchingDelimiter(cleanedSource, innerStart - 1, openChar, closeChar);
            if (closeIdx <= innerStart) {
                continue;
            }
            int[] indexMap = resolveFrameIndexMap(cleanedSource, arrayName, frameUniformName, expectedCount, closeIdx + 1);
            if (indexMap == null) {
                continue;
            }
            float[][] parsed = parseOrderedVec2List(cleanedSource.substring(innerStart, closeIdx), expectedCount);
            if (parsed == null) {
                continue;
            }
            float[][] ordered = new float[expectedCount][2];
            for (int f = 0; f < expectedCount; f++) {
                ordered[f] = parsed[indexMap[f]];
            }
            if (found != null && !tablesEqual(found.offsets(), ordered)) {
                return null;
            }
            found = new ParsedJitterTable(arrayName, ordered);
        }
        return found;
    }

    private static int @Nullable [] resolveFrameIndexMap(
            final String cleanedSource,
            final String arrayName,
            final String frameUniformName,
            final int expectedCount,
            final int searchFrom
    ) {
        Pattern indexPattern = Pattern.compile("\\b" + Pattern.quote(arrayName) + "\\s*\\[");
        Matcher matcher = indexPattern.matcher(cleanedSource);
        if (searchFrom > 0 && searchFrom < cleanedSource.length()) {
            matcher.region(searchFrom, cleanedSource.length());
        }
        int[] resolved = null;
        while (matcher.find()) {
            int openBracket = matcher.end() - 1;
            int closeBracket = findMatchingDelimiter(cleanedSource, openBracket, '[', ']');
            if (closeBracket <= openBracket) {
                return null;
            }
            int after = closeBracket + 1;
            while (after < cleanedSource.length() && Character.isWhitespace(cleanedSource.charAt(after))) {
                after++;
            }
            if (after < cleanedSource.length() && cleanedSource.charAt(after) == '='
                    && (after + 1 >= cleanedSource.length() || cleanedSource.charAt(after + 1) != '=')) {
                continue;
            }
            String indexExpr = cleanedSource.substring(openBracket + 1, closeBracket);
            if (!Pattern.compile("\\b" + Pattern.quote(frameUniformName) + "\\b").matcher(indexExpr).find()) {
                return null;
            }
            int[] map = evaluateFrameIndexExpression(indexExpr, frameUniformName, expectedCount);
            if (map == null) {
                return null;
            }
            if (resolved != null && !java.util.Arrays.equals(resolved, map)) {
                return null;
            }
            resolved = map;
        }
        return resolved;
    }

    private static int @Nullable [] evaluateFrameIndexExpression(
            final String expr,
            final String frameUniformName,
            final int expectedCount
    ) {
        int[] map = new int[expectedCount];
        for (int f = 0; f < expectedCount; f++) {
            FrameExprParser parser = new FrameExprParser(expr, frameUniformName, f);
            Integer value = parser.parse();
            if (value == null || value < 0 || value >= expectedCount) {
                return null;
            }
            map[f] = value;
        }
        return map;
    }

    private static final class FrameExprParser {
        private final String input;
        private final String frameVar;
        private final int frameValue;
        private int pos;

        FrameExprParser(String input, String frameVar, int frameValue) {
            this.input = input;
            this.frameVar = frameVar;
            this.frameValue = frameValue;
        }

        @Nullable Integer parse() {
            Integer v = parseBitOr();
            skipWs();
            return pos == input.length() ? v : null;
        }

        private void skipWs() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        private boolean consume(String token) {
            skipWs();
            if (input.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private @Nullable Integer parseBitOr() {
            Integer left = parseBitXor();
            while (left != null && consume("|")) {
                Integer right = parseBitXor();
                if (right == null) return null;
                left = left | right;
            }
            return left;
        }

        private @Nullable Integer parseBitXor() {
            Integer left = parseBitAnd();
            while (left != null && consume("^")) {
                Integer right = parseBitAnd();
                if (right == null) return null;
                left = left ^ right;
            }
            return left;
        }

        private @Nullable Integer parseBitAnd() {
            Integer left = parseAddSub();
            while (left != null && consume("&")) {
                Integer right = parseAddSub();
                if (right == null) return null;
                left = left & right;
            }
            return left;
        }

        private @Nullable Integer parseAddSub() {
            Integer left = parseMulDiv();
            while (left != null) {
                if (consume("+")) {
                    Integer right = parseMulDiv();
                    if (right == null) return null;
                    left = left + right;
                } else if (consume("-")) {
                    Integer right = parseMulDiv();
                    if (right == null) return null;
                    left = left - right;
                } else {
                    break;
                }
            }
            return left;
        }

        private @Nullable Integer parseMulDiv() {
            Integer left = parseUnary();
            while (left != null) {
                if (consume("*")) {
                    Integer right = parseUnary();
                    if (right == null) return null;
                    left = left * right;
                } else if (consume("/")) {
                    Integer right = parseUnary();
                    if (right == null || right == 0) return null;
                    left = left / right;
                } else if (consume("%")) {
                    Integer right = parseUnary();
                    if (right == null || right <= 0) return null;
                    left = Math.floorMod(left, right);
                } else {
                    break;
                }
            }
            return left;
        }

        private @Nullable Integer parseUnary() {
            if (consume("+")) return parseUnary();
            if (consume("-")) {
                Integer v = parseUnary();
                return v == null ? null : -v;
            }
            return parsePrimary();
        }

        private @Nullable Integer parsePrimary() {
            skipWs();
            if (pos >= input.length()) return null;
            if (input.charAt(pos) == '(') {
                pos++;
                Integer v = parseBitOr();
                if (v == null || !consume(")")) return null;
                return v;
            }
            if (Character.isDigit(input.charAt(pos))) {
                int start = pos;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
                // Reject floating-point constants in array index arithmetic unless whole.
                if (pos < input.length() && (input.charAt(pos) == '.' || input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                    return null;
                }
                if (pos < input.length() && (input.charAt(pos) == 'u' || input.charAt(pos) == 'U')) pos++;
                try {
                    return Integer.parseInt(input.substring(start, pos).replaceAll("[uU]", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (Character.isLetter(input.charAt(pos)) || input.charAt(pos) == '_') {
                int start = pos;
                while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) pos++;
                String ident = input.substring(start, pos);
                if (consume("(")) {
                    if (ident.equals("int") || ident.equals("uint")) {
                        Integer arg = parseBitOr();
                        return arg != null && consume(")") ? arg : null;
                    }
                    if (ident.equals("clamp")) {
                        Integer a = parseBitOr();
                        if (a == null || !consume(",")) return null;
                        Integer lo = parseBitOr();
                        if (lo == null || !consume(",")) return null;
                        Integer hi = parseBitOr();
                        if (hi == null || !consume(")")) return null;
                        return Math.max(lo, Math.min(hi, a));
                    }
                    if (ident.equals("min") || ident.equals("max")) {
                        Integer a = parseBitOr();
                        if (a == null || !consume(",")) return null;
                        Integer b = parseBitOr();
                        if (b == null || !consume(")")) return null;
                        return ident.equals("min") ? Math.min(a, b) : Math.max(a, b);
                    }
                    return null;
                }
                return ident.equals(frameVar) ? frameValue : null;
            }
            return null;
        }
    }

    private static float @Nullable [][] parseOrderedVec2List(final String content, final int expectedCount) {
        List<String> elements = new ArrayList<>(expectedCount);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                elements.add(content.substring(start, i).trim());
                start = i + 1;
            }
        }
        String tail = content.substring(start).trim();
        if (!tail.isEmpty()) {
            elements.add(tail);
        }
        if (elements.size() != expectedCount) {
            return null;
        }
        float[][] result = new float[expectedCount][2];
        for (int i = 0; i < expectedCount; i++) {
            Matcher lit = VEC2_LITERAL.matcher(elements.get(i));
            if (!lit.matches()) {
                return null;
            }
            try {
                float x = Float.parseFloat(lit.group(1));
                float y = Float.parseFloat(lit.group(2));
                if (!Float.isFinite(x) || !Float.isFinite(y)) {
                    return null;
                }
                result[i][0] = x;
                result[i][1] = y;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return result;
    }

    private static int findMatchingDelimiter(
            final String source,
            final int openPos,
            final char openChar,
            final char closeChar
    ) {
        int depth = 1;
        for (int i = openPos + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == openChar) {
                depth++;
            } else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean tablesEqual(final float @Nullable [][] a, final float @Nullable [][] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Float.compare(a[i][0], b[i][0]) != 0 || Float.compare(a[i][1], b[i][1]) != 0) {
                return false;
            }
        }
        return true;
    }

    private static float @Nullable [] extractGenericJitterScale(final String reachableVertex, final String jitterArrayName) {
        String compactStatements = reachableVertex.replace('\n', ' ').replace('\r', ' ');
        java.util.Deque<JitterBlockScope> scopeStack = new java.util.ArrayDeque<>();
        int conditionalDepth = 0;
        java.util.Map<String, float[]> varScales = new java.util.HashMap<>();
        java.util.Set<String> clipWVars = new java.util.HashSet<>();
        java.util.Set<String> glPosBaseVars = new java.util.HashSet<>();
        java.util.Set<String> glPosPreservingVars = new java.util.HashSet<>();
        java.util.Set<String> taintedAfterJitterVars = new java.util.HashSet<>();
        float[] glPosAppliedScale = null;
        boolean sawClearedGlPosJitter = false;
        Pattern arrayRef = Pattern.compile("\\b" + Pattern.quote(jitterArrayName) + "\\s*\\[");

        for (BlockToken tok : tokenizeBlockStatements(compactStatements)) {
            if (tok.kind() == BlockTokenKind.OPEN_BRACE) {
                boolean isCtrl = isControlFlowHeader(tok.text());
                scopeStack.push(new JitterBlockScope(isCtrl));
                if (isCtrl) conditionalDepth++;
                continue;
            }
            if (tok.kind() == BlockTokenKind.CLOSE_BRACE) {
                if (scopeStack.isEmpty()) return null;
                JitterBlockScope closed = scopeStack.pop();
                if (closed.isControl) {
                    conditionalDepth--;
                }
                restoreShadowedScope(closed, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                continue;
            }
            String stmt = tok.text().trim();
            if (stmt.isEmpty()) continue;

            // Reject conditional branches / loops / ternary expressions affecting jitter or gl_Position: cannot prove constant scale.
            if (conditionalDepth > 0 && (arrayRef.matcher(stmt).find() || referencesAny(stmt, varScales.keySet()) || stmt.contains("gl_Position"))) {
                return null;
            }
            if (isControlFlowOrTernaryWithJitter(stmt, arrayRef, varScales.keySet())) {
                return null;
            }

            int compoundOpIdx = findTopLevelCompoundOp(stmt);
            int eqIdx = compoundOpIdx >= 0 ? -1 : findTopLevelEquals(stmt);
            int opIdx = compoundOpIdx >= 0 ? compoundOpIdx : eqIdx;
            if (opIdx <= 0) {
                if (!scopeStack.isEmpty()) {
                    recordUninitializedLocalDecls(stmt, scopeStack.peek(),
                            varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                }
                continue;
            }

            char opChar = compoundOpIdx >= 0 ? stmt.charAt(compoundOpIdx) : '=';
            String lhsRaw = stmt.substring(0, opIdx).trim();
            String rhs = stmt.substring(opIdx + (compoundOpIdx >= 0 ? 2 : 1)).trim();
            String strippedLhs = stripGlslDeclQualifiers(lhsRaw);
            boolean isDeclaration = !strippedLhs.equals(lhsRaw);
            lhsRaw = strippedLhs;
            if (lhsRaw.contains(" ")) continue;

            String lhsBase = lhsRaw;
            String swizzle = "";
            int dot = lhsRaw.indexOf('.');
            if (dot > 0) {
                lhsBase = lhsRaw.substring(0, dot).trim();
                swizzle = lhsRaw.substring(dot + 1).trim();
            }
            if (isDeclaration && opChar == '=' && !scopeStack.isEmpty()) {
                shadowLocalVar(scopeStack.peek(), lhsBase,
                        varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
            }

            boolean rhsRefsJitter = arrayRef.matcher(rhs).find() || referencesAny(rhs, varScales.keySet());
            if ("gl_Position".equals(lhsBase)) {
                if (opChar == '+' || opChar == '-') {
                    if (!rhsRefsJitter) {
                        if (glPosAppliedScale != null) {
                            float[] c = evalConstantVec2Factor(rhs);
                            if (c != null) {
                                float[] cBias = new float[] { 0.0F, 0.0F, opChar == '-' ? -c[0] : c[0], 0.0F, 0.0F, opChar == '-' ? -c[1] : c[1] };
                                glPosAppliedScale = applySwizzleAccumulate(glPosAppliedScale, swizzle, cBias);
                                if (glPosAppliedScale == null) return null;
                                continue;
                            }
                            return null;
                        }
                        continue;
                    }
                    if (referencesAny(rhs, taintedAfterJitterVars)) return null;
                    float[] delta = evaluateJitterSumExpr(unwrapGlslPosVec4(rhs), jitterArrayName, varScales, clipWVars, glPosBaseVars);
                    if (delta == null) return null;
                    if (opChar == '-') {
                        delta = negateAffine(delta);
                    }
                    if (glPosAppliedScale == null) glPosAppliedScale = new float[6];
                    glPosAppliedScale = applySwizzleAccumulate(glPosAppliedScale, swizzle, delta);
                    if (glPosAppliedScale == null) return null;
                } else if (opChar == '=') {
                    if (rhsRefsJitter) {
                        if (referencesAny(rhs, taintedAfterJitterVars)) return null;
                        boolean preservesOldGlPos = Pattern.compile("\\bgl_Position\\b").matcher(rhs).find()
                                || referencesAny(rhs, glPosPreservingVars);
                        float[] eval = evaluateJitterSumExpr(unwrapGlslPosVec4(rhs), jitterArrayName, varScales, clipWVars, glPosBaseVars);
                        if (eval == null) return null;
                        if (preservesOldGlPos && glPosAppliedScale != null) {
                            glPosAppliedScale = applySwizzleAccumulate(glPosAppliedScale, swizzle, eval);
                        } else {
                            glPosAppliedScale = applySwizzleAssign(glPosAppliedScale, swizzle, eval);
                        }
                        if (glPosAppliedScale == null) return null;
                    } else if (glPosAppliedScale != null) {
                        if (swizzle.isEmpty()) {
                            if (referencesGlPosOrVars(rhs, glPosPreservingVars, taintedAfterJitterVars)) {
                                return null;
                            }
                            glPosAppliedScale = null;
                            glPosBaseVars.clear();
                            glPosPreservingVars.clear();
                            taintedAfterJitterVars.clear();
                            sawClearedGlPosJitter = true;
                        } else {
                            if (swizzleTouchesW(swizzle) || referencesGlPosOrVars(rhs, glPosPreservingVars, taintedAfterJitterVars)) {
                                return null;
                            }
                            glPosAppliedScale = clearGlPosSwizzleRows(glPosAppliedScale, swizzle);
                            if (glPosAppliedScale == null) return null;
                            sawClearedGlPosJitter = true;
                        }
                    } else if (swizzle.isEmpty()) {
                        glPosAppliedScale = null;
                        glPosBaseVars.clear();
                        glPosPreservingVars.clear();
                        taintedAfterJitterVars.clear();
                    }
                } else {
                    if (glPosAppliedScale != null || rhsRefsJitter) return null;
                }
                continue;
            }

            if (opChar == '=' && !rhsRefsJitter) {
                String unwrappedRhs = unwrapParens(rhs);
                if (!swizzle.isEmpty() && (varScales.containsKey(lhsBase) || glPosPreservingVars.contains(lhsBase)
                        || taintedAfterJitterVars.contains(lhsBase) || clipWVars.contains(lhsBase))) {
                    if (clipWVars.contains(lhsBase) || taintedAfterJitterVars.contains(lhsBase)) {
                        return null;
                    }
                    if ("z".equals(swizzle) || "b".equals(swizzle) || "w".equals(swizzle) || "a".equals(swizzle)
                            || "zw".equals(swizzle) || "ba".equals(swizzle)) {
                        continue;
                    }
                    float[] curScale = varScales.get(lhsBase);
                    if (curScale == null || isClipWNormToken(unwrappedRhs) || clipWVars.contains(unwrappedRhs)) {
                        return null;
                    }
                    if (isDirectGlPosExpr(unwrappedRhs)) {
                        String rhsSwz = glPosSwizzle(unwrappedRhs);
                        if (glPosAppliedScale != null && hasNonZeroForSwizzle(glPosAppliedScale, rhsSwz)) {
                            float[] copied = readAffineSwizzle(glPosAppliedScale, rhsSwz);
                            if (copied == null) return null;
                            float[] updated = applySwizzleAssign(curScale, swizzle, copied);
                            if (updated == null) return null;
                            varScales.put(lhsBase, updated);
                            glPosPreservingVars.add(lhsBase);
                        } else {
                            float[] updated = applySwizzleAssign(curScale, swizzle, new float[6]);
                            if (updated == null) return null;
                            varScales.put(lhsBase, updated);
                        }
                    } else if (isGlPosBaseExpr(unwrappedRhs, glPosBaseVars)) {
                        float[] updated = applySwizzleAssign(curScale, swizzle, new float[6]);
                        if (updated == null) return null;
                        varScales.put(lhsBase, updated);
                    } else {
                        float[] c = evalConstantVec2Factor(rhs);
                        if (c == null) return null;
                        float[] updated = applySwizzleAssign(curScale, swizzle,
                                new float[] { 0.0F, 0.0F, c[0], 0.0F, 0.0F, c[1] });
                        if (updated == null) return null;
                        varScales.put(lhsBase, updated);
                    }
                    continue;
                }
                if (isClipWNormToken(unwrappedRhs) || clipWVars.contains(unwrappedRhs)) {
                    clearVarState(lhsBase, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                    clipWVars.add(lhsBase);
                } else if (isDirectGlPosExpr(unwrappedRhs)) {
                    String rhsSwz = glPosSwizzle(unwrappedRhs);
                    if (glPosAppliedScale != null && hasNonZeroForSwizzle(glPosAppliedScale, rhsSwz)) {
                        float[] copied = readAffineSwizzle(glPosAppliedScale, rhsSwz);
                        if (copied == null) {
                            clearVarState(lhsBase, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                            taintedAfterJitterVars.add(lhsBase);
                        } else {
                            clearVarState(lhsBase, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                            varScales.put(lhsBase, copied);
                            glPosPreservingVars.add(lhsBase);
                        }
                    } else {
                        clearVarState(lhsBase, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                        glPosBaseVars.add(lhsBase);
                    }
                } else if (isGlPosBaseExpr(unwrappedRhs, glPosBaseVars)) {
                    clearVarState(lhsBase, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                    glPosBaseVars.add(lhsBase);
                } else {
                    clearVarState(lhsBase, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                    if (glPosAppliedScale != null && referencesGlPosOrVars(rhs, glPosPreservingVars, taintedAfterJitterVars)) {
                        taintedAfterJitterVars.add(lhsBase);
                    }
                }
                continue;
            }

            if (opChar == '*' || opChar == '/') {
                float[] cur = varScales.get(lhsBase);
                if (cur == null) continue;
                float[] factor = evalConstantVec2Factor(rhs);
                if (factor == null) return null;
                if (opChar == '/') {
                    if (factor[0] == 0.0F || factor[1] == 0.0F) return null;
                    factor = new float[] { 1.0F / factor[0], 1.0F / factor[1] };
                }
                float[] updated = cur.clone();
                if ("x".equals(swizzle) || "r".equals(swizzle)) {
                    updated[0] *= factor[0]; updated[1] *= factor[0]; updated[2] *= factor[0];
                } else if ("y".equals(swizzle) || "g".equals(swizzle)) {
                    updated[3] *= factor[1]; updated[4] *= factor[1]; updated[5] *= factor[1];
                } else if (swizzle.isEmpty() || "xy".equals(swizzle) || "rg".equals(swizzle)) {
                    updated[0] *= factor[0]; updated[1] *= factor[0]; updated[2] *= factor[0];
                    updated[3] *= factor[1]; updated[4] *= factor[1]; updated[5] *= factor[1];
                } else if ("yx".equals(swizzle) || "gr".equals(swizzle)) {
                    updated[3] *= factor[0]; updated[4] *= factor[0]; updated[5] *= factor[0];
                    updated[0] *= factor[1]; updated[1] *= factor[1]; updated[2] *= factor[1];
                } else {
                    return null;
                }
                varScales.put(lhsBase, updated);
            } else if (opChar == '+' || opChar == '-') {
                float[] cur = varScales.get(lhsBase);
                if (!rhsRefsJitter && cur == null) {
                    if (glPosAppliedScale != null && referencesGlPosOrVars(rhs, glPosPreservingVars, taintedAfterJitterVars)) {
                        taintedAfterJitterVars.add(lhsBase);
                    }
                    continue;
                }
                if (referencesAny(rhs, taintedAfterJitterVars)) return null;
                float[] delta = evaluateJitterSumExpr(rhs, jitterArrayName, varScales, clipWVars, glPosBaseVars);
                if (delta == null) return null;
                if (opChar == '-') delta = negateAffine(delta);
                float[] base = cur != null ? cur : new float[6];
                float[] updated = applySwizzleAccumulate(base, swizzle, delta);
                if (updated == null) return null;
                varScales.put(lhsBase, updated);
                if (glPosBaseVars.remove(lhsBase)) glPosPreservingVars.add(lhsBase);
            } else if (rhsRefsJitter) {
                if (referencesAny(rhs, taintedAfterJitterVars)) return null;
                float[] evaluated = evaluateJitterSumExpr(rhs, jitterArrayName, varScales, clipWVars, glPosBaseVars);
                if (evaluated == null) return null;
                float[] updated = applySwizzleAssign(varScales.get(lhsBase), swizzle, evaluated);
                if (updated == null) return null;
                varScales.put(lhsBase, updated);
                if (containsGlPosBaseTerm(rhs, glPosBaseVars)) {
                    glPosPreservingVars.add(lhsBase);
                } else {
                    glPosPreservingVars.remove(lhsBase);
                }
                glPosBaseVars.remove(lhsBase);
                clipWVars.remove(lhsBase);
                taintedAfterJitterVars.remove(lhsBase);
            }
        }
        if (!scopeStack.isEmpty()) return null;
        if (glPosAppliedScale == null) {
            return sawClearedGlPosJitter ? new float[6] : null;
        }
        for (float v : glPosAppliedScale) {
            if (!Float.isFinite(v)) return null;
        }
        return glPosAppliedScale;
    }

    private static boolean referencesGlPosOrVars(
            String expr,
            java.util.Set<String> glPosPreservingVars,
            java.util.Set<String> taintedAfterJitterVars
    ) {
        return Pattern.compile("\\bgl_Position\\b").matcher(expr).find()
                || referencesAny(expr, glPosPreservingVars)
                || referencesAny(expr, taintedAfterJitterVars);
    }

    private static boolean swizzleTouchesW(String swizzle) {
        return swizzle.indexOf('w') >= 0 || swizzle.indexOf('a') >= 0;
    }

    private static boolean isDirectGlPosExpr(String token) {
        return "gl_Position".equals(token) || token.matches("gl_Position\\.[rgbaxyzw]{1,4}");
    }

    private static String glPosSwizzle(String token) {
        int dot = token.indexOf('.');
        return dot > 0 ? token.substring(dot + 1).trim() : "";
    }

    private static boolean hasNonZeroForSwizzle(float[] affine, String swizzle) {
        boolean checkX = swizzle.isEmpty() || swizzle.indexOf('x') >= 0 || swizzle.indexOf('r') >= 0;
        boolean checkY = swizzle.isEmpty() || swizzle.indexOf('y') >= 0 || swizzle.indexOf('g') >= 0;
        if (checkX && (affine[0] != 0.0F || affine[1] != 0.0F || affine[2] != 0.0F)) return true;
        if (checkY && (affine[3] != 0.0F || affine[4] != 0.0F || affine[5] != 0.0F)) return true;
        return false;
    }

    private static float @Nullable [] clearGlPosSwizzleRows(float[] cur, String swizzle) {
        float[] next = cur.clone();
        for (int i = 0; i < swizzle.length(); i++) {
            switch (swizzle.charAt(i)) {
                case 'x', 'r' -> { next[0] = 0.0F; next[1] = 0.0F; next[2] = 0.0F; }
                case 'y', 'g' -> { next[3] = 0.0F; next[4] = 0.0F; next[5] = 0.0F; }
                case 'z', 'b' -> { }
                default -> { return null; }
            }
        }
        return next;
    }

    private enum BlockTokenKind { STATEMENT, OPEN_BRACE, CLOSE_BRACE }
    private record BlockToken(BlockTokenKind kind, String text) { }

    private static List<BlockToken> tokenizeBlockStatements(String source) {
        List<BlockToken> out = new ArrayList<>();
        int parenDepth = 0;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(' || c == '[') parenDepth++;
            else if (c == ')' || c == ']') {
                if (parenDepth > 0) parenDepth--;
            } else if (parenDepth == 0 && c == ';') {
                String piece = source.substring(start, i).trim();
                if (!piece.isEmpty()) out.add(new BlockToken(BlockTokenKind.STATEMENT, piece));
                start = i + 1;
            } else if (parenDepth == 0 && c == '{') {
                out.add(new BlockToken(BlockTokenKind.OPEN_BRACE, source.substring(start, i).trim()));
                start = i + 1;
            } else if (parenDepth == 0 && c == '}') {
                String piece = source.substring(start, i).trim();
                if (!piece.isEmpty()) out.add(new BlockToken(BlockTokenKind.STATEMENT, piece));
                out.add(new BlockToken(BlockTokenKind.CLOSE_BRACE, ""));
                start = i + 1;
            }
        }
        if (start < source.length()) {
            String piece = source.substring(start).trim();
            if (!piece.isEmpty()) out.add(new BlockToken(BlockTokenKind.STATEMENT, piece));
        }
        return out;
    }

    private static boolean isControlFlowHeader(String header) {
        String s = header.trim();
        return s.startsWith("if ") || s.startsWith("if(")
                || s.startsWith("else") || s.startsWith("for ") || s.startsWith("for(")
                || s.startsWith("while ") || s.startsWith("while(")
                || s.startsWith("switch ") || s.startsWith("switch(");
    }

    private static boolean isControlFlowOrTernaryWithJitter(String stmt, Pattern arrayRef, java.util.Set<String> vars) {
        boolean hasControl = stmt.startsWith("if ") || stmt.startsWith("if(")
                || stmt.startsWith("else") || stmt.startsWith("for ") || stmt.startsWith("for(")
                || stmt.startsWith("while ") || stmt.startsWith("while(")
                || stmt.indexOf('?') >= 0;
        if (!hasControl) return false;
        return arrayRef.matcher(stmt).find() || referencesAny(stmt, vars) || stmt.contains("gl_Position");
    }

    private static int findTopLevelCompoundOp(String s) {
        int d = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') d++;
            else if (c == ')' || c == ']') { if (d > 0) d--; }
            else if (d == 0 && s.charAt(i + 1) == '=' && (c == '+' || c == '-' || c == '*' || c == '/')) {
                return i;
            }
        }
        return -1;
    }

    private record VarStateSnapshot(
            float @Nullable [] scale,
            boolean clipW,
            boolean glPosBase,
            boolean glPosPreserving,
            boolean taintedAfterJitter
    ) { }

    private static final class JitterBlockScope {
        final boolean isControl;
        final java.util.Map<String, VarStateSnapshot> shadowed = new java.util.HashMap<>();

        JitterBlockScope(boolean isControl) {
            this.isControl = isControl;
        }
    }

    private static void shadowLocalVar(
            JitterBlockScope scope,
            String name,
            java.util.Map<String, float[]> varScales,
            java.util.Set<String> clipWVars,
            java.util.Set<String> glPosBaseVars,
            java.util.Set<String> glPosPreservingVars,
            java.util.Set<String> taintedAfterJitterVars
    ) {
        if (name.isEmpty() || scope.shadowed.containsKey(name)) return;
        float[] existingScale = varScales.get(name);
        scope.shadowed.put(name, new VarStateSnapshot(
                existingScale != null ? existingScale.clone() : null,
                clipWVars.contains(name),
                glPosBaseVars.contains(name),
                glPosPreservingVars.contains(name),
                taintedAfterJitterVars.contains(name)
        ));
    }

    private static void clearVarState(
            String name,
            java.util.Map<String, float[]> varScales,
            java.util.Set<String> clipWVars,
            java.util.Set<String> glPosBaseVars,
            java.util.Set<String> glPosPreservingVars,
            java.util.Set<String> taintedAfterJitterVars
    ) {
        varScales.remove(name);
        clipWVars.remove(name);
        glPosBaseVars.remove(name);
        glPosPreservingVars.remove(name);
        taintedAfterJitterVars.remove(name);
    }

    private static void restoreShadowedScope(
            JitterBlockScope scope,
            java.util.Map<String, float[]> varScales,
            java.util.Set<String> clipWVars,
            java.util.Set<String> glPosBaseVars,
            java.util.Set<String> glPosPreservingVars,
            java.util.Set<String> taintedAfterJitterVars
    ) {
        for (var entry : scope.shadowed.entrySet()) {
            String name = entry.getKey();
            VarStateSnapshot snap = entry.getValue();
            if (snap.scale() != null) varScales.put(name, snap.scale());
            else varScales.remove(name);
            if (snap.clipW()) clipWVars.add(name); else clipWVars.remove(name);
            if (snap.glPosBase()) glPosBaseVars.add(name); else glPosBaseVars.remove(name);
            if (snap.glPosPreserving()) glPosPreservingVars.add(name); else glPosPreservingVars.remove(name);
            if (snap.taintedAfterJitter()) taintedAfterJitterVars.add(name); else taintedAfterJitterVars.remove(name);
        }
    }

    private static void recordUninitializedLocalDecls(
            String stmt,
            JitterBlockScope scope,
            java.util.Map<String, float[]> varScales,
            java.util.Set<String> clipWVars,
            java.util.Set<String> glPosBaseVars,
            java.util.Set<String> glPosPreservingVars,
            java.util.Set<String> taintedAfterJitterVars
    ) {
        String s = stmt.trim();
        if (s.contains("(") || s.startsWith("return ")) return;
        String stripped = stripGlslDeclQualifiers(s);
        if (stripped.equals(s)) return;
        for (String declarator : stripped.split(",")) {
            String d = declarator.trim();
            int bracket = d.indexOf('[');
            if (bracket > 0) d = d.substring(0, bracket).trim();
            if (d.matches("[A-Za-z_]\\w*")) {
                shadowLocalVar(scope, d, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
                clearVarState(d, varScales, clipWVars, glPosBaseVars, glPosPreservingVars, taintedAfterJitterVars);
            }
        }
    }

    private static final String[] GLSL_DECL_QUALIFIERS_AND_TYPES = new String[] {
            "const ", "highp ", "mediump ", "lowp ", "flat ", "smooth ", "noperspective ", "invariant ", "precise ",
            "vec4 ", "vec3 ", "vec2 ", "dvec4 ", "dvec3 ", "dvec2 ",
            "ivec4 ", "ivec3 ", "ivec2 ", "uvec4 ", "uvec3 ", "uvec2 ", "bvec4 ", "bvec3 ", "bvec2 ",
            "mat4 ", "mat3 ", "mat2 ", "float ", "double ", "int ", "uint ", "bool "
    };

    private static String stripGlslDeclQualifiers(String lhs) {
        String s = lhs.trim();
        boolean changed;
        do {
            changed = false;
            for (String prefix : GLSL_DECL_QUALIFIERS_AND_TYPES) {
                if (s.startsWith(prefix)) {
                    s = s.substring(prefix.length()).trim();
                    changed = true;
                }
            }
        } while (changed);
        return s;
    }

    private static boolean evaluateNonJitterOrCheckSafe(String rhs) {
        return !rhs.isEmpty();
    }

    private static String unwrapGlslPosVec4(String rhs) {
        String clean = unwrapParens(rhs);
        if (clean.startsWith("vec4")) {
            int open = clean.indexOf('(');
            if (open > 0 && findMatchingDelimiter(clean, open, '(', ')') == clean.length() - 1) {
                List<String> args = splitTopLevelArgs(clean.substring(open + 1, clean.length() - 1));
                if (args.size() >= 2) {
                    // vec4(xy, z, w) vs vec4(x, y, z, w)
                    if (args.size() == 3) return args.get(0);
                    if (args.size() == 4) return "vec2(" + args.get(0) + ", " + args.get(1) + ")";
                    return args.get(0);
                }
            }
        }
        return rhs;
    }

    private static boolean matrixEquals(float[] a, float[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Float.compare(a[i], b[i]) != 0) return false;
        }
        return true;
    }

    private static float[] negateAffine(float[] a) {
        return new float[] { -a[0], -a[1], -a[2], -a[3], -a[4], -a[5] };
    }

    private static float @Nullable [] readAffineSwizzle(float[] a, @Nullable String swizzle) {
        if (swizzle == null || swizzle.isEmpty() || "xy".equals(swizzle) || "rg".equals(swizzle)) {
            return a.clone();
        }
        if ("x".equals(swizzle) || "r".equals(swizzle)) {
            return new float[] { a[0], a[1], a[2], a[0], a[1], a[2] };
        }
        if ("y".equals(swizzle) || "g".equals(swizzle)) {
            return new float[] { a[3], a[4], a[5], a[3], a[4], a[5] };
        }
        if (swizzle.length() == 2) {
            char c0 = swizzle.charAt(0);
            char c1 = swizzle.charAt(1);
            int r0 = (c0 == 'x' || c0 == 'r') ? 0 : (c0 == 'y' || c0 == 'g') ? 3 : -1;
            int r1 = (c1 == 'x' || c1 == 'r') ? 0 : (c1 == 'y' || c1 == 'g') ? 3 : -1;
            if (r0 < 0 || r1 < 0) return null;
            return new float[] { a[r0], a[r0 + 1], a[r0 + 2], a[r1], a[r1 + 1], a[r1 + 2] };
        }
        return null;
    }

    private static float @Nullable [] applySwizzleAccumulate(float[] cur, String swizzle, float[] delta) {
        if (swizzle.isEmpty() || "xy".equals(swizzle) || "rg".equals(swizzle)) {
            return new float[] { cur[0] + delta[0], cur[1] + delta[1], cur[2] + delta[2],
                    cur[3] + delta[3], cur[4] + delta[4], cur[5] + delta[5] };
        }
        if ("x".equals(swizzle) || "r".equals(swizzle)) {
            return new float[] { cur[0] + delta[0], cur[1] + delta[1], cur[2] + delta[2], cur[3], cur[4], cur[5] };
        }
        if ("y".equals(swizzle) || "g".equals(swizzle)) {
            return new float[] { cur[0], cur[1], cur[2], cur[3] + delta[3], cur[4] + delta[4], cur[5] + delta[5] };
        }
        if ("yx".equals(swizzle) || "gr".equals(swizzle)) {
            return new float[] { cur[0] + delta[3], cur[1] + delta[4], cur[2] + delta[5],
                    cur[3] + delta[0], cur[4] + delta[1], cur[5] + delta[2] };
        }
        return null;
    }

    private static float @Nullable [] applySwizzleAssign(float @Nullable [] cur, String swizzle, float[] val) {
        float xx = cur == null ? 0.0F : cur[0], xy = cur == null ? 0.0F : cur[1], bx = cur == null ? 0.0F : cur[2];
        float yx = cur == null ? 0.0F : cur[3], yy = cur == null ? 0.0F : cur[4], by = cur == null ? 0.0F : cur[5];
        if (swizzle.isEmpty() || "xy".equals(swizzle) || "rg".equals(swizzle)) {
            return val.clone();
        }
        if ("x".equals(swizzle) || "r".equals(swizzle)) {
            return new float[] { val[0], val[1], val[2], yx, yy, by };
        }
        if ("y".equals(swizzle) || "g".equals(swizzle)) {
            return new float[] { xx, xy, bx, val[3], val[4], val[5] };
        }
        if ("yx".equals(swizzle) || "gr".equals(swizzle)) {
            return new float[] { val[3], val[4], val[5], val[0], val[1], val[2] };
        }
        return null;
    }

    private static boolean isGlPosBaseExpr(String token, java.util.Set<String> glPosBaseVars) {
        String t = unwrapParens(token);
        if (t.equals("gl_Position") || t.startsWith("gl_Position.")) return true;
        int dot = t.indexOf('.');
        String base = dot > 0 ? t.substring(0, dot).trim() : t;
        return glPosBaseVars.contains(base);
    }

    private static boolean containsGlPosBaseTerm(String expr, java.util.Set<String> glPosBaseVars) {
        for (SignedTerm term : splitTopLevelSignedTerms(unwrapParens(expr))) {
            if (isGlPosBaseExpr(term.expr(), glPosBaseVars)
                    || Pattern.compile("\\bgl_Position\\b").matcher(term.expr()).find()) {
                return true;
            }
        }
        return false;
    }

    private static float @Nullable [] evaluateJitterSumExpr(
            String expr,
            String jitterArrayName,
            java.util.Map<String, float[]> varScales,
            java.util.Set<String> clipWVars,
            java.util.Set<String> glPosBaseVars
    ) {
        Pattern arrayRef = Pattern.compile("\\b" + Pattern.quote(jitterArrayName) + "\\s*\\[");
        List<SignedTerm> terms = splitTopLevelSignedTerms(unwrapParens(expr));
        float[] sum = new float[6];
        boolean foundJitter = false;
        for (SignedTerm term : terms) {
            String termExpr = term.expr();
            if (arrayRef.matcher(termExpr).find() || referencesAny(termExpr, varScales.keySet())) {
                float[] eval = evaluateJitterScaleExpr(termExpr, jitterArrayName, varScales, clipWVars, glPosBaseVars);
                if (eval == null) return null;
                float s = term.sign();
                for (int k = 0; k < 6; k++) sum[k] += s * eval[k];
                foundJitter = true;
            } else {
                float[] c = evalConstantVec2Factor(termExpr);
                if (c != null) {
                    float s = term.sign();
                    sum[2] += s * c[0];
                    sum[5] += s * c[1];
                } else if (!isGlPosBaseExpr(termExpr, glPosBaseVars)) {
                    // Unrecognized non-constant term affecting jitter variable or scaled jitter sum -> cannot prove constant affine transform.
                    return null;
                }
            }
        }
        return foundJitter || (sum[2] != 0.0F || sum[5] != 0.0F) ? sum : null;
    }

    private record SignedTerm(float sign, String expr) { }

    private static List<SignedTerm> splitTopLevelSignedTerms(String expr) {
        List<SignedTerm> terms = new ArrayList<>();
        int d = 0;
        int start = 0;
        float nextSign = 1.0F;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(' || c == '[') d++;
            else if (c == ')' || c == ']') { if (d > 0) d--; }
            else if (d == 0 && (c == '+' || c == '-')) {
                // Distinguish binary +/- from unary +/- after an operator or at start.
                int prevNonSpace = i - 1;
                while (prevNonSpace >= start && Character.isWhitespace(expr.charAt(prevNonSpace))) prevNonSpace--;
                boolean binary = prevNonSpace >= start && "+-*/(".indexOf(expr.charAt(prevNonSpace)) < 0;
                if (binary) {
                    terms.add(new SignedTerm(nextSign, expr.substring(start, i).trim()));
                    nextSign = (c == '-') ? -1.0F : 1.0F;
                    start = i + 1;
                }
            }
        }
        terms.add(new SignedTerm(nextSign, expr.substring(start).trim()));
        return terms;
    }

    private static List<String> splitTopLevelArgs(String argsText) {
        List<String> args = new ArrayList<>();
        int d = 0, start = 0;
        for (int i = 0; i < argsText.length(); i++) {
            char c = argsText.charAt(i);
            if (c == '(' || c == '[') d++;
            else if (c == ')' || c == ']') { if (d > 0) d--; }
            else if (c == ',' && d == 0) {
                args.add(argsText.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (!argsText.trim().isEmpty()) args.add(argsText.substring(start).trim());
        return args;
    }

    private static float @Nullable [] evaluateJitterScaleExpr(
            String expr,
            String jitterArrayName,
            java.util.Map<String, float[]> varScales,
            java.util.Set<String> clipWVars,
            java.util.Set<String> glPosBaseVars
    ) {
        List<String> factors = new ArrayList<>();
        List<Boolean> divides = new ArrayList<>();
        splitMulDivFactors(expr.trim(), false, factors, divides);
        float[] affine = new float[] { 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F };
        boolean foundSource = false;
        Pattern arrayPattern = Pattern.compile("^" + Pattern.quote(jitterArrayName) + "\\s*\\[[^\\]]+\\](?:\\.([A-Za-z]+))?$");
        for (int i = 0; i < factors.size(); i++) {
            String f = unwrapParens(factors.get(i));
            boolean div = divides.get(i);
            float factorSign = 1.0F;
            while (f.startsWith("-") || f.startsWith("+")) {
                if (f.startsWith("-")) factorSign = -factorSign;
                f = unwrapParens(f.substring(1));
            }
            if (!div && splitTopLevelSignedTerms(f).size() > 1 && referencesJitterOrVars(f, jitterArrayName, varScales)) {
                if (foundSource) return null;
                float[] sum = evaluateJitterSumExpr(f, jitterArrayName, varScales, clipWVars, glPosBaseVars);
                if (sum == null) return null;
                float sxCur = affine[0];
                float syCur = affine[4];
                affine = new float[] {
                        sxCur * factorSign * sum[0], sxCur * factorSign * sum[1], sxCur * factorSign * sum[2],
                        syCur * factorSign * sum[3], syCur * factorSign * sum[4], syCur * factorSign * sum[5]
                };
                foundSource = true;
                continue;
            }
            if (!div && f.startsWith("vec2")) {
                int open = f.indexOf('(');
                if (open > 0 && findMatchingDelimiter(f, open, '(', ')') == f.length() - 1) {
                    List<String> args = splitTopLevelArgs(f.substring(open + 1, f.length() - 1));
                    if (args.size() == 2 && (referencesJitterOrVars(args.get(0), jitterArrayName, varScales)
                            || referencesJitterOrVars(args.get(1), jitterArrayName, varScales))) {
                        if (foundSource) return null;
                        float[] ax = evaluateJitterSumExpr(args.get(0), jitterArrayName, varScales, clipWVars, glPosBaseVars);
                        float[] ay = evaluateJitterSumExpr(args.get(1), jitterArrayName, varScales, clipWVars, glPosBaseVars);
                        if (ax == null || ay == null) return null;
                        float sxCur = affine[0];
                        float syCur = affine[4];
                        affine = new float[] {
                                sxCur * factorSign * ax[0], sxCur * factorSign * ax[1], sxCur * factorSign * ax[2],
                                syCur * factorSign * ay[3], syCur * factorSign * ay[4], syCur * factorSign * ay[5]
                        };
                        foundSource = true;
                        continue;
                    }
                }
            }
            Matcher arrMatch = arrayPattern.matcher(f);
            if (arrMatch.matches()) {
                if (div || foundSource) return null;
                float[] swMat = readAffineSwizzle(new float[] { factorSign, 0.0F, 0.0F, 0.0F, factorSign, 0.0F }, arrMatch.group(1));
                if (swMat == null) return null;
                affine = new float[] {
                        affine[0] * swMat[0], affine[0] * swMat[1], affine[0] * swMat[2],
                        affine[4] * swMat[3], affine[4] * swMat[4], affine[4] * swMat[5]
                };
                foundSource = true;
                continue;
            }
            String varBase = f;
            int dot = f.indexOf('.');
            if (dot > 0) varBase = f.substring(0, dot);
            if (varScales.containsKey(varBase)) {
                if (div || foundSource) return null;
                float[] vs = readAffineSwizzle(varScales.get(varBase), dot > 0 ? f.substring(dot + 1) : "");
                if (vs == null) return null;
                affine = new float[] {
                        affine[0] * factorSign * vs[0], affine[0] * factorSign * vs[1], affine[0] * factorSign * vs[2],
                        affine[4] * factorSign * vs[3], affine[4] * factorSign * vs[4], affine[4] * factorSign * vs[5]
                };
                foundSource = true;
                continue;
            }
            if (isClipWNormToken(f) || clipWVars.contains(f) || isResolutionVectorToken(f) || isPixelSizeToken(f)) {
                for (int k = 0; k < 6; k++) affine[k] *= factorSign;
                continue;
            }
            float[] c = evalConstantVec2Factor(f);
            if (c == null) return null;
            c = new float[] { factorSign * c[0], factorSign * c[1] };
            if (div) {
                if (c[0] == 0.0F || c[1] == 0.0F) return null;
                affine[0] /= c[0]; affine[1] /= c[0]; affine[2] /= c[0];
                affine[3] /= c[1]; affine[4] /= c[1]; affine[5] /= c[1];
            } else {
                affine[0] *= c[0]; affine[1] *= c[0]; affine[2] *= c[0];
                affine[3] *= c[1]; affine[4] *= c[1]; affine[5] *= c[1];
            }
        }
        return foundSource ? affine : null;
    }

    private static boolean referencesJitterOrVars(String expr, String jitterArrayName, java.util.Map<String, float[]> varScales) {
        return Pattern.compile("\\b" + Pattern.quote(jitterArrayName) + "\\s*\\[").matcher(expr).find()
                || referencesAny(expr, varScales.keySet());
    }

    private static int findTopLevelEquals(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') d++;
            else if (c == ')' || c == ']') { if (d > 0) d--; }
            else if (c == '=' && d == 0) {
                char prev = i > 0 ? s.charAt(i - 1) : '\0';
                char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
                if (prev != '=' && prev != '!' && prev != '<' && prev != '>' && prev != '+' && prev != '-' && prev != '*' && prev != '/' && next != '=') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean referencesAny(String expr, java.util.Set<String> vars) {
        for (String v : vars) {
            if (Pattern.compile("\\b" + Pattern.quote(v) + "\\b").matcher(expr).find()) return true;
        }
        return false;
    }

    private static void splitMulDivFactors(String expr, boolean invert, List<String> out, List<Boolean> divides) {
        String inner = unwrapParens(expr);
        List<Integer> ops = new ArrayList<>();
        List<Boolean> opIsDiv = new ArrayList<>();
        int d = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(' || c == '[') d++;
            else if (c == ')' || c == ']') d--;
            else if (d == 0 && (c == '*' || c == '/')) {
                ops.add(i);
                opIsDiv.add(c == '/');
            }
        }
        if (ops.isEmpty()) {
            out.add(inner);
            divides.add(invert);
            return;
        }
        int start = 0;
        boolean curDiv = invert;
        for (int i = 0; i < ops.size(); i++) {
            String part = inner.substring(start, ops.get(i)).trim();
            splitMulDivFactors(part, curDiv, out, divides);
            curDiv = invert ^ opIsDiv.get(i);
            start = ops.get(i) + 1;
        }
        splitMulDivFactors(inner.substring(start).trim(), curDiv, out, divides);
    }

    private static String unwrapParens(String s) {
        String cur = s.trim();
        while (cur.startsWith("(") && cur.endsWith(")")) {
            if (findMatchingDelimiter(cur, 0, '(', ')') == cur.length() - 1) {
                cur = cur.substring(1, cur.length() - 1).trim();
            } else {
                break;
            }
        }
        return cur;
    }

    private static boolean isClipWNormToken(String token) {
        return "w".equals(token) || "gl_Position.w".equals(token) || token.endsWith(".w");
    }

    private static boolean isResolutionVectorToken(String token) {
        return token.equals("vec2(viewWidth,viewHeight)")
                || token.replaceAll("\\s+", "").equals("vec2(viewWidth,viewHeight)")
                || token.equals("viewSize") || token.equals("resolution");
    }

    private static boolean isPixelSizeToken(String token) {
        return token.equals("viewPixelSize") || token.equals("texelSize") || token.equals("pixelSize");
    }

    private static float @Nullable [] evalConstantVec2Factor(String token) {
        String clean = unwrapParens(token).replaceAll("\\s+", "");
        if (clean.startsWith("vec2(") && clean.endsWith(")")) {
            String argsStr = clean.substring(5, clean.length() - 1);
            int comma = argsStr.indexOf(',');
            if (comma < 0) {
                Float s = parseFloatScalar(argsStr);
                return s == null ? null : new float[] { s, s };
            }
            Float x = parseFloatScalar(argsStr.substring(0, comma));
            Float y = parseFloatScalar(argsStr.substring(comma + 1));
            return (x != null && y != null) ? new float[] { x, y } : null;
        }
        if (clean.startsWith("float(") && clean.endsWith(")")) {
            Float s = parseFloatScalar(clean.substring(6, clean.length() - 1));
            return s == null ? null : new float[] { s, s };
        }
        Float s = parseFloatScalar(clean);
        return s == null ? null : new float[] { s, s };
    }

    private static @Nullable Float parseFloatScalar(String text) {
        String s = text.trim();
        if (s.endsWith("f") || s.endsWith("F")) s = s.substring(0, s.length() - 1);
        try {
            float v = Float.parseFloat(s);
            return Float.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean writesColortex0(final ProgramSource pass) {
        var directives = pass.getDirectives();
        if (directives.hasUnknownDrawBuffers()) {
            return true;
        }
        int[] drawBuffers = directives.getDrawBuffers();
        if (drawBuffers == null || drawBuffers.length == 0) {
            return true;
        }
        for (int buffer : drawBuffers) {
            if (buffer == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFloatingPointFormat(final @Nullable InternalTextureFormat format) {
        if (format == null) {
            return false;
        }
        return switch (format) {
            case R16F, RG16F, RGB16F, RGBA16F, R32F, RG32F, RGB32F, RGBA32F, R11F_G11F_B10F, RGB9_E5 -> true;
            default -> false;
        };
    }

    private static IrisShaderProfile jitterProfile(final ShaderPack pack, final ProgramSet programs) {
        if (pack == null) {
            return UNKNOWN;
        }
        final IncludeGraph includes = pack.getShaderPackOptions().getIncludes();
        final String jitter = source(includes, JITTER_PATH);
        final var composite = programs.getComposite(ProgramArrayId.Composite);
        final var composite1 = composite.length > 1 ? composite[1] : null;
        final String fragment = composite1 != null && composite1.isValid()
                ? composite1.getFragmentSource().orElse(null) : null;
        final boolean sourceProof = hasJitterContract(jitter)
                && fragment != null
                && COMPOSITE_DECODE.matcher(GLSL_COMMENTS.matcher(fragment).replaceAll(" ")).find();

        final ShaderpackKind kind = ShaderpackClassifier.classify(sourceProof, true);
        final ColourEncoding encoding = ShaderpackClassifier.colourEncoding(kind);
        if (!sourceProof) {
            return new IrisShaderProfile(kind, encoding, false, Float.NaN);
        }

        final Integer taaDefine = integerOption(pack, "TAA_DEFINE");
        final Integer detailQuality = integerOption(pack, "DETAIL_QUALITY");
        if (taaDefine == null || detailQuality == null) {
            return new IrisShaderProfile(kind, encoding, false, Float.NaN);
        }
        final boolean taaEnabled = taaDefine == 1 || (taaDefine == -1 && detailQuality >= 2);
        if (!taaEnabled) {
            return new IrisShaderProfile(kind, encoding, true, 0.0F);
        }

        final Integer jitterMode = integerOption(pack, "TAA_JITTER");
        if (jitterMode == null) {
            return new IrisShaderProfile(kind, encoding, false, Float.NaN);
        }
        final float scale;
        if (jitterMode <= 0) {
            scale = 0.0F;
        } else if (jitterMode == 1) {
            scale = 0.125F;
        } else if (jitterMode == 2) {
            scale = 0.33F;
        } else {
            scale = 1.0F;
        }
        return new IrisShaderProfile(kind, encoding, true, scale, RawSpecularEncoding.UNKNOWN, Float.NaN, 8);
    }

    private static boolean hasJitterContract(final String source) {
        if (source == null
                || !source.contains("int(framemod8)")
                || !source.contains("offset *= 0.125;")
                || !source.contains("offset *= 0.33;")) {
            return false;
        }
        String cleaned = GLSL_COMMENTS.matcher(source).replaceAll(" ");
        ParsedJitterTable parsed = extractOrderedJitterTable(cleaned, "framemod8", 8);
        return parsed != null && tablesEqual(parsed.offsets(), STANDARD_JITTER_OFFSETS);
    }

    private static Integer integerOption(final ShaderPack pack, final String name) {
        String value = stringOption(pack, name);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String stringOption(final ShaderPack pack, final String name) {
        final OptionValues values = pack.getShaderPackOptions().getOptionValues();
        final Optional<String> changed = values.getStringValue(name);
        final String value;
        if (changed.isPresent()) {
            value = changed.get();
        } else {
            final MergedStringOption option = values.getOptionSet().getStringOptions().get(name);
            if (option == null) {
                return null;
            }
            value = option.getOption().getDefaultValue();
        }
        return value;
    }

    private static String source(final IncludeGraph graph, final String pathSuffix) {
        for (FileNode node : graph.getNodes().values()) {
            if (node.getPath().getPathString().endsWith(pathSuffix)) {
                return String.join("\n", node.getLines());
            }
        }
        return null;
    }
}
