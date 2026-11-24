package com.kltyton.lumenfx.client.resource.shader.glsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.ShaderDefines;

/**
 * Evaluates GLSL preprocessor conditionals ({@code #if}, {@code #ifdef}, {@code #ifndef}, {@code #elif},
 * {@code #else}, {@code #endif}, {@code #define}, {@code #undef}) against active {@link ShaderDefines}
 * so shader adapters inspect the exact compiled variant (Issue 33).
 */
final class GlslConditionalEvaluator {
    private static final int MAX_MACRO_EXPANSION_DEPTH = 16;

    private static final class Frame {
        final boolean parentActive;
        boolean branchTaken;
        boolean currentActive;
        boolean sawElse;

        Frame(boolean parentActive, boolean taken) {
            this.parentActive = parentActive;
            this.branchTaken = taken;
            this.currentActive = taken;
        }
    }

    private GlslConditionalEvaluator() { }

    static List<GlslToken> filter(List<GlslToken> tokens, ShaderDefines defines) {
        Map<String, String> macros = new HashMap<>();
        Set<String> functionMacros = new HashSet<>();
        macros.put("GL_core_profile", "1");
        macros.put("GL_ES", "0");
        if (defines != null) {
            for (String flag : defines.flags()) {
                macros.put(flag, "");
            }
            for (var entry : defines.values().entrySet()) {
                macros.put(entry.getKey(), entry.getValue());
            }
        }

        List<GlslToken> activeTokens = new ArrayList<>(tokens.size());
        Deque<Frame> stack = new ArrayDeque<>();

        for (GlslToken token : tokens) {
            boolean active = stack.isEmpty() || stack.peek().currentActive;
            if (!token.preprocessor()) {
                if (active) {
                    activeTokens.add(token);
                }
                continue;
            }

            String cleaned = stripCommentsAndContinuations(token.text());
            int cursor = skipDirectiveHashAndWhitespace(cleaned);
            int nameStart = cursor;
            while (cursor < cleaned.length() && isIdentPart(cleaned.charAt(cursor))) {
                cursor++;
            }
            String directive = cleaned.substring(nameStart, cursor);
            String body = cleaned.substring(cursor).trim();

            switch (directive) {
                case "if" -> {
                    boolean taken = active && evaluateExpression(body, macros, functionMacros) != 0L;
                    stack.push(new Frame(active, taken));
                    if (active) activeTokens.add(token);
                }
                case "ifdef" -> {
                    String symbol = firstIdentifier(body);
                    boolean taken = active && !symbol.isEmpty() && macros.containsKey(symbol);
                    stack.push(new Frame(active, taken));
                    if (active) activeTokens.add(token);
                }
                case "ifndef" -> {
                    String symbol = firstIdentifier(body);
                    boolean taken = active && !symbol.isEmpty() && !macros.containsKey(symbol);
                    stack.push(new Frame(active, taken));
                    if (active) activeTokens.add(token);
                }
                case "elif" -> {
                    if (stack.isEmpty() || stack.peek().sawElse) {
                        throw new GlslSourceException("unexpected #elif in host shader source");
                    }
                    Frame frame = stack.peek();
                    boolean taken = frame.parentActive && !frame.branchTaken
                            && evaluateExpression(body, macros, functionMacros) != 0L;
                    frame.currentActive = taken;
                    frame.branchTaken |= taken;
                    if (frame.parentActive) activeTokens.add(token);
                }
                case "else" -> {
                    if (stack.isEmpty() || stack.peek().sawElse) {
                        throw new GlslSourceException("unexpected #else in host shader source");
                    }
                    Frame frame = stack.peek();
                    frame.currentActive = frame.parentActive && !frame.branchTaken;
                    frame.branchTaken = true;
                    frame.sawElse = true;
                    if (frame.parentActive) activeTokens.add(token);
                }
                case "endif" -> {
                    if (stack.isEmpty()) {
                        throw new GlslSourceException("unbalanced #endif in host shader source");
                    }
                    Frame frame = stack.pop();
                    if (frame.parentActive) activeTokens.add(token);
                }
                case "define" -> {
                    if (active) {
                        recordDefine(body, macros, functionMacros);
                        activeTokens.add(token);
                    }
                }
                case "undef" -> {
                    if (active) {
                        String symbol = firstIdentifier(body);
                        if (!symbol.isEmpty()) {
                            macros.remove(symbol);
                            functionMacros.remove(symbol);
                        }
                        activeTokens.add(token);
                    }
                }
                case "version" -> {
                    if (active) {
                        String version = firstInteger(body);
                        if (!version.isEmpty()) {
                            macros.put("__VERSION__", version);
                        }
                        activeTokens.add(token);
                    }
                }
                default -> {
                    if (active) {
                        activeTokens.add(token);
                    }
                }
            }
        }

        if (!stack.isEmpty()) {
            throw new GlslSourceException("unbalanced preprocessor conditionals in host shader source");
        }
        return activeTokens;
    }

    private static void recordDefine(String body, Map<String, String> macros, Set<String> functionMacros) {
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        int start = i;
        while (i < body.length() && isIdentPart(body.charAt(i))) i++;
        if (i == start) return;
        String name = body.substring(start, i);
        if (i < body.length() && body.charAt(i) == '(') {
            macros.put(name, "");
            functionMacros.add(name);
            return;
        }
        functionMacros.remove(name);
        macros.put(name, body.substring(i).trim());
    }

    private static String stripCommentsAndContinuations(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && (text.charAt(i + 1) == '\n' || text.charAt(i + 1) == '\r')) {
                i++;
                if (text.charAt(i) == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                i++;
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                break;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) i++;
                i = i + 1 < n ? i + 2 : n;
                out.append(' ');
                continue;
            }
            if (c == '\r' || c == '\n') {
                out.append(' ');
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int skipDirectiveHashAndWhitespace(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i < text.length() && text.charAt(i) == '#') i++;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    private static String firstIdentifier(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        int start = i;
        if (i < text.length() && isIdentStart(text.charAt(i))) {
            i++;
            while (i < text.length() && isIdentPart(text.charAt(i))) i++;
        }
        return text.substring(start, i);
    }

    private static String firstInteger(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        int start = i;
        while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
        return text.substring(start, i);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static long evaluateExpression(String expression, Map<String, String> macros, Set<String> functionMacros) {
        String resolvedDefined = resolveDefinedOperators(expression, macros);
        String expanded = expandMacros(resolvedDefined, macros, functionMacros, 0);
        ExprParser parser = new ExprParser(expanded);
        long value = parser.parseExpression();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new GlslSourceException("Unsupported preprocessor expression in host shader source: " + expression);
        }
        return value;
    }

    private static String resolveDefinedOperators(String text, Map<String, String> macros) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isIdentStart(c)) {
                int start = i++;
                while (i < n && isIdentPart(text.charAt(i))) i++;
                String ident = text.substring(start, i);
                if (ident.equals("defined")) {
                    int j = i;
                    while (j < n && Character.isWhitespace(text.charAt(j))) j++;
                    boolean paren = j < n && text.charAt(j) == '(';
                    if (paren) {
                        j++;
                        while (j < n && Character.isWhitespace(text.charAt(j))) j++;
                    }
                    int symStart = j;
                    if (j < n && isIdentStart(text.charAt(j))) {
                        j++;
                        while (j < n && isIdentPart(text.charAt(j))) j++;
                    }
                    String symbol = text.substring(symStart, j);
                    if (symbol.isEmpty()) {
                        throw new GlslSourceException("Malformed defined() operator in preprocessor expression: " + text);
                    }
                    if (paren) {
                        while (j < n && Character.isWhitespace(text.charAt(j))) j++;
                        if (j >= n || text.charAt(j) != ')') {
                            throw new GlslSourceException("Missing ')' in defined() preprocessor expression: " + text);
                        }
                        j++;
                    }
                    out.append(macros.containsKey(symbol) ? '1' : '0');
                    i = j;
                    continue;
                }
                out.append(ident);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String expandMacros(String text, Map<String, String> macros, Set<String> functionMacros, int depth) {
        if (depth >= MAX_MACRO_EXPANSION_DEPTH) return text;
        StringBuilder out = new StringBuilder(text.length());
        boolean changed = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isIdentStart(c)) {
                int start = i++;
                while (i < n && isIdentPart(text.charAt(i))) i++;
                String ident = text.substring(start, i);
                String replacement = macros.get(ident);
                if (replacement != null && !functionMacros.contains(ident)) {
                    out.append(replacement.isEmpty() ? "0" : replacement);
                    changed = true;
                } else {
                    out.append(ident);
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return changed ? expandMacros(out.toString(), macros, functionMacros, depth + 1) : out.toString();
    }

    private static final class ExprParser {
        private final String input;
        private int pos;

        ExprParser(String input) {
            this.input = input;
        }

        boolean eof() {
            return pos >= input.length();
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        long parseExpression() {
            return parseTernary();
        }

        private long parseTernary() {
            long cond = parseLogicalOr();
            skipWhitespace();
            if (match('?')) {
                long whenTrue = parseExpression();
                skipWhitespace();
                if (!match(':')) {
                    throw new GlslSourceException("Missing ':' in ternary preprocessor expression: " + input);
                }
                long whenFalse = parseTernary();
                return cond != 0L ? whenTrue : whenFalse;
            }
            return cond;
        }

        private long parseLogicalOr() {
            long value = parseLogicalAnd();
            while (true) {
                skipWhitespace();
                if (match("||")) {
                    long rhs = parseLogicalAnd();
                    value = (value != 0L || rhs != 0L) ? 1L : 0L;
                } else {
                    return value;
                }
            }
        }

        private long parseLogicalAnd() {
            long value = parseBitOr();
            while (true) {
                skipWhitespace();
                if (match("&&")) {
                    long rhs = parseBitOr();
                    value = (value != 0L && rhs != 0L) ? 1L : 0L;
                } else {
                    return value;
                }
            }
        }

        private long parseBitOr() {
            long value = parseBitXor();
            while (true) {
                skipWhitespace();
                if (peek('|') && !peek("||")) {
                    pos++;
                    value |= parseBitXor();
                } else {
                    return value;
                }
            }
        }

        private long parseBitXor() {
            long value = parseBitAnd();
            while (true) {
                skipWhitespace();
                if (match('^')) {
                    value ^= parseBitAnd();
                } else {
                    return value;
                }
            }
        }

        private long parseBitAnd() {
            long value = parseEquality();
            while (true) {
                skipWhitespace();
                if (peek('&') && !peek("&&")) {
                    pos++;
                    value &= parseEquality();
                } else {
                    return value;
                }
            }
        }

        private long parseEquality() {
            long value = parseRelational();
            while (true) {
                skipWhitespace();
                if (match("==")) {
                    value = value == parseRelational() ? 1L : 0L;
                } else if (match("!=")) {
                    value = value != parseRelational() ? 1L : 0L;
                } else {
                    return value;
                }
            }
        }

        private long parseRelational() {
            long value = parseShift();
            while (true) {
                skipWhitespace();
                if (match("<=")) {
                    value = value <= parseShift() ? 1L : 0L;
                } else if (match(">=")) {
                    value = value >= parseShift() ? 1L : 0L;
                } else if (peek('<') && !peek("<<")) {
                    pos++;
                    value = value < parseShift() ? 1L : 0L;
                } else if (peek('>') && !peek(">>")) {
                    pos++;
                    value = value > parseShift() ? 1L : 0L;
                } else {
                    return value;
                }
            }
        }

        private long parseShift() {
            long value = parseAdditive();
            while (true) {
                skipWhitespace();
                if (match("<<")) {
                    value <<= parseAdditive();
                } else if (match(">>")) {
                    value >>= parseAdditive();
                } else {
                    return value;
                }
            }
        }

        private long parseAdditive() {
            long value = parseMultiplicative();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    value += parseMultiplicative();
                } else if (match('-')) {
                    value -= parseMultiplicative();
                } else {
                    return value;
                }
            }
        }

        private long parseMultiplicative() {
            long value = parseUnary();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    value *= parseUnary();
                } else if (match('/')) {
                    long rhs = parseUnary();
                    value = rhs == 0L ? 0L : value / rhs;
                } else if (match('%')) {
                    long rhs = parseUnary();
                    value = rhs == 0L ? 0L : value % rhs;
                } else {
                    return value;
                }
            }
        }

        private long parseUnary() {
            skipWhitespace();
            if (match('+')) return parseUnary();
            if (match('-')) return -parseUnary();
            if (match('!')) return parseUnary() == 0L ? 1L : 0L;
            if (match('~')) return ~parseUnary();
            return parsePrimary();
        }

        private long parsePrimary() {
            skipWhitespace();
            if (match('(')) {
                long value = parseExpression();
                skipWhitespace();
                if (!match(')')) {
                    throw new GlslSourceException("Missing ')' in preprocessor expression: " + input);
                }
                return value;
            }
            if (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                int start = pos;
                if (input.charAt(pos) == '0' && pos + 1 < input.length()
                        && (input.charAt(pos + 1) == 'x' || input.charAt(pos + 1) == 'X')) {
                    pos += 2;
                    int hexStart = pos;
                    while (pos < input.length() && isHexDigit(input.charAt(pos))) pos++;
                    String digits = input.substring(hexStart, pos);
                    consumeIntSuffix();
                    return Long.parseUnsignedLong(digits, 16);
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
                String digits = input.substring(start, pos);
                consumeIntSuffix();
                if (digits.length() > 1 && digits.charAt(0) == '0') {
                    return Long.parseUnsignedLong(digits.substring(1), 8);
                }
                return Long.parseUnsignedLong(digits, 10);
            }
            if (pos < input.length() && isIdentStart(input.charAt(pos))) {
                pos++;
                while (pos < input.length() && isIdentPart(input.charAt(pos))) pos++;
                // Undefined identifiers in GLSL #if expressions evaluate to 0 (GLSL 4.60 section 3.3).
                return 0L;
            }
            throw new GlslSourceException("Unexpected token in preprocessor expression: " + input);
        }

        private void consumeIntSuffix() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == 'u' || c == 'U' || c == 'l' || c == 'L') pos++;
                else break;
            }
        }

        private static boolean isHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private boolean peek(char c) {
            return pos < input.length() && input.charAt(pos) == c;
        }

        private boolean peek(String s) {
            return input.startsWith(s, pos);
        }

        private boolean match(char c) {
            if (peek(c)) {
                pos++;
                return true;
            }
            return false;
        }

        private boolean match(String s) {
            if (peek(s)) {
                pos += s.length();
                return true;
            }
            return false;
        }
    }
}
