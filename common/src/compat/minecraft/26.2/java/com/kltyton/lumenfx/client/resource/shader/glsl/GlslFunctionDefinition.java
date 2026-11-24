package com.kltyton.lumenfx.client.resource.shader.glsl;

import java.util.List;

/** Source ranges for a top-level function definition, excluding prototypes and calls. */
public record GlslFunctionDefinition(String returnType, String name, List<Parameter> parameters,
                                     int start, int bodyStart, int bodyEnd, int end) {
    public record Parameter(String type, String name, String direction) { }

    public GlslFunctionDefinition { parameters = List.copyOf(parameters); }
}
