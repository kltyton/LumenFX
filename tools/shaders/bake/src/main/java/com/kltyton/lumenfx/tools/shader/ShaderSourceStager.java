package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.lwjgl.util.spvc.Spv;

final class ShaderSourceStager {
    record StagedSource(Path sourceFile, Path stageRoot, List<Path> includeDirectories, Map<String, Path> injected) {
    }

    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"](.*)$");
    private static final Pattern RW_DECLARATION = Pattern.compile(
            "^(\\s*)((?:\\[\\[[^\\]]*\\]\\]\\s*)*)(RWTexture(?:1D|2D|3D)(?:Array)?\\s*<[^>]+>\\s+)"
                    + "([A-Za-z_]\\w*)\\b(.*)$"
    );

    private final Path ueRoot;
    private final Path cacheRoot;
    private final ShaderDirectoryMappings shaderDirectories;
    private final Path compatDirectory;
    private final Path stageRoot;
    private final List<ShaderManifest.IncludeOverride> overrides;
    private final List<String> unmodifiedIncludes;
    private final boolean reuseSharedCompat;
    private final GitShaderSource gitSource;
    private final Set<Path> processed = new HashSet<>();
    private final Set<Path> processing = new HashSet<>();
    private boolean niagaraEmitter;
    private final boolean niagaraAsyncTrace;
    private final boolean lowerAsyncTraceCounts;
    private boolean flattenedNiagaraView;
    private boolean flattenedNiagaraSceneTextures;
    private boolean flattenedNiagaraSubstrate;
    private final boolean niagaraMeshScene;
    private final boolean niagaraMeshCulling;
    private final boolean niagaraGaussianSplat;
    private final boolean niagaraSplatHalf;
    private final boolean textureMipBounds;
    private final List<String> niagaraTextureLoads;
    private final List<String> niagaraRasterizationGrids;
    private final List<String> niagaraCollectionGrids;
    private final List<String> niagaraCollectionArrays;
    private final List<String> niagaraIntRenderTargets;
    private final boolean sparsePageLoads;
    private final List<String> niagaraCubeWrites;
    private final List<String> niagaraStaticMeshColors;
    private final List<String> niagaraSharedMeshFields;
    private final List<String> niagaraRigidMeshFields;
    private final List<String> niagaraSkeletalMeshColors;
    private final List<String> niagaraDataChannelReaders;
    private final List<String> niagaraDataChannelWriters;
    private final List<String> niagaraSimCacheReaders;

    ShaderSourceStager(
            final Path ueRoot,
            final Path compatDirectory,
            final Path stageRoot,
            final ShaderManifest manifest
    ) {
        this(ueRoot, compatDirectory, stageRoot, manifest, null, ShaderDirectoryMappings.EMPTY);
    }

    ShaderSourceStager(
            final Path ueRoot,
            final Path compatDirectory,
            final Path stageRoot,
            final ShaderManifest manifest,
            final ShaderManifest.Pass pass,
            final ShaderDirectoryMappings shaderDirectories
    ) {
        this(ueRoot, compatDirectory, stageRoot, manifest, pass, shaderDirectories, gitSource(manifest, stageRoot));
    }

    ShaderSourceStager(
            final Path ueRoot,
            final Path compatDirectory,
            final Path stageRoot,
            final ShaderManifest manifest,
            final ShaderManifest.Pass pass,
            final ShaderDirectoryMappings shaderDirectories,
            final GitShaderSource gitSource
    ) {
        this.shaderDirectories = shaderDirectories;
        this.ueRoot = ueRoot.toAbsolutePath().normalize();
        this.niagaraAsyncTrace = pass != null && pass.raw().has("niagara_async_trace_source")
                && pass.raw().get("niagara_async_trace_source").getAsBoolean();
        this.lowerAsyncTraceCounts = pass != null && ShaderManifest.resources(pass).stream()
                .anyMatch(resource -> "niagara_async_trace_counts".equals(resource.kind()));
        this.niagaraMeshScene = pass != null && pass.raw().has("niagara_mesh_scene_source")
                && pass.raw().get("niagara_mesh_scene_source").getAsBoolean();
        this.niagaraMeshCulling = pass != null && pass.raw().has("niagara_mesh_scene_culling_source")
                && pass.raw().get("niagara_mesh_scene_culling_source").getAsBoolean();
        this.niagaraGaussianSplat = pass != null && pass.raw().has("niagara_gaussian_splat_source")
                && pass.raw().get("niagara_gaussian_splat_source").getAsBoolean();
        this.niagaraSplatHalf = pass != null && pass.raw().has("niagara_gaussian_splat_half")
                && pass.raw().get("niagara_gaussian_splat_half").getAsBoolean();
        this.textureMipBounds = pass != null && pass.raw().has("texture_mip_bounds")
                && pass.raw().get("texture_mip_bounds").getAsBoolean();
        niagaraTextureLoads = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> !resource.uav() && ("niagara_texture_2d_array".equals(resource.kind())
                        || "niagara_render_target_2d_array".equals(resource.kind()) || "niagara_texture_volume".equals(resource.kind())
                        || "niagara_render_target_volume".equals(resource.kind()) || "niagara_sparse_volume_atlas".equals(resource.kind())
                        || "niagara_vector_field".equals(resource.kind())))
                .map(ShaderManifest.Resource::name).toList();
        sparsePageLoads = pass != null && ShaderManifest.resources(pass).stream()
                .anyMatch(resource -> "niagara_sparse_volume_table".equals(resource.kind()));
        niagaraRasterizationGrids = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> !resource.uav() && "niagara_rasterization_grid".equals(resource.kind()))
                .map(ShaderManifest.Resource::name).toList();
        niagaraCollectionGrids = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> resource.uav() && NiagaraGrid3DShaderFormats.IMAGE_KIND.equals(resource.kind()))
                .map(ShaderManifest.Resource::name).toList();
        niagaraCollectionArrays = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> resource.uav() && NiagaraGrid3DShaderFormats.ARRAY_IMAGE_KIND.equals(resource.kind()))
                .map(ShaderManifest.Resource::name).toList();
        niagaraIntRenderTargets = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> resource.uav() && "niagara_int_render_target_2d".equals(resource.kind()))
                .map(ShaderManifest.Resource::name).toList();
        niagaraCubeWrites = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> resource.uav() && "niagara_render_target_cube".equals(resource.kind()))
                .map(ShaderManifest.Resource::name).toList();
        niagaraStaticMeshColors = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> "niagara_static_mesh_buffer".equals(resource.kind()) && resource.name().endsWith("_ColorBuffer"))
                .map(ShaderManifest.Resource::name).toList();
        niagaraSharedMeshFields = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> NiagaraSharedMeshDistanceSource.KIND.equals(resource.kind()))
                .map(ShaderManifest.Resource::name).toList();
        niagaraRigidMeshFields = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> NiagaraSharedMeshDistanceSource.RIGID_KIND.equals(resource.kind()))
                .map(ShaderManifest.Resource::name).toList();
        niagaraSkeletalMeshColors = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> "niagara_skeletal_mesh_buffer".equals(resource.kind()) && resource.name().endsWith("_MeshColorBuffer"))
                .map(ShaderManifest.Resource::name).toList();
        niagaraDataChannelReaders = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> "niagara_data_channel_read_count".equals(resource.kind()) && resource.name().endsWith("_NDCInstanceCount"))
                .map(resource -> resource.name().substring(0, resource.name().length() - "_NDCInstanceCount".length())).toList();
        niagaraDataChannelWriters = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> "niagara_data_channel_write_count".equals(resource.kind()) && resource.name().endsWith("_NDCGpuCount"))
                .map(resource -> resource.name().substring(0, resource.name().length() - "_NDCGpuCount".length())).toList();
        niagaraSimCacheReaders = pass == null ? List.of() : ShaderManifest.resources(pass).stream()
                .filter(resource -> "niagara_sim_cache".equals(resource.kind()))
                .map(resource -> resource.name().substring(0, resource.name().length() - "_CacheData".length())).toList();
        this.cacheRoot = manifest.cacheRoot() == null
                ? null
                : safePath(manifest.cacheRoot());
        this.compatDirectory = compatDirectory.toAbsolutePath().normalize();
        this.stageRoot = stageRoot.toAbsolutePath().normalize();
        final Map<String, ShaderManifest.IncludeOverride> combined = new LinkedHashMap<>();
        for (var override : manifest.includeOverrides()) combined.put(override.from(), override);
        if (pass != null && pass.raw().has("include_overrides")) {
            for (var item : pass.raw().getAsJsonArray("include_overrides")) {
                var override = item.getAsJsonObject();
                String from = override.get("from").getAsString(), to = override.get("to").getAsString();
                combined.put(from, new ShaderManifest.IncludeOverride(from, to));
            }
        }
        this.overrides = List.copyOf(combined.values());
        this.unmodifiedIncludes = manifest.unmodifiedIncludes();
        this.reuseSharedCompat = manifest.reuseSharedCompat();
        this.gitSource = gitSource;
    }

    static GitShaderSource gitSource(ShaderManifest manifest, Path temporaryDirectory) {
        Path root = manifest.cacheRoot() == null ? null : safePath(manifest.cacheRoot());
        return root != null && Files.exists(root.resolve(".git"))
                && manifest.engineHead() != null && manifest.engineHead().matches("[0-9a-fA-F]{40}")
                ? new GitShaderSource(root, manifest.engineHead(), temporaryDirectory) : null;
    }

    StagedSource stage(
            final Path sourceRelative,
            final Map<String, String> imageFormats
    ) {
        return stage(sourceRelative, imageFormats, Map.of());
    }

    StagedSource stage(
            final Path sourceRelative,
            final Map<String, String> imageFormats,
            final Map<String, String> bufferLowerings
    ) {
        return stage(sourceRelative, imageFormats, bufferLowerings, Map.of(), false, false, false, List.of(), List.of(), List.of(), List.of());
    }

    StagedSource stage(
            final Path sourceRelative,
            final Map<String, String> imageFormats,
            final Map<String, String> bufferLowerings,
            final Map<String, Path> generatedIncludes,
            final boolean niagaraPackedHalf,
            final boolean niagaraSortPackedHalf,
            final boolean niagaraRibbonPackedHalf,
            final List<String> preIncludes,
            final List<String> uint8Arrays,
            final List<String> sharedArrayReaders,
            final List<String> particleReadHalfBuffers
    ) {
        try {
            Files.createDirectories(this.stageRoot);
            this.niagaraEmitter = this.niagaraAsyncTrace || this.niagaraGaussianSplat
                    || generatedIncludes.containsKey("/Engine/Generated/NiagaraEmitterInstance.ush");
            copyCompat();
            if (this.niagaraMeshScene) {
                generateMeshSceneUniform("Engine/Shaders/Generated/LumenMeshParticleUniforms.ush", "NiagaraParticleData");
                generateMeshSceneUniform("Engine/Shaders/Generated/LumenMeshRendererUniforms.ush", "NiagaraMeshData");
            }
            copyListedIncludes();
            for (Map.Entry<String, Path> generated : generatedIncludes.entrySet()) {
                final String relative = normalizeRelative(generated.getKey());
                if (!relative.startsWith("Engine/Shaders/Generated/")) {
                    throw new BakeException("Generated include has an invalid UE virtual path: " + generated.getKey());
                }
                final Path source = generated.getValue().toAbsolutePath().normalize();
                if (!Files.isRegularFile(source)) throw new BakeException("Generated include is missing: " + source);
                copy(source, this.stageRoot.resolve(relative));
            }
            final String relative = normalizeRelative(sourceRelative.toString());
            final Path sourceTarget = this.stageRoot.resolve(relative);
            if (!Files.isRegularFile(sourceTarget)) {
                copySource(relative);
            }
            stripPrimaryPragmaOnce(sourceTarget);
            if (!preIncludes.isEmpty()) {
                StringBuilder prefix = new StringBuilder();
                for (String include : preIncludes) {
                    String mapped = normalizeRelative(include);
                    if (!mapped.startsWith("Engine/Shaders/Generated/"))
                        throw new BakeException("Invalid generated shader preinclude " + include);
                    prefix.append("#include \"").append(include.replace('\\', '/')).append("\"\n");
                }
                Files.writeString(sourceTarget, prefix + Files.readString(sourceTarget, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            }
            processIncludes(sourceTarget);
            patchStatelessTemplates();
            updateNiagaraTransformDecomposition();
            if (relative.equals("Engine/Shaders/Private/VirtualTextureCompress.usf") && !imageFormats.isEmpty())
                RuntimeVirtualTextureImageSource.lower(sourceTarget);
            if (this.niagaraAsyncTrace) NiagaraAsyncTraceSource.apply(sourceTarget);
            if (this.textureMipBounds) addTextureMipBounds(sourceTarget);
            if (relative.equals("Engine/Shaders/Private/ShadingEnergyConservationTable.usf")) addShadingEnergyBounds(sourceTarget);
            if (this.flattenedNiagaraView || this.flattenedNiagaraSceneTextures || this.flattenedNiagaraSubstrate)
                flattenNiagaraUniformReferences();
            if (this.niagaraEmitter && this.flattenedNiagaraSceneTextures)
                NiagaraSceneTextureSource.repairLegacyGBufferCalls(this.stageRoot);
            applyBufferLowerings(bufferLowerings);
            if (bufferLowerings.containsKey("RWNDIDebugDrawLineVertex")) NiagaraDebugDrawShaderSource.lower(this.stageRoot);
            NiagaraNeighborGridLowering.apply(this.stageRoot,
                    bufferLowerings.keySet().stream().filter(NiagaraNeighborGridLowering::isGridBuffer).toList());
            NiagaraNeighborQueryLowering.apply(this.stageRoot,
                    bufferLowerings.keySet().stream().filter(NiagaraNeighborQueryLowering::isQueryBuffer).toList());
            if (!niagaraRasterizationGrids.isEmpty()) NiagaraRasterizationGridLowering.apply(this.stageRoot, niagaraRasterizationGrids);
            if (!niagaraCollectionGrids.isEmpty()) NiagaraGrid3DShaderLowering.apply(this.stageRoot, niagaraCollectionGrids);
            if (!niagaraCollectionArrays.isEmpty()) NiagaraGrid2DShaderLowering.apply(this.stageRoot, niagaraCollectionArrays);
            if (!niagaraIntRenderTargets.isEmpty()) NiagaraIntRenderTarget2DLowering.apply(this.stageRoot, niagaraIntRenderTargets);
            if (!niagaraDataChannelReaders.isEmpty() || !niagaraDataChannelWriters.isEmpty())
                NiagaraDataChannelShaderSource.lower(this.stageRoot, niagaraDataChannelReaders, niagaraDataChannelWriters);
            if (!niagaraSimCacheReaders.isEmpty()) updateSimCacheFrameHeaders();
            if (this.lowerAsyncTraceCounts) NiagaraAsyncTraceSource.lowerCountParameters(this.stageRoot);
            if (!niagaraStaticMeshColors.isEmpty() || !niagaraSkeletalMeshColors.isEmpty()) {
                usePackedMeshAttributes();
                lowerStaticMeshUvParameters();
            }
            if (!niagaraSharedMeshFields.isEmpty() || !niagaraRigidMeshFields.isEmpty())
                NiagaraSharedMeshDistanceSource.lower(this.stageRoot, niagaraSharedMeshFields, niagaraRigidMeshFields);
            if (niagaraPackedHalf) NiagaraHalfBufferLowering.apply(this.stageRoot);
            if (niagaraSortPackedHalf) NiagaraSortHalfBufferLowering.apply(this.stageRoot);
            if (niagaraRibbonPackedHalf) NiagaraRibbonHalfBufferLowering.apply(this.stageRoot);
            if (this.niagaraMeshScene)
                NiagaraParticleHalfAccess.apply(this.stageRoot, "NiagaraParticleDataHalf", "LfxMeshHalf");
            if (this.niagaraMeshCulling)
                NiagaraParticleHalfAccess.apply(this.stageRoot, "NiagaraParticleDataHalf", "LfxMeshCullHalf");
            if (this.niagaraSplatHalf)
                NiagaraParticleHalfAccess.apply(this.stageRoot, "NiagaraParticleDataHalf", "LfxSplatHalf");
            if (!uint8Arrays.isEmpty()) NiagaraArrayByteBufferLowering.apply(this.stageRoot, uint8Arrays);
            if (!sharedArrayReaders.isEmpty()) NiagaraSharedArrayReadLowering.apply(this.stageRoot, sharedArrayReaders);
            if (!particleReadHalfBuffers.isEmpty()) NiagaraParticleReadHalfLowering.apply(this.stageRoot, particleReadHalfBuffers);
            if (!niagaraTextureLoads.isEmpty()) NiagaraTextureLoadLowering.apply(this.stageRoot, niagaraTextureLoads);
            if (sparsePageLoads) NiagaraTextureLoadLowering.lowerSparsePageLoads(this.stageRoot);
            if (!niagaraCubeWrites.isEmpty()) NiagaraTextureLoadLowering.lowerCubeWrites(this.stageRoot, niagaraCubeWrites);
            final Map<String, Path> injected = injectImageFormats(imageFormats);
            final List<Path> includeDirectories = List.of(
                    this.stageRoot,
                    this.stageRoot.resolve("Engine/Shaders/Private"),
                    this.stageRoot.resolve("Engine/Shaders/Public"),
                    this.stageRoot.resolve("Engine/Shaders/Shared"),
                    sourceTarget.getParent()
            );
            return new StagedSource(sourceTarget, this.stageRoot, includeDirectories, injected);
        } catch (IOException error) {
            throw new BakeException("Unable to stage shader source " + sourceRelative + " in " + this.stageRoot, error);
        }
    }

    private void updateNiagaraTransformDecomposition() throws IOException {
        Path path = stageRoot.resolve("Engine/Plugins/FX/Niagara/Shaders/Private/NiagaraTransformUtils.ush");
        if (!Files.isRegularFile(path)) return;
        String source = Files.readString(path, StandardCharsets.UTF_8);
        // UE 5.9 fixed the Z basis to read the third matrix column rather than its translation lane.
        String updated = source.replace("InMatrix._m02_m12_m23", "InMatrix._m02_m12_m22");
        if (!source.equals(updated)) Files.writeString(path, updated, StandardCharsets.UTF_8);
    }

    private void updateSimCacheFrameHeaders() throws IOException {
        Path translated = stageRoot.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String original = Files.readString(translated, StandardCharsets.UTF_8);
        String source = original;
        for (String symbol : niagaraSimCacheReaders) {
            // UE 5.9 applies the four-word header stride to the complete frame/emitter index.
            String numEmitters = symbol + "_NumEmitters";
            Pattern legacy = Pattern.compile("(\\buint\\s+FrameDataOffset\\s*=\\s*)\\(\\s*FrameIndex\\s*\\*\\s*4\\s*\\*\\s*"
                    + Pattern.quote(numEmitters) + "\\s*\\)\\s*\\+\\s*EmitterIndex\\s*;");
            source = legacy.matcher(source).replaceAll("$1((FrameIndex * " + numEmitters + ") + EmitterIndex) * 4;");
        }
        if (!source.equals(original)) Files.writeString(translated, source, StandardCharsets.UTF_8);
    }

    private void usePackedMeshAttributes() throws IOException {
        Path translated = stageRoot.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(translated, StandardCharsets.UTF_8);
        // The host buffer already stores the decoded source's RGBA channel order.
        String packed = source;
        for (String name : niagaraStaticMeshColors) {
            var access = Pattern.compile("(\\b" + Pattern.quote(name) + "\\s*\\[\\s*Vertex\\s*\\])"
                    + "\\s+FMANUALFETCH_COLOR_COMPONENT_SWIZZLE\\b");
            packed = access.matcher(packed).replaceAll("$1");
            packed = NiagaraStaticMeshDistanceSource.lower(packed, name.substring(0, name.length() - "_ColorBuffer".length()));
        }
        for (String name : niagaraSkeletalMeshColors) {
            String symbol = name.substring(0, name.length() - "_MeshColorBuffer".length());
            var colors = Pattern.compile("(\\b" + Pattern.quote(name) + "\\s*\\[[^\\]]+\\])"
                    + "\\s+FMANUALFETCH_COLOR_COMPONENT_SWIZZLE\\b");
            packed = colors.matcher(packed).replaceAll("$1");
            var basis = Pattern.compile("\\bTangentBias\\s*\\(\\s*(" + Pattern.quote(symbol)
                    + "_(?:MeshTangentBuffer|DeformedTangentBuffer)\\s*\\[[^\\]]+\\]\\s*\\.[xyzw]+)\\s*\\)");
            packed = basis.matcher(packed).replaceAll("$1");
        }
        if (!source.equals(packed)) Files.writeString(translated, packed, StandardCharsets.UTF_8);
    }

    private void lowerStaticMeshUvParameters() throws IOException {
        Path include = stageRoot.resolve("Engine/Plugins/FX/Niagara/Shaders/Private/Experimental/NiagaraUvMappingUtils.ush");
        if (!Files.isRegularFile(include)) return;
        String source = Files.readString(include, StandardCharsets.UTF_8);
        int start = source.indexOf("struct UvMappingParameters");
        int end = start < 0 ? -1 : source.indexOf("};", start);
        if (end < 0) throw new BakeException("Source Niagara UV mapping parameter declaration is missing");
        String declaration = source.substring(start, end).replaceAll("\\bBuffer\\s*<", "StructuredBuffer<");
        Files.writeString(include, source.substring(0, start) + declaration + source.substring(end), StandardCharsets.UTF_8);
    }

    private void generateMeshSceneUniform(String relative, String name) throws IOException {
        ShaderManifest.IncludeOverride override = findOverride(relative);
        if (override == null) throw new BakeException("Mesh scene uniform requires its graphics source: " + relative);
        String graphics = Files.readString(compatFile(override), StandardCharsets.UTF_8);
        Path target = this.stageRoot.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, NiagaraMeshSceneSource.uniform(graphics, name), StandardCharsets.UTF_8);
    }

    private void copyCompat() throws IOException {
        for (ShaderManifest.IncludeOverride override : this.overrides) {
            final Path compat = compatFile(override);
            final Path target = this.stageRoot.resolve(normalizeRelative(override.from()));
            copy(compat, target);
            if (override.from().replace('\\', '/').startsWith("Engine/Shaders/Public/")) {
                copy(compat, this.stageRoot.resolve("Engine/Shaders/Private")
                        .resolve(target.getFileName()));
            }
        }
        copyCompatDirectory(this.compatDirectory);
        Path repositoryRoot = this.compatDirectory;
        for (int i = 0; i < 4 && repositoryRoot != null; i++) {
            repositoryRoot = repositoryRoot.getParent();
        }
        if (this.reuseSharedCompat && repositoryRoot != null) {
            copyCompatDirectory(repositoryRoot.resolve("tools/shaders/shared/compat"));
        }
    }

    private void copyCompatDirectory(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path header : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ush"))
                    .toList()) {
                final Path privateTarget = this.stageRoot.resolve("Engine/Shaders/Private")
                        .resolve(header.getFileName());
                if (!Files.exists(privateTarget)) {
                    copy(header, privateTarget);
                }
            }
        }
    }

    private void copyListedIncludes() throws IOException {
        for (String include : this.unmodifiedIncludes) {
            final String relative = normalizeRelative(include);
            final Path target = this.stageRoot.resolve(relative);
            if (!Files.exists(target)) {
                copySource(relative);
            }
        }
    }

    private void copySource(final String relative) throws IOException {
        if (shaderDirectories.isMapped(relative)) {
            Path source = shaderDirectories.source(relative);
            if (!Files.isRegularFile(source)) throw new BakeException("Mapped UE shader source is missing: " + source);
            copy(source, this.stageRoot.resolve(relative));
            return;
        }
        final Path primary = this.ueRoot.resolve(relative);
        final Path cached = this.cacheRoot == null ? null : this.cacheRoot.resolve(relative);
        if (Files.isRegularFile(primary)) { copy(primary, this.stageRoot.resolve(relative)); return; }
        if (this.gitSource != null && this.gitSource.contains(relative)) {
            Path target = this.stageRoot.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, this.gitSource.read(relative));
            return;
        }
        if (this.gitSource == null && cached != null && Files.isRegularFile(cached)) { copy(cached, this.stageRoot.resolve(relative)); return; }
        throw new BakeException("UE shader source is missing: " + primary
                + (cached == null ? "" : " (cache fallback " + cached + ")"));
    }

    private boolean sourceExists(final String relative) throws IOException {
        if (shaderDirectories.isMapped(relative)) return Files.isRegularFile(shaderDirectories.source(relative));
        return Files.isRegularFile(this.ueRoot.resolve(relative))
                || (this.gitSource != null ? this.gitSource.contains(relative)
                        : this.cacheRoot != null && Files.isRegularFile(this.cacheRoot.resolve(relative)));
    }

    private static Path safePath(final String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void processIncludes(final Path file) throws IOException {
        final Path normalized = file.toAbsolutePath().normalize();
        if (this.processed.contains(normalized) || this.processing.contains(normalized)) {
            return;
        }
        if (!this.processing.add(normalized)) {
            throw new BakeException("Include cycle while staging: " + normalized);
        }
        try {
            final List<String> lines = Files.readAllLines(normalized, StandardCharsets.UTF_8);
            final List<String> rewritten = new ArrayList<>(lines.size());
            boolean changed = false;
            for (String line : lines) {
                final Matcher matcher = INCLUDE.matcher(line);
                if (!matcher.matches()) {
                    rewritten.add(line);
                    continue;
                }
                final String include = matcher.group(1).replace('\\', '/');
                final Path target;
                final String replacement;
                if (include.startsWith("/")) {
                    // UE injects platform-specific mappings. DXC evaluates the guards for our desktop target.
                    if (include.startsWith("/Platform/")) {
                        rewritten.add(line);
                        continue;
                    }
                    final String relative = mapAbsolute(include);
                    if (relative == null) {
                        throw new BakeException("Unsupported UE include '" + include + "' in " + normalized);
                    }
                    replacement = "#include \"" + relative + "\"" + matcher.group(2);
                    if (this.niagaraEmitter && !canStage(relative)) {
                        rewritten.add(replacement);
                        changed = changed || !replacement.equals(line);
                        continue;
                    }
                    target = ensureStaged(relative, normalized);
                } else {
                    target = resolveRelative(normalized, include);
                    if (target == null) {
                        if (this.niagaraEmitter) {
                            rewritten.add(line);
                            continue;
                        }
                        throw new BakeException("Unable to resolve include '" + include + "' from " + normalized);
                    }
                    replacement = "#include \"" + slash(this.stageRoot.relativize(target)) + "\""
                            + matcher.group(2);
                }
                rewritten.add(replacement);
                changed = changed || !replacement.equals(line);
                processIncludes(target);
            }
            if (changed) {
                Files.write(normalized, rewritten, StandardCharsets.UTF_8);
            }
            this.processed.add(normalized);
        } finally {
            this.processing.remove(normalized);
        }
    }

    private boolean canStage(final String relative) throws IOException {
        Path target = this.stageRoot.resolve(relative).normalize();
        if (!target.startsWith(this.stageRoot)) throw new BakeException("Include escapes staging root: " + relative);
        if (Files.isRegularFile(target) || findOverride(relative) != null || sourceExists(relative)) return true;
        return Set.of("Engine/Shaders/Generated/GeneratedUniformBuffers.ush",
                "Engine/Shaders/Generated/GeneratedInstancedStereo.ush",
                "Engine/Shaders/Generated/UniformBuffers/View.ush",
                "Engine/Shaders/Generated/UniformBuffers/InstancedView.ush",
                "Engine/Shaders/Generated/UniformBuffers/SceneTexturesStruct.ush",
                "Engine/Shaders/Generated/UniformBuffers/SubstratePublic.ush").contains(relative);
    }

    private Path ensureStaged(final String relative, final Path including) throws IOException {
        final Path target = this.stageRoot.resolve(relative).normalize();
        if (!target.startsWith(this.stageRoot)) {
            throw new BakeException("Include escapes staging root: " + relative);
        }
        if (!Files.isRegularFile(target)) {
            if (this.niagaraEmitter && relative.equals("Engine/Shaders/Generated/GeneratedUniformBuffers.ush")) {
                Files.createDirectories(target.getParent());
                // Common.ush references DrawRectangleParameters inside unreferenced vertex helpers.
                Files.writeString(target, "#include \"/Engine/Generated/UniformBuffers/View.ush\"\n"
                        + "#include \"/Engine/Generated/UniformBuffers/SceneTexturesStruct.ush\"\n"
                        + "struct FDrawRectangleParameters { float4 PosScaleBias; float4 UVScaleBias; float4 InvTargetSizeAndTextureSize; };\n"
                        + "static const FDrawRectangleParameters DrawRectangleParameters = (FDrawRectangleParameters)0;\n",
                        StandardCharsets.UTF_8);
                return target;
            }
            if (this.niagaraEmitter && relative.equals("Engine/Shaders/Generated/UniformBuffers/SceneTexturesStruct.ush")) {
                Files.createDirectories(target.getParent());
                Files.writeString(target, NiagaraSceneTextureSource.generate(engineSource(
                        "Engine/Source/Runtime/Engine/Public/SceneTexturesConfig.h")), StandardCharsets.UTF_8);
                this.flattenedNiagaraSceneTextures = true;
                return target;
            }
            if (this.niagaraMeshScene && relative.equals("Engine/Shaders/Generated/LumenMeshSceneTransforms.ush")) {
                Files.createDirectories(target.getParent());
                Files.writeString(target, NiagaraMeshSceneSource.codec(engineSource(
                        "Engine/Shaders/Private/SceneData.ush")), StandardCharsets.UTF_8);
                return target;
            }
            if ((this.niagaraMeshScene || this.niagaraMeshCulling) && relative.equals("Engine/Shaders/Generated/LumenMeshSceneFrustum.ush")) {
                Files.createDirectories(target.getParent());
                Files.writeString(target, NiagaraMeshSceneSource.frustum(engineSource(
                        "Engine/Shaders/Private/Nanite/NaniteHZBCull.ush")), StandardCharsets.UTF_8);
                return target;
            }
            if (this.niagaraEmitter && relative.equals("Engine/Shaders/Generated/UniformBuffers/SubstratePublic.ush")) {
                Files.createDirectories(target.getParent());
                Files.writeString(target, NiagaraSubstrateSource.generate(engineSource(
                        "Engine/Source/Runtime/Renderer/Private/Substrate/Substrate.h")), StandardCharsets.UTF_8);
                this.flattenedNiagaraSubstrate = true;
                return target;
            }
            if (this.niagaraEmitter && relative.equals("Engine/Shaders/Generated/UniformBuffers/InstancedView.ush")) {
                Files.createDirectories(target.getParent());
                Files.writeString(target, "#pragma once\n#if INSTANCED_STEREO || MOBILE_MULTI_VIEW\n#error Niagara view requires monoscopic rendering\n#endif\n", StandardCharsets.UTF_8);
                return target;
            }
            if (this.niagaraEmitter && (relative.equals("Engine/Shaders/Generated/GeneratedInstancedStereo.ush")
                    || relative.equals("Engine/Shaders/Generated/UniformBuffers/View.ush"))) {
                final String source = engineSource("Engine/Source/Runtime/Engine/Public/SceneView.h");
                Files.createDirectories(target.getParent());
                if (relative.endsWith("/View.ush")) {
                    this.flattenedNiagaraView = true;
                    Files.writeString(target, NiagaraViewStateSource.viewUniform(source), StandardCharsets.UTF_8);
                } else Files.writeString(target, NiagaraViewStateSource.generate(source), StandardCharsets.UTF_8);
                return target;
            }
            final ShaderManifest.IncludeOverride override = findOverride(relative);
            if (override != null) {
                final Path compat = compatFile(override);
                copy(compat, target);
                return target;
            }
            copySource(relative);
        }
        return target;
    }

    private String engineSource(final String sourcePath) throws IOException {
        final Path primary = this.ueRoot.resolve(sourcePath);
        final Path cached = this.cacheRoot == null ? null : this.cacheRoot.resolve(sourcePath);
        if (Files.isRegularFile(primary)) return Files.readString(primary, StandardCharsets.UTF_8);
        if (this.gitSource != null && this.gitSource.contains(sourcePath))
            return new String(this.gitSource.read(sourcePath), StandardCharsets.UTF_8);
        if (this.gitSource == null && cached != null && Files.isRegularFile(cached)) return Files.readString(cached, StandardCharsets.UTF_8);
        throw new BakeException("UE source is required to generate Niagara shader parameters: " + sourcePath);
    }

    private void flattenNiagaraUniformReferences() throws IOException {
        final Pattern view = this.niagaraGaussianSplat
                ? Pattern.compile("(?<![A-Za-z0-9_.])(?:View|PrimaryView|ResolvedView|GaussianViewData)\\s*\\.\\s*([A-Za-z_]\\w*)")
                : Pattern.compile("(?<![A-Za-z0-9_.])View\\s*\\.\\s*([A-Za-z_]\\w*)");
        final Pattern resolveView = Pattern.compile("^\\s*(?:(?:const|static|ViewState)\\s+)*ResolvedView\\s*=\\s*ResolveView\\s*\\(\\s*\\)\\s*;\\s*$");
        final Pattern scene = Pattern.compile("(?<![A-Za-z0-9_.])SceneTexturesStruct\\s*\\.\\s*([A-Za-z_]\\w*)");
        final Pattern substrate = Pattern.compile("(?<![A-Za-z0-9_.])(?:SubstratePublic|SubstratePublicStruct)\\s*\\.\\s*([A-Za-z_]\\w*)");
        try (Stream<Path> paths = Files.walk(this.stageRoot)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".ush") && !name.endsWith(".usf")) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                List<String> converted = new ArrayList<>(lines.size());
                boolean changed = false;
                for (String original : lines) {
                    if (INCLUDE.matcher(original).matches() || original.stripLeading().startsWith("#line")) {
                        converted.add(original);
                        continue;
                    }
                    if (this.niagaraGaussianSplat && resolveView.matcher(original).matches()) {
                        changed = true;
                        continue;
                    }
                    String line = this.flattenedNiagaraView ? view.matcher(original).replaceAll("View_$1") : original;
                    if (this.flattenedNiagaraSceneTextures) line = scene.matcher(line).replaceAll("SceneTexturesStruct_$1");
                    if (this.flattenedNiagaraSubstrate) line = substrate.matcher(line).replaceAll("SubstratePublic_$1");
                    converted.add(line);
                    changed |= !line.equals(original);
                }
                if (changed) Files.write(file, converted, StandardCharsets.UTF_8);
            }
        }
    }

    private Path resolveRelative(final Path including, final String include) throws IOException {
        final Path direct = including.getParent().resolve(include).normalize();
        if (Files.isRegularFile(direct) && direct.startsWith(this.stageRoot)) {
            return direct;
        }
        if (direct.startsWith(this.stageRoot)) {
            final String relative = slash(this.stageRoot.relativize(direct));
            if (findOverride(relative) != null || sourceExists(relative)) {
                return ensureStaged(relative, including);
            }
        }
        for (Path root : List.of(
                this.stageRoot,
                this.stageRoot.resolve("Engine/Shaders/Private"),
                this.stageRoot.resolve("Engine/Shaders/Public"),
                this.stageRoot.resolve("Engine/Shaders/Shared")
        )) {
            final Path candidate = root.resolve(include).normalize();
            if (Files.isRegularFile(candidate) && candidate.startsWith(this.stageRoot)) {
                return candidate;
            }
        }
        for (String relative : List.of(
                "Engine/Shaders/Private/" + include,
                "Engine/Shaders/Public/" + include,
                "Engine/Shaders/Shared/" + include
        )) {
            final String normalized = normalizeRelative(relative);
            if (sourceExists(normalized)) {
                final Path target = this.stageRoot.resolve(normalized);
                copySource(normalized);
                return target;
            }
        }
        return null;
    }

    private void applyBufferLowerings(final Map<String, String> bufferLowerings) throws IOException {
        if (bufferLowerings.isEmpty()) {
            return;
        }
        completeLegacyPhysicsFieldInputs(bufferLowerings.keySet());
        final List<Path> files;
        try (Stream<Path> stream = Files.walk(this.stageRoot)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".usf")
                            || path.getFileName().toString().endsWith(".ush"))
                    .sorted()
                    .toList();
        }
        final Set<String> found = new HashSet<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            boolean changed = false;
            for (Map.Entry<String, String> lowering : bufferLowerings.entrySet()) {
                final String descriptor = lowering.getValue();
                if (descriptor.startsWith(NiagaraChaosBufferSource.PREFIX)) {
                    String converted = NiagaraChaosBufferSource.lower(text, lowering.getKey(),
                            descriptor.substring(NiagaraChaosBufferSource.PREFIX.length()));
                    if (!text.equals(converted)) {
                        found.add(lowering.getKey());
                        text = converted;
                        changed = true;
                    }
                    continue;
                }
                final boolean write = descriptor.equals("uint") || descriptor.startsWith("write:");
                final String type = descriptor.equals("uint") ? "uint" : descriptor.substring(descriptor.indexOf(':') + 1);
                if (!Set.of("float", "float2", "float4", "int", "uint", "uint2", "uint4").contains(type)
                        || !(descriptor.equals("uint") || descriptor.startsWith("read:") || descriptor.startsWith("write:")))
                    throw new BakeException("Invalid declared buffer lowering for " + lowering.getKey());
                final String originalType = write ? "RWBuffer" : "Buffer";
                final String loweredType = write ? "RWStructuredBuffer" : "StructuredBuffer";
                final String name = Pattern.quote(lowering.getKey());
                final Pattern lowered = Pattern.compile(
                        "(?<![A-Za-z0-9_])" + loweredType + "\\s*<\\s*" + type + "\\s*>\\s+" + name + "\\b"
                );
                if (lowered.matcher(text).find()) {
                    found.add(lowering.getKey());
                    continue;
                }
                final Pattern declaration = Pattern.compile(
                        "(?<![A-Za-z0-9_])" + originalType + "\\s*<\\s*" + type + "\\s*>\\s+" + name + "\\b"
                );
                final java.util.regex.Matcher matcher = declaration.matcher(text);
                if (matcher.find()) {
                    text = matcher.replaceAll(java.util.regex.Matcher.quoteReplacement(
                            loweredType + "<" + type + "> " + lowering.getKey()
                    ));
                    found.add(lowering.getKey());
                    changed = true;
                }
            }
            if (changed) {
                Files.writeString(file, text, StandardCharsets.UTF_8);
            }
        }
        patchPhysicsFieldEvalBuffers(found);
        final List<String> missing = bufferLowerings.keySet().stream()
                .filter(name -> !found.contains(name))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new BakeException(
                    "Declared typed-buffer ABI lowering was not found in staged sources: " + missing
            );
        }
    }

    private static void addTextureMipBounds(Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        Pattern signature = Pattern.compile("\\bvoid\\s+MainCS\\s*\\(\\s*uint3\\s+DT_ID\\s*:\\s*SV_DispatchThreadID\\s*\\)\\s*\\{");
        Matcher matcher = signature.matcher(text);
        if (!matcher.find()) throw new BakeException("RenderCore mip shader has no expected compute entry point");
        int end = matcher.end();
        if (matcher.find()) throw new BakeException("RenderCore mip shader has multiple compute entry points");
        String bounds = "\n    uint LfxMipWidth, LfxMipHeight;\n"
                + "    MipOutUAV.GetDimensions(LfxMipWidth, LfxMipHeight);\n"
                + "    if (any(DT_ID.xy >= uint2(LfxMipWidth, LfxMipHeight))) return;\n";
        Files.writeString(source, text.substring(0, end) + bounds + text.substring(end), StandardCharsets.UTF_8);
    }

    private static void addShadingEnergyBounds(Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String start = "const float3 Index = (float3(DispatchThreadId) + 0.5) / float(EnergyTableResolution);";
        int position = text.indexOf(start);
        if (position < 0 || text.indexOf(start, position + start.length()) >= 0)
            throw new BakeException("Unexpected native shading-energy table entry point");
        // The max-lobe dispatch has a fixed 32x32 extent and four synchronized NoV lanes.
        String bounds = """
                #if BUILD_ENERGY_TABLE != 4
                    if (any(DispatchThreadId.xy >= EnergyTableResolution)) return;
                #endif
                    """;
        Files.writeString(source, text.substring(0, position) + bounds + text.substring(position), StandardCharsets.UTF_8);
    }

    private Map<String, Path> injectImageFormats(final Map<String, String> imageFormats) throws IOException {
        if (imageFormats.isEmpty()) {
            return Map.of();
        }
        final List<Path> files;
        try (Stream<Path> stream = Files.walk(this.stageRoot)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".usf")
                            || path.getFileName().toString().endsWith(".ush"))
                    .sorted()
                    .toList();
        }
        final Set<String> found = new HashSet<>();
        final Map<String, Path> injected = new LinkedHashMap<>();
        for (Path file : files) {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            final List<String> rewritten = new ArrayList<>(lines.size());
            boolean changed = false;
            String previous = "";
            for (String line : lines) {
                final Matcher matcher = RW_DECLARATION.matcher(line);
                if (matcher.matches()) {
                    final String name = matcher.group(4);
                    final String format = imageFormats.get(name);
                    if (format != null) {
                        found.add(name);
                        String declaration = line;
                        if (!line.contains("vk::image_format") && !previous.contains("vk::image_format")) {
                            declaration = matcher.group(1) + matcher.group(2)
                                    + "[[vk::image_format(\"" + format + "\")]] "
                                    + matcher.group(3) + name + matcher.group(5);
                        }
                        if (format.equals("unknown")) {
                            String writeOnly = "[[vk::ext_decorate(" + Spv.SpvDecorationNonReadable + ")]] ";
                            if (!declaration.contains(writeOnly.strip()) && !previous.contains(writeOnly.strip()))
                                declaration = matcher.group(1) + writeOnly + declaration.substring(matcher.group(1).length());
                        }
                        rewritten.add(declaration);
                        if (!declaration.equals(line)) {
                            changed = true;
                            injected.put(name, file);
                        }
                    } else {
                        rewritten.add(line);
                    }
                } else {
                    rewritten.add(line);
                }
                previous = line;
            }
            if (changed) {
                Files.write(file, rewritten, StandardCharsets.UTF_8);
            }
        }
        final List<String> missing = imageFormats.keySet().stream()
                .filter(name -> !found.contains(name))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new BakeException("Declared RW image formats were not found in staged sources: " + missing);
        }
        return Map.copyOf(injected);
    }

    private Path compatFile(final ShaderManifest.IncludeOverride override) {
        final List<Path> candidates = new ArrayList<>();
        Path root = this.compatDirectory;
        for (int i = 0; i < 4 && root != null; i++) {
            root = root.getParent();
        }
        if (root != null) {
            candidates.add(root.resolve(override.to()).normalize());
        }
        candidates.add(Path.of(override.to()).toAbsolutePath().normalize());
        candidates.add(this.compatDirectory.resolve(Path.of(override.to()).getFileName()));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new BakeException(
                "Shader compat override is missing: manifest to=" + override.to() + ", tried " + candidates
        );
    }

    private ShaderManifest.IncludeOverride findOverride(final String relative) {
        for (ShaderManifest.IncludeOverride override : this.overrides) {
            if (normalizeRelative(override.from()).equals(relative)) {
                return override;
            }
        }
        return null;
    }

    private String mapAbsolute(final String include) {
        String mappedSource = shaderDirectories.stagePath(include);
        if (mappedSource != null) return mappedSource;
        if (include.startsWith("/Engine/Private/")) {
            return "Engine/Shaders/Private/" + include.substring("/Engine/Private/".length());
        }
        if (include.startsWith("/Engine/Public/")) {
            return "Engine/Shaders/Public/" + include.substring("/Engine/Public/".length());
        }
        if (include.startsWith("/Engine/Shared/")) {
            return "Engine/Shaders/Shared/" + include.substring("/Engine/Shared/".length());
        }
        if (include.startsWith("/Engine/Generated/")) {
            return "Engine/Shaders/Generated/" + include.substring("/Engine/Generated/".length());
        }
        if (include.startsWith("/Plugin/FX/Niagara/")) {
            return "Engine/Plugins/FX/Niagara/Shaders/" + include.substring("/Plugin/FX/Niagara/".length());
        }
        if (include.startsWith("/Plugin/Runtime/HairStrands/")) {
            return "Engine/Plugins/Runtime/HairStrands/Shaders/" + include.substring("/Plugin/Runtime/HairStrands/".length());
        }
        if (include.startsWith("/Plugin/GaussianSplat/")) {
            return "Engine/Plugins/GaussianSplat/Shaders/" + include.substring("/Plugin/GaussianSplat/".length());
        }
        if (include.startsWith("/Plugin/")) {
            final String mapped = resolvePluginAbsolute(include.substring("/Plugin/".length()));
            if (mapped != null) {
                return mapped;
            }
        }
        if (include.startsWith("/Engine/Shaders/")) {
            return include.substring(1);
        }
        return null;
    }

    private String resolvePluginAbsolute(final String rest) {
        final String direct = "Engine/Plugins/" + rest;
        if (rest.contains("/Shaders/") || canStageQuietly(direct)) {
            return direct;
        }
        int firstSlash = rest.indexOf('/');
        if (firstSlash <= 0 || firstSlash == rest.length() - 1) {
            return null;
        }
        String fallback = null;
        int slash = firstSlash;
        while (slash > 0 && slash < rest.length() - 1) {
            final String candidate = "Engine/Plugins/" + rest.substring(0, slash) + "/Shaders/" + rest.substring(slash + 1);
            if (canStageQuietly(candidate)) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
            slash = rest.indexOf('/', slash + 1);
        }
        return fallback;
    }

    private boolean canStageQuietly(final String relative) {
        try {
            return canStage(relative);
        } catch (IOException | BakeException ignored) {
            return false;
        }
    }

    private String normalizeRelative(final String value) {
        final String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/")) {
            final String mapped = mapAbsolute(normalized);
            if (mapped == null) {
                throw new BakeException("Unsupported UE virtual path " + value);
            }
            return mapped;
        }
        final Path path = Path.of(normalized).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new BakeException("Invalid relative shader path " + value);
        }
        return slash(path);
    }

    private static String slash(final Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void copy(final Path source, final Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void stripPrimaryPragmaOnce(final Path sourceTarget) throws IOException {
        final String original = Files.readString(sourceTarget, StandardCharsets.UTF_8);
        final String stripped = original.replaceFirst("(?m)^[ \\t]*#pragma[ \\t]+once[ \\t]*\\r?\\n?", "");
        if (!stripped.equals(original)) {
            Files.writeString(sourceTarget, stripped, StandardCharsets.UTF_8);
        }
    }

    private void patchStatelessTemplates() throws IOException {
        final Path pre = this.stageRoot.resolve(
                "Engine/Plugins/FX/Niagara/Shaders/Private/Stateless/Template/GenerateCS_PreModules.ush");
        if (Files.isRegularFile(pre)) {
            final String text = Files.readString(pre, StandardCharsets.UTF_8);
            final String updated = text
                    .replace("TYPE NAME##;", "TYPE NAME;")
                    .replaceAll("(?m)^([ \\t]*#undef[ \\t]+PARTICLE_ATTRIBUTE)[ \\t]*$", "$1_OUTPUT");
            if (!updated.equals(text)) {
                Files.writeString(pre, updated, StandardCharsets.UTF_8);
            }
        }
        final Path post = this.stageRoot.resolve(
                "Engine/Plugins/FX/Niagara/Shaders/Private/Stateless/Template/GenerateCS_PostModules.ush");
        if (Files.isRegularFile(post)) {
            final String text = Files.readString(post, StandardCharsets.UTF_8);
            final String updated = text
                    .replace("##NAME##_Simulate(Particle);", "NAME##_Simulate(Particle);")
                    .replace("Particle.##NAME", "Particle.NAME")
                    .replaceAll("(?m)^([ \\t]*#undef[ \\t]+PARTICLE_ATTRIBUTE)[ \\t]*$", "$1_OUTPUT");
            if (!updated.equals(text)) {
                Files.writeString(post, updated, StandardCharsets.UTF_8);
            }
        }
        final Path dist = this.stageRoot.resolve(
                "Engine/Plugins/FX/Niagara/Shaders/Private/Stateless/NiagaraStatelessDistribution.ush");
        if (Files.isRegularFile(dist)) {
            final String text = Files.readString(dist, StandardCharsets.UTF_8);
            final String updated = text.replace("##NAMETYPE##(", "##NAMETYPE(");
            if (!updated.equals(text)) {
                Files.writeString(dist, updated, StandardCharsets.UTF_8);
            }
        }
    }

    private void patchPhysicsFieldEvalBuffers(final Set<String> found) throws IOException {
        if (found.stream().anyMatch(name -> name.endsWith("_ClipmapBuffer"))) {
            final Path shared = this.stageRoot.resolve("Engine/Shaders/Private/PhysicsFieldShared.ush");
            if (Files.isRegularFile(shared)) {
                String text = Files.readString(shared, StandardCharsets.UTF_8);
                String updated = text.replaceAll("\\bBuffer<float>\\s+ClipmapBuffer", "StructuredBuffer<float> ClipmapBuffer");
                if (!text.equals(updated)) Files.writeString(shared, updated, StandardCharsets.UTF_8);
            }
        }
        final Path eval = this.stageRoot.resolve("Engine/Shaders/Private/PhysicsFieldEval.ush");
        if (!Files.isRegularFile(eval)) {
            return;
        }
        String text = Files.readString(eval, StandardCharsets.UTF_8);
        String updated = text;
        if (physicsFieldBuffer(found, "NodesParams")) {
            updated = updated.replace(
                    "in Buffer<float> PHYSICS_FIELD_NODES_PARAMS",
                    "in StructuredBuffer<float> PHYSICS_FIELD_NODES_PARAMS");
        }
        if (physicsFieldBuffer(found, "NodesOffsets")) {
            updated = updated.replace(
                    "in Buffer<int> PHYSICS_FIELD_NODES_OFFSETS",
                    "in StructuredBuffer<int> PHYSICS_FIELD_NODES_OFFSETS");
        }
        if (physicsFieldBuffer(found, "TargetsOffsets")) {
            updated = updated.replace(
                    "in Buffer<int> PHYSICS_FIELD_TARGETS_OFFSETS",
                    "in StructuredBuffer<int> PHYSICS_FIELD_TARGETS_OFFSETS");
        }
        if (!updated.equals(text)) {
            Files.writeString(eval, updated, StandardCharsets.UTF_8);
        }
    }

    private static boolean physicsFieldBuffer(Set<String> found, String name) {
        return found.contains(name) || found.stream().anyMatch(buffer -> buffer.endsWith("_" + name));
    }

    private void completeLegacyPhysicsFieldInputs(Set<String> buffers) throws IOException {
        var fields = buffers.stream().filter(name -> name.endsWith("_NodesParams"))
                .map(name -> name.substring(0, name.length() - "_NodesParams".length())).toList();
        if (fields.isEmpty()) return;
        var generated = stageRoot.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        if (!Files.isRegularFile(generated)) return;
        String original = Files.readString(generated, StandardCharsets.UTF_8);
        String source = original;
        for (String field : fields) {
            if (source.contains(field + "_NodesParams")) continue;
            String mapping = Pattern.quote(field + "_TargetMappings") + "\\[TargetIndex]";
            var legacy = Pattern.compile("GFieldGroupThreadId\\s*=\\s*GGroupThreadId\\.x;\\s*return\\s+"
                    + "PhysicsField_EvalPhysics(?:Vector|Scalar|Integer)Field\\(LWCHackToFloat\\(SamplePos\\),\\s*"
                    + mapping + "\\.(?<lane>[xyz])\\s*\\);");
            Matcher calls = legacy.matcher(source);
            if (!calls.find()) continue;
            source = calls.replaceAll(match -> {
                String lane = match.group(1);
                String type = switch (lane) { case "x" -> "Vector"; case "y" -> "Scalar"; default -> "Integer"; };
                return "return PhysicsField_EvalPhysics" + type + "Field(LWCHackToFloat(SamplePos), "
                        + field + "_TargetMappings[TargetIndex]." + lane + ", " + field + "_NodesParams, "
                        + field + "_NodesOffsets, " + field + "_TargetsOffsets, " + field + "_TimeSeconds);";
            });
            var declaration = Pattern.compile("(?m)^([ \\t]*int4\\s+" + Pattern.quote(field + "_TargetMappings") + "\\[[^\\]\\r\\n]+]\\s*;)");
            source = declaration.matcher(source).replaceFirst(Matcher.quoteReplacement(
                    "Buffer<float> " + field + "_NodesParams;\nBuffer<int> " + field + "_NodesOffsets;\n"
                    + "Buffer<int> " + field + "_TargetsOffsets;\nfloat " + field + "_TimeSeconds;\n") + "$1");
        }
        if (!source.equals(original)) Files.writeString(generated, source, StandardCharsets.UTF_8);
    }
}
