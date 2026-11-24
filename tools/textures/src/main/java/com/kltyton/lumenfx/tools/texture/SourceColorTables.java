package com.kltyton.lumenfx.tools.texture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SourceColorTables {
    private static final String COLOR_HEADER = "Engine/Source/Runtime/Core/Public/Math/Color.h";
    private static final String COLOR_IMPLEMENTATION = "Engine/Source/Runtime/Core/Private/Math/Color.cpp";
    private static final Pattern FLOAT_LITERAL =
            Pattern.compile("[-+]?(?:\\d+\\.\\d*|\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?[fF]?");
    private static final Pattern HEX_LITERAL = Pattern.compile("0[xX][0-9a-fA-F]+");
    private static final int SRGB_MIN_BITS = (127 - 13) << 23;
    private static final float SRGB_MIN = Float.intBitsToFloat(SRGB_MIN_BITS);
    private static final float SRGB_MAX = Float.intBitsToFloat(0x3f7fffff);

    private final float[] srgbToLinear;
    private final float[] pow22OneOver255;
    private final int[] fp32ToSrgb8;

    private SourceColorTables(float[] srgbToLinear, float[] pow22OneOver255, int[] fp32ToSrgb8) {
        this.srgbToLinear = srgbToLinear;
        this.pow22OneOver255 = pow22OneOver255;
        this.fp32ToSrgb8 = fp32ToSrgb8;
    }

    public static SourceColorTables load(Path unrealSourceRoot) throws IOException {
        Path root = unrealSourceRoot.toAbsolutePath().normalize();
        String header = Files.readString(root.resolve(COLOR_HEADER), StandardCharsets.UTF_8);
        String implementation = Files.readString(root.resolve(COLOR_IMPLEMENTATION), StandardCharsets.UTF_8);
        return new SourceColorTables(
                floats(header, "sRGBToLinearTable[]", 256, COLOR_HEADER),
                floats(implementation, "Pow22OneOver255Table[256]", 256, COLOR_IMPLEMENTATION),
                hexes(implementation, "stb_fp32_to_srgb8_tab4[104]", 104, COLOR_IMPLEMENTATION));
    }

    public float[] decodeTable(boolean legacyGamma) {
        return legacyGamma ? pow22OneOver255.clone() : srgbToLinear.clone();
    }

    public int toSrgb8(float linear) {
        float clamped = linear;
        if (!(clamped > SRGB_MIN)) clamped = SRGB_MIN;
        if (clamped > SRGB_MAX) clamped = SRGB_MAX;
        int bits = Float.floatToRawIntBits(clamped);
        int entry = fp32ToSrgb8[(bits - SRGB_MIN_BITS) >>> 20];
        int bias = (entry >>> 16) << 9;
        int scale = entry & 0xffff;
        int t = (bits >>> 12) & 0xff;
        return (bias + scale * t) >>> 16;
    }

    public static int quantizeRound(float linear) {
        return (int) (0.5F + clamp01(linear) * 255.0F);
    }

    private static float clamp01(float value) {
        if (value > 1.0F) return 1.0F;
        if (value >= 0.0F) return value;
        return 0.0F;
    }

    private static float[] floats(String text, String declaration, int expected, String file) {
        String body = arrayBody(text, declaration, file);
        Matcher matcher = FLOAT_LITERAL.matcher(body);
        List<Float> values = new ArrayList<>(expected);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.endsWith("f") || token.endsWith("F")) token = token.substring(0, token.length() - 1);
            values.add(Float.parseFloat(token));
        }
        if (values.size() != expected)
            throw new IllegalArgumentException("Expected " + expected + " values in " + declaration + " from " + file);
        float[] result = new float[expected];
        for (int i = 0; i < expected; i++) result[i] = values.get(i);
        return result;
    }

    private static int[] hexes(String text, String declaration, int expected, String file) {
        String body = arrayBody(text, declaration, file);
        Matcher matcher = HEX_LITERAL.matcher(body);
        List<Integer> values = new ArrayList<>(expected);
        while (matcher.find()) {
            String token = matcher.group();
            values.add((int) Long.parseLong(token.substring(2), 16));
        }
        if (values.size() != expected)
            throw new IllegalArgumentException("Expected " + expected + " values in " + declaration + " from " + file);
        int[] result = new int[expected];
        for (int i = 0; i < expected; i++) result[i] = values.get(i);
        return result;
    }

    private static String arrayBody(String text, String declaration, String file) {
        int declarationIndex = text.indexOf(declaration);
        if (declarationIndex < 0)
            throw new IllegalArgumentException("Missing " + declaration + " in " + file);
        int open = text.indexOf('{', declarationIndex + declaration.length());
        if (open < 0) throw new IllegalArgumentException("Missing array body for " + declaration + " in " + file);
        int close = text.indexOf("};", open);
        if (close < 0) throw new IllegalArgumentException("Unterminated array " + declaration + " in " + file);
        return text.substring(open + 1, close);
    }
}
