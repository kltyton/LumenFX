package com.kltyton.lumenfx.api.client.parameter;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** Host block and entity selectors for the Unity project's layer slots. A leading # selects a Minecraft tag. */
public record UnityCollisionLayers(Map<Integer, Layer> layers) {
    public static final UnityCollisionLayers EMPTY = new UnityCollisionLayers(Map.of());

    public UnityCollisionLayers {
        layers = Map.copyOf(layers);
        for (int index : layers.keySet())
            if (index < 0 || index >= Integer.SIZE) throw new IllegalArgumentException("Invalid Unity layer index " + index);
    }

    public record Layer(List<String> blocks, List<String> entities) {
        public Layer {
            blocks = List.copyOf(blocks);
            entities = List.copyOf(entities);
            blocks.forEach(Layer::validate);
            entities.forEach(Layer::validate);
        }

        private static void validate(String selector) {
            if (Identifier.tryParse(selector.startsWith("#") ? selector.substring(1) : selector) == null)
                throw new IllegalArgumentException("Invalid Unity collision layer selector " + selector);
        }
    }
}
