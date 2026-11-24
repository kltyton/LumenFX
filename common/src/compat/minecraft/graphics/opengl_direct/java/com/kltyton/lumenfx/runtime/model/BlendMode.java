package com.kltyton.lumenfx.runtime.model;

import java.util.Locale;

/** Blend modes and blend equations matching LumenFX 26.2 {@code MaterialDefinition.Blend} & {@code MaterialBlendOp}. */
public enum BlendMode {
    OPAQUE,
    ALPHA,
    ADDITIVE,
    PREMULTIPLIED,
    MULTIPLY,
    MODULATE,
    SUBTRACT,
    REVERSE_SUBTRACT;

    public static BlendMode parse(String name) {
        if (name == null || name.isBlank()) return ALPHA;
        String norm = name.trim().toLowerCase(Locale.ROOT);
        if (norm.equals("opaque") || norm.equals("masked") || norm.equals("cutout")) return OPAQUE;
        if (norm.contains("reverse_sub") || norm.contains("revsub")) return REVERSE_SUBTRACT;
        if (norm.contains("sub")) return SUBTRACT;
        if (norm.contains("add")) return ADDITIVE;
        if (norm.contains("premult") || norm.equals("alpha_composite")) return PREMULTIPLIED;
        if (norm.contains("modulate")) return MODULATE;
        if (norm.contains("mult")) return MULTIPLY;
        return ALPHA;
    }
}
