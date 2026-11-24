package com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class NiagaraGaussianSplatWriter {
    public static final String TYPE = "NiagaraDataInterfaceGaussianSplatWriter";
    public static final String ENABLED = "fx.Niagara.GaussianSplatWriter.Enabled";
    public static final String MAX_COUNT = "fx.Niagara.GaussianSplatWriter.MaxNumSplats";
    private static final int DEFAULT_COUNT_LIMIT = 4 * 1024 * 1024;
    private static final int QUANTIZED_POSITION_MAX = (1 << 21) - 1;
    private static final Vec3 DEFAULT_MIN = new Vec3(-1000, -1000, -1000);
    private static final Vec3 DEFAULT_MAX = new Vec3(1000, 1000, 1000);
    private static final float MAX_COLOR_COEFFICIENT = 0.5f / 0.28209479177387814f;
    public enum Function { GetNumSplats, GetSHDegree, Clear }

    public record Definition(@Nullable Integer count, @Nullable Integer degree, @Nullable Integer coordinateSpace,
                             @Nullable Vec3 positionMin, @Nullable Vec3 positionMax,
                             @Nullable Float maxScale, @Nullable Float maxCoefficient,
                             float splatScale, float opacityScale, float inverseToneCurve, boolean invertExpandGamut,
                             boolean localSpace, NiagaraGaussianSplatReader.@Nullable Definition reader) {
        public static Definition read(JsonObject source) {
            var reader = source.getAsJsonObject("gaussian_splat_reader");
            return new Definition(integer(source, "count"), integer(source, "sh_degree"), integer(source, "sh_coordinate_space"),
                    vector(source, "position_min"), vector(source, "position_max"), real(source, "max_scale"), real(source, "max_sh_coefficient"),
                    Objects.requireNonNullElse(real(source, "splat_scale"), 1f), Objects.requireNonNullElse(real(source, "opacity_scale"), 1f),
                    Objects.requireNonNullElse(real(source, "inverse_tone_curve"), 0f),
                    !source.has("invert_expand_gamut") || source.get("invert_expand_gamut").getAsBoolean(),
                    source.has("local_space") && source.get("local_space").getAsBoolean(),
                    reader == null ? null : NiagaraGaussianSplatReader.Definition.read(reader));
        }

        private static @Nullable Integer integer(JsonObject source, String name) {
            var value = source.get(name);
            return value == null || value.isJsonNull() ? null : value.getAsInt();
        }

        private static @Nullable Float real(JsonObject source, String name) {
            var value = source.get(name);
            return value == null || value.isJsonNull() ? null : value.getAsFloat();
        }

        private static @Nullable Vec3 vector(JsonObject source, String name) {
            var value = source.getAsJsonArray(name);
            return value == null ? null : new Vec3(value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat());
        }
    }

    public record Config(int count, int degree, int coordinateSpace, boolean quantized, ByteBuffer parameters, Definition source) {
        @Override public ByteBuffer parameters() { return parameters.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
        public int coefficientCount() { return NiagaraGaussianSplatAsset.coefficientCount(degree); }
        public int stride() { return quantized ? 20 + 4 * (coefficientCount() - 1) : 56 + 12 * (coefficientCount() - 1); }
        public long storageBytes() { return (long)Math.max(count, 1) * stride(); }
    }

    public record Snapshot(Config config, long clearEpoch) { }

    private final Config config;
    private long clearEpoch;
    private boolean pendingClear;

    public NiagaraGaussianSplatWriter(Definition definition, Map<Identifier, NiagaraGaussianSplatAsset> assets,
                                    NiagaraPlatformEnvironment environment, long maxStorageBytes) {
        var reader = definition.reader();
        var asset = reader == null || reader.asset() == null ? NiagaraGaussianSplatAsset.EMPTY
                : Objects.requireNonNull(assets.get(reader.asset()), "Missing prepared Niagara Gaussian Splat " + reader.asset());
        var quantization = asset.quantization();
        boolean hasAsset = asset.count() > 0;
        boolean quantized = !hasAsset || quantization != null;
        int inheritedDegree = reader != null && hasAsset ? Math.min(asset.degree(), reader.maxShDegree()) : 0;
        int degree = Math.clamp(Objects.requireNonNullElse(definition.degree(), inheritedDegree), 0, NiagaraGaussianSplatAsset.MAX_SH_DEGREE);
        int coordinate = Objects.requireNonNullElse(definition.coordinateSpace(), asset.coordinateSpace());
        if (coordinate < 0 || coordinate > 2) throw new IllegalArgumentException("Unknown Niagara Gaussian Splat coordinate space " + coordinate);
        var maximum = environment.console(MAX_COUNT);
        int count = Math.clamp(Objects.requireNonNullElse(definition.count(), asset.count()), 0,
                maximum == null ? DEFAULT_COUNT_LIMIT : Math.max(0, maximum.bits()));
        var parameters = parameters(quantized, quantization, definition);
        var configured = new Config(count, degree, coordinate, quantized, parameters, definition);
        config = configured.storageBytes() <= maxStorageBytes ? configured
                : new Config(0, degree, coordinate, quantized, parameters, definition);
    }

    public void execute(Function function, int lanes, NiagaraGaussianSplatReader.Input input, NiagaraGaussianSplatReader.Output output) {
        for (int lane = 0; lane < lanes; lane++) {
            switch (function) {
                case GetNumSplats -> output.word(0, lane, config.count());
                case GetSHDegree -> output.word(0, lane, config.degree());
                case Clear -> pendingClear |= input.word(0, lane) != 0;
            }
        }
    }

    public Snapshot publish() {
        if (pendingClear) {
            clearEpoch++;
            pendingClear = false;
        }
        return new Snapshot(config, clearEpoch);
    }

    private static ByteBuffer parameters(boolean quantized, @Nullable ByteBuffer inherited, Definition definition) {
        var data = ByteBuffer.allocate(26 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        if (!quantized) {
            put(data, 1, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0);
        } else if (inherited != null && definition.positionMin() == null && definition.maxScale() == null && definition.maxCoefficient() == null) {
            data.put(inherited);
        } else {
            Vec3 min = DEFAULT_MIN, max = DEFAULT_MAX;
            float scale = 100, coefficient = 2;
            if (inherited != null) {
                Vec3 step = new Vec3(inherited.getFloat(0), inherited.getFloat(4), inherited.getFloat(8));
                min = new Vec3(inherited.getFloat(12), inherited.getFloat(16), inherited.getFloat(20));
                max = min.add(step.scale(QUANTIZED_POSITION_MAX));
                float scaleX = inherited.getFloat(36) + inherited.getFloat(24) * 2047;
                float scaleY = inherited.getFloat(40) + inherited.getFloat(28) * 2047;
                float scaleZ = inherited.getFloat(44) + inherited.getFloat(32) * 1023;
                scale = (float)Math.exp(Math.max(scaleX, Math.max(scaleY, scaleZ)));
                coefficient = 0;
                for (int axis = 0; axis < 12; axis += 4) {
                    float bias = inherited.getFloat(92 + axis), stepSh = inherited.getFloat(80 + axis);
                    coefficient = Math.max(coefficient, Math.max(Math.abs(bias), Math.abs(bias + stepSh * 255)));
                }
            }
            min = Objects.requireNonNullElse(definition.positionMin(), min);
            max = Objects.requireNonNullElse(definition.positionMax(), max);
            scale = Objects.requireNonNullElse(definition.maxScale(), scale);
            coefficient = Objects.requireNonNullElse(definition.maxCoefficient(), coefficient);
            put(data, (float)Math.max(max.x - min.x, 0) / QUANTIZED_POSITION_MAX,
                    (float)Math.max(max.y - min.y, 0) / QUANTIZED_POSITION_MAX,
                    (float)Math.max(max.z - min.z, 0) / QUANTIZED_POSITION_MAX, (float)min.x, (float)min.y, (float)min.z);
            float logMin = (float)Math.log(1e-8f), logMax = (float)Math.log(Math.max(scale, 1e-8f)), range = logMax - logMin;
            put(data, range / 2047, range / 2047, range / 1023, logMin, logMin, logMin);
            float color = MAX_COLOR_COEFFICIENT, colorStep = 2 * color / 255;
            put(data, colorStep, colorStep, colorStep, 1f / 255f, -color, -color, -color, 0);
            float coefficientStep = 2 * coefficient / 255;
            put(data, coefficientStep, coefficientStep, coefficientStep, -coefficient, -coefficient, -coefficient);
        }
        return data.flip().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void put(ByteBuffer data, float... values) {
        for (float value : values) data.putFloat(value);
    }
}
