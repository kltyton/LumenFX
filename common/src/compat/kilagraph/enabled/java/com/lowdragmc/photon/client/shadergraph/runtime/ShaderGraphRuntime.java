/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.shadergraph.runtime;

import com.kltyton.lumenfx.internal.photon.core.editor.resource.BuiltinPath;
import com.kltyton.lumenfx.internal.photon.core.editor.resource.FilePath;
import com.kltyton.lumenfx.internal.photon.core.editor.resource.IResourcePath;
import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.IGraphReferenceResolver;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.resource.PhotonAssetLibrary;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderFunctionGraph;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Compiles each authored particle graph once per pack generation on the render thread when external KilaGraph is installed. */
public final class ShaderGraphRuntime {
    private static final Map<IResourcePath, Entry> CACHE = new HashMap<>();
    private static Object generation;

    private ShaderGraphRuntime() {}

    @Nullable
    public static Entry get(@Nullable IResourcePath path) {
        if (path == null) return null;
        var activeGeneration = PhotonAssetLibrary.generation();
        if (generation != activeGeneration) {
            CACHE.clear();
            generation = activeGeneration;
        }
        return CACHE.computeIfAbsent(path, ShaderGraphRuntime::compile);
    }

    private static Entry compile(IResourcePath path) {
        try {
            var graph = new PackGraphResolver().loadShaderGraph(path);
            if (graph == null) throw new IllegalArgumentException("Shader graph resource not found");
            var compiler = (PhotonShaderCompiler) graph.createCompiler();
            var compiled = compiler.compile();
            if (compiled.hasStageErrors()) {
                throw new IllegalArgumentException(compiled.stageErrors().getFirst().message());
            }
            return new Entry(graph, compiled, "", compiler.getUsedChannelMask(), compiler.isUsesCustomData());
        } catch (RuntimeException error) {
            Photon.LOGGER.error("Compiling Photon shader graph {}", path.getResourceName(), error);
            return new Entry(null, null, error.toString(), 0, false);
        }
    }

    private static IResourcePath toInternal(com.lowdragmc.lowdraglib2.editor.resource.IResourcePath ext) {
        if (ext instanceof com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath b) {
            return new BuiltinPath(b.name());
        }
        if (ext instanceof com.lowdragmc.lowdraglib2.editor.resource.FilePath f) {
            return new FilePath(f.location);
        }
        return new BuiltinPath(ext.getResourceName());
    }

    @Getter
    public static final class Entry {
        private final @Nullable ShaderGraph graph;
        private final @Nullable CompiledShaderGraph compiled;
        private final String errorMessage;
        private final long usedChannelMask;
        private final boolean usesCustomData;

        private Entry(@Nullable ShaderGraph graph, @Nullable CompiledShaderGraph compiled, String errorMessage,
                      long usedChannelMask, boolean usesCustomData) {
            this.graph = graph;
            this.compiled = compiled;
            this.errorMessage = errorMessage;
            this.usedChannelMask = usedChannelMask;
            this.usesCustomData = usesCustomData;
        }

        public boolean isValid() {
            return compiled != null;
        }
    }

    private static final class PackGraphResolver implements IGraphReferenceResolver {
        private final Map<IResourcePath, Graph> references = new HashMap<>();
        private final Set<IResourcePath> loading = new HashSet<>();

        @Override
        public @Nullable Graph resolve(@Nullable com.lowdragmc.lowdraglib2.editor.resource.IResourcePath extPath) {
            if (extPath == null) return null;
            IResourcePath path = toInternal(extPath);
            var existing = references.get(path);
            if (existing != null) return existing;
            if (!loading.add(path)) throw new IllegalArgumentException("Cyclic Photon shader graph: " + path.getResourceName());
            try {
                var function = PhotonAssetLibrary.findGraph(path, PhotonAssetLibrary.SHADER_FUNCTION);
                Graph graph = function == null ? loadShaderGraph(path) : loadFunction(function);
                if (graph == null) throw new IllegalArgumentException("Missing Photon subgraph: " + path.getResourceName());
                references.put(path, graph);
                return graph;
            } finally {
                loading.remove(path);
            }
        }

        private @Nullable ShaderGraph loadShaderGraph(IResourcePath path) {
            if (path instanceof BuiltinPath builtin && builtin.name().equals("default_particle")) {
                return new ShaderGraph();
            }
            var tag = PhotonAssetLibrary.findGraph(path, PhotonAssetLibrary.SHADER_GRAPH);
            if (tag == null) return null;
            var graph = new ShaderGraph(false);
            var model = graph.graphModel;
            model.setReferenceResolver(this);
            var graphTag = tag.get("graph") instanceof CompoundTag compound ? compound : tag;
            try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
                model.deserialize(TagValueInput.create(reporter, Platform.getFrozenRegistry(), graphTag));
            }
            model.setReferenceResolver(this);
            graph.restoreFixedStagesAfterDeserialize();
            return graph;
        }

        private PhotonShaderFunctionGraph loadFunction(CompoundTag tag) {
            var graph = new PhotonShaderFunctionGraph();
            graph.graphModel.setReferenceResolver(this);
            PersistedParser.deserializeNBT(tag, graph.graphModel, Platform.getFrozenRegistry());
            graph.graphModel.setReferenceResolver(this);
            return graph;
        }
    }
}
