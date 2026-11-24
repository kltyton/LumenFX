package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs.Component;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalMeshDefinition.Skinning;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Lod;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.SkinWeightBuffer;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** World-lifetime generated data for immutable skeletal component and LOD publications. */
public final class NiagaraSkeletalMeshWorlds {
    private static final Map<ClientLevel, World> WORLDS = new WeakHashMap<>();

    private NiagaraSkeletalMeshWorlds() { }

    static @Nullable World world(@Nullable ClientLevel level) {
        if (level == null) return null;
        synchronized (WORLDS) { return WORLDS.computeIfAbsent(level, ignored -> new World()); }
    }

    public static void clear(ClientLevel level) {
        synchronized (WORLDS) { WORLDS.remove(level); }
    }

    public static void clear() {
        synchronized (WORLDS) { WORLDS.clear(); }
    }

    private record PoseKey(@Nullable Lod lod, @Nullable SkinWeightBuffer weights, Skinning skinning) { }

    static final class World {
        private final Map<Component, Map<PoseKey, NiagaraSkeletalPose>> poses = new IdentityHashMap<>();
        private final Map<StaticMeshGeometry, Map<Integer, WeakReference<NiagaraMeshUvTree>>> uvTrees = new WeakHashMap<>();
        private final Map<StaticMeshGeometry, WeakReference<NiagaraSkeletalConnectivity>> connectivity = new WeakHashMap<>();
        private double poseTime = Double.NaN;

        synchronized NiagaraSkeletalPose pose(Component source, SkeletalMeshAsset mesh, @Nullable Lod lod,
                                              @Nullable SkinWeightBuffer weights, Skinning skinning, double frameTime) {
            if (poseTime != frameTime) {
                poses.clear();
                poseTime = frameTime;
            }
            var variants = poses.computeIfAbsent(source, ignored -> new HashMap<>());
            return variants.computeIfAbsent(new PoseKey(lod, weights, skinning),
                    ignored -> new NiagaraSkeletalPose(mesh, lod, weights, source.componentPose(), skinning));
        }

        synchronized NiagaraMeshUvTree uvTree(StaticMeshGeometry geometry, int channel) {
            var channels = uvTrees.computeIfAbsent(geometry, ignored -> new HashMap<>());
            var reference = channels.get(channel);
            var tree = reference == null ? null : reference.get();
            if (tree == null) {
                tree = new NiagaraMeshUvTree(geometry, channel);
                channels.put(channel, new WeakReference<>(tree));
            }
            return tree;
        }

        synchronized NiagaraSkeletalConnectivity connectivity(StaticMeshGeometry geometry) {
            var reference = connectivity.get(geometry);
            var value = reference == null ? null : reference.get();
            if (value == null) {
                value = new NiagaraSkeletalConnectivity(geometry);
                connectivity.put(geometry, new WeakReference<>(value));
            }
            return value;
        }
    }

    static NiagaraSkeletalPose pose(@Nullable World world, @Nullable Component source, SkeletalMeshAsset mesh,
                                   @Nullable Lod lod, @Nullable SkinWeightBuffer weights, Skinning skinning, double frameTime) {
        if (world != null && source != null && Double.isFinite(frameTime))
            return world.pose(source, mesh, lod, weights, skinning, frameTime);
        return new NiagaraSkeletalPose(mesh, lod, weights, source == null ? List.of() : source.componentPose(), skinning);
    }
}
