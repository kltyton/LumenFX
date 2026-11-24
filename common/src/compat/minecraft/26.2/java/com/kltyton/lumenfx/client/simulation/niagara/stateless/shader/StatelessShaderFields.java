package com.kltyton.lumenfx.client.simulation.niagara.stateless.shader;

/** Named scalar words and vector spans are packed against the baked shader's reflected uniform layout. */
public interface StatelessShaderFields {
    void integer(String name, int value);
    void floating(String name, float value);
    void floats(String name, float[] values, int offset, int count);
    void words(String name, int[] values, int offset, int count);
}
