package com.kltyton.lumenfx.effect.niagara.parameter;

import java.util.Objects;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import org.jspecify.annotations.Nullable;

/** Typed collection defaults retain their original byte layout, including half and aggregate values. */
public record NiagaraCollectionBinding(String path, String name, String sourceType, byte[] defaults,
                                      double @Nullable [] position, boolean systemOverride,
                                      byte @Nullable [] baseDefaults, double @Nullable [] basePosition,
                                      @Nullable MaterialCollectionDefinition materialSource, @Nullable String materialParameter) {
    public NiagaraCollectionBinding(String path, String name, String sourceType, byte[] defaults, double @Nullable [] position,
                                    boolean systemOverride, byte @Nullable [] baseDefaults, double @Nullable [] basePosition) {
        this(path, name, sourceType, defaults, position, systemOverride, baseDefaults, basePosition, null, null);
    }
    public NiagaraCollectionBinding {
        Objects.requireNonNull(path); Objects.requireNonNull(name); Objects.requireNonNull(sourceType);
        if (materialParameter != null && materialSource == null) throw new IllegalArgumentException("Incomplete MPC source binding: " + name);
        if (materialParameter != null) {
            boolean valid = switch (sourceType) {
                case "/Script/Niagara.NiagaraFloat" -> materialSource.scalars().stream().anyMatch(value -> SourceNames.equal(value.name(), materialParameter));
                case "/Script/CoreUObject.LinearColor" -> materialSource.vectors().stream().anyMatch(value -> SourceNames.equal(value.name(), materialParameter));
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("MPC source does not drive this NPC variable type: " + name);
        }
        defaults = defaults.clone();
        baseDefaults = baseDefaults == null ? null : baseDefaults.clone();
        position = position == null ? null : position.clone();
        basePosition = basePosition == null ? null : basePosition.clone();
        if (defaults.length == 0 || baseDefaults != null && baseDefaults.length != defaults.length)
            throw new IllegalArgumentException("Invalid collection default byte layout: " + name);
        if (position != null && (position.length != 3 || defaults.length != 12 || !sourceType.equals("/Script/Niagara.NiagaraPosition"))
                || basePosition != null && (baseDefaults == null || basePosition.length != 3 || defaults.length != 12
                    || !sourceType.equals("/Script/Niagara.NiagaraPosition")))
            throw new IllegalArgumentException("Invalid collection absolute position: " + name);
    }
    @Override public byte[] defaults() { return defaults.clone(); }
    @Override public byte @Nullable [] baseDefaults() { return baseDefaults == null ? null : baseDefaults.clone(); }
    @Override public double @Nullable [] position() { return position == null ? null : position.clone(); }
    @Override public double @Nullable [] basePosition() { return basePosition == null ? null : basePosition.clone(); }
    public int bytes() { return defaults.length; }
    public boolean hasDefault(boolean component) { return !component || baseDefaults != null; }

    public void copyDefault(byte[] destination, int offset, boolean component, double originX, double originY, double originZ) {
        byte[] data = component ? baseDefaults : defaults;
        if (data == null) return;
        System.arraycopy(data, 0, destination, offset, data.length);
        double[] absolute = component ? basePosition : position;
        if (absolute != null) for (int index = 0; index < 3; index++) {
            float value = (float)(absolute[index] - switch (index) { case 0 -> originX; case 1 -> originY; default -> originZ; });
            int bits = Float.floatToRawIntBits(value);
            for (int part = 0; part < 4; part++) destination[offset + index * 4 + part] = (byte)(bits >>> (part * 8));
        }
    }
    public void copyDefault(int[] destination, int offset, boolean component, double originX, double originY, double originZ) {
        byte[] data = component ? baseDefaults : defaults;
        if (data == null) return;
        if ((data.length & 3) != 0) throw new IllegalArgumentException("Collection value is not DWORD aligned: " + name);
        for (int index = 0; index < data.length / 4; index++) {
            int source = index * 4;
            destination[offset + index] = Byte.toUnsignedInt(data[source]) | Byte.toUnsignedInt(data[source + 1]) << 8
                    | Byte.toUnsignedInt(data[source + 2]) << 16 | Byte.toUnsignedInt(data[source + 3]) << 24;
        }
        double[] absolute = component ? basePosition : position;
        if (absolute != null) for (int index = 0; index < 3; index++)
            destination[offset + index] = Float.floatToRawIntBits((float)(absolute[index]
                    - switch (index) { case 0 -> originX; case 1 -> originY; default -> originZ; }));
    }
}
