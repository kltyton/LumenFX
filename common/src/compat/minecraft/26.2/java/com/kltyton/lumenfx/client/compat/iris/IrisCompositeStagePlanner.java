package com.kltyton.lumenfx.client.compat.iris;

import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Computes Iris composite pass cutpoints and per-stage color buffer state from preprocessed sources. */
public final class IrisCompositeStagePlanner {
    private static final Pattern GLSL_COMMENTS = Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*");
    private static final Pattern COLORIMG_WRITE = Pattern.compile("\\bimageStore\\s*\\(\\s*colorimg(1[0-5]|[0-9])\\b");
    private static final Pattern COLOR_BUFFER_REF = Pattern.compile("\\b(?:colortex|colorimg)(1[0-5]|[0-9])(?:_alt)?\\b");
    private static final Pattern IDENTIFIER_CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern COLOR_ALIAS_REF = Pattern.compile("\\b(?:gcolor|gaux([1-4]))\\b");
    private static final Pattern DOF_CALL = Pattern.compile(
            "\\b(?:DoWorldBlur|DoDepthOfField|DepthOfField|DoDOF|depthOfField|dofBlur|calculateDOF|calcDoF|GetDoF|SampleDoF|sampleDoF|bokehBlur|hexagonalBokeh|circleOfConfusion|calcCoC|getCoC|DoF)\\s*\\(");
    private static final Pattern DOF_IDENTIFIER = Pattern.compile(
            "\\b(?:DOF_STRENGTH|DOF_RADIUS|DOF_SAMPLES|DOF_APERTURE|DOF_QUALITY|DOF_BLUR|DoF_Strength|focalDepth|cocRadius|circleOfConfusion|dofOffsets|getPolygonOffset)\\b");
    private static final Pattern CENTER_DEPTH_SMOOTH = Pattern.compile("\\bcenterDepthSmooth\\b");
    private static final Pattern DOF_CONTEXT_HINT = Pattern.compile(
            "\\b(?:bokeh|coc|cocView|CoC|circleOfConfusion|blur|dof|DoF|dofScale|aperture|focal)\\b");
    private static final Pattern MOTION_BLUR_CALL = Pattern.compile(
            "\\b(?:MotionBlur|DoMotionBlur|doMotionBlur|calcMotionBlur|applyMotionBlur|ComputeMotionBlur|sampleMotionBlur)\\s*\\(");
    private static final Pattern MOTION_BLUR_IDENTIFIER = Pattern.compile(
            "\\b(?:MOTION_BLUR|MOTION_BLUR_STRENGTH|MOTION_BLUR_SAMPLES|motionBlurStrength|motionBlur)\\b");
    private static final Pattern MOTION_BLUR_SAMPLE_HINT = Pattern.compile(
            "\\b(?:blur|sample|vel|velocity|prevScreenPos|previousProjection|gbufferPreviousProjection)\\b");
    private static final Pattern MOTION_PREVIOUS_POSITION = Pattern.compile(
            "\\b(?:previousPosition|prevScreenPos|previousClipPos|gbufferPreviousProjection|previousProjection)\\b");
    private static final Pattern MOTION_REPROJECTION_VELOCITY = Pattern.compile("\\bvelocity\\b");
    private static final Pattern MOTION_VELOCITY_STEP = Pattern.compile(
            "(?:[+-]=\\s*velocity\\b|\\bfor\\s*\\([^)]*velocity\\b)");
    private static final Pattern MOTION_COLORTEX0_SAMPLE = Pattern.compile(
            "\\btexture(?:2DLod|2D|Lod)?\\s*\\(\\s*colortex0\\b");
    private static final Pattern GAMMA_TO_LINEAR_FN = Pattern.compile(
            "\\b(?:srgbToLinear|SRGBToLinear|SrgbToLinear|gammaToLinear|GammaToLinear)\\s*\\(");
    private static final Pattern LINEAR_TO_SRGB_FN = Pattern.compile(
            "\\b(?:linearToSrgb|LinearToSRGB|LinearToSrgb)\\s*\\(");
    private static final Pattern LINEAR_TO_GAMMA_FN = Pattern.compile(
            "\\b(?:linearToGamma|LinearToGamma)\\s*\\(");
    private static final Pattern POW_CALL = Pattern.compile("\\bpow\\s*\\(");
    private static final Pattern EXPONENT_2_2 = Pattern.compile("(?<![./\\d])2\\.2(?:0*)?f?\\b");
    private static final Pattern EXPONENT_INV_2_2 = Pattern.compile(
            "(?:1(?:\\.0*)?f?\\s*/\\s*2\\.2(?:0*)?f?|0\\.4545[0-9]*f?)\\b");
    private static final Pattern SRGB_TO_LINEAR_FN = Pattern.compile(
            "\\b(?:srgbToLinear|SRGBToLinear|SrgbToLinear)\\s*\\(");
    private static final Pattern GAMMA_ONLY_TO_LINEAR_FN = Pattern.compile(
            "\\b(?:gammaToLinear|GammaToLinear)\\s*\\(");
    private static final Pattern TONEMAP_CALL = Pattern.compile(
            "\\b(?:DoCompTonemap|tonemap[A-Za-z0-9_]*|Tonemap[A-Za-z0-9_]*|toneMap[A-Za-z0-9_]*|ToneMap[A-Za-z0-9_]*|ACES[A-Za-z0-9_]*|Uncharted2[A-Za-z0-9_]*|uncharted2[A-Za-z0-9_]*|Reinhard[A-Za-z0-9_]*|reinhard[A-Za-z0-9_]*|AgX[A-Za-z0-9_]*|agxTonemap[A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern NON_SCENE_BUFFER_HINT = Pattern.compile(
            "\\b(?:colortex(?:[1-9]|1[0-5])|albedo|gcolor|normal|specular)\\b");

    private static final Pattern DRAWBUFFERS_DIRECTIVE = Pattern.compile("/\\*\\s*(?:DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9,\\s]+)\\*/");
    private static final Pattern OUT_VAR_DECL = Pattern.compile(
            "(?:layout\\s*\\(([^)]*)\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample|highp|mediump|lowp)\\s+)*out\\s+(?:highp|mediump|lowp\\s+)?vec[234]\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
    private static final Pattern LAYOUT_LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)\\b");
    private static final Pattern RETURN_EXPR_STMT = Pattern.compile("\\breturn(?=\\s+[^;])");

    private IrisCompositeStagePlanner() {
    }

    public record PassInput(
            String name,
            @Nullable String fragmentSource,
            List<String> computeSources,
            boolean writesColortex0,
            boolean colortex0AltBefore,
            int writtenBufferMask,
            int colortex0Slot
    ) {
        public PassInput {
            Objects.requireNonNull(name, "name");
            computeSources = computeSources == null ? List.of() : List.copyOf(computeSources);
            if (writesColortex0) {
                writtenBufferMask |= 1;
            }
        }

        public PassInput(
                String name,
                @Nullable String fragmentSource,
                List<String> computeSources,
                boolean writesColortex0,
                boolean colortex0AltBefore,
                int writtenBufferMask
        ) {
            this(name, fragmentSource, computeSources, writesColortex0, colortex0AltBefore, writtenBufferMask,
                    inferColortex0Slot(fragmentSource, writesColortex0));
        }

        public PassInput(
                String name,
                @Nullable String fragmentSource,
                List<String> computeSources,
                boolean writesColortex0,
                boolean colortex0AltBefore
        ) {
            this(name, fragmentSource, computeSources, writesColortex0, colortex0AltBefore, writesColortex0 ? 1 : 0,
                    inferColortex0Slot(fragmentSource, writesColortex0));
        }
    }

    private static int inferColortex0Slot(@Nullable String rawFragment, boolean writesColortex0) {
        if (rawFragment != null && !rawFragment.isEmpty()) {
            Matcher db = DRAWBUFFERS_DIRECTIVE.matcher(rawFragment);
            int lastSlot = Integer.MIN_VALUE;
            while (db.find()) {
                String spec = db.group(1).trim();
                if (spec.contains(",")) {
                    String[] parts = spec.split(",");
                    int found = -1;
                    for (int i = 0; i < parts.length; i++) {
                        if ("0".equals(parts[i].trim())) {
                            found = i;
                            break;
                        }
                    }
                    lastSlot = found;
                } else {
                    String compact = spec.replaceAll("\\s+", "");
                    lastSlot = compact.indexOf('0');
                }
            }
            if (lastSlot != Integer.MIN_VALUE) {
                return lastSlot;
            }
        }
        return writesColortex0 ? 0 : -1;
    }

    public record StagePlan(
            int passCount,
            int afterDofPassIndex,
            boolean afterDofAltSide,
            ColourEncoding afterDofEncoding,
            int afterMotionBlurPassIndex,
            boolean afterMotionBlurAltSide,
            ColourEncoding afterMotionBlurEncoding,
            boolean hasDofPass,
            boolean hasMotionBlurPass
    ) {
        public static StagePlan fallbackImmediate(int passCount, ColourEncoding encoding) {
            return new StagePlan(passCount, -1, false, encoding, -1, false, encoding, false, false);
        }

        public StagePlan {
            Objects.requireNonNull(afterDofEncoding, "afterDofEncoding");
            Objects.requireNonNull(afterMotionBlurEncoding, "afterMotionBlurEncoding");
        }

        public boolean hasDeferredAfterDof() {
            return afterDofPassIndex > 0;
        }

        public boolean hasDeferredAfterMotionBlur() {
            return afterMotionBlurPassIndex > 0;
        }

        public boolean hasSeparateAfterMotionBlur() {
            return hasDeferredAfterMotionBlur() && afterMotionBlurPassIndex > afterDofPassIndex;
        }

        public boolean hasAnyDeferredStage() {
            return hasDeferredAfterDof() || hasDeferredAfterMotionBlur();
        }

        public EnumSet<TranslucencyPass> immediateStages() {
            EnumSet<TranslucencyPass> stages = EnumSet.of(TranslucencyPass.BEFORE_DOF);
            if (!hasDeferredAfterDof()) {
                stages.add(TranslucencyPass.AFTER_DOF);
                if (!hasDeferredAfterMotionBlur()) {
                    stages.add(TranslucencyPass.AFTER_MOTION_BLUR);
                }
            }
            return stages;
        }

        public EnumSet<TranslucencyPass> afterDofStages() {
            if (!hasDeferredAfterDof()) {
                return EnumSet.noneOf(TranslucencyPass.class);
            }
            if (hasSeparateAfterMotionBlur()) {
                return EnumSet.of(TranslucencyPass.AFTER_DOF);
            }
            return EnumSet.of(TranslucencyPass.AFTER_DOF, TranslucencyPass.AFTER_MOTION_BLUR);
        }

        public EnumSet<TranslucencyPass> afterMotionBlurStages() {
            if (!hasDeferredAfterMotionBlur()) {
                return EnumSet.noneOf(TranslucencyPass.class);
            }
            if (!hasDeferredAfterDof() || hasSeparateAfterMotionBlur()) {
                return EnumSet.of(TranslucencyPass.AFTER_MOTION_BLUR);
            }
            return EnumSet.noneOf(TranslucencyPass.class);
        }
    }

    public static StagePlan plan(
            List<PassInput> passes,
            boolean colortex0AltAtEnd,
            ColourEncoding initialEncoding
    ) {
        Objects.requireNonNull(passes, "passes");
        Objects.requireNonNull(initialEncoding, "initialEncoding");
        if (passes.isEmpty()) {
            return StagePlan.fallbackImmediate(0, initialEncoding);
        }

        int passCount = passes.size();
        ColourEncoding[] encodingBefore = new ColourEncoding[passCount + 1];
        ColourEncoding currentEncoding = initialEncoding;
        int intermediateDofMask = 0;
        int intermediateMotionMask = 0;
        int lastDofIndex = -1;
        int lastMotionBlurIndex = -1;

        for (int i = 0; i < passCount; i++) {
            PassInput pass = passes.get(i);
            encodingBefore[i] = currentEncoding;
            String cleanedFragment = stripComments(pass.fragmentSource());
            List<String> cleanedComputes = stripAllComments(pass.computeSources());
            ReachableProgram fragProgram = extractReachableMainResult(cleanedFragment);
            String executableFragment = fragProgram.source();
            List<ReachableProgram> computePrograms = new ArrayList<>(cleanedComputes.size());
            List<String> executableComputes = new ArrayList<>(cleanedComputes.size());
            int computeWriteMask = 0;
            boolean computeWritesScene = false;
            boolean computeDof = false;
            boolean computeMotion = false;
            for (String cleanedCompute : cleanedComputes) {
                ReachableProgram computeProg = extractReachableMainResult(cleanedCompute);
                String executableCompute = computeProg.source();
                computePrograms.add(computeProg);
                executableComputes.add(executableCompute);
                int writeMaskForCompute = colorImgWriteMask(executableCompute);
                computeWriteMask |= writeMaskForCompute;
                if ((writeMaskForCompute & 1) != 0) {
                    computeWritesScene = true;
                }
                if (hasDofEvidence(executableCompute)) {
                    computeDof = true;
                }
                if (hasMotionBlurEvidence(executableCompute)) {
                    computeMotion = true;
                }
            }

            int writeMask = pass.writtenBufferMask() | computeWriteMask;
            boolean writesScene = pass.writesColortex0() || (writeMask & 1) != 0;
            int readMask = readBufferMask(executableFragment, executableComputes);
            boolean hasDof = hasDofEvidence(executableFragment) || computeDof || ((readMask & intermediateDofMask & ~1) != 0);
            boolean hasMotion = hasMotionBlurEvidence(executableFragment) || computeMotion || ((readMask & intermediateMotionMask & ~1) != 0);
            intermediateDofMask = updateTaintedBuffers(intermediateDofMask, writeMask & ~1, hasDof);
            intermediateMotionMask = updateTaintedBuffers(intermediateMotionMask, writeMask & ~1, hasMotion);

            if (writesScene) {
                if (hasDof) {
                    lastDofIndex = i;
                }
                if (hasMotion) {
                    lastMotionBlurIndex = i;
                }
            }
            if (computeWritesScene) {
                for (int ci = 0; ci < computePrograms.size(); ci++) {
                    String executableCompute = executableComputes.get(ci);
                    if ((colorImgWriteMask(executableCompute) & 1) != 0) {
                        currentEncoding = advanceComputeEncoding(currentEncoding, computePrograms.get(ci));
                    }
                }
            }
            if (pass.writesColortex0() || (pass.writtenBufferMask() & 1) != 0) {
                currentEncoding = advanceFragmentEncoding(currentEncoding, cleanedFragment, fragProgram, pass.colortex0Slot());
            }
        }
        encodingBefore[passCount] = currentEncoding;

        int afterDofPassIndex = lastDofIndex >= 0 ? lastDofIndex + 1 : -1;
        int afterMotionBlurPassIndex = lastMotionBlurIndex >= 0 ? lastMotionBlurIndex + 1 : -1;
        if (afterMotionBlurPassIndex > 0 && afterDofPassIndex > 0 && afterMotionBlurPassIndex < afterDofPassIndex) {
            // Preserve strict stage ordering (AFTER_MOTION_BLUR cannot precede AFTER_DOF).
            afterMotionBlurPassIndex = afterDofPassIndex;
        }

        boolean afterDofAltSide = afterDofPassIndex > 0
                ? resolveAltBefore(passes, colortex0AltAtEnd, afterDofPassIndex)
                : false;
        ColourEncoding afterDofEncoding = afterDofPassIndex > 0
                ? encodingBefore[afterDofPassIndex]
                : initialEncoding;

        boolean afterMotionBlurAltSide = afterMotionBlurPassIndex > 0
                ? resolveAltBefore(passes, colortex0AltAtEnd, afterMotionBlurPassIndex)
                : false;
        ColourEncoding afterMotionBlurEncoding = afterMotionBlurPassIndex > 0
                ? encodingBefore[afterMotionBlurPassIndex]
                : initialEncoding;

        return new StagePlan(
                passCount,
                afterDofPassIndex,
                afterDofAltSide,
                afterDofEncoding,
                afterMotionBlurPassIndex,
                afterMotionBlurAltSide,
                afterMotionBlurEncoding,
                lastDofIndex >= 0,
                lastMotionBlurIndex >= 0
        );
    }

    static String stripComments(@Nullable String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        return GLSL_COMMENTS.matcher(source).replaceAll(" ");
    }

    private static List<String> stripAllComments(List<String> sources) {
        if (sources.isEmpty()) {
            return List.of();
        }
        return sources.stream().map(IrisCompositeStagePlanner::stripComments).filter(s -> !s.isEmpty()).toList();
    }

    private static int colorImgWriteMask(String executableCompute) {
        if (executableCompute.isEmpty()) {
            return 0;
        }
        int mask = 0;
        Matcher matcher = COLORIMG_WRITE.matcher(executableCompute);
        while (matcher.find()) {
            int idx = Integer.parseInt(matcher.group(1));
            if (idx >= 0 && idx < 16) {
                mask |= (1 << idx);
            }
        }
        return mask;
    }

    private static int readBufferMask(String executableFragment, List<String> executableComputes) {
        int mask = scanColorBufferRefs(executableFragment);
        for (String executableCompute : executableComputes) {
            if (!executableCompute.isEmpty()) {
                String withoutStores = COLORIMG_WRITE.matcher(executableCompute).replaceAll(" ");
                mask |= scanColorBufferRefs(withoutStores);
            }
        }
        return mask;
    }

    private static int scanColorBufferRefs(String source) {
        if (source.isEmpty()) {
            return 0;
        }
        int mask = 0;
        Matcher matcher = COLOR_BUFFER_REF.matcher(source);
        while (matcher.find()) {
            int idx = Integer.parseInt(matcher.group(1));
            if (idx >= 0 && idx < 16) {
                mask |= (1 << idx);
            }
        }
        Matcher alias = COLOR_ALIAS_REF.matcher(source);
        while (alias.find()) {
            String aux = alias.group(1);
            int idx = aux == null ? 0 : 3 + Integer.parseInt(aux);
            mask |= (1 << idx);
        }
        return mask;
    }

    private static int updateTaintedBuffers(int taintedMask, int writtenMask, boolean stageTainted) {
        if (writtenMask == 0) {
            return taintedMask;
        }
        return stageTainted ? (taintedMask | writtenMask) : (taintedMask & ~writtenMask);
    }

    private record FunctionParam(String name, String type, boolean copyOut) { }
    private record FunctionDef(String returnType, List<FunctionParam> params, String body) { }
    private record ReachableProgram(String source, Map<String, String> varTypes,
                                    Map<String, List<FunctionDef>> functionDefs) { }
    private static final String AMBIGUOUS_ENCODING_MARKER = "__lfx_ambiguous_encoding__";

    private static final Pattern VAR_DECL_LINE = Pattern.compile(
            "(?:^|[;{}])\\s*(?:(?:const|highp|mediump|lowp|flat|smooth|noperspective|centroid|sample|uniform|in|out|inout)\\s+)*"
                    + "(float|int|uint|bool|vec[234]|ivec[234]|uvec[234]|bvec[234]|mat[234](?:x[234])?|sampler[A-Za-z0-9_]+|u?image[A-Za-z0-9_]+)"
                    + "\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\s*\\[[^\\]]*\\])?(?:\\s*=[^;]*|\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*(?:\\s*=[^;]*)*)*)(?=;)");

    static String extractReachableMainSource(String cleaned) {
        return extractReachableMainResult(cleaned).source();
    }

    private static ReachableProgram extractReachableMainResult(String cleaned) {
        if (cleaned == null || cleaned.isEmpty()) {
            return new ReachableProgram("", Map.of(), Map.of());
        }
        Map<String, List<FunctionDef>> functionDefs = new HashMap<>();
        int len = cleaned.length();
        int braceDepth = 0;
        int stmtStart = 0;
        int i = 0;
        while (i < len) {
            char c = cleaned.charAt(i);
            if (braceDepth == 0 && c == '#') {
                int eol = cleaned.indexOf('\n', i);
                i = eol < 0 ? len : eol + 1;
                stmtStart = i;
                continue;
            }
            if (c == '{') {
                if (braceDepth == 0) {
                    int close = findMatchingBrace(cleaned, i);
                    if (close > i) {
                        List<FunctionParam> params = new ArrayList<>();
                        String[] retTypeOut = new String[1];
                        String fnName = parseFunctionSignature(cleaned, stmtStart, i, params, retTypeOut);
                        if (fnName != null) {
                            functionDefs.computeIfAbsent(fnName, k -> new ArrayList<>())
                                    .add(new FunctionDef(retTypeOut[0], List.copyOf(params), cleaned.substring(i + 1, close)));
                        }
                        i = close + 1;
                        stmtStart = i;
                        continue;
                    }
                }
                braceDepth++;
            } else if (c == '}') {
                if (braceDepth > 0) {
                    braceDepth--;
                }
                if (braceDepth == 0) {
                    stmtStart = i + 1;
                }
            } else if (c == ';' && braceDepth == 0) {
                stmtStart = i + 1;
            }
            i++;
        }

        Map<String, String> allVarTypes = new HashMap<>();
        collectVarDeclTypes(cleaned, allVarTypes);
        List<FunctionDef> mainDefs = functionDefs.get("main");
        if (mainDefs == null || mainDefs.isEmpty()) {
            return new ReachableProgram(cleaned, allVarTypes, functionDefs);
        }
        StringBuilder reachable = new StringBuilder();
        Set<String> active = new HashSet<>();
        active.add("main");
        int[] callSeq = {0};
        for (FunctionDef mainDef : mainDefs) {
            Map<String, String> mainTypes = new HashMap<>(allVarTypes);
            collectVarDeclTypes(mainDef.body(), mainTypes);
            allVarTypes.putAll(mainTypes);
            appendExpandedExecutionOrder(mainDef.body(), functionDefs, mainTypes, allVarTypes, active, 0, null, callSeq, reachable);
        }
        return new ReachableProgram(reachable.toString(), allVarTypes, functionDefs);
    }

    private static final int MAX_CALL_EXPANSION_DEPTH = 8;

    private static void appendExpandedExecutionOrder(
            String body,
            Map<String, List<FunctionDef>> functionDefs,
            Map<String, String> varTypes,
            Map<String, String> allVarTypes,
            Set<String> active,
            int depth,
            @Nullable String returnTarget,
            int[] callSeq,
            StringBuilder out
    ) {
        if (body.isEmpty()) {
            return;
        }
        int len = body.length();
        int segStart = 0;
        for (int i = 0; i < len; i++) {
            char c = body.charAt(i);
            if (c == ';' || c == '{' || c == '}') {
                String segment = body.substring(segStart, i + 1);
                expandSegmentCalls(segment, functionDefs, varTypes, allVarTypes, active, depth, returnTarget, callSeq, out);
                segStart = i + 1;
            }
        }
        if (segStart < len) {
            expandSegmentCalls(body.substring(segStart), functionDefs, varTypes, allVarTypes, active, depth, returnTarget, callSeq, out);
        }
    }

    private static void expandSegmentCalls(
            String segment,
            Map<String, List<FunctionDef>> functionDefs,
            Map<String, String> varTypes,
            Map<String, String> allVarTypes,
            Set<String> active,
            int depth,
            @Nullable String returnTarget,
            int[] callSeq,
            StringBuilder out
    ) {
        String prepared = segment;
        if (returnTarget != null && prepared.contains("return")) {
            prepared = RETURN_EXPR_STMT.matcher(prepared).replaceFirst(Matcher.quoteReplacement(returnTarget + " ="));
        }
        if (prepared.isBlank() || depth >= MAX_CALL_EXPANSION_DEPTH) {
            out.append(prepared);
            if (!prepared.endsWith("\n")) {
                out.append('\n');
            }
            return;
        }
        StringBuilder rewritten = new StringBuilder();
        expandExpressionCalls(prepared, functionDefs, varTypes, allVarTypes, active, depth, callSeq, out, rewritten);
        String resultStmt = rewritten.toString();
        collectVarDeclTypes(resultStmt, varTypes);
        allVarTypes.putAll(varTypes);
        out.append(resultStmt);
        if (resultStmt.isEmpty() || resultStmt.charAt(resultStmt.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static void expandExpressionCalls(
            String text,
            Map<String, List<FunctionDef>> functionDefs,
            Map<String, String> varTypes,
            Map<String, String> allVarTypes,
            Set<String> active,
            int depth,
            int[] callSeq,
            StringBuilder preOut,
            StringBuilder rewrittenOut
    ) {
        int cursor = 0;
        Matcher call = IDENTIFIER_CALL.matcher(text);
        while (call.find(cursor)) {
            String name = call.group(1);
            int openParen = call.end() - 1;
            if (isOpaqueEncodingCall(name) || !functionDefs.containsKey(name) || active.contains(name)) {
                // Step inside opaque/unknown calls so nested user helper calls in arguments still expand in order.
                rewrittenOut.append(text, cursor, openParen + 1);
                cursor = openParen + 1;
                continue;
            }
            int closeParen = findClosingParen(text, openParen);
            if (closeParen < 0) {
                break;
            }
            rewrittenOut.append(text, cursor, call.start());
            int callId = callSeq[0]++;
            String callPrefix = "lfx_c" + callId + "_";
            String callVar = "lfx_call_" + callId;
            List<FunctionDef> defs = functionDefs.get(name);
            List<String> rawArgs = splitTopLevelArgs(text.substring(openParen + 1, closeParen));
            List<String> rewrittenArgs = new ArrayList<>(rawArgs.size());
            List<@Nullable String> argTypes = new ArrayList<>(rawArgs.size());
            for (String rawArg : rawArgs) {
                if (rawArg.isBlank()) {
                    rewrittenArgs.add("");
                    argTypes.add(null);
                    continue;
                }
                StringBuilder argRewritten = new StringBuilder();
                expandExpressionCalls(rawArg, functionDefs, varTypes, allVarTypes, active, depth + 1, callSeq, preOut, argRewritten);
                String rewrittenArg = argRewritten.toString().trim();
                rewrittenArgs.add(rewrittenArg);
                argTypes.add(inferGlslExprType(rewrittenArg, varTypes, functionDefs));
            }
            List<FunctionDef> matchedDefs = selectMatchingOverloads(defs, argTypes);
            if (matchedDefs.size() > 1) {
                // Never expand multiple ambiguous overloads sequentially; mark unknown if any candidate encodes/decodes color.
                if (anyOverloadTouchesEncoding(matchedDefs)) {
                    preOut.append(callVar).append(" = ").append(AMBIGUOUS_ENCODING_MARKER).append(";\n");
                    rewrittenOut.append(callVar);
                } else {
                    rewrittenOut.append(callVar);
                }
                cursor = closeParen + 1;
                continue;
            }
            FunctionDef primary = matchedDefs.size() == 1 ? matchedDefs.get(0) : null;
            if (primary != null && !"void".equals(primary.returnType()) && !primary.returnType().isEmpty()) {
                varTypes.put(callVar, primary.returnType());
                allVarTypes.put(callVar, primary.returnType());
            }
            if (primary != null && depth + 1 < MAX_CALL_EXPANSION_DEPTH && active.add(name)) {
                List<FunctionParam> params = primary.params();
                Map<String, String> renameMap = new HashMap<>();
                Map<String, String> calleeTypes = new HashMap<>(varTypes);
                for (int k = 0; k < params.size(); k++) {
                    String scopedName = callPrefix + params.get(k).name();
                    renameMap.put(params.get(k).name(), scopedName);
                    if (!params.get(k).type().isEmpty()) {
                        calleeTypes.put(scopedName, params.get(k).type());
                        allVarTypes.put(scopedName, params.get(k).type());
                    }
                    if (k < rewrittenArgs.size() && !rewrittenArgs.get(k).isEmpty()) {
                        preOut.append(scopedName).append(" = ").append(rewrittenArgs.get(k)).append(";\n");
                    }
                }
                Map<String, String> localTypes = new HashMap<>();
                collectVarDeclTypes(primary.body(), localTypes);
                for (Map.Entry<String, String> e : localTypes.entrySet()) {
                    String scoped = callPrefix + e.getKey();
                    renameMap.putIfAbsent(e.getKey(), scoped);
                    calleeTypes.put(scoped, e.getValue());
                    allVarTypes.put(scoped, e.getValue());
                }
                String scopedBody = renameIdentifiers(primary.body(), renameMap);
                appendExpandedExecutionOrder(scopedBody, functionDefs, calleeTypes, allVarTypes, active, depth + 1, callVar, callSeq, preOut);
                for (int k = 0; k < params.size() && k < rewrittenArgs.size(); k++) {
                    if (params.get(k).copyOut()) {
                        String lvalue = rewrittenArgs.get(k);
                        if (isAssignableLvalue(lvalue)) {
                            preOut.append(lvalue).append(" = ").append(callPrefix).append(params.get(k).name()).append(";\n");
                        }
                    }
                }
                active.remove(name);
            }
            rewrittenOut.append(callVar);
            cursor = closeParen + 1;
        }
        if (cursor < text.length()) {
            rewrittenOut.append(text, cursor, text.length());
        }
    }

    private static boolean anyOverloadTouchesEncoding(List<FunctionDef> defs) {
        for (FunctionDef def : defs) {
            String body = def.body();
            if (GAMMA_TO_LINEAR_FN.matcher(body).find()
                    || LINEAR_TO_SRGB_FN.matcher(body).find()
                    || LINEAR_TO_GAMMA_FN.matcher(body).find()
                    || TONEMAP_CALL.matcher(body).find()
                    || POW_CALL.matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<FunctionDef> selectMatchingOverloads(@Nullable List<FunctionDef> defs, List<@Nullable String> argTypes) {
        if (defs == null || defs.isEmpty()) return List.of();
        if (defs.size() == 1) return defs;
        int argCount = argTypes.size();
        List<FunctionDef> best = new ArrayList<>();
        int bestScore = Integer.MIN_VALUE;
        for (FunctionDef def : defs) {
            if (def.params().size() != argCount) continue;
            int score = 0;
            boolean compatible = true;
            for (int i = 0; i < argCount; i++) {
                String argType = argTypes.get(i);
                String paramType = def.params().get(i).type();
                if (argType == null || paramType.isEmpty()) {
                    continue;
                }
                if (argType.equals(paramType)) {
                    score += 4;
                } else if (isNumericImplicitConvertible(argType, paramType)) {
                    score += 1;
                } else {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) continue;
            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(def);
            } else if (score == bestScore) {
                best.add(def);
            }
        }
        return best;
    }

    private static boolean isNumericImplicitConvertible(String fromType, String toType) {
        if ("float".equals(toType) && ("int".equals(fromType) || "uint".equals(fromType))) return true;
        if ("vec2".equals(toType) && ("ivec2".equals(fromType) || "uvec2".equals(fromType))) return true;
        if ("vec3".equals(toType) && ("ivec3".equals(fromType) || "uvec3".equals(fromType))) return true;
        return "vec4".equals(toType) && ("ivec4".equals(fromType) || "uvec4".equals(fromType));
    }

    private static void collectVarDeclTypes(String source, Map<String, String> out) {
        if (source == null || source.isEmpty()) return;
        Matcher m = VAR_DECL_LINE.matcher(source);
        while (m.find()) {
            String type = m.group(1);
            String declList = m.group(2);
            for (String item : splitTopLevelArgs(declList)) {
                int eq = item.indexOf('=');
                String namePart = (eq >= 0 ? item.substring(0, eq) : item).trim();
                int br = namePart.indexOf('[');
                if (br > 0) namePart = namePart.substring(0, br).trim();
                if (!namePart.isEmpty() && isGlslIdentStart(namePart.charAt(0))) {
                    out.put(namePart, type);
                }
            }
        }
    }

    private static @Nullable String inferGlslExprType(
            String expr,
            Map<String, String> varTypes,
            Map<String, List<FunctionDef>> functionDefs
    ) {
        return inferGlslExprType(expr, null, varTypes, functionDefs);
    }

    private static @Nullable String inferGlslExprType(
            String expr,
            @Nullable ScopeNode scope,
            Map<String, String> varTypes,
            Map<String, List<FunctionDef>> functionDefs
    ) {
        String s = expr.trim();
        while (s.startsWith("(") && s.endsWith(")")) {
            int close = findClosingParen(s, 0);
            if (close == s.length() - 1) s = s.substring(1, close).trim();
            else break;
        }
        if (s.isEmpty()) return null;
        if (s.startsWith("-") || s.startsWith("+") || s.startsWith("!") || s.startsWith("~")) {
            return inferGlslExprType(s.substring(1), scope, varTypes, functionDefs);
        }
        // Check top-level ternary (cond ? a : b) before member/call checks.
        int q = findTopLevelChar(s, '?');
        if (q > 0) {
            int colon = findTopLevelCharFrom(s, ':', q + 1);
            if (colon > q) {
                String aType = inferGlslExprType(s.substring(q + 1, colon), scope, varTypes, functionDefs);
                String bType = inferGlslExprType(s.substring(colon + 1), scope, varTypes, functionDefs);
                if (Objects.equals(aType, bType)) return aType;
                return glslTypeWidth(aType) >= glslTypeWidth(bType) ? aType : bType;
            }
        }
        // Check binary operators (+ - * /) before call/dot so expressions like `(a).rgb + b` resolve cleanly.
        int d = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')' || c == ']') d++;
            else if (c == '(' || c == '[') d--;
            else if (d == 0 && (c == '+' || c == '-' || c == '*' || c == '/') && i > 0) {
                char prev = s.charAt(i - 1);
                if (prev == 'e' || prev == 'E') continue; // scientific notation guard
                String lt = inferGlslExprType(s.substring(0, i), scope, varTypes, functionDefs);
                String rt = inferGlslExprType(s.substring(i + 1), scope, varTypes, functionDefs);
                if (lt != null && rt != null) {
                    if (lt.equals(rt)) return lt;
                    if (lt.startsWith("vec") && ("float".equals(rt) || "int".equals(rt) || "uint".equals(rt))) return lt;
                    if (rt.startsWith("vec") && ("float".equals(lt) || "int".equals(lt) || "uint".equals(lt))) return rt;
                    if (lt.startsWith("mat") && rt.startsWith("vec")) return rt;
                    if (rt.startsWith("mat") && lt.startsWith("vec")) return lt;
                }
                return null;
            }
        }
        // Trailing swizzle `.rgb` etc. only if not inside unclosed call/brackets
        int lastDot = findTrailingTopLevelDot(s);
        if (lastDot > 0) {
            String suffix = s.substring(lastDot + 1).trim();
            if (suffix.matches("[rgbaxyzw]{1,4}")) {
                return switch (suffix.length()) {
                    case 1 -> "float";
                    case 2 -> "vec2";
                    case 3 -> "vec3";
                    case 4 -> "vec4";
                    default -> null;
                };
            }
        }
        if (s.endsWith(")")) {
            int open = -1;
            int depth = 0;
            for (int i = s.length() - 1; i >= 0; i--) {
                char c = s.charAt(i);
                if (c == ')') depth++;
                else if (c == '(') {
                    depth--;
                    if (depth == 0) {
                        open = i;
                        break;
                    }
                }
            }
            if (open > 0) {
                String head = s.substring(0, open).trim();
                if (isSimpleIdent(head)) {
                    switch (head) {
                        case "vec2", "vec3", "vec4", "float", "int", "uint", "bool",
                             "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
                             "mat2", "mat3", "mat4" -> { return head; }
                        case "texture", "texture2D", "texture2DLod", "textureLod", "texelFetch" -> { return "vec4"; }
                        case "dot", "length", "distance", "determinant" -> { return "float"; }
                        case "cross" -> { return "vec3"; }
                        case "all", "any" -> { return "bool"; }
                        case "sin", "cos", "tan", "asin", "acos", "atan",
                             "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
                             "radians", "degrees", "pow", "exp", "log", "exp2", "log2",
                             "sqrt", "inversesqrt", "abs", "sign", "floor", "trunc",
                             "round", "roundEven", "ceil", "fract", "mod", "min", "max",
                             "clamp", "mix", "fma", "dFdx", "dFdy", "fwidth", "normalize",
                             "faceforward", "reflect", "refract", "saturate" -> {
                            List<String> args = splitTopLevelArgs(s.substring(open + 1, s.length() - 1));
                            return args.isEmpty() ? null : inferGlslExprType(args.get(0), scope, varTypes, functionDefs);
                        }
                        case "step", "smoothstep" -> {
                            List<String> args = splitTopLevelArgs(s.substring(open + 1, s.length() - 1));
                            return args.isEmpty() ? null : inferGlslExprType(args.get(args.size() - 1), scope, varTypes, functionDefs);
                        }
                        default -> {
                            List<FunctionDef> defs = functionDefs.get(head);
                            if (defs != null && !defs.isEmpty()) {
                                List<String> args = splitTopLevelArgs(s.substring(open + 1, s.length() - 1));
                                List<@Nullable String> aTypes = new ArrayList<>(args.size());
                                for (String arg : args) aTypes.add(inferGlslExprType(arg, scope, varTypes, functionDefs));
                                List<FunctionDef> matched = selectMatchingOverloads(defs, aTypes);
                                if (!matched.isEmpty()) {
                                    String ret = matched.get(0).returnType();
                                    boolean same = !ret.isEmpty();
                                    for (FunctionDef m : matched) {
                                        if (!ret.equals(m.returnType())) { same = false; break; }
                                    }
                                    if (same) return ret;
                                }
                            }
                            return null;
                        }
                    }
                }
            }
        }
        if (s.endsWith("]")) {
            int br = s.indexOf('[');
            if (br > 0) {
                String base = s.substring(0, br).trim();
                if (isSimpleIdent(base)) {
                    VarSymbol sym = lookupSymbol(scope, base);
                    return sym != null ? sym.glslType() : varTypes.get(base);
                }
            }
        }
        if (isSimpleIdent(s)) {
            if ("gl_FragColor".equals(s) || "fragColor".equals(s)) return "vec4";
            VarSymbol sym = lookupSymbol(scope, s);
            return sym != null ? sym.glslType() : varTypes.get(s);
        }
        if (s.matches("[+-]?(?:\\d+\\.\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?[fF]?")) return "float";
        if (s.matches("[+-]?\\d+[uU]?")) return s.endsWith("u") || s.endsWith("U") ? "uint" : "int";
        if ("true".equals(s) || "false".equals(s)) return "bool";
        return null;
    }

    private static int findTrailingTopLevelDot(String s) {
        int d = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')' || c == ']') d++;
            else if (c == '(' || c == '[') d--;
            else if (c == '.' && d == 0) return i;
        }
        return -1;
    }

    private static int findTopLevelChar(String s, char target) {
        return findTopLevelCharFrom(s, target, 0);
    }

    private static int findTopLevelCharFrom(String s, char target, int start) {
        int d = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') d++;
            else if (c == ')' || c == ']') { if (d > 0) d--; }
            else if (c == target && d == 0) return i;
        }
        return -1;
    }

    private static int glslTypeWidth(@Nullable String type) {
        if (type == null || type.isEmpty()) return 0;
        return switch (type) {
            case "float", "int", "uint", "bool" -> 1;
            case "vec2", "ivec2", "uvec2", "bvec2" -> 2;
            case "vec3", "ivec3", "uvec3", "bvec3" -> 3;
            case "vec4", "ivec4", "uvec4", "bvec4" -> 4;
            default -> 0;
        };
    }

    private static boolean isSimpleIdent(String s) {
        if (s.isEmpty() || !isGlslIdentStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!isGlslIdentPart(s.charAt(i))) return false;
        }
        return true;
    }

    private static List<String> splitTopLevelArgs(String argsText) {
        List<String> args = new ArrayList<>();
        if (argsText.isBlank()) {
            return args;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < argsText.length(); i++) {
            char c = argsText.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') {
                if (depth > 0) depth--;
            } else if (c == ',' && depth == 0) {
                args.add(argsText.substring(start, i).trim());
                start = i + 1;
            }
        }
        args.add(argsText.substring(start).trim());
        return args;
    }

    private static Set<String> collectDeclaredLocalVars(String body) {
        Set<String> locals = new HashSet<>();
        for (Statement stmt : parseStatements(body)) {
            if (!stmt.isAssignment() || stmt.partialDef()) continue;
            String raw = stmt.raw();
            int eq = findTopLevelAssignmentOp(raw);
            if (eq <= 0) continue;
            String lhs = raw.substring(0, eq).trim();
            if (lhs.contains(" ") || lhs.contains("\t")) {
                locals.add(stmt.lhsBase());
            }
        }
        return locals;
    }

    private static String renameIdentifiers(String source, Map<String, String> renameMap) {
        if (renameMap.isEmpty() || source.isEmpty()) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (isGlslIdentStart(c)) {
                int end = i + 1;
                while (end < source.length() && isGlslIdentPart(source.charAt(end))) end++;
                String ident = source.substring(i, end);
                boolean isMember = i > 0 && source.charAt(i - 1) == '.';
                boolean isCall = end < source.length() && source.charAt(end) == '(';
                String mapped = (!isMember && !isCall) ? renameMap.get(ident) : null;
                out.append(mapped != null ? mapped : ident);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isAssignableLvalue(String expr) {
        if (expr.isEmpty()) return false;
        int dot = expr.indexOf('.');
        String head = dot > 0 ? expr.substring(0, dot).trim() : expr.trim();
        if (!isGlslIdentStart(head.charAt(0))) return false;
        for (int i = 1; i < head.length(); i++) {
            if (!isGlslIdentPart(head.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isOpaqueEncodingCall(String fnName) {
        String probe = fnName + "(";
        return GAMMA_TO_LINEAR_FN.matcher(probe).find()
                || LINEAR_TO_SRGB_FN.matcher(probe).find()
                || LINEAR_TO_GAMMA_FN.matcher(probe).find()
                || TONEMAP_CALL.matcher(probe).find();
    }

    private static int findMatchingBrace(String source, int openBrace) {
        int depth = 1;
        for (int i = openBrace + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static @Nullable String parseFunctionSignature(
            String source,
            int start,
            int openBrace,
            List<FunctionParam> paramsOut,
            String[] returnTypeOut
    ) {
        int end = openBrace - 1;
        while (end >= start && Character.isWhitespace(source.charAt(end))) {
            end--;
        }
        if (end < start || source.charAt(end) != ')') {
            return null;
        }
        int depth = 1;
        int openParen = -1;
        for (int i = end - 1; i >= start; i--) {
            char c = source.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) {
                    openParen = i;
                    break;
                }
            }
        }
        if (openParen < start) {
            return null;
        }
        int nameEnd = openParen - 1;
        while (nameEnd >= start && Character.isWhitespace(source.charAt(nameEnd))) {
            nameEnd--;
        }
        if (nameEnd < start) {
            return null;
        }
        int nameStart = nameEnd;
        while (nameStart >= start && isGlslIdentPart(source.charAt(nameStart))) {
            nameStart--;
        }
        nameStart++;
        if (nameStart > nameEnd || !isGlslIdentStart(source.charAt(nameStart))) {
            return null;
        }
        String fnName = source.substring(nameStart, nameEnd + 1);
        switch (fnName) {
            case "if", "for", "while", "switch", "layout" -> { return null; }
            default -> {
                String beforeName = source.substring(start, nameStart).trim();
                if (beforeName.isEmpty()) return null;
                String[] retTokens = beforeName.split("\\s+");
                returnTypeOut[0] = retTokens.length > 0 ? retTokens[retTokens.length - 1] : "";
                for (String rawParam : splitTopLevelArgs(source.substring(openParen + 1, end))) {
                    String trimmed = rawParam.trim();
                    if (trimmed.isEmpty() || "void".equals(trimmed)) continue;
                    int bracket = trimmed.indexOf('[');
                    String noArray = bracket > 0 ? trimmed.substring(0, bracket).trim() : trimmed;
                    String[] tokens = noArray.split("\\s+");
                    if (tokens.length < 2) continue;
                    boolean copyOut = false;
                    String paramType = "";
                    for (int t = 0; t < tokens.length - 1; t++) {
                        if ("out".equals(tokens[t]) || "inout".equals(tokens[t])) {
                            copyOut = true;
                        } else if (!"in".equals(tokens[t]) && !"const".equals(tokens[t])
                                && !"highp".equals(tokens[t]) && !"mediump".equals(tokens[t]) && !"lowp".equals(tokens[t])) {
                            paramType = tokens[t];
                        }
                    }
                    String paramName = tokens[tokens.length - 1];
                    if (!paramName.isEmpty() && isGlslIdentStart(paramName.charAt(0))) {
                        paramsOut.add(new FunctionParam(paramName, paramType, copyOut));
                    }
                }
                return fnName;
            }
        }
    }

    private static boolean isGlslIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isGlslIdentPart(char c) {
        return isGlslIdentStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean resolveAltBefore(List<PassInput> passes, boolean colortex0AltAtEnd, int cutIndex) {
        if (cutIndex >= passes.size()) {
            return colortex0AltAtEnd;
        }
        return passes.get(cutIndex).colortex0AltBefore();
    }

    private static boolean hasDofEvidence(String cleanedSource) {
        if (cleanedSource.isEmpty()) {
            return false;
        }
        if (DOF_CALL.matcher(cleanedSource).find() || DOF_IDENTIFIER.matcher(cleanedSource).find()) {
            return true;
        }
        return CENTER_DEPTH_SMOOTH.matcher(cleanedSource).find()
                && DOF_CONTEXT_HINT.matcher(cleanedSource).find();
    }

    private static boolean hasMotionBlurEvidence(String cleanedSource) {
        if (cleanedSource.isEmpty()) {
            return false;
        }
        if (MOTION_BLUR_CALL.matcher(cleanedSource).find()) {
            return true;
        }
        if (MOTION_BLUR_IDENTIFIER.matcher(cleanedSource).find()
                && MOTION_BLUR_SAMPLE_HINT.matcher(cleanedSource).find()) {
            return true;
        }
        return MOTION_PREVIOUS_POSITION.matcher(cleanedSource).find()
                && MOTION_REPROJECTION_VELOCITY.matcher(cleanedSource).find()
                && MOTION_VELOCITY_STEP.matcher(cleanedSource).find()
                && MOTION_COLORTEX0_SAMPLE.matcher(cleanedSource).find();
    }

    private record PowMatch(int argStart, int comma, int closeParen, boolean isDecode22, boolean isEncodeInv22) { }

    static ColourEncoding inferPreCompositeEncoding(List<String> orderedSources, boolean floatingPointTarget) {
        if (orderedSources != null) {
            for (String raw : orderedSources) {
                String cleaned = stripComments(raw);
                if (cleaned.isEmpty()) {
                    continue;
                }
                int slot = inferColortex0Slot(raw, true);
                ReachableProgram prog = extractReachableMainResult(cleaned);
                String body = sliceColortex0FlowForPreComposite(cleaned, prog, slot);
                if (body.contains(AMBIGUOUS_ENCODING_MARKER)) {
                    return ColourEncoding.UNKNOWN;
                }
                if (body.isEmpty()) {
                    continue;
                }
                int firstDecodePos = Integer.MAX_VALUE;
                ColourEncoding firstDecodeKind = null;
                int firstEncodeOrTonemapPos = Integer.MAX_VALUE;

                Matcher srgb = SRGB_TO_LINEAR_FN.matcher(body);
                while (srgb.find()) {
                    int close = findClosingParen(body, srgb.end() - 1);
                    String arg = close > srgb.end() ? body.substring(srgb.end(), close) : "";
                    if (!isNonSceneBufferExpr(arg)) {
                        int callEnd = close >= 0 ? close + 1 : srgb.end();
                        if (callEnd < firstDecodePos) {
                            firstDecodePos = callEnd;
                            firstDecodeKind = ColourEncoding.SRGB;
                        }
                        break;
                    }
                }
                Matcher gammaFn = GAMMA_ONLY_TO_LINEAR_FN.matcher(body);
                while (gammaFn.find()) {
                    int close = findClosingParen(body, gammaFn.end() - 1);
                    String arg = close > gammaFn.end() ? body.substring(gammaFn.end(), close) : "";
                    if (!isNonSceneBufferExpr(arg)) {
                        int callEnd = close >= 0 ? close + 1 : gammaFn.end();
                        if (callEnd < firstDecodePos) {
                            firstDecodePos = callEnd;
                            firstDecodeKind = ColourEncoding.GAMMA_2_2;
                        }
                        break;
                    }
                }
                Matcher linearToSrgb = LINEAR_TO_SRGB_FN.matcher(body);
                while (linearToSrgb.find()) {
                    int close = findClosingParen(body, linearToSrgb.end() - 1);
                    int callEnd = close >= 0 ? close + 1 : linearToSrgb.end();
                    firstEncodeOrTonemapPos = Math.min(firstEncodeOrTonemapPos, callEnd);
                }
                Matcher linearToGamma = LINEAR_TO_GAMMA_FN.matcher(body);
                while (linearToGamma.find()) {
                    int close = findClosingParen(body, linearToGamma.end() - 1);
                    int callEnd = close >= 0 ? close + 1 : linearToGamma.end();
                    firstEncodeOrTonemapPos = Math.min(firstEncodeOrTonemapPos, callEnd);
                }
                Matcher tonemap = TONEMAP_CALL.matcher(body);
                while (tonemap.find()) {
                    int close = findClosingParen(body, tonemap.end() - 1);
                    int callEnd = close >= 0 ? close + 1 : tonemap.end();
                    firstEncodeOrTonemapPos = Math.min(firstEncodeOrTonemapPos, callEnd);
                }
                for (PowMatch pow : scanPowCalls(body)) {
                    String baseArg = body.substring(pow.argStart(), pow.comma());
                    if (isNonSceneBufferExpr(baseArg)) {
                        continue;
                    }
                    int callEnd = pow.closeParen() + 1;
                    if (pow.isDecode22() && callEnd < firstDecodePos) {
                        firstDecodePos = callEnd;
                        firstDecodeKind = ColourEncoding.GAMMA_2_2;
                    } else if (pow.isEncodeInv22() && callEnd < firstEncodeOrTonemapPos) {
                        firstEncodeOrTonemapPos = callEnd;
                    }
                }
                if (firstDecodeKind != null && firstDecodePos < firstEncodeOrTonemapPos) {
                    return firstDecodeKind;
                }
                if (firstEncodeOrTonemapPos < Integer.MAX_VALUE) {
                    return ColourEncoding.LINEAR;
                }
            }
        }
        return floatingPointTarget ? ColourEncoding.LINEAR : ColourEncoding.SRGB;
    }

    private static boolean isNonSceneBufferExpr(String expression) {
        return NON_SCENE_BUFFER_HINT.matcher(expression).find() && !expression.contains("colortex0");
    }

    private static ColourEncoding advanceComputeEncoding(ColourEncoding current, ReachableProgram computeProg) {
        if (current == ColourEncoding.UNKNOWN || computeProg.source().isEmpty()) {
            return current;
        }
        String sliced = sliceComputeColorImg0Flow(computeProg.source(), computeProg.varTypes(), computeProg.functionDefs());
        return sliced.isEmpty() ? current : advanceEncoding(current, sliced);
    }

    private static ColourEncoding advanceFragmentEncoding(
            ColourEncoding current,
            String cleanedFragment,
            ReachableProgram fragProg,
            int colortex0Slot
    ) {
        if (current == ColourEncoding.UNKNOWN || fragProg.source().isEmpty() || colortex0Slot < 0) {
            return current;
        }
        String sliced = sliceFragmentColortex0Flow(cleanedFragment, fragProg.source(),
                fragProg.varTypes(), fragProg.functionDefs(), colortex0Slot, true);
        if (sliced == null) {
            // Unmatched legacy write pattern when colortex0 is explicitly targeted and no other sink wrote.
            return advanceEncoding(current, fragProg.source());
        }
        return sliced.isEmpty() ? current : advanceEncoding(current, sliced);
    }

    private static String sliceColortex0FlowForPreComposite(String cleaned, ReachableProgram prog, int slot) {
        String executable = prog.source();
        if (executable.isEmpty()) {
            return "";
        }
        if (executable.contains("imageStore")) {
            String computeSliced = sliceComputeColorImg0Flow(executable, prog.varTypes(), prog.functionDefs());
            if (!computeSliced.isEmpty()) {
                return computeSliced;
            }
        }
        String fragSliced = sliceFragmentColortex0Flow(cleaned, executable, prog.varTypes(), prog.functionDefs(),
                Math.max(0, slot), false);
        return fragSliced != null ? fragSliced : executable;
    }

    private static final int CH_R = 1;
    private static final int CH_G = 2;
    private static final int CH_B = 4;
    private static final int CH_A = 8;
    private static final int CH_RGB = CH_R | CH_G | CH_B;
    private static final int CH_RGBA = CH_RGB | CH_A;

    private static String sliceComputeColorImg0Flow(
            String executableCompute,
            Map<String, String> varTypes,
            Map<String, List<FunctionDef>> functionDefs
    ) {
        List<Statement> stmts = parseStatements(executableCompute);
        boolean[] keep = new boolean[stmts.size()];
        String[] keptExpr = new String[stmts.size()];
        Map<SymbolKey, Integer> neededMasks = new HashMap<>();
        boolean sawImg0Write = false;
        int img0LiveMask = CH_RGB;
        for (int i = stmts.size() - 1; i >= 0; i--) {
            Statement stmt = stmts.get(i);
            if (stmt.isReturn() && !stmt.unconditionalInMain()) {
                img0LiveMask |= CH_RGB;
            }
            if (!stmt.isAssignment() && stmt.raw().contains("imageStore")) {
                List<String> img0Args = extractImageStoreDataArgs(stmt.raw(), "colorimg0");
                if (!img0Args.isEmpty()) {
                    if (img0LiveMask == 0) {
                        continue;
                    }
                    sawImg0Write = true;
                    keep[i] = true;
                    List<String> rgbArgs = new ArrayList<>(img0Args.size());
                    List<VarDemand> demands = new ArrayList<>();
                    for (String dataArg : img0Args) {
                        String rgbArg = sliceExprForDemand(dataArg, img0LiveMask, stmt.scope(), varTypes, functionDefs, demands);
                        rgbArgs.add(rgbArg);
                    }
                    if (stmt.unconditionalInMain()) {
                        img0LiveMask = 0;
                    }
                    for (VarDemand d : demands) {
                        if (d.key().scopeId() == 0 && "colorimg0".equals(d.key().name())) {
                            img0LiveMask |= (d.mask() & CH_RGB);
                        } else {
                            neededMasks.merge(d.key(), d.mask(), (a, b) -> a | b);
                        }
                    }
                    String combined = String.join("; ", rgbArgs);
                    keptExpr[i] = !stmt.unconditionalInMain() && hasEncodingTransform(combined)
                            ? combined + "; " + AMBIGUOUS_ENCODING_MARKER : combined;
                }
            } else if (stmt.isAssignment()) {
                propagateAssignmentSlice(stmt, i, keep, keptExpr, neededMasks, varTypes, functionDefs);
            }
        }
        if (!sawImg0Write) {
            return "";
        }
        return joinKeptStatements(stmts.size(), keep, keptExpr);
    }

    private static @Nullable String sliceFragmentColortex0Flow(
            String cleanedFragment,
            String executableFragment,
            Map<String, String> varTypes,
            Map<String, List<FunctionDef>> functionDefs,
            int colortex0Slot,
            boolean distinguishNoSink
    ) {
        Set<String> allDeclaredOutVars = new HashSet<>();
        Set<String> colortex0OutVars = collectColortex0OutVars(cleanedFragment, colortex0Slot, allDeclaredOutVars);
        Set<String> colortex0Sinks = new HashSet<>(colortex0OutVars);
        colortex0Sinks.add("colortex0");
        if (colortex0Slot >= 0) {
            colortex0Sinks.add("gl_FragData[" + colortex0Slot + "]");
            if (colortex0Slot == 0) {
                colortex0Sinks.add("gl_FragColor");
                if (allDeclaredOutVars.isEmpty()) {
                    colortex0Sinks.add("fragColor");
                    colortex0Sinks.add("outColor");
                    colortex0Sinks.add("outColor0");
                    colortex0Sinks.add("iris_FragColor");
                }
            }
        }
        Set<String> writableColortex0Targets = new HashSet<>();
        for (String sink : colortex0Sinks) {
            String vt = varTypes.get(sink);
            if (vt == null || (!vt.contains("sampler") && !vt.contains("image"))) {
                writableColortex0Targets.add(sink);
            }
        }
        List<Statement> stmts = parseStatements(executableFragment);
        boolean[] keep = new boolean[stmts.size()];
        String[] keptExpr = new String[stmts.size()];
        Map<SymbolKey, Integer> neededMasks = new HashMap<>();
        boolean sawColortex0RgbWrite = false;
        boolean sawOtherSinkOrAlphaWrite = false;
        int sinkLiveMask = CH_RGB;
        for (int i = stmts.size() - 1; i >= 0; i--) {
            Statement stmt = stmts.get(i);
            if (stmt.isReturn() && !stmt.unconditionalInMain()) {
                sinkLiveMask |= CH_RGB;
            }
            if (!stmt.isAssignment()) {
                continue;
            }
            if (stmt.isAlphaOnlyWrite()) {
                if (colortex0Sinks.contains(stmt.lhsSinkKey()) || isAnyOutputSink(stmt.lhsSinkKey(), allDeclaredOutVars)) {
                    if (!sawColortex0RgbWrite) sawOtherSinkOrAlphaWrite = true;
                } else {
                    propagateAssignmentSlice(stmt, i, keep, keptExpr, neededMasks, varTypes, functionDefs);
                }
                continue;
            }
            String target = stmt.lhsSinkKey();
            if (colortex0Sinks.contains(target) && stmt.lhsSymbolId() == 0) {
                int writtenMask = stmt.lhsMask() & CH_RGB;
                if (writtenMask == 0) {
                    if (!sawColortex0RgbWrite) sawOtherSinkOrAlphaWrite = true;
                    continue;
                }
                int sinkDemand = sinkLiveMask & writtenMask;
                if (sinkDemand == 0) {
                    continue;
                }
                sawColortex0RgbWrite = true;
                keep[i] = true;
                int rhsMask = mapLhsSwizzleToRhsMask(stmt.swizzle(), stmt.alphaIndex(), stmt.partialDef(), sinkDemand);
                List<VarDemand> demands = new ArrayList<>();
                String rhs = sliceExprForDemand(stmt.rhs(), rhsMask, stmt.scope(), varTypes, functionDefs, demands);
                keptExpr[i] = !stmt.unconditionalInMain() && hasEncodingTransform(rhs)
                        ? rhs + "; " + AMBIGUOUS_ENCODING_MARKER : rhs;
                if (stmt.unconditionalInMain() && (!stmt.partialDef() || stmt.swizzle().matches("[rgbaxyzw]+"))) {
                    sinkLiveMask &= ~writtenMask;
                }
                for (VarDemand d : demands) {
                    if (d.key().scopeId() == 0 && writableColortex0Targets.contains(d.key().name())) {
                        sinkLiveMask |= (d.mask() & CH_RGB);
                    } else {
                        neededMasks.merge(d.key(), d.mask(), (a, b) -> a | b);
                    }
                }
            } else if (isAnyOutputSink(target, allDeclaredOutVars) && stmt.lhsSymbolId() == 0) {
                if (!sawColortex0RgbWrite) sawOtherSinkOrAlphaWrite = true;
                propagateAssignmentSlice(stmt, i, keep, keptExpr, neededMasks, varTypes, functionDefs);
            } else {
                propagateAssignmentSlice(stmt, i, keep, keptExpr, neededMasks, varTypes, functionDefs);
            }
        }
        if (sawColortex0RgbWrite) {
            return joinKeptStatements(stmts.size(), keep, keptExpr);
        }
        if (sawOtherSinkOrAlphaWrite) {
            return "";
        }
        return distinguishNoSink ? null : executableFragment;
    }

    private static void propagateAssignmentSlice(
            Statement stmt,
            int idx,
            boolean[] keep,
            String[] keptExpr,
            Map<SymbolKey, Integer> neededMasks,
            Map<String, String> varTypes,
            Map<String, List<FunctionDef>> functionDefs
    ) {
        SymbolKey lhsKey = stmt.lhsSymbolKey();
        int needed = neededMasks.getOrDefault(lhsKey, 0);
        int overlap = needed & stmt.lhsMask();
        if (overlap == 0) {
            return;
        }
        keep[idx] = true;
        int readMask = mapLhsSwizzleToRhsMask(stmt.swizzle(), stmt.alphaIndex(), stmt.partialDef(), overlap);
        List<VarDemand> demands = new ArrayList<>();
        String rhs = sliceExprForDemand(stmt.rhs(), readMask, stmt.scope(), varTypes, functionDefs, demands);
        keptExpr[idx] = !stmt.unconditionalInMain() && hasEncodingTransform(rhs)
                ? rhs + "; " + AMBIGUOUS_ENCODING_MARKER : rhs;
        if (stmt.unconditionalInMain() && (!stmt.partialDef() || stmt.swizzle().matches("[rgbaxyzw]+"))) {
            int remaining = needed & ~stmt.lhsMask();
            if (remaining == 0) neededMasks.remove(lhsKey);
            else neededMasks.put(lhsKey, remaining);
        }
        for (VarDemand d : demands) neededMasks.merge(d.key(), d.mask(), (a, b) -> a | b);
    }

    private static String joinKeptStatements(int count, boolean[] keep, String[] keptExpr) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (keep[i] && keptExpr[i] != null) {
                out.append(keptExpr[i]).append(';');
            }
        }
        return out.toString();
    }

    private static Set<String> collectColortex0OutVars(String cleanedFragment, int colortex0Slot, Set<String> allOutVars) {
        Set<String> matched = new HashSet<>();
        if (cleanedFragment.isEmpty()) {
            return matched;
        }
        // Scan global scope declarations only (braceDepth == 0).
        StringBuilder globalOnly = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < cleanedFragment.length(); i++) {
            char c = cleanedFragment.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth > 0) depth--;
            } else if (depth == 0) {
                globalOnly.append(c);
            }
        }
        Matcher m = OUT_VAR_DECL.matcher(globalOnly.toString());
        int autoLoc = 0;
        while (m.find()) {
            String layoutArgs = m.group(1);
            String varName = m.group(2);
            allOutVars.add(varName);
            int loc = autoLoc;
            if (layoutArgs != null) {
                Matcher locMatcher = LAYOUT_LOCATION.matcher(layoutArgs);
                if (locMatcher.find()) {
                    loc = Integer.parseInt(locMatcher.group(1));
                }
            }
            autoLoc = Math.max(autoLoc, loc + 1);
            if (loc == colortex0Slot) {
                matched.add(varName);
            }
        }
        return matched;
    }

    private static boolean isAnyOutputSink(String sinkKey, Set<String> declaredOutVars) {
        if (declaredOutVars.contains(sinkKey) || "gl_FragColor".equals(sinkKey)) {
            return true;
        }
        if (sinkKey.startsWith("colortex") || sinkKey.startsWith("gl_FragData[")) {
            return true;
        }
        return false;
    }

    private record VarSymbol(int id, String name, String glslType) { }
    private record SymbolKey(int scopeId, String name) { }
    private record VarDemand(SymbolKey key, int mask) { }
    private record ScopeNode(@Nullable ScopeNode parent, @Nullable String name, @Nullable VarSymbol symbol, int blockDepth) { }

    private static @Nullable VarSymbol lookupSymbol(@Nullable ScopeNode scope, String name) {
        for (ScopeNode cur = scope; cur != null; cur = cur.parent()) {
            if (cur.symbol() != null && name.equals(cur.name())) {
                return cur.symbol();
            }
        }
        return null;
    }

    private static SymbolKey resolveSymbolKey(@Nullable ScopeNode scope, String name) {
        VarSymbol sym = lookupSymbol(scope, name);
        return sym != null ? new SymbolKey(sym.id(), name) : new SymbolKey(0, name);
    }

    private record Statement(String raw, @Nullable String lhsSinkKey, @Nullable String lhsBase,
                             String swizzle, @Nullable String rhs, boolean partialDef, boolean alphaIndex,
                             int lhsMask, int lhsSymbolId, @Nullable ScopeNode scope,
                             boolean unconditionalInMain, boolean isReturn) {
        boolean isAssignment() {
            return lhsSinkKey != null && lhsBase != null && rhs != null;
        }

        SymbolKey lhsSymbolKey() {
            return new SymbolKey(lhsSymbolId, Objects.requireNonNull(lhsBase));
        }

        boolean isAlphaOnlyWrite() {
            return lhsMask == CH_A;
        }
    }

    private static int swizzleMask(String swizzle, boolean alphaIndex) {
        if (alphaIndex) return CH_A;
        if (swizzle.isEmpty()) return CH_RGBA;
        int mask = 0;
        for (int i = 0; i < swizzle.length(); i++) {
            switch (swizzle.charAt(i)) {
                case 'r', 'x' -> mask |= CH_R;
                case 'g', 'y' -> mask |= CH_G;
                case 'b', 'z' -> mask |= CH_B;
                case 'a', 'w' -> mask |= CH_A;
                default -> { return CH_RGBA; }
            }
        }
        return mask == 0 ? CH_RGBA : mask;
    }

    private static boolean isControlFlowBlockHeader(String header) {
        String s = header.trim();
        return s.startsWith("if ") || s.startsWith("if(")
                || s.startsWith("else") || s.startsWith("for ") || s.startsWith("for(")
                || s.startsWith("while ") || s.startsWith("while(")
                || s.startsWith("switch ") || s.startsWith("switch(")
                || s.equals("do") || s.startsWith("do ");
    }

    private static boolean isMainSignatureHeader(String header) {
        return header.matches("^.*\\bvoid\\s+main\\s*\\([^)]*\\)$");
    }

    private static List<Statement> parseStatements(String source) {
        List<Statement> list = new ArrayList<>();
        int parenDepth = 0;
        int start = 0;
        int blockDepth = 0;
        int conditionalDepth = 0;
        java.util.Deque<Boolean> controlBraceStack = new java.util.ArrayDeque<>();
        int[] nextSymId = { 1 };
        ScopeNode scope = new ScopeNode(null, null, null, 0);
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                if (parenDepth > 0) parenDepth--;
            } else if (parenDepth == 0 && (c == ';' || c == '{' || c == '}')) {
                String piece = source.substring(start, i).trim();
                if (!piece.isEmpty() && c != '{') {
                    ScopeNode[] updatedScope = { scope };
                    boolean singleStmtCtrl = isControlFlowBlockHeader(piece);
                    boolean unconditional = conditionalDepth == 0 && !singleStmtCtrl;
                    list.add(parseSingleStatement(piece, scope, nextSymId, blockDepth, updatedScope, unconditional));
                    scope = updatedScope[0];
                }
                if (c == '{') {
                    blockDepth++;
                    boolean isMainHeader = blockDepth == 1 && isMainSignatureHeader(piece);
                    boolean isOtherTopFn = blockDepth == 1 && !isMainHeader && !piece.isEmpty() && !isControlFlowBlockHeader(piece);
                    boolean isCtrl = isOtherTopFn || isControlFlowBlockHeader(piece);
                    if (isCtrl) conditionalDepth++;
                    controlBraceStack.push(isCtrl);
                    scope = new ScopeNode(scope, null, null, blockDepth);
                } else if (c == '}' && blockDepth > 0) {
                    if (!controlBraceStack.isEmpty() && Boolean.TRUE.equals(controlBraceStack.pop())) {
                        conditionalDepth--;
                    }
                    int targetDepth = blockDepth - 1;
                    blockDepth = targetDepth;
                    while (scope.parent() != null && scope.blockDepth() > targetDepth) {
                        scope = scope.parent();
                    }
                }
                start = i + 1;
            }
        }
        if (start < source.length()) {
            String piece = source.substring(start).trim();
            if (!piece.isEmpty()) {
                ScopeNode[] updatedScope = { scope };
                boolean unconditional = conditionalDepth == 0 && !isControlFlowBlockHeader(piece);
                list.add(parseSingleStatement(piece, scope, nextSymId, blockDepth, updatedScope, unconditional));
            }
        }
        return list;
    }

    private static final Pattern GLSL_TYPE_DECL_LHS = Pattern.compile(
            "^(?:(?:const|highp|mediump|lowp|flat|smooth|noperspective|centroid|sample|invariant)\\s+)*"
            + "(float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4|uvec2|uvec3|uvec4|bvec2|bvec3|bvec4|mat2|mat3|mat4)"
            + "\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\[[^\\]]*\\])?$");

    private static Statement parseSingleStatement(
            String stmt,
            ScopeNode currentScope,
            int[] nextSymId,
            int blockDepth,
            ScopeNode[] outScope,
            boolean unconditionalInMain
    ) {
        boolean isReturn = stmt.equals("return") || stmt.startsWith("return ")
                || (isControlFlowBlockHeader(stmt) && stmt.matches(".*\\breturn\\b.*"));
        int eq = findTopLevelAssignmentOp(stmt);
        if (eq <= 0) {
            Matcher uninit = Pattern.compile(
                    "^(?:(?:const|highp|mediump|lowp)\\s+)*"
                    + "(float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4|uvec2|uvec3|uvec4|bvec2|bvec3|bvec4|mat2|mat3|mat4)"
                    + "\\s+(.+)$").matcher(stmt.trim());
            if (uninit.matches()) {
                String declType = uninit.group(1);
                ScopeNode s = currentScope;
                for (String item : splitTopLevelArgs(uninit.group(2))) {
                    String namePart = item.trim();
                    int br = namePart.indexOf('[');
                    if (br > 0) namePart = namePart.substring(0, br).trim();
                    if (isSimpleIdent(namePart)) {
                        VarSymbol sym = new VarSymbol(nextSymId[0]++, namePart, declType);
                        s = new ScopeNode(s, namePart, sym, blockDepth);
                    }
                }
                outScope[0] = s;
            }
            return new Statement(stmt, null, null, "", null, false, false, CH_RGBA, 0, currentScope, unconditionalInMain, isReturn);
        }
        boolean compound = stmt.charAt(eq - 1) == '+' || stmt.charAt(eq - 1) == '-'
                || stmt.charAt(eq - 1) == '*' || stmt.charAt(eq - 1) == '/';
        int lhsEnd = compound ? eq - 1 : eq;
        String lhs = stmt.substring(0, lhsEnd).trim();
        String rhs = stmt.substring(eq + 1).trim();
        if (lhs.startsWith("if") && lhs.contains("(")) {
            int close = findClosingParen(lhs, lhs.indexOf('('));
            if (close > 0 && close + 1 < lhs.length()) {
                lhs = lhs.substring(close + 1).trim();
            }
        }
        Matcher declMatcher = !compound ? GLSL_TYPE_DECL_LHS.matcher(lhs) : null;
        if (declMatcher != null && declMatcher.matches()) {
            String declType = declMatcher.group(1);
            String varName = declMatcher.group(2);
            VarSymbol sym = new VarSymbol(nextSymId[0]++, varName, declType);
            outScope[0] = new ScopeNode(currentScope, varName, sym, blockDepth);
            // RHS of `vec3 c = f(c);` sees currentScope BEFORE introducing new local `c`.
            return new Statement(stmt, varName, varName, "", rhs, false, false, CH_RGBA, sym.id(), currentScope, unconditionalInMain, false);
        }
        // Strip leading type / qualifier tokens from declaration assignment (`custom_t c = ...`).
        String cleanedLhs = lhs;
        int lastSpace = Math.max(lhs.lastIndexOf(' '), lhs.lastIndexOf('\t'));
        if (lastSpace > 0 && !lhs.contains("[")) {
            cleanedLhs = lhs.substring(lastSpace + 1).trim();
        } else if (lastSpace > 0 && lhs.indexOf(' ') < lhs.indexOf('[')) {
            cleanedLhs = lhs.substring(lastSpace + 1).trim();
        }
        int dot = cleanedLhs.indexOf('.');
        String sinkKey = dot > 0 ? cleanedLhs.substring(0, dot).trim() : cleanedLhs;
        String swizzle = dot > 0 ? cleanedLhs.substring(dot + 1).trim() : "";
        boolean alphaIndex = dot < 0 && cleanedLhs.endsWith("[3]");
        if (alphaIndex) {
            sinkKey = cleanedLhs.substring(0, cleanedLhs.length() - 3).trim();
        }
        int bracket = sinkKey.indexOf('[');
        String baseVar = bracket > 0 ? sinkKey.substring(0, bracket).trim() : sinkKey;
        if (compound) {
            rhs = "(" + cleanedLhs + ") " + stmt.charAt(eq - 1) + " (" + rhs + ")";
        }
        boolean partialDef = dot > 0 || bracket > 0 || alphaIndex;
        int lhsMask = swizzleMask(swizzle, alphaIndex);
        VarSymbol existing = lookupSymbol(currentScope, baseVar);
        int symId = existing != null ? existing.id() : 0;
        return new Statement(stmt, sinkKey, baseVar, swizzle, rhs, partialDef, alphaIndex, lhsMask, symId, currentScope, unconditionalInMain, false);
    }

    private static String sliceExprForDemand(
            String expr,
            int neededMask,
            @Nullable ScopeNode scope,
            Map<String, String> fallbackTypes,
            Map<String, List<FunctionDef>> functionDefs,
            List<VarDemand> outDemands
    ) {
        if (neededMask == 0) {
            return "";
        }
        String trimmed = expr.trim();
        while (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            int close = findClosingParen(trimmed, 0);
            if (close == trimmed.length() - 1) trimmed = trimmed.substring(1, close).trim();
            else break;
        }
        if (trimmed.isEmpty()) {
            return expr;
        }

        // Handle trailing swizzle on arbitrary expressions, e.g., `vec4(a, b, c, d).bgr` or `packed.ba`
        int lastDot = findTrailingTopLevelDot(trimmed);
        if (lastDot > 0) {
            String base = trimmed.substring(0, lastDot).trim();
            String swz = trimmed.substring(lastDot + 1).trim();
            if (swz.matches("[rgbaxyzw]{1,4}")) {
                int mappedMask = mapSwizzleDemand(swz, neededMask);
                if (mappedMask == 0) {
                    return "";
                }
                String slicedBase = sliceExprForDemand(base, mappedMask, scope, fallbackTypes, functionDefs, outDemands);
                return base.startsWith("vec") ? slicedBase : expr;
            }
        }

        // Vector constructors: vec2 / vec3 / vec4 / ivec2..4 / uvec2..4 / bvec2..4
        if (trimmed.endsWith(")")) {
            int targetW = constructorVectorWidth(trimmed);
            if (targetW >= 2) {
                int open = trimmed.indexOf('(');
                int close = findClosingParen(trimmed, open);
                if (close == trimmed.length() - 1) {
                    int targetMask = neededMask & ((1 << targetW) - 1);
                    if (targetMask == 0) {
                        return "";
                    }
                    List<String> args = splitTopLevelArgs(trimmed.substring(open + 1, close));
                    if (args.size() == 1) {
                        int argW = glslTypeWidth(inferGlslExprType(args.get(0), scope, fallbackTypes, functionDefs));
                        int argMask = argW == targetW ? targetMask : CH_RGBA;
                        return sliceExprForDemand(args.get(0), argMask, scope, fallbackTypes, functionDefs, outDemands);
                    }
                    int nArgs = args.size();
                    if (nArgs >= 2 && nArgs <= targetW) {
                        int[] widths = new int[nArgs];
                        int unknownIdx = -1;
                        int unknownCount = 0;
                        int knownSum = 0;
                        for (int k = 0; k < nArgs; k++) {
                            int w = glslTypeWidth(inferGlslExprType(args.get(k), scope, fallbackTypes, functionDefs));
                            if (w == 0 && isScalarExprHeuristic(args.get(k))) w = 1;
                            widths[k] = w;
                            if (w == 0) {
                                unknownIdx = k;
                                unknownCount++;
                            } else {
                                knownSum += w;
                            }
                        }
                        if (unknownCount == 1 && targetW - knownSum >= 1) {
                            widths[unknownIdx] = targetW - knownSum;
                            knownSum = targetW;
                        }
                        if (knownSum == targetW) {
                            List<String> kept = new ArrayList<>(nArgs);
                            int ch = 0;
                            for (int k = 0; k < nArgs; k++) {
                                int w = widths[k];
                                int subMask = 0;
                                for (int b = 0; b < w && ch + b < targetW; b++) {
                                    if ((targetMask & (1 << (ch + b))) != 0) {
                                        subMask |= (1 << b);
                                    }
                                }
                                ch += w;
                                if (subMask != 0) {
                                    String piece = sliceExprForDemand(
                                            args.get(k), subMask, scope, fallbackTypes, functionDefs, outDemands);
                                    if (!piece.isEmpty()) {
                                        kept.add(piece);
                                    }
                                }
                            }
                            return kept.isEmpty() ? "" : String.join(", ", kept);
                        }
                    }
                    if (args.size() == targetW && targetW == 4) {
                        List<String> kept = new ArrayList<>(4);
                        int[] bits = { CH_R, CH_G, CH_B, CH_A };
                        for (int k = 0; k < 4; k++) {
                            if ((targetMask & bits[k]) != 0) {
                                String piece = sliceExprForDemand(args.get(k), CH_R, scope, fallbackTypes, functionDefs, outDemands);
                                if (!piece.isEmpty()) kept.add(piece);
                            }
                        }
                        return kept.isEmpty() ? "" : String.join(", ", kept);
                    }
                }
            }
        }

        // Pure identifier: preserve exact channel mask demand for this binding slot!
        if (isSimpleIdent(trimmed)) {
            outDemands.add(new VarDemand(resolveSymbolKey(scope, trimmed), neededMask));
            return expr;
        }

        // Array subscript: var[0..3]
        if (trimmed.endsWith("]")) {
            int br = trimmed.indexOf('[');
            if (br > 0) {
                String base = trimmed.substring(0, br).trim();
                String idxTxt = trimmed.substring(br + 1, trimmed.length() - 1).trim();
                if (isSimpleIdent(base)) {
                    int elemMask = switch (idxTxt) {
                        case "0" -> CH_R;
                        case "1" -> CH_G;
                        case "2" -> CH_B;
                        case "3" -> CH_A;
                        default -> neededMask;
                    };
                    outDemands.add(new VarDemand(resolveSymbolKey(scope, base), elemMask));
                    return expr;
                }
            }
        }

        // Ternary operator
        int q = findTopLevelChar(trimmed, '?');
        if (q > 0) {
            int colon = findTopLevelCharFrom(trimmed, ':', q + 1);
            if (colon > q) {
                String cond = trimmed.substring(0, q).trim();
                String tBranch = trimmed.substring(q + 1, colon).trim();
                String fBranch = trimmed.substring(colon + 1).trim();
                propagateExprDemand(cond, CH_RGBA, scope, outDemands);
                String tSlice = sliceExprForDemand(tBranch, neededMask, scope, fallbackTypes, functionDefs, outDemands);
                String fSlice = sliceExprForDemand(fBranch, neededMask, scope, fallbackTypes, functionDefs, outDemands);
                if (hasDivergentBranchEncoding(tSlice, fSlice)) {
                    return AMBIGUOUS_ENCODING_MARKER;
                }
                return tSlice + "; " + fSlice;
            }
        }

        // Componentwise binary +/-/*/: keep channel mask unless a matrix operand mixes channels.
        int d = 0;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == ')' || c == ']') d++;
            else if (c == '(' || c == '[') d--;
            else if (d == 0 && (c == '+' || c == '-' || c == '*' || c == '/') && i > 0) {
                char prev = trimmed.charAt(i - 1);
                if (prev == 'e' || prev == 'E') continue;
                String left = trimmed.substring(0, i).trim();
                String right = trimmed.substring(i + 1).trim();
                if (!left.isEmpty() && !right.isEmpty()) {
                    String lt = inferGlslExprType(left, scope, fallbackTypes, functionDefs);
                    String rt = inferGlslExprType(right, scope, fallbackTypes, functionDefs);
                    boolean mixesChannels = (lt != null && lt.startsWith("mat")) || (rt != null && rt.startsWith("mat"));
                    int opMask = mixesChannels ? CH_RGBA : neededMask;
                    int lMask = "float".equals(lt) || "int".equals(lt) || "uint".equals(lt) ? CH_RGBA : opMask;
                    int rMask = "float".equals(rt) || "int".equals(rt) || "uint".equals(rt) ? CH_RGBA : opMask;
                    sliceExprForDemand(left, lMask, scope, fallbackTypes, functionDefs, outDemands);
                    sliceExprForDemand(right, rMask, scope, fallbackTypes, functionDefs, outDemands);
                    return expr;
                }
            }
        }

        propagateExprDemand(expr, neededMask, scope, outDemands);
        return expr;
    }

    private static int constructorVectorWidth(String trimmed) {
        int open = trimmed.indexOf('(');
        if (open <= 0) return 0;
        String head = trimmed.substring(0, open).trim();
        return switch (head) {
            case "vec2", "ivec2", "uvec2", "bvec2" -> 2;
            case "vec3", "ivec3", "uvec3", "bvec3" -> 3;
            case "vec4", "ivec4", "uvec4", "bvec4" -> 4;
            default -> 0;
        };
    }

    private static int singleSwizzleBit(char c) {
        return switch (c) {
            case 'r', 'x' -> 0;
            case 'g', 'y' -> 1;
            case 'b', 'z' -> 2;
            case 'a', 'w' -> 3;
            default -> -1;
        };
    }

    private static int mapLhsSwizzleToRhsMask(String swizzle, boolean alphaIndex, boolean partialDef, int neededLhsMask) {
        if (alphaIndex) {
            return (neededLhsMask & CH_A) != 0 ? CH_R : 0;
        }
        if (swizzle.isEmpty()) {
            return partialDef ? CH_RGBA : neededLhsMask;
        }
        int rhsMask = 0;
        int count = Math.min(swizzle.length(), 4);
        for (int i = 0; i < count; i++) {
            int lhsBit = singleSwizzleBit(swizzle.charAt(i));
            if (lhsBit < 0) return CH_RGBA;
            if ((neededLhsMask & (1 << lhsBit)) != 0) {
                rhsMask |= (1 << i);
            }
        }
        return rhsMask;
    }

    private static int mapSwizzleDemand(String swz, int neededMask) {
        if ((neededMask & CH_RGBA) == CH_RGBA) {
            return swizzleMask(swz, false);
        }
        int mapped = 0;
        for (int bit = 0; bit < swz.length() && bit < 4; bit++) {
            if ((neededMask & (1 << bit)) != 0) {
                int srcBit = singleSwizzleBit(swz.charAt(bit));
                if (srcBit < 0) return CH_RGBA;
                mapped |= (1 << srcBit);
            }
        }
        return mapped;
    }

    private static boolean isScalarExprHeuristic(String arg) {
        String s = arg.trim();
        int dot = findTrailingTopLevelDot(s);
        if (dot > 0) {
            String sw = s.substring(dot + 1).trim();
            if (sw.length() == 1 && "rgbaxyzw".indexOf(sw.charAt(0)) >= 0) {
                return true;
            }
        }
        if (s.startsWith("float(") && s.endsWith(")")) {
            return true;
        }
        String num = s.endsWith("f") || s.endsWith("F") ? s.substring(0, s.length() - 1) : s;
        try {
            Double.parseDouble(num);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void propagateExprDemand(
            String expr,
            int defaultMask,
            @Nullable ScopeNode scope,
            List<VarDemand> outDemands
    ) {
        int i = 0;
        int len = expr.length();
        while (i < len) {
            char c = expr.charAt(i);
            if (isGlslIdentStart(c)) {
                int end = i + 1;
                while (end < len && isGlslIdentPart(expr.charAt(end))) end++;
                String ident = expr.substring(i, end);
                boolean isMember = i > 0 && expr.charAt(i - 1) == '.';
                boolean isCall = end < len && expr.charAt(end) == '(';
                if (!isMember && !isCall) {
                    int mask = defaultMask;
                    if ("gl_FragData".equals(ident) && end + 2 < len && expr.charAt(end) == '[') {
                        int closeBr = expr.indexOf(']', end + 1);
                        if (closeBr > end + 1) {
                            String idxTxt = expr.substring(end + 1, closeBr).trim();
                            ident = "gl_FragData[" + idxTxt + "]";
                            end = closeBr + 1;
                        }
                    }
                    if (end + 1 < len && expr.charAt(end) == '.') {
                        int swEnd = end + 1;
                        while (swEnd < len && isGlslIdentPart(expr.charAt(swEnd))) swEnd++;
                        String sw = expr.substring(end + 1, swEnd);
                        if (sw.matches("[rgbaxyzw]{1,4}")) {
                            mask = swizzleMask(sw, false);
                        } else {
                            mask = CH_RGBA;
                        }
                    } else if (end + 2 < len && expr.charAt(end) == '[') {
                        int closeBr = expr.indexOf(']', end + 1);
                        if (closeBr > end + 1) {
                            String idxTxt = expr.substring(end + 1, closeBr).trim();
                            mask = switch (idxTxt) {
                                case "0" -> CH_R;
                                case "1" -> CH_G;
                                case "2" -> CH_B;
                                case "3" -> CH_A;
                                default -> defaultMask;
                            };
                        }
                    } else if (isInsideCallArgs(expr, i)) {
                        mask = CH_RGBA;
                    }
                    outDemands.add(new VarDemand(resolveSymbolKey(scope, ident), mask));
                }
                i = end;
            } else {
                i++;
            }
        }
    }

    private static boolean isInsideCallArgs(String expr, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth > 0) depth--;
                else {
                    int p = i - 1;
                    while (p >= 0 && Character.isWhitespace(expr.charAt(p))) p--;
                    return p >= 0 && isGlslIdentPart(expr.charAt(p));
                }
            }
        }
        return false;
    }

    private static int findTopLevelAssignmentOp(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') {
                if (depth > 0) depth--;
            } else if (c == '=' && depth == 0) {
                char prev = i > 0 ? s.charAt(i - 1) : '\0';
                char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
                if (prev != '=' && prev != '!' && prev != '<' && prev != '>' && next != '=') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> extractImageStoreDataArgs(String source, String imageName) {
        List<String> args = new ArrayList<>();
        Pattern p = Pattern.compile("\\bimageStore\\s*\\(\\s*" + Pattern.quote(imageName) + "\\s*,");
        Matcher m = p.matcher(source);
        while (m.find()) {
            int openParen = source.indexOf('(', m.start());
            int closeParen = findClosingParen(source, openParen);
            if (closeParen > m.end()) {
                String rest = source.substring(m.end(), closeParen);
                int depth = 0;
                int secondComma = -1;
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c == '(' || c == '[') depth++;
                    else if (c == ')' || c == ']') depth--;
                    else if (c == ',' && depth == 0) {
                        secondComma = i;
                        break;
                    }
                }
                if (secondComma >= 0 && secondComma + 1 < rest.length()) {
                    args.add(rest.substring(secondComma + 1).trim());
                }
            }
        }
        return args;
    }

    private static boolean hasEncodingTransform(String expr) {
        if (expr == null || expr.isEmpty()) {
            return false;
        }
        return GAMMA_TO_LINEAR_FN.matcher(expr).find()
                || LINEAR_TO_SRGB_FN.matcher(expr).find()
                || LINEAR_TO_GAMMA_FN.matcher(expr).find()
                || TONEMAP_CALL.matcher(expr).find()
                || !scanPowCalls(expr).isEmpty();
    }

    private static boolean hasDivergentBranchEncoding(String trueExpr, String falseExpr) {
        if (!hasEncodingTransform(trueExpr) && !hasEncodingTransform(falseExpr)) {
            return false;
        }
        return advanceEncoding(ColourEncoding.LINEAR, trueExpr) != advanceEncoding(ColourEncoding.LINEAR, falseExpr)
                || advanceEncoding(ColourEncoding.SRGB, trueExpr) != advanceEncoding(ColourEncoding.SRGB, falseExpr)
                || advanceEncoding(ColourEncoding.GAMMA_2_2, trueExpr) != advanceEncoding(ColourEncoding.GAMMA_2_2, falseExpr);
    }

    private static ColourEncoding advanceEncoding(ColourEncoding current, String executableSource) {
        if (current == ColourEncoding.UNKNOWN || executableSource.isEmpty()) {
            return current;
        }
        if (executableSource.contains(AMBIGUOUS_ENCODING_MARKER)) {
            return ColourEncoding.UNKNOWN;
        }
        int lastDecodeEnd = lastCallEnd(GAMMA_TO_LINEAR_FN, executableSource);
        int lastSrgbEncodeEnd = lastCallEnd(LINEAR_TO_SRGB_FN, executableSource);
        int lastGammaEncodeEnd = lastCallEnd(LINEAR_TO_GAMMA_FN, executableSource);
        for (PowMatch pow : scanPowCalls(executableSource)) {
            if (pow.isEncodeInv22()) {
                lastGammaEncodeEnd = Math.max(lastGammaEncodeEnd, pow.closeParen() + 1);
            } else if (pow.isDecode22()) {
                lastDecodeEnd = Math.max(lastDecodeEnd, pow.closeParen() + 1);
            }
        }
        int lastEncodeEnd = Math.max(lastSrgbEncodeEnd, lastGammaEncodeEnd);
        if (lastDecodeEnd < 0 && lastEncodeEnd < 0) {
            return current;
        }
        if (lastDecodeEnd > lastEncodeEnd) {
            return ColourEncoding.LINEAR;
        }
        return lastSrgbEncodeEnd >= lastGammaEncodeEnd ? ColourEncoding.SRGB : ColourEncoding.GAMMA_2_2;
    }

    private static List<PowMatch> scanPowCalls(String cleaned) {
        List<PowMatch> matches = new java.util.ArrayList<>();
        Matcher pow = POW_CALL.matcher(cleaned);
        while (pow.find()) {
            int openParen = pow.end() - 1;
            int depth = 1;
            int comma = -1;
            int closeParen = -1;
            for (int i = openParen + 1; i < cleaned.length() && depth > 0; i++) {
                char c = cleaned.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        closeParen = i;
                    }
                } else if (c == ',' && depth == 1 && comma < 0) {
                    comma = i;
                }
            }
            if (comma >= 0 && closeParen > comma) {
                String exponent = cleaned.substring(comma + 1, closeParen);
                boolean isInv22 = EXPONENT_INV_2_2.matcher(exponent).find();
                boolean is22 = !isInv22 && EXPONENT_2_2.matcher(exponent).find();
                if (isInv22 || is22) {
                    matches.add(new PowMatch(openParen + 1, comma, closeParen, is22, isInv22));
                }
            }
        }
        return matches;
    }

    private static int findClosingParen(String source, int openParen) {
        int depth = 1;
        for (int i = openParen + 1; i < source.length() && depth > 0; i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int lastCallEnd(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        int lastEnd = -1;
        while (matcher.find()) {
            int close = findClosingParen(input, matcher.end() - 1);
            lastEnd = Math.max(lastEnd, close >= 0 ? close + 1 : matcher.end());
        }
        return lastEnd;
    }
}
