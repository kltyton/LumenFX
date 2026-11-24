package com.lowdragmc.lowdraglib2.configurator;

public interface IConfigurable {
    default String getConfigurableName() {
        return getClass().getSimpleName();
    }
}
