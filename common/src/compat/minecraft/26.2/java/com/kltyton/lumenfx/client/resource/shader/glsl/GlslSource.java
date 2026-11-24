package com.kltyton.lumenfx.client.resource.shader.glsl;

import java.util.List;
import java.util.Set;

public record GlslSource(
        GlslHeader header,
        List<GlslFunction> mains,
        List<GlslOutputDeclaration> outputs,
        Set<String> identifiers) {
}
