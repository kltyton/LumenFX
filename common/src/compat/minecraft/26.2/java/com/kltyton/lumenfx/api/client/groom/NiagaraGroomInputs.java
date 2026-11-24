package com.kltyton.lumenfx.api.client.groom;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Groom groups linked to this Niagara instance. Transforms use source axes and centimeters. */
public record NiagaraGroomInputs(Map<String, Group> actors, @Nullable Group attached) {
    public static final NiagaraGroomInputs EMPTY = new NiagaraGroomInputs(Map.of(), null);

    public record Group(String identity, @Nullable NiagaraGroomGuides guides, @Nullable NiagaraGroomRoots roots,
                        NiagaraGroomPhysics physics, Transform componentTransform, Transform simulationBone,
                        Transform renderedWorld, Transform rigidWorld, Vec3 deformedPositionOffset,
                        boolean simulationEnabled, boolean globalInterpolation, boolean skinningTransfer,
                        boolean localSimulation, boolean nanite, boolean forceReset, long skeletalMeshes,
                        float linearVelocityScale, float angularVelocityScale, float timeDilation,
                        boolean rbfLocalSpace, @Nullable NiagaraGroomOutput output) {
        public Group {
            if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Missing groom group identity");
            Objects.requireNonNull(physics);
            Objects.requireNonNull(componentTransform);
            Objects.requireNonNull(simulationBone);
            Objects.requireNonNull(renderedWorld);
            Objects.requireNonNull(rigidWorld);
            Objects.requireNonNull(deformedPositionOffset);
            if (roots != null && (guides == null || roots.roots() != guides.curves()))
                throw new IllegalArgumentException("Groom root data must match the guide curves");
        }
    }

    public NiagaraGroomInputs {
        var copy = new TreeMap<String, Group>(SourceNames.ASCII_CASE_INSENSITIVE);
        copy.putAll(Map.copyOf(actors));
        if (copy.size() != actors.size()) throw new IllegalArgumentException("Duplicate groom source actor");
        actors = Collections.unmodifiableMap(copy);
    }
}
