package com.kltyton.lumenfx.tools.shader;

import java.util.Locale;

enum ShaderStage {
    COMPUTE("compute", "cs_6_6", 2),
    VERTEX("vertex", "vs_6_6", 0),
    FRAGMENT("fragment", "ps_6_6", 1);

    private final String id;
    private final String profile;
    private final int shadercKind;

    ShaderStage(final String id, final String profile, final int shadercKind) {
        this.id = id;
        this.profile = profile;
        this.shadercKind = shadercKind;
    }

    String id() {
        return this.id;
    }

    String profile() {
        return this.profile;
    }

    int shadercKind() {
        return this.shadercKind;
    }

    boolean graphics() {
        return this == VERTEX || this == FRAGMENT;
    }

    static ShaderStage parse(final String value) {
        if (value == null) {
            return COMPUTE;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "compute", "cs" -> COMPUTE;
            case "vertex", "vs" -> VERTEX;
            case "pixel", "fragment", "ps", "fs" -> FRAGMENT;
            default -> throw new BakeException("Unsupported shader stage '" + value + "'");
        };
    }
}
