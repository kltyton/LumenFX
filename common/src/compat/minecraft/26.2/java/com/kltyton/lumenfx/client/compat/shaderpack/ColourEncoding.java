package com.kltyton.lumenfx.client.compat.shaderpack;

public enum ColourEncoding {
    LINEAR(0),
    SRGB(1),
    GAMMA_2_2(2),
    UNKNOWN(-1);

    private final int shaderCode;

    ColourEncoding(int shaderCode) {
        this.shaderCode = shaderCode;
    }

    /** Encoding selector shared with world_to_linear, linear_to_world, and particle_light shaders. */
    public int shaderCode() {
        return shaderCode;
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    public ColourEncoding orDefaultForFormat(boolean floatingPoint) {
        return this != UNKNOWN ? this : (floatingPoint ? LINEAR : SRGB);
    }
}
