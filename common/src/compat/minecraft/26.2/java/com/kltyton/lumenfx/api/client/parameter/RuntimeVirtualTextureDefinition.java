package com.kltyton.lumenfx.api.client.parameter;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Authored RVT asset settings and the producer geometry derived for a placed volume. */
public record RuntimeVirtualTextureDefinition(Material material, int tileCount, int tileSize, int tileBorder,
                                              int removeLowMips, String lodGroup, boolean compress,
                                              boolean lowQualityCompression, boolean clear, boolean packedTable,
                                              boolean privateSpace, boolean adaptive, boolean continuous,
                                              Priority priority, MaterialData customMaterialData) {
    private static final int MAX_TABLE_LOG2 = 12;
    private static final int MAX_ADAPTIVE_LOG2 = 20;

    public enum Priority { Lowest, Lower, Low, BelowNormal, Normal, AboveNormal, High, Highest }
    public record MaterialData(float x, float y, float z, float w) { }
    /** Reference to an authored, built VT in the same resource package as the placed component. */
    public record StreamingMips(String source, boolean only) {
        public StreamingMips {
            if (source.isBlank()) throw new IllegalArgumentException("Missing RVT streaming texture source");
        }
    }

    /** Scaled, signed axes describe the three world-space edges of the authored unit cube. */
    public record Volume(Vec3 origin, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, double minHeight, double maxHeight) {
        public Volume {
            requireAxis(xAxis);
            requireAxis(yAxis);
            requireAxis(zAxis);
            if (!finite(origin) || !Double.isFinite(minHeight) || !Double.isFinite(maxHeight) || maxHeight < minHeight
                    || !perpendicular(xAxis, yAxis) || !perpendicular(xAxis, zAxis) || !perpendicular(yAxis, zAxis))
                throw new IllegalArgumentException("Invalid runtime virtual texture volume");
        }
        public Vec3 u() { return xAxis.scale(1 / xAxis.lengthSqr()); }
        public Vec3 v() { return yAxis.scale(1 / yAxis.lengthSqr()); }
        public double heightScale() { return Math.max(0.01, maxHeight - minHeight); }
        private static boolean perpendicular(Vec3 a, Vec3 b) {
            return Math.abs(a.dot(b)) <= 1e-6 * a.length() * b.length();
        }
        private static void requireAxis(Vec3 axis) {
            if (!finite(axis) || !Double.isFinite(axis.lengthSqr()) || axis.lengthSqr() == 0)
                throw new IllegalArgumentException("Invalid runtime virtual texture volume axis");
        }
        private static boolean finite(Vec3 value) {
            return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
        }
    }

    public record ProducerLayout(int pagesX, int pagesY, int tileSize, int border, int producerMaxMip, int tableMaxMip,
                                 int adaptiveTableLog2) {
        public int physicalSize() { return tileSize + border * 2; }

        /** Niagara receives FAdaptiveVirtualTexture's persistent low-mip allocation. */
        public PageTableLayout niagaraPageTable(boolean adaptive) {
            int longest = Integer.numberOfTrailingZeros(Math.max(pagesX, pagesY));
            if (!adaptive || longest < 11) return new PageTableLayout(pagesX, pagesY, 0, tableMaxMip);
            int bias = Math.max(0, adaptiveTableLog2 - 2);
            int width = Math.max(1, pagesX >> bias), height = Math.max(1, pagesY >> bias);
            int maxMip = Math.min(Integer.numberOfTrailingZeros(Math.min(width, height)), MAX_TABLE_LOG2 - 1);
            return new PageTableLayout(width, height, bias, maxMip);
        }

        public PageView pageView(Volume volume, int mip, int pageX, int pageY) {
            if (mip < 0 || mip > Integer.numberOfTrailingZeros(Math.max(pagesX, pagesY)) || pageX < 0 || pageY < 0
                    || pageX > (pagesX - 1) >> mip || pageY > (pagesY - 1) >> mip)
                throw new IllegalArgumentException("Runtime virtual texture page lies outside its producer");
            float sizeU = (float)(1 << mip) / pagesX;
            float sizeV = (float)(1 << mip) / pagesY;
            float borderFraction = (float)border / tileSize;
            double minU = pageX * sizeU - sizeU * borderFraction;
            double minV = pageY * sizeV - sizeV * borderFraction;
            double maxU = pageX * sizeU + sizeU + sizeU * borderFraction;
            double maxV = pageY * sizeV + sizeV + sizeV * borderFraction;
            Vec3 camera = volume.origin().add(volume.xAxis().scale((minU + maxU) * 0.5))
                    .add(volume.yAxis().scale((minV + maxV) * 0.5)).add(volume.zAxis());
            double halfWidth = volume.xAxis().length() * (maxU - minU) * 0.5;
            double halfHeight = volume.yAxis().length() * (maxV - minV) * 0.5;
            return new PageView(mip, producerMaxMip, pageX, pageY, physicalSize(), minU, minV, maxU, maxV, camera,
                    halfWidth, halfHeight, volume.zAxis().length(), halfWidth / physicalSize(), halfHeight / physicalSize());
        }
    }

    public record PageTableLayout(int pagesX, int pagesY, int producerMipBias, int maxMip) { }

    public record PageView(int mip, int maxMip, int x, int y, int pixelSize, double minU, double minV,
                           double maxU, double maxV, Vec3 camera, double halfWidth, double halfHeight,
                           double depth, double derivativeU, double derivativeV) { }

    public RuntimeVirtualTextureDefinition {
        Objects.requireNonNull(material);
        Objects.requireNonNull(lodGroup);
        Objects.requireNonNull(priority);
        Objects.requireNonNull(customMaterialData);
    }

    /** LOD biases are the effective texture-group and component settings of the current host. */
    public ProducerLayout layout(Volume volume, int tileSizeBias, int tileCountBias, int componentBias) {
        int logCount = (int)Math.clamp((long)tileCount + tileCountBias + componentBias, 0, adaptive ? MAX_ADAPTIVE_LOG2 : MAX_TABLE_LOG2);
        int size = 1 << (int)Math.clamp((long)tileSize + 6 + tileSizeBias, 6, 10);
        int border = Math.clamp(tileBorder, 0, 4) * 2;
        float logAspect = (float)(Math.log(Math.max(volume.xAxis().length(), 0.0001f)
                / Math.max(volume.yAxis().length(), 0.0001f)) / Math.log(2));
        int widthLog = logAspect < 0 ? Math.max(0, logCount - Math.round(-logAspect)) : logCount;
        int heightLog = logAspect > 0 ? Math.max(0, logCount - Math.round(logAspect)) : logCount;
        int producerMaxMip = (int)Math.clamp((long)logCount - removeLowMips, 0, logCount);
        return producerLayout(1 << widthLog, 1 << heightLog, size, border, producerMaxMip, logCount);
    }

    public ProducerLayout streamedLayout(ProducerLayout scene, int width, int height, int maxMip) {
        return producerLayout(width, height, scene.tileSize(), scene.border(), maxMip, maxMip);
    }

    private ProducerLayout producerLayout(int width, int height, int size, int border, int producerMaxMip, int logCount) {
        int shortest = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(Math.min(width, height));
        int tableMaxMip = Math.min(shortest, Math.min(producerMaxMip, MAX_TABLE_LOG2 - 1));
        int adaptiveTable = !adaptive || logCount < 11 ? logCount : logCount <= 14 ? 10 : logCount <= 17 ? 11 : 12;
        return new ProducerLayout(width, height, size, border, producerMaxMip, tableMaxMip, adaptiveTable);
    }

    public static RuntimeVirtualTextureDefinition read(JsonObject source) {
        var values = source.getAsJsonArray("custom_material_data");
        if (values.size() != 4) throw new IllegalArgumentException("Runtime virtual texture material data requires a float4");
        return new RuntimeVirtualTextureDefinition(Material.valueOf(source.get("material").getAsString()),
                source.get("tile_count").getAsInt(), source.get("tile_size").getAsInt(), source.get("tile_border").getAsInt(),
                source.get("remove_low_mips").getAsInt(), source.get("lod_group").getAsString(), source.get("compress").getAsBoolean(),
                source.get("low_quality_compression").getAsBoolean(), source.get("clear").getAsBoolean(), source.get("packed_table").getAsBoolean(),
                source.get("private_space").getAsBoolean(), source.get("adaptive").getAsBoolean(), source.get("continuous").getAsBoolean(),
                Priority.valueOf(source.get("priority").getAsString()),
                new MaterialData(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat(), values.get(3).getAsFloat()));
    }
}
