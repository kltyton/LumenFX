/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The render-layer index the drain reads to draw Photon geometry manually: RenderType →
 * {@link PhotonDrawInfo}. It also holds the build/drop primitives for the PER-MATERIAL-OWNED custom
 * shaders (those RenderTypes are NOT cached here — {@code CustomShaderMaterial} owns them). The
 * shared, value-based factory for the standard HDR materials lives in the material layer, in
 * {@code MaterialRenderTypes}.
 */
public final class PhotonRenderTypes {

    /** What Photon needs to draw a render type itself; render types without one are not Photon's. */
    public record PhotonDrawInfo(Programs programs, Bindings bindings,
                                 @Nullable InstancedRecipe instanced) {

        public record Programs(RenderPipeline main) {
        }

        /**
         * Everything a pass binds for this material; none of it comes from the RenderSetup.
         * {@code legacyDepth}: a custom shader written for forward-Z depth. {@code binder}: state the material
         * binds itself.
         */
        public record Bindings(Map<String, Identifier> textures,
                               List<String> sceneSamplers,
                               @Nullable PhotonCustomUniforms customUniforms,
                               @Nullable GraphSource graph,
                               boolean legacyDepth,
                               @Nullable MaterialBinder binder) {
            public Bindings(Map<String, Identifier> textures, List<String> sceneSamplers,
                            @Nullable PhotonCustomUniforms customUniforms, @Nullable GraphSource graph) {
                this(textures, sceneSamplers, customUniforms, graph, false, null);
            }

            public Bindings(Map<String, Identifier> textures, List<String> sceneSamplers,
                            @Nullable PhotonCustomUniforms customUniforms, @Nullable GraphSource graph,
                            boolean legacyDepth) {
                this(textures, sceneSamplers, customUniforms, graph, legacyDepth, null);
            }
        }

        /**
         * The recipe for re-deriving this material's pipeline against a GPU-instanced vertex stage —
         * the instanced variants are separate pipelines, not a bind-time choice (26.1 bakes the
         * {@code #define} and the vertex format into the pipeline). {@code null} = the material has no
         * instanced form and its emitter must take the CPU path.
         * <p>
         * The sources are mutually exclusive and checked in this order by
         * {@code Emitter.bakeInstancedGroup}: a shader graph (read from {@link Bindings#graph}),
         * a material that derives its own ({@link #source}), a custom user shader ({@link #customShaderKey}),
         * else Photon's own stage ({@link #hdrFragment}).
         */
        public record InstancedRecipe(PhotonPipelines.ParticlePipelineKey key,
                                      @Nullable Identifier hdrFragment,
                                      @Nullable PhotonPipelines.CustomShaderKey customShaderKey,
                                      @Nullable PipelineSource source) {
            public InstancedRecipe(PhotonPipelines.ParticlePipelineKey key, @Nullable Identifier hdrFragment,
                                   @Nullable PhotonPipelines.CustomShaderKey customShaderKey) {
                this(key, hdrFragment, customShaderKey, null);
            }
        }
    }

    /**
     * GPU state a material owns and binds itself (a live uniform block, textures it made, per-texture samplers).
     * Photon opens its own passes, so this is the material's way into them, as {@link GraphSource} is a graph's.
     */
    public interface MaterialBinder {
        /** Before any pass opens: upload and resolve everything illegal inside one. */
        void prepare();

        /** Inside the pass, after the pipeline is set. */
        void bind(RenderPass pass);
    }

    /**
     * A material that builds its own pipelines (a KilaMaterial), so its instanced and CPU-records forms are its to
     * derive. {@code geometry} null is the CPU geometry; {@code cpuData} asks for the per-particle records bound
     * with it. Null when that program failed to compile: the group then draws on the CPU without records.
     */
    @FunctionalInterface
    public interface PipelineSource {
        @Nullable
        RenderPipeline pipeline(@Nullable PhotonPipelines.InstancedGeometryKey geometry, boolean cpuData);
    }

    /**
     * A shader-graph material drawn through Photon's own pipeline (implemented in the KilaGraph compat layer when
     * KilaGraph is present).
     */
    public interface GraphSource {
        long usedChannelMask();

        boolean usesCustomData();

        RenderPipeline pipeline(@Nullable PhotonPipelines.InstancedGeometryKey geometry,
                                PhotonPipelines.ParticlePipelineKey key,
                                @Nullable PhotonPipelines.CpuData cpu);

        void prepareUniforms();

        void bindCustomUniforms(RenderPass pass);
    }

    private static final Map<RenderType, PhotonDrawInfo> DRAW_INFO = new ConcurrentHashMap<>();

    /**
     * The material's pipeline over CPU geometry with the per-particle records bound ({@code PHOTON_CPU_DATA}), or
     * null when it has none: only a material that reads particle data has one, a shader graph or a KilaMaterial.
     */
    @Nullable
    public static RenderPipeline cpuDataPipeline(PhotonDrawInfo info) {
        var graph = info.bindings().graph();
        var recipe = info.instanced();
        if (graph != null && recipe != null) {
            return graph.pipeline(null, recipe.key(),
                    new PhotonPipelines.CpuData(graph.usedChannelMask() != 0L, graph.usesCustomData()));
        }
        return recipe != null && recipe.source() != null ? recipe.source().pipeline(null, true) : null;
    }

    @Nullable
    public static PhotonDrawInfo drawInfo(RenderType renderType) {
        return DRAW_INFO.get(renderType);
    }

    private PhotonRenderTypes() {
    }

    /** Register the DrawInfo for a freshly built RenderType. Used by {@code MaterialRenderTypes} for
     *  the shared HDR types; custom shaders register through {@link #createCustomShader}. */
    public static void registerDrawInfo(RenderType renderType, PhotonDrawInfo info) {
        DRAW_INFO.put(renderType, info);
    }

    // ---- custom user shaders --------------------------------------------------------------------
    //
    // These are NOT cached here. Each CustomShaderMaterial instance OWNS its RenderTypes + uniform
    // buffer and frees them when it is released (the 1.21 LDShaderHolder + Cleaner lifecycle). This
    // layer only BUILDS one and registers it into the drain's indexes; the expensive part — the
    // compiled pipeline — is still deduped globally in PhotonPipelines, so per-material ownership
    // never duplicates a GPU compile. Two identical-but-separate materials get their own RenderTypes
    // (they each own a live uniform buffer that a timeline can drive independently); share the
    // material instance if you want them to batch.

    /** Fragment shaders whose pipeline failed GPU compilation — short-circuit so a known-bad shader
     *  isn't re-precompiled every frame. Cleared per shader on recompile (see
     *  {@link #clearFailedCustomShader}). */
    private static final Set<Identifier> FAILED_CUSTOM_SHADERS = ConcurrentHashMap.newKeySet();

    /** The caller owns the result and must {@link #dropCustomShader} it; {@code liveTextures} is referenced live.
     *  Empty when the pipeline failed to compile. */
    public static Optional<RenderType> createCustomShader(PhotonPipelines.CustomShaderKey key,
                                                          PhotonCustomUniforms uniforms,
                                                          Map<String, Identifier> liveTextures,
                                                          boolean legacyDepth) {
        if (FAILED_CUSTOM_SHADERS.contains(key.fragmentShader())) {
            return Optional.empty();
        }
        var pipeline = PhotonPipelines.customShader(key);
        if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
            if (FAILED_CUSTOM_SHADERS.add(key.fragmentShader())) {
                Photon.LOGGER.warn("custom shader pipeline failed to compile: {} (must be 26.2-format GLSL: "
                        + "uniform values in blocks, no loose uniforms)", key.fragmentShader());
            }
            return Optional.empty();
        }
        var setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", MissingTextureAtlasSprite.getLocation())
                .useLightmap();
        // placeholders only; Photon binds the live textures
        key.samplerNames().forEach(name -> setup.withTexture(name, MissingTextureAtlasSprite.getLocation()));
        key.sceneSamplers().forEach(name -> setup.withTexture(name, MissingTextureAtlasSprite.getLocation()));
        if (key.pipelineKey().blend() != null && key.pipelineKey().mode() == PrimitiveTopology.QUADS) {
            setup.sortOnUpload();
        }
        var renderType = RenderType.create("photon_custom_shader", setup.createRenderSetup());
        DRAW_INFO.put(renderType, new PhotonDrawInfo(
                new PhotonDrawInfo.Programs(pipeline),
                new PhotonDrawInfo.Bindings(liveTextures, key.sceneSamplers(), uniforms, null, legacyDepth),
                new PhotonDrawInfo.InstancedRecipe(key.pipelineKey(), null, key)));
        return Optional.of(renderType);
    }

    // ---- materials that build their own pipelines ----------------------------------------------

    /**
     * A RenderType (not cached) over a pipeline the material built itself; its own state comes in through
     * {@link PhotonDrawInfo.Bindings#binder}, its other forms through {@link PhotonDrawInfo.InstancedRecipe#source}.
     * Owned by the material: release it with {@link #dropCustomShader}.
     */
    public static RenderType createMaterialOwned(String name, PhotonDrawInfo info, boolean lightmap) {
        var setup = RenderSetup.builder(info.programs().main());
        if (lightmap) {
            setup.useLightmap();
        }
        var key = info.instanced() == null ? null : info.instanced().key();
        if (key != null && key.blend() != null && key.mode() == PrimitiveTopology.QUADS) {
            setup.sortOnUpload();
        }
        var renderType = RenderType.create(name, setup.createRenderSetup());
        DRAW_INFO.put(renderType, info);
        return renderType;
    }

    /** Release a material-owned custom RenderType from the drain index. No GPU work — the material's
     *  Cleaner closes the uniform buffer separately. Idempotent. */
    public static void dropCustomShader(RenderType renderType) {
        DRAW_INFO.remove(renderType);
    }

    /** Let a fixed shader re-attempt compilation (called on the material's recompile). */
    public static void clearFailedCustomShader(Identifier fragmentShader) {
        FAILED_CUSTOM_SHADERS.remove(fragmentShader);
    }

    // ---- resource reload ------------------------------------------------------------------------

    private static final AtomicInteger RELOAD_GENERATION =
            new AtomicInteger();

    /** Bumped on every resource reload; a {@code CustomShaderMaterial} compares against it to drop
     *  stale JSON metadata + GPU state. The GLSL itself is recompiled by the engine on reload, but
     *  the Photon-side uniform LAYOUT is parsed from the shader JSON and must be re-read. */
    public static int reloadGeneration() {
        return RELOAD_GENERATION.get();
    }

    public static void onResourceReload() {
        RELOAD_GENERATION.incrementAndGet();
        FAILED_CUSTOM_SHADERS.clear();
        com.lowdragmc.photon.client.resource.PhotonProceduralTextures.clear();
        PhotonPipelines.onResourceReload();
    }
}
