package com.kltyton.lumenfx.client.resource.shader.glsl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.ShaderDefines;
import org.jspecify.annotations.Nullable;

public final class GlslSourceScanner {
    private GlslSourceScanner() {
    }

    public static GlslHeader header(String source) {
        List<GlslToken> tokens = tokenize(source);
        int conditionalDepth = 0;
        GlslHeader header = null;
        for (GlslToken token : tokens) {
            if (!token.preprocessor()) {
                continue;
            }
            String directive = directiveName(token.text());
            if (directive.equals("moj_import")) {
                throw new GlslSourceException(
                        "host source still contains #moj_import; pass the preprocessed ShaderManager source string");
            }
            if (directive.equals("version") && conditionalDepth == 0 && header == null) {
                header = new GlslHeader(token.end());
            }
            if (directive.equals("if") || directive.equals("ifdef") || directive.equals("ifndef")) {
                conditionalDepth++;
            } else if (directive.equals("endif")) {
                conditionalDepth--;
            }
        }
        if (header == null) {
            throw new GlslSourceException("host shader source is missing a #version directive");
        }
        return extendHeader(tokens, header);
    }

    public static GlslSource scan(String source) {
        return scanInternal(source, null);
    }

    /**
     * Scans a shader source after evaluating preprocessor conditionals against the pipeline's effective
     * {@link ShaderDefines}, so mutually exclusive entry points, conditional outputs, and dead-branch
     * built-ins such as {@code gl_FragDepth} match the actual compiled variant (Issue 33).
     */
    public static GlslSource scan(String source, ShaderDefines defines) {
        return scanInternal(source, defines == null ? ShaderDefines.EMPTY : defines);
    }

    private static GlslSource scanInternal(String source, @Nullable ShaderDefines defines) {
        List<GlslToken> allTokens = tokenize(source);
        boolean evaluateConditionals = defines != null;
        List<GlslToken> tokens = evaluateConditionals
                ? GlslConditionalEvaluator.filter(allTokens, defines)
                : allTokens;
        List<GlslFunction> mains = new ArrayList<>();
        List<GlslOutputDeclaration> outputs = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();
        int conditionalDepth = 0;
        int braceDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;
        int statementStartToken = 0;
        GlslHeader header = null;

        for (int i = 0; i < tokens.size(); i++) {
            GlslToken token = tokens.get(i);
            if (token.preprocessor()) {
                String directive = directiveName(token.text());
                if (directive.equals("moj_import")) {
                    throw new GlslSourceException(
                            "host source still contains #moj_import; pass the preprocessed ShaderManager source string");
                }
                if (directive.equals("define")) {
                    String macro = directiveSymbol(token.text());
                    if (!macro.isEmpty()) {
                        identifiers.add(macro);
                    }
                }
                if (directive.equals("version") && conditionalDepth == 0 && header == null) {
                    header = new GlslHeader(token.end());
                }
                if (!evaluateConditionals) {
                    if (directive.equals("if") || directive.equals("ifdef") || directive.equals("ifndef")) {
                        conditionalDepth++;
                    } else if (directive.equals("endif")) {
                        conditionalDepth--;
                    }
                }
                if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                    statementStartToken = i + 1;
                }
                continue;
            }

            if (token.identifier()) {
                identifiers.add(token.text());
            }

            String text = token.text();
            if (text.equals("{")) {
                if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                    statementStartToken = i + 1;
                }
                braceDepth++;
            } else if (text.equals("}")) {
                braceDepth--;
                if (braceDepth < 0) {
                    throw new GlslSourceException("unbalanced '}' in host shader source");
                }
                if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                    statementStartToken = i + 1;
                }
            } else if (text.equals("(")) {
                parenDepth++;
            } else if (text.equals(")")) {
                parenDepth--;
                if (parenDepth < 0) {
                    throw new GlslSourceException("unbalanced ')' in host shader source");
                }
            } else if (text.equals("[")) {
                bracketDepth++;
            } else if (text.equals("]")) {
                bracketDepth--;
                if (bracketDepth < 0) {
                    throw new GlslSourceException("unbalanced ']' in host shader source");
                }
            } else if (text.equals(";")) {
                if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                    statementStartToken = i + 1;
                }
            }

            if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                if (token.identifier() && text.equals("out")) {
                    GlslOutputDeclaration output = parseOutput(tokens, i, statementStartToken, conditionalDepth);
                    if (output != null) {
                        outputs.add(output);
                    }
                } else if (token.identifier()
                        && text.equals("main")
                        && i > 0
                        && tokens.get(i - 1).identifier()
                        && tokens.get(i - 1).text().equals("void")) {
                    GlslFunction main = parseMain(tokens, i);
                    if (main != null) {
                        mains.add(main);
                    }
                }
            }
        }

        if (conditionalDepth != 0) {
            throw new GlslSourceException("unbalanced preprocessor conditionals in host shader source");
        }
        if (braceDepth != 0 || parenDepth != 0 || bracketDepth != 0) {
            throw new GlslSourceException("unbalanced delimiters in host shader source");
        }
        if (header == null) {
            throw new GlslSourceException("host shader source is missing a #version directive");
        }

        return new GlslSource(
                extendHeader(allTokens, header),
                List.copyOf(mains),
                List.copyOf(outputs),
                Set.copyOf(identifiers));
    }

    public static List<GlslFunctionDefinition> functions(String source, String name) {
        List<GlslToken> tokens = tokenize(source);
        List<GlslFunctionDefinition> result = new ArrayList<>();
        int braces = 0, parentheses = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslToken token = tokens.get(index);
            if (token.preprocessor()) continue;
            String value = token.text();
            if (braces == 0 && parentheses == 0 && token.identifier() && value.equals(name)
                    && index > 0 && tokens.get(index - 1).identifier()) {
                GlslFunctionDefinition definition = parseFunction(tokens, index);
                if (definition != null) result.add(definition);
            }
            if (value.equals("{")) braces++;
            else if (value.equals("}")) braces--;
            else if (value.equals("(")) parentheses++;
            else if (value.equals(")")) parentheses--;
        }
        return List.copyOf(result);
    }

    private static GlslFunctionDefinition parseFunction(List<GlslToken> tokens, int nameIndex) {
        int open = nameIndex + 1;
        if (open >= tokens.size() || !tokens.get(open).text().equals("(")) return null;
        int close = open + 1;
        while (close < tokens.size() && !tokens.get(close).text().equals(")")) {
            if (tokens.get(close).text().equals("(")) return null;
            close++;
        }
        if (close + 1 >= tokens.size() || !tokens.get(close + 1).text().equals("{")) return null;
        int bodyClose = matchingBrace(tokens, close + 1);
        if (bodyClose < 0) throw new GlslSourceException("Unbalanced function body: " + tokens.get(nameIndex).text());
        List<GlslFunctionDefinition.Parameter> parameters = new ArrayList<>();
        int first = open + 1;
        while (first < close) {
            int last = first;
            while (last < close && !tokens.get(last).text().equals(",")) last++;
            if (last == first + 1 && tokens.get(first).text().equals("void") && parameters.isEmpty() && last == close) break;
            int type = first;
            String direction = "in";
            while (type < last && tokens.get(type).identifier() && (isQualifier(tokens.get(type).text())
                    || tokens.get(type).text().equals("in") || tokens.get(type).text().equals("out")
                    || tokens.get(type).text().equals("inout"))) {
                String qualifier = tokens.get(type++).text();
                if (qualifier.equals("in") || qualifier.equals("out") || qualifier.equals("inout")) direction = qualifier;
            }
            if (type + 2 != last || !tokens.get(type).identifier() || !tokens.get(type + 1).identifier()) return null;
            parameters.add(new GlslFunctionDefinition.Parameter(tokens.get(type).text(), tokens.get(type + 1).text(), direction));
            first = last + 1;
        }
        return new GlslFunctionDefinition(tokens.get(nameIndex - 1).text(), tokens.get(nameIndex).text(), parameters,
                tokens.get(nameIndex - 1).start(), tokens.get(close + 1).end(), tokens.get(bodyClose).start(), tokens.get(bodyClose).end());
    }

    private static GlslHeader extendHeader(List<GlslToken> tokens, GlslHeader header) {
        int insertion = header.insertionIndex();
        boolean foundVersion = false;
        for (GlslToken token : tokens) {
            if (!foundVersion) {
                if (token.preprocessor() && directiveName(token.text()).equals("version")) {
                    foundVersion = true;
                }
                continue;
            }
            if (token.end() <= insertion) {
                continue;
            }
            if (token.preprocessor() && directiveName(token.text()).equals("extension")) {
                insertion = token.end();
            } else {
                break;
            }
        }
        return new GlslHeader(insertion);
    }

    private static GlslFunction parseMain(List<GlslToken> tokens, int mainIndex) {
        int i = mainIndex + 1;
        if (i >= tokens.size() || !tokens.get(i).text().equals("(")) {
            return null;
        }
        i++;
        if (i < tokens.size() && tokens.get(i).identifier() && tokens.get(i).text().equals("void")) {
            i++;
        }
        if (i >= tokens.size() || !tokens.get(i).text().equals(")")) {
            return null;
        }
        i++;
        if (i >= tokens.size() || !tokens.get(i).text().equals("{")) {
            return null;
        }
        int close = matchingBrace(tokens, i);
        if (close < 0) {
            return null;
        }
        return new GlslFunction(tokens.get(mainIndex - 1).start(), tokens.get(close).end());
    }

    private static GlslOutputDeclaration parseOutput(
            List<GlslToken> tokens, int outIndex, int startTokenIndex, int conditionalDepth) {
        int i = outIndex + 1;
        String precision = null;
        while (i < tokens.size() && tokens.get(i).identifier() && isQualifier(tokens.get(i).text())) {
            if (isPrecision(tokens.get(i).text())) {
                precision = tokens.get(i).text();
            }
            i++;
        }
        if (i >= tokens.size() || !tokens.get(i).identifier()) {
            return null;
        }
        String type = tokens.get(i).text();
        i++;

        boolean arrayType = false;
        if (i < tokens.size() && tokens.get(i).text().equals("[")) {
            arrayType = true;
            i = skipBrackets(tokens, i);
        }
        if (i >= tokens.size() || !tokens.get(i).identifier()) {
            return null;
        }
        String name = tokens.get(i).text();
        i++;

        boolean array = false;
        if (i < tokens.size() && tokens.get(i).text().equals("[")) {
            array = true;
            i = skipBrackets(tokens, i);
        }

        int endIndex = findSemicolon(tokens, i);
        if (endIndex < 0) {
            return null;
        }

        int declarators = 1;
        int parens = 0;
        int brackets = 0;
        for (int j = i; j < endIndex; j++) {
            String text = tokens.get(j).text();
            if (text.equals("(")) {
                parens++;
            } else if (text.equals(")")) {
                parens--;
            } else if (text.equals("[")) {
                brackets++;
            } else if (text.equals("]")) {
                brackets--;
            } else if (text.equals(",") && parens == 0 && brackets == 0) {
                declarators++;
            }
        }

        int start = startTokenIndex < tokens.size() ? tokens.get(startTokenIndex).start() : tokens.get(outIndex).start();
        return new GlslOutputDeclaration(
                name,
                type,
                start,
                tokens.get(endIndex).end(),
                conditionalDepth,
                array || arrayType,
                declarators,
                precision);
    }

    private static int findSemicolon(List<GlslToken> tokens, int start) {
        int parens = 0;
        int brackets = 0;
        for (int i = start; i < tokens.size(); i++) {
            String text = tokens.get(i).text();
            if (text.equals("(")) {
                parens++;
            } else if (text.equals(")")) {
                parens--;
            } else if (text.equals("[")) {
                brackets++;
            } else if (text.equals("]")) {
                brackets--;
            } else if (text.equals(";") && parens == 0 && brackets == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipBrackets(List<GlslToken> tokens, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            String text = tokens.get(i).text();
            if (text.equals("[")) {
                depth++;
            } else if (text.equals("]")) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return tokens.size();
    }

    private static int matchingBrace(List<GlslToken> tokens, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < tokens.size(); i++) {
            String text = tokens.get(i).text();
            if (text.equals("{")) {
                depth++;
            } else if (text.equals("}")) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isQualifier(String text) {
        return switch (text) {
            case "flat", "smooth", "noperspective", "centroid", "sample", "invariant", "precise",
                    "highp", "mediump", "lowp", "patch", "pervertexNV", "pervertexEXT" -> true;
            default -> false;
        };
    }

    private static boolean isPrecision(String text) {
        return text.equals("highp") || text.equals("mediump") || text.equals("lowp");
    }

    private static String directiveName(String text) {
        int i = 0;
        if (i < text.length() && text.charAt(i) == '#') {
            i++;
        }
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                i++;
            } else {
                break;
            }
        }
        return text.substring(start, i);
    }

    private static String directiveSymbol(String text) {
        int i = 0;
        if (i < text.length() && text.charAt(i) == '#') {
            i++;
        }
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                i++;
            } else {
                break;
            }
        }
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                i++;
            } else {
                break;
            }
        }
        return text.substring(start, i);
    }

    private static List<GlslToken> tokenize(String source) {
        List<GlslToken> tokens = new ArrayList<>();
        int i = 0;
        int length = source.length();
        boolean atLineStart = true;

        while (i < length) {
            char c = source.charAt(i);
            if (c == '\r' || c == '\n') {
                i++;
                atLineStart = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                i += 2;
                while (i < length && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = i + 1 < length ? i + 2 : length;
                continue;
            }
            if (c == '#' && atLineStart) {
                int start = i;
                while (i < length) {
                    char ch = source.charAt(i);
                    if (ch == '\r' || ch == '\n') {
                        break;
                    }
                    if (ch == '\\' && i + 1 < length) {
                        if (source.charAt(i + 1) == '\r' && i + 2 < length && source.charAt(i + 2) == '\n') {
                            i += 3;
                        } else {
                            i += 2;
                        }
                        continue;
                    }
                    i++;
                }
                if (i < length && source.charAt(i) == '\r') {
                    i++;
                    if (i < length && source.charAt(i) == '\n') {
                        i++;
                    }
                } else if (i < length && source.charAt(i) == '\n') {
                    i++;
                }
                tokens.add(new GlslToken(source.substring(start, i), start, i, true, false));
                atLineStart = true;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                i++;
                while (i < length) {
                    char ch = source.charAt(i);
                    if (Character.isLetterOrDigit(ch) || ch == '_') {
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(new GlslToken(source.substring(start, i), start, i, false, true));
                atLineStart = false;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < length && Character.isDigit(source.charAt(i + 1)))) {
                int start = i;
                i++;
                while (i < length) {
                    char ch = source.charAt(i);
                    if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '+' || ch == '-') {
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(new GlslToken(source.substring(start, i), start, i, false, false));
                atLineStart = false;
                continue;
            }
            if (c == '"') {
                int start = i;
                i++;
                while (i < length && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\' && i + 1 < length) {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (i < length) {
                    i++;
                }
                tokens.add(new GlslToken(source.substring(start, i), start, i, false, false));
                atLineStart = false;
                continue;
            }
            tokens.add(new GlslToken(String.valueOf(c), i, i + 1, false, false));
            i++;
            atLineStart = false;
        }
        return tokens;
    }
}
