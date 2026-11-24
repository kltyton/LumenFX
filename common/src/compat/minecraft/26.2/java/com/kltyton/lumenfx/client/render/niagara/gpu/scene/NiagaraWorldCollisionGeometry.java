package com.kltyton.lumenfx.client.render.niagara.gpu.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

/** Extracts the loaded Minecraft collision scene on its owning client thread. */
final class NiagaraWorldCollisionGeometry {
    private static final int SECTION = SectionPos.SECTION_SIZE;
    private static final double CENTIMETERS = 100;

    record Cell(int x, int y, int z, LevelChunkSection section) {
        long key() { return SectionPos.asLong(x, y, z); }
    }

    private NiagaraWorldCollisionGeometry() { }

    static List<Cell> sections(ClientLevel level, NiagaraVoxelDistanceFieldAtlas.Clipmap clip) {
        double border = clip.pageSize();
        int minX = section(clip.minX() - border), maxX = section(clip.x() + clip.extent() + border);
        int minY = section(clip.minZ() - border), maxY = section(clip.z() + clip.extent() + border);
        int minZ = section(clip.minY() - border), maxZ = section(clip.y() + clip.extent() + border);
        int centerX = section(clip.x()), centerY = section(clip.z()), centerZ = section(clip.y());
        var cells = new ArrayList<Cell>();
        var chunks = level.getChunkSource();
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            var chunk = chunks.getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            var sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                var section = sections[index];
                int y = chunk.getSectionYFromSectionIndex(index);
                if (y >= minY && y <= maxY && !section.hasOnlyAir()) cells.add(new Cell(x, y, z, section));
            }
        }
        cells.sort(Comparator.comparingLong(cell -> square(cell.x() - centerX) + square(cell.y() - centerY) + square(cell.z() - centerZ)));
        return List.copyOf(cells);
    }

    static List<AABB> capture(ClientLevel level, Cell cell) {
        var boxes = new ArrayList<AABB>();
        boolean[] full = new boolean[SECTION * SECTION * SECTION];
        int originX = cell.x() * SECTION, originY = cell.y() * SECTION, originZ = cell.z() * SECTION;
        var position = new BlockPos.MutableBlockPos();
        for (int y = 0; y < SECTION; y++) for (int z = 0; z < SECTION; z++) for (int x = 0; x < SECTION; x++) {
            var state = cell.section().getBlockState(x, y, z);
            if (state.isAir()) continue;
            position.set(originX + x, originY + y, originZ + z);
            var shape = state.getCollisionShape(level, position);
            if (shape.isEmpty()) continue;
            if (shape == Shapes.block()) {
                full[index(x, y, z)] = true;
                continue;
            }
            var bounds = shape.toAabbs();
            if (bounds.size() == 1 && isUnitBlock(bounds.getFirst())) {
                full[index(x, y, z)] = true;
                continue;
            }
            for (var box : bounds) boxes.add(sourceBox(position.getX() + box.minX, position.getY() + box.minY,
                    position.getZ() + box.minZ, position.getX() + box.maxX, position.getY() + box.maxY, position.getZ() + box.maxZ));
        }
        mergeFullBlocks(full, boxes, originX, originY, originZ);
        return List.copyOf(boxes);
    }

    private static void mergeFullBlocks(boolean[] full, List<AABB> boxes, int originX, int originY, int originZ) {
        for (int y = 0; y < SECTION; y++) for (int z = 0; z < SECTION; z++) for (int x = 0; x < SECTION; x++) {
            if (!full[index(x, y, z)]) continue;
            int endX = x + 1, endZ = z + 1, endY = y + 1;
            while (endX < SECTION && full[index(endX, y, z)]) endX++;
            while (endZ < SECTION && rectangle(full, x, endX, z + 1, endZ + 1, y)) endZ++;
            while (endY < SECTION && rectangle(full, x, endX, z, endZ, endY)) endY++;
            for (int rowY = y; rowY < endY; rowY++) for (int rowZ = z; rowZ < endZ; rowZ++) {
                for (int column = x; column < endX; column++) full[index(column, rowY, rowZ)] = false;
            }
            boxes.add(sourceBox(originX + x, originY + y, originZ + z, originX + endX, originY + endY, originZ + endZ));
        }
    }

    private static boolean rectangle(boolean[] full, int x, int endX, int z, int endZ, int y) {
        for (int row = z; row < endZ; row++) for (int column = x; column < endX; column++)
            if (!full[index(column, y, row)]) return false;
        return true;
    }

    private static boolean isUnitBlock(AABB box) {
        return box.minX == 0 && box.minY == 0 && box.minZ == 0 && box.maxX == 1 && box.maxY == 1 && box.maxZ == 1;
    }

    private static AABB sourceBox(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new AABB(x0 * CENTIMETERS, z0 * CENTIMETERS, y0 * CENTIMETERS,
                x1 * CENTIMETERS, z1 * CENTIMETERS, y1 * CENTIMETERS);
    }

    private static int section(double position) { return SectionPos.blockToSectionCoord(position / CENTIMETERS); }
    private static int index(int x, int y, int z) { return (y * SECTION + z) * SECTION + x; }
    private static long square(long value) { return value * value; }
}
