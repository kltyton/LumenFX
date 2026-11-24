package com.kltyton.lumenfx.client.resource.shader.glsl;

public record GlslOutputDeclaration(
        String name,
        String type,
        int start,
        int end,
        int conditionalDepth,
        boolean array,
        int declarators,
        String precision) {
}
