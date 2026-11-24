package com.kltyton.lumenfx.client.simulation.niagara.field;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** CPU field metadata remains available when the source asset has no readable CPU texels. */
public final class NiagaraVectorFieldData {
    private final @Nullable VectorFieldVolume volume;
    private final boolean readable, tileX, tileY, tileZ;
    private final float[] dimensions = new float[3], bounds = new float[6], tiling;

    public NiagaraVectorFieldData(VectorProgram.External binding) {
        this(binding.field(), binding.tileX(), binding.tileY(), binding.tileZ(),
                Objects.requireNonNull(binding.interfaceData(), "Vector field has no CPU metadata"),
                binding.name().equals("SampleField") || binding.name().equals("LoadField"));
    }
    public NiagaraVectorFieldData(@Nullable VectorFieldVolume volume, boolean tileX, boolean tileY, boolean tileZ,
                                  JsonObject data, boolean sampling) {
        JsonArray size = data.getAsJsonArray("dimensions"), min = data.getAsJsonArray("min_bounds"), max = data.getAsJsonArray("max_bounds");
        if (size == null || min == null || max == null || size.size() != 3 || min.size() != 3 || max.size() != 3)
            throw new IllegalArgumentException("Vector field requires three dimensions and two bounds");
        this.volume = volume;
        readable = sampling && data.get("cpu_access").getAsBoolean();
        if (readable && volume == null) throw new IllegalArgumentException("Readable vector field has no source texels");
        for (int axis = 0; axis < 3; axis++) {
            dimensions[axis] = size.get(axis).getAsBigDecimal().intValueExact();
            bounds[axis] = min.get(axis).getAsFloat(); bounds[axis + 3] = max.get(axis).getAsFloat();
        }
        this.tileX = tileX; this.tileY = tileY; this.tileZ = tileZ;
        tiling = new float[] {tileX ? 1 : 0, tileY ? 1 : 0, tileZ ? 1 : 0};
    }

    public float constant(String name, int component) {
        return switch (name) {
            case "FieldDimensions" -> dimensions[component];
            case "FieldBounds" -> bounds[component];
            case "FieldTilingAxes" -> tiling[component];
            default -> throw new IllegalArgumentException("Vector field function has no constant output: " + name);
        };
    }

    public void sample(float x, float y, float z, double[] output) {
        if (readable) Objects.requireNonNull(volume).sampleSource(x, y, z, tileX, tileY, tileZ, output, 0);
        else Arrays.fill(output, 0);
    }

    public void load(int x, int y, int z, double[] output) {
        if (readable) Objects.requireNonNull(volume).loadSource(x, y, z, output, 0);
        else Arrays.fill(output, 0);
    }
}
