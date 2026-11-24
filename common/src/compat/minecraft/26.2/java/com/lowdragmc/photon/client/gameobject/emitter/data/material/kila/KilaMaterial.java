/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.mojang.blaze3d.PrimitiveTopology;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderState;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderTypes;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderInstanceMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.render.StackedDistortion;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The built-in all-in-one particle material: switchable modules over one shader, each enabled set its own variant
 * ({@link KilaPipelines}, one {@link KilaBlock}). Each RenderType owns its block values and binds them through a
 * {@link PhotonRenderTypes.MaterialBinder}.
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "kila_material", registry = "photon:material")
public class KilaMaterial extends ShaderInstanceMaterial {
    /** ⚠️ ordinals MIRRORED IN {@code photon:kila.fsh} (KilaSurfaceModes.x). */
    public enum VertexColor {
        MULTIPLY, ALPHA_ONLY, IGNORE,
        /** for when the vertex alpha drives something else, e.g. dissolve on a trail */
        RGB_ONLY
    }

    private static final String[] BASE = new String[KilaParam.GROUPS];
    private static final String[] SCALE = new String[KilaParam.GROUPS];
    private static final String[] SOURCE = new String[KilaParam.GROUPS];

    static {
        for (int group = 0; group < KilaParam.GROUPS; group++) {
            BASE[group] = "KilaBase" + group;
            SCALE[group] = "KilaScale" + group;
            SOURCE[group] = "KilaSrc" + group;
        }
    }
    /** A RenderType no draw asked for in this long is let go: editing walks through many variants. */
    private static final long RENDER_TYPE_IDLE_MS = 5000;

    // render state
    /** Leave blend, cull and depth to each slot, as a TextureMaterial does. */
    @Persisted
    public boolean slotRenderState;
    @Persisted
    public BlendMode.Preset blend = BlendMode.Preset.ALPHA;
    /** Premultiplied only: 0 is a normal blend, 1 is purely additive. */
    @Persisted
    public float additive;
    @Persisted
    public boolean doubleSided;
    /** double sided, drawn twice: back faces, then front ones */
    @Persisted
    public boolean backFacesFirst;
    @Persisted
    public boolean depthTest = true;
    @Persisted
    public boolean depthWrite;
    /** blocks towards the camera the quad is depth tested at; where it shows does not move */
    @Persisted
    public float cameraOffset;

    // surface
    @Persisted
    public HDRColor color = HDRColor.white();
    @Persisted
    public HDRColor backColor = HDRColor.white();
    @Persisted
    public HDRColor emission = HDRColor.black();
    @Persisted(subPersisted = true)
    public final KilaDriver alpha = new KilaDriver(1);
    @Persisted(subPersisted = true)
    public final KilaDriver intensity = new KilaDriver(1);
    @Persisted
    public float alphaSharpen = 1;
    @Persisted
    public VertexColor vertexColor = VertexColor.MULTIPLY;
    @Persisted
    public boolean worldLight = true;
    @Persisted
    public boolean fog = true;
    @Persisted
    public float alphaClip = 0.01f;
    /** animations stepped at this many frames per second; 0 = smooth */
    @Persisted
    public float frameRate;
    /** how far the uv jumps every step */
    @Persisted
    public float boil;

    // modules
    @Persisted(subPersisted = true)
    public final KilaMainTexture main = new KilaMainTexture();
    @Persisted(subPersisted = true)
    public final KilaLayer layer = new KilaLayer();
    @Persisted(subPersisted = true)
    public final KilaLayer layer2 = new KilaLayer(2);
    @Persisted(subPersisted = true)
    public final KilaMask mask1 = new KilaMask(1);
    @Persisted(subPersisted = true)
    public final KilaMask mask2 = new KilaMask(2);
    @Persisted(subPersisted = true)
    public final KilaDissolve dissolve = new KilaDissolve();
    @Persisted(subPersisted = true)
    public final KilaDistortion distortion = new KilaDistortion();
    @Persisted(subPersisted = true)
    public final KilaUvEffects uvEffects = new KilaUvEffects();
    @Persisted(subPersisted = true)
    public final KilaProjection projection = new KilaProjection();
    @Persisted(subPersisted = true)
    public final KilaScreenDistortion screenDistortion = new KilaScreenDistortion();
    @Persisted(subPersisted = true)
    public final KilaRamp ramp = new KilaRamp();
    @Persisted(subPersisted = true)
    public final KilaFresnel fresnel = new KilaFresnel();
    @Persisted(subPersisted = true)
    public final KilaShading shading = new KilaShading();
    @Persisted(subPersisted = true)
    public final KilaMatcap matcap = new KilaMatcap();
    @Persisted(subPersisted = true)
    public final KilaVertexOffset vertexOffset = new KilaVertexOffset();
    @Persisted(subPersisted = true)
    public final KilaDepth depth = new KilaDepth();
    @Persisted(subPersisted = true)
    public final KilaVolume volume = new KilaVolume();
    @Persisted(subPersisted = true)
    public final KilaSimpleModules.CameraFade cameraFade = new KilaSimpleModules.CameraFade();
    @Persisted(subPersisted = true)
    public final KilaSimpleModules.ColorAdjust colorAdjust = new KilaSimpleModules.ColorAdjust();
    @Persisted(subPersisted = true)
    public final KilaSimpleModules.Pixelate pixelate = new KilaSimpleModules.Pixelate();
    @Persisted(subPersisted = true)
    public final KilaSimpleModules.Lit lit = new KilaSimpleModules.Lit();

    private final List<KilaModule> modules = List.of(main, layer, layer2, mask1, mask2, dissolve, distortion, uvEffects,
            projection, screenDistortion, ramp, fresnel, shading, matcap, vertexOffset, depth, volume, cameraFade,
            colorAdjust, pixelate, lit);
    // when the samplers do not all fit, the later ones give way first
    private final List<KilaModule> keepOrder = List.of(main, layer, mask1, mask2, dissolve, distortion, uvEffects,
            projection, screenDistortion, ramp, fresnel, shading, vertexOffset, depth, volume, cameraFade, colorAdjust,
            pixelate, lit, layer2, matcap);

    // runtime
    private final KilaLayout[] layouts = new KilaLayout[2];
    private final long[] layoutStructures = new long[2];
    @Nullable
    private MaterialRenderState renderState;
    private int renderStateKey;
    private final float[] scratch = new float[4];
    private final float[][] base = new float[KilaParam.GROUPS][4];
    private final float[][] scale = new float[KilaParam.GROUPS][4];
    private final int[][] source = new int[KilaParam.GROUPS][4];
    // allocation-free walks: these run per draw
    private final BiConsumer<KilaParam, KilaDriver> stager = this::stage;
    private long textureHash;
    private final Consumer<KilaTexture> textureHasher = texture -> textureHash = textureHash * 31 + texture.structureHash();
    /** what the pass has to provide; the layout has a scan of its own, so neither interrupts the other */
    private final DriverScan scan = new DriverScan();
    private final DriverScan layoutScan = new DriverScan();
    @Nullable
    private transient GpuState gpu;

    public List<KilaModule> modules() {
        return modules;
    }

    // ---- variant ----------------------------------------------------------------------------------

    /** The program layout is reused until a change affects the shader variant. */
    public KilaLayout layout() {
        syncStacking();
        long structure = structure();
        int slot = screenDistortion.stacking ? 1 : 0;
        var layout = layouts[slot];
        if (layout == null || structure != layoutStructures[slot]) {
            layout = buildLayout();
            layouts[slot] = layout;
            layoutStructures[slot] = structure;
        }
        return layout;
    }

    private KilaLayout buildLayout() {
        var enabled = new ArrayList<KilaModule>();
        for (var module : keepOrder) {
            if (module.isEnable()) enabled.add(module);
        }
        var layout = layoutBuilder().build(enabled);
        // a record a driver reads must be declared, and a declared one bound: follow getUsedChannelMask /
        // usesCustomData. Defines take no sampler, so the rebuild keeps the same modules.
        layoutScan.reset();
        scanActive(layout, layoutScan);
        if (layoutScan.mask == 0 && !layoutScan.custom) return layout;
        var builder = layoutBuilder();
        if (layoutScan.mask != 0) builder.define(KilaPipelines.DATA_DEFINE);
        if (layoutScan.custom) builder.define(KilaPipelines.CUSTOM_DATA_DEFINE);
        return builder.build(enabled);
    }

    private KilaLayout.Builder layoutBuilder() {
        var builder = KilaLayout.surface();
        // blocks the quad is drawn towards the camera
        if (cameraOffset != 0) builder.define("KILA_CAMERA_OFFSET").float1("KilaCameraOffset", 0);
        // x = steps per second, y = how far the uv jumps each step
        if (frameRate > 0) builder.define("KILA_TIME_STEP").vec4("KilaTimeStep", 12, 0, 0, 0);
        return builder;
    }

    private long structure() {
        textureHash = (cameraOffset != 0 ? 2 : 1) + (frameRate > 0 ? 4 : 0);
        textureHash = textureHash * 31 + needs(KilaParam.ALPHA, alpha) * 4L + needs(KilaParam.INTENSITY, intensity);
        for (var module : modules) {
            textureHash = textureHash * 31 + (module.isEnable() ? 1 : 0);
            if (!module.isEnable()) continue;
            textureHash = textureHash * 31 + module.structure();
            module.textures(textureHasher);
            // a driver starting or stopping to read particle data changes what the program declares
            layoutScan.reset();
            module.drivers(layoutScan);
            textureHash = textureHash * 31 + layoutScan.needs();
        }
        return textureHash;
    }

    private int needs(KilaParam param, KilaDriver driver) {
        layoutScan.reset();
        layoutScan.accept(param, driver);
        return layoutScan.needs();
    }

    private boolean active(KilaModule module, KilaLayout layout) {
        return module.isEnable() && !layout.dropped.contains(module);
    }

    /** Its file textures, which the binder loads on the first draw, and its baked gradients. */
    @Override
    public void preload(PreloadContext context) {
        for (var texture : layout().textures) {
            if (texture.texture() != null) {
                context.texture(texture.texture().getTexture());
            } else if (texture.dynamic() != null) {
                texture.dynamic().textureView();
            }
        }
    }

    /** Stacked only where the frame can be bent, and not while a debug view replaces the output. */
    private void syncStacking() {
        screenDistortion.stacking = screenDistortion.isEnable() && screenDistortion.stack
                && StackedDistortion.available();
    }

    private static boolean stacked(KilaLayout layout) {
        return layout.defines.contains(KilaScreenDistortion.STACK_DEFINE);
    }

    @Override
    @Nullable
    public RenderType getRenderType(MaterialSetting setting, PrimitiveTopology mode) {
        var layout = layout();
        var key = setting.pipelineKey(mode);
        // the slot's depth test would hide the projection behind the terrain it paints
        if (slotRenderState && active(projection, layout)) key = KilaPipelines.untested(key);
        if (stacked(layout)) key = KilaPipelines.stacked(key);
        return renderType(layout, key);
    }


    private RenderType renderType(KilaLayout layout, PhotonPipelines.ParticlePipelineKey key) {
        var state = gpu();
        var typeKey = new TypeKey(layout.key, key);
        var now = System.currentTimeMillis();
        var entry = state.renderTypes.get(typeKey);
        if (entry != null) {
            entry.lastUsed = now;
            return entry.renderType;
        }
        var lease = new KilaPipelines.Lease();
        var pipeline = KilaPipelines.get(lease, layout, key, null, false);
        if (pipeline == null) {
            lease.close();
            // visibly broken rather than invisible — the same as MissingMaterial
            return MaterialRenderTypes.hdrParticle(MissingTextureAtlasSprite.getLocation(), key);
        }
        state.evictIdle(now);
        var values = new KilaValues("KilaMaterial UBO", layout.block);
        var binder = new Binder(this, layout, values);
        // nothing of the material in here: these sit in the drain's indexes until its Cleaner drops them
        PhotonRenderTypes.PipelineSource pipelines = (geometry, cpuData) -> KilaPipelines.get(lease, layout, key, geometry, cpuData);
        var info = new PhotonRenderTypes.PhotonDrawInfo(
                new PhotonRenderTypes.PhotonDrawInfo.Programs(pipeline),
                new PhotonRenderTypes.PhotonDrawInfo.Bindings(Map.of(), layout.sceneSamplers, null, null, false, binder),
                new PhotonRenderTypes.PhotonDrawInfo.InstancedRecipe(key, null, null, pipelines));
        var renderType = PhotonRenderTypes.createMaterialOwned("photon_kila", info, true);
        state.renderTypes.put(typeKey, new Entry(renderType, values, lease, now));
        return renderType;
    }

    /** This material's GPU state, freed on the render thread once the material is gone. */
    private GpuState gpu() {
        if (gpu == null) {
            gpu = new GpuState();
            AutoCloseCleaner.registerRenderThread(this, gpu);
        }
        return gpu;
    }

    private record TypeKey(String layout, PhotonPipelines.ParticlePipelineKey state) {
    }

    /** One RenderType and what only it uses: its values of the variant's block, and its hold on the pipelines. */
    private static final class Entry {
        final RenderType renderType;
        final KilaValues values;
        final KilaPipelines.Lease lease;
        long lastUsed;

        Entry(RenderType renderType, KilaValues values, KilaPipelines.Lease lease, long lastUsed) {
            this.renderType = renderType;
            this.values = values;
            this.lease = lease;
            this.lastUsed = lastUsed;
        }

        void close() {
            PhotonRenderTypes.dropCustomShader(renderType);
            values.close();
            lease.close();
        }
    }

    /** The RenderTypes this material owns. Holds nothing of the material, or its Cleaner could not fire. */
    private static final class GpuState implements AutoCloseable {
        final Map<TypeKey, Entry> renderTypes = new HashMap<>();

        /**
         * Let go of what no draw asked for lately. Not at once: a job baked this frame may still draw it, and without
         * draw info the drain would bind none of Kila's state.
         */
        void evictIdle(long now) {
            renderTypes.values().removeIf(entry -> {
                if (now - entry.lastUsed < RENDER_TYPE_IDLE_MS) return false;
                entry.close();
                return true;
            });
        }

        @Override
        public void close() {
            renderTypes.values().forEach(Entry::close);
            renderTypes.clear();
        }
    }

    /**
     * Fills and binds one RenderType's block and textures. Holds the material weakly — the binder sits in the
     * drain's indexes — and once the material is gone the block keeps what it last held.
     */
    private static final class Binder implements PhotonRenderTypes.MaterialBinder {
        private final WeakReference<KilaMaterial> material;
        private final KilaLayout layout;
        private final KilaValues values;
        private final GpuTextureView[] views;
        private final GpuSampler[] samplers;

        Binder(KilaMaterial material, KilaLayout layout, KilaValues values) {
            this.material = new WeakReference<>(material);
            this.layout = layout;
            this.values = values;
            this.views = new GpuTextureView[layout.textures.size()];
            this.samplers = new GpuSampler[layout.textures.size()];
        }

        @Override
        public void prepare() {
            var owner = material.get();
            if (owner != null) owner.pack(values, layout);
            values.upload();
            var textureManager = Minecraft.getInstance().getTextureManager();
            for (int i = 0; i < views.length; i++) {
                var texture = layout.textures.get(i);
                if (texture.texture() != null) {
                    // the first use of a texture uploads it: here, before the pass opens
                    views[i] = textureManager.getTexture(texture.texture().getTexture()).getTextureView();
                    samplers[i] = KilaSamplers.of(texture.texture());
                } else if (texture.dynamic() != null) {
                    views[i] = texture.dynamic().textureView();
                    samplers[i] = KilaSamplers.gradient();
                }
            }
        }

        @Override
        public void bind(RenderPass pass) {
            var slice = values.slice();
            if (slice != null) pass.setUniform(KilaBlock.NAME, slice);
            for (int i = 0; i < views.length; i++) {
                if (views[i] != null) pass.bindTexture(layout.textures.get(i).name(), views[i], samplers[i]);
            }
        }
    }

    // ---- values -----------------------------------------------------------------------------------

    /** Everything {@code layout}'s program reads, into {@code values}. */
    void pack(KilaValues values, KilaLayout layout) {
        syncStacking();
        values.clear();
        layout.seed(values);
        var tint = color.toVector4f();
        values.set("KilaTint", tint.x, tint.y, tint.z, tint.w);
        var back = backColor.toVector4fOpaque();
        values.set("KilaBackTint", back.x, back.y, back.z, 1f);
        var glow = emission.toVector4fOpaque();
        values.set("KilaEmission", glow.x, glow.y, glow.z, 0f);
        values.set("KilaSurface", alphaClip, alphaSharpen, worldLight ? 1f : 0f, fog ? 1f : 0f);
        // the slot's blend wants what a TextureMaterial gives it: straight colour
        values.set("KilaSurfaceModes", vertexColor.ordinal(),
                slotRenderState ? BlendMode.Preset.ALPHA.ordinal() : blend.ordinal(), 0, 0);
        values.set("KilaOutput", additive, 0f, 0f, 0f);
        if (cameraOffset != 0) values.set("KilaCameraOffset", cameraOffset);
        if (frameRate > 0) values.set("KilaTimeStep", frameRate, boil, 0f, 0f);

        for (int group = 0; group < KilaParam.GROUPS; group++) {
            Arrays.fill(base[group], 0);
            Arrays.fill(scale[group], 0);
            Arrays.fill(source[group], 0);
        }
        stage(KilaParam.ALPHA, alpha);
        stage(KilaParam.INTENSITY, intensity);
        for (var module : modules) {
            if (!active(module, layout)) continue;
            module.drivers(stager);
            module.apply(values, scratch);
        }
        for (int group = 0; group < KilaParam.GROUPS; group++) {
            if (!layout.groups[group]) continue;
            var b = base[group];
            var s = scale[group];
            var c = source[group];
            values.set(BASE[group], b[0], b[1], b[2], b[3]);
            values.set(SCALE[group], s[0], s[1], s[2], s[3]);
            values.set(SOURCE[group], c[0], c[1], c[2], c[3]);
        }
    }

    private void stage(KilaParam param, KilaDriver driver) {
        base[param.group][param.component] = driver.value;
        scale[param.group][param.component] = driver.isDriven() ? driver.scale : 0;
        source[param.group][param.component] = driver.code();
    }


    // ---- what the pass has to provide -------------------------------------------------------------

    /** What a set of drivers reads. */
    private static final class DriverScan implements BiConsumer<KilaParam, KilaDriver> {
        long mask;
        boolean custom;

        void reset() {
            mask = 0;
            custom = false;
        }

        int needs() {
            return (mask != 0 ? 1 : 0) | (custom ? 2 : 0);
        }

        @Override
        public void accept(KilaParam param, KilaDriver driver) {
            mask |= driver.channelMask();
            custom |= driver.readsCustomData();
        }
    }

    private void scanActive(KilaLayout layout, DriverScan into) {
        into.accept(KilaParam.ALPHA, alpha);
        into.accept(KilaParam.INTENSITY, intensity);
        for (var module : modules) {
            if (active(module, layout)) module.drivers(into);
        }
    }

    private void scanDrivers() {
        var layout = layout();
        scan.reset();
        scanActive(layout, scan);
    }

    @Override
    public long getUsedChannelMask() {
        scanDrivers();
        return scan.mask;
    }

    @Override
    public boolean usesCustomData() {
        scanDrivers();
        return scan.custom;
    }

    @Override
    public int[] requiredCustomChannels() {
        var needed = new int[4];
        BiConsumer<KilaParam, KilaDriver> collect = (param, driver) -> {
            if (driver.readsCustomData()) {
                int stream = Math.clamp(driver.stream, 0, 3);
                needed[stream] = Math.max(needed[stream], Math.clamp(driver.channel, 0, 3) + 1);
            }
        };
        collect.accept(KilaParam.ALPHA, alpha);
        collect.accept(KilaParam.INTENSITY, intensity);
        var layout = layout();
        for (var module : modules) {
            if (active(module, layout)) module.drivers(collect);
        }
        return needed;
    }

    @Override
    @Nullable
    public MaterialRenderState getPreferredRenderState() {
        if (slotRenderState) return null;
        // a projection paints the very terrain that stands in front of its quad
        boolean tested = depthTest && !projection.isEnable();
        boolean twoPass = doubleSided && backFacesFirst;
        int key = blend.ordinal() | (doubleSided ? 16 : 0) | (tested ? 32 : 0) | (depthWrite ? 64 : 0) | (twoPass ? 128 : 0);
        if (renderState == null || key != renderStateKey) {
            renderState = new MaterialRenderState(blend.create(), !doubleSided || twoPass, tested, depthWrite, twoPass);
            renderStateKey = key;
        }
        return renderState;
    }

    @Override
    public IMaterial copy() {
        var copy = new KilaMaterial();
        var provider = Platform.getFrozenRegistry();
        PersistedParser.deserializeNBT(PersistedParser.serializeNBT(this, provider), copy, provider);
        return copy;
    }

    /** A KilaMaterial that draws what {@code source} draws; blend, cull and depth stay with each slot. */
    public static KilaMaterial fromTexture(TextureMaterial source) {
        var kila = new KilaMaterial();
        kila.slotRenderState = true;
        var texture = kila.main.texture;
        texture.texture = source.getTexture();
        var metadata = textureMetadata(source.getTexture());
        texture.nearest = !metadata.blur();
        var wrap = metadata.clamp() ? KilaTexture.Wrap.CLAMP : KilaTexture.Wrap.REPEAT;
        texture.wrap(wrap, wrap);
        kila.alphaClip = source.getDiscardThreshold();
        var hdr = source.getHdr();
        if (source.getHdrMode() == TextureMaterial.HDRMode.ADDITIVE) {
            kila.emission = hdr.copy();
        } else {
            var raw = hdr.toRawVector4f();
            kila.color = new HDRColor(raw.x, raw.y, raw.z, 1, hdr.getIntensity());
        }
        if (source.getPixelArt().isEnable()) {
            kila.pixelate.setEnable(true);
            kila.pixelate.pixels = source.getPixelArt().bits;
        }
        var soft = source.getSoftParticles();
        if (soft.isEnable()) {
            kila.depth.setEnable(true);
            kila.depth.distance = soft.distance;
            kila.depth.power = soft.power;
        }
        var lit = source.getLitParticles();
        if (lit.isEnable()) {
            kila.lit.setEnable(true);
            kila.lit.intensity = lit.intensity;
        }
        return kila;
    }

    private static TextureMetadataSection textureMetadata(Identifier location) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isPresent()) {
                var section = resource.get().metadata().getSection(TextureMetadataSection.TYPE);
                if (section.isPresent()) return section.get();
            }
        } catch (Exception ignored) {
            // Minecraft's own fallback: no blur, no clamp
        }
        return new TextureMetadataSection(TextureMetadataSection.DEFAULT_BLUR, TextureMetadataSection.DEFAULT_CLAMP,
                MipmapStrategy.AUTO, TextureMetadataSection.DEFAULT_ALPHA_CUTOFF_BIAS);
    }

    /** Take every setting of {@code other}, as if it had been loaded in its place. */
    public void copyFrom(KilaMaterial other) {
        var provider = Platform.getFrozenRegistry();
        PersistedParser.deserializeNBT(PersistedParser.serializeNBT(other, provider), this, provider);
    }

}
