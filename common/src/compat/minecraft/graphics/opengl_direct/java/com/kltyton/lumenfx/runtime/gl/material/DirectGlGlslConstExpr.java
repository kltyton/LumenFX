package com.kltyton.lumenfx.runtime.gl.material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Recursive-descent evaluator for GLSL preprocessor {@code #if}/{@code #elif} and
 * {@code layout(location = <expr>)} constant expressions.
 *
 * <p>Supports driver-matched 32-bit signed vs 64-bit signed preprocessor integer arithmetic
 * ({@link #evaluatePreprocessorExpr(String, boolean)}) as well as GLSL 3.30 /
 * {@code GL_ARB_enhanced_layouts} constant expressions including 32-bit signed/unsigned integers,
 * booleans, floats/doubles, scalar/vector/array constructors, member swizzles ({@code .x/.y/.z/.w},
 * {@code .r/.g/.b/.a}, {@code .s/.t/.p/.q}, {@code .length()}), indexing ({@code [i]}), and
 * standard constant built-in functions ({@code min}, {@code max}, {@code clamp}, {@code abs},
 * {@code sign}, {@code floor}, {@code ceil}, {@code trunc}, {@code round}, {@code mix}, etc.).
 */
final class DirectGlGlslConstExpr {
    enum Kind { INT, UINT, BOOL, FLOAT, VEC }

    static final class ConstVal {
        final Kind kind;
        final long intBits;
        final double floatVal;
        final @Nullable Kind elemKind;
        final @Nullable ConstVal[] elems;

        private ConstVal(Kind kind, long intBits, double floatVal,
                         @Nullable Kind elemKind, @Nullable ConstVal[] elems) {
            this.kind = kind;
            this.intBits = intBits;
            this.floatVal = floatVal;
            this.elemKind = elemKind;
            this.elems = elems;
        }

        static ConstVal ofSigned32(int v) {
            return new ConstVal(Kind.INT, v, v, null, null);
        }

        static ConstVal ofUnsigned32(long v) {
            long u = v & 0xFFFF_FFFFL;
            return new ConstVal(Kind.UINT, u, (double) u, null, null);
        }

        static ConstVal ofSigned64(long v) {
            return new ConstVal(Kind.INT, v, (double) v, null, null);
        }

        static ConstVal ofBool(boolean v) {
            return new ConstVal(Kind.BOOL, v ? 1L : 0L, v ? 1.0 : 0.0, null, null);
        }

        static ConstVal ofFloat(double v) {
            return new ConstVal(Kind.FLOAT, (long) v, v, null, null);
        }

        static ConstVal ofVector(Kind elemKind, ConstVal[] elems) {
            return new ConstVal(Kind.VEC, 0L, 0.0, elemKind, elems);
        }

        long value() {
            return kind == Kind.FLOAT ? (long) floatVal : intBits;
        }

        double asDouble() {
            return switch (kind) {
                case FLOAT -> floatVal;
                case UINT -> (double) (intBits & 0xFFFF_FFFFL);
                case BOOL -> intBits != 0L ? 1.0 : 0.0;
                default -> (double) intBits;
            };
        }

        boolean unsigned() {
            return kind == Kind.UINT;
        }

        boolean isFloat() {
            return kind == Kind.FLOAT;
        }

        boolean isVector() {
            return kind == Kind.VEC && elems != null;
        }

        int length() {
            return elems != null ? elems.length : 1;
        }

        ConstVal elem(int index) {
            return elems != null ? elems[index] : this;
        }

        boolean isTruthy() {
            return switch (kind) {
                case FLOAT -> floatVal != 0.0;
                case VEC -> elems != null && elems.length > 0 && elems[0].isTruthy();
                default -> intBits != 0L;
            };
        }

        ConstVal castScalar(Kind target) {
            if (isVector()) {
                return elems.length > 0 ? elems[0].castScalar(target) : ofSigned32(0).castScalar(target);
            }
            return switch (target) {
                case INT -> ofSigned32(kind == Kind.FLOAT ? (int) floatVal : (int) intBits);
                case UINT -> ofUnsigned32(kind == Kind.FLOAT ? (long) floatVal : intBits);
                case BOOL -> ofBool(isTruthy());
                case FLOAT -> ofFloat(asDouble());
                default -> this;
            };
        }
    }

    private final String src;
    private final int len;
    private final Function<String, ConstVal> resolver;
    private final boolean preprocessorMode;
    private final boolean preprocessor32Bit;
    private int pos;
    private boolean failed;

    private DirectGlGlslConstExpr(String src, Function<String, ConstVal> resolver,
                                  boolean preprocessorMode, boolean preprocessor32Bit) {
        this.src = src;
        this.len = src.length();
        this.resolver = resolver;
        this.preprocessorMode = preprocessorMode;
        this.preprocessor32Bit = preprocessor32Bit;
    }

    /** Evaluates a GLSL {@code #if}/{@code #elif} expression using 64-bit signed integer arithmetic. */
    static long evaluatePreprocessorExpr(String expr) {
        return evaluatePreprocessorExpr(expr, false);
    }

    /**
     * Evaluates a GLSL {@code #if}/{@code #elif} expression where unknown identifiers evaluate to
     * {@code 0}, using either 32-bit signed ({@code int32_t}, matching NVIDIA Cg) or 64-bit signed
     * ({@code intmax_t}, matching Mesa {@code glcpp}) integer arithmetic.
     */
    static long evaluatePreprocessorExpr(String expr, boolean use32Bit) {
        DirectGlGlslConstExpr parser = new DirectGlGlslConstExpr(
                expr, name -> ConstVal.ofSigned64(0L), true, use32Bit);
        ConstVal val = parser.parseTernary(true);
        return parser.failed ? 0L : val.intBits;
    }

    /**
     * Evaluates a strict GLSL constant expression (scalar, vector, or 1D array) for global
     * {@code const} initializers and {@code layout(location = <expr>)}.
     */
    static @Nullable ConstVal evaluateConstExpr(String expr, Map<String, ConstVal> constVars) {
        DirectGlGlslConstExpr parser = new DirectGlGlslConstExpr(expr, constVars::get, false, false);
        ConstVal val = parser.parseTernary(true);
        parser.skipWs();
        if (parser.failed || parser.pos != parser.len) {
            return null;
        }
        return val;
    }

    /**
     * Evaluates a strict integer constant expression for {@code layout(location = <expr>)}.
     * Returns {@code null} if the expression does not resolve to an {@code int}/{@code uint} scalar
     * or contains unresolved identifiers / invalid syntax.
     */
    static @Nullable Integer evaluateLocationExpr(String expr, Map<String, ConstVal> constVars) {
        ConstVal val = evaluateConstExpr(expr, constVars);
        if (val == null || (val.kind != Kind.INT && val.kind != Kind.UINT)) {
            return null;
        }
        long v = val.unsigned() ? (val.intBits & 0xFFFF_FFFFL) : (int) val.intBits;
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            return null;
        }
        return (int) v;
    }

    private ConstVal wrapPreproc(long v) {
        return preprocessor32Bit ? ConstVal.ofSigned32((int) v) : ConstVal.ofSigned64(v);
    }

    private ConstVal wrapShaderInt(long v, boolean unsigned) {
        return unsigned ? ConstVal.ofUnsigned32(v) : ConstVal.ofSigned32((int) v);
    }

    private ConstVal parseTernary(boolean eval) {
        ConstVal cond = parseLogicalOr(eval);
        if (match("?")) {
            ConstVal whenTrue = parseTernary(eval && cond.isTruthy());
            if (!match(":")) {
                failed = true;
                return ConstVal.ofSigned64(0L);
            }
            ConstVal whenFalse = parseTernary(eval && !cond.isTruthy());
            return cond.isTruthy() ? whenTrue : whenFalse;
        }
        return cond;
    }

    private ConstVal parseLogicalOr(boolean eval) {
        ConstVal left = parseLogicalXor(eval);
        while (match("||")) {
            ConstVal right = parseLogicalXor(eval && !left.isTruthy());
            left = preprocessorMode
                    ? ConstVal.ofSigned32((left.isTruthy() || right.isTruthy()) ? 1 : 0)
                    : ConstVal.ofBool(left.isTruthy() || right.isTruthy());
        }
        return left;
    }

    private ConstVal parseLogicalXor(boolean eval) {
        ConstVal left = parseLogicalAnd(eval);
        while (!preprocessorMode && match("^^")) {
            ConstVal right = parseLogicalAnd(eval);
            left = ConstVal.ofBool(left.isTruthy() ^ right.isTruthy());
        }
        return left;
    }

    private ConstVal parseLogicalAnd(boolean eval) {
        ConstVal left = parseBitOr(eval);
        while (match("&&")) {
            ConstVal right = parseBitOr(eval && left.isTruthy());
            left = preprocessorMode
                    ? ConstVal.ofSigned32((left.isTruthy() && right.isTruthy()) ? 1 : 0)
                    : ConstVal.ofBool(left.isTruthy() && right.isTruthy());
        }
        return left;
    }

    private ConstVal parseBitOr(boolean eval) {
        ConstVal left = parseBitXor(eval);
        while (matchSingle('|', '|')) {
            ConstVal right = parseBitXor(eval);
            left = applyBinary(left, right, '|', eval);
        }
        return left;
    }

    private ConstVal parseBitXor(boolean eval) {
        ConstVal left = parseBitAnd(eval);
        while (matchSingle('^', '^')) {
            ConstVal right = parseBitAnd(eval);
            left = applyBinary(left, right, '^', eval);
        }
        return left;
    }

    private ConstVal parseBitAnd(boolean eval) {
        ConstVal left = parseEquality(eval);
        while (matchSingle('&', '&')) {
            ConstVal right = parseEquality(eval);
            left = applyBinary(left, right, '&', eval);
        }
        return left;
    }

    private ConstVal parseEquality(boolean eval) {
        ConstVal left = parseRelational(eval);
        while (true) {
            if (match("==")) {
                ConstVal right = parseRelational(eval);
                boolean eq = valuesEqual(left, right);
                left = preprocessorMode ? ConstVal.ofSigned32(eq ? 1 : 0) : ConstVal.ofBool(eq);
            } else if (match("!=")) {
                ConstVal right = parseRelational(eval);
                boolean ne = !valuesEqual(left, right);
                left = preprocessorMode ? ConstVal.ofSigned32(ne ? 1 : 0) : ConstVal.ofBool(ne);
            } else {
                return left;
            }
        }
    }

    private ConstVal parseRelational(boolean eval) {
        ConstVal left = parseShift(eval);
        while (true) {
            if (match("<=")) {
                ConstVal right = parseShift(eval);
                boolean r = compareScalars(left, right) <= 0;
                left = preprocessorMode ? ConstVal.ofSigned32(r ? 1 : 0) : ConstVal.ofBool(r);
            } else if (match(">=")) {
                ConstVal right = parseShift(eval);
                boolean r = compareScalars(left, right) >= 0;
                left = preprocessorMode ? ConstVal.ofSigned32(r ? 1 : 0) : ConstVal.ofBool(r);
            } else if (matchSingle('<', '<')) {
                ConstVal right = parseShift(eval);
                boolean r = compareScalars(left, right) < 0;
                left = preprocessorMode ? ConstVal.ofSigned32(r ? 1 : 0) : ConstVal.ofBool(r);
            } else if (matchSingle('>', '>')) {
                ConstVal right = parseShift(eval);
                boolean r = compareScalars(left, right) > 0;
                left = preprocessorMode ? ConstVal.ofSigned32(r ? 1 : 0) : ConstVal.ofBool(r);
            } else {
                return left;
            }
        }
    }

    private boolean valuesEqual(ConstVal a, ConstVal b) {
        if (a.isVector() || b.isVector()) {
            if (!a.isVector() || !b.isVector() || a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) {
                if (!valuesEqual(a.elem(i), b.elem(i))) return false;
            }
            return true;
        }
        return compareScalars(a, b) == 0;
    }

    private int compareScalars(ConstVal a, ConstVal b) {
        if (preprocessorMode) {
            if (preprocessor32Bit) {
                return Integer.compare((int) a.intBits, (int) b.intBits);
            }
            return Long.compare(a.intBits, b.intBits);
        }
        if (a.isFloat() || b.isFloat()) {
            return Double.compare(a.asDouble(), b.asDouble());
        }
        if (a.unsigned() || b.unsigned()) {
            return Long.compare(a.intBits & 0xFFFF_FFFFL, b.intBits & 0xFFFF_FFFFL);
        }
        return Integer.compare((int) a.intBits, (int) b.intBits);
    }

    private ConstVal parseShift(boolean eval) {
        ConstVal left = parseAdditive(eval);
        while (true) {
            if (match("<<")) {
                ConstVal right = parseAdditive(eval);
                left = applyBinary(left, right, 'L', eval);
            } else if (match(">>")) {
                ConstVal right = parseAdditive(eval);
                left = applyBinary(left, right, 'R', eval);
            } else {
                return left;
            }
        }
    }

    private ConstVal parseAdditive(boolean eval) {
        ConstVal left = parseMultiplicative(eval);
        while (true) {
            if (match("+")) {
                ConstVal right = parseMultiplicative(eval);
                left = applyBinary(left, right, '+', eval);
            } else if (match("-")) {
                ConstVal right = parseMultiplicative(eval);
                left = applyBinary(left, right, '-', eval);
            } else {
                return left;
            }
        }
    }

    private ConstVal parseMultiplicative(boolean eval) {
        ConstVal left = parseUnary(eval);
        while (true) {
            if (match("*")) {
                ConstVal right = parseUnary(eval);
                left = applyBinary(left, right, '*', eval);
            } else if (match("/")) {
                ConstVal right = parseUnary(eval);
                left = applyBinary(left, right, '/', eval);
            } else if (match("%")) {
                ConstVal right = parseUnary(eval);
                left = applyBinary(left, right, '%', eval);
            } else {
                return left;
            }
        }
    }

    private ConstVal applyBinary(ConstVal a, ConstVal b, char op, boolean eval) {
        if (!preprocessorMode && (a.isVector() || b.isVector())) {
            int n = a.isVector() ? a.length() : b.length();
            if (a.isVector() && b.isVector() && a.length() != b.length()) {
                failed = true;
                return ConstVal.ofSigned32(0);
            }
            ConstVal[] res = new ConstVal[n];
            for (int i = 0; i < n; i++) {
                res[i] = applyScalarBinary(a.isVector() ? a.elem(i) : a, b.isVector() ? b.elem(i) : b, op, eval);
            }
            return ConstVal.ofVector(res[0].kind, res);
        }
        return applyScalarBinary(a, b, op, eval);
    }

    private ConstVal applyScalarBinary(ConstVal left, ConstVal right, char op, boolean eval) {
        if (preprocessorMode) {
            return switch (op) {
                case '|' -> wrapPreproc(left.intBits | right.intBits);
                case '^' -> wrapPreproc(left.intBits ^ right.intBits);
                case '&' -> wrapPreproc(left.intBits & right.intBits);
                case 'L' -> preprocessor32Bit
                        ? ConstVal.ofSigned32((int) left.intBits << (int) right.intBits)
                        : ConstVal.ofSigned64(left.intBits << right.intBits);
                case 'R' -> preprocessor32Bit
                        ? ConstVal.ofSigned32((int) left.intBits >> (int) right.intBits)
                        : ConstVal.ofSigned64(left.intBits >> right.intBits);
                case '+' -> wrapPreproc(left.intBits + right.intBits);
                case '-' -> wrapPreproc(left.intBits - right.intBits);
                case '*' -> wrapPreproc(left.intBits * right.intBits);
                case '/' -> {
                    if (!eval) yield left;
                    if (right.intBits == 0L) {
                        failed = true;
                        yield ConstVal.ofSigned64(0L);
                    }
                    if (preprocessor32Bit) {
                        int l = (int) left.intBits, r = (int) right.intBits;
                        yield ConstVal.ofSigned32((l == Integer.MIN_VALUE && r == -1) ? Integer.MIN_VALUE : (l / r));
                    }
                    long l = left.intBits, r = right.intBits;
                    yield ConstVal.ofSigned64((l == Long.MIN_VALUE && r == -1L) ? Long.MIN_VALUE : (l / r));
                }
                case '%' -> {
                    if (!eval) yield left;
                    if (right.intBits == 0L) {
                        failed = true;
                        yield ConstVal.ofSigned64(0L);
                    }
                    if (preprocessor32Bit) {
                        int l = (int) left.intBits, r = (int) right.intBits;
                        yield ConstVal.ofSigned32((l == Integer.MIN_VALUE && r == -1) ? 0 : (l % r));
                    }
                    long l = left.intBits, r = right.intBits;
                    yield ConstVal.ofSigned64((l == Long.MIN_VALUE && r == -1L) ? 0L : (l % r));
                }
                default -> left;
            };
        }

        if (left.isFloat() || right.isFloat()) {
            double l = left.asDouble(), r = right.asDouble();
            return switch (op) {
                case '+' -> ConstVal.ofFloat(l + r);
                case '-' -> ConstVal.ofFloat(l - r);
                case '*' -> ConstVal.ofFloat(l * r);
                case '/' -> {
                    if (eval && r == 0.0) {
                        failed = true;
                        yield ConstVal.ofFloat(0.0);
                    }
                    yield ConstVal.ofFloat(eval ? l / r : 0.0);
                }
                default -> {
                    failed = true;
                    yield ConstVal.ofFloat(0.0);
                }
            };
        }

        boolean unsigned = left.unsigned() || right.unsigned();
        return switch (op) {
            case '|' -> wrapShaderInt(left.intBits | right.intBits, unsigned);
            case '^' -> wrapShaderInt(left.intBits ^ right.intBits, unsigned);
            case '&' -> wrapShaderInt(left.intBits & right.intBits, unsigned);
            case 'L' -> wrapShaderInt(((int) left.intBits) << ((int) right.intBits & 31), left.unsigned());
            case 'R' -> {
                int shift = (int) right.intBits & 31;
                if (left.unsigned()) {
                    yield ConstVal.ofUnsigned32((left.intBits & 0xFFFF_FFFFL) >>> shift);
                }
                yield ConstVal.ofSigned32(((int) left.intBits) >> shift);
            }
            case '+' -> wrapShaderInt(left.intBits + right.intBits, unsigned);
            case '-' -> wrapShaderInt(left.intBits - right.intBits, unsigned);
            case '*' -> wrapShaderInt(left.intBits * right.intBits, unsigned);
            case '/' -> {
                if (!eval) yield left;
                if (right.intBits == 0L) {
                    failed = true;
                    yield ConstVal.ofSigned32(0);
                }
                if (unsigned) {
                    yield ConstVal.ofUnsigned32((left.intBits & 0xFFFF_FFFFL) / (right.intBits & 0xFFFF_FFFFL));
                }
                int l = (int) left.intBits, r = (int) right.intBits;
                yield ConstVal.ofSigned32((l == Integer.MIN_VALUE && r == -1) ? Integer.MIN_VALUE : (l / r));
            }
            case '%' -> {
                if (!eval) yield left;
                if (right.intBits == 0L) {
                    failed = true;
                    yield ConstVal.ofSigned32(0);
                }
                if (unsigned) {
                    yield ConstVal.ofUnsigned32((left.intBits & 0xFFFF_FFFFL) % (right.intBits & 0xFFFF_FFFFL));
                }
                int l = (int) left.intBits, r = (int) right.intBits;
                yield ConstVal.ofSigned32((l == Integer.MIN_VALUE && r == -1) ? 0 : (l % r));
            }
            default -> left;
        };
    }

    private ConstVal parseUnary(boolean eval) {
        skipWs();
        if (match("+")) return parseUnary(eval);
        if (match("-")) {
            ConstVal v = parseUnary(eval);
            if (!preprocessorMode && v.isVector()) {
                ConstVal[] out = new ConstVal[v.length()];
                for (int i = 0; i < out.length; i++) out[i] = negateScalar(v.elem(i));
                return ConstVal.ofVector(v.elemKind, out);
            }
            return negateScalar(v);
        }
        if (match("!")) {
            ConstVal v = parseUnary(eval);
            return preprocessorMode
                    ? ConstVal.ofSigned32(v.isTruthy() ? 0 : 1)
                    : ConstVal.ofBool(!v.isTruthy());
        }
        if (match("~")) {
            ConstVal v = parseUnary(eval);
            if (!preprocessorMode && v.isVector()) {
                ConstVal[] out = new ConstVal[v.length()];
                for (int i = 0; i < out.length; i++) out[i] = wrapShaderInt(~v.elem(i).intBits, v.elem(i).unsigned());
                return ConstVal.ofVector(v.elemKind, out);
            }
            return preprocessorMode ? wrapPreproc(~v.intBits) : wrapShaderInt(~v.intBits, v.unsigned());
        }
        ConstVal primary = parsePrimary(eval);
        return preprocessorMode ? primary : parsePostfix(primary, eval);
    }

    private ConstVal negateScalar(ConstVal v) {
        if (preprocessorMode) return wrapPreproc(-v.intBits);
        if (v.isFloat()) return ConstVal.ofFloat(-v.floatVal);
        return wrapShaderInt(-v.intBits, v.unsigned());
    }

    private ConstVal parsePostfix(ConstVal base, boolean eval) {
        ConstVal cur = base;
        while (true) {
            skipWs();
            if (pos >= len) return cur;
            char c = src.charAt(pos);
            if (c == '.') {
                pos++;
                skipWs();
                if (pos >= len || !isIdentStart(src.charAt(pos))) {
                    failed = true;
                    return cur;
                }
                int start = pos++;
                while (pos < len && isIdentPart(src.charAt(pos))) pos++;
                String member = src.substring(start, pos);
                if ("length".equals(member)) {
                    int saved = pos;
                    skipWs();
                    if (pos < len && src.charAt(pos) == '(') {
                        pos++;
                        skipWs();
                        if (pos < len && src.charAt(pos) == ')') {
                            pos++;
                            cur = ConstVal.ofSigned32(cur.length());
                            continue;
                        }
                        failed = true;
                        return cur;
                    }
                    pos = saved;
                }
                cur = applySwizzle(cur, member);
            } else if (c == '[') {
                pos++;
                ConstVal idxVal = parseTernary(eval);
                skipWs();
                if (pos < len && src.charAt(pos) == ']') {
                    pos++;
                } else {
                    failed = true;
                    return cur;
                }
                if (eval) {
                    int idx = (int) idxVal.intBits;
                    if (!cur.isVector() || idx < 0 || idx >= cur.length()) {
                        failed = true;
                        return ConstVal.ofSigned32(0);
                    }
                    cur = cur.elem(idx);
                }
            } else {
                return cur;
            }
        }
    }

    private ConstVal applySwizzle(ConstVal base, String swizzle) {
        if (!base.isVector() || swizzle.isEmpty() || swizzle.length() > 4) {
            failed = true;
            return ConstVal.ofSigned32(0);
        }
        int set = -1;
        ConstVal[] selected = new ConstVal[swizzle.length()];
        for (int i = 0; i < swizzle.length(); i++) {
            char ch = swizzle.charAt(i);
            int curSet;
            int idx;
            switch (ch) {
                case 'x' -> { curSet = 0; idx = 0; }
                case 'y' -> { curSet = 0; idx = 1; }
                case 'z' -> { curSet = 0; idx = 2; }
                case 'w' -> { curSet = 0; idx = 3; }
                case 'r' -> { curSet = 1; idx = 0; }
                case 'g' -> { curSet = 1; idx = 1; }
                case 'b' -> { curSet = 1; idx = 2; }
                case 'a' -> { curSet = 1; idx = 3; }
                case 's' -> { curSet = 2; idx = 0; }
                case 't' -> { curSet = 2; idx = 1; }
                case 'p' -> { curSet = 2; idx = 2; }
                case 'q' -> { curSet = 2; idx = 3; }
                default -> {
                    failed = true;
                    return ConstVal.ofSigned32(0);
                }
            }
            if (set < 0) set = curSet;
            else if (set != curSet) {
                failed = true;
                return ConstVal.ofSigned32(0);
            }
            if (idx >= base.length()) {
                failed = true;
                return ConstVal.ofSigned32(0);
            }
            selected[i] = base.elem(idx);
        }
        return selected.length == 1 ? selected[0] : ConstVal.ofVector(base.elemKind, selected);
    }

    private ConstVal parsePrimary(boolean eval) {
        skipWs();
        if (pos >= len) {
            failed = true;
            return ConstVal.ofSigned64(0L);
        }
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            ConstVal inner = parseTernary(eval);
            skipWs();
            if (pos < len && src.charAt(pos) == ')') {
                pos++;
            } else {
                failed = true;
            }
            return inner;
        }
        if (!preprocessorMode && c == '.' && pos + 1 < len && Character.isDigit(src.charAt(pos + 1))) {
            return parseFloatLiteral();
        }
        if (Character.isDigit(c)) {
            if (!preprocessorMode && looksLikeFloatLiteral()) {
                return parseFloatLiteral();
            }
            return parseIntLiteral();
        }
        if (isIdentStart(c)) {
            int start = pos++;
            while (pos < len && isIdentPart(src.charAt(pos))) pos++;
            String ident = src.substring(start, pos);
            if (!preprocessorMode) {
                ConstVal arrayCtor = tryParseArrayConstructor(ident, eval);
                if (arrayCtor != null) {
                    return arrayCtor;
                }
                int saved = pos;
                skipWs();
                if (pos < len && src.charAt(pos) == '(') {
                    pos++;
                    List<ConstVal> args = parseCallArgs(eval);
                    return evaluateCall(ident, args, eval);
                }
                pos = saved;
            }
            if ("true".equals(ident)) return preprocessorMode ? ConstVal.ofSigned32(1) : ConstVal.ofBool(true);
            if ("false".equals(ident)) return preprocessorMode ? ConstVal.ofSigned32(0) : ConstVal.ofBool(false);
            ConstVal resolved = resolver.apply(ident);
            if (resolved == null) {
                if (eval) failed = true;
                return ConstVal.ofSigned64(0L);
            }
            return resolved;
        }
        failed = true;
        return ConstVal.ofSigned64(0L);
    }

    private @Nullable ConstVal tryParseArrayConstructor(String typeName, boolean eval) {
        Kind elemKind = scalarTypeKind(typeName);
        if (elemKind == null) return null;
        int saved = pos;
        skipWs();
        if (pos >= len || src.charAt(pos) != '[') {
            pos = saved;
            return null;
        }
        pos++;
        skipWs();
        Integer declaredLen = null;
        if (pos < len && src.charAt(pos) != ']') {
            ConstVal lenVal = parseTernary(eval);
            declaredLen = (int) lenVal.intBits;
            skipWs();
        }
        if (pos >= len || src.charAt(pos) != ']') {
            pos = saved;
            return null;
        }
        pos++;
        skipWs();
        if (pos >= len || src.charAt(pos) != '(') {
            pos = saved;
            return null;
        }
        pos++;
        List<ConstVal> args = parseCallArgs(eval);
        if (declaredLen != null && args.size() != declaredLen) {
            failed = true;
            return ConstVal.ofSigned32(0);
        }
        ConstVal[] elems = new ConstVal[args.size()];
        for (int i = 0; i < args.size(); i++) {
            elems[i] = args.get(i).castScalar(elemKind);
        }
        return ConstVal.ofVector(elemKind, elems);
    }

    private List<ConstVal> parseCallArgs(boolean eval) {
        List<ConstVal> args = new ArrayList<>();
        skipWs();
        if (pos < len && src.charAt(pos) == ')') {
            pos++;
            return args;
        }
        while (true) {
            args.add(parseTernary(eval));
            skipWs();
            if (pos < len && src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            if (pos < len && src.charAt(pos) == ')') {
                pos++;
            } else {
                failed = true;
            }
            return args;
        }
    }

    private ConstVal evaluateCall(String name, List<ConstVal> args, boolean eval) {
        Kind scalarKind = scalarTypeKind(name);
        if (scalarKind != null) {
            if (args.size() != 1) {
                failed = true;
                return ConstVal.ofSigned32(0);
            }
            return args.get(0).castScalar(scalarKind);
        }
        int vecDim = vectorTypeDim(name);
        if (vecDim > 0) {
            Kind elemKind = vectorElemKind(name);
            List<ConstVal> flat = new ArrayList<>();
            for (ConstVal a : args) {
                if (a.isVector()) {
                    for (int i = 0; i < a.length(); i++) flat.add(a.elem(i));
                } else {
                    flat.add(a);
                }
            }
            if (flat.isEmpty()) {
                failed = true;
                return ConstVal.ofSigned32(0);
            }
            ConstVal[] out = new ConstVal[vecDim];
            if (flat.size() == 1) {
                ConstVal splat = flat.get(0).castScalar(elemKind);
                for (int i = 0; i < vecDim; i++) out[i] = splat;
            } else if (flat.size() >= vecDim) {
                for (int i = 0; i < vecDim; i++) out[i] = flat.get(i).castScalar(elemKind);
            } else {
                failed = true;
                return ConstVal.ofSigned32(0);
            }
            return ConstVal.ofVector(elemKind, out);
        }
        return evaluateBuiltinFunction(name, args, eval);
    }

    private ConstVal evaluateBuiltinFunction(String name, List<ConstVal> args, boolean eval) {
        if (!eval) {
            return ConstVal.ofSigned32(0);
        }
        return switch (name) {
            case "min" -> applyBinaryFunc(args, (a, b) -> compareScalars(a, b) <= 0 ? a : b);
            case "max" -> applyBinaryFunc(args, (a, b) -> compareScalars(a, b) >= 0 ? a : b);
            case "clamp" -> {
                if (args.size() != 3) { failed = true; yield ConstVal.ofSigned32(0); }
                ConstVal x = args.get(0), lo = args.get(1), hi = args.get(2);
                yield applyTernaryFunc(x, lo, hi, (xv, lv, hv) -> {
                    ConstVal m = compareScalars(xv, lv) >= 0 ? xv : lv;
                    return compareScalars(m, hv) <= 0 ? m : hv;
                });
            }
            case "abs" -> applyUnaryFunc(args, v -> {
                if (v.isFloat()) return ConstVal.ofFloat(Math.abs(v.floatVal));
                if (v.unsigned()) return v;
                int iv = (int) v.intBits;
                return ConstVal.ofSigned32(iv == Integer.MIN_VALUE ? Integer.MIN_VALUE : Math.abs(iv));
            });
            case "sign" -> applyUnaryFunc(args, v -> {
                if (v.isFloat()) return ConstVal.ofFloat(Math.signum(v.floatVal));
                int iv = (int) v.intBits;
                return ConstVal.ofSigned32(Integer.compare(iv, 0));
            });
            case "floor" -> applyUnaryFunc(args, v -> ConstVal.ofFloat(Math.floor(v.asDouble())));
            case "ceil" -> applyUnaryFunc(args, v -> ConstVal.ofFloat(Math.ceil(v.asDouble())));
            case "trunc" -> applyUnaryFunc(args, v -> {
                double d = v.asDouble();
                return ConstVal.ofFloat(d < 0.0 ? Math.ceil(d) : Math.floor(d));
            });
            case "round", "roundEven" -> applyUnaryFunc(args, v -> ConstVal.ofFloat(Math.rint(v.asDouble())));
            case "fract" -> applyUnaryFunc(args, v -> {
                double d = v.asDouble();
                return ConstVal.ofFloat(d - Math.floor(d));
            });
            case "mod" -> applyBinaryFunc(args, (a, b) -> {
                double x = a.asDouble(), y = b.asDouble();
                if (y == 0.0) { failed = true; return ConstVal.ofFloat(0.0); }
                return ConstVal.ofFloat(x - y * Math.floor(x / y));
            });
            case "step" -> applyBinaryFunc(args, (edge, x) ->
                    ConstVal.ofFloat(x.asDouble() < edge.asDouble() ? 0.0 : 1.0));
            case "mix" -> {
                if (args.size() != 3) { failed = true; yield ConstVal.ofSigned32(0); }
                yield applyTernaryFunc(args.get(0), args.get(1), args.get(2), (x, y, a) -> {
                    if (a.kind == Kind.BOOL) return a.isTruthy() ? y : x;
                    double t = a.asDouble();
                    return ConstVal.ofFloat(x.asDouble() * (1.0 - t) + y.asDouble() * t);
                });
            }
            case "any" -> {
                if (args.size() != 1 || !args.get(0).isVector()) { failed = true; yield ConstVal.ofBool(false); }
                boolean r = false;
                for (int i = 0; i < args.get(0).length(); i++) r |= args.get(0).elem(i).isTruthy();
                yield ConstVal.ofBool(r);
            }
            case "all" -> {
                if (args.size() != 1 || !args.get(0).isVector()) { failed = true; yield ConstVal.ofBool(false); }
                boolean r = true;
                for (int i = 0; i < args.get(0).length(); i++) r &= args.get(0).elem(i).isTruthy();
                yield ConstVal.ofBool(r);
            }
            case "not" -> applyUnaryFunc(args, v -> ConstVal.ofBool(!v.isTruthy()));
            case "equal" -> applyVecCompare(args, (a, b) -> compareScalars(a, b) == 0);
            case "notEqual" -> applyVecCompare(args, (a, b) -> compareScalars(a, b) != 0);
            case "lessThan" -> applyVecCompare(args, (a, b) -> compareScalars(a, b) < 0);
            case "lessThanEqual" -> applyVecCompare(args, (a, b) -> compareScalars(a, b) <= 0);
            case "greaterThan" -> applyVecCompare(args, (a, b) -> compareScalars(a, b) > 0);
            case "greaterThanEqual" -> applyVecCompare(args, (a, b) -> compareScalars(a, b) >= 0);
            case "dot" -> {
                if (args.size() != 2 || args.get(0).length() != args.get(1).length()) {
                    failed = true;
                    yield ConstVal.ofFloat(0.0);
                }
                double sum = 0.0;
                for (int i = 0; i < args.get(0).length(); i++) {
                    sum += args.get(0).elem(i).asDouble() * args.get(1).elem(i).asDouble();
                }
                yield ConstVal.ofFloat(sum);
            }
            case "bitCount" -> applyUnaryFunc(args, v -> ConstVal.ofSigned32(Integer.bitCount((int) v.intBits)));
            case "findLSB" -> applyUnaryFunc(args, v -> {
                int iv = (int) v.intBits;
                return ConstVal.ofSigned32(iv == 0 ? -1 : Integer.numberOfTrailingZeros(iv));
            });
            case "findMSB" -> applyUnaryFunc(args, v -> {
                int iv = (int) v.intBits;
                if (v.unsigned()) {
                    return ConstVal.ofSigned32(iv == 0 ? -1 : (31 - Integer.numberOfLeadingZeros(iv)));
                }
                if (iv == 0 || iv == -1) return ConstVal.ofSigned32(-1);
                int target = iv < 0 ? ~iv : iv;
                return ConstVal.ofSigned32(31 - Integer.numberOfLeadingZeros(target));
            });
            case "bitfieldReverse" -> applyUnaryFunc(args, v ->
                    wrapShaderInt(Integer.reverse((int) v.intBits), v.unsigned()));
            default -> {
                failed = true;
                yield ConstVal.ofSigned32(0);
            }
        };
    }

    @FunctionalInterface
    private interface UnaryOp { ConstVal apply(ConstVal a); }

    @FunctionalInterface
    private interface BinaryOp { ConstVal apply(ConstVal a, ConstVal b); }

    @FunctionalInterface
    private interface TernaryOp { ConstVal apply(ConstVal a, ConstVal b, ConstVal c); }

    @FunctionalInterface
    private interface ScalarPred { boolean test(ConstVal a, ConstVal b); }

    private ConstVal applyUnaryFunc(List<ConstVal> args, UnaryOp fn) {
        if (args.size() != 1) {
            failed = true;
            return ConstVal.ofSigned32(0);
        }
        ConstVal a = args.get(0);
        if (a.isVector()) {
            ConstVal[] out = new ConstVal[a.length()];
            for (int i = 0; i < out.length; i++) out[i] = fn.apply(a.elem(i));
            return ConstVal.ofVector(out[0].kind, out);
        }
        return fn.apply(a);
    }

    private ConstVal applyBinaryFunc(List<ConstVal> args, BinaryOp fn) {
        if (args.size() != 2) {
            failed = true;
            return ConstVal.ofSigned32(0);
        }
        ConstVal a = args.get(0), b = args.get(1);
        if (a.isVector() || b.isVector()) {
            int n = a.isVector() ? a.length() : b.length();
            if (a.isVector() && b.isVector() && a.length() != b.length()) {
                failed = true;
                return ConstVal.ofSigned32(0);
            }
            ConstVal[] out = new ConstVal[n];
            for (int i = 0; i < n; i++) {
                out[i] = fn.apply(a.isVector() ? a.elem(i) : a, b.isVector() ? b.elem(i) : b);
            }
            return ConstVal.ofVector(out[0].kind, out);
        }
        return fn.apply(a, b);
    }

    private ConstVal applyTernaryFunc(ConstVal a, ConstVal b, ConstVal c, TernaryOp fn) {
        if (a.isVector() || b.isVector() || c.isVector()) {
            int n = a.isVector() ? a.length() : (b.isVector() ? b.length() : c.length());
            if ((a.isVector() && a.length() != n) || (b.isVector() && b.length() != n) || (c.isVector() && c.length() != n)) {
                failed = true;
                return ConstVal.ofSigned32(0);
            }
            ConstVal[] out = new ConstVal[n];
            for (int i = 0; i < n; i++) {
                out[i] = fn.apply(
                        a.isVector() ? a.elem(i) : a,
                        b.isVector() ? b.elem(i) : b,
                        c.isVector() ? c.elem(i) : c);
            }
            return ConstVal.ofVector(out[0].kind, out);
        }
        return fn.apply(a, b, c);
    }

    private ConstVal applyVecCompare(List<ConstVal> args, ScalarPred pred) {
        if (args.size() != 2 || !args.get(0).isVector() || !args.get(1).isVector()
                || args.get(0).length() != args.get(1).length()) {
            failed = true;
            return ConstVal.ofBool(false);
        }
        int n = args.get(0).length();
        ConstVal[] out = new ConstVal[n];
        for (int i = 0; i < n; i++) {
            out[i] = ConstVal.ofBool(pred.test(args.get(0).elem(i), args.get(1).elem(i)));
        }
        return ConstVal.ofVector(Kind.BOOL, out);
    }

    static @Nullable Kind scalarTypeKind(String tok) {
        return switch (tok) {
            case "int" -> Kind.INT;
            case "uint" -> Kind.UINT;
            case "bool" -> Kind.BOOL;
            case "float", "double" -> Kind.FLOAT;
            default -> null;
        };
    }

    static int vectorTypeDim(String tok) {
        return switch (tok) {
            case "ivec2", "uvec2", "bvec2", "vec2", "dvec2" -> 2;
            case "ivec3", "uvec3", "bvec3", "vec3", "dvec3" -> 3;
            case "ivec4", "uvec4", "bvec4", "vec4", "dvec4" -> 4;
            default -> 0;
        };
    }

    static @Nullable Kind vectorElemKind(String tok) {
        if (tok.startsWith("ivec")) return Kind.INT;
        if (tok.startsWith("uvec")) return Kind.UINT;
        if (tok.startsWith("bvec")) return Kind.BOOL;
        if (tok.startsWith("vec") || tok.startsWith("dvec")) return Kind.FLOAT;
        return null;
    }

    private boolean looksLikeFloatLiteral() {
        int p = pos;
        if (src.charAt(p) == '0' && p + 1 < len && (src.charAt(p + 1) == 'x' || src.charAt(p + 1) == 'X')) {
            return false;
        }
        while (p < len && Character.isDigit(src.charAt(p))) p++;
        if (p >= len) return false;
        char ch = src.charAt(p);
        return ch == '.' || ch == 'e' || ch == 'E' || ch == 'f' || ch == 'F';
    }

    private ConstVal parseFloatLiteral() {
        int start = pos;
        while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
        if (pos < len && src.charAt(pos) == '.') {
            pos++;
            while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
        }
        if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
        }
        int end = pos;
        if (pos < len && (src.charAt(pos) == 'f' || src.charAt(pos) == 'F')) {
            pos++;
        } else if (pos + 1 < len && (src.charAt(pos) == 'l' || src.charAt(pos) == 'L')
                && (src.charAt(pos + 1) == 'f' || src.charAt(pos + 1) == 'F')) {
            pos += 2;
        }
        try {
            return ConstVal.ofFloat(Double.parseDouble(src.substring(start, end)));
        } catch (NumberFormatException ignored) {
            failed = true;
            return ConstVal.ofFloat(0.0);
        }
    }

    private ConstVal parseIntLiteral() {
        int start = pos;
        int radix = 10;
        if (src.charAt(pos) == '0' && pos + 1 < len) {
            char next = src.charAt(pos + 1);
            if (next == 'x' || next == 'X') {
                radix = 16;
                pos += 2;
                start = pos;
                while (pos < len && isHexDigit(src.charAt(pos))) pos++;
            } else if (next >= '0' && next <= '7') {
                radix = 8;
                pos++;
                start = pos;
                while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '7') pos++;
            } else {
                pos++;
            }
        } else {
            while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
        }
        int end = pos;
        boolean unsignedSuffix = false;
        if (pos < len && (src.charAt(pos) == 'u' || src.charAt(pos) == 'U')) {
            unsignedSuffix = true;
            pos++;
        }
        if (end == start) {
            failed = true;
            return ConstVal.ofSigned64(0L);
        }
        try {
            long parsed = Long.parseUnsignedLong(src.substring(start, end), radix);
            if (preprocessorMode) {
                return wrapPreproc(parsed);
            }
            return unsignedSuffix ? ConstVal.ofUnsigned32(parsed) : ConstVal.ofSigned32((int) parsed);
        } catch (NumberFormatException ignored) {
            failed = true;
            return ConstVal.ofSigned64(0L);
        }
    }

    private void skipWs() {
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f') pos++;
            else break;
        }
    }

    private boolean match(String op) {
        skipWs();
        if (src.startsWith(op, pos)) {
            pos += op.length();
            return true;
        }
        return false;
    }

    private boolean matchSingle(char ch, char disallowNext) {
        skipWs();
        if (pos < len && src.charAt(pos) == ch) {
            if (pos + 1 < len && src.charAt(pos + 1) == disallowNext) return false;
            if (pos + 1 < len && src.charAt(pos + 1) == '=' && (ch == '<' || ch == '>')) return false;
            pos++;
            return true;
        }
        return false;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
