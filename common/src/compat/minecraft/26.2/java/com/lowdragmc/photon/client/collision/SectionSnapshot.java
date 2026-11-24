/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.collision;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

/** An immutable copy of one 16³ section: a palette of states with their shapes and an index per cell. */
final class SectionSnapshot {
    static final SectionSnapshot EMPTY = new SectionSnapshot(new BlockState[0], new BlockShape[0], null, null,
            null, false, false);

    final BlockState[] palette;
    final BlockShape[] shapes;
    /** Null when the whole section is one state. */
    @Nullable
    final short[] cells;
    /** Cell indices in this section whose block shape is dynamic; null when none. */
    final int @Nullable [] dynamicCells;
    @Nullable
    private volatile Int2ObjectOpenHashMap<double[]> dynamicBoxes;
    final boolean anyLarge;
    final boolean anyFluid;
    volatile long lastUse;

    private SectionSnapshot(BlockState[] palette, BlockShape[] shapes, @Nullable short[] cells,
                            int @Nullable [] dynamicCells, @Nullable Int2ObjectOpenHashMap<double[]> dynamicBoxes,
                            boolean anyLarge, boolean anyFluid) {
        this.palette = palette;
        this.shapes = shapes;
        this.cells = cells;
        this.dynamicCells = dynamicCells;
        this.dynamicBoxes = dynamicBoxes;
        this.anyLarge = anyLarge;
        this.anyFluid = anyFluid;
    }

    static int cell(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    int paletteIndex(int cell) {
        return cells == null ? 0 : cells[cell];
    }

    boolean hasDynamic() {
        return dynamicCells != null && dynamicCells.length > 0;
    }

    double[] dynamicBoxes(int cell) {
        var map = dynamicBoxes;
        if (map == null) return BlockShape.EMPTY.boxes;
        var boxes = map.get(cell);
        return boxes == null ? BlockShape.EMPTY.boxes : boxes;
    }

    /**
     * Re-evaluates dynamic shapes on the owning game thread and atomically swaps the immutable map if any cell's
     * collision boxes changed (e.g., an opening Shulker box or moving piston block entity).
     */
    boolean refreshDynamic(BlockGetter level, int sectionX, int sectionY, int sectionZ) {
        if (dynamicCells == null || dynamicCells.length == 0) return false;
        var current = dynamicBoxes;
        var next = new Int2ObjectOpenHashMap<double[]>(dynamicCells.length);
        var pos = new BlockPos.MutableBlockPos();
        int baseX = sectionX << 4, baseY = sectionY << 4, baseZ = sectionZ << 4;
        boolean changed = false;
        for (int cell : dynamicCells) {
            int index = paletteIndex(cell);
            pos.set(baseX + (cell & 15), baseY + (cell >> 8), baseZ + (cell >> 4 & 15));
            double[] fresh = BlockShape.at(palette[index], level, pos);
            next.put(cell, fresh);
            if (!changed) {
                double[] previous = current == null ? null : current.get(cell);
                if (previous == null || !Arrays.equals(previous, fresh)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            this.dynamicBoxes = next;
        }
        return changed;
    }

    /** Reads {@code section} when given, otherwise every cell through {@code level}; dynamic shapes always through {@code level}. */
    static SectionSnapshot build(BlockGetter level, int sectionX, int sectionY, int sectionZ,
                                 @Nullable LevelChunkSection section) {
        if (section != null && section.hasOnlyAir()) return EMPTY;
        var indices = new Reference2IntOpenHashMap<BlockState>();
        indices.defaultReturnValue(-1);
        var states = new ArrayList<BlockState>();
        var cells = new short[4096];
        var pos = new BlockPos.MutableBlockPos();
        int baseX = sectionX << 4, baseY = sectionY << 4, baseZ = sectionZ << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    var state = section != null ? section.getBlockState(x, y, z)
                            : level.getBlockState(pos.set(baseX + x, baseY + y, baseZ + z));
                    int index = indices.getInt(state);
                    if (index < 0) {
                        index = states.size();
                        states.add(state);
                        indices.put(state, index);
                    }
                    cells[y << 8 | z << 4 | x] = (short) index;
                }
            }
        }
        var palette = states.toArray(new BlockState[0]);
        var shapes = new BlockShape[palette.length];
        boolean anyLarge = false, anyFluid = false, anySolid = false, anyDynamic = false;
        for (int i = 0; i < palette.length; i++) {
            shapes[i] = BlockShape.of(palette[i]);
            anyLarge |= shapes[i].large;
            anySolid |= !shapes[i].isEmpty();
            anyDynamic |= shapes[i].dynamic;
            anyFluid |= !palette[i].getFluidState().isEmpty();
        }
        if (!anySolid && !anyFluid) return EMPTY;
        Int2ObjectOpenHashMap<double[]> dynamic = null;
        int[] dynamicCells = null;
        if (anyDynamic) {
            int dynamicCount = 0;
            for (int cell = 0; cell < 4096; cell++) {
                if (shapes[cells[cell]].dynamic) dynamicCount++;
            }
            dynamic = new Int2ObjectOpenHashMap<>(dynamicCount);
            dynamicCells = new int[dynamicCount];
            int cursor = 0;
            for (int cell = 0; cell < 4096; cell++) {
                int index = cells[cell];
                if (!shapes[index].dynamic) continue;
                dynamicCells[cursor++] = cell;
                pos.set(baseX + (cell & 15), baseY + (cell >> 8), baseZ + (cell >> 4 & 15));
                dynamic.put(cell, BlockShape.at(palette[index], level, pos));
            }
        }
        return new SectionSnapshot(palette, shapes, palette.length == 1 ? null : cells,
                dynamicCells, dynamic, anyLarge, anyFluid);
    }
}
