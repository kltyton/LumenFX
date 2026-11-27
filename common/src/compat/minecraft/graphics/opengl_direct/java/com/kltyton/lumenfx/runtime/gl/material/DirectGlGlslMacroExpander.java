package com.kltyton.lumenfx.runtime.gl.material;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * C99 / GLSL 3.30 Section 3.3 compliant token-stream macro expander using Dave Prosser's
 * per-token hide-set algorithm with per-token logical line tracking.
 *
 * <p>Supports:
 * <ul>
 *   <li>Rescanning object-like and function-like replacements across subsequent input tokens
 *       (e.g. {@code #define ID(x) x; #define UV_BIND ID; UV_BIND(6)}).</li>
 *   <li>Exact {@code ##} token pasting (raw unexpanded arguments adjacent to {@code ##},
 *       prescanned arguments otherwise, placemarker handling for empty arguments, chained
 *       {@code ##}, and {@code ##} in object-like macros) followed by rescanning.</li>
 *   <li>Per-token hide sets preventing direct and mutual infinite macro recursion while
 *       still allowing nested calls such as {@code INC(INC(5))}.</li>
 *   <li>Per-token line tracking so {@code __LINE__} inside multi-line macro arguments evaluates to
 *       each argument token's own logical line while {@code __LINE__} in a macro body evaluates to
 *       the closing {@code )} line of a function-like call (matching Mesa {@code glcpp}).</li>
 *   <li>Token-safe rendering preventing accidental token merging.</li>
 * </ul>
 */
final class DirectGlGlslMacroExpander {
    private static final int MAX_EXPANSION_STEPS = 65_536;

    enum Kind {
        IDENT,
        NUMBER,
        PUNCT,
        WS,
        NEWLINE,
        PLACEMARKER
    }

    record Token(Kind kind, String text, Set<String> hideSet, int line) {
        Token withHideSet(Set<String> next) {
            return next.equals(hideSet) ? this : new Token(kind, text, next, line);
        }

        Token withLine(int nextLine) {
            return nextLine == line ? this : new Token(kind, text, hideSet, nextLine);
        }
    }

    record MacroDef(String name, @Nullable List<String> params, List<Token> body) { }

    private DirectGlGlslMacroExpander() { }

    static MacroDef defineObject(String name, String rawBody) {
        return new MacroDef(name, null, trimWsTokens(tokenize(rawBody, null, -1)));
    }

    static MacroDef defineFunction(String name, List<String> params, String rawBody) {
        return new MacroDef(name, List.copyOf(params), trimWsTokens(tokenize(rawBody, null, -1)));
    }

    static String expand(String input, Map<String, MacroDef> macros, int startLineNumber, int sourceFile) {
        return expand(input, null, macros, startLineNumber, sourceFile);
    }

    static String expand(String input, @Nullable int[] charLines, Map<String, MacroDef> macros,
                         int startLineNumber, int sourceFile) {
        if (input.isEmpty()) {
            return input;
        }
        if (macros.isEmpty() && !input.contains("__LINE__") && !input.contains("__FILE__")) {
            return input;
        }
        List<Token> initial = tokenize(input, charLines, startLineNumber);
        List<Token> expanded = expandTokens(
                new ArrayDeque<>(initial), macros, startLineNumber, sourceFile, new int[]{0});
        return renderTokens(expanded);
    }

    static List<Token> tokenize(String s, @Nullable int[] charLines, int startLineNumber) {
        int len = s.length();
        List<Token> out = new ArrayList<>();
        int curLine = startLineNumber;
        int i = 0;
        while (i < len) {
            int tokLine = (charLines != null && i < charLines.length) ? charLines[i] : curLine;
            char c = s.charAt(i);
            if (c == '\n') {
                out.add(new Token(Kind.NEWLINE, "\n", Set.of(), tokLine));
                if (curLine > 0) {
                    curLine++;
                }
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\f' || c == '\u000B') {
                int start = i++;
                while (i < len) {
                    char w = s.charAt(i);
                    if (w == ' ' || w == '\t' || w == '\f' || w == '\u000B') i++;
                    else break;
                }
                out.add(new Token(Kind.WS, s.substring(start, i), Set.of(), tokLine));
                continue;
            }
            if (isIdentStart(c)) {
                int start = i++;
                while (i < len && isIdentPart(s.charAt(i))) i++;
                out.add(new Token(Kind.IDENT, s.substring(start, i), Set.of(), tokLine));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(s.charAt(i + 1)))) {
                int start = i++;
                while (i < len) {
                    char n = s.charAt(i);
                    if ((n == 'e' || n == 'E' || n == 'p' || n == 'P')
                            && i + 1 < len && (s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-')) {
                        i += 2;
                    } else if (isIdentPart(n) || n == '.') {
                        i++;
                    } else {
                        break;
                    }
                }
                out.add(new Token(Kind.NUMBER, s.substring(start, i), Set.of(), tokLine));
                continue;
            }
            // Multi-character punctuators (3-char, then 2-char, then 1-char)
            if (i + 2 < len) {
                String p3 = s.substring(i, i + 3);
                if ("<<=".equals(p3) || ">>=".equals(p3)) {
                    out.add(new Token(Kind.PUNCT, p3, Set.of(), tokLine));
                    i += 3;
                    continue;
                }
            }
            if (i + 1 < len) {
                String p2 = s.substring(i, i + 2);
                if (isTwoCharPunct(p2)) {
                    out.add(new Token(Kind.PUNCT, p2, Set.of(), tokLine));
                    i += 2;
                    continue;
                }
            }
            out.add(new Token(Kind.PUNCT, String.valueOf(c), Set.of(), tokLine));
            i++;
        }
        return out;
    }

    private static List<Token> expandTokens(Deque<Token> input, Map<String, MacroDef> macros,
                                            int startLineNumber, int sourceFile, int[] steps) {
        List<Token> output = new ArrayList<>();
        int currentLine = startLineNumber;

        while (!input.isEmpty()) {
            Token tok = input.removeFirst();
            if (tok.kind == Kind.NEWLINE) {
                if (tok.line > 0) {
                    currentLine = tok.line + 1;
                } else if (currentLine > 0) {
                    currentLine++;
                }
                output.add(tok);
                continue;
            }
            int tokenLine = tok.line > 0 ? tok.line : currentLine;
            if (tok.kind != Kind.IDENT || steps[0] >= MAX_EXPANSION_STEPS) {
                if (tok.kind != Kind.PLACEMARKER) {
                    output.add(tok);
                }
                continue;
            }

            // Built-in dynamic GLSL predefined macros
            if (!macros.containsKey(tok.text)) {
                if ("__LINE__".equals(tok.text)) {
                    output.add(new Token(Kind.NUMBER, Integer.toString(tokenLine), tok.hideSet, tokenLine));
                    continue;
                }
                if ("__FILE__".equals(tok.text)) {
                    output.add(new Token(Kind.NUMBER, Integer.toString(sourceFile), tok.hideSet, tokenLine));
                    continue;
                }
                output.add(tok);
                continue;
            }

            if (tok.hideSet.contains(tok.text)) {
                output.add(tok);
                continue;
            }

            MacroDef def = macros.get(tok.text);
            if (def.params == null) {
                // Object-like macro: paste any ## inside body, stamp expansion-site line onto body
                // tokens, tag hideSet, and prepend to input for rescan.
                steps[0]++;
                List<Token> pasted = pasteTokens(def.body, tokenLine);
                Set<String> nextHide = union(tok.hideSet, def.name);
                prependWithHideSetAndLine(input, pasted, nextHide, tokenLine);
                continue;
            }

            // Function-like macro: check whether next non-whitespace token in input is '('
            List<Token> leadingWs = new ArrayList<>();
            while (!input.isEmpty() && isSpaceOrNewline(input.peekFirst())) {
                leadingWs.add(input.removeFirst());
            }
            if (input.isEmpty() || !isPunct(input.peekFirst(), "(")) {
                // Not invoked as a function-like macro; restore consumed whitespace and emit identifier verbatim.
                prependExact(input, leadingWs);
                output.add(tok);
                continue;
            }

            // Parse macro call arguments up to matching ')'
            Token openParen = input.removeFirst();
            List<Token> consumed = new ArrayList<>(leadingWs.size() + 16);
            consumed.addAll(leadingWs);
            consumed.add(openParen);

            List<List<Token>> rawArgs = new ArrayList<>();
            List<Token> curArg = new ArrayList<>();
            int parenDepth = 1;
            Token closeParen = null;
            int newlinesInArgs = 0;

            while (!input.isEmpty()) {
                Token t = input.removeFirst();
                consumed.add(t);
                if (t.kind == Kind.NEWLINE) {
                    newlinesInArgs++;
                }
                if (isPunct(t, "(")) {
                    parenDepth++;
                    curArg.add(t);
                } else if (isPunct(t, ")")) {
                    parenDepth--;
                    if (parenDepth == 0) {
                        closeParen = t;
                        rawArgs.add(normalizeArgTokens(curArg));
                        break;
                    }
                    curArg.add(t);
                } else if (isPunct(t, ",") && parenDepth == 1) {
                    rawArgs.add(normalizeArgTokens(curArg));
                    curArg = new ArrayList<>();
                } else {
                    curArg.add(t);
                }
            }

            if (closeParen == null) {
                prependExact(input, consumed);
                output.add(tok);
                continue;
            }

            if (def.params.isEmpty() && rawArgs.size() == 1 && rawArgs.get(0).isEmpty()) {
                rawArgs = List.of();
            }
            if (rawArgs.size() != def.params.size()) {
                prependExact(input, consumed);
                output.add(tok);
                continue;
            }

            steps[0]++;
            int callEndLine = closeParen.line > 0 ? closeParen.line : (tokenLine + newlinesInArgs);
            currentLine = callEndLine;
            Set<String> nextHide = union(intersect(tok.hideSet, closeParen.hideSet), def.name);
            List<Token> replaced = substituteAndPaste(def, rawArgs, macros, callEndLine, sourceFile, steps);
            prependWithHideSetAndLine(input, replaced, nextHide, callEndLine);
        }

        return output;
    }

    private static List<Token> substituteAndPaste(MacroDef def, List<List<Token>> rawArgs,
                                                  Map<String, MacroDef> macros, int callEndLine,
                                                  int sourceFile, int[] steps) {
        Map<String, Integer> paramIndex = new HashMap<>(def.params.size() * 2);
        for (int i = 0; i < def.params.size(); i++) {
            paramIndex.put(def.params.get(i), i);
        }

        List<Token> body = def.body;
        int n = body.size();
        // Lazily cache prescanned arguments for parameters NOT adjacent to ##.
        @SuppressWarnings("unchecked")
        List<Token>[] expandedCache = new List[def.params.size()];
        List<Token> substituted = new ArrayList<>(n + 8);

        for (int i = 0; i < n; i++) {
            Token t = body.get(i);
            if (t.kind == Kind.IDENT) {
                Integer pIdx = paramIndex.get(t.text);
                if (pIdx != null) {
                    boolean adjacentPaste = isPrevNonWsPaste(body, i) || isNextNonWsPaste(body, i);
                    if (adjacentPaste) {
                        List<Token> raw = rawArgs.get(pIdx);
                        if (raw.isEmpty()) {
                            substituted.add(new Token(Kind.PLACEMARKER, "", Set.of(), callEndLine));
                        } else {
                            substituted.addAll(raw);
                        }
                    } else {
                        List<Token> exp = expandedCache[pIdx];
                        if (exp == null) {
                            List<Token> raw = rawArgs.get(pIdx);
                            int argFallbackLine = firstPositiveLine(raw, callEndLine);
                            exp = raw.isEmpty()
                                    ? List.of()
                                    : expandTokens(new ArrayDeque<>(raw), macros, argFallbackLine, sourceFile, steps);
                            expandedCache[pIdx] = exp;
                        }
                        if (!exp.isEmpty()) {
                            substituted.addAll(exp);
                        }
                    }
                    continue;
                }
            }
            // Macro-body tokens inherit callEndLine (the closing ')' line of the invocation).
            substituted.add(t.line > 0 ? t : t.withLine(callEndLine));
        }

        return pasteTokens(substituted, callEndLine);
    }

    private static int firstPositiveLine(List<Token> tokens, int fallback) {
        for (Token t : tokens) {
            if (t.line > 0) {
                return t.line;
            }
        }
        return fallback;
    }

    private static List<Token> pasteTokens(List<Token> tokens, int pasteLine) {
        boolean hasPaste = false;
        for (Token t : tokens) {
            if (isPunct(t, "##")) {
                hasPaste = true;
                break;
            }
        }
        if (!hasPaste) {
            return stripPlacemarkers(tokens);
        }

        List<Token> out = new ArrayList<>(tokens.size());
        int i = 0;
        int n = tokens.size();
        while (i < n) {
            Token t = tokens.get(i);
            if (isPunct(t, "##")) {
                // Trim trailing whitespace from out to reach left operand
                while (!out.isEmpty() && isSpaceOrNewline(out.get(out.size() - 1))) {
                    out.remove(out.size() - 1);
                }
                // Advance past whitespace after ## to reach right operand
                int r = i + 1;
                while (r < n && isSpaceOrNewline(tokens.get(r))) {
                    r++;
                }
                if (out.isEmpty() || r >= n) {
                    // Stray leading/trailing ##: drop operator gracefully
                    i = r;
                    continue;
                }
                Token left = out.remove(out.size() - 1);
                Token right = tokens.get(r);
                pastePair(left, right, pasteLine, out);
                i = r + 1;
            } else {
                out.add(t);
                i++;
            }
        }
        return stripPlacemarkers(out);
    }

    private static void pastePair(Token left, Token right, int pasteLine, List<Token> out) {
        if (left.kind == Kind.PLACEMARKER && right.kind == Kind.PLACEMARKER) {
            out.add(left);
            return;
        }
        if (left.kind == Kind.PLACEMARKER) {
            out.add(right);
            return;
        }
        if (right.kind == Kind.PLACEMARKER) {
            out.add(left);
            return;
        }
        String combined = left.text + right.text;
        Set<String> mergedHide = intersect(left.hideSet, right.hideSet);
        int effectiveLine = pasteLine > 0 ? pasteLine : (left.line > 0 ? left.line : right.line);
        List<Token> relexed = tokenize(combined, null, effectiveLine);
        for (Token rt : relexed) {
            out.add(rt.withHideSet(mergedHide));
        }
    }

    private static List<Token> stripPlacemarkers(List<Token> tokens) {
        List<Token> clean = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            if (t.kind != Kind.PLACEMARKER) {
                clean.add(t);
            }
        }
        return clean;
    }

    private static boolean isPrevNonWsPaste(List<Token> body, int idx) {
        for (int i = idx - 1; i >= 0; i--) {
            Token t = body.get(i);
            if (isSpaceOrNewline(t)) continue;
            return isPunct(t, "##");
        }
        return false;
    }

    private static boolean isNextNonWsPaste(List<Token> body, int idx) {
        for (int i = idx + 1; i < body.size(); i++) {
            Token t = body.get(i);
            if (isSpaceOrNewline(t)) continue;
            return isPunct(t, "##");
        }
        return false;
    }

    private static List<Token> normalizeArgTokens(List<Token> raw) {
        List<Token> trimmed = trimWsTokens(raw);
        if (trimmed.isEmpty()) return List.of();
        List<Token> out = new ArrayList<>(trimmed.size());
        for (Token t : trimmed) {
            if (t.kind == Kind.NEWLINE) {
                out.add(new Token(Kind.WS, " ", Set.of(), t.line));
            } else {
                out.add(t);
            }
        }
        return out;
    }

    private static List<Token> trimWsTokens(List<Token> tokens) {
        int start = 0;
        int end = tokens.size();
        while (start < end && isSpaceOrNewline(tokens.get(start))) start++;
        while (end > start && isSpaceOrNewline(tokens.get(end - 1))) end--;
        if (start == 0 && end == tokens.size()) return tokens;
        return new ArrayList<>(tokens.subList(start, end));
    }

    private static void prependWithHideSetAndLine(Deque<Token> input, List<Token> tokens,
                                                  Set<String> extraHide, int fallbackLine) {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.kind == Kind.PLACEMARKER) continue;
            Set<String> merged = extraHide.isEmpty() ? t.hideSet
                    : (t.hideSet.isEmpty() ? extraHide : unionSets(t.hideSet, extraHide));
            int line = t.line > 0 ? t.line : fallbackLine;
            Token updated = t.withHideSet(merged).withLine(line);
            input.addFirst(updated);
        }
    }

    private static void prependExact(Deque<Token> input, List<Token> tokens) {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            input.addFirst(tokens.get(i));
        }
    }

    static String renderTokens(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        Token lastNonWs = null;
        boolean separatedByWs = true;

        for (Token tok : tokens) {
            if (tok.kind == Kind.PLACEMARKER) continue;
            if (tok.kind == Kind.WS || tok.kind == Kind.NEWLINE) {
                sb.append(tok.text);
                separatedByWs = true;
                continue;
            }
            if (!separatedByWs && lastNonWs != null && needsSpaceBetween(lastNonWs, tok)) {
                sb.append(' ');
            }
            sb.append(tok.text);
            lastNonWs = tok;
            separatedByWs = false;
        }
        return sb.toString();
    }

    private static boolean needsSpaceBetween(Token a, Token b) {
        if ((a.kind == Kind.IDENT || a.kind == Kind.NUMBER)
                && (b.kind == Kind.IDENT || b.kind == Kind.NUMBER)) {
            return true;
        }
        if (a.kind == Kind.NUMBER && isPunct(b, ".")) return true;
        if (isPunct(a, ".") && b.kind == Kind.NUMBER) return true;
        if (a.kind == Kind.PUNCT && b.kind == Kind.PUNCT) {
            String joined = a.text + b.text;
            if (joined.startsWith("//") || joined.startsWith("/*")) return true;
            if (isTwoCharPunct(joined) || "<<=".equals(joined) || ">>=".equals(joined)) return true;
            char ac = a.text.charAt(a.text.length() - 1);
            char bc = b.text.charAt(0);
            if ((ac == '+' && bc == '+') || (ac == '-' && bc == '-')
                    || (ac == '<' && bc == '<') || (ac == '>' && bc == '>')
                    || (ac == '&' && bc == '&') || (ac == '|' && bc == '|')
                    || (ac == '^' && bc == '^') || (ac == '#' && bc == '#')
                    || (bc == '=' && (ac == '=' || ac == '!' || ac == '<' || ac == '>'
                    || ac == '+' || ac == '-' || ac == '*' || ac == '/' || ac == '%'
                    || ac == '&' || ac == '|' || ac == '^'))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpaceOrNewline(Token t) {
        return t.kind == Kind.WS || t.kind == Kind.NEWLINE;
    }

    private static boolean isPunct(Token t, String text) {
        return t.kind == Kind.PUNCT && text.equals(t.text);
    }

    private static Set<String> union(Set<String> set, String extra) {
        if (set.contains(extra)) return set;
        if (set.isEmpty()) return Set.of(extra);
        Set<String> next = new HashSet<>(set.size() + 1);
        next.addAll(set);
        next.add(extra);
        return Collections.unmodifiableSet(next);
    }

    private static Set<String> unionSets(Set<String> a, Set<String> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        if (a.containsAll(b)) return a;
        if (b.containsAll(a)) return b;
        Set<String> next = new HashSet<>(a.size() + b.size());
        next.addAll(a);
        next.addAll(b);
        return Collections.unmodifiableSet(next);
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String s : a) {
            if (b.contains(s)) out.add(s);
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }

    private static boolean isTwoCharPunct(String s) {
        return switch (s) {
            case "##", "<=", ">=", "==", "!=", "&&", "||", "^^", "<<", ">>",
                 "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=" -> true;
            default -> false;
        };
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }
}
