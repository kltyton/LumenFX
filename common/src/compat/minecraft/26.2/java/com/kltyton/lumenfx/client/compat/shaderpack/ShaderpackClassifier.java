package com.kltyton.lumenfx.client.compat.shaderpack;

public final class ShaderpackClassifier {
    private ShaderpackClassifier() {
    }

    public static ShaderpackKind classify(final boolean complementaryReimaginedSourceProof) {
        return complementaryReimaginedSourceProof
                ? ShaderpackKind.COMPLEMENTARY_REIMAGINED
                : ShaderpackKind.UNKNOWN;
    }

    public static ShaderpackKind classify(final boolean complementaryReimaginedSourceProof, final boolean packLoaded) {
        if (complementaryReimaginedSourceProof) {
            return ShaderpackKind.COMPLEMENTARY_REIMAGINED;
        }
        return packLoaded ? ShaderpackKind.GENERIC : ShaderpackKind.UNKNOWN;
    }

    public static ColourEncoding colourEncoding(final ShaderpackKind kind) {
        return switch (kind) {
            case COMPLEMENTARY_REIMAGINED -> ColourEncoding.GAMMA_2_2;
            case GENERIC -> ColourEncoding.LINEAR;
            case UNKNOWN -> ColourEncoding.UNKNOWN;
        };
    }
}
