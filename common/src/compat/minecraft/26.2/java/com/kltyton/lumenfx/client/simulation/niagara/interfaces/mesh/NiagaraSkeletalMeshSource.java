package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs.Component;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalMeshReader.InvalidSamplingRegion;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalMeshDefinition.SourceMode;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Region;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.ResidentLods;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Resolves the authored component, LOD and region priorities before skeletal sampling. */
final class NiagaraSkeletalMeshSource {
    record Sampling(int lod, List<Region> regions) {
        static final Sampling NO_RESIDENT_LOD = new Sampling(-1, List.of());
    }
    private static final String DEFAULT_COMPONENT = "/Script/Engine.SkeletalMeshComponent";

    private NiagaraSkeletalMeshSource() { }

    static @Nullable Component component(String identity, @Nullable String sourcePath,
                                         NiagaraSkeletalMeshDefinition definition, NiagaraSkeletalMeshInputs inputs,
                                         @Nullable Object userObject, boolean explicitOverride) {
        if (specified(definition.meshParameter())) {
            if (!explicitOverride) {
                var parameter = inputs.parameters().get(definition.meshParameter());
                if (parameter != null && parameter.registered() && (parameter.transform() != null || parameter.componentIdentity() != null
                        || !parameter.componentClasses().isEmpty())) return parameter;
                String actor = inputs.hierarchy().actorParameters().get(definition.meshParameter());
                if (actor != null) return actor(inputs, actor, definition, true);
            }
            String path = NiagaraParameterStoreSources.sourceObjectPath(userObject);
            if (path.isEmpty()) return null;
            var component = inputs.components().get(path);
            return component == null || !component.registered() ? actor(inputs, path, definition, true) : component;
        }
        SourceMode mode = definition.mode();
        if (mode == SourceMode.DEFAULT || mode == SourceMode.SOURCE) {
            var component = inputs.components().get(identity);
            // User data interfaces are copied per Niagara component and get a new runtime ID.
            if ((component == null || !component.registered()) && sourcePath != null
                    && !SourceNames.equal(identity, sourcePath)) component = inputs.components().get(sourcePath);
            if ((component == null || !component.registered()) && specified(definition.component()))
                component = inputs.components().get(definition.component());
            if (component != null && component.registered()) return component;
            String actor = definition.actor();
            if (specified(actor) && (inputs.actors().containsKey(actor) || inputs.hierarchy().skeletalActorComponents().containsKey(actor)))
                return actor(inputs, actor, definition, true);
        }
        if (mode == SourceMode.DEFAULT || mode == SourceMode.ATTACH_PARENT) {
            var attached = first(inputs.attachments(), definition, true);
            if (attached != null) return attached;
            for (var outer : inputs.hierarchy().outers()) {
                if (!contains(outer.componentClasses(), componentClass(definition))) continue;
                if (valid(outer, definition, true)) return outer;
                break;
            }
            var rootActors = inputs.hierarchy().attachmentRootActors();
            for (int index = 0; index < rootActors.size(); index++) {
                var source = actor(inputs, rootActors.get(index), definition, index == 0);
                if (source != null) return source;
            }
        }
        if (mode == SourceMode.CHILDREN_ONLY) return first(inputs.children(), definition, false);
        return null;
    }

    static @Nullable Component resolve(@Nullable Component selected, NiagaraSkeletalMeshDefinition definition,
                                       Map<Identifier, SkeletalMeshAsset> assets) {
        if (selected != null) return selected;
        var asset = definition.asset();
        if (asset == null || definition.mode() != SourceMode.DEFAULT && definition.mode() != SourceMode.DEFAULT_MESH_ONLY) return null;
        return Component.asset(Objects.requireNonNull(assets.get(asset), "Missing prepared Niagara skeletal mesh " + asset));
    }

    static Sampling sampling(String identity, SkeletalMeshAsset asset, Component source, NiagaraSkeletalMeshDefinition definition,
                             ResidentLods resident) {
        var streaming = asset.streaming();
        if (!resident.allLods() && resident.lods().isEmpty()) {
            if (streaming != null) streaming.request(asset.lods().size() - 1);
            return Sampling.NO_RESIDENT_LOD;
        }
        var desired = desiredSampling(identity, asset, definition);
        if (streaming != null) streaming.request(desired.lod());
        int lod = Math.max(desired.lod(), Math.max(source.firstResidentLod(), resident.firstLod()));
        int last = asset.lods().size() - 1;
        while (lod <= last && !resident.contains(lod)) lod++;
        return lod > last ? Sampling.NO_RESIDENT_LOD : new Sampling(lod, desired.regions());
    }

    private static Sampling desiredSampling(String identity, SkeletalMeshAsset asset, NiagaraSkeletalMeshDefinition definition) {
        int last = asset.lods().size() - 1;
        if (definition.regions().isEmpty()) {
            int desired = definition.wholeMeshLod() == -1 ? last : Math.clamp(definition.wholeMeshLod(), 0, last);
            return new Sampling(desired, List.of());
        }
        var active = new ArrayList<Region>();
        int lod = -1;
        for (String name : definition.regions()) {
            Region found = null;
            for (var region : asset.regions()) if (SourceNames.equal(name, region.name())) { found = region; break; }
            if (found == null)
                throw new InvalidSamplingRegion(identity, "source mesh does not provide region " + name);
            int required = found.lod() == -1 ? last : Math.clamp(found.lod(), 0, last);
            if (lod != -1 && lod != required)
                throw new InvalidSamplingRegion(identity, "region " + name + " requires a different LOD");
            if (found.triangleOffsets().size() == 0)
                throw new InvalidSamplingRegion(identity, "region " + name + " has no triangles in LOD " + required);
            lod = required;
            active.add(found);
        }
        return new Sampling(lod, List.copyOf(active));
    }

    private static @Nullable Component actor(NiagaraSkeletalMeshInputs inputs, String name,
                                             NiagaraSkeletalMeshDefinition definition, boolean usePrimary) {
        if (usePrimary) {
            var primary = inputs.hierarchy().skeletalActorComponents().get(name);
            if (primary != null && valid(primary, definition, true)) return primary;
        }
        return first(inputs.actors().getOrDefault(name, List.of()), definition, false);
    }

    private static @Nullable Component first(List<Component> candidates, NiagaraSkeletalMeshDefinition definition, boolean allowNullMesh) {
        for (var source : candidates) if (valid(source, definition, allowNullMesh)) return source;
        return null;
    }

    private static boolean valid(Component source, NiagaraSkeletalMeshDefinition definition, boolean allowNullMesh) {
        if (!source.registered() || (!allowNullMesh && source.mesh() == null)
                || !contains(source.componentClasses(), componentClass(definition))) return false;
        if (definition.componentTags().isEmpty()) return true;
        for (String tag : definition.componentTags())
            if (SourceNames.equal(tag, "None") || contains(source.tags(), tag)) return true;
        return false;
    }

    private static String componentClass(NiagaraSkeletalMeshDefinition definition) {
        return specified(definition.componentClass()) ? definition.componentClass() : DEFAULT_COMPONENT;
    }

    private static boolean specified(String name) { return !name.isEmpty() && !SourceNames.equal(name, "None"); }
    private static boolean contains(List<String> values, String name) {
        for (String value : values) if (SourceNames.equal(value, name)) return true;
        return false;
    }
}
