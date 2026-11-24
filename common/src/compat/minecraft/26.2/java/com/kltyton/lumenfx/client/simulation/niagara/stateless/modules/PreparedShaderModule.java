package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.shader.StatelessShaderFields;

/** Modules with shared native preparation publish the same prepared values to their GPU shader fields. */
public interface PreparedShaderModule extends PreparedModule {
    void writeShaderParameters(StatelessShaderFields fields);
}
