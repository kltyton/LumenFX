package com.kltyton.lumenfx.api.client.geometry;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Snapshot of geometry collection components and their rest assets in source axes and centimeters. */
public record NiagaraGeometryCollectionInputs(Map<String, Component> components, Map<String, Source> parameters,
                                             Map<String, RestCollection> assets, @Nullable Component attachment) {
    public static final NiagaraGeometryCollectionInputs EMPTY = new NiagaraGeometryCollectionInputs(Map.of(), Map.of(), Map.of(), null);

    public sealed interface Source permits RestCollection, Component {
        RestCollection asset();
    }

    public record Piece(int transform, boolean leaf, Vec3 minimum, Vec3 maximum) {
        public Piece {
            if (transform < 0) throw new IllegalArgumentException("Invalid geometry collection transform index");
            Objects.requireNonNull(minimum);
            Objects.requireNonNull(maximum);
        }
    }

    /** The asset rest transforms are accumulated in component space, in original transform-group order. */
    public record RestCollection(List<Piece> pieces, List<Transform> globalRest) implements Source {
        public RestCollection {
            pieces = List.copyOf(pieces);
            globalRest = List.copyOf(globalRest);
            int count = globalRest.size();
            for (var piece : pieces) {
                if (piece.transform() >= count) throw new IllegalArgumentException("Geometry collection piece has no rest transform");
            }
        }
        @Override public RestCollection asset() { return this; }
    }

    @FunctionalInterface public interface RestTransforms {
        /** Called on the client thread after Niagara finishes its CPU simulation step. */
        void apply(List<Transform> localRest, boolean leavesOnly);
    }

    public record Component(String identity, RestCollection asset, Transform root, List<Transform> localRest,
                            List<Transform> componentSpace, Vec3 boundsOrigin, Vec3 boundsExtent,
                            RestTransforms restTransforms) implements Source {
        public Component {
            if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Missing geometry collection component identity");
            Objects.requireNonNull(asset);
            Objects.requireNonNull(root);
            localRest = List.copyOf(localRest);
            componentSpace = List.copyOf(componentSpace);
            Objects.requireNonNull(boundsOrigin);
            Objects.requireNonNull(boundsExtent);
            Objects.requireNonNull(restTransforms);
        }
    }

    public NiagaraGeometryCollectionInputs {
        components = named(components);
        parameters = named(parameters);
        assets = named(assets);
    }

    private static <T> Map<String, T> named(Map<String, T> source) {
        var result = new TreeMap<String, T>(SourceNames.ASCII_CASE_INSENSITIVE);
        result.putAll(Map.copyOf(source));
        if (result.size() != source.size()) throw new IllegalArgumentException("Duplicate geometry collection name");
        return Collections.unmodifiableMap(result);
    }
}
