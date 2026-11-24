package com.kltyton.lumenfx.client.compat.shaderpack;

public record FrameOwnership(
        boolean active,
        boolean external,
        Object identity,
        String identityType
) {
    public static FrameOwnership inactive() {
        return new FrameOwnership(false, false, null, null);
    }
}
