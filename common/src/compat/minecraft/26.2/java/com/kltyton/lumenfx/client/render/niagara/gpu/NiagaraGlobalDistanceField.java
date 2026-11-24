package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.mojang.blaze3d.GpuFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Scene-owned global distance field for the current translated source view, in centimeters. */
public final class NiagaraGlobalDistanceField {
    private static final int MAX_CLIPMAPS = 6;
    public static final ComputeVolume.Sampling ATLAS_SAMPLING = sampling(Address.WRAP, FilterMode.LINEAR);
    public static final ComputeVolume.Sampling MIP_SAMPLING = sampling(Address.CLAMP, FilterMode.LINEAR);
    public static final ComputeVolume.Sampling PAGE_TABLE_SAMPLING = sampling(Address.CLAMP, FilterMode.POINT);

    public record Float4(float x, float y, float z, float w) {
        public Float4 {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(w))
                throw new IllegalArgumentException("Non-finite global distance field clipmap");
        }
        private void put(ByteBuffer target) { target.putFloat(x).putFloat(y).putFloat(z).putFloat(w); }
    }

    public record Clipmap(Float4 centerAndExtent, Float4 worldToUvAddAndMul, Float4 mipWorldToUvScale, Float4 mipWorldToUvBias) {
        public Clipmap {
            if (centerAndExtent.w() <= 0) throw new IllegalArgumentException("Invalid global distance field clipmap extent");
        }
    }

    private static final String PREFIX = "GlobalDistanceField";
    private static final String PAGE_ATLAS = PREFIX + "PageAtlasTexture";
    private static final String COVERAGE_ATLAS = PREFIX + "CoverageAtlasTexture";
    private static final String PAGE_TABLE = PREFIX + "PageTableTexture";
    private static final String MIP = PREFIX + "MipTexture";
    private static final NiagaraGlobalDistanceField EMPTY = new NiagaraGlobalDistanceField(null, null, null, null,
            List.of(), 0, 0, 0, 0, 0);

    private final @Nullable ComputeVolume pageAtlas, coverageAtlas, pageTable, mip;
    private final Map<String, byte[]> values;

    /** The producer keeps the supplied volumes alive through GPU dispatch for this view. */
    public NiagaraGlobalDistanceField(@Nullable ComputeVolume pageAtlas, @Nullable ComputeVolume coverageAtlas,
                                      @Nullable ComputeVolume pageTable, @Nullable ComputeVolume mip,
                                      List<Clipmap> clipmaps, int clipmapSizeInPages, float resolution,
                                      float maxConeDistance, float mipFactor, float mipTransition) {
        this.pageAtlas = validate(pageAtlas, ATLAS_SAMPLING, false);
        this.coverageAtlas = validate(coverageAtlas, ATLAS_SAMPLING, false);
        this.pageTable = validate(pageTable, PAGE_TABLE_SAMPLING, true);
        this.mip = validate(mip, MIP_SAMPLING, false);
        clipmaps = List.copyOf(clipmaps);
        if (clipmaps.size() > MAX_CLIPMAPS || clipmapSizeInPages < 0 || !Float.isFinite(resolution) || resolution < 0
                || !Float.isFinite(maxConeDistance) || maxConeDistance < 0
                || !Float.isFinite(mipFactor) || !Float.isFinite(mipTransition)
                || !clipmaps.isEmpty() && (pageAtlas == null || pageTable == null || clipmapSizeInPages == 0 || resolution == 0))
            throw new IllegalArgumentException("Invalid global distance field clipmap layout");
        Map<String, byte[]> fields = new HashMap<>();
        fields.put("GlobalVolumeTranslatedCenterAndExtent", pack(clipmaps, Clipmap::centerAndExtent));
        fields.put("GlobalVolumeTranslatedWorldToUVAddAndMul", pack(clipmaps, Clipmap::worldToUvAddAndMul));
        fields.put(PREFIX + "MipTranslatedWorldToUVScale", pack(clipmaps, Clipmap::mipWorldToUvScale));
        fields.put(PREFIX + "MipTranslatedWorldToUVBias", pack(clipmaps, Clipmap::mipWorldToUvBias));
        fields.put(PREFIX + "MipFactor", floats(mipFactor));
        fields.put(PREFIX + "MipTransition", floats(mipTransition));
        fields.put(PREFIX + "ClipmapSizeInPages", words(clipmapSizeInPages));
        fields.put(PREFIX + "InvPageAtlasSize", inverseExtent(pageAtlas));
        fields.put(PREFIX + "InvCoverageAtlasSize", inverseExtent(coverageAtlas));
        fields.put("GlobalVolumeDimension", floats(resolution));
        fields.put("GlobalVolumeTexelSize", floats(resolution == 0 ? 0 : 1f / resolution));
        fields.put("MaxGlobalDFAOConeDistance", floats(maxConeDistance));
        fields.put("NumGlobalSDFClipmaps", words(clipmaps.size()));
        for (String name : List.of("CoveredExpandSurfaceScale", "NotCoveredExpandSurfaceScale", "NotCoveredMinStepScale",
                "DitheredTransparencyStepThreshold", "DitheredTransparencyTraceThreshold"))
            fields.put(name, floats(0));
        values = Map.copyOf(fields);
    }

    static NiagaraGlobalDistanceField orEmpty(@Nullable NiagaraGlobalDistanceField value) { return value == null ? EMPTY : value; }

    @Nullable byte[] value(String name) { return values.get(name); }

    @Nullable ComputeVolume texture(String name, NiagaraGpuPrograms programs) {
        if (matches(name, PAGE_ATLAS)) return pageAtlas == null ? programs.blackVolumeTexture() : pageAtlas;
        if (matches(name, COVERAGE_ATLAS)) return coverageAtlas == null ? programs.blackVolumeTexture() : coverageAtlas;
        if (matches(name, PAGE_TABLE)) return pageTable == null ? programs.blackIntegerVolumeTexture() : pageTable;
        if (matches(name, MIP)) return mip == null ? programs.blackVolumeTexture() : mip;
        return null;
    }

    private static boolean matches(String name, String texture) { return name.equals(texture) || name.startsWith(texture + "_"); }

    private static @Nullable ComputeVolume validate(@Nullable ComputeVolume volume, ComputeVolume.Sampling sampler, boolean integer) {
        if (volume != null && (volume.isClosed() || !volume.hasSampling(sampler)
                || (integer ? volume.format() != GpuFormat.R32_UINT : !volume.floatingColor())))
            throw new IllegalArgumentException("Invalid global distance field texture or sampler");
        return volume;
    }

    private static ComputeVolume.Sampling sampling(Address address, FilterMode filter) {
        return new ComputeVolume.Sampling(new EffectSamplerFactory.Settings(address, address, filter, filter, 1), address);
    }

    private static byte[] pack(List<Clipmap> clipmaps, Function<Clipmap, Float4> field) {
        var target = buffer(MAX_CLIPMAPS * 4);
        for (var clipmap : clipmaps) field.apply(clipmap).put(target);
        return target.array();
    }

    private static byte[] inverseExtent(@Nullable ComputeVolume volume) {
        return volume == null ? floats(0, 0, 0) : floats(1f / volume.width(), 1f / volume.height(), 1f / volume.depth());
    }

    private static byte[] floats(float... values) {
        var target = buffer(values.length);
        for (float value : values) target.putFloat(value);
        return target.array();
    }
    private static byte[] words(int value) { return buffer(1).putInt(value).array(); }
    private static ByteBuffer buffer(int words) { return ByteBuffer.allocate(words * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN); }
}
