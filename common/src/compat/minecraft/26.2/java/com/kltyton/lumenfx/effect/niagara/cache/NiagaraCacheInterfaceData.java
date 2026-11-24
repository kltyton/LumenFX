package com.kltyton.lumenfx.effect.niagara.cache;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import java.util.List;
import java.util.Objects;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/** Saved data-interface values keyed by the native variable name and class. */
public record NiagaraCacheInterfaceData(Key key, Frames frames) {
    public NiagaraCacheInterfaceData {
        Objects.requireNonNull(key);
        Objects.requireNonNull(frames);
    }

    public record Key(String name, String type) {
        public Key {
            name = SourceNames.canonical(name);
            type = SourceNames.canonical(type);
            if (name.isEmpty() || type.isEmpty()) throw new IllegalArgumentException("Invalid Niagara cache data interface key");
        }
    }

    public sealed interface Frames permits Arrays, Counters, Targets2D, Channels, DynamicMeshes, VolumeTargets {
        int size();
    }

    public record ArrayFrame(boolean present, int[] words) {
        public ArrayFrame {
            words = words.clone();
            if (!present && words.length != 0) throw new IllegalArgumentException("Absent Niagara array contains cached values");
        }
        @Override public int[] words() { return words.clone(); }
    }

    public record Arrays(List<ArrayFrame> cpu, List<ArrayFrame> gpu) implements Frames {
        public Arrays {
            cpu = List.copyOf(cpu);
            gpu = List.copyOf(gpu);
            if (cpu.size() != gpu.size()) throw new IllegalArgumentException("Niagara array cache frame counts differ");
        }
        @Override public int size() { return cpu.size(); }
    }

    public record Counters(int[] cpu, int[] gpu) implements Frames {
        public Counters {
            cpu = cpu.clone();
            gpu = gpu.clone();
            if (cpu.length != gpu.length) throw new IllegalArgumentException("Niagara counter cache frame counts differ");
        }
        @Override public int[] cpu() { return cpu.clone(); }
        @Override public int[] gpu() { return gpu.clone(); }
        @Override public int size() { return cpu.length; }
        public int cpu(int frame) { return cpu[frame]; }
        public int gpu(int frame) { return gpu[frame]; }
    }

    public record Target2D(int format, int width, int height, byte[] pixels) {
        public Target2D {
            pixels = pixels.clone();
            if (format == -1) {
                if (width != 0 || height != 0 || pixels.length != 0)
                    throw new IllegalArgumentException("Absent Niagara render target has data");
            } else {
                int pixelSize = pixelBytes(format);
                if (width < 0 || height < 0 || (width == 0) != (height == 0)
                        || pixels.length != 0 && (long)width * height * pixelSize != pixels.length)
                    throw new IllegalArgumentException("Invalid Niagara cache render target extent");
            }
        }
        @Override public byte[] pixels() { return pixels.clone(); }
        public ByteBuffer pixelData() { return ByteBuffer.wrap(pixels).asReadOnlyBuffer(); }
    }

    static int pixelBytes(int format) {
        return switch (format) {
            case 0 -> 1;
            case 1, 4 -> 2;
            case 2, 3, 5, 7, 10 -> 4;
            case 6, 8 -> 8;
            case 9 -> 16;
            default -> throw new IllegalArgumentException("Invalid Niagara cache target format " + format);
        };
    }

    public record Targets2D(List<Target2D> values) implements Frames {
        public Targets2D { values = List.copyOf(values); }
        @Override public int size() { return values.size(); }
    }

    public record ChannelColumn(Key field, int width, double[] values) {
        public ChannelColumn {
            Objects.requireNonNull(field);
            values = values.clone();
            if (width < 1 || values.length % width != 0)
                throw new IllegalArgumentException("Invalid Niagara cache channel field " + field.name());
        }
        @Override public double[] values() { return values.clone(); }
        public int size() { return values.length / width; }
        public double component(int row, int component) { return values[row * width + component]; }
    }

    public record ChannelFrame(int rows, boolean game, boolean cpu, boolean gpu, boolean rebase, List<ChannelColumn> fields) {
        public ChannelFrame {
            fields = List.copyOf(fields);
            if (rows < 0 || fields.stream().anyMatch(field -> field.size() != rows))
                throw new IllegalArgumentException("Invalid Niagara cache channel row count");
        }
    }

    public record Channels(List<ChannelFrame> values) implements Frames {
        public Channels { values = List.copyOf(values); }
        @Override public int size() { return values.size(); }
    }

    public record DynamicMesh(NiagaraDynamicMeshFrame frame, List<String> materials) {
        public DynamicMesh {
            Objects.requireNonNull(frame);
            materials = List.copyOf(materials);
            if (materials.size() != frame.sections().size()) throw new IllegalArgumentException("Dynamic mesh cache material table differs");
        }
    }

    public record DynamicMeshPair(@Nullable DynamicMesh cpu, @Nullable DynamicMesh gpu) { }

    public record DynamicMeshes(List<DynamicMeshPair> values) implements Frames {
        public DynamicMeshes { values = List.copyOf(values); }
        @Override public int size() { return values.size(); }
    }
    public record VolumeTargets(List<NiagaraCacheVolumeFrame> values) implements Frames {
        public VolumeTargets { values = List.copyOf(values); }
        @Override public int size() { return values.size(); }
    }
}
